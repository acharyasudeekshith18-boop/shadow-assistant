package com.sudeekshith.shadow.ai

import com.sudeekshith.shadow.core.Command
import org.json.JSONArray
import org.json.JSONObject

/** The phone actions offered to the AI as functions, so natural phrasing ("ping amma on whatsapp that I'm late") still triggers real actions. */
object Tools {
    data class Param(val name: String, val type: String, val description: String, val required: Boolean = true)
    data class Fn(val name: String, val description: String, val params: List<Param>)

    val all = listOf(
        Fn("open_app", "Open an installed app on the phone", listOf(Param("app_name", "string", "App name, e.g. Instagram, Camera, Settings"))),
        Fn("set_alarm", "Set an alarm (also use for reminders at a clock time)", listOf(
            Param("hour", "integer", "Hour in 24h format, 0-23"),
            Param("minute", "integer", "Minute 0-59"),
            Param("label", "string", "Optional label / reminder text", required = false))),
        Fn("set_timer", "Start a countdown timer (also use for 'remind me in N minutes')", listOf(
            Param("seconds", "integer", "Duration in seconds"),
            Param("label", "string", "Optional label / reminder text", required = false))),
        Fn("call_contact", "Phone call a contact by name or a phone number", listOf(Param("who", "string", "Contact name or phone number"))),
        Fn("send_whatsapp", "Open WhatsApp with a message ready to send to a contact", listOf(
            Param("who", "string", "Contact name or phone number"),
            Param("message", "string", "The message text, written as the user wants it sent"))),
        Fn("web_search", "Search the web in the browser", listOf(Param("query", "string", "Search query"))),
        Fn("play_youtube", "Search / play a video or song on YouTube", listOf(Param("query", "string", "What to play"))),
        Fn("flashlight", "Turn the flashlight (torch) on or off", listOf(Param("on", "boolean", "true = on, false = off"))),
        Fn("battery_status", "Tell the phone's battery level", emptyList()),
        Fn("end_conversation", "The user is saying goodbye / wants Shadow to stop listening", emptyList()),
    )

    private fun schema(fn: Fn, upperTypes: Boolean): JSONObject {
        val props = JSONObject()
        fn.params.forEach { p ->
            props.put(p.name, JSONObject().put("type", if (upperTypes) p.type.uppercase() else p.type).put("description", p.description))
        }
        val o = JSONObject().put("type", if (upperTypes) "OBJECT" else "object").put("properties", props)
        val req = fn.params.filter { it.required }.map { it.name }
        if (req.isNotEmpty()) o.put("required", JSONArray(req))
        return o
    }

    /** Gemini "functionDeclarations" format. */
    fun geminiDeclarations(): JSONArray = JSONArray().apply {
        all.forEach { fn ->
            val d = JSONObject().put("name", fn.name).put("description", fn.description)
            if (fn.params.isNotEmpty()) d.put("parameters", schema(fn, upperTypes = true))
            put(d)
        }
    }

    /** OpenAI-compatible tools format (Groq). */
    fun openAiTools(): JSONArray = JSONArray().apply {
        all.forEach { fn ->
            put(JSONObject().put("type", "function").put("function",
                JSONObject().put("name", fn.name).put("description", fn.description).put("parameters", schema(fn, upperTypes = false))))
        }
    }

    /** Turns an AI function call into a [Command]. Returns null for unknown/invalid calls. */
    fun toCommand(name: String, args: JSONObject): Command? = try {
        when (name) {
            "open_app" -> Command.OpenApp(args.getString("app_name"))
            "set_alarm" -> Command.SetAlarm(args.getInt("hour"), args.optInt("minute", 0), args.optString("label").ifBlank { null })
            "set_timer" -> Command.SetTimer(args.getInt("seconds"), args.optString("label").ifBlank { null })
            "call_contact" -> Command.Call(args.getString("who"))
            "send_whatsapp" -> Command.WhatsApp(args.getString("who"), args.getString("message"))
            "web_search" -> Command.WebSearch(args.getString("query"))
            "play_youtube" -> Command.PlayYouTube(args.getString("query"))
            "flashlight" -> Command.Flashlight(args.optBoolean("on", true))
            "battery_status" -> Command.Battery
            "end_conversation" -> Command.StopConversation
            else -> null
        }
    } catch (e: Exception) { null }
}
