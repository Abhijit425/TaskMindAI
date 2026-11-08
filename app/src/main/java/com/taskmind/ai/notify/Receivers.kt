package com.taskmind.ai.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.taskmind.ai.call.CaptureStateHolder
import com.taskmind.ai.data.db.AppDatabase
import com.taskmind.ai.data.db.ReminderKind
import com.taskmind.ai.data.db.isCompleted
import com.taskmind.ai.data.prefs.SettingsRepository
import com.taskmind.ai.data.repo.CallRepository
import com.taskmind.ai.data.repo.TaskRepository
import com.taskmind.ai.di.ApplicationScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Dependency access for BroadcastReceivers (kept free of Hilt bytecode transforms). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoint {
    fun database(): AppDatabase
    fun settings(): SettingsRepository
    fun taskRepository(): TaskRepository
    fun callRepository(): CallRepository
    fun workScheduler(): WorkScheduler
    fun captureState(): CaptureStateHolder

    @ApplicationScope
    fun appScope(): CoroutineScope
}

fun Context.appEntryPoint(): AppEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, AppEntryPoint::class.java)

/** Runs [block] off the main thread while keeping the receiver alive. */
fun BroadcastReceiver.runAsync(context: Context, block: suspend (AppEntryPoint) -> Unit) {
    val pending = goAsync()
    val ep = context.appEntryPoint()
    ep.appScope().launch {
        try {
            block(ep)
        } catch (_: Exception) {
        } finally {
            pending.finish()
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        if (reminderId < 0) return
        runAsync(context) { ep ->
            val db = ep.database()
            val reminder = db.reminderDao().getById(reminderId) ?: return@runAsync
            db.reminderDao().markFired(reminderId)
            val task = db.taskDao().getTask(reminder.taskId) ?: return@runAsync
            if (task.isCompleted) return@runAsync
            val s = ep.settings().current()
            val userSet = reminder.kind == ReminderKind.CUSTOM || reminder.kind == ReminderKind.SNOOZE
            if (!s.deadlineReminders && !userSet) return@runAsync
            Notifications.showTaskReminder(context, task, early = reminder.kind == ReminderKind.EARLY)
        }
    }

    companion object {
        const val ACTION_FIRE = "com.taskmind.ai.REMINDER_FIRE"
        const val EXTRA_REMINDER_ID = "reminder_id"
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        when (intent.action) {
            ACTION_DISMISS_PROMPT -> Notifications.cancelCallPrompt(context)
            ACTION_COMPLETE -> {
                Notifications.cancel(context, Notifications.idTask(taskId))
                runAsync(context) { it.taskRepository().setCompleted(taskId, true) }
            }
            ACTION_SNOOZE -> {
                Notifications.cancel(context, Notifications.idTask(taskId))
                runAsync(context) { it.taskRepository().snooze(taskId, 60) }
            }
        }
    }

    companion object {
        const val ACTION_COMPLETE = "com.taskmind.ai.ACTION_COMPLETE"
        const val ACTION_SNOOZE = "com.taskmind.ai.ACTION_SNOOZE"
        const val ACTION_DISMISS_PROMPT = "com.taskmind.ai.ACTION_DISMISS_PROMPT"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_SESSION_ID = "session_id"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ok = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        if (!ok) return
        runAsync(context) { ep ->
            ep.taskRepository().rescheduleAll()
            ep.workScheduler().scheduleAll()
        }
    }
}
