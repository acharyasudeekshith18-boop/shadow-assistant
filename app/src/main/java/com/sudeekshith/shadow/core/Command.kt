package com.sudeekshith.shadow.core

/** Every action Shadow can perform on the phone. Produced by [IntentRouter] (offline) or by the AI (function calling). */
sealed class Command {
    data class OpenApp(val name: String) : Command()
    data class SetAlarm(val hour: Int, val minute: Int, val label: String? = null) : Command()
    data class SetTimer(val seconds: Int, val label: String? = null) : Command()
    data class Call(val who: String) : Command()
    data class WhatsApp(val who: String, val message: String) : Command()
    data class WebSearch(val query: String) : Command()
    data class PlayYouTube(val query: String) : Command()
    data class Flashlight(val on: Boolean) : Command()
    object Battery : Command()
    object Time : Command()
    object Date : Command()
    object Greeting : Command()
    object StopConversation : Command()
    object OpenSettings : Command()
}
