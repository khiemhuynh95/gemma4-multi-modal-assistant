package com.example.voiceassistant

import com.example.voiceassistant.data.ConversationStore
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class VoiceAssistant(
    private val context: android.content.Context,
    private val modelPath: String,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val NEW_CHAT_TITLE = "New chat"
    }

    private lateinit var engine: Engine
    private var conversation: Conversation? = null

    private val kokoroDir = java.io.File(context.filesDir, KokoroTts.MODEL_DIR_NAME)
    private var kokoro: KokoroTts? = null

    // Local-first conversation history. `history` is the in-memory source for display (updated
    // synchronously so finalizing a turn is flicker-free); `store` is the durable Room backing.
    private val store = ConversationStore(context)
    private val history = mutableListOf<Message>()
    @Volatile private var activeConversationId: Long = 0L

    // Media staged by the user (copied to app-private storage), sent with the next turn.
    @Volatile private var pendingAttachment: Attachment? = null

    // Per-turn bookkeeping so a turn is persisted once, after both generation and speech finish.
    @Volatile private var currentUserText: String? = null
    @Volatile private var currentTurnConversationId: Long = 0L
    private val currentAssistantFull = StringBuilder()
    @Volatile private var generationComplete = false

    // Maps an in-flight utterance id to its sentence text so the on-screen transcript
    // can be revealed in lockstep with the voice (instead of dumping the full LLM output).
    private val pendingTexts = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val spokenDisplay = StringBuilder()

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val tts: android.speech.tts.TextToSpeech = run {
        val googleTtsPackage = "com.google.android.tts"
        val isGoogleTtsInstalled = try {
            context.packageManager.getPackageInfo(googleTtsPackage, 0)
            true
        } catch (e: Exception) {
            false
        }
        
        val listener = android.speech.tts.TextToSpeech.OnInitListener { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                this@VoiceAssistant.tts.setSpeechRate(1.1f)
                selectBestVoice()
            } else {
                android.util.Log.e("VoiceAssistant", "TTS Initialization failed")
            }
        }
        
        if (isGoogleTtsInstalled) {
            android.util.Log.i("VoiceAssistant", "Initializing TextToSpeech with Google TTS engine")
            android.speech.tts.TextToSpeech(context, listener, googleTtsPackage)
        } else {
            android.util.Log.i("VoiceAssistant", "Initializing TextToSpeech with default system engine")
            android.speech.tts.TextToSpeech(context, listener)
        }
    }

    private val engineProcessing = AtomicBoolean(false)
    private val pendingUtterances = AtomicInteger(0)
    private var turnJob: Job? = null
    private var startJob: Job? = null
    private var isInitialized = false
    private var isStarted = false

    init {
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { handleUtteranceStart(utteranceId ?: "") }

            override fun onDone(utteranceId: String?) { handleUtteranceDone(utteranceId ?: "") }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { handleUtteranceDone(utteranceId ?: "") }
        })
        
        // Initial check for model and STT
        updateModelAvailability()

        // Restore the most-recent conversation (creating an empty one if there are none).
        scope.launch {
            try {
                var convos = withContext(Dispatchers.IO) { store.loadConversations() }
                if (convos.isEmpty()) {
                    withContext(Dispatchers.IO) { store.createConversation(NEW_CHAT_TITLE) }
                    convos = withContext(Dispatchers.IO) { store.loadConversations() }
                }
                activateConversation(convos.first().id)
            } catch (e: Exception) {
                android.util.Log.e("VoiceAssistant", "Failed to load conversations", e)
            }
        }
    }

    fun setPermissionGranted(granted: Boolean) {
        _uiState.update { it.copy(isPermissionGranted = granted) }
    }

    /**
     * Copy a picked media [uri] into app-private storage and stage it for the next turn. The runtime
     * reads media from a file path and the picker's read grant is transient, so we copy immediately.
     */
    fun attachMedia(uri: android.net.Uri, kind: AttachmentKind, extension: String) {
        scope.launch {
            try {
                val path = withContext(Dispatchers.IO) { store.saveMedia(uri, extension) }
                pendingAttachment = Attachment(path, kind)
                _uiState.update { it.copy(pendingAttachment = pendingAttachment, error = null) }
            } catch (e: Exception) {
                android.util.Log.e("VoiceAssistant", "Failed to attach media", e)
                _uiState.update { it.copy(error = "Couldn't attach that file.") }
            }
        }
    }

    /** Discard the staged attachment before it's sent. */
    fun clearPendingAttachment() {
        pendingAttachment = null
        _uiState.update { it.copy(pendingAttachment = null) }
    }

    private fun defaultPromptFor(attachment: Attachment?): String = when (attachment?.kind) {
        AttachmentKind.IMAGE -> "What's in this image?"
        AttachmentKind.AUDIO -> "What's in this audio?"
        else -> ""
    }

    fun updateModelAvailability() {
        val exists = java.io.File(modelPath).exists()
        _uiState.update { it.copy(isModelAvailable = exists) }
    }

    fun setDownloading(isDownloading: Boolean, progress: Float = 0f) {
        _uiState.update { it.copy(isDownloading = isDownloading, downloadProgress = progress) }
    }

    fun setKokoroDownloading(isDownloading: Boolean, progress: Float = 0f) {
        _uiState.update { it.copy(isKokoroDownloading = isDownloading, kokoroDownloadProgress = progress) }
    }

    /** Live speech-rate control. Applies to whichever engine is active; takes effect on
     *  the next spoken sentence. */
    fun setSpeechSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.5f)
        kokoro?.setSpeed(clamped)
        tts.setSpeechRate(clamped)
        _uiState.update { it.copy(kokoroSpeed = clamped) }
    }

    private fun handleUtteranceStart(utteranceId: String) {
        // Reveal this sentence's text exactly as the voice starts speaking it.
        val sentence = pendingTexts[utteranceId]
        val display = synchronized(spokenDisplay) {
            if (sentence != null) {
                if (spokenDisplay.isNotEmpty()) spokenDisplay.append(' ')
                spokenDisplay.append(sentence)
            }
            spokenDisplay.toString()
        }
        _uiState.update { it.copy(isSpeaking = true, assistantResponse = display) }
    }

    private fun handleUtteranceDone(utteranceId: String) {
        pendingTexts.remove(utteranceId)
        if (pendingUtterances.decrementAndGet() <= 0) {
            _uiState.update { it.copy(isSpeaking = false) }
            // Speech drained — persist the turn if generation has also finished.
            maybeFinalizeTurn()
        }
    }

    /**
     * Load the Kokoro neural voice if its model has been downloaded. Heavy (loads a
     * ~310 MB ONNX model), so callers must invoke this off the main thread. Safe to call
     * repeatedly; it's a no-op once loaded. Falls back silently to the system TTS engine.
     */
    fun refreshTtsEngine() {
        if (kokoro != null) return
        if (!java.io.File(kokoroDir, "model.onnx").exists()) return
        try {
            val k = KokoroTts(kokoroDir)
            k.setSpeed(_uiState.value.kokoroSpeed)
            k.listener = object : KokoroTts.Listener {
                override fun onStart(utteranceId: String) = handleUtteranceStart(utteranceId)
                override fun onDone(utteranceId: String) = handleUtteranceDone(utteranceId)
                override fun onError(utteranceId: String) = handleUtteranceDone(utteranceId)
            }
            kokoro = k
            val voices = (0 until k.numSpeakers).map { "Speaker $it" }
            _uiState.update {
                it.copy(
                    ttsEngine = "Kokoro (neural)",
                    isKokoroAvailable = true,
                    isTtsVoiceDataAvailable = true,
                    availableVoices = voices,
                    selectedVoiceName = voices.firstOrNull() ?: ""
                )
            }
            android.util.Log.i("VoiceAssistant", "Kokoro TTS ready: ${k.numSpeakers} speakers @ ${k.sampleRate}Hz")
        } catch (e: Throwable) {
            android.util.Log.e("VoiceAssistant", "Failed to init Kokoro; using system TTS", e)
            kokoro = null
            _uiState.update { it.copy(isKokoroAvailable = false, ttsEngine = "System") }
        }
    }

    fun setError(message: String?) {
        _uiState.update { it.copy(error = message) }
    }

    private fun isSpeaking(): Boolean = engineProcessing.get() || pendingUtterances.get() > 0

    suspend fun initialize() = withContext(Dispatchers.Default) {
        if (isInitialized) return@withContext
        
        // Prefer the offline Kokoro neural voice when its model is present.
        refreshTtsEngine()

        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(context)) {
            _uiState.update { it.copy(error = "Speech recognition is not available on this device.") }
            return@withContext
        }

        if (kokoro == null) {
            val defaultLocale = java.util.Locale.getDefault()
            val ttsAvailability = tts.isLanguageAvailable(defaultLocale)
            var isTtsDataAvailable = ttsAvailability != android.speech.tts.TextToSpeech.LANG_MISSING_DATA &&
                                     ttsAvailability != android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED

            if (!isTtsDataAvailable) {
                try {
                    val voices = tts.voices
                    if (voices != null && voices.any { it.locale.language == defaultLocale.language }) {
                        isTtsDataAvailable = true
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
            _uiState.update { it.copy(isTtsVoiceDataAvailable = isTtsDataAvailable) }
        }

        val file = java.io.File(modelPath)
        if (!file.exists()) {
            _uiState.update { it.copy(isModelAvailable = false) }
            return@withContext
        }

        _uiState.update { it.copy(assistantResponse = "Initializing Gemma engine...", isModelAvailable = true) }

        try {
            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
            var initialized = false
            
            // Try GPU backend first
            try {
                engine = Engine(
                    EngineConfig(
                        modelPath     = modelPath,
                        backend       = Backend.GPU(),
                        // Enable the on-demand vision + audio sub-models for media attachments.
                        visionBackend = Backend.GPU(),
                        audioBackend  = Backend.GPU(),
                        maxNumImages  = 1,
                        cacheDir = context.cacheDir.path
                    )
                )
                engine.initialize()
                initialized = true
                android.util.Log.i("VoiceAssistant", "Gemma engine initialized successfully with GPU backend")
            } catch (e: Exception) {
                android.util.Log.w("VoiceAssistant", "Failed to initialize with GPU backend, falling back to CPU", e)
            }
            
            // Fallback to CPU backend if GPU failed
            if (!initialized) {
                engine = Engine(
                    EngineConfig(
                        modelPath     = modelPath,
                        backend       = Backend.CPU(),
                        visionBackend = Backend.CPU(),
                        audioBackend  = Backend.CPU(),
                        maxNumImages  = 1,
                        cacheDir = context.cacheDir.path
                    )
                )
                engine.initialize()
                android.util.Log.i("VoiceAssistant", "Gemma engine initialized successfully with CPU backend")
            }

            conversation = engine.createConversation(conversationConfig())
            isInitialized = true
            _uiState.update { it.copy(assistantResponse = "Ready! Tap Start.") }
        } catch (e: Exception) {
            android.util.Log.e("VoiceAssistant", "Initialization failed", e)
            _uiState.update { it.copy(error = "Engine Init Failed: ${e.message}") }
        }
    }

    fun start() {
        if (isStarted || !isInitialized) return
        isStarted = true
        _uiState.update { it.copy(isActive = true, error = null) }
        startJob = scope.launch {
            try {
                while (isActive) {
                    while (isSpeaking()) {
                        delay(500)
                    }
                    
                    val utterance = listenForUtterance()
                    if (utterance.isNotBlank()) {
                        beginTurn(utterance)
                    } else {
                        delay(500)
                    }
                }
            } finally {
                isStarted = false
                _uiState.update { it.copy(isActive = false, isListening = false) }
            }
        }
    }

    fun stop() {
        startJob?.cancel()
        stopSpeaking()
    }

    fun reset() {
        stop()
        _uiState.update { AssistantUiState(isModelAvailable = true, isPermissionGranted = it.isPermissionGranted) }
        // Note: Conversation reset would require recreating the conversation object if needed
    }

    private suspend fun listenForUtterance(): String = withContext(Dispatchers.Main) {
        _uiState.update { it.copy(isListening = true) }
        val result = suspendCancellableCoroutine { continuation ->
            val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(context)
            val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            val listener = object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (continuation.isActive) continuation.resume("")
                    recognizer.destroy()
                }
                override fun onResults(results: android.os.Bundle?) {
                    val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (continuation.isActive) continuation.resume(text)
                    recognizer.destroy()
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            }

            recognizer.setRecognitionListener(listener)
            recognizer.startListening(intent)

            continuation.invokeOnCancellation {
                recognizer.stopListening()
                recognizer.cancel()
                recognizer.destroy()
            }
        }
        _uiState.update { it.copy(isListening = false) }
        result
    }

    private fun conversationConfig() = ConversationConfig(
        systemInstruction = Contents.of(
            "You are Alex, a warm, helpful, calm, and reassuring voice assistant. " +
            "Write your response exactly how it should be spoken out loud. " +
            "Always use common contractions (e.g. say 'I'm', 'don't', 'it's', 'we're', 'can't' instead of full words). " +
            "Keep your sentences very short and conversational (no more than 15-20 words per sentence, one or two sentences total). " +
            "Do not use markdown, asterisks, bold, or lists. " +
            "Write out all numbers, dates, times, and abbreviations fully as they sound (e.g. 'one hundred' instead of '100', 'June seventeenth' instead of 'June 17', 'three thirty p m' instead of '3:30 PM', 'doctor' instead of 'Dr.'). " +
            "Include short thinking pauses or filler words (like 'uh', 'well', 'um', 'let's see') naturally, but very sparingly (no more than once per response). " +
            "Vary your opening acknowledgements (avoid starting with 'Sure', 'Okay', or 'Got it' repeatedly). " +
            "End turns with a simple, natural follow-up question."
        ),
        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
    )

    /** Recreate the model session so context doesn't bleed across conversations. */
    private fun resetModelSession() {
        if (!isInitialized) return
        try {
            conversation?.close()
            conversation = engine.createConversation(conversationConfig())
        } catch (e: Exception) {
            android.util.Log.e("VoiceAssistant", "Failed to reset model session", e)
        }
    }

    /** Make [id] the open conversation: load its turns into the thread and refresh the drawer. */
    private suspend fun activateConversation(id: Long) {
        activeConversationId = id
        val turns = withContext(Dispatchers.IO) { store.loadTurns(id) }
        history.clear()
        turns.forEach { t ->
            val attachment = t.mediaPath?.let { path ->
                val kind = runCatching { AttachmentKind.valueOf(t.mediaKind ?: "") }.getOrNull()
                kind?.let { Attachment(path, it) }
            }
            history.add(
                Message(
                    id = t.id,
                    role = if (t.role == "user") Role.USER else Role.ASSISTANT,
                    text = t.text,
                    attachment = attachment
                )
            )
        }
        val convos = withContext(Dispatchers.IO) { store.loadConversations() }
        _uiState.update {
            it.copy(
                messages = history.toList(),
                conversations = convos.map { c -> ConversationSummary(c.id, c.title) },
                activeConversationId = id,
                lastUserUtterance = "",
                assistantResponse = ""
            )
        }
    }

    /** Refresh just the drawer list (order + titles) without touching the open thread. */
    private fun refreshConversations() {
        scope.launch {
            try {
                val convos = withContext(Dispatchers.IO) { store.loadConversations() }
                _uiState.update {
                    it.copy(conversations = convos.map { c -> ConversationSummary(c.id, c.title) })
                }
            } catch (e: Exception) {
                android.util.Log.e("VoiceAssistant", "Failed to refresh conversations", e)
            }
        }
    }

    /** Start a fresh, empty conversation and open it. */
    fun newConversation() {
        scope.launch {
            stopSpeaking()
            resetModelSession()
            val id = withContext(Dispatchers.IO) { store.createConversation(NEW_CHAT_TITLE) }
            activateConversation(id)
        }
    }

    /** Open an existing conversation. */
    fun switchConversation(id: Long) {
        if (id == activeConversationId) return
        scope.launch {
            stopSpeaking()
            resetModelSession()
            activateConversation(id)
        }
    }

    /** Delete a conversation; if it was open, fall back to the most-recent remaining one. */
    fun deleteConversation(id: Long) {
        scope.launch {
            withContext(Dispatchers.IO) { store.deleteConversation(id) }
            var convos = withContext(Dispatchers.IO) { store.loadConversations() }
            if (convos.isEmpty()) {
                withContext(Dispatchers.IO) { store.createConversation(NEW_CHAT_TITLE) }
                convos = withContext(Dispatchers.IO) { store.loadConversations() }
            }
            if (id == activeConversationId) {
                stopSpeaking()
                resetModelSession()
                activateConversation(convos.first().id)
            } else {
                _uiState.update {
                    it.copy(conversations = convos.map { c -> ConversationSummary(c.id, c.title) })
                }
            }
        }
    }

    /** Title an untitled chat from its first user message. */
    private fun maybeTitleConversation(conversationId: Long, firstUserText: String) {
        val title = firstUserText.lineSequence().firstOrNull()?.trim().orEmpty()
            .let { if (it.length > 40) it.take(40).trimEnd() + "…" else it }
            .ifBlank { NEW_CHAT_TITLE }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.renameConversation(conversationId, title) }
                refreshConversations()
            } catch (e: Exception) {
                android.util.Log.e("VoiceAssistant", "Failed to title conversation", e)
            }
        }
    }

    /**
     * Run a one-off text turn (typed input). Independent of the voice loop; ignored if the engine
     * isn't ready or another turn is mid-flight.
     */
    fun submitText(text: String) {
        val trimmed = text.trim()
        val attachment = pendingAttachment
        // A turn needs either text or an attachment (an attachment alone uses a default prompt).
        if (trimmed.isEmpty() && attachment == null) return
        if (!isInitialized) {
            _uiState.update { it.copy(error = "The assistant is still starting up. One moment…") }
            return
        }
        if (currentUserText != null || isSpeaking()) return
        beginTurn(trimmed, attachment)
    }

    /** Persist the user message, show a thinking placeholder, and kick off inference. */
    private fun beginTurn(userText: String, attachment: Attachment? = null) {
        // The model always gets a non-blank instruction; the displayed bubble may be image-only.
        val prompt = userText.ifBlank { defaultPromptFor(attachment) }
        currentUserText = prompt
        currentTurnConversationId = activeConversationId
        currentAssistantFull.setLength(0)
        generationComplete = false

        // First user message in a chat names it.
        val isFirstUserMessage = history.none { it.role == Role.USER }

        appendMessage(Role.USER, userText, attachment)
        pendingAttachment = null
        // "…" is the thinking placeholder; the lockstep reveal overwrites it once speech starts.
        _uiState.update {
            it.copy(lastUserUtterance = userText, assistantResponse = "…", error = null, pendingAttachment = null)
        }

        if (isFirstUserMessage) {
            maybeTitleConversation(currentTurnConversationId, prompt)
        }

        handleTurn(prompt, attachment)
    }

    /** Append to the in-memory thread (synchronous, drives the UI) and persist durably. */
    private fun appendMessage(role: Role, text: String, attachment: Attachment? = null) {
        val message = Message(id = System.nanoTime(), role = role, text = text, attachment = attachment)
        history.add(message)
        _uiState.update { it.copy(messages = history.toList()) }
        val roleTag = if (role == Role.USER) "user" else "assistant"
        val conversationId = currentTurnConversationId
        scope.launch(Dispatchers.IO) {
            try {
                store.append(conversationId, roleTag, text, attachment?.path, attachment?.kind?.name)
            } catch (e: Exception) {
                android.util.Log.e("VoiceAssistant", "Failed to persist $roleTag message", e)
            }
        }
    }

    /**
     * Finalize the active turn once generation has finished AND all speech has drained. Persists the
     * assistant message and clears the live streaming bubble in one atomic UI update (no flicker).
     */
    @Synchronized
    private fun maybeFinalizeTurn() {
        if (!generationComplete) return
        if (pendingUtterances.get() > 0) return
        val user = currentUserText ?: return  // already finalized / no active turn
        currentUserText = null

        val full = currentAssistantFull.toString().trim()
        val conversationId = currentTurnConversationId
        if (full.isNotEmpty()) {
            val message = Message(id = System.nanoTime(), role = Role.ASSISTANT, text = full)
            history.add(message)
            scope.launch(Dispatchers.IO) {
                try {
                    store.append(conversationId, "assistant", full)
                } catch (e: Exception) {
                    android.util.Log.e("VoiceAssistant", "Failed to persist assistant message", e)
                }
            }
        }
        _uiState.update {
            it.copy(messages = history.toList(), assistantResponse = "", isSpeaking = false)
        }
        // Bubble the just-updated conversation to the top of the drawer.
        refreshConversations()
    }

    /** Empty the active conversation (memory + storage), keeping it as a fresh chat. */
    fun clearHistory() {
        val id = activeConversationId
        scope.launch {
            stopSpeaking()
            resetModelSession()
            currentUserText = null
            history.clear()
            _uiState.update { it.copy(messages = emptyList(), lastUserUtterance = "", assistantResponse = "") }
            try {
                withContext(Dispatchers.IO) {
                    store.clearTurns(id)
                    store.renameConversation(id, NEW_CHAT_TITLE)
                }
                refreshConversations()
            } catch (e: Exception) {
                android.util.Log.e("VoiceAssistant", "Failed to clear conversation", e)
            }
        }
    }

    private fun handleTurn(userText: String, attachment: Attachment? = null) {
        val convo = conversation ?: return
        engineProcessing.set(true)

        // Reset the synced transcript for this turn; text is revealed as it's spoken.
        synchronized(spokenDisplay) { spokenDisplay.setLength(0) }
        pendingTexts.clear()

        turnJob = scope.launch {
            val sentenceBuffer = StringBuilder()
            var firstAudioEmitted = false
            try {
                // Media before text (model-card requirement); the vision/audio sub-model maps in on
                // first use. Plain text turns keep the lighter String overload.
                val responseStream = if (attachment != null) {
                    val media: Content = when (attachment.kind) {
                        AttachmentKind.IMAGE -> Content.ImageFile(attachment.path)
                        AttachmentKind.AUDIO -> Content.AudioFile(attachment.path)
                    }
                    convo.sendMessageAsync(Contents.of(media, Content.Text(userText)))
                } else {
                    convo.sendMessageAsync(userText)
                }
                responseStream
                    .collect { chunk ->
                        val piece = chunk.toString()
                        sentenceBuffer.append(piece)
                        currentAssistantFull.append(piece)
                        firstAudioEmitted = flushCompletedSentences(sentenceBuffer, firstAudioEmitted)
                    }

                if (sentenceBuffer.isNotBlank()) {
                    speak(sentenceBuffer.toString())
                }
            } catch (e: Exception) {
                android.util.Log.e("VoiceAssistant", "Error in turn handling", e)
            } finally {
                engineProcessing.set(false)
                // Generation done; finalize now if speech has already drained (e.g. empty reply),
                // otherwise the last utterance's onDone will trigger it.
                generationComplete = true
                maybeFinalizeTurn()
            }
        }
    }

    private suspend fun flushCompletedSentences(buf: StringBuilder, firstAudioEmitted: Boolean): Boolean {
        var emitted = firstAudioEmitted
        var idx = findSentenceEnd(buf, emitted)
        while (idx >= 0) {
            val sentence = buf.substring(0, idx + 1).trim()
            buf.delete(0, idx + 1)
            if (sentence.isNotEmpty()) {
                speak(sentence)
                emitted = true
            }
            idx = findSentenceEnd(buf, emitted)
        }
        return emitted
    }

    private fun findSentenceEnd(buf: StringBuilder, firstAudioEmitted: Boolean): Int {
        // Strong sentence ending delimiters: always split immediately
        val strongDelimiters = listOf('.', '!', '?', '\n')
        val strongIdx = strongDelimiters.map { buf.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1
        if (strongIdx >= 0) return strongIdx

        // Semicolons and colons: clear boundary delimiters
        val midDelimiters = listOf(';', ':')
        val midIdx = midDelimiters.map { buf.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1
        if (midIdx >= 0) return midIdx

        // Commas: soft flush only once we have a reasonable phrase, to avoid choppy
        // fragments. Before the first audio of a turn we use a shorter threshold so the
        // user hears something as early as possible (time-to-first-audio), then relax it.
        val commaThreshold = if (firstAudioEmitted) 30 else 15
        val commaIdx = buf.indexOf(',')
        if (commaIdx >= commaThreshold) {
            return commaIdx
        }

        return -1
    }

    private suspend fun speak(sentence: String) = withContext(Dispatchers.Main) {
        val count = pendingUtterances.incrementAndGet()
        val utteranceId = "utt_${System.currentTimeMillis()}_$count"
        // Remember the text so it can be shown when this utterance starts speaking.
        pendingTexts[utteranceId] = sentence
        val k = kokoro
        if (k != null) {
            k.speak(sentence, utteranceId)
        } else {
            tts.speak(sentence, android.speech.tts.TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    private fun stopSpeaking() {
        engineProcessing.set(false)
        pendingUtterances.set(0)
        pendingTexts.clear()
        _uiState.update { it.copy(isSpeaking = false) }
        turnJob?.cancel()
        kokoro?.stop()
        tts.stop()
        // Persist whatever the assistant produced before the barge-in, then close out the turn.
        generationComplete = true
        maybeFinalizeTurn()
    }

    fun interrupt() {
        stopSpeaking()
    }

    fun refreshVoiceSelection() {
        // Kokoro manages its own speaker list; nothing to refresh from the system engine.
        if (kokoro != null) return

        val defaultLocale = java.util.Locale.getDefault()
        val ttsAvailability = tts.isLanguageAvailable(defaultLocale)
        var isTtsDataAvailable = ttsAvailability != android.speech.tts.TextToSpeech.LANG_MISSING_DATA && 
                                 ttsAvailability != android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
        
        if (!isTtsDataAvailable) {
            try {
                val voices = tts.voices
                if (voices != null && voices.any { it.locale.language == defaultLocale.language }) {
                    isTtsDataAvailable = true
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        _uiState.update { it.copy(isTtsVoiceDataAvailable = isTtsDataAvailable) }
        
        selectBestVoice()
    }

    private fun selectBestVoice() {
        try {
            val defaultLocale = java.util.Locale.getDefault()
            val voices = tts.voices
            if (voices != null && voices.isNotEmpty()) {
                var bestVoice: android.speech.tts.Voice? = null
                var highestScore = -99999
                
                for (voice in voices) {
                    if (voice.locale.language == defaultLocale.language) {
                        var score = voice.quality * 10
                        
                        if (voice.locale.country == defaultLocale.country) {
                            score += 2000
                        }
                        
                        val name = voice.name.lowercase()
                        if (name.contains("local") || name.contains("neural") || name.contains("wavenet")) {
                            score += 1000
                        }
                        
                        if (!voice.isNetworkConnectionRequired) {
                            score += 500
                        }
                        
                        if (score > highestScore) {
                            highestScore = score
                            bestVoice = voice
                        }
                    }
                }
                
                if (bestVoice != null) {
                    tts.voice = bestVoice
                    android.util.Log.i("VoiceAssistant", "Selected high-quality voice: ${bestVoice.name} (quality: ${bestVoice.quality})")
                }
            }
            // Note: the system engine still auto-selects its best voice for fallback
            // playback, but its voice list is intentionally not exposed in the selector.
            // The voice selector only lists Kokoro speakers (see refreshTtsEngine).
        } catch (e: Exception) {
            android.util.Log.w("VoiceAssistant", "Could not customize TTS voice selection", e)
        }
    }

    fun selectVoiceByName(name: String) {
        // Only Kokoro speakers are user-selectable; the system engine self-selects.
        val k = kokoro ?: return
        val sid = name.removePrefix("Speaker ").trim().toIntOrNull() ?: return
        k.setVoice(sid)
        _uiState.update { it.copy(selectedVoiceName = name) }
        android.util.Log.i("VoiceAssistant", "Selected Kokoro speaker $sid")
    }

    fun close() {
        stop()
        conversation?.close()
        if (::engine.isInitialized) {
            engine.close()
        }
        kokoro?.shutdown()
        tts.shutdown()
        store.close()
    }
}
