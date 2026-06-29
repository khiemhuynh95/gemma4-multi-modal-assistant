package com.example.voiceassistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL

class AssistantViewModel(application: Application) : AndroidViewModel(application) {
    private val assistant = VoiceAssistant(
        context = application,
        modelPath = File(application.filesDir, "model.litertlm").absolutePath,
        scope = viewModelScope
    )

    val uiState: StateFlow<AssistantUiState> = assistant.uiState

    init {
        assistant.updateModelAvailability()
    }

    fun checkModel() {
        assistant.updateModelAvailability()
        assistant.refreshVoiceSelection()
        // If the model was just pushed, we might need to initialize it
        if (uiState.value.isModelAvailable && uiState.value.isPermissionGranted) {
            onPermissionGranted()
        }
    }

    fun downloadModel(token: String, customUrl: String? = null) {
        val url = customUrl ?: "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        val destinationFile = File(getApplication<Application>().filesDir, "model.litertlm")
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                assistant.setError(null)
                assistant.setDownloading(true, 0f)
                
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                if (token.isNotBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer $token")
                }
                
                val responseCode = connection.responseCode
                if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
                    throw Exception("Server returned HTTP $responseCode: ${connection.responseMessage}")
                }
                
                // contentLengthLong (not contentLength: Int overflows for files > 2 GB and returns
                // -1, which previously hid both the progress bar AND any completeness check — a
                // dropped connection then saved a truncated model that fails to load).
                val length = connection.contentLengthLong
                val input = connection.inputStream
                val output = FileOutputStream(destinationFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    if (length > 0) {
                        assistant.setDownloading(true, totalBytesRead.toFloat() / length)
                    }
                }

                output.close()
                input.close()

                // Reject an incomplete download instead of saving a broken model that fails at init.
                if (length > 0 && totalBytesRead != length) {
                    destinationFile.delete()
                    throw Exception("Incomplete download: got $totalBytesRead of $length bytes. Check your connection and try again.")
                }

                assistant.setDownloading(false)
                assistant.updateModelAvailability()

                if (uiState.value.isPermissionGranted) {
                    onPermissionGranted()
                }
            } catch (e: Exception) {
                android.util.Log.e("AssistantViewModel", "Download failed", e)
                assistant.setDownloading(false)
                assistant.setError("Download failed: ${e.message}")
            }
        }
    }

    fun downloadKokoro() {
        val filesDir = getApplication<Application>().filesDir
        val modelDir = File(filesDir, KokoroTts.MODEL_DIR_NAME)

        viewModelScope.launch(Dispatchers.IO) {
            // Already fully extracted? Just (re)load the engine. A *partial* install (e.g. an
            // interrupted prior download) falls through and re-downloads — checking only model.onnx
            // would leave a broken voice that crashes sherpa-onnx on use.
            if (KokoroTts.isModelComplete(modelDir)) {
                assistant.refreshTtsEngine()
                return@launch
            }

            val tmp = File(getApplication<Application>().cacheDir, "kokoro.tar.bz2")
            try {
                assistant.setError(null)
                assistant.setKokoroDownloading(true, 0f)

                val connection = URL(KokoroTts.MODEL_URL).openConnection() as java.net.HttpURLConnection
                connection.instanceFollowRedirects = true
                val responseCode = connection.responseCode
                if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
                    throw Exception("Server returned HTTP $responseCode: ${connection.responseMessage}")
                }

                val length = connection.contentLengthLong
                connection.inputStream.use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var totalBytesRead = 0L
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            if (length > 0) {
                                // Reserve the last 10% of the bar for extraction.
                                assistant.setKokoroDownloading(true, (totalBytesRead.toFloat() / length) * 0.9f)
                            }
                        }
                    }
                }

                assistant.setKokoroDownloading(true, 0.92f)
                extractTarBz2(tmp, filesDir)
                tmp.delete()

                if (!File(modelDir, "model.onnx").exists()) {
                    throw Exception("Extraction completed but model files are missing.")
                }

                assistant.setKokoroDownloading(false, 1f)
                assistant.refreshTtsEngine()
            } catch (e: Exception) {
                android.util.Log.e("AssistantViewModel", "Kokoro download failed", e)
                tmp.delete()
                assistant.setKokoroDownloading(false)
                assistant.setError("Voice download failed: ${e.message}")
            }
        }
    }

    private fun extractTarBz2(archive: File, destDir: File) {
        val destPrefix = destDir.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                // Guard against path traversal in archive entries.
                if (!outFile.canonicalPath.startsWith(destPrefix)) {
                    throw java.io.IOException("Refusing to extract entry outside target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                }
                entry = tar.nextEntry
            }
        }
    }

    fun startAssistant() {
        assistant.start()
    }

    fun stopAssistant() {
        assistant.stop()
    }

    fun interrupt() {
        assistant.interrupt()
    }

    fun submitText(text: String) {
        assistant.submitText(text)
    }

    /** Stage a picked image (Photo Picker) for the next turn. */
    fun attachImage(uri: android.net.Uri) = attach(uri, AttachmentKind.IMAGE)

    /** Stage a picked audio file (document picker) for the next turn. */
    fun attachAudio(uri: android.net.Uri) = attach(uri, AttachmentKind.AUDIO)

    private fun attach(uri: android.net.Uri, kind: AttachmentKind) {
        val resolver = getApplication<Application>().contentResolver
        val ext = android.webkit.MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(resolver.getType(uri))
            ?: if (kind == AttachmentKind.IMAGE) "jpg" else "mp3"
        assistant.attachMedia(uri, kind, ext)
    }

    fun clearAttachment() {
        assistant.clearPendingAttachment()
    }

    fun clearHistory() {
        assistant.clearHistory()
    }

    /** Create or update a custom instruction skill, then re-apply to the live model session. */
    fun saveInstructionSkill(skill: com.example.voiceassistant.tools.InstructionSkill) {
        assistant.saveInstructionSkill(skill)
    }

    fun deleteInstructionSkill(id: Long) {
        assistant.deleteInstructionSkill(id)
    }

    fun setInstructionSkillEnabled(id: Long, enabled: Boolean) {
        assistant.setInstructionSkillEnabled(id, enabled)
    }

    // --- MCP servers ---

    fun saveMcpServer(server: com.example.voiceassistant.tools.McpServer) {
        assistant.saveMcpServer(server)
    }

    fun deleteMcpServer(id: Long) {
        assistant.deleteMcpServer(id)
    }

    fun setMcpServerEnabled(id: Long, enabled: Boolean) {
        assistant.setMcpServerEnabled(id, enabled)
    }

    fun refreshMcpServers() {
        assistant.refreshMcpServers()
    }

    fun newConversation() {
        assistant.newConversation()
    }

    fun switchConversation(id: Long) {
        assistant.switchConversation(id)
    }

    fun deleteConversation(id: Long) {
        assistant.deleteConversation(id)
    }

    fun installVoiceData() {
        try {
            val intent = android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getApplication<android.app.Application>().startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("AssistantViewModel", "Failed to launch TTS install intent", e)
        }
    }

    fun selectVoice(name: String) {
        assistant.selectVoiceByName(name)
    }

    fun setSpeechSpeed(speed: Float) {
        assistant.setSpeechSpeed(speed)
    }

    fun onPermissionResult(isGranted: Boolean) {
        assistant.setPermissionGranted(isGranted)
        if (isGranted) {
            viewModelScope.launch {
                try {
                    assistant.initialize()
                } catch (e: Exception) {
                    android.util.Log.e("AssistantViewModel", "Failed to initialize assistant", e)
                    assistant.setError("Initialization failed: ${e.message}")
                }
            }
        } else {
            assistant.setError("Recording permission is required for the voice assistant.")
        }
    }

    fun onPermissionGranted() {
        onPermissionResult(true)
    }

    override fun onCleared() {
        super.onCleared()
        assistant.close()
    }
}
