package com.sudeekshith.shadow.ai

/** Remembers models that are out of quota or unavailable, so later questions skip them straight away. */
interface ModelHealth {
    fun coolingUntil(id: String): Long
    fun cool(id: String, untilMs: Long)
}

class MemoryModelHealth : ModelHealth {
    private val map = mutableMapOf<String, Long>()
    override fun coolingUntil(id: String) = map[id] ?: 0L
    override fun cool(id: String, untilMs: Long) { map[id] = untilMs }
}

/** One step of the fallback chain. [provider] groups models that share a key ("gemini", "groq"). */
data class Route(val id: String, val provider: String, val client: LlmClient)

data class RoutedReply(val reply: LlmReply, val usedBy: String, val notices: List<String>)

/** Every route failed. [spoken] is the plain explanation for him. */
class AllRoutesFailed(val spoken: String, val details: List<String>) : Exception(spoken)

/**
 * Tries each route in order:
 * - 429 per-minute / 503 / timeout: one retry after a short backoff, then the next model;
 * - 429 daily limit: the model is skipped until Google's daily reset, and he's told once ("switching to …");
 * - 404 (model closed to new keys): skipped for a week;
 * - bad key: skip the rest of that provider;
 * - no internet: stop at once with a clear message;
 * - safety block: a polite "can't answer that";
 * - empty answer: next model.
 */
class AiRouter(
    private val routes: List<Route>,
    private val health: ModelHealth = MemoryModelHealth(),
    private val log: (String) -> Unit = {},
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    fun ask(system: String, history: List<ChatTurn>, userText: String): RoutedReply {
        val notices = mutableListOf<String>()
        val details = mutableListOf<String>()
        val deadProviders = mutableSetOf<String>()
        var lastFailure: Failure? = null
        var quotaHits = 0
        var skipped = 0
        var lastQuotaLabel = ""

        for ((index, r) in routes.withIndex()) {
            if (r.provider in deadProviders) continue
            if (health.coolingUntil(r.id) > now()) { skipped++; continue }
            var attempt = 0
            while (true) {
                attempt++
                try {
                    val reply = r.client.reply(system, history, userText)
                    if (reply.text.isBlank() && reply.commands.isEmpty())
                        throw LlmException("Empty answer (${reply.finishReason.ifBlank { "no text" }})", kind = Failure.EMPTY)
                    if (lastQuotaLabel.isNotEmpty()) notices += "I've hit today's free limit on $lastQuotaLabel, so I switched to ${r.client.name}."
                    return RoutedReply(reply, r.client.name, notices)
                } catch (e: LlmException) {
                    lastFailure = e.kind
                    val line = "${r.client.name}: ${e.message}"
                    details += line
                    log(line)
                    when (e.kind) {
                        Failure.NETWORK -> throw AllRoutesFailed(
                            "I can't reach the internet right now, boss. Phone commands still work, but I need internet to answer questions.", details)
                        Failure.BLOCKED -> return RoutedReply(LlmReply("Sorry boss, I can't answer that one. Try asking it a different way."), r.client.name, notices)
                        Failure.RATE_LIMIT, Failure.OVERLOADED, Failure.TIMEOUT -> {
                            if (attempt == 1 && !(e.kind == Failure.TIMEOUT && index < routes.lastIndex)) {
                                val wait = if (e.kind == Failure.RATE_LIMIT) (e.retryAfterSec * 1000).coerceIn(1500, 6000) else 1500L
                                log("${r.client.name}: retrying in ${wait / 1000.0}s")
                                sleep(wait)
                                continue
                            }
                            if (e.kind == Failure.RATE_LIMIT) health.cool(r.id, now() + 60_000)
                        }
                        Failure.QUOTA_DAY -> {
                            quotaHits++
                            lastQuotaLabel = r.client.name
                            health.cool(r.id, nextQuotaReset(now(), e.retryAfterSec))
                        }
                        Failure.NOT_FOUND -> health.cool(r.id, now() + 7L * 24 * 3600_000)
                        Failure.BAD_KEY -> deadProviders += r.provider
                        else -> {}
                    }
                    break
                }
            }
        }

        val spoken = when {
            routes.isEmpty() -> "To answer questions I need your free Gemini key. Add it in Settings."
            lastFailure == Failure.BAD_KEY && quotaHits == 0 ->
                "My AI key was rejected. Open Settings and check your Gemini key."
            quotaHits > 0 || (lastFailure == null && skipped > 0) ->
                "I've used up today's free AI limit on every model, boss. It resets around 1:30 in the afternoon India time. " +
                    (if (routes.none { it.provider == "groq" }) "Adding a free Groq key in Settings gives me a backup. " else "") +
                    "Phone commands still work."
            lastFailure == Failure.TIMEOUT -> "The AI is taking too long to answer right now. Try again in a moment."
            lastFailure == Failure.OVERLOADED || lastFailure == Failure.RATE_LIMIT ->
                "The AI servers are busy right now. Give me a few seconds and ask again."
            lastFailure == Failure.EMPTY -> "The AI came back with an empty answer. Try asking it a bit differently."
            else -> "I couldn't get an answer from the AI: ${details.lastOrNull() ?: "unknown error"}"
        }
        throw AllRoutesFailed(spoken, details)
    }

    companion object {
        /** Google resets free daily quotas at midnight Pacific time. Use its retry hint when given, else ~ the next reset. */
        fun nextQuotaReset(nowMs: Long, retryAfterSec: Long): Long {
            if (retryAfterSec > 0) return nowMs + retryAfterSec * 1000
            val pacific = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            pacific.timeInMillis = nowMs
            pacific.add(java.util.Calendar.DAY_OF_YEAR, 1)
            pacific.set(java.util.Calendar.HOUR_OF_DAY, 0); pacific.set(java.util.Calendar.MINUTE, 1)
            pacific.set(java.util.Calendar.SECOND, 0); pacific.set(java.util.Calendar.MILLISECOND, 0)
            return pacific.timeInMillis
        }

        /** The default chain for his keys. [preferred] = a model he picked in Settings ("auto" = default order). */
        fun routesFor(geminiKey: String, groqKey: String, preferred: String = GeminiClient.DEFAULT_MODEL, transport: Transport = Http): List<Route> = buildList {
            if (geminiKey.isNotBlank()) {
                val models = (listOfNotNull(preferred.takeIf { it.isNotBlank() && it != GeminiClient.DEFAULT_MODEL }) +
                    GeminiClient.CHAIN).distinct()
                models.forEach { add(Route("gemini:$it", "gemini", GeminiClient(geminiKey, it, transport = transport))) }
            }
            if (groqKey.isNotBlank()) GroqClient.MODELS.forEach { add(Route("groq:$it", "groq", GroqClient(groqKey, it, transport = transport))) }
            // Gemma shares the Gemini key but has its own, much bigger free quota: last resort before giving up.
            if (geminiKey.isNotBlank()) add(Route("gemini:${GeminiClient.BACKUP_MODEL}", "gemini",
                GeminiClient(geminiKey, GeminiClient.BACKUP_MODEL, transport = transport)))
        }
    }
}
