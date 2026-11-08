package com.taskmind.ai.call

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File

/** Microphone recorder (AAC/M4A, 16 kHz mono — small files, fine for speech transcription). */
class AudioRecorderEngine(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    fun start(file: File) {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            file.delete()
            throw e
        }
        recorder = r
        output = file
    }

    fun maxAmplitude(): Int = try {
        recorder?.maxAmplitude ?: 0
    } catch (_: Exception) {
        0
    }

    /** Returns the recorded file, or null if nothing usable was written. */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        return try {
            r.stop()
            output?.takeIf { it.exists() && it.length() > 0 }
        } catch (_: RuntimeException) {
            output?.delete()
            null
        } finally {
            r.release()
        }
    }
}

/**
 * Continuous on-device speech recognition built on Android's SpeechRecognizer. The platform
 * recognizer stops after each utterance, so we restart it until [stop] is called.
 * Must be used from the main thread.
 */
class LiveTranscriber(
    private val context: Context,
    private val languageTag: String,
    private val onUpdate: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private val finalText = StringBuilder()
    private var partial = ""
    private var running = false
    private val handler = Handler(Looper.getMainLooper())
    var lastError: Int? = null
        private set
    var permissionDenied = false
        private set

    val text: String get() = (finalText.toString() + " " + partial).trim()

    fun start() {
        running = true
        createRecognizer()
        listen()
    }

    private fun createRecognizer() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply { setRecognitionListener(listener) }
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15_000L)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    private fun listen() {
        if (!running) return
        try {
            recognizer?.startListening(intent())
        } catch (_: Exception) {
            restart(800, recreate = true)
        }
    }

    private fun restart(delayMs: Long, recreate: Boolean = false) {
        if (!running) return
        handler.postDelayed({
            if (!running) return@postDelayed
            if (recreate) createRecognizer()
            listen()
        }, delayMs)
    }

    private fun commitPartial() {
        if (partial.isNotBlank()) {
            if (finalText.isNotEmpty()) finalText.append(' ')
            finalText.append(sentence(partial))
            partial = ""
        }
    }

    private fun sentence(s: String): String {
        val t = s.trim().replaceFirstChar { it.uppercaseChar() }
        return if (t.endsWith('.') || t.endsWith('?') || t.endsWith('!')) t else "$t."
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val best = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!best.isNullOrBlank()) {
                partial = best
                onUpdate(text)
            }
        }

        override fun onResults(results: Bundle?) {
            val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!best.isNullOrBlank()) partial = best
            commitPartial()
            onUpdate(text)
            restart(150)
        }

        override fun onError(error: Int) {
            lastError = error
            commitPartial()
            onUpdate(text)
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    permissionDenied = true
                    running = false
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> restart(700, recreate = true)
                else -> restart(250)
            }
        }
    }

    /** Stops listening and returns the full transcript. */
    fun stop(): String {
        running = false
        handler.removeCallbacksAndMessages(null)
        commitPartial()
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        return finalText.toString().trim()
    }
}
