package com.abhijit.airealityscanner.features.result

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhijit.airealityscanner.ai.AiEngine
import com.abhijit.airealityscanner.ai.AiException
import com.abhijit.airealityscanner.ai.mlkit.BarcodeInfo
import com.abhijit.airealityscanner.ai.mlkit.MlKitVision
import com.abhijit.airealityscanner.core.util.ImageUtils
import com.abhijit.airealityscanner.core.voice.SpeechEvent
import com.abhijit.airealityscanner.core.voice.SpeechToTextManager
import com.abhijit.airealityscanner.core.voice.TextToSpeechManager
import com.abhijit.airealityscanner.data.export.ReportExporter
import com.abhijit.airealityscanner.domain.model.AppLanguage
import com.abhijit.airealityscanner.domain.model.AppSettings
import com.abhijit.airealityscanner.domain.model.ChatMessage
import com.abhijit.airealityscanner.domain.model.ChatRole
import com.abhijit.airealityscanner.domain.model.ScanSession
import com.abhijit.airealityscanner.domain.repository.ScanRepository
import com.abhijit.airealityscanner.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface PendingAction {
    data object Analyze : PendingAction
    data class Ask(val question: String, val fromVoice: Boolean) : PendingAction
}

data class ResultUiState(
    val loaded: Boolean = false,
    val notFound: Boolean = false,
    val analyzing: Boolean = false,
    val answering: Boolean = false,
    val error: String? = null,
    val consentPending: PendingAction? = null,
    val listening: Boolean = false,
    val partialSpeech: String = "",
    val translating: Boolean = false,
    val translation: String? = null,
    val barcodes: List<BarcodeInfo> = emptyList(),
    val exporting: Boolean = false,
    val showVoiceEnable: Boolean = false,
    val snackbar: String? = null,
)

sealed interface ResultEvent {
    data class ShareText(val text: String, val imageUri: Uri?) : ResultEvent
    data class ShareFile(val uri: Uri, val mimeType: String) : ResultEvent
    data object RequestMicPermission : ResultEvent
    data object Closed : ResultEvent
}

@HiltViewModel
class ResultViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ScanRepository,
    private val settingsRepository: SettingsRepository,
    private val aiEngine: AiEngine,
    private val ml: MlKitVision,
    private val tts: TextToSpeechManager,
    private val stt: SpeechToTextManager,
    private val exporter: ReportExporter,
) : ViewModel() {

    val sessionId: String = checkNotNull(savedStateHandle["sessionId"])
    private var initialQuestion: String? = savedStateHandle.get<String>("q")?.takeIf { it.isNotBlank() }

    val session: StateFlow<ScanSession?> =
        repository.observeSession(sessionId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val messages: StateFlow<List<ChatMessage>> =
        repository.observeMessages(sessionId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val speaking: StateFlow<Boolean> = tts.isSpeaking
    val uploadsInFlight: StateFlow<Int> = aiEngine.uploadsInFlight

    private val _ui = MutableStateFlow(ResultUiState())
    val ui: StateFlow<ResultUiState> = _ui.asStateFlow()

    private val _events = Channel<ResultEvent>(Channel.BUFFERED)
    val events: Flow<ResultEvent> = _events.receiveAsFlow()

    private var bitmaps: List<Bitmap>? = null
    private var listenJob: Job? = null
    private var notesJob: Job? = null

    init {
        viewModelScope.launch {
            val s = repository.getSession(sessionId)
            if (s == null) {
                _ui.update { it.copy(loaded = true, notFound = true) }
                return@launch
            }
            _ui.update { it.copy(loaded = true) }
            if (s.analysis == null && s.images.isNotEmpty()) {
                analyze()
            } else {
                consumeInitialQuestion()
            }
            scanCodes(s)
        }
        viewModelScope.launch {
            tts.errors.collect { msg -> _ui.update { it.copy(snackbar = msg) } }
        }
    }

    private suspend fun loadBitmaps(s: ScanSession): List<Bitmap> {
        bitmaps?.let { return it }
        val loaded = withContext(Dispatchers.IO) { s.images.mapNotNull { ImageUtils.decodeFile(it.path, 2048) } }
        bitmaps = loaded
        return loaded
    }

    private suspend fun scanCodes(s: ScanSession) {
        if (s.images.isEmpty()) return
        val first = loadBitmaps(s).firstOrNull() ?: return
        val codes = runCatching { ml.scanBarcodes(first) }.getOrDefault(emptyList())
        _ui.update { it.copy(barcodes = codes) }
    }

    fun analyze() {
        viewModelScope.launch {
            val s = repository.getSession(sessionId) ?: return@launch
            _ui.update { it.copy(analyzing = true, error = null) }
            try {
                val images = loadBitmaps(s)
                if (images.isEmpty()) {
                    _ui.update { it.copy(error = "The image for this scan is no longer available.") }
                    return@launch
                }
                val currentSettings = settingsRepository.settings.first()
                val ocr = if (s.mode.usesOcr) {
                    runCatching { ml.recognizeText(images.first(), currentSettings.language).text }.getOrNull()?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
                val result = aiEngine.analyze(s.mode, images, ocr).let {
                    if (it.extractedText.isNullOrBlank() && ocr != null) it.copy(extractedText = ocr) else it
                }
                repository.saveResult(sessionId, result)
                if (currentSettings.autoSpeak) speak(result.spokenSummary())
                consumeInitialQuestion()
            } catch (e: AiException.ConsentRequired) {
                _ui.update { it.copy(consentPending = PendingAction.Analyze) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: "Analysis failed.") }
            } finally {
                _ui.update { it.copy(analyzing = false) }
            }
        }
    }

    private fun consumeInitialQuestion() {
        val q = initialQuestion ?: return
        initialQuestion = null
        ask(q, fromVoice = true)
    }

    fun ask(question: String, fromVoice: Boolean = false) {
        val q = question.trim()
        if (q.isEmpty() || _ui.value.answering) return
        viewModelScope.launch {
            val s = repository.getSession(sessionId) ?: return@launch
            val current = settingsRepository.settings.first()
            if (current.provider.isCloud && !current.cloudConsentGiven && s.images.isNotEmpty()) {
                _ui.update { it.copy(consentPending = PendingAction.Ask(q, fromVoice)) }
                return@launch
            }
            val history = repository.getMessages(sessionId)
            repository.addMessage(sessionId, ChatRole.USER, q)
            _ui.update { it.copy(answering = true, error = null) }
            try {
                val answer = aiEngine.followUp(s.mode, loadBitmaps(s), s.analysis, history, q)
                repository.addMessage(sessionId, ChatRole.ASSISTANT, answer)
                if (fromVoice || current.autoSpeak) {
                    val loop = fromVoice && current.continuousConversation && current.voiceEnabled
                    speak(answer, onDone = if (loop) ({ startListening() }) else null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repository.addMessage(sessionId, ChatRole.SYSTEM, "Couldn't get an answer: ${e.message ?: "unknown error"}")
            } finally {
                _ui.update { it.copy(answering = false) }
            }
        }
    }

    fun acceptConsent() {
        val pending = _ui.value.consentPending ?: return
        viewModelScope.launch {
            settingsRepository.update { it.copy(cloudConsentGiven = true) }
            _ui.update { it.copy(consentPending = null) }
            when (pending) {
                PendingAction.Analyze -> analyze()
                is PendingAction.Ask -> ask(pending.question, pending.fromVoice)
            }
        }
    }

    fun declineConsent() {
        val pending = _ui.value.consentPending
        _ui.update {
            it.copy(
                consentPending = null,
                error = if (pending == PendingAction.Analyze) {
                    "Not analysed — cloud processing was declined. Choose 'On-device (offline)' in Settings to analyse without uploading."
                } else {
                    it.error
                },
            )
        }
    }

    // ---------------------------------------------------------------- voice

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val s = settings.value
        tts.speak(text, s.language, s.speechRate, onDone)
    }

    fun stopSpeaking() = tts.stop()

    fun onMicPressed() {
        if (_ui.value.listening) return stopListening()
        if (!settings.value.voiceEnabled) {
            _ui.update { it.copy(showVoiceEnable = true) }
            return
        }
        viewModelScope.launch { _events.send(ResultEvent.RequestMicPermission) }
    }

    fun enableVoice() {
        viewModelScope.launch {
            settingsRepository.update { it.copy(voiceEnabled = true) }
            _ui.update { it.copy(showVoiceEnable = false) }
            _events.send(ResultEvent.RequestMicPermission)
        }
    }

    fun dismissVoiceEnable() = _ui.update { it.copy(showVoiceEnable = false) }

    fun startListening() {
        listenJob?.cancel()
        tts.stop()
        listenJob = viewModelScope.launch {
            _ui.update { it.copy(listening = true, partialSpeech = "") }
            try {
                stt.listen(settings.value.language).collect { event ->
                    when (event) {
                        SpeechEvent.Ready -> Unit
                        is SpeechEvent.Partial -> _ui.update { it.copy(partialSpeech = event.text) }
                        is SpeechEvent.Final -> {
                            _ui.update { it.copy(listening = false, partialSpeech = "") }
                            ask(event.text, fromVoice = true)
                        }
                        is SpeechEvent.Error -> _ui.update { it.copy(snackbar = event.message) }
                    }
                }
            } finally {
                _ui.update { it.copy(listening = false, partialSpeech = "") }
            }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        _ui.update { it.copy(listening = false, partialSpeech = "") }
    }

    fun onMicPermissionDenied() =
        _ui.update { it.copy(snackbar = "Microphone permission denied. You can still type your question.") }

    // ---------------------------------------------------------------- actions

    fun toggleFavourite() {
        val s = session.value ?: return
        viewModelScope.launch {
            repository.setFavourite(sessionId, !s.isFavourite)
            if (!s.isSaved) repository.setSaved(sessionId, true)
        }
    }

    fun toggleSaved() {
        val s = session.value ?: return
        viewModelScope.launch {
            repository.setSaved(sessionId, !s.isSaved)
            _ui.update { it.copy(snackbar = if (s.isSaved) "Removed from history (deleted automatically after 24 h)" else "Saved to history") }
        }
    }

    fun updateNotes(notes: String) {
        notesJob?.cancel()
        notesJob = viewModelScope.launch {
            delay(600)
            repository.setNotes(sessionId, notes)
        }
    }

    fun delete() {
        viewModelScope.launch {
            tts.stop()
            repository.deleteSession(sessionId)
            _events.send(ResultEvent.Closed)
        }
    }

    fun deleteImages() {
        viewModelScope.launch {
            repository.deleteSessionImages(sessionId)
            bitmaps = emptyList()
            _ui.update { it.copy(snackbar = "Image deleted. The text results and conversation are kept.") }
        }
    }

    fun share() {
        val s = session.value ?: return
        viewModelScope.launch {
            val text = exporter.shareText(s, messages.value)
            val imageUri = s.thumbnailPath?.let { runCatching { exporter.uriFor(java.io.File(it)) }.getOrNull() }
            _events.send(ResultEvent.ShareText(text, imageUri))
        }
    }

    fun exportPdf() {
        val s = session.value ?: return
        viewModelScope.launch {
            _ui.update { it.copy(exporting = true) }
            try {
                val uri = exporter.exportPdf(s, messages.value)
                _events.send(ResultEvent.ShareFile(uri, "application/pdf"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(snackbar = "Couldn't create the report: ${e.message}") }
            } finally {
                _ui.update { it.copy(exporting = false) }
            }
        }
    }

    fun translateExtractedText(target: AppLanguage) {
        val text = session.value?.analysis?.extractedText ?: return
        viewModelScope.launch {
            _ui.update { it.copy(translating = true) }
            try {
                val result = ml.translate(text.take(4000), target)
                _ui.update { it.copy(translation = result.text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(snackbar = "Translation failed (the language model may need to download first — check your connection): ${e.message}") }
            } finally {
                _ui.update { it.copy(translating = false) }
            }
        }
    }

    fun consumeSnackbar() = _ui.update { it.copy(snackbar = null) }

    override fun onCleared() {
        super.onCleared()
        listenJob?.cancel()
        tts.stop()
    }
}
