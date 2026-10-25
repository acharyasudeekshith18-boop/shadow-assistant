package com.sudeekshith.shadow

import com.sudeekshith.shadow.ai.AiRouter
import com.sudeekshith.shadow.ai.AllRoutesFailed
import com.sudeekshith.shadow.ai.ChatTurn
import com.sudeekshith.shadow.ai.Failure
import com.sudeekshith.shadow.ai.GeminiClient
import com.sudeekshith.shadow.ai.HistoryTrim
import com.sudeekshith.shadow.ai.Http
import com.sudeekshith.shadow.ai.LlmException
import com.sudeekshith.shadow.ai.MemoryModelHealth
import com.sudeekshith.shadow.ai.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRouterTest {
    private val ok = """{"candidates":[{"content":{"parts":[{"text":"Bengaluru, boss."}]},"finishReason":"STOP"}]}"""
    private val groqOk = """{"choices":[{"message":{"content":"Answer from Groq."},"finish_reason":"stop"}]}"""
    // Google's real 429 body for a used-up daily free quota (shape copied from a live reply, 25 Oct 2026)
    private val daily429 = """{"error":{"code":429,"message":"You exceeded your current quota... Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 500","status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]},{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"15335s"}]}}"""
    private val minute429 = """{"error":{"code":429,"message":"Quota exceeded","details":[{"violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]},{"retryDelay":"2s"}]}}"""
    private val gone404 = """{"error":{"code":404,"message":"This model models/gemini-2.5-flash is no longer available to new users."}}"""

    /** Fake network: answers by URL substring, in order, recording calls. */
    private class FakeNet(val script: MutableMap<String, MutableList<() -> String>>) : Transport {
        val calls = mutableListOf<String>()
        override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): String {
            val key = script.keys.first { url.contains(it) }
            calls += key
            val q = script.getValue(key)
            val f = if (q.size > 1) q.removeAt(0) else q.first()
            return f()
        }
    }
    private fun err(code: Int, body: String): () -> String = { throw Http.httpError(code, body) }

    private fun router(net: FakeNet, groq: String = "", health: MemoryModelHealth = MemoryModelHealth(), sleeps: MutableList<Long> = mutableListOf()) =
        AiRouter(AiRouter.routesFor("AIzaFAKE", groq, transport = net), health, sleep = { sleeps += it })

    @Test fun chainOrderMatchesPlan() {
        val ids = AiRouter.routesFor("k", "g").map { it.id }
        assertEquals(listOf("gemini:gemini-2.5-flash", "gemini:gemini-flash-latest", "gemini:gemini-flash-lite-latest",
            "groq:llama-3.3-70b-versatile", "groq:openai/gpt-oss-120b", "groq:llama-3.1-8b-instant", "gemini:gemma-4-31b-it"), ids)
        assertTrue(AiRouter.routesFor("", "").isEmpty())
    }

    @Test fun closedModelAndDailyLimitFallThroughWithNotice() {
        val net = FakeNet(mutableMapOf(
            "gemini-2.5-flash:" to mutableListOf(err(404, gone404)),
            "gemini-flash-latest:" to mutableListOf(err(429, daily429)),
            "gemini-flash-lite-latest:" to mutableListOf({ ok }),
        ))
        val health = MemoryModelHealth()
        val r = router(net, health = health).ask("sys", emptyList(), "capital of karnataka?")
        assertEquals("Bengaluru, boss.", r.reply.text)
        assertTrue(r.notices.single().contains("free limit"))
        // next question skips the closed and the used-up model without asking Google again
        net.calls.clear()
        val r2 = router(net, health = health).ask("sys", emptyList(), "again")
        assertEquals(listOf("gemini-flash-lite-latest:"), net.calls)
        assertTrue(r2.notices.isEmpty())
    }

    @Test fun retriesOnceOn503ThenSucceeds() {
        val sleeps = mutableListOf<Long>()
        val net = FakeNet(mutableMapOf(
            "gemini-2.5-flash:" to mutableListOf(err(503, "{}"), { ok }),
        ))
        val r = router(net, sleeps = sleeps).ask("sys", emptyList(), "hi")
        assertEquals("Bengaluru, boss.", r.reply.text)
        assertEquals(1, sleeps.size)
    }

    @Test fun perMinute429RetriesWithBackoff() {
        val sleeps = mutableListOf<Long>()
        val net = FakeNet(mutableMapOf("gemini-2.5-flash:" to mutableListOf(err(429, minute429), { ok })))
        router(net, sleeps = sleeps).ask("sys", emptyList(), "hi")
        assertEquals(listOf(2000L), sleeps)
    }

    @Test fun groqTakesOverWhenGeminiIsOut() {
        val net = FakeNet(mutableMapOf(
            "generativelanguage" to mutableListOf(err(429, daily429)),
            "groq" to mutableListOf({ groqOk }),
        ))
        val r = router(net, groq = "gsk_FAKE").ask("sys", emptyList(), "hi")
        assertEquals("Answer from Groq.", r.reply.text)
        assertTrue(r.usedBy.startsWith("Groq"))
    }

    @Test fun gemmaIsLastResortOnSameKey() {
        val net = FakeNet(mutableMapOf(
            "gemma-4" to mutableListOf({ """{"candidates":[{"content":{"parts":[{"text":"","thought":true},{"text":"Gemma here."}]},"finishReason":"STOP"}]}""" }),
            "generativelanguage" to mutableListOf(err(429, daily429)),
        ))
        val r = router(net).ask("sys", emptyList(), "hi")
        assertEquals("Gemma here.", r.reply.text)
    }

    @Test fun noInternetStopsWithClearMessage() {
        val net = FakeNet(mutableMapOf("generativelanguage" to mutableListOf({ throw LlmException("No internet", kind = Failure.NETWORK) })))
        val e = try { router(net).ask("sys", emptyList(), "hi"); null } catch (e: AllRoutesFailed) { e }
        assertTrue(e!!.spoken.contains("internet"))
        assertEquals(1, net.calls.size)
    }

    @Test fun everythingOutOfQuotaSaysSoPlainly() {
        val net = FakeNet(mutableMapOf("generativelanguage" to mutableListOf(err(429, daily429))))
        val e = try { router(net).ask("sys", emptyList(), "hi"); null } catch (e: AllRoutesFailed) { e }
        assertTrue(e!!.spoken.contains("free AI limit"))
        assertTrue(e.spoken.contains("Groq"))
    }

    @Test fun badKeyStopsGoogleRoutes() {
        val net = FakeNet(mutableMapOf("generativelanguage" to mutableListOf(err(400, """{"error":{"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""))))
        val e = try { router(net).ask("sys", emptyList(), "hi"); null } catch (e: AllRoutesFailed) { e }
        assertTrue(e!!.spoken.contains("key"))
        assertEquals(1, net.calls.size)
    }

    @Test fun safetyBlockGivesPoliteAnswer() {
        val net = FakeNet(mutableMapOf("gemini-2.5-flash:" to mutableListOf({ """{"candidates":[{"finishReason":"SAFETY","content":{"parts":[]}}]}""" })))
        val r = router(net).ask("sys", emptyList(), "x")
        assertTrue(r.reply.text.contains("can't answer"))
    }

    @Test fun maxTokensWithEmptyTextRetriesWithoutFastMode() {
        val net = FakeNet(mutableMapOf("gemini-2.5-flash:" to mutableListOf(
            { """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"","thought":true}]}}]}""" }, { ok })))
        val r = router(net).ask("sys", emptyList(), "x")
        assertEquals("Bengaluru, boss.", r.reply.text)
        assertEquals(2, net.calls.size)
    }

    @Test fun maxTokensWithTextEndsOnFullSentence() {
        val raw = """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"First full sentence here. Second full sentence too. Third one is cut of"}]}}]}"""
        assertEquals("First full sentence here. Second full sentence too.", GeminiClient.parseResponse(raw).text)
    }

    @Test fun errorClassification() {
        assertEquals(Failure.QUOTA_DAY, Http.httpError(429, daily429).kind)
        assertEquals(15335L, Http.httpError(429, daily429).retryAfterSec)
        assertEquals(Failure.RATE_LIMIT, Http.httpError(429, minute429).kind)
        assertEquals(Failure.NOT_FOUND, Http.httpError(404, gone404).kind)
        assertEquals(Failure.OVERLOADED, Http.httpError(503, "").kind)
        assertEquals(Failure.BAD_KEY, Http.httpError(403, "").kind)
    }

    @Test fun historyIsTrimmedAndCleaned() {
        val long = "x".repeat(5000)
        val h = listOf(ChatTurn(false, "Good morning boss"), ChatTurn(true, "q1"), ChatTurn(false, "I couldn't reach the AI", meta = true),
            ChatTurn(false, long), ChatTurn(true, "q2"), ChatTurn(false, "a2"))
        val t = HistoryTrim.trim(h)
        assertTrue(t.first().fromUser)
        assertFalse(t.any { it.meta })
        assertTrue(t.sumOf { it.text.length } <= HistoryTrim.MAX_TOTAL_CHARS)
        assertTrue(t.all { it.text.length <= HistoryTrim.MAX_TURN_CHARS + 1 })
        val many = (1..40).map { ChatTurn(it % 2 == 1, "t$it") }
        assertTrue(HistoryTrim.trim(many).size <= HistoryTrim.MAX_TURNS)
    }

    @Test fun requestHasRoomForFullAnswers() {
        val fast = GeminiClient.buildRequest("s", emptyList(), "q", "gemini-flash-latest", fast = true).getJSONObject("generationConfig")
        assertEquals(1024, fast.getInt("maxOutputTokens"))
        assertEquals("minimal", fast.getJSONObject("thinkingConfig").getString("thinkingLevel"))
        val f25 = GeminiClient.buildRequest("s", emptyList(), "q", "gemini-2.5-flash", fast = true).getJSONObject("generationConfig")
        assertEquals(0, f25.getJSONObject("thinkingConfig").getInt("thinkingBudget"))
        val slow = GeminiClient.buildRequest("s", emptyList(), "q", "gemini-flash-latest", fast = false).getJSONObject("generationConfig")
        assertFalse(slow.has("thinkingConfig"))
    }
}
