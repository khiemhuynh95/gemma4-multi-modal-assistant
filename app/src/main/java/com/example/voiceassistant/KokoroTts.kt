package com.example.voiceassistant

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Offline neural text-to-speech backed by sherpa-onnx + the Kokoro model.
 *
 * Audio is synthesized on a single background thread and streamed into an
 * [AudioTrack] as it is produced (via [OfflineTts.generateWithCallback]) so the
 * first words start playing before the whole sentence has been rendered.
 *
 * The callback/queue contract mirrors Android's [android.speech.tts.UtteranceProgressListener]
 * so it drops in behind the same accounting the rest of the app already uses.
 */
class KokoroTts(modelDir: File, numThreads: Int = 4) {

    interface Listener {
        fun onStart(utteranceId: String)
        fun onDone(utteranceId: String)
        fun onError(utteranceId: String)
    }

    @Volatile
    var listener: Listener? = null

    private val tts: OfflineTts
    val sampleRate: Int
    val numSpeakers: Int

    @Volatile private var speakerId: Int = 0
    @Volatile private var speed: Float = 1.0f

    private val executor = Executors.newSingleThreadExecutor()
    private val queued = AtomicInteger(0)

    /** Bumped by [stop]; any in-flight generation/playback for an older epoch aborts. */
    @Volatile private var cancelEpoch = 0

    private var track: AudioTrack? = null
    private var framesWritten = 0

    init {
        fun path(name: String) = File(modelDir, name).absolutePath
        fun has(name: String) = File(modelDir, name).exists()

        val lexicons = listOf("lexicon-us-en.txt", "lexicon-zh.txt")
            .filter { has(it) }
            .joinToString(",") { path(it) }
        val ruleFsts = listOf("date-zh.fst", "number-zh.fst", "phone-zh.fst")
            .filter { has(it) }
            .joinToString(",") { path(it) }

        val kokoro = OfflineTtsKokoroModelConfig(
            model = path("model.onnx"),
            voices = path("voices.bin"),
            tokens = path("tokens.txt"),
            dataDir = path("espeak-ng-data"),
            dictDir = if (has("dict")) path("dict") else "",
            lexicon = lexicons,
        )
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = kokoro,
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
            ),
            ruleFsts = ruleFsts,
            maxNumSentences = 1,
        )
        tts = OfflineTts(config = config)
        sampleRate = tts.sampleRate()
        numSpeakers = tts.numSpeakers()
    }

    fun setVoice(sid: Int) {
        speakerId = sid.coerceIn(0, (numSpeakers - 1).coerceAtLeast(0))
    }

    fun setSpeed(value: Float) {
        speed = value.coerceIn(0.3f, 3.0f)
    }

    /** Enqueue [text] for synthesis + playback. Returns immediately. */
    fun speak(text: String, utteranceId: String) {
        if (text.isBlank()) return
        queued.incrementAndGet()
        val epoch = cancelEpoch
        val voice = speakerId
        val rate = speed
        executor.execute {
            if (epoch != cancelEpoch) {
                queued.decrementAndGet()
                return@execute
            }
            val l = listener
            var errored = false
            try {
                val t = ensureTrack()
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    framesWritten = 0
                    t.play()
                }
                // Must be an explicit Function1 object, NOT a lambda. Kotlin 2.x compiles
                // lambdas via invokedynamic, which D8 desugars into a synthetic class that
                // only exposes invoke(Object): Object. sherpa-onnx's JNI looks up the
                // specialized invoke([F)Ljava/lang/Integer; and aborts (SIGABRT /
                // NoSuchMethodError) if it isn't present. An object literal always emits it.
                var firstChunk = true
                val callback = object : Function1<FloatArray, Int> {
                    override fun invoke(samples: FloatArray): Int {
                        if (epoch != cancelEpoch) return 0
                        if (firstChunk) {
                            firstChunk = false
                            // Fire onStart when audio actually begins, so the UI can reveal
                            // this sentence's text in sync with the voice.
                            l?.onStart(utteranceId)
                        }
                        val n = t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                        if (n > 0) framesWritten += n
                        return 1
                    }
                }
                tts.generateWithCallback(text, voice, rate, callback)
            } catch (e: Throwable) {
                errored = true
                android.util.Log.e(TAG, "Synthesis failed", e)
            } finally {
                val remaining = queued.decrementAndGet()
                if (remaining <= 0 && epoch == cancelEpoch) drainAndIdle(epoch)
                if (errored) l?.onError(utteranceId) else l?.onDone(utteranceId)
            }
        }
    }

    /** Stop everything immediately and discard anything still queued. */
    fun stop() {
        cancelEpoch++
        queued.set(0)
        track?.let {
            try {
                it.pause()
                it.flush()
                framesWritten = 0
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "stop() failed", e)
            }
        }
    }

    fun shutdown() {
        stop()
        executor.shutdownNow()
        try {
            track?.release()
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "track release failed", e)
        }
        track = null
        try {
            tts.release()
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "tts release failed", e)
        }
    }

    @Synchronized
    private fun ensureTrack(): AudioTrack {
        track?.let { return it }
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(4096)
        val t = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            minBytes * 2,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        track = t
        return t
    }

    /** Wait for the buffered tail to finish playing, then park the track at idle. */
    private fun drainAndIdle(epoch: Int) {
        val t = track ?: return
        try {
            while (epoch == cancelEpoch &&
                t.playState == AudioTrack.PLAYSTATE_PLAYING &&
                t.playbackHeadPosition < framesWritten
            ) {
                Thread.sleep(10)
            }
            if (epoch == cancelEpoch) {
                t.pause()
                t.flush()
                framesWritten = 0
            }
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "drain failed", e)
        }
    }

    companion object {
        private const val TAG = "KokoroTts"
        const val MODEL_DIR_NAME = "kokoro-multi-lang-v1_0"
        const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2"

        /**
         * Files that MUST be present for sherpa-onnx to build a usable Kokoro voice. If any are
         * missing (e.g. an interrupted download/extraction), the native `OfflineTts` constructor
         * returns an invalid handle and the first call into it (`sampleRate()`) SIGSEGVs — a native
         * crash Kotlin can't catch. So callers must verify completeness via [isModelComplete] before
         * constructing this class. The `espeak-ng-data/phon*` files are the ones the model config
         * validator checks (`phontab does not exist` → "Errors found in config!").
         */
        private val REQUIRED_FILES = listOf(
            "model.onnx", "voices.bin", "tokens.txt",
            "espeak-ng-data/phontab", "espeak-ng-data/phondata", "espeak-ng-data/phonindex",
        )

        /** True only if every required model file exists and is non-empty. */
        fun isModelComplete(modelDir: File): Boolean =
            REQUIRED_FILES.all { rel -> File(modelDir, rel).let { it.exists() && it.length() > 0 } }
    }
}
