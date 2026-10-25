package com.sudeekshith.shadow.system

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Thin wrapper over Android's SpeechRecognizer (uses the phone's speech service, e.g. Google). */
class Listener(context: Context) {
    private val ctx = context.applicationContext
    private var rec: SpeechRecognizer? = null

    var onPartial: (String) -> Unit = {}
    var onResult: (String) -> Unit = {}
    var onError: (Int) -> Unit = {}
    var onLevel: (Float) -> Unit = {}

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start(languageTag: String) {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { onLevel(0f) }
            override fun onError(error: Int) { onLevel(0f); this@Listener.onError(error) }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) this@Listener.onError(SpeechRecognizer.ERROR_NO_MATCH) else onResult(text)
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) onPartial(it) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        r.startListening(i)
    }

    fun stop() {
        try { rec?.cancel(); rec?.destroy() } catch (_: Exception) {}
        rec = null
    }

    companion object {
        fun isSilence(code: Int) = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

        fun describe(code: Int) = when (code) {
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs internet on this phone (or an offline speech pack)."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "I need microphone permission to hear you."
            SpeechRecognizer.ERROR_AUDIO -> "Microphone error. Try again."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service is busy, try again."
            else -> "I didn't catch that."
        }
    }
}
