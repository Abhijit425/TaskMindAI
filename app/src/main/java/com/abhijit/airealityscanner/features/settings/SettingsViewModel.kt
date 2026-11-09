package com.abhijit.airealityscanner.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhijit.airealityscanner.ai.AiEngine
import com.abhijit.airealityscanner.core.voice.TextToSpeechManager
import com.abhijit.airealityscanner.core.voice.VoiceAvailability
import com.abhijit.airealityscanner.domain.model.AiProviderType
import com.abhijit.airealityscanner.domain.model.AppSettings
import com.abhijit.airealityscanner.domain.repository.ScanRepository
import com.abhijit.airealityscanner.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val testing: Boolean = false,
    val testResult: String? = null,
    val testOk: Boolean = false,
    val voiceStatus: VoiceAvailability? = null,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val scanRepository: ScanRepository,
    private val aiEngine: AiEngine,
    private val tts: TextToSpeechManager,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    fun saveApiKey(provider: AiProviderType, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            settingsRepository.setApiKey(provider, trimmed)
            _ui.update { it.copy(message = "${provider.displayName} key saved (encrypted on this phone).", testResult = null) }
        }
    }

    fun clearApiKey(provider: AiProviderType) {
        viewModelScope.launch {
            settingsRepository.setApiKey(provider, null)
            _ui.update { it.copy(message = "${provider.displayName} key removed.", testResult = null) }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _ui.update { it.copy(testing = true, testResult = null) }
            try {
                val result = aiEngine.testConnection()
                _ui.update { it.copy(testResult = result, testOk = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(testResult = e.message ?: "Connection failed", testOk = false) }
            } finally {
                _ui.update { it.copy(testing = false) }
            }
        }
    }

    fun checkVoice() {
        tts.checkAvailability(settings.value.language) { status -> _ui.update { it.copy(voiceStatus = status) } }
    }

    fun testVoice() {
        val s = settings.value
        val sample = when (s.language) {
            com.abhijit.airealityscanner.domain.model.AppLanguage.ENGLISH -> "Hello! I am your AI Reality Scanner."
            com.abhijit.airealityscanner.domain.model.AppLanguage.HINDI -> "नमस्ते! मैं आपका एआई रियलिटी स्कैनर हूँ।"
            com.abhijit.airealityscanner.domain.model.AppLanguage.BENGALI -> "নমস্কার! আমি আপনার এআই রিয়েলিটি স্ক্যানার।"
        }
        tts.speak(sample, s.language, s.speechRate)
    }

    fun deleteAllHistory() {
        viewModelScope.launch {
            scanRepository.deleteAllHistory()
            _ui.update { it.copy(message = "All scans, images and conversations were deleted.") }
        }
    }

    fun deleteAllImages() {
        viewModelScope.launch {
            scanRepository.deleteAllImages()
            _ui.update { it.copy(message = "All scan images were deleted. Text results were kept.") }
        }
    }

    fun consumeMessage() = _ui.update { it.copy(message = null) }
}
