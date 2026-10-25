package com.sudeekshith.shadow.ai

import com.sudeekshith.shadow.core.Command
import org.json.JSONArray
import org.json.JSONObject

/** One Google model (Gemini or Gemma) through the free-tier REST API (key from aistudio.google.com). */
class GeminiClient(
    private val apiKey: String,
    val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val transport: Transport = Http,
) : LlmClient {
    override val name = label(model)

    override fun reply(system: String, history: List<ChatTurn>, userText: String): LlmReply {
        val url = "$baseUrl/models/$model:generateContent"
        val headers = mapOf("x-goog-api-key" to apiKey)
        var fast = true
        var tools = true
        repeat(3) {
            try {
                val raw = transport.post(url, headers, buildRequest(system, history, userText, model, fast, tools).toString(), 45000)
                val r = parseResponse(raw)
                if (r.finishReason == "MAX_TOKENS" && r.text.isBlank() && r.commands.isEmpty() && fast) {
                    // Thinking used the whole budget: once more without the "fast" setting and a bigger budget.
                    fast = false; return@repeat
                }
                return r
            } catch (e: LlmException) {
                // 400 = this model doesn't take one of our settings: drop the thinking setting first, then tools.
                if (e.kind == Failure.BAD_REQUEST && fast) { fast = false; return@repeat }
                if (e.kind == Failure.BAD_REQUEST && tools) { tools = false; return@repeat }
                throw e
            }
        }
        throw LlmException("$name gave an empty answer", kind = Failure.EMPTY)
    }

    companion object {
        /** Tried in this order (checked against Google's models list, 25 Oct 2026). gemini-2.5-flash answers 404 for new keys; it's skipped after the first 404. */
        val CHAIN = listOf("gemini-2.5-flash", "gemini-flash-latest", "gemini-flash-lite-latest")
        /** Open Gemma model on the same free key: separate (much larger) free quota, used when the Gemini models are out. */
        const val BACKUP_MODEL = "gemma-4-31b-it"
        const val DEFAULT_MODEL = "auto"
        const val OLD_DEFAULT = "gemini-flash-lite-latest"

        fun label(model: String) = when (model) {
            "gemini-2.5-flash" -> "Gemini 2.5 Flash"
            "gemini-flash-latest" -> "Gemini Flash"
            "gemini-flash-lite-latest" -> "Gemini Flash-Lite"
            BACKUP_MODEL -> "Gemma (backup brain)"
            else -> model
        }

        fun buildRequest(system: String, history: List<ChatTurn>, userText: String, model: String = "gemini-flash-latest",
                         fast: Boolean = true, tools: Boolean = true): JSONObject {
            val contents = JSONArray()
            history.forEach { t ->
                contents.put(JSONObject().put("role", if (t.fromUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", t.text))))
            }
            contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText))))
            val gen = JSONObject().put("temperature", 0.7)
            if (fast) {
                gen.put("maxOutputTokens", 1024)
                // Quicker spoken replies. 2.5 models take a token budget, newer models (and Gemma) a level.
                gen.put("thinkingConfig", if (model.contains("2.5")) JSONObject().put("thinkingBudget", 0)
                                          else JSONObject().put("thinkingLevel", "minimal"))
            } else {
                gen.put("maxOutputTokens", 4096)   // leaves room for the model's own thinking + ~1000 tokens of answer
            }
            val req = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", contents)
                .put("generationConfig", gen)
            if (tools) req.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", Tools.geminiDeclarations())))
            return req
        }

        fun parseResponse(raw: String): LlmReply {
            val root = JSONObject(raw)
            val cands = root.optJSONArray("candidates")
            if (cands == null || cands.length() == 0) {
                val block = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                if (block.isNotBlank()) throw LlmException("Blocked by the AI's safety filter ($block)", kind = Failure.BLOCKED)
                throw LlmException("Empty answer (no candidates)", kind = Failure.EMPTY)
            }
            val cand = cands.getJSONObject(0)
            val reason = cand.optString("finishReason")
            val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val text = StringBuilder()
            val cmds = mutableListOf<Command>()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.optBoolean("thought", false)) continue
                p.optString("text").takeIf { it.isNotEmpty() }?.let { text.append(it) }
                p.optJSONObject("functionCall")?.let { fc ->
                    Tools.toCommand(fc.optString("name"), fc.optJSONObject("args") ?: JSONObject())?.let { cmds += it }
                }
            }
            val out = text.toString().trim()
            if (out.isEmpty() && cmds.isEmpty()) {
                when (reason) {
                    "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION" ->
                        throw LlmException("Blocked by the AI's safety filter ($reason)", kind = Failure.BLOCKED)
                    "MAX_TOKENS" -> return LlmReply("", emptyList(), reason)
                    else -> throw LlmException("Empty answer" + (if (reason.isNotBlank()) " ($reason)" else ""), kind = Failure.EMPTY)
                }
            }
            // Cut off by the length limit: end on the last full sentence so it doesn't stop mid-word.
            val finalText = if (reason == "MAX_TOKENS") trimToSentence(out) else out
            return LlmReply(finalText, cmds, reason)
        }

        fun trimToSentence(s: String): String {
            val idx = s.lastIndexOfAny(charArrayOf('.', '!', '?', '।'))
            return if (idx > s.length / 2) s.substring(0, idx + 1) else s
        }
    }
}
