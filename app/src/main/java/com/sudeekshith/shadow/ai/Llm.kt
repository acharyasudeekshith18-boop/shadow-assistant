package com.sudeekshith.shadow.ai

import com.sudeekshith.shadow.core.Command
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** [meta] = a status/error line from Shadow (not part of the real conversation, never sent to the AI). */
data class ChatTurn(val fromUser: Boolean, val text: String, val meta: Boolean = false)

data class LlmReply(val text: String, val commands: List<Command> = emptyList(), val finishReason: String = "")

enum class Failure { QUOTA_DAY, RATE_LIMIT, OVERLOADED, NETWORK, TIMEOUT, BAD_KEY, NOT_FOUND, BAD_REQUEST, EMPTY, BLOCKED, OTHER }

class LlmException(message: String, val httpCode: Int = 0, val kind: Failure = Failure.OTHER, val retryAfterSec: Long = 0) : IOException(message)

interface LlmClient {
    val name: String
    fun reply(system: String, history: List<ChatTurn>, userText: String): LlmReply
}

/** POST a JSON body and return the response text; throws [LlmException]. Swappable in tests. */
fun interface Transport {
    fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): String
}

object Persona {
    fun system(userName: String, timeNow: String): String = """
You are Shadow, ${userName}'s personal AI voice assistant on his Android phone. He's a BCA student in Karnataka, India.
Personality: calm, confident, friendly and a little witty, like a loyal sidekick. Call him "$userName" or "boss" now and then (not every line).
Most important: actually answer what he asked, completely and correctly. Never dodge, never give a vague or half answer.
- Simple questions or chit-chat: 1 to 3 natural sentences.
- Explanations, how-to, comparisons, study or coding questions: give the full answer, usually 4 to 10 sentences, as clear spoken paragraphs. For steps say "first", "next", "finally".
- If his words look cut off or unclear (speech recognition can miss words), answer the most likely meaning, and only ask a short question back if you truly can't tell.
- If you don't know something current (live news, prices, scores), say so honestly and offer to search it with the search function.
Your replies are SPOKEN aloud, so: no markdown, no bullet symbols, no tables, no emojis, no code blocks unless he asks for code; write numbers and symbols the way they're said.
Reply in the same language he used: English, Kannada (ಕನ್ನಡ script) or Hindi (देवನागरी script). Mixed Kanglish/Hinglish is fine if he talks that way.
When he asks you to DO something on the phone (open an app, alarm, timer, reminder, call, WhatsApp, search, YouTube, flashlight, battery), call the matching function and also give a short spoken confirmation.
For WhatsApp, write the message the way he'd send it. If he says goodbye or asks you to stop, call end_conversation.
Current date and time: $timeNow.
""".trimIndent()
}

/** Keeps the request small: recent real turns only, each capped, total capped. */
object HistoryTrim {
    const val MAX_TURNS = 12
    const val MAX_TURN_CHARS = 1200
    const val MAX_TOTAL_CHARS = 6000

    fun trim(history: List<ChatTurn>, maxTurns: Int = MAX_TURNS, maxTotal: Int = MAX_TOTAL_CHARS): List<ChatTurn> {
        val real = history.filter { !it.meta && it.text.isNotBlank() }.takeLast(maxTurns)
            .map { if (it.text.length > MAX_TURN_CHARS) it.copy(text = it.text.take(MAX_TURN_CHARS) + "…") else it }
        val out = ArrayDeque<ChatTurn>()
        var total = 0
        for (t in real.asReversed()) {
            if (total + t.text.length > maxTotal) break
            out.addFirst(t); total += t.text.length
        }
        // The conversation sent to the AI must start with something he said.
        while (out.isNotEmpty() && !out.first().fromUser) out.removeFirst()
        return out.toList()
    }
}

internal object Http : Transport {
    override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): String {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12000
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
                if (code !in 200..299) throw httpError(code, text)
                return text
            } finally {
                conn.disconnect()
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: UnknownHostException) {
            throw LlmException("No internet (can't reach ${host(url)})", kind = Failure.NETWORK)
        } catch (e: ConnectException) {
            throw LlmException("No internet (connection failed)", kind = Failure.NETWORK)
        } catch (e: SocketTimeoutException) {
            throw LlmException("The AI took too long to answer", kind = Failure.TIMEOUT)
        } catch (e: InterruptedIOException) {
            throw LlmException("The AI took too long to answer", kind = Failure.TIMEOUT)
        } catch (e: SSLException) {
            throw LlmException("Secure connection failed (${e.message?.take(80)})", kind = Failure.NETWORK)
        } catch (e: IOException) {
            throw LlmException("Network error: ${e.message?.take(120)}", kind = Failure.NETWORK)
        }
    }

    private fun host(url: String) = try { URL(url).host } catch (e: Exception) { "server" }

    /** Turns an HTTP error into a typed failure. Public for tests. */
    fun httpError(code: Int, text: String): LlmException {
        val msg = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){0,300})").find(text)?.groupValues?.get(1)
            ?.replace("\\n", " ")?.replace("\\\"", "\"")?.trim()
        val retry = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("retry in (\\d+)(?:\\.\\d+)?s", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val daily = text.contains("PerDay", true) || text.contains("per day", true) || text.contains("tokens per day", true) ||
            text.contains("insufficient_quota") || retry > 600
        return when (code) {
            400 -> if (text.contains("API key not valid", true) || text.contains("API_KEY_INVALID"))
                       LlmException("API key not valid. Check the key in Settings.", code, Failure.BAD_KEY)
                   else LlmException("Request rejected (400)" + (msg?.let { ": $it" } ?: ""), code, Failure.BAD_REQUEST)
            401, 403 -> LlmException("API key not accepted ($code)" + (msg?.let { ": ${it.take(120)}" } ?: "") , code, Failure.BAD_KEY)
            404 -> LlmException("Model not available (404)" + (msg?.let { ": ${it.take(160)}" } ?: ""), code, Failure.NOT_FOUND)
            429 -> if (daily) LlmException("Daily free limit reached (429)", code, Failure.QUOTA_DAY, retry)
                   else LlmException("Too many requests per minute (429)", code, Failure.RATE_LIMIT, retry)
            500, 502, 503, 504 -> LlmException("AI server busy ($code)" + (msg?.let { ": ${it.take(120)}" } ?: ""), code, Failure.OVERLOADED)
            else -> LlmException("Server error $code" + (msg?.let { ": ${it.take(120)}" } ?: ""), code, Failure.OTHER)
        }
    }
}
