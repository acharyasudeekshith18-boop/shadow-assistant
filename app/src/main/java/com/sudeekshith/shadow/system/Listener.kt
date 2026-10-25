package com.sudeekshith.shadow.system

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.sudeekshith.shadow.core.ListenPolicy
import com.sudeekshith.shadow.core.SpeechAccumulator

/**
 * Listens for one full spoken request, even with pauses in it.
 *
 * - Long silence settings + partial results shown live.
 * - When a segment ends, the recognizer is restarted for a short window: if he keeps talking, the words are appended.
 * - "No match" / "timeout" after partial text keeps the partial text.
 * - Hold mode (press-and-hold the orb): keeps listening until [release].
 * - Busy / client errors: the recognizer is destroyed and recreated.
 */
class Listener(context: Context, private val log: (String) -> Unit = {}) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var rec: SpeechRecognizer? = null
    private val acc = SpeechAccumulator()

    /** Text heard so far (committed + live partial). */
    var onLive: (String) -> Unit = {}
    /** The whole request, once he has finished. */
    var onFinished: (String) -> Unit = {}
    /** The listen ended with nothing heard. */
    var onNothingHeard: () -> Unit = {}
    /** A real error (permission, audio, network...), with nothing heard. */
    var onError: (Int) -> Unit = {}
    var onLevel: (Float) -> Unit = {}

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    private var active = false
    private var holding = false
    private var releasing = false
    private var inContinuation = false
    private var segmentHadSpeech = false
    private var generation = 0
    private var restarts = 0
    private var language = "en-IN"
    private var useLanguage = true
    private var patience = 1
    private var focus: AudioFocusRequest? = null

    private val continuationTimeout = Runnable { if (active && inContinuation && !segmentHadSpeech) { log("listen: no more speech, sending"); deliver() } }
    private val releaseTimeout = Runnable { if (active && releasing) deliver() }

    /** Start listening for a new request. [hold] = push-to-talk (listen until [release]). */
    fun begin(languageTag: String, patienceLevel: Int, hold: Boolean) {
        cancelInternal()
        acc.reset()
        active = true; holding = hold; releasing = false; inContinuation = false; restarts = 0
        language = languageTag.ifBlank { "en-IN" }; useLanguage = true; patience = patienceLevel
        requestFocus()
        startSegment()
    }

    /** Hold released, or tap-to-stop: send what was heard so far. */
    fun release() {
        if (!active) return
        holding = false
        releasing = true
        main.removeCallbacks(continuationTimeout)
        try { rec?.stopListening() } catch (_: Exception) {}
        // The recognizer normally answers with its final result; if it doesn't, use what we have.
        main.postDelayed(releaseTimeout, 1500)
    }

    /** Stop and discard. */
    fun cancel() {
        cancelInternal()
        acc.reset()
    }

    val isActive: Boolean get() = active

    private fun cancelInternal() {
        active = false; holding = false; releasing = false; inContinuation = false
        generation++
        main.removeCallbacks(continuationTimeout); main.removeCallbacks(releaseTimeout)
        try { rec?.cancel() } catch (_: Exception) {}
        abandonFocus()
        onLevel(0f)
    }

    private fun destroyRecognizer() {
        try { rec?.cancel() } catch (_: Exception) {}
        try { rec?.destroy() } catch (_: Exception) {}
        rec = null
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        if (useLanguage) {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
        }
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        val silence = if (holding) 10_000L else ListenPolicy.silenceMs(patience)
        val possibly = if (holding) 8_000L else ListenPolicy.possiblyCompleteMs(patience)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, possibly)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
    }

    private fun startSegment() {
        if (!active) return
        segmentHadSpeech = false
        val gen = ++generation
        val r = rec ?: try {
            SpeechRecognizer.createSpeechRecognizer(ctx).also { rec = it }
        } catch (e: Exception) { log("listen: can't create recognizer: ${e.message}"); fail(SpeechRecognizer.ERROR_CLIENT); return }
        r.setRecognitionListener(object : RecognitionListener {
            fun current() = active && gen == generation
            override fun onReadyForSpeech(params: Bundle?) {
                if (!current()) return
                if (inContinuation) {
                    main.removeCallbacks(continuationTimeout)
                    main.postDelayed(continuationTimeout, ListenPolicy.continuationWindowMs(patience))
                }
            }
            override fun onBeginningOfSpeech() {
                if (!current()) return
                segmentHadSpeech = true
                main.removeCallbacks(continuationTimeout)
            }
            override fun onRmsChanged(rmsdB: Float) { if (current()) onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { if (current()) onLevel(0f) }
            override fun onPartialResults(partial: Bundle?) {
                if (!current()) return
                val t = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (t.isNotBlank()) {
                    segmentHadSpeech = true
                    main.removeCallbacks(continuationTimeout)
                    acc.onPartial(t)
                    onLive(acc.live())
                }
            }
            override fun onResults(results: Bundle?) {
                if (!current()) return
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val before = acc.text()
                acc.onFinal(t)
                val gotNew = acc.text() != before
                onLive(acc.live())
                segmentEnded(gotNew || segmentHadSpeech && t.isNotBlank())
            }
            override fun onError(error: Int) {
                if (!current()) return
                onLevel(0f)
                handleError(error)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try {
            r.startListening(intent())
        } catch (e: Exception) {
            log("listen: startListening failed: ${e.message}")
            handleError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private fun segmentEnded(gotNewSpeech: Boolean) {
        main.removeCallbacks(continuationTimeout)
        when (ListenPolicy.afterSegment(holding, releasing, acc.hasCommitted(), inContinuation, gotNewSpeech)) {
            ListenPolicy.Next.RESTART -> { inContinuation = !holding; restartSoon(80) }
            ListenPolicy.Next.FINISH -> deliver()
            ListenPolicy.Next.NOTHING_HEARD -> deliver()
        }
    }

    private fun handleError(code: Int) {
        when {
            isSilence(code) -> {
                val keptPartial = acc.commitPartial()
                if (keptPartial) log("listen: recognizer ended (${name(code)}) - kept the partial text")
                segmentEnded(keptPartial)
            }
            code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || code == SpeechRecognizer.ERROR_CLIENT ||
                code == 11 /* ERROR_SERVER_DISCONNECTED */ -> {
                acc.commitPartial()
                if (releasing) { deliver(); return }
                if (restarts < 3) {
                    restarts++
                    log("listen: ${name(code)} - recreating the recognizer (try $restarts)")
                    destroyRecognizer()
                    restartSoon(350L * restarts)
                } else if (acc.hasCommitted()) deliver() else fail(code)
            }
            code == 12 || code == 13 /* language not supported / unavailable */ -> {
                acc.commitPartial()
                if (useLanguage) {
                    log("listen: language $language not available on this phone - using the phone's default")
                    useLanguage = false
                    restartSoon(150)
                } else if (acc.hasCommitted()) deliver() else fail(code)
            }
            else -> {
                acc.commitPartial()
                log("listen: ${name(code)}")
                if (acc.hasCommitted()) deliver() else fail(code)
            }
        }
    }

    private fun restartSoon(delayMs: Long) {
        val gen = generation
        main.postDelayed({ if (active && gen == generation) startSegment() }, delayMs)
    }

    private fun deliver() {
        if (!active) return
        acc.commitPartial()
        val text = acc.text()
        cancelInternal()
        acc.reset()
        if (text.isBlank()) onNothingHeard() else onFinished(text)
    }

    private fun fail(code: Int) {
        cancelInternal()
        acc.reset()
        onError(code)
    }

    private fun requestFocus() {
        // Pause music/videos while listening so the recognizer hears him clearly.
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .build()
                focus = req
                audio.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            }
        } catch (_: Exception) {}
    }

    private fun abandonFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) focus?.let { audio.abandonAudioFocusRequest(it) }
            else @Suppress("DEPRECATION") audio.abandonAudioFocus(null)
        } catch (_: Exception) {}
        focus = null
    }

    fun shutdown() {
        cancel()
        destroyRecognizer()
    }

    companion object {
        fun isSilence(code: Int) = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

        fun name(code: Int) = when (code) {
            1 -> "network timeout"; 2 -> "network error"; 3 -> "audio error"; 4 -> "speech server error"
            5 -> "client error"; 6 -> "speech timeout"; 7 -> "no match"; 8 -> "recognizer busy"
            9 -> "no mic permission"; 10 -> "too many requests"; 11 -> "server disconnected"
            12 -> "language not supported"; 13 -> "language unavailable"; else -> "error $code"
        }

        fun describe(code: Int) = when (code) {
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, 4, 11 ->
                "Speech recognition needs internet on this phone (or an offline speech pack). Check your connection."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "I need microphone permission to hear you."
            SpeechRecognizer.ERROR_AUDIO -> "The microphone is busy or failed. Close other apps using the mic and try again."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> "The phone's speech service got stuck. Tap the orb to try again."
            10 -> "The speech service says too many requests. Wait a few seconds and try again."
            12, 13 -> "This phone can't recognise that language. Change 'I speak' in Settings."
            else -> "I didn't catch that."
        }
    }
}
