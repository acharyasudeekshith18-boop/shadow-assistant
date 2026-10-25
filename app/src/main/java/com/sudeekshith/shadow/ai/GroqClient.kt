package com.sudeekshith.shadow.ai

import com.sudeekshith.shadow.core.Command
import org.json.JSONArray
import org.json.JSONObject

/** Groq (OpenAI-compatible) free tier, used as a fallback when Gemini fails. */
class GroqClient(
    private val apiKey: String,
    private val model: String = "llama-3.3-70b-versatile",
    private val url: String = "https://api.groq.com/openai/v1/chat/completions",
) : LlmClient {
    override val name = "Groq"

    override fun reply(system: String, history: List<ChatTurn>, userText: String): LlmReply {
        var last: LlmException? = null
        for (m in listOf(model, "openai/gpt-oss-120b", "llama-3.1-8b-instant").distinct()) {
            try {
                val raw = Http.postJson(url, mapOf("Authorization" to "Bearer $apiKey"), buildRequest(m, system, history, userText).toString())
                return parseResponse(raw)
            } catch (e: LlmException) {
                last = e
                if (e.httpCode == 401 || e.httpCode == 403) throw e   // bad key: no point trying other models
            }
        }
        throw last ?: LlmException("Groq failed")
    }

    companion object {
        fun buildRequest(model: String, system: String, history: List<ChatTurn>, userText: String): JSONObject {
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            history.forEach { msgs.put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text)) }
            msgs.put(JSONObject().put("role", "user").put("content", userText))
            return JSONObject().put("model", model).put("messages", msgs)
                .put("tools", Tools.openAiTools()).put("tool_choice", "auto")
                .put("temperature", 0.7).put("max_tokens", 1024)
        }

        fun parseResponse(raw: String): LlmReply {
            val msg = JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val text = if (msg.isNull("content")) "" else msg.optString("content")
            val cmds = mutableListOf<Command>()
            msg.optJSONArray("tool_calls")?.let { calls ->
                for (i in 0 until calls.length()) {
                    val fn = calls.getJSONObject(i).getJSONObject("function")
                    val args = try { JSONObject(fn.optString("arguments", "{}")) } catch (e: Exception) { JSONObject() }
                    Tools.toCommand(fn.optString("name"), args)?.let { cmds += it }
                }
            }
            return LlmReply(text.trim(), cmds)
        }
    }
}
