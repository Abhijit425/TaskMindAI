package com.taskmind.ai.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.taskmind.ai.MainActivity
import com.taskmind.ai.R
import com.taskmind.ai.call.CaptureService
import com.taskmind.ai.data.db.CallDirection
import com.taskmind.ai.data.db.TaskEntity
import com.taskmind.ai.util.TimeUtil

object DeepLink {
    const val EXTRA_TASK_ID = "open_task_id"
    const val EXTRA_SESSION_ID = "open_session_id"
    const val EXTRA_ROUTE = "open_route"
}

object Notifications {
    const val CH_REMINDERS = "reminders"
    const val CH_OVERDUE = "overdue"
    const val CH_BRIEFING = "briefing"
    const val CH_CALL_PROMPT = "call_prompt"
    const val CH_CAPTURE = "capture_active"
    const val CH_REVIEW = "post_call_review"
    const val CH_FOLLOWUP = "follow_up"

    const val ID_CALL_PROMPT = 2001
    const val ID_CAPTURE = 2002
    const val ID_MORNING = 4001
    const val ID_EVENING = 4002
    const val ID_OVERDUE = 4003
    fun idTask(taskId: Long) = 10_000 + (taskId % 1_000_000).toInt()
    fun idReview(sessionId: Long) = 3_000_000 + (sessionId % 1_000_000).toInt()
    fun idFollowUp(sessionId: Long) = 5_000_000 + (sessionId % 1_000_000).toInt()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channels = listOf(
            NotificationChannel(CH_REMINDERS, "Task reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Deadline and custom reminders for your tasks"
            },
            NotificationChannel(CH_OVERDUE, "Overdue tasks", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts when tasks become overdue"
            },
            NotificationChannel(CH_BRIEFING, "Daily briefings", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Morning briefing and evening unfinished-task summary"
            },
            NotificationChannel(CH_CALL_PROMPT, "Call capture prompts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks whether to capture tasks from a call. Silent so it never competes with your ringtone."
                setSound(null, null)
                enableVibration(false)
            },
            NotificationChannel(CH_CAPTURE, "Capture in progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Always shown while TaskMind is recording or transcribing"
                setShowBadge(false)
            },
            NotificationChannel(CH_REVIEW, "Post-call task review", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tasks found in a conversation, waiting for your approval"
            },
            NotificationChannel(CH_FOLLOWUP, "Follow-up reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Nudges for conversations you have not reviewed yet"
            },
        )
        nm.createNotificationChannels(channels)
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun post(context: Context, id: Int, notification: Notification) {
        if (!canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    fun openAppIntent(context: Context, requestCode: Int, taskId: Long? = null, sessionId: Long? = null, route: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            taskId?.let { putExtra(DeepLink.EXTRA_TASK_ID, it) }
            sessionId?.let { putExtra(DeepLink.EXTRA_SESSION_ID, it) }
            route?.let { putExtra(DeepLink.EXTRA_ROUTE, it) }
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionBroadcast(context: Context, action: String, requestCode: Int, taskId: Long = 0, sessionId: Long = 0): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(NotificationActionReceiver.EXTRA_TASK_ID, taskId)
            .putExtra(NotificationActionReceiver.EXTRA_SESSION_ID, sessionId)
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun base(context: Context, channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_taskmind)
        .setColor(0xFF6D5EF5.toInt())

    // ---------------------------------------------------------------- Tasks

    fun showTaskReminder(context: Context, task: TaskEntity, early: Boolean) {
        val id = idTask(task.id)
        val due = task.dueAt?.let { TimeUtil.formatDue(it, task.hasDueTime) }
        val title = if (early) "Coming up: ${task.title}" else task.title
        val text = when {
            due == null -> "Reminder"
            early -> "Due $due"
            else -> "Due $due"
        } + (task.assignedBy?.let { " · from $it" } ?: "")
        val n = base(context, CH_REMINDERS)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (task.notes.isNotBlank()) "$text\n${task.notes.take(300)}" else text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, id, taskId = task.id))
            .addAction(0, "Done", actionBroadcast(context, NotificationActionReceiver.ACTION_COMPLETE, id * 10 + 1, taskId = task.id))
            .addAction(0, "Snooze 1 h", actionBroadcast(context, NotificationActionReceiver.ACTION_SNOOZE, id * 10 + 2, taskId = task.id))
            .build()
        post(context, id, n)
    }

    fun showOverdue(context: Context, tasks: List<TaskEntity>) {
        if (tasks.isEmpty()) return
        val title = if (tasks.size == 1) "Overdue: ${tasks.first().title}" else "${tasks.size} tasks are overdue"
        val style = NotificationCompat.InboxStyle()
        tasks.take(6).forEach { t -> style.addLine("• ${t.title}" + (t.dueAt?.let { " — ${TimeUtil.overdueBy(it)}" } ?: "")) }
        val n = base(context, CH_OVERDUE)
            .setContentTitle(title)
            .setContentText(tasks.first().dueAt?.let { TimeUtil.overdueBy(it) } ?: "Overdue")
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, ID_OVERDUE, route = "tasks"))
            .build()
        post(context, ID_OVERDUE, n)
    }

    fun showBriefing(context: Context, id: Int, title: String, text: String) {
        val n = base(context, CH_BRIEFING)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, id, route = "home"))
            .build()
        post(context, id, n)
    }

    // ---------------------------------------------------------------- Calls

    fun showCallPrompt(context: Context, direction: CallDirection, displayName: String?) {
        val armIntent = Intent(context, CaptureService::class.java)
            .setAction(CaptureService.ACTION_ARM_CALL)
            .putExtra(CaptureService.EXTRA_DIRECTION, direction.name)
            .putExtra(CaptureService.EXTRA_CONTACT, displayName)
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // Without the microphone permission, "Enable" opens the permission screen instead of the service.
        val armPi = if (micGranted) {
            PendingIntent.getForegroundService(context, 7001, armIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            openAppIntent(context, 7006, route = "permissions")
        }
        val label = if (direction == CallDirection.OUTGOING) "Outgoing call detected" else "Incoming call detected"
        val who = displayName?.let { " · $it" } ?: ""
        val n = base(context, CH_CALL_PROMPT)
            .setContentTitle("$label$who")
            .setContentText("Enable AI Task Capture?")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Enable AI Task Capture? TaskMind will show a recording indicator for the whole call. " +
                        "Tell everyone on the call before you start."
                )
            )
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .addAction(0, "Enable for this call", armPi)
            .addAction(0, "Not now", actionBroadcast(context, NotificationActionReceiver.ACTION_DISMISS_PROMPT, 7002))
            .build()
        post(context, ID_CALL_PROMPT, n)
    }

    fun cancelCallPrompt(context: Context) = cancel(context, ID_CALL_PROMPT)

    fun captureNotification(context: Context, title: String, text: String, startedAt: Long?, active: Boolean): Notification {
        val stopIntent = Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)
        val stopPi = PendingIntent.getService(context, 7003, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return base(context, CH_CAPTURE)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setUsesChronometer(active && startedAt != null)
            .setWhen(startedAt ?: System.currentTimeMillis())
            .setShowWhen(active)
            .setColorized(active)
            .setColor(if (active) 0xFFE5484D.toInt() else 0xFF6D5EF5.toInt())
            .setContentIntent(openAppIntent(context, 7004, route = "calls"))
            .addAction(0, if (active) "Stop capture" else "Cancel", stopPi)
            .build()
    }

    fun showPostCallReview(context: Context, sessionId: Long, title: String, actionable: Int, summary: String?) {
        val id = idReview(sessionId)
        val text = if (actionable > 0) "$actionable item${if (actionable > 1) "s" else ""} to review" else "No tasks detected — tap to review the transcript"
        val n = base(context, CH_REVIEW)
            .setContentTitle("Review tasks from $title")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(text, summary).joinToString("\n")))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, id, sessionId = sessionId))
            .build()
        post(context, id, n)
    }

    fun showCaptureProblem(context: Context, sessionId: Long?, title: String, message: String) {
        val id = sessionId?.let { idReview(it) } ?: 7005
        val n = base(context, CH_REVIEW)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, id, sessionId = sessionId, route = if (sessionId == null) "calls" else null))
            .build()
        post(context, id, n)
    }

    fun showFollowUp(context: Context, sessionId: Long, title: String, pending: Int) {
        val id = idFollowUp(sessionId)
        val n = base(context, CH_FOLLOWUP)
            .setContentTitle("Don't lose what was agreed")
            .setContentText("$pending item${if (pending > 1) "s" else ""} from $title still need your review")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, id, sessionId = sessionId))
            .build()
        post(context, id, n)
    }
}
