package com.taskmind.ai.data.db

import kotlinx.serialization.Serializable

@Serializable
enum class Priority(val label: String, val weight: Int) {
    LOW("Low", 0), MEDIUM("Medium", 1), HIGH("High", 2), URGENT("Urgent", 3);

    companion object {
        fun fromWeight(w: Int): Priority = entries.firstOrNull { it.weight == w } ?: MEDIUM
        fun parse(s: String?): Priority? = entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) }
    }
}

@Serializable
enum class TaskStatus { PENDING, COMPLETED }

@Serializable
enum class Recurrence(val label: String) {
    NONE("Does not repeat"), DAILY("Every day"), WEEKDAYS("Every weekday"), WEEKLY("Every week"), MONTHLY("Every month");

    companion object {
        fun parse(s: String?): Recurrence = entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: NONE
    }
}

@Serializable
enum class TaskSource(val label: String) {
    MANUAL("Added manually"), NATURAL_LANGUAGE("Natural language"), VOICE("Voice command"),
    CALL("Captured conversation"), ASSISTANT("AI assistant")
}

@Serializable
enum class ReminderKind { DEADLINE, EARLY, CUSTOM, SNOOZE }

@Serializable
enum class CallDirection(val label: String) {
    INCOMING("Incoming call"), OUTGOING("Outgoing call"), IN_PERSON("Recorded conversation"),
    VOICE_NOTE("Voice note"), IMPORTED("Imported recording"), MANUAL_TRANSCRIPT("Pasted transcript")
}

@Serializable
enum class CaptureMode(val label: String) {
    LIVE_TRANSCRIPTION("Live on-device transcription"), AUDIO_RECORDING("Audio recording (transcribe later)")
}

@Serializable
enum class SessionStatus(val label: String) {
    CAPTURING("Capturing"), PROCESSING("Analyzing"), NEEDS_TRANSCRIPT("Needs transcript"),
    READY("Ready"), FAILED("Failed")
}

@Serializable
enum class TranscriptSource(val label: String) {
    LIVE_ON_DEVICE("On-device speech recognition"), CLOUD_TRANSCRIPTION("Cloud transcription"),
    MANUAL("Entered manually"), VOICE_NOTE("Voice note")
}

@Serializable
enum class ActionType(val label: String, val actionable: Boolean) {
    TASK("Task", true),
    COMMITMENT("Commitment", true),
    PROMISE("Promise", true),
    FOLLOW_UP("Follow-up", true),
    MEETING("Meeting", true),
    DEADLINE("Deadline", true),
    DELIVERABLE("Deliverable", true),
    REMINDER("Reminder", true),
    EMAIL("Email follow-up", true),
    NAME("Person", false),
    PHONE("Phone number", false);

    companion object {
        fun parse(s: String?): ActionType? = entries.firstOrNull {
            it.name.equals(s?.trim()?.replace('-', '_')?.replace(' ', '_'), ignoreCase = true)
        }
    }
}

@Serializable
enum class ActionStatus { PENDING, APPROVED, REJECTED }
