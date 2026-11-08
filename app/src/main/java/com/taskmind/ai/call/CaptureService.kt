package com.taskmind.ai.call

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.SpeechRecognizer
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.taskmind.ai.data.db.CallDirection
import com.taskmind.ai.data.db.CallSessionEntity
import com.taskmind.ai.data.db.CaptureMode
import com.taskmind.ai.data.db.SessionStatus
import com.taskmind.ai.data.db.TranscriptSource
import com.taskmind.ai.data.prefs.SettingsRepository
import com.taskmind.ai.data.repo.CallRepository
import com.taskmind.ai.notify.Notifications
import com.taskmind.ai.notify.WorkScheduler
import com.taskmind.ai.util.TimeUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.max

/**
 * The ONLY place TaskMind uses the microphone in the background. It always runs as a visible
 * foreground service with an ongoing notification and a Stop button, and it only starts from
 * an explicit user action (notification button or in-app button).
 *
 * It never tries to read the cellular call stream (VOICE_CALL / VOICE_DOWNLINK need a
 * system-only permission). During a call it records the microphone, which on most phones only
 * hears the user's side unless speakerphone is on — and some phones silence it entirely.
 * We measure the audio level and tell the user honestly when nothing was captured.
 */
@AndroidEntryPoint
class CaptureService : Service() {

    @Inject lateinit var stateHolder: CaptureStateHolder
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var calls: CallRepository
    @Inject lateinit var workScheduler: WorkScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class Active(
        val startedAt: Long,
        val mode: CaptureMode,
        val direction: CallDirection,
        val isCall: Boolean,
        val title: String,
        val contact: String?,
        val language: String,
    )

    private var active: Active? = null
    private var recorder: AudioRecorderEngine? = null
    private var transcriber: LiveTranscriber? = null
    private var levelJob: Job? = null
    private var armTimeoutJob: Job? = null
    private var peakAmplitude = 0
    private var armedDirection: CallDirection? = null
    private var armedContact: String? = null
    private var finishing = false
    private var telephonyCallback: TelephonyCallback? = null
    private var phoneStateListener: PhoneStateListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM_CALL -> handleArm(intent)
            ACTION_START_MANUAL -> handleManual(intent)
            ACTION_STOP -> finish(userStopped = true)
            else -> if (active == null && armedDirection == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasPhoneState() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun startInForeground(title: String, text: String, startedAt: Long?, recording: Boolean): Boolean {
        val n = Notifications.captureNotification(this, title, text, startedAt, recording)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(Notifications.ID_CAPTURE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(Notifications.ID_CAPTURE, n)
            }
            true
        } catch (e: Exception) {
            Notifications.showCaptureProblem(
                this, null, "Couldn't start capture",
                "Android did not allow TaskMind to use the microphone from the background (${e.javaClass.simpleName}). " +
                    "Open TaskMind → Call Insights and start a recording from there.",
            )
            false
        }
    }

    private fun refreshNotification(title: String, text: String, startedAt: Long?, recording: Boolean) {
        Notifications.post(this, Notifications.ID_CAPTURE, Notifications.captureNotification(this, title, text, startedAt, recording))
    }

    // ------------------------------------------------------------------ Call capture

    private fun handleArm(intent: Intent) {
        Notifications.cancelCallPrompt(this)
        if (active != null || armedDirection != null) return
        if (!hasMic()) {
            // Must still satisfy startForegroundService(); a non-mic FGS is not declared, so stop at once.
            Notifications.showCaptureProblem(
                this, null, "Microphone permission needed",
                "Open TaskMind → Settings → Permissions and allow the microphone, then try again.",
            )
            stopSelf()
            return
        }
        val direction = intent.getStringExtra(EXTRA_DIRECTION)
            ?.let { v -> CallDirection.entries.firstOrNull { it.name == v } } ?: CallDirection.INCOMING
        armedDirection = direction
        armedContact = intent.getStringExtra(EXTRA_CONTACT)
        val ok = startInForeground(
            "AI Task Capture is armed",
            "Starts when the call connects. Let the other person know you're capturing.",
            null, false,
        )
        if (!ok) {
            stopSelf()
            return
        }
        stateHolder.set(CaptureState.Armed(direction, armedContact))
        registerCallState()
        val current = CallTracker.lastState(this)
        if (current == TelephonyManager.EXTRA_STATE_OFFHOOK) beginCapture(isCall = true, direction = direction, modeOverride = null, kind = null)
        // Safety: never stay armed forever (e.g. call was rejected while we were starting).
        armTimeoutJob = scope.launch {
            delay(3 * 60_000L)
            if (active == null) cancelArmed()
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerCallState() {
        if (!hasPhoneState()) return
        val tm = getSystemService(TelephonyManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onCallState(state)
                }
                tm.registerTelephonyCallback(mainExecutor, cb)
                telephonyCallback = cb
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = onCallState(state)
                }
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                phoneStateListener = l
            }
        } catch (_: SecurityException) {
        }
    }

    private fun unregisterCallState() {
        val tm = getSystemService(TelephonyManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { runCatching { tm.unregisterTelephonyCallback(it) } }
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let { runCatching { tm.listen(it, PhoneStateListener.LISTEN_NONE) } }
        }
        telephonyCallback = null
        phoneStateListener = null
    }

    private fun onCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                val dir = armedDirection
                if (active == null && dir != null) beginCapture(isCall = true, direction = dir, modeOverride = null, kind = null)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (active?.isCall == true) finish(userStopped = false)
                else if (active == null && armedDirection != null && CallTracker.lastState(this) == TelephonyManager.EXTRA_STATE_IDLE) cancelArmed()
            }
        }
    }

    private fun cancelArmed() {
        armedDirection = null
        stopEverything()
    }

    // ------------------------------------------------------------------ In-app capture

    private fun handleManual(intent: Intent) {
        if (active != null) return
        if (!hasMic()) {
            stopSelf()
            return
        }
        val kind = intent.getStringExtra(EXTRA_KIND)?.let { v -> CallDirection.entries.firstOrNull { it.name == v } } ?: CallDirection.IN_PERSON
        val mode = intent.getStringExtra(EXTRA_MODE)?.let { v -> CaptureMode.entries.firstOrNull { it.name == v } }
        val ok = startInForeground("Starting capture…", "Preparing the microphone", System.currentTimeMillis(), true)
        if (!ok) {
            stopSelf()
            return
        }
        armedContact = intent.getStringExtra(EXTRA_CONTACT)
        beginCapture(isCall = false, direction = kind, modeOverride = mode, kind = kind)
    }

    private fun beginCapture(isCall: Boolean, direction: CallDirection, modeOverride: CaptureMode?, kind: CallDirection?) {
        if (active != null) return
        armTimeoutJob?.cancel()
        scope.launch {
            val s = settings.current()
            var mode = modeOverride ?: s.captureMode
            if (mode == CaptureMode.LIVE_TRANSCRIPTION && !SpeechRecognizer.isRecognitionAvailable(this@CaptureService)) {
                mode = CaptureMode.AUDIO_RECORDING
            }
            val started = System.currentTimeMillis()
            val who = armedContact
            val title = when {
                kind == CallDirection.VOICE_NOTE -> "Voice note"
                isCall -> "${direction.label}${who?.let { " · $it" }.orEmpty()}"
                else -> "Conversation${who?.let { " with $it" }.orEmpty()}"
            }
            val a = Active(started, mode, direction, isCall, title, who, s.speechLanguage)
            active = a
            peakAmplitude = 0
            try {
                when (mode) {
                    CaptureMode.LIVE_TRANSCRIPTION -> {
                        transcriber = LiveTranscriber(this@CaptureService, s.speechLanguage) { txt ->
                            val cur = stateHolder.state.value
                            if (cur is CaptureState.Active) stateHolder.set(cur.copy(preview = txt.takeLast(400)))
                        }.also { it.start() }
                    }
                    CaptureMode.AUDIO_RECORDING -> {
                        val file = File(cacheDir, "capture_$started.m4a")
                        recorder = AudioRecorderEngine(this@CaptureService).also { it.start(file) }
                        levelJob = scope.launch {
                            while (isActive) {
                                val amp = recorder?.maxAmplitude() ?: 0
                                peakAmplitude = max(peakAmplitude, amp)
                                val cur = stateHolder.state.value
                                if (cur is CaptureState.Active) stateHolder.set(cur.copy(level = (amp / 12_000f).coerceIn(0f, 1f)))
                                delay(250)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                active = null
                Notifications.showCaptureProblem(
                    this@CaptureService, null, "Microphone unavailable",
                    "Another app or the phone call is using the microphone (${e.message ?: e.javaClass.simpleName}). " +
                        "Try again after the call, add a voice note, or import your dialer's recording.",
                )
                stopEverything()
                return@launch
            }
            stateHolder.set(CaptureState.Active(started, mode, direction, isCall, title))
            val verb = if (mode == CaptureMode.LIVE_TRANSCRIPTION) "Transcribing" else "Recording"
            refreshNotification(
                "● $verb — $title",
                if (isCall) "Capture active for this call. Ends automatically when you hang up." else "Tap Stop when you're done.",
                started, true,
            )
        }
    }

    // ------------------------------------------------------------------ Finish

    private fun finish(userStopped: Boolean) {
        if (finishing) return
        val a = active
        if (a == null) {
            cancelArmed()
            return
        }
        finishing = true
        stateHolder.set(CaptureState.Finishing)
        scope.launch {
            val text = transcriber?.stop().orEmpty()
            val permissionDenied = transcriber?.permissionDenied == true
            transcriber = null
            levelJob?.cancel()
            val audio = recorder?.stop()
            recorder = null
            val ended = System.currentTimeMillis()
            val duration = (ended - a.startedAt) / 1000
            val nothingHeard = when (a.mode) {
                CaptureMode.LIVE_TRANSCRIPTION -> text.isBlank()
                CaptureMode.AUDIO_RECORDING -> audio == null || peakAmplitude < SILENCE_THRESHOLD
            }
            val tooShort = userStopped && duration < 2 && text.isBlank()
            if (tooShort) {
                audio?.delete()
                stopEverything()
                return@launch
            }
            withContext(Dispatchers.IO + NonCancellable) {
                val id = calls.createSession(
                    CallSessionEntity(
                        title = "${a.title} · ${TimeUtil.formatTime(a.startedAt)}",
                        contactName = a.contact,
                        phoneNumber = if (a.isCall) CallTracker.recentIncomingNumber(this@CaptureService) else null,
                        direction = a.direction,
                        captureMode = a.mode,
                        startedAt = a.startedAt,
                        endedAt = ended,
                        durationSec = duration,
                        status = SessionStatus.PROCESSING,
                        audioBlocked = a.isCall && nothingHeard,
                        consentConfirmed = true,
                        lastError = if (permissionDenied) "Speech recognition permission was denied." else null,
                    )
                )
                if (text.isNotBlank()) {
                    val src = if (a.direction == CallDirection.VOICE_NOTE) TranscriptSource.VOICE_NOTE else TranscriptSource.LIVE_ON_DEVICE
                    calls.addTranscript(id, text, src, a.language)
                }
                if (audio != null && peakAmplitude > 0) calls.storeEncryptedAudio(id, audio, "audio/mp4") else audio?.delete()
                workScheduler.enqueueSessionProcessing(id)
            }
            stopEverything()
        }
    }

    private fun stopEverything() {
        armTimeoutJob?.cancel()
        unregisterCallState()
        active = null
        armedDirection = null
        finishing = false
        stateHolder.set(CaptureState.Idle)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        // System is killing us mid-capture: release hardware, don't leave a hot microphone.
        runCatching { transcriber?.stop() }
        runCatching { recorder?.stop()?.delete() }
        unregisterCallState()
        stateHolder.set(CaptureState.Idle)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_ARM_CALL = "com.taskmind.ai.capture.ARM_CALL"
        const val ACTION_START_MANUAL = "com.taskmind.ai.capture.START_MANUAL"
        const val ACTION_STOP = "com.taskmind.ai.capture.STOP"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_CONTACT = "contact"
        const val EXTRA_KIND = "kind"
        const val EXTRA_MODE = "mode"

        /** Peak MediaRecorder amplitude below this means the phone gave us silence. */
        const val SILENCE_THRESHOLD = 400

        fun startManual(context: Context, kind: CallDirection, mode: CaptureMode?, contact: String?) {
            val i = Intent(context, CaptureService::class.java)
                .setAction(ACTION_START_MANUAL)
                .putExtra(EXTRA_KIND, kind.name)
                .putExtra(EXTRA_MODE, mode?.name)
                .putExtra(EXTRA_CONTACT, contact)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
