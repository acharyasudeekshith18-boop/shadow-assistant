package com.sudeekshith.shadow

import com.sudeekshith.shadow.ai.ChatTurn
import com.sudeekshith.shadow.ai.GeminiClient
import com.sudeekshith.shadow.ai.GroqClient
import com.sudeekshith.shadow.ai.Failure
import com.sudeekshith.shadow.ai.LlmException
import com.sudeekshith.shadow.core.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmParsingTest {
    @Test fun geminiRequestShape() {
        val req = GeminiClient.buildRequest("sys", listOf(ChatTurn(true, "hi"), ChatTurn(false, "hello boss")), "open insta")
        val contents = req.getJSONArray("contents")
        assertEquals(3, contents.length())
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        assertEquals("sys", req.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val fns = req.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        assertTrue(fns.length() >= 9)
        assertEquals("OBJECT", fns.getJSONObject(0).getJSONObject("parameters").getString("type"))
    }

    @Test fun geminiTextReply() {
        val raw = """{"candidates":[{"content":{"role":"model","parts":[{"text":"Canberra is the capital, boss."}]},"finishReason":"STOP"}]}"""
        val r = GeminiClient.parseResponse(raw)
        assertEquals("Canberra is the capital, boss.", r.text)
        assertTrue(r.commands.isEmpty())
    }

    @Test fun geminiFunctionCall() {
        val raw = """{"candidates":[{"content":{"role":"model","parts":[
            {"text":"On it."},
            {"functionCall":{"name":"send_whatsapp","args":{"who":"Amma","message":"I'll be late today"}}},
            {"functionCall":{"name":"set_alarm","args":{"hour":6,"minute":0,"label":"Gym"}}}]}}]}"""
        val r = GeminiClient.parseResponse(raw)
        assertEquals("On it.", r.text)
        assertEquals(listOf(Command.WhatsApp("Amma", "I'll be late today"), Command.SetAlarm(6, 0, "Gym")), r.commands)
    }

    @Test fun geminiBlocked() {
        val e = try { GeminiClient.parseResponse("""{"promptFeedback":{"blockReason":"SAFETY"}}"""); null } catch (e: LlmException) { e }
        assertEquals(Failure.BLOCKED, e?.kind)
    }

    @Test fun groqToolCall() {
        val raw = """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
            {"id":"c1","type":"function","function":{"name":"open_app","arguments":"{\"app_name\":\"Spotify\"}"}}]}}]}"""
        val r = GroqClient.parseResponse(raw)
        assertEquals(listOf(Command.OpenApp("Spotify")), r.commands)
        assertEquals("", r.text)
    }

    @Test fun groqRequestShape() {
        val req = GroqClient.buildRequest("llama-3.3-70b-versatile", "sys", emptyList(), "hello")
        assertEquals("system", req.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("function", req.getJSONArray("tools").getJSONObject(0).getString("type"))
    }
}
