package com.sudeekshith.shadow.system

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Base64
import com.sudeekshith.shadow.data.SettingsStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Shadow's voice. Default: the phone's own TextToSpeech (free, works offline) with a deep male voice
 * picked automatically and pitch ~0.85. Optional: Gemini TTS (same free key) or ElevenLabs (free key).
 * Any online voice that fails falls back to the phone voice.
 */
class ShadowVoice(context: Context, private val settings: SettingsStore) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingDone: (() -> Unit)? = null
    private var player: MediaPlayer? = null
    private var track: AudioTrack? = null
    @Volatile private var generation = 0
    private var waitingText: String? = null
    private var chunkCount = 0
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest? = null

    var onReady: (() -> Unit)? = null

    init {
        tts = TextToSpeech(ctx) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                applyVoice()
                try {
                    tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                } catch (_: Exception) {}
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    // v1.1 fix: long answers are spoken in several chunks. Only the LAST chunk of the
                    // CURRENT answer means "done" — v1.0 treated the first sentence's end as done, so the
                    // mic re-opened mid-answer, cut Shadow off and heard its own voice.
                    override fun onDone(id: String?) { main.post { utteranceEnded(id, error = false) } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { main.post { utteranceEnded(id, error = true) } }
                    override fun onError(id: String?, errorCode: Int) { main.post { utteranceEnded(id, error = true) } }
                    override fun onStop(id: String?, interrupted: Boolean) {}
                })
                main.post {
                    onReady?.invoke()
                    waitingText?.let { t -> waitingText = null; if (pendingDone != null) deviceSpeak(t) }
                }
            }
        }
    }

    /** English voices on the phone, best (deep / male / India) first. */
    fun availableVoices(): List<Voice> {
        val all = try { tts?.voices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        return all.filter { it.locale.language in setOf("en", "kn", "hi") }
            .sortedWith(compareBy({ -score(it) }, { it.name }))
    }

    private fun score(v: Voice): Int {
        var s = 0
        val n = v.name.lowercase(Locale.ROOT)
        if (MALE_HINTS.any { n.contains(it) }) s += 50
        if (n.contains("male") && !n.contains("female")) s += 50
        if (v.locale.language == "en") s += 20
        if (v.locale.country == "IN") s += 15
        if (v.isNetworkConnectionRequired) s += 5
        if (v.features?.contains("notInstalled") == true) s -= 100
        s += v.quality / 100
        return s
    }

    fun applyVoice() {
        val t = tts ?: return
        t.setPitch(settings.pitch)
        t.setSpeechRate(settings.speed)
        val chosen = settings.deviceVoice
        val voices = availableVoices()
        val v = voices.firstOrNull { it.name == chosen } ?: voices.firstOrNull { it.locale.language == "en" }
        if (v != null) try { t.voice = v } catch (e: Exception) { t.language = Locale("en", "IN") }
        else t.language = Locale("en", "IN")
    }

    fun speak(text: String, onDone: () -> Unit) {
        stop()
        val gen = ++generation
        pendingDone = onDone
        val clean = text.replace(Regex("[*_#`>]+"), "").trim()
        if (clean.isEmpty()) { finish(); return }
        when (settings.voiceEngine) {
            "gemini" -> if (settings.geminiKey.isNotBlank()) { io.execute { onlineSpeak(gen, clean) { geminiPcm(clean) } }; return }
            "elevenlabs" -> if (settings.elevenKey.isNotBlank()) { io.execute { onlineSpeak(gen, clean) { elevenMp3(clean) } }; return }
        }
        deviceSpeak(clean)
    }

    private fun deviceSpeak(text: String) {
        val t = tts
        if (t == null) { finish(); return }
        if (!ready) {
            // The phone's voice engine is still starting (first launch): speak as soon as it's ready, give up after 4 s.
            waitingText = text
            val gen = generation
            main.postDelayed({ if (gen == generation && waitingText != null) { waitingText = null; finish() } }, 4000)
            return
        }
        requestFocus()
        applyVoice()
        // Kannada / Hindi script: switch to that language's voice if the phone has it.
        val script = when {
            text.any { it in '\u0C80'..'\u0CFF' } -> Locale("kn", "IN")
            text.any { it in '\u0900'..'\u097F' } -> Locale("hi", "IN")
            else -> null
        }
        if (script != null && t.isLanguageAvailable(script) >= TextToSpeech.LANG_AVAILABLE) t.language = script
        val params = Bundle()
        // TTS has a per-call length limit: split long answers into sentences.
        val chunks = text.split(Regex("(?<=[.!?।])\\s+")).flatMap { it.chunked(3500) }.filter { it.isNotBlank() }
        if (chunks.isEmpty()) { finish(); return }
        val gen = generation
        chunkCount = chunks.size
        chunks.forEachIndexed { i, c ->
            t.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, utteranceId(gen, i, chunks.size))
        }
        // Safety net: if the voice engine never reports "done", don't leave Shadow stuck in Speaking.
        val maxMs = (text.length * 110L / settings.speed.coerceAtLeast(0.5f)).toLong() + 6000L
        main.postDelayed({ if (gen == generation && pendingDone != null) finish() }, maxMs)
    }

    private sealed class Audio { class Pcm(val bytes: ByteArray) : Audio(); class Mp3(val file: File) : Audio() }

    private fun onlineSpeak(gen: Int, text: String, fetch: () -> Audio) {
        val audio = try { fetch() } catch (e: Exception) { null }
        main.post {
            if (gen != generation) return@post
            when (audio) {
                null -> deviceSpeak(text)   // fall back to the phone's own voice
                is Audio.Mp3 -> playMp3(audio.file)
                is Audio.Pcm -> playPcm(audio.bytes)
            }
        }
    }

    private fun geminiPcm(text: String): Audio {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", settings.geminiVoice)))))
        var raw: ByteArray? = null
        var err: Exception? = null
        for (m in listOf("gemini-3.8-flash-tts", "gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts")) {
            try {
                raw = post("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent",
                    mapOf("x-goog-api-key" to settings.geminiKey), body.toString())
                break
            } catch (e: Exception) { err = e }
        }
        if (raw == null) throw err ?: IllegalStateException("Gemini TTS failed")
        val data = JSONObject(String(raw)).getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("data")
        return Audio.Pcm(Base64.decode(data, Base64.DEFAULT))
    }

    private fun elevenMp3(text: String): Audio {
        val body = JSONObject().put("text", text).put("model_id", "eleven_flash_v2_5")
        val bytes = post("https://api.elevenlabs.io/v1/text-to-speech/${settings.elevenVoiceId}?output_format=mp3_44100_128",
            mapOf("xi-api-key" to settings.elevenKey, "Accept" to "audio/mpeg"), body.toString())
        val f = File(ctx.cacheDir, "shadow_voice.mp3"); f.writeBytes(bytes)
        return Audio.Mp3(f)
    }

    private fun post(url: String, headers: Map<String, String>, body: String): ByteArray {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10000; readTimeout = 30000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) throw IllegalStateException("TTS HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private fun playMp3(f: File) {
        try {
            requestFocus()
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener { finish() }
                setOnErrorListener { _, _, _ -> finish(); true }
                prepare(); start()
            }
        } catch (e: Exception) { finish() }
    }

    private fun playPcm(pcm: ByteArray) {
        try {
            val rate = 24000
            val at = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size).build()
            requestFocus()
            at.write(pcm, 0, pcm.size)
            track = at
            at.play()
            val ms = pcm.size / 2 * 1000L / rate
            val gen = generation
            main.postDelayed({ if (gen == generation) finish() }, ms + 250)
        } catch (e: Exception) { finish() }
    }

    private fun utteranceEnded(id: String?, error: Boolean) {
        val gen = generation
        if (!isFinalUtterance(id, gen)) {
            // A middle chunk failed: carry on with the rest; a middle chunk finished: nothing to do.
            return
        }
        finish()
    }

    private fun finish() {
        abandonFocus()
        val cb = pendingDone
        pendingDone = null
        cb?.invoke()
    }

    private fun requestFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
                focus = req
                audio.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
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

    fun isSpeaking(): Boolean = pendingDone != null

    /** Stops speaking without triggering the "done" callback. */
    fun stop() {
        generation++
        pendingDone = null
        waitingText = null
        abandonFocus()
        try { tts?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { track?.release() } catch (_: Exception) {}
        track = null
    }

    fun shutdown() {
        stop(); tts?.shutdown(); io.shutdownNow()
    }

    companion object {
        fun utteranceId(gen: Int, index: Int, total: Int) = if (index == total - 1) "g$gen-last" else "g$gen-part$index"
        /** True only for the last chunk of the answer that is speaking now. */
        fun isFinalUtterance(id: String?, gen: Int) = id == "g$gen-last"

        // Google TTS male voice ids (en-IN, en-GB, en-US, en-AU)
        val MALE_HINTS = listOf("en-in-x-ene", "en-in-x-end", "en-gb-x-rjs", "en-gb-x-gbd", "en-us-x-iom", "en-us-x-iol", "en-us-x-tpd", "en-au-x-aud", "en-au-x-auc")
    }
}
