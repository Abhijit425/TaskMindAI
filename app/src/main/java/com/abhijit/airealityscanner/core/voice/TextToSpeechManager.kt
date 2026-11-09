package com.abhijit.airealityscanner.core.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.abhijit.airealityscanner.core.util.TextFormat
import com.abhijit.airealityscanner.domain.model.AppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class VoiceAvailability { AVAILABLE, MISSING_DATA, NOT_SUPPORTED, ENGINE_UNAVAILABLE, UNKNOWN }

/** Wraps Android TextToSpeech with chunking, a speaking state and a stop control. */
@Singleton
class TextToSpeechManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : TextToSpeech.OnInitListener {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private val pending = ArrayDeque<() -> Unit>()

    private var lastUtteranceId: String? = null
    private var onDone: (() -> Unit)? = null
    private var counter = 0

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            main.post { _isSpeaking.value = true }
        }

        override fun onDone(utteranceId: String?) {
            main.post {
                if (utteranceId == lastUtteranceId) {
                    _isSpeaking.value = false
                    val callback = onDone
                    onDone = null
                    callback?.invoke()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            main.post { if (utteranceId == lastUtteranceId) _isSpeaking.value = false }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            main.post { _isSpeaking.value = false }
        }
    }

    private fun runWhenReady(action: () -> Unit) {
        main.post {
            when {
                ready -> action()
                failed -> _errors.tryEmit("Text-to-speech engine is not available on this device.")
                else -> {
                    pending.addLast(action)
                    if (tts == null) tts = TextToSpeech(context, this)
                }
            }
        }
    }

    override fun onInit(status: Int) {
        main.post {
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts?.setOnUtteranceProgressListener(listener)
                while (pending.isNotEmpty()) pending.removeFirst().invoke()
            } else {
                failed = true
                pending.clear()
                _errors.tryEmit("Text-to-speech engine failed to start.")
            }
        }
    }

    fun speak(text: String, language: AppLanguage, rate: Float = 1f, onFinished: (() -> Unit)? = null) {
        val clean = TextFormat.stripMarkdown(text).replace("•", "")
        if (clean.isBlank()) return
        runWhenReady {
            val engine = tts ?: return@runWhenReady
            val result = engine.setLanguage(language.locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                _errors.tryEmit(
                    "${language.englishName} voice is not installed. Open Settings → Voice → Install voice data, " +
                        "or install it in your phone's Text-to-speech settings.",
                )
                return@runWhenReady
            }
            engine.setSpeechRate(rate)
            engine.stop()
            onDone = onFinished
            val max = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
            val chunks = TextFormat.chunkForSpeech(clean, max)
            chunks.forEachIndexed { index, chunk ->
                val id = "utt-${++counter}"
                if (index == chunks.lastIndex) lastUtteranceId = id
                engine.speak(chunk, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
            }
        }
    }

    fun stop() {
        main.post {
            onDone = null
            tts?.stop()
            _isSpeaking.value = false
        }
    }

    /** Checks whether a voice for [language] is installed. Invokes [callback] on the main thread. */
    fun checkAvailability(language: AppLanguage, callback: (VoiceAvailability) -> Unit) {
        runWhenReady {
            val status = when (tts?.isLanguageAvailable(language.locale)) {
                null -> VoiceAvailability.ENGINE_UNAVAILABLE
                TextToSpeech.LANG_MISSING_DATA -> VoiceAvailability.MISSING_DATA
                TextToSpeech.LANG_NOT_SUPPORTED -> VoiceAvailability.NOT_SUPPORTED
                TextToSpeech.LANG_AVAILABLE, TextToSpeech.LANG_COUNTRY_AVAILABLE,
                TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> VoiceAvailability.AVAILABLE
                else -> VoiceAvailability.UNKNOWN
            }
            callback(status)
        }
        if (failed) main.post { callback(VoiceAvailability.ENGINE_UNAVAILABLE) }
    }
}
