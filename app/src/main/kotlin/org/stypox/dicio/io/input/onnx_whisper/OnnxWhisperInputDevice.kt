package org.stypox.dicio.io.input.onnx_whisper

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineFeatureExtractorConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.io.input.SttState
import java.io.File
import kotlin.math.sqrt

/**
 * An [SttInputDevice] that uses sherpa-onnx's OfflineRecognizer with the multilingual
 * Whisper-tiny model stored in the app's internal files directory.
 *
 * ## Model placement
 * Place the following files (extracted from sherpa-onnx-whisper-tiny.tar.bz2) into:
 *   <files-dir>/sherpa-onnx-whisper-tiny/
 *     tiny-encoder.int8.onnx
 *     tiny-decoder.int8.onnx
 *     tiny-tokens.txt
 *
 * The float32 variants (tiny-encoder.onnx, tiny-decoder.onnx) that come in the archive
 * are not needed — only the int8 quantised pair is used here.
 *
 * ## Integration point
 * Registered in [org.stypox.dicio.di.SttInputDeviceWrapper.buildInputDevice] under
 * [org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_ONNX_WHISPER].
 *
 * ## State lifecycle
 *  NotDownloaded  →  (user places files)  →  NotLoaded
 *  NotLoaded      →  tryLoad(listener)    →  Loading(true)  →  Loaded / ErrorLoading
 *  Loaded         →  tryLoad(listener)    →  Listening      →  Loaded
 *  Listening      →  stopListening()      →  Loaded
 */
class OnnxWhisperInputDevice(
    private val context: Context,
    private val localeManager: LocaleManager,
) : SttInputDevice {

    // ── Model file constants ──────────────────────────────────────────────────────────────────────

    companion object {
        private val TAG = OnnxWhisperInputDevice::class.simpleName

        /** Sub-directory name inside [Context.getFilesDir]. */
        const val MODEL_DIR_NAME = "sherpa-onnx-whisper-tiny"

        private const val ENCODER_FILENAME = "tiny-encoder.int8.onnx"
        private const val DECODER_FILENAME = "tiny-decoder.int8.onnx"
        private const val TOKENS_FILENAME  = "tiny-tokens.txt"

        private const val SAMPLE_RATE = 16_000

        /**
         * Energy-based VAD: normalised RMS above this value is considered speech.
         * 0.01 ≈ −40 dBFS, well above background hiss but below quiet speech.
         */
        private const val SPEECH_ENERGY_THRESHOLD = 0.01f

        /**
         * How long silence is allowed after speech has been detected before we stop recording
         * and hand the audio to Whisper.
         */
        private const val SILENCE_AFTER_SPEECH_MS = 1_500L

        /**
         * If no speech at all is detected within this window, emit [InputEvent.None] and stop.
         */
        private const val MAX_SILENCE_BEFORE_SPEECH_MS = 5_000L

        /** Hard cap. Whisper tiny was trained on ≤30 s of audio. */
        private const val MAX_RECORDING_MS = 30_000L

        /** Pre-allocate for ~12 s of audio so ArrayList rarely needs to resize. */
        private const val INITIAL_SAMPLE_CAPACITY = SAMPLE_RATE * 12
    }

    // ── State ─────────────────────────────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow<SttState>(
        if (areModelFilesPresent()) SttState.NotLoaded else SttState.NotDownloaded
    )
    override val uiState: StateFlow<SttState> = _uiState

    // ── Background work ───────────────────────────────────────────────────────────────────────────

    /** Single long-lived scope for both model loading and audio recording. */
    private val scope = CoroutineScope(Dispatchers.Default)

    /** The loaded sherpa-onnx recognizer, or null while loading / not yet loaded. */
    private var recognizer: OfflineRecognizer? = null

    /** Language tag the current [recognizer] was built for, e.g. "he" or "en". */
    private var loadedForLocale: String? = null

    /** Active recording coroutine. At most one runs at a time. */
    private var recordingJob: Job? = null

    /**
     * Event listener stored while the model is still loading.
     * Once loading completes, recording starts and the listener is consumed.
     * [Volatile] because it is written from the caller thread and read from [scope].
     */
    @Volatile
    private var pendingListener: ((InputEvent) -> Unit)? = null

    // ── Initialisation ────────────────────────────────────────────────────────────────────────────

    init {
        // Rebuild the recognizer whenever the user changes language in settings.
        scope.launch {
            localeManager.locale.collect { newLocale ->
                val lang = normalizeLanguage(newLocale.language)
                if (recognizer != null && loadedForLocale != lang) {
                    rebuildRecognizer(lang)
                }
            }
        }
    }

    // ── SttInputDevice implementation ─────────────────────────────────────────────────────────────

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        return when (val state = _uiState.value) {
            SttState.NotDownloaded -> false

            SttState.NotLoaded, is SttState.ErrorLoading -> {
                // Files are present (or we'll retry). Kick off the loading job.
                if (thenStartListeningEventListener != null) {
                    pendingListener = thenStartListeningEventListener
                }
                startLoading(thenStartListening = thenStartListeningEventListener != null)
                true
            }

            is SttState.Loading -> {
                // Model is already loading. Store listener so it fires once loading is done.
                if (thenStartListeningEventListener != null) {
                    pendingListener = thenStartListeningEventListener
                }
                true
            }

            SttState.Loaded -> {
                if (thenStartListeningEventListener != null) {
                    recordingJob = scope.launch {
                        performRecording(thenStartListeningEventListener)
                    }
                }
                true
            }

            SttState.Listening -> false  // already busy

            // NoMicrophonePermission, NotInitialized, NotAvailable, etc.
            else -> false
        }
    }

    override fun stopListening() {
        recordingJob?.cancel()
        if (_uiState.value == SttState.Listening) {
            _uiState.value = SttState.Loaded
        }
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) {
        if (_uiState.value == SttState.Listening) {
            stopListening()
        } else {
            tryLoad(eventListener)
        }
    }

    override suspend fun destroy() {
        recordingJob?.cancel()
        scope.cancel()
        withContext(Dispatchers.Default) {
            recognizer?.release()
            recognizer = null
        }
    }

    // ── Model loading ─────────────────────────────────────────────────────────────────────────────

    private fun areModelFilesPresent(): Boolean {
        return File(modelDir(), ENCODER_FILENAME).exists()
            && File(modelDir(), DECODER_FILENAME).exists()
            && File(modelDir(), TOKENS_FILENAME).exists()
    }

    private fun modelDir(): File = File(context.filesDir, MODEL_DIR_NAME)

    /**
     * Java / Android stores Hebrew as the legacy BCP-47 code "iw"; Whisper expects "he".
     */
    private fun normalizeLanguage(language: String): String =
        if (language == "iw") "he" else language

    private fun startLoading(thenStartListening: Boolean) {
        _uiState.value = SttState.Loading(thenStartListening)
        scope.launch {
            val language = normalizeLanguage(localeManager.locale.value.language)
            try {
                recognizer?.release()
                recognizer = null
                recognizer = buildRecognizer(language)
                loadedForLocale = language
                _uiState.value = SttState.Loaded

                // Fire the listener that was waiting for the model, if any.
                val listener = pendingListener
                pendingListener = null
                if (listener != null) {
                    recordingJob = scope.launch { performRecording(listener) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Whisper model", e)
                _uiState.value = SttState.ErrorLoading(e)
            }
        }
    }

    /** Called when the locale changes while the recognizer is already loaded. */
    private fun rebuildRecognizer(newLanguage: String) {
        _uiState.value = SttState.Loading(false)
        scope.launch {
            try {
                recognizer?.release()
                recognizer = null
                recognizer = buildRecognizer(newLanguage)
                loadedForLocale = newLanguage
                _uiState.value = SttState.Loaded
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rebuild Whisper recognizer for locale $newLanguage", e)
                _uiState.value = SttState.ErrorLoading(e)
            }
        }
    }

    /**
     * Constructs an [OfflineRecognizer] for [language] using the int8-quantised encoder/decoder
     * files found in [modelDir].
     *
     * Passing [assetManager] = null tells sherpa-onnx to treat every path as an absolute
     * filesystem path rather than an Android asset path.
     *
     * [tailPaddings] = 0: the sherpa-onnx default of 1 000 samples adds ~62 ms of silence
     * padding at the tail, which is unnecessary for a VAD-trimmed buffer and slightly degrades
     * accuracy on short utterances.
     */
    private fun buildRecognizer(language: String): OfflineRecognizer {
        val dir = modelDir()
        return OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                featConfig = OfflineFeatureExtractorConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = 80,
                ),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder  = File(dir, ENCODER_FILENAME).absolutePath,
                        decoder  = File(dir, DECODER_FILENAME).absolutePath,
                        language = language,
                        task     = "transcribe",
                        tailPaddings = 0,
                    ),
                    tokens     = File(dir, TOKENS_FILENAME).absolutePath,
                    modelType  = "whisper",
                    numThreads = 2,
                ),
            )
        )
    }

    // ── Audio recording & transcription ───────────────────────────────────────────────────────────

    /**
     * Records microphone audio, applies a simple energy-based VAD to detect start/end of speech,
     * then feeds the collected samples to the sherpa-onnx [OfflineRecognizer] and emits the result
     * as an [InputEvent].
     *
     * Designed to be launched as a [Job] inside [scope] so it can be cancelled cleanly.
     */
    private suspend fun performRecording(eventListener: (InputEvent) -> Unit) {
        val rec = recognizer ?: return  // should not happen; guards against races

        _uiState.value = SttState.Listening

        // ── Set up AudioRecord ────────────────────────────────────────────────────────────────────
        val chunkSamples = SAMPLE_RATE / 10  // 100 ms per read
        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // At least 4× the read chunk so the OS never overflows the ring buffer
        val bufSize = maxOf(minBufSize, chunkSamples * 4)

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            ).also { ar ->
                check(ar.state == AudioRecord.STATE_INITIALIZED) {
                    "AudioRecord failed to initialize — microphone may be in use or permission denied"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open AudioRecord", e)
            _uiState.value = SttState.Loaded
            withContext(Dispatchers.Main) { eventListener(InputEvent.Error(e)) }
            return
        }

        // ── Recording loop ────────────────────────────────────────────────────────────────────────
        try {
            audioRecord.startRecording()

            val allSamples = ArrayList<Float>(INITIAL_SAMPLE_CAPACITY)
            val shortBuf   = ShortArray(chunkSamples)

            var speechDetected = false
            var lastSpeechMs   = -1L
            val startMs        = System.currentTimeMillis()

            while (true) {
                // coroutineContext is checked here; if cancelled, the CancellationException
                // will propagate through the read below.
                kotlinx.coroutines.yield()

                val read = audioRecord.read(shortBuf, 0, chunkSamples)
                if (read <= 0) continue

                // ── Energy-based VAD ──────────────────────────────────────────────────────────────
                var sumSq = 0.0
                for (i in 0 until read) sumSq += (shortBuf[i] / 32768.0).let { it * it }
                val rms     = sqrt(sumSq / read).toFloat()
                val isSpeech = rms > SPEECH_ENERGY_THRESHOLD

                // Append to accumulation buffer (convert 16-bit PCM to [-1, 1] float)
                for (i in 0 until read) allSamples.add(shortBuf[i] / 32768.0f)

                val nowMs = System.currentTimeMillis()
                val elapsed = nowMs - startMs

                if (isSpeech) {
                    speechDetected = true
                    lastSpeechMs = nowMs
                }

                when {
                    // Natural end of utterance: speech was detected, then silence for long enough
                    speechDetected
                        && lastSpeechMs >= 0
                        && (nowMs - lastSpeechMs) >= SILENCE_AFTER_SPEECH_MS -> break

                    // Hard cap — prevent Whisper from being asked to process an unreasonably long clip
                    elapsed >= MAX_RECORDING_MS -> break

                    // No speech at all within the patience window → give up
                    !speechDetected && elapsed >= MAX_SILENCE_BEFORE_SPEECH_MS -> {
                        _uiState.value = SttState.Loaded
                        withContext(Dispatchers.Main) { eventListener(InputEvent.None) }
                        return
                    }
                }
            }

            if (!speechDetected) {
                _uiState.value = SttState.Loaded
                withContext(Dispatchers.Main) { eventListener(InputEvent.None) }
                return
            }

            // ── Transcription ─────────────────────────────────────────────────────────────────────
            val samples = allSamples.toFloatArray()
            val stream  = rec.createStream()
            try {
                stream.acceptWaveform(samples = samples, sampleRate = SAMPLE_RATE)
                rec.decode(stream)
                val text = rec.getResult(stream).text.trim()

                _uiState.value = SttState.Loaded
                withContext(Dispatchers.Main) {
                    if (text.isBlank()) {
                        eventListener(InputEvent.None)
                    } else {
                        // Whisper doesn't produce per-utterance confidence scores;
                        // use 1.0f as a placeholder to satisfy the InputEvent.Final contract.
                        eventListener(InputEvent.Final(listOf(Pair(text, 1.0f))))
                    }
                }
            } finally {
                stream.release()
            }

        } catch (_: CancellationException) {
            // Caller cancelled (e.g. user tapped Stop) — just restore state, re-throw below
            _uiState.value = SttState.Loaded
            throw CancellationException()
        } catch (e: Exception) {
            Log.e(TAG, "Error during recording or transcription", e)
            _uiState.value = SttState.Loaded
            withContext(Dispatchers.Main) { eventListener(InputEvent.Error(e)) }
        } finally {
            audioRecord.stop()
            audioRecord.release()
        }
    }
}
