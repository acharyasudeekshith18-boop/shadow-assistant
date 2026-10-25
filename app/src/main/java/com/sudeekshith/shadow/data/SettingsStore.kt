package com.sudeekshith.shadow.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sudeekshith.shadow.ai.ChatTurn
import com.sudeekshith.shadow.ai.GeminiClient
import com.sudeekshith.shadow.ai.ModelHealth
import org.json.JSONArray
import org.json.JSONObject

/** All settings live only on the phone. API keys are encrypted with the Android Keystore (EncryptedSharedPreferences). */
class SettingsStore(context: Context) {
    private val app = context.applicationContext

    private val secure: SharedPreferences = try {
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(app, "shadow_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    } catch (e: Exception) {
        // Very rare keystore failure on some devices: fall back to private app storage so the app still works.
        app.getSharedPreferences("shadow_secure_fallback", Context.MODE_PRIVATE)
    }
    private val prefs = app.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE)

    var geminiKey: String
        get() = secure.getString("gemini_key", "") ?: ""
        set(v) = secure.edit().putString("gemini_key", v.trim()).apply()
    var groqKey: String
        get() = secure.getString("groq_key", "") ?: ""
        set(v) = secure.edit().putString("groq_key", v.trim()).apply()
    var elevenKey: String
        get() = secure.getString("eleven_key", "") ?: ""
        set(v) = secure.edit().putString("eleven_key", v.trim()).apply()

    var userName: String
        get() = prefs.getString("user_name", "Sudeekshith") ?: "Sudeekshith"
        set(v) = prefs.edit().putString("user_name", v.trim().ifBlank { "Sudeekshith" }).apply()
    /** "auto" = best free model first, switching automatically when one runs out; or one model id he picked. */
    var geminiModel: String
        get() = (prefs.getString("gemini_model_v2", GeminiClient.DEFAULT_MODEL) ?: GeminiClient.DEFAULT_MODEL).ifBlank { GeminiClient.DEFAULT_MODEL }
        set(v) = prefs.edit().putString("gemini_model_v2", v.trim().ifBlank { GeminiClient.DEFAULT_MODEL }).apply()
    /** Speech recognition language: en-IN, kn-IN or hi-IN. */
    var listenLanguage: String
        get() = prefs.getString("listen_lang", "en-IN") ?: "en-IN"
        set(v) = prefs.edit().putString("listen_lang", v).apply()
    var countryCode: String
        get() = prefs.getString("country_code", "91") ?: "91"
        set(v) = prefs.edit().putString("country_code", v.filter { it.isDigit() }.ifBlank { "91" }).apply()
    /** How long Shadow waits when he pauses mid-sentence: 0 quick, 1 normal, 2 patient. */
    var patience: Int
        get() = prefs.getInt("patience", 1)
        set(v) = prefs.edit().putInt("patience", v.coerceIn(0, 2)).apply()
    var continuous: Boolean
        get() = prefs.getBoolean("continuous", true)
        set(v) = prefs.edit().putBoolean("continuous", v).apply()
    var speakReplies: Boolean
        get() = prefs.getBoolean("speak", true)
        set(v) = prefs.edit().putBoolean("speak", v).apply()
    var greetOnOpen: Boolean
        get() = prefs.getBoolean("greet", true)
        set(v) = prefs.edit().putBoolean("greet", v).apply()

    // ---- Shadow's voice ----
    /** "device", "gemini" or "elevenlabs". */
    var voiceEngine: String
        get() = prefs.getString("voice_engine", "device") ?: "device"
        set(v) = prefs.edit().putString("voice_engine", v).apply()
    /** Name of the chosen on-device TTS voice ("" = auto-pick the deepest English-India voice). */
    var deviceVoice: String
        get() = prefs.getString("device_voice", "") ?: ""
        set(v) = prefs.edit().putString("device_voice", v).apply()
    var pitch: Float
        get() = prefs.getFloat("pitch", 0.85f)
        set(v) = prefs.edit().putFloat("pitch", v).apply()
    var speed: Float
        get() = prefs.getFloat("speed", 1.0f)
        set(v) = prefs.edit().putFloat("speed", v).apply()
    var geminiVoice: String
        get() = prefs.getString("gemini_voice", "Charon") ?: "Charon"
        set(v) = prefs.edit().putString("gemini_voice", v).apply()
    var elevenVoiceId: String
        get() = prefs.getString("eleven_voice", "pNInz6obpgDQGcFmaJgB") ?: "pNInz6obpgDQGcFmaJgB"   // "Adam" (deep male)
        set(v) = prefs.edit().putString("eleven_voice", v.trim()).apply()

    // ---- chat history (last 60 messages, stored locally) ----
    fun loadHistory(): List<ChatTurn> = try {
        val arr = JSONArray(prefs.getString("history", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> ChatTurn(o.getBoolean("u"), o.getString("t"), o.optBoolean("m", false)) } }
    } catch (e: Exception) { emptyList() }

    fun saveHistory(list: List<ChatTurn>) {
        val arr = JSONArray()
        list.takeLast(60).forEach { arr.put(JSONObject().put("u", it.fromUser).put("t", it.text).put("m", it.meta)) }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    /** Which AI models are out of quota / unavailable, and until when (survives app restarts). */
    val modelHealth: ModelHealth = object : ModelHealth {
        override fun coolingUntil(id: String) = prefs.getLong("cool_$id", 0L)
        override fun cool(id: String, untilMs: Long) = prefs.edit().putLong("cool_$id", untilMs).apply()
    }

    fun resetModelHealth() {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("cool_") }.forEach { e.remove(it) }
        e.apply()
    }
}
