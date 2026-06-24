package org.stypox.dicio.io.input.onnx_whisper

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
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
 * ## Native libraries
 * The sherpa-onnx JNI bridge (libsherpa-onnx-jni.so) and ONNX runtime
 * (libonnxruntime.so) must be present in jniLibs/. They are downloaded
 * automatically in CI; see .github/workflows/ci.yml and ONNX_WHISPER_SETUP.md.
 *
 * ## Integration point
 * Registered in [org.stypox.dicio.di.SttInputDeviceWrapper.buildInputDevice] under
 * [org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_ONNX_WHISPER].
 */
class OnnxWhisperInputDevice(
    private val context: Context,
    private val localeManager: LocaleManager,
) : SttInputDevice {

    companion object {
        private val TAG = OnnxWhisperInputDevice::class.simpleName

        const val MODEL_DIR_NAME = "sherpa-onnx-whisper-tiny"
        private const val ENCODER_FILENAME = "tiny-encoder.int8.onnx"
        private const val DECODER_FILENAME = "tiny-decoder.int8.onnx"
        private const val TOKENS_FILENAME  = "tiny-tokens.txt"

        private const val SAMPLE_RATE = 16_000

        /** Normalised RMS above this value is considered speech (~−40 dBFS). */
        private const val SPEECH_ENERGY_THRESHOLD = 0.01f

        /** Silence after speech ends → stop recording and transcribe. */
        private const val SILENCE_AFTER_SPEECH_MS = 1_500L

        /** No speech at all within this window → emit [InputEvent.None]. */
        private const val MAX_SILENCE_BEFORE_SPEECH_MS = 5_000L

        /** Hard cap — Whisper tiny was trained on ≤30 s of audio. */
        private const val MAX_RECORDING_MS = 30_000L

        private const val INITIAL_SAMPLE_CAPACITY = SAMPLE_RATE * 12
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow<SttState>(
        if (areModelFilesPresent()) SttState.NotLoaded else SttState.NotDownloaded
    )
    override val uiState: StateFlow<SttState> = _uiState

    // ── Background work ───────────────────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.Default)
    private var recognizer: OfflineRecognizer? = null
    private var loadedForLocale: String? = null
    private var recordingJob: Job? = null

    @Volatile
    private var pendingListener: ((InputEvent) -> Unit)? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        scope.launch {
            localeManager.locale.collect { newLocale ->
                val lang = normalizeLanguage(newLocale.language)
                if (recognizer != null && loadedForLocale != lang) {
                    rebuildRecognizer(lang)
                }
            }
        }
    }

    // ── SttInputDevice ────────────────────────────────────────────────────────

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        return when (_uiState.value) {
            SttState.NotDownloaded -> false

            SttState.NotLoaded, is SttState.ErrorLoading -> {
                if (thenStartListeningEventListener != null) {
                    pendingListener = thenStartListeningEventListener
                }
                startLoading(thenStartListening = thenStartListeningEventListener != null)
                true
            }

            is SttState.Loading -> {
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

            SttState.Listening -> false
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

    // ── Model loading ─────────────────────────────────────────────────────────

    private fun areModelFilesPresent(): Boolean =
        File(modelDir(), ENCODER_FILENAME).exists() &&
        File(modelDir(), DECODER_FILENAME).exists() &&
        File(modelDir(), TOKENS_FILENAME).exists()

    private fun modelDir(): File = File(context.getExternalFilesDir(null), MODEL_DIR_NAME)

    /** Android stores Hebrew as legacy BCP-47 code "iw"; Whisper expects "he". */
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
     * Builds the [OfflineRecognizer].
     *
     * Passing [assetManager] = null tells sherpa-onnx to treat every path as an
     * absolute filesystem path rather than an Android asset path.
     *
     * [tailPaddings] = 0: the default of 1 000 samples adds ~62 ms of silence at
     * the tail, which degrades accuracy on short VAD-trimmed utterances.
     */
    private fun buildRecognizer(language: String): OfflineRecognizer {
        val dir = modelDir()
        return OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = 80,
                ),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder      = File(dir, ENCODER_FILENAME).absolutePath,
                        decoder      = File(dir, DECODER_FILENAME).absolutePath,
                        language     = language,
                        task         = "transcribe",
                        tailPaddings = 0,
                    ),
                    tokens     = File(dir, TOKENS_FILENAME).absolutePath,
                    modelType  = "whisper",
                    numThreads = 2,
                ),
            )
        )
    }

    // ── Audio recording & transcription ───────────────────────────────────────

    private suspend fun performRecording(eventListener: (InputEvent) -> Unit) {
        val rec = recognizer ?: return

        _uiState.value = SttState.Listening

        val chunkSamples = SAMPLE_RATE / 10   // 100 ms per read
        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
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
                    "AudioRecord failed to initialize — mic may be in use or permission denied"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open AudioRecord", e)
            _uiState.value = SttState.Loaded
            withContext(Dispatchers.Main) { eventListener(InputEvent.Error(e)) }
            return
        }

        try {
            audioRecord.startRecording()

            val allSamples = ArrayList<Float>(INITIAL_SAMPLE_CAPACITY)
            val shortBuf   = ShortArray(chunkSamples)

            var speechDetected = false
            var lastSpeechMs   = -1L
            val startMs        = System.currentTimeMillis()

            while (true) {
                kotlinx.coroutines.yield()

                val read = audioRecord.read(shortBuf, 0, chunkSamples)
                if (read <= 0) continue

                // Energy-based VAD
                var sumSq = 0.0
                for (i in 0 until read) sumSq += (shortBuf[i] / 32768.0).let { it * it }
                val rms      = sqrt(sumSq / read).toFloat()
                val isSpeech = rms > SPEECH_ENERGY_THRESHOLD

                for (i in 0 until read) allSamples.add(shortBuf[i] / 32768.0f)

                val nowMs   = System.currentTimeMillis()
                val elapsed = nowMs - startMs

                if (isSpeech) {
                    speechDetected = true
                    lastSpeechMs   = nowMs
                }

                when {
                    speechDetected
                        && lastSpeechMs >= 0
                        && (nowMs - lastSpeechMs) >= SILENCE_AFTER_SPEECH_MS -> break

                    elapsed >= MAX_RECORDING_MS -> break

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

            // Transcribe
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples = allSamples.toFloatArray(), sampleRate = SAMPLE_RATE)
                rec.decode(stream)
                val text = rec.getResult(stream).text.trim()

                _uiState.value = SttState.Loaded
                withContext(Dispatchers.Main) {
                    if (text.isBlank()) eventListener(InputEvent.None)
                    else eventListener(InputEvent.Final(listOf(Pair(text, 1.0f))))
                }
            } finally {
                stream.release()
            }

        } catch (_: CancellationException) {
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
