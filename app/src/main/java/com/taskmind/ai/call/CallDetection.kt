package com.taskmind.ai.call

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.taskmind.ai.data.db.CallDirection
import com.taskmind.ai.data.db.CaptureMode
import com.taskmind.ai.notify.Notifications
import com.taskmind.ai.notify.runAsync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface CaptureState {
    data object Idle : CaptureState
    data class Armed(val direction: CallDirection, val contact: String?) : CaptureState
    data class Active(
        val startedAt: Long,
        val mode: CaptureMode,
        val direction: CallDirection,
        val isCall: Boolean,
        val title: String,
        val level: Float = 0f,
        val preview: String = "",
    ) : CaptureState
    data object Finishing : CaptureState
}

/** Single source of truth for the visible capture indicator in the app UI. */
@Singleton
class CaptureStateHolder @Inject constructor() {
    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()
    fun set(s: CaptureState) {
        _state.value = s
    }
}

/** Tiny persistent call-state memory (the process may be restarted between broadcasts). */
object CallTracker {
    private const val PREFS = "call_tracker"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastState(context: Context): String =
        prefs(context).getString("state", TelephonyManager.EXTRA_STATE_IDLE) ?: TelephonyManager.EXTRA_STATE_IDLE

    fun setLastState(context: Context, state: String) {
        prefs(context).edit().putString("state", state).putLong("state_at", System.currentTimeMillis()).apply()
    }

    fun setIncomingNumber(context: Context, number: String?) {
        prefs(context).edit().putString("number", number).putLong("number_at", System.currentTimeMillis()).apply()
    }

    /** Number reported by the (optional) call screening service in the last two minutes. */
    fun recentIncomingNumber(context: Context): String? {
        val p = prefs(context)
        val at = p.getLong("number_at", 0)
        return if (System.currentTimeMillis() - at < 120_000) p.getString("number", null) else null
    }
}

object ContactLookup {
    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun nameFor(context: Context, number: String?): String? {
        if (number.isNullOrBlank() || !hasPermission(context)) return null
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
}

/**
 * Listens to the public PHONE_STATE broadcast (needs READ_PHONE_STATE, granted only when the
 * user turns on call prompts). It never touches call audio: it only posts a question.
 */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        runAsync(context) { ep ->
            val previous = CallTracker.lastState(context)
            CallTracker.setLastState(context, state)
            if (state == previous) return@runAsync
            val s = ep.settings().current()
            val enabled = s.callPromptsEnabled && s.callConsentAcknowledged
            val idle = ep.captureState().state.value is CaptureState.Idle
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> if (enabled && idle) {
                    Notifications.showCallPrompt(context, CallDirection.INCOMING, resolveName(context, ep, s.lookupContactNames))
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> if (enabled && idle && previous == TelephonyManager.EXTRA_STATE_IDLE) {
                    // Off-hook straight from idle = the user placed an outgoing call.
                    Notifications.showCallPrompt(context, CallDirection.OUTGOING, null)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> Notifications.cancelCallPrompt(context)
            }
        }
    }

    private suspend fun resolveName(context: Context, ep: com.taskmind.ai.notify.AppEntryPoint, lookup: Boolean): String? {
        val number = CallTracker.recentIncomingNumber(context) ?: return null
        ep.callRepository().findContactName(number)?.let { return it.name }
        return if (lookup) ContactLookup.nameFor(context, number) ?: number else number
    }
}

/**
 * Optional. Active only if the user grants TaskMind the Call Screening role in Settings.
 * It records the caller number for the prompt and ALWAYS lets the call through unchanged.
 */
class TaskMindScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection == Call.Details.DIRECTION_INCOMING) {
            CallTracker.setIncomingNumber(this, callDetails.handle?.schemeSpecificPart)
        }
        respondToCall(callDetails, CallResponse.Builder().build())
    }
}
