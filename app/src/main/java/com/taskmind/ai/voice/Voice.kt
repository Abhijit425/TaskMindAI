package com.taskmind.ai.voice

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** One-shot voice input for commands and dictation, with on-screen partial results. */
class SpeechInputState internal constructor(private val context: Context) {
    var listening by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
        private set
    var level by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)

    internal var languageTag: String = "en-IN"
    internal var onFinal: (String) -> Unit = {}
    internal var requestPermission: () -> Unit = {}
    internal var launchSystemRecognizer: () -> Unit = {}
    private var recognizer: SpeechRecognizer? = null

    fun start() {
        error = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermission()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            launchSystemRecognizer()
            return
        }
        destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                level = 0f
            }
            override fun onError(error: Int) {
                listening = false
                level = 0f
                val heard = partial
                partial = ""
                if (heard.isNotBlank()) {
                    onFinal(heard)
                } else {
                    this@SpeechInputState.error = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that — tap the mic and try again."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a connection on this phone (or download the offline language pack)."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed for voice input."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy (is a capture running?)."
                        else -> "Voice input error ($error)."
                    }
                }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                level = 0f
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().ifBlank { partial }
                partial = ""
                if (text.isNotBlank()) onFinal(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer = r
        partial = ""
        listening = true
        r.startListening(recognizerIntent(languageTag, context.packageName))
    }

    fun stop() {
        recognizer?.stopListening()
    }

    fun destroy() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        listening = false
    }

    companion object {
        fun recognizerIntent(tag: String, pkg: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, pkg)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a task or ask a question")
        }
    }
}

@Composable
fun rememberSpeechInput(languageTag: String, onFinal: (String) -> Unit): SpeechInputState {
    val context = LocalContext.current
    val state = remember { SpeechInputState(context) }
    val latest by rememberUpdatedState(onFinal)
    state.languageTag = languageTag
    state.onFinal = { latest(it) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) state.start() else state.error = "Microphone permission is needed for voice input."
    }
    val system = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) state.onFinal(text)
    }
    state.requestPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) }
    state.launchSystemRecognizer = {
        try {
            system.launch(SpeechInputState.recognizerIntent(state.languageTag, context.packageName))
        } catch (_: ActivityNotFoundException) {
            state.error = "No speech recognition service is installed on this phone."
        }
    }
    DisposableEffect(Unit) { onDispose { state.destroy() } }
    return state
}

@Singleton
class TtsManager @Inject constructor(@ApplicationContext private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, String>? = null

    fun speak(text: String, languageTag: String) {
        if (tts == null) tts = TextToSpeech(context, this)
        if (ready) say(text, languageTag) else pending = text to languageTag
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        pending?.let { (t, l) -> if (ready) say(t, l) }
        pending = null
    }

    private fun say(text: String, languageTag: String) {
        val engine = tts ?: return
        runCatching { engine.language = Locale.forLanguageTag(languageTag) }
        val clean = text.replace(Regex("""[•*#_`]"""), "").replace("—", ", ").take(TextToSpeech.getMaxSpeechInputLength() - 1)
        engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "taskmind-reply")
    }

    fun stop() {
        tts?.stop()
    }
}
