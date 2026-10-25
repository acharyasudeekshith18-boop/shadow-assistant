package com.sudeekshith.shadow

import com.sudeekshith.shadow.ai.ChatTurn
import com.sudeekshith.shadow.ai.GeminiClient
import com.sudeekshith.shadow.ai.GroqClient
import com.sudeekshith.shadow.ai.Persona
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Writes the exact request bodies the app sends to build/live/, and parses real responses placed there
 * (used once to check the request/response contract against the live Gemini and Groq APIs; skipped otherwise).
 */
class LiveContractTest {
    private val dir = File(System.getProperty("user.dir")!!, "build/live").apply { mkdirs() }
    private val system = Persona.system("Sudeekshith", "Sunday 25 October 2026, 7:45 AM")
    private val history = listOf(ChatTurn(true, "hi"), ChatTurn(false, "Hey boss, Shadow here."))

    @Test fun dumpRequests() {
        File(dir, "gemini-q.json").writeText(GeminiClient.buildRequest(system, history, "In one sentence, what is Newton's third law?").toString())
        File(dir, "gemini-a.json").writeText(GeminiClient.buildRequest(system, history, "ping amma on whatsapp that I'll be home by 8").toString())
        File(dir, "groq-a.json").writeText(GroqClient.buildRequest("llama-3.3-70b-versatile", system, history, "wake me up at 6:15 tomorrow").toString())
    }

    @Test fun parseLiveResponses() {
        val files = listOf("gemini-q", "gemini-a", "groq-a").map { File(dir, "$it.response.json") }
        assumeTrue(files.all { it.exists() })
        val out = StringBuilder()
        files.forEach { f ->
            val r = if (f.name.startsWith("groq")) GroqClient.parseResponse(f.readText()) else GeminiClient.parseResponse(f.readText())
            out.append(f.name).append(" -> text=\"").append(r.text).append("\" commands=").append(r.commands).append("\n")
        }
        File(dir, "parsed.txt").writeText(out.toString())
    }
}
