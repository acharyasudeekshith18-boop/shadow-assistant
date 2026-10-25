package com.sudeekshith.shadow.core

import java.util.Locale

/**
 * Offline, rule-based command parser. Runs before the AI so common commands are instant
 * and work without internet. Returns null when the sentence isn't a known command;
 * the AI then handles it (and can still trigger actions through function calling).
 */
object IntentRouter {

    private val fillers = Regex("^(hey |hi |ok |okay |hello )?(shadow[,!.]?\\s*)?(please |can you |could you |would you |will you )*")
    private const val TIME = "(\\d{1,2})(?:[:. ](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?|in the morning|in the evening|at night|tonight)?"

    fun parse(input: String): Command? {
        var t = input.trim().lowercase(Locale.ROOT)
            .replace(Regex("[?!]+$"), "").replace(Regex("\\.$"), "").trim()
        if (Regex("^(hi|hello|hey|yo|ok|okay|wake up)?\\s*(there )?shadow[,!.]?$").matches(t)) return Command.Greeting
        t = fillers.replace(t, "").trim()
        if (t.isEmpty()) return null

        // stop / bye
        if (Regex("^(stop|bye|goodbye|good bye|bye bye|exit|quit|that's all|thats all|stop listening|go to sleep|shut up)( shadow)?( for now)?$").matches(t))
            return Command.StopConversation
        if (Regex("^(hi|hello|hey|yo|wake up)( there)?( shadow)?$").matches(t) || t == "shadow") return Command.Greeting
        if (Regex("^(open )?(shadow )?settings( of shadow)?$").matches(t) && t.contains("shadow")) return Command.OpenSettings

        // flashlight
        Regex("^(?:turn |switch )?(on|off) (?:the )?(?:flash ?light|torch|flash)$").find(t)?.let {
            return Command.Flashlight(it.groupValues[1] == "on")
        }
        Regex("^(?:turn |switch )?(?:the )?(?:flash ?light|torch|flash) (on|off)$").find(t)?.let {
            return Command.Flashlight(it.groupValues[1] == "on")
        }

        // battery / time / date
        if (Regex("(battery|charge) (level|percentage|status|left)|how much (battery|charge)|^battery$").containsMatchIn(t)) return Command.Battery
        if (Regex("^(what('s| is) the )?time( is it)?( now)?$|^what time is it( now)?$|^tell me the time$").matches(t)) return Command.Time
        if (Regex("^(what('s| is) )?(the |today's )?(date|day)( today| is it today| is it)?$|^what day is (it|today)$|^today's date$").matches(t)) return Command.Date

        // timer
        Regex("^(?:set |start )?(?:a )?timer (?:for )?(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)$").find(t)?.let {
            return Command.SetTimer(toSeconds(it.groupValues[1].toInt(), it.groupValues[2]))
        }
        Regex("^(?:set |start )?(?:a )?(\\d+)[ -](seconds?|secs?|minutes?|mins?|hours?|hrs?) timer$").find(t)?.let {
            return Command.SetTimer(toSeconds(it.groupValues[1].toInt(), it.groupValues[2]))
        }
        // reminders: "remind me in 10 minutes to drink water" / "remind me at 6 pm to study"
        Regex("^remind me in (\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?) (?:to |about )?(.+)$").find(t)?.let {
            return Command.SetTimer(toSeconds(it.groupValues[1].toInt(), it.groupValues[2]), it.groupValues[3].trim())
        }
        Regex("^remind me (?:to |about )?(.+?) in (\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)$").find(t)?.let {
            return Command.SetTimer(toSeconds(it.groupValues[2].toInt(), it.groupValues[3]), it.groupValues[1].trim())
        }
        Regex("^remind me at $TIME (?:to |about )?(.+)$").find(t)?.let {
            val (h, m) = toHourMinute(it.groupValues[1], it.groupValues[2], it.groupValues[3]) ?: return null
            return Command.SetAlarm(h, m, it.groupValues[4].trim())
        }
        Regex("^remind me (?:to |about )?(.+?) at $TIME$").find(t)?.let {
            val (h, m) = toHourMinute(it.groupValues[2], it.groupValues[3], it.groupValues[4]) ?: return null
            return Command.SetAlarm(h, m, it.groupValues[1].trim())
        }
        // alarm
        Regex("^(?:set |put |create )?(?:an |a |my )?alarm (?:for |at )?(?:tomorrow )?$TIME(?: tomorrow)?$").find(t)?.let {
            val (h, m) = toHourMinute(it.groupValues[1], it.groupValues[2], it.groupValues[3]) ?: return null
            return Command.SetAlarm(h, m)
        }
        Regex("^wake me (?:up )?(?:at )?$TIME(?: tomorrow)?$").find(t)?.let {
            val (h, m) = toHourMinute(it.groupValues[1], it.groupValues[2], it.groupValues[3]) ?: return null
            return Command.SetAlarm(h, m, "Wake up")
        }

        // whatsapp
        Regex("^(?:send )?(?:a )?(?:whatsapp|whats app|watsapp)(?: message| msg)? (?:to )?(.+?) (?:saying|that|message|msg|telling)(?: that)? (.+)$").find(t)?.let {
            return Command.WhatsApp(it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        Regex("^(?:send )?(?:a )?message (?:to )?(.+?) on (?:whatsapp|whats app) (?:saying |that )?(.+)$").find(t)?.let {
            return Command.WhatsApp(it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        Regex("^(?:send )?(?:a )?(?:whatsapp|whats app|watsapp)(?: message| msg)? (?:to )?(\\S+) (.+)$").find(t)?.let {
            return Command.WhatsApp(it.groupValues[1].trim(), it.groupValues[2].trim())
        }

        // call
        Regex("^(?:make a )?(?:phone )?call (?:to )?(.+?)(?: now| please)?$").find(t)?.let {
            val who = it.groupValues[1].trim()
            if (who.isNotEmpty() && who != "me") return Command.Call(who)
        }
        Regex("^(?:dial|ring|phone) (.+)$").find(t)?.let { return Command.Call(it.groupValues[1].trim()) }

        // youtube
        Regex("^play (.+?) on youtube$").find(t)?.let { return Command.PlayYouTube(it.groupValues[1].trim()) }
        Regex("^(?:search |find )?(.+?) on youtube$").find(t)?.let { return Command.PlayYouTube(it.groupValues[1].trim()) }
        Regex("^youtube (?:search )?(.+)$").find(t)?.let { return Command.PlayYouTube(it.groupValues[1].trim()) }
        Regex("^play (?:the )?(?:song |video )?(.+)$").find(t)?.let { return Command.PlayYouTube(it.groupValues[1].trim()) }

        // web search
        Regex("^(?:search|google|look up|search for|search google for|search the web for|google search)(?: for)? (.+)$").find(t)?.let {
            return Command.WebSearch(it.groupValues[1].trim())
        }

        // open app
        Regex("^(?:open|launch|start|run) (?:the )?(.+?)(?: app| application)?$").find(t)?.let {
            return Command.OpenApp(it.groupValues[1].trim())
        }
        return null
    }

    fun toSeconds(n: Int, unit: String): Int = when {
        unit.startsWith("h") -> n * 3600
        unit.startsWith("m") -> n * 60
        else -> n
    }

    /** Converts spoken time pieces to 24h hour/minute, or null if invalid. */
    fun toHourMinute(h: String, m: String, ampm: String): Pair<Int, Int>? {
        var hour = h.toIntOrNull() ?: return null
        val minute = if (m.isBlank()) 0 else m.toIntOrNull() ?: return null
        val a = ampm.replace(".", "").trim()
        val pm = a == "pm" || a.contains("evening") || a.contains("night")
        val am = a == "am" || a.contains("morning")
        if (pm && hour in 1..11) hour += 12
        if (am && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }
}
