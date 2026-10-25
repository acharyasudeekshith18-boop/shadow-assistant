package com.sudeekshith.shadow.core

import java.util.Locale

/** Fuzzy name matching for app labels and contact names ("insta" -> Instagram, "amma" -> "Amma Jio"). */
object AppMatcher {
    private val aliases = mapOf(
        "insta" to "instagram", "ig" to "instagram", "yt" to "youtube", "fb" to "facebook",
        "gmail" to "gmail", "mail" to "gmail", "maps" to "maps", "google maps" to "maps",
        "play store" to "play store", "playstore" to "play store", "whats app" to "whatsapp", "watsapp" to "whatsapp",
        "chrome" to "chrome", "browser" to "chrome", "calculator" to "calculator", "photos" to "photos", "gallery" to "gallery",
        "clock" to "clock", "calendar" to "calendar", "messages" to "messages", "sms" to "messages", "music" to "music",
        "spotify" to "spotify", "telegram" to "telegram", "snapchat" to "snapchat", "snap" to "snapchat", "x" to "x", "twitter" to "x",
    )

    fun best(query: String, names: List<String>): String? {
        val q0 = query.lowercase(Locale.ROOT).trim()
        if (q0.isEmpty() || names.isEmpty()) return null
        val q = aliases[q0] ?: q0
        val lower = names.map { it to it.lowercase(Locale.ROOT).trim() }
        lower.firstOrNull { it.second == q }?.let { return it.first }
        lower.firstOrNull { it.second.startsWith(q) }?.let { return it.first }
        lower.firstOrNull { it.second.split(" ").any { w -> w == q } }?.let { return it.first }
        lower.filter { it.second.contains(q) }.minByOrNull { it.second.length }?.let { return it.first }
        if (q.length >= 4) {
            val scored = lower.map { it.first to distance(q, it.second) }.minByOrNull { it.second }
            if (scored != null && scored.second <= maxOf(1, q.length / 4)) return scored.first
        }
        return null
    }

    fun distance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return dp[a.length][b.length]
    }
}
