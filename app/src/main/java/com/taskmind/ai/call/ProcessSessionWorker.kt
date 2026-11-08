package com.taskmind.ai.call

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.taskmind.ai.ai.AiEngine
import com.taskmind.ai.data.db.SessionStatus
import com.taskmind.ai.data.db.TranscriptSource
import com.taskmind.ai.data.db.transcriptText
import com.taskmind.ai.data.prefs.SettingsRepository
import com.taskmind.ai.data.repo.CallRepository
import com.taskmind.ai.data.repo.ProfileRepository
import com.taskmind.ai.notify.Notifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * After a capture/import: transcribe (only with consent + OpenAI key), extract tasks, then ask the
 * user to review. Nothing becomes a task until the user approves it.
 */
@HiltWorker
class ProcessSessionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val calls: CallRepository,
    private val ai: AiEngine,
    private val settings: SettingsRepository,
    private val profile: ProfileRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_SESSION_ID, -1L)
        val details = calls.getSessionWithDetails(id) ?: return Result.success()
        val session = details.session
        val s = settings.current()
        val title = session.contactName ?: session.title
        var transcript = details.transcriptText

        if (transcript.isBlank()) {
            if (session.audioPath != null && ai.canTranscribeAudio()) {
                calls.setStatus(id, SessionStatus.PROCESSING)
                val file = calls.decryptAudioToCache(session)
                try {
                    if (file == null) throw IllegalStateException("Recording file is missing")
                    val text = ai.transcribe(file, session.audioMime ?: "audio/mp4")
                    calls.addTranscript(id, text, TranscriptSource.CLOUD_TRANSCRIPTION, s.speechLanguage)
                    transcript = text
                    if (!s.keepAudioAfterTranscription) calls.deleteRecording(id)
                } catch (e: Exception) {
                    val msg = "Transcription failed: ${e.message ?: e.javaClass.simpleName}. You can retry or type the key points."
                    calls.setStatus(id, SessionStatus.NEEDS_TRANSCRIPT, msg)
                    if (s.postCallReview) Notifications.showCaptureProblem(applicationContext, id, "Couldn't transcribe $title", msg)
                    return Result.success()
                } finally {
                    file?.delete()
                }
            } else {
                val msg = when {
                    session.audioBlocked ->
                        "Your phone did not give TaskMind any call audio (Android restricts call audio for third-party apps). " +
                            "Import the recording from your dialer, or add a quick voice note of what was agreed."
                    session.audioPath != null ->
                        "Recording saved and encrypted on this phone. To turn it into text, add an OpenAI key and allow audio " +
                            "transcription in Settings — or type the key points yourself."
                    else -> "Nothing was captured. Add a voice note or type what was agreed."
                }
                calls.setStatus(id, SessionStatus.NEEDS_TRANSCRIPT, msg)
                if (s.postCallReview) Notifications.showCaptureProblem(applicationContext, id, "Add details for $title", msg)
                return Result.success()
            }
        }

        if (transcript.isBlank()) {
            calls.setStatus(id, SessionStatus.NEEDS_TRANSCRIPT, "The transcript is empty.")
            return Result.success()
        }

        return try {
            val contact = session.contactName ?: details.contact?.name
            val outcome = ai.extract(transcript, contact, profile.get().displayName)
            calls.applyExtraction(id, outcome.value, outcome.engine, outcome.note)
            if (s.postCallReview) {
                val actionable = outcome.value.items.count { it.type.actionable }
                Notifications.showPostCallReview(applicationContext, id, title, actionable, outcome.value.summary)
            }
            Result.success()
        } catch (e: Exception) {
            calls.setStatus(id, SessionStatus.FAILED, e.message)
            Result.success()
        }
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"
    }
}
