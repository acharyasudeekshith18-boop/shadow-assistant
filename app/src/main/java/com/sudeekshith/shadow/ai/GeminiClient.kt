package com.sudeekshith.shadow.ai

import com.sudeekshith.shadow.core.Command
import org.json.JSONArray
import org.json.JSONObject

/** Google Gemini via the free-tier REST API (key from aistudio.google.com). */
class GeminiClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) : LlmClient {
    override val name = "Gemini"

    override fun reply(system: String, history: List<ChatTurn>, userText: String): LlmReply {
        // Free-tier limits are per model (checked Oct 2026: ~500 requests/day on flash-lite, ~20/day on flash),
        // so when one model is over its limit we try the next.
        val models = (listOf(model) + FALLBACK_MODELS).distinct()
        var last: LlmException? = null
        for (m in models) {
            for (fast in listOf(true, false)) {
                try {
                    val body = buildRequest(system, history, userText, fast).toString()
                    val raw = Http.postJson("$baseUrl/models/$m:generateContent", mapOf("x-goog-api-key" to apiKey), body)
                    return parseResponse(raw)
                } catch (e: LlmException) {
                    last = e
                    // 400 may mean this model doesn't accept the "no thinking" setting: retry without it.
                    if (e.httpCode == 400 && fast) continue
                    if (e.httpCode == 404 || e.httpCode == 429) break   // unknown model or over its free limit: next model
                    throw e
                }
            }
        }
        throw last ?: LlmException("Gemini failed")
    }

    companion object {
        /** Google's alias for its newest "lite" model: fast, and the biggest free daily quota. */
        const val DEFAULT_MODEL = "gemini-flash-lite-latest"
        val FALLBACK_MODELS = listOf("gemini-flash-lite-latest", "gemini-3.1-flash-lite", "gemini-flash-latest")

        fun buildRequest(system: String, history: List<ChatTurn>, userText: String, fast: Boolean = true): JSONObject {
            val contents = JSONArray()
            history.forEach { t ->
                contents.put(JSONObject().put("role", if (t.fromUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", t.text))))
            }
            contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText))))
            val gen = JSONObject().put("temperature", 0.7).put("maxOutputTokens", 1024)
            if (fast) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))   // quicker spoken replies
            return JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", contents)
                .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", Tools.geminiDeclarations())))
                .put("generationConfig", gen)
        }

        fun parseResponse(raw: String): LlmReply {
            val root = JSONObject(raw)
            val cands = root.optJSONArray("candidates")
            if (cands == null || cands.length() == 0) {
                val block = root.optJSONObject("promptFeedback")?.optString("blockReason")
                return LlmReply(if (!block.isNullOrBlank()) "Sorry boss, I can't answer that one." else "I didn't get an answer. Try again?")
            }
            val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val text = StringBuilder()
            val cmds = mutableListOf<Command>()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.optBoolean("thought", false)) continue
                p.optString("text").takeIf { it.isNotBlank() }?.let { text.append(it) }
                p.optJSONObject("functionCall")?.let { fc ->
                    Tools.toCommand(fc.optString("name"), fc.optJSONObject("args") ?: JSONObject())?.let { cmds += it }
                }
            }
            return LlmReply(text.toString().trim(), cmds)
        }
    }
}
