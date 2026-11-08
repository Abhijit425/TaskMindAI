package com.taskmind.ai.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import com.taskmind.ai.ai.AiEngine
import com.taskmind.ai.backup.BackupManager
import com.taskmind.ai.data.db.CaptureMode
import com.taskmind.ai.data.db.UserSettingsEntity
import com.taskmind.ai.data.prefs.AiProvider
import com.taskmind.ai.data.prefs.AppSettings
import com.taskmind.ai.data.prefs.SettingsRepository
import com.taskmind.ai.data.prefs.ThemeMode
import com.taskmind.ai.data.repo.CallRepository
import com.taskmind.ai.data.repo.ProfileRepository
import com.taskmind.ai.data.repo.TaskRepository
import com.taskmind.ai.notify.BriefingWorker
import com.taskmind.ai.notify.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val profileRepo: ProfileRepository,
    private val ai: AiEngine,
    private val backup: BackupManager,
    private val tasks: TaskRepository,
    private val calls: CallRepository,
    private val work: WorkScheduler,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val profile: StateFlow<UserSettingsEntity?> = profileRepo.observe().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun consume() { _message.value = null }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    fun setName(v: String) = io { profileRepo.update { it.copy(displayName = v.trim()) } }
    fun setManager(v: String) = io { profileRepo.update { it.copy(managerName = v.trim()) } }

    fun setProvider(p: AiProvider) = io { repo.setProvider(p) }
    fun setOpenAiModel(v: String) = io { repo.setOpenAiModel(v) }
    fun setAnthropicModel(v: String) = io { repo.setAnthropicModel(v) }
    fun setTranscriptionModel(v: String) = io { repo.setTranscriptionModel(v) }

    fun saveKey(p: AiProvider, key: String) = io {
        if (key.isBlank()) return@io
        repo.saveApiKey(p, key)
        _message.value = "${p.label} key saved (encrypted with the Android Keystore)"
    }

    fun clearKey(p: AiProvider) = io {
        repo.clearApiKey(p)
        _message.value = "${p.label} key removed"
    }

    fun test(p: AiProvider) = io {
        _busy.value = true
        _message.value = runCatching { ai.testConnection(p) }.getOrElse { "Test failed: ${it.message}" }
        _busy.value = false
    }

    fun setCloudTasks(v: Boolean) = io { repo.setCloudTasksConsent(v) }
    fun setCloudTranscripts(v: Boolean) = io { repo.setCloudTranscriptsConsent(v) }
    fun setCloudAudio(v: Boolean) = io { repo.setCloudAudioConsent(v) }

    fun setReminderTime(h: Int, m: Int) = io { repo.setDefaultReminderTime(h, m); tasks.rescheduleAll() }
    fun setLead(min: Int) = io { repo.setReminderLead(min); tasks.rescheduleAll() }
    fun setDayFirst(v: Boolean) = io { repo.setDayFirst(v) }

    fun enableCallPrompts(v: Boolean) = io {
        if (v) repo.setCallConsentAcknowledged(true)
        repo.setCallPrompts(v)
    }
    fun setCaptureMode(m: CaptureMode) = io { repo.setCaptureMode(m) }
    fun setKeepAudio(v: Boolean) = io { repo.setKeepAudio(v) }
    fun setLookupContacts(v: Boolean) = io { repo.setLookupContacts(v) }

    fun setDeadline(v: Boolean) = io { repo.setDeadlineReminders(v); tasks.rescheduleAll() }
    fun setOverdue(v: Boolean) = io { repo.setOverdueAlerts(v) }
    fun setMorning(v: Boolean) = io { repo.setMorningBriefing(v); work.scheduleAll() }
    fun setMorningTime(h: Int, m: Int) = io {
        repo.setMorningTime(h, m)
        work.scheduleBriefing(BriefingWorker.MORNING, h, m, true, ExistingWorkPolicy.REPLACE)
    }
    fun setEvening(v: Boolean) = io { repo.setEveningSummary(v); work.scheduleAll() }
    fun setEveningTime(h: Int, m: Int) = io {
        repo.setEveningTime(h, m)
        work.scheduleBriefing(BriefingWorker.EVENING, h, m, true, ExistingWorkPolicy.REPLACE)
    }
    fun setFollowUp(v: Boolean) = io { repo.setFollowUpReminders(v) }
    fun setPostCall(v: Boolean) = io { repo.setPostCallReview(v) }

    fun setSpeechLanguage(tag: String) = io { repo.setSpeechLanguage(tag) }
    fun setSpeak(v: Boolean) = io { repo.setSpeakReplies(v) }
    fun setTheme(t: ThemeMode) = io { repo.setTheme(t) }
    fun setDynamic(v: Boolean) = io { repo.setDynamicColor(v) }

    fun export(uri: Uri, includeTranscripts: Boolean) = io {
        _busy.value = true
        _message.value = runCatching { "Exported ${backup.export(uri, includeTranscripts)} tasks" }.getOrElse { "Export failed: ${it.message}" }
        _busy.value = false
    }

    fun import(uri: Uri) = io {
        _busy.value = true
        _message.value = runCatching { "Restored ${backup.import(uri)} tasks" }.getOrElse { "Import failed: ${it.message}" }
        _busy.value = false
    }

    fun deleteRecordings() = io {
        calls.deleteAllRecordings()
        _message.value = "All recordings deleted"
    }

    fun deleteEverything() = io {
        backup.deleteEverything()
        _message.value = "All TaskMind data was deleted from this phone"
    }
}
