package com.sudeekshith.shadow

import com.sudeekshith.shadow.core.AppMatcher
import com.sudeekshith.shadow.core.Command
import com.sudeekshith.shadow.core.IntentRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentRouterTest {
    private fun p(s: String) = IntentRouter.parse(s)

    @Test fun openApp() {
        assertEquals(Command.OpenApp("instagram"), p("Open Instagram"))
        assertEquals(Command.OpenApp("youtube"), p("hey shadow, please open YouTube app"))
        assertEquals(Command.OpenApp("camera"), p("launch the camera"))
    }
    @Test fun alarms() {
        assertEquals(Command.SetAlarm(6, 30), p("set an alarm for 6:30 am"))
        assertEquals(Command.SetAlarm(18, 0), p("Set alarm at 6 pm"))
        assertEquals(Command.SetAlarm(7, 15), p("alarm 7.15"))
        assertEquals(Command.SetAlarm(5, 0, "Wake up"), p("wake me up at 5 in the morning"))
        assertEquals(Command.SetAlarm(0, 0), p("set alarm for 12 am"))
    }
    @Test fun timersAndReminders() {
        assertEquals(Command.SetTimer(600), p("set a timer for 10 minutes"))
        assertEquals(Command.SetTimer(30), p("timer 30 seconds"))
        assertEquals(Command.SetTimer(300), p("5 minute timer"))
        assertEquals(Command.SetTimer(1200, "drink water"), p("remind me in 20 minutes to drink water"))
        assertEquals(Command.SetAlarm(21, 0, "study"), p("remind me at 9 pm to study"))
        assertEquals(Command.SetAlarm(17, 30, "call mom"), p("remind me to call mom at 5:30 pm"))
    }
    @Test fun calls() {
        assertEquals(Command.Call("amma"), p("Call Amma"))
        assertEquals(Command.Call("9876543210"), p("call 9876543210"))
        assertEquals(Command.Call("rahul"), p("dial rahul"))
    }
    @Test fun whatsapp() {
        assertEquals(Command.WhatsApp("rahul", "i'm coming in 5 minutes"), p("Send a WhatsApp message to Rahul saying I'm coming in 5 minutes"))
        assertEquals(Command.WhatsApp("amma", "reached college"), p("whatsapp amma reached college"))
        assertEquals(Command.WhatsApp("priya", "happy birthday"), p("send message to priya on whatsapp happy birthday"))
    }
    @Test fun searchAndYoutube() {
        assertEquals(Command.WebSearch("bca syllabus kuvempu"), p("search for BCA syllabus Kuvempu"))
        assertEquals(Command.WebSearch("weather in shimoga"), p("google weather in Shimoga"))
        assertEquals(Command.PlayYouTube("kantara songs"), p("play Kantara songs on YouTube"))
        assertEquals(Command.PlayYouTube("believer"), p("play believer"))
    }
    @Test fun deviceStuff() {
        assertEquals(Command.Flashlight(true), p("turn on the flashlight"))
        assertEquals(Command.Flashlight(false), p("torch off"))
        assertEquals(Command.Battery, p("what's my battery level"))
        assertEquals(Command.Time, p("what time is it?"))
        assertEquals(Command.Date, p("what's the date today"))
        assertEquals(Command.StopConversation, p("bye shadow"))
        assertEquals(Command.Greeting, p("hey shadow"))
    }
    @Test fun questionsGoToAi() {
        assertNull(p("what is the capital of Australia"))
        assertNull(p("explain newton's third law"))
        assertNull(p("tell me a joke"))
        assertNull(p("ನಾಳೆ ಹವಾಮಾನ ಹೇಗಿದೆ"))
    }
    @Test fun fuzzyMatch() {
        val apps = listOf("Instagram", "WhatsApp", "YouTube", "Google Maps", "Phone", "Calculator")
        assertEquals("Instagram", AppMatcher.best("insta", apps))
        assertEquals("WhatsApp", AppMatcher.best("whats app", apps))
        assertEquals("Google Maps", AppMatcher.best("maps", apps))
        assertEquals("Calculator", AppMatcher.best("calculater", apps))
        assertNull(AppMatcher.best("spotify", apps))
    }
}
