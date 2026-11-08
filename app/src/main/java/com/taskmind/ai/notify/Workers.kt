package com.taskmind.ai.notify

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.taskmind.ai.ai.AssistantEngine
import com.taskmind.ai.call.ProcessSessionWorker
import com.taskmind.ai.data.db.AppDatabase
import com.taskmind.ai.data.db.SessionStatus
import com.taskmind.ai.data.db.isOverdue
import com.taskmind.ai.data.db.pendingCount
import com.taskmind.ai.data.prefs.SettingsRepository
import com.taskmind.ai.util.TimeUtil
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val wm get() = WorkManager.getInstance(context)

    suspend fun scheduleAll() {
        val s = settings.current()
        scheduleBriefing(BriefingWorker.MORNING, s.morningHour, s.morningMinute, s.morningBriefing, ExistingWorkPolicy.REPLACE)
        scheduleBriefing(BriefingWorker.EVENING, s.eveningHour, s.eveningMinute, s.eveningSummary, ExistingWorkPolicy.REPLACE)
        wm.enqueueUniquePeriodicWork(
            "periodic_checks",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CheckWorker>(1, TimeUnit.HOURS).build(),
        )
    }

    fun scheduleBriefing(kind: String, hour: Int, minute: Int, enabled: Boolean, policy: ExistingWorkPolicy) {
        val name = "briefing_$kind"
        if (!enabled) {
            wm.cancelUniqueWork(name)
            return
        }
        val now = TimeUtil.now()
        var target = now.toLocalDate().atTime(hour, minute)
        if (!target.isAfter(now.plusMinutes(1))) target = target.plusDays(1)
        val delay = Duration.between(now, target).toMillis()
        val request = OneTimeWorkRequestBuilder<BriefingWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(BriefingWorker.KEY_KIND to kind))
            .addTag("briefing")
            .build()
        wm.enqueueUniqueWork(name, policy, request)
    }

    fun enqueueSessionProcessing(sessionId: Long) {
        val request = OneTimeWorkRequestBuilder<ProcessSessionWorker>()
            .setInputData(workDataOf(ProcessSessionWorker.KEY_SESSION_ID to sessionId))
            .build()
        wm.enqueueUniqueWork("process_session_$sessionId", ExistingWorkPolicy.REPLACE, request)
    }
}

/** Morning briefing and evening unfinished-task summary. Re-schedules itself for the next day. */
@HiltWorker
class BriefingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val assistant: AssistantEngine,
    private val settings: SettingsRepository,
    private val scheduler: WorkScheduler,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val kind = inputData.getString(KEY_KIND) ?: MORNING
        val s = settings.current()
        val snap = assistant.snapshot()
        if (kind == MORNING && s.morningBriefing) {
            val name = snap.profile.displayName.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()
            Notifications.showBriefing(applicationContext, Notifications.ID_MORNING, "${TimeUtil.greeting()}$name — your briefing", assistant.localBriefing(snap))
        }
        if (kind == EVENING && s.eveningSummary) {
            val open = snap.dueToday.size + snap.overdue.size
            if (open > 0 || snap.completedToday.isNotEmpty()) {
                Notifications.showBriefing(applicationContext, Notifications.ID_EVENING, "Evening summary", assistant.localBriefing(snap, evening = true))
            }
        }
        val (h, m, enabled) = if (kind == MORNING) Triple(s.morningHour, s.morningMinute, s.morningBriefing)
        else Triple(s.eveningHour, s.eveningMinute, s.eveningSummary)
        scheduler.scheduleBriefing(kind, h, m, enabled, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        const val KEY_KIND = "kind"
        const val MORNING = "morning"
        const val EVENING = "evening"
    }
}

/** Hourly: newly overdue tasks and follow-up nudges for conversations nobody reviewed. */
@HiltWorker
class CheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val s = settings.current()
        val now = System.currentTimeMillis()

        if (s.overdueAlerts) {
            val overdue = db.taskDao().getPendingDueBefore(now).filter { it.isOverdue(now) }
            val already = settings.notifiedOverdueIds()
            val fresh = overdue.filter { it.id !in already }
            if (fresh.isNotEmpty()) Notifications.showOverdue(applicationContext, fresh)
            settings.setNotifiedOverdueIds(overdue.map { it.id }.toSet())
        }

        if (s.followUpReminders) {
            db.callDao().getRecent(20)
                .filter { it.session.status == SessionStatus.READY && it.pendingCount > 0 && it.session.reviewNudgedAt == null }
                .filter { now - (it.session.endedAt ?: it.session.startedAt) > 2 * 3600_000L }
                .forEach { sess ->
                    Notifications.showFollowUp(applicationContext, sess.session.id, sess.session.contactName ?: sess.session.title, sess.pendingCount)
                    db.callDao().updateSession(sess.session.copy(reviewNudgedAt = now))
                }
        }
        return Result.success()
    }
}
