package com.abhijit.airealityscanner.features.scanner

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhijit.airealityscanner.ai.AiEngine
import com.abhijit.airealityscanner.ai.AiException
import com.abhijit.airealityscanner.ai.mlkit.BarcodeInfo
import com.abhijit.airealityscanner.ai.mlkit.DetectedObjectInfo
import com.abhijit.airealityscanner.ai.mlkit.MlKitVision
import com.abhijit.airealityscanner.camera.FrameThrottle
import com.abhijit.airealityscanner.camera.SceneChange
import com.abhijit.airealityscanner.core.util.ImageUtils
import com.abhijit.airealityscanner.core.voice.SpeechEvent
import com.abhijit.airealityscanner.core.voice.SpeechToTextManager
import com.abhijit.airealityscanner.core.voice.TextToSpeechManager
import com.abhijit.airealityscanner.data.local.ImageStorage
import com.abhijit.airealityscanner.domain.model.AppSettings
import com.abhijit.airealityscanner.domain.model.ScanMode
import com.abhijit.airealityscanner.domain.model.ScanSession
import com.abhijit.airealityscanner.domain.model.StoredImage
import com.abhijit.airealityscanner.domain.repository.ScanRepository
import com.abhijit.airealityscanner.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class LiveState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val cloudEnabled: Boolean = false,
    val translateEnabled: Boolean = false,
    val narrate: Boolean = false,
    val processingOnDevice: Boolean = false,
    val uploading: Boolean = false,
    val description: String? = null,
    val text: String = "",
    val translatedText: String? = null,
    val barcodes: List<BarcodeInfo> = emptyList(),
    val objects: List<DetectedObjectInfo> = emptyList(),
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val uploads: Int = 0,
    val sceneChange: Float = 0f,
    val error: String? = null,
)

data class ScannerUiState(
    val processing: Boolean = false,
    val torchOn: Boolean = false,
    val listening: Boolean = false,
    val partialSpeech: String = "",
    val message: String? = null,
    val live: LiveState = LiveState(),
    val showLiveConsent: Boolean = false,
    val showVoiceEnable: Boolean = false,
)

sealed interface ScannerEvent {
    data class OpenResult(val sessionId: String, val question: String?) : ScannerEvent
    data class Capture(val question: String?) : ScannerEvent
    data object RequestMicPermission : ScannerEvent
}

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val aiEngine: AiEngine,
    private val ml: MlKitVision,
    private val repository: ScanRepository,
    private val settingsRepository: SettingsRepository,
    private val imageStorage: ImageStorage,
    private val tts: TextToSpeechManager,
    private val stt: SpeechToTextManager,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val recent: StateFlow<ScanSession?> =
        repository.observeLatest().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Number of cloud uploads currently in progress anywhere in the app. */
    val uploadsInFlight: StateFlow<Int> = aiEngine.uploadsInFlight

    private val _state = MutableStateFlow(ScannerUiState())
    val state: StateFlow<ScannerUiState> = _state.asStateFlow()

    private val _events = Channel<ScannerEvent>(Channel.BUFFERED)
    val events: Flow<ScannerEvent> = _events.receiveAsFlow()

    /** Single background thread for CameraX analysis callbacks (never the UI thread). */
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val onDeviceThrottle = FrameThrottle(700L)
    private val onDeviceBusy = AtomicBoolean(false)
    @Volatile private var cloudInFlight = false
    @Volatile private var lastCloudAt = 0L
    @Volatile private var lastCloudSignature: FloatArray? = null
    @Volatile private var lastOcrText = ""
    private var listenJob: Job? = null

    val liveAnalyzer = ImageAnalysis.Analyzer { proxy -> onFrame(proxy) }

    // ---------------------------------------------------------------- capture

    fun selectMode(mode: ScanMode) {
        viewModelScope.launch { settingsRepository.update { it.copy(lastMode = mode) } }
    }

    fun toggleTorch() = _state.update { it.copy(torchOn = !it.torchOn) }

    fun requestCapture(question: String? = null) {
        if (_state.value.processing) return
        viewModelScope.launch { _events.send(ScannerEvent.Capture(question)) }
    }

    fun onImageCaptured(image: ImageProxy, question: String?) {
        _state.update { it.copy(processing = true) }
        viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.Default) {
                    try {
                        ImageUtils.imageProxyToBitmap(image, 2048)
                    } finally {
                        image.close()
                    }
                }
                val stored = imageStorage.save(bitmap, source = "camera")
                openNewSession(listOf(stored), question)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Couldn't save the photo: ${e.message}")
            } finally {
                _state.update { it.copy(processing = false) }
            }
        }
    }

    fun onCaptureError(message: String?) {
        _state.update { it.copy(processing = false) }
        showMessage("Capture failed${message?.let { ": $it" } ?: ""}")
    }

    fun onGalleryImage(uri: Uri) {
        _state.update { it.copy(processing = true) }
        viewModelScope.launch {
            try {
                val stored = imageStorage.import(uri)
                if (stored == null) showMessage("Couldn't open that image.") else openNewSession(listOf(stored), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Couldn't import the image: ${e.message}")
            } finally {
                _state.update { it.copy(processing = false) }
            }
        }
    }

    private suspend fun openNewSession(images: List<StoredImage>, question: String?) {
        val s = settings.value
        val mode = s.lastMode
        val id = repository.createSession(mode, "${mode.title} scan", images, saved = s.autoSaveScans)
        _events.send(ScannerEvent.OpenResult(id, question))
    }

    fun showMessage(message: String) = _state.update { it.copy(message = message) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    // ---------------------------------------------------------------- voice

    fun onMicPressed() {
        if (_state.value.listening) {
            stopListening()
            return
        }
        if (!settings.value.voiceEnabled) {
            _state.update { it.copy(showVoiceEnable = true) }
            return
        }
        viewModelScope.launch { _events.send(ScannerEvent.RequestMicPermission) }
    }

    fun enableVoice() {
        viewModelScope.launch {
            settingsRepository.update { it.copy(voiceEnabled = true) }
            _state.update { it.copy(showVoiceEnable = false) }
            _events.send(ScannerEvent.RequestMicPermission)
        }
    }

    fun dismissVoiceEnable() = _state.update { it.copy(showVoiceEnable = false) }

    fun onMicPermissionDenied() =
        showMessage("Microphone permission is needed for voice questions. You can still type questions after scanning.")

    /** Listens for a spoken question, then captures a photo and asks it about that photo. */
    fun startListening() {
        listenJob?.cancel()
        tts.stop()
        listenJob = viewModelScope.launch {
            _state.update { it.copy(listening = true, partialSpeech = "") }
            try {
                stt.listen(settings.value.language).collect { event ->
                    when (event) {
                        SpeechEvent.Ready -> Unit
                        is SpeechEvent.Partial -> _state.update { it.copy(partialSpeech = event.text) }
                        is SpeechEvent.Final -> {
                            _state.update { it.copy(listening = false, partialSpeech = event.text) }
                            requestCapture(event.text)
                        }
                        is SpeechEvent.Error -> showMessage(event.message)
                    }
                }
            } finally {
                _state.update { it.copy(listening = false) }
            }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        _state.update { it.copy(listening = false, partialSpeech = "") }
    }

    // ---------------------------------------------------------------- live mode

    fun startLive() {
        val s = settings.value
        if (s.provider.isCloud && s.liveCloudEnabled && !s.cloudConsentGiven) {
            _state.update { it.copy(showLiveConsent = true) }
            return
        }
        beginLive(s)
    }

    fun acceptLiveConsent() {
        viewModelScope.launch {
            settingsRepository.update { it.copy(cloudConsentGiven = true) }
            _state.update { it.copy(showLiveConsent = false) }
            beginLive(settings.value.copy(cloudConsentGiven = true))
        }
    }

    fun startLiveOnDeviceOnly() {
        _state.update { it.copy(showLiveConsent = false) }
        beginLive(settings.value, forceOnDevice = true)
    }

    private fun beginLive(s: AppSettings, forceOnDevice: Boolean = false) {
        onDeviceThrottle.reset()
        lastCloudAt = 0L
        lastCloudSignature = null
        lastOcrText = ""
        val wantsCloud = !forceOnDevice && s.provider.isCloud && s.liveCloudEnabled && s.cloudConsentGiven
        val cloud = wantsCloud && s.hasKeyForProvider
        _state.update { it.copy(live = LiveState(active = true, cloudEnabled = cloud, narrate = s.autoSpeak)) }
        if (wantsCloud && !cloud) showMessage("Add an API key in Settings for AI scene descriptions. Showing on-device detection only.")
    }

    fun togglePause() = updateLive { it.copy(paused = !it.paused, processingOnDevice = false) }

    fun stopLive() {
        tts.stop()
        _state.update { it.copy(live = LiveState()) }
    }

    fun toggleLiveCloud() {
        val s = settings.value
        val live = _state.value.live
        if (!live.cloudEnabled) {
            when {
                !s.provider.isCloud -> return showMessage("Choose Claude or OpenAI in Settings to enable AI scene descriptions.")
                !s.cloudConsentGiven -> return _state.update { it.copy(showLiveConsent = true) }
                !s.hasKeyForProvider -> return showMessage("Add an API key in Settings first.")
            }
            lastCloudSignature = null
            lastCloudAt = 0L
        }
        updateLive { it.copy(cloudEnabled = !it.cloudEnabled) }
    }

    fun toggleTranslate() {
        lastOcrText = ""
        updateLive { it.copy(translateEnabled = !it.translateEnabled, translatedText = null) }
    }

    fun toggleNarrate() {
        if (_state.value.live.narrate) tts.stop()
        updateLive { it.copy(narrate = !it.narrate) }
    }

    private fun updateLive(transform: (LiveState) -> LiveState) =
        _state.update { it.copy(live = transform(it.live)) }

    /** Called on [analysisExecutor] for each camera frame. Most frames are dropped by design. */
    private fun onFrame(proxy: ImageProxy) {
        try {
            val live = _state.value.live
            if (!live.active || live.paused) return
            if (onDeviceBusy.get() || !onDeviceThrottle.tryAcquire(SystemClock.elapsedRealtime())) return
            val bitmap = ImageUtils.imageProxyToBitmap(proxy, 960)
            onDeviceBusy.set(true)
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    processLiveFrame(bitmap)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    updateLive { it.copy(processingOnDevice = false, error = e.message) }
                } finally {
                    onDeviceBusy.set(false)
                }
            }
        } finally {
            proxy.close()
        }
    }

    private suspend fun processLiveFrame(bitmap: Bitmap) = coroutineScope {
        val s = settings.value
        updateLive { it.copy(processingOnDevice = true, frameWidth = bitmap.width, frameHeight = bitmap.height) }
        val textJob = async { runCatching { ml.recognizeText(bitmap, s.language).text.trim() }.getOrDefault("") }
        val codesJob = async { runCatching { ml.scanBarcodes(bitmap) }.getOrDefault(emptyList()) }
        val objectsJob = async { runCatching { ml.detectObjects(bitmap) }.getOrDefault(emptyList()) }
        val text = textJob.await()
        val codes = codesJob.await()
        val objects = objectsJob.await()
        if (!_state.value.live.active) return@coroutineScope
        updateLive { it.copy(processingOnDevice = false, text = text, barcodes = codes, objects = objects) }

        if (_state.value.live.translateEnabled && text.isNotEmpty() && text != lastOcrText) {
            lastOcrText = text
            viewModelScope.launch {
                runCatching { ml.translate(text.take(1500), s.language) }
                    .onSuccess { r -> updateLive { it.copy(translatedText = r.text) } }
                    .onFailure { e -> updateLive { it.copy(error = "Translation unavailable: ${e.message}") } }
            }
        }
        maybeDescribe(bitmap, s)
    }

    /** Uploads a frame only when cloud is on, the interval has elapsed AND the scene changed. */
    private fun maybeDescribe(bitmap: Bitmap, s: AppSettings) {
        val live = _state.value.live
        if (!live.cloudEnabled || cloudInFlight) return
        val now = SystemClock.elapsedRealtime()
        if (lastCloudAt != 0L && now - lastCloudAt < s.liveIntervalSeconds * 1000L) return
        val signature = ImageUtils.lumaSignature(bitmap)
        val previous = lastCloudSignature
        val change = previous?.let { SceneChange.difference(it, signature) } ?: 1f
        updateLive { it.copy(sceneChange = change) }
        if (!SceneChange.hasChanged(previous, signature, s.sceneChangeThreshold)) return

        cloudInFlight = true
        lastCloudAt = now
        lastCloudSignature = signature
        updateLive { it.copy(uploading = true) }
        viewModelScope.launch {
            try {
                val description = aiEngine.describeLive(bitmap)
                if (_state.value.live.active) {
                    updateLive { it.copy(description = description, uploads = it.uploads + 1, error = null) }
                    if (_state.value.live.narrate) tts.speak(description, s.language, s.speechRate)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val stopCloud = e is AiException.MissingApiKey || e is AiException.ConsentRequired ||
                    (e is AiException.Http && (e.code == 401 || e.code == 403))
                updateLive { it.copy(error = e.message, cloudEnabled = if (stopCloud) false else it.cloudEnabled) }
            } finally {
                cloudInFlight = false
                updateLive { it.copy(uploading = false) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        listenJob?.cancel()
        tts.stop()
        analysisExecutor.shutdown()
    }
}
