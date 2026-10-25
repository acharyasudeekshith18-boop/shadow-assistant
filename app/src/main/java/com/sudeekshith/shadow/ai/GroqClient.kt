package com.sudeekshith.shadow.ai

import com.sudeekshith.shadow.core.Command
import org.json.JSONArray
import org.json.JSONObject

/** Groq (OpenAI-compatible) free tier, one model per instance. Used automatically when Gemini fails and a Groq key exists. */
class GroqClient(
    private val apiKey: String,
    val model: String = MODELS.first(),
    private val url: String = "https://api.groq.com/openai/v1/chat/completions",
    private val transport: Transport = Http,
) : LlmClient {
    override val name = "Groq " + model.substringAfter('/')

    override fun reply(system: String, history: List<ChatTurn>, userText: String): LlmReply {
        val headers = mapOf("Authorization" to "Bearer $apiKey")
        return try {
            parseResponse(transport.post(url, headers, buildRequest(model, system, history, userText).toString(), 40000))
        } catch (e: LlmException) {
            // Groq answers 400 "tool_use_failed" when a model fumbles a tool call: ask again without tools.
            if (e.kind == Failure.BAD_REQUEST) parseResponse(transport.post(url, headers, buildRequest(model, system, history, userText, tools = false).toString(), 40000))
            else throw e
        }
    }

    companion object {
        /** Production models on console.groq.com/docs/models (checked 25 Oct 2026). */
        val MODELS = listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "llama-3.1-8b-instant")

        fun buildRequest(model: String, system: String, history: List<ChatTurn>, userText: String, tools: Boolean = true): JSONObject {
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            history.forEach { msgs.put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text)) }
            msgs.put(JSONObject().put("role", "user").put("content", userText))
            val req = JSONObject().put("model", model).put("messages", msgs)
                .put("temperature", 0.7).put("max_tokens", 1024)
            if (tools) req.put("tools", Tools.openAiTools()).put("tool_choice", "auto")
            return req
        }

        fun parseResponse(raw: String): LlmReply {
            val choices = JSONObject(raw).optJSONArray("choices")
            if (choices == null || choices.length() == 0) throw LlmException("Empty answer from Groq", kind = Failure.EMPTY)
            val choice = choices.getJSONObject(0)
            val msg = choice.getJSONObject("message")
            val text = if (msg.isNull("content")) "" else msg.optString("content")
            val cmds = mutableListOf<Command>()
            msg.optJSONArray("tool_calls")?.let { calls ->
                for (i in 0 until calls.length()) {
                    val fn = calls.getJSONObject(i).getJSONObject("function")
                    val args = try { JSONObject(fn.optString("arguments", "{}")) } catch (e: Exception) { JSONObject() }
                    Tools.toCommand(fn.optString("name"), args)?.let { cmds += it }
                }
            }
            val reason = choice.optString("finish_reason")
            if (text.isBlank() && cmds.isEmpty()) throw LlmException("Empty answer from Groq ($reason)", kind = Failure.EMPTY)
            return LlmReply(text.trim(), cmds, reason)
        }
    }
}
