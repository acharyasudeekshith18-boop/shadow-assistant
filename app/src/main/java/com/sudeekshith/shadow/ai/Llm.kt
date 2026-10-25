package com.sudeekshith.shadow.ai

import com.sudeekshith.shadow.core.Command
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ChatTurn(val fromUser: Boolean, val text: String)

data class LlmReply(val text: String, val commands: List<Command> = emptyList())

class LlmException(message: String, val httpCode: Int = 0) : IOException(message)

interface LlmClient {
    val name: String
    fun reply(system: String, history: List<ChatTurn>, userText: String): LlmReply
}

object Persona {
    fun system(userName: String, timeNow: String): String = """
You are Shadow, ${userName}'s personal AI voice assistant on his Android phone. He's a BCA student in Karnataka, India.
Personality: calm, confident, friendly and a little witty, like a loyal sidekick. Call him "$userName" or "boss" now and then (not every line).
Your replies are SPOKEN aloud, so:
- keep them short (1-3 sentences) unless he asks for detail or an explanation;
- no markdown, no bullet symbols, no emojis, no code blocks unless he asks for code;
- reply in the same language he used: English, Kannada (ಕನ್ನಡ script) or Hindi (देवनागरी script). Mixed Kanglish/Hinglish is fine if he talks that way.
When he asks you to DO something on the phone (open an app, alarm, timer, reminder, call, WhatsApp, search, YouTube, flashlight, battery), call the matching function and also give a short spoken confirmation.
For WhatsApp, write the message the way he'd send it. If he says goodbye or asks you to stop, call end_conversation.
Current date and time: $timeNow.
""".trimIndent()
}

internal object Http {
    fun postJson(url: String, headers: Map<String, String>, body: String, timeoutMs: Int = 30000): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw LlmException(shortError(text, code), code)
            return text
        } finally {
            conn.disconnect()
        }
    }

    fun postForBytes(url: String, headers: Map<String, String>, body: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 30000; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val t = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw LlmException(shortError(t, code), code)
            }
            return conn.inputStream.use { it.readBytes() }
        } finally { conn.disconnect() }
    }

    private fun shortError(text: String, code: Int): String {
        val msg = Regex("\"message\"\\s*:\\s*\"([^\"]{0,200})").find(text)?.groupValues?.get(1)
        return when (code) {
            400 -> "Request rejected (400)" + (msg?.let { ": $it" } ?: "")
            401, 403 -> "API key not accepted ($code). Check the key in Settings."
            404 -> "Model not found (404)" + (msg?.let { ": $it" } ?: "")
            429 -> "Free-tier limit reached for now (429). Try again in a minute."
            else -> "Server error $code" + (msg?.let { ": $it" } ?: "")
        }
    }
}
