package com.sudeekshith.shadow.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The last 20 problems (speech + AI), kept on the phone so he can screenshot them from Settings > Debug log. No keys are ever written here. */
class DebugLog(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("shadow_debug", Context.MODE_PRIVATE)

    data class Entry(val time: String, val text: String)

    @Synchronized
    fun add(text: String) {
        val clean = scrub(text)
        val arr = try { JSONArray(prefs.getString("log", "[]")) } catch (e: Exception) { JSONArray() }
        arr.put(JSONObject().put("t", SimpleDateFormat("d MMM HH:mm:ss", Locale.US).format(Date())).put("m", clean.take(400)))
        val keep = JSONArray()
        val from = (arr.length() - MAX).coerceAtLeast(0)
        for (i in from until arr.length()) keep.put(arr.get(i))
        prefs.edit().putString("log", keep.toString()).apply()
    }

    fun entries(): List<Entry> = try {
        val arr = JSONArray(prefs.getString("log", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Entry(o.optString("t"), o.optString("m")) } }.reversed()
    } catch (e: Exception) { emptyList() }

    fun clear() = prefs.edit().putString("log", "[]").apply()

    companion object {
        const val MAX = 20
        /** Removes anything that looks like an API key. */
        fun scrub(s: String) = s.replace(Regex("AIza[0-9A-Za-z_\\-]{20,}"), "AIza…")
            .replace(Regex("gsk_[0-9A-Za-z]{10,}"), "gsk_…")
            .replace(Regex("sk_[0-9A-Za-z]{10,}"), "sk_…")
    }
}
