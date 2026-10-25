package com.sudeekshith.shadow

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sudeekshith.shadow.ai.ChatTurn
import com.sudeekshith.shadow.ai.GeminiClient
import com.sudeekshith.shadow.ai.GroqClient
import com.sudeekshith.shadow.ai.LlmClient
import com.sudeekshith.shadow.ai.LlmReply
import com.sudeekshith.shadow.ai.Persona
import com.sudeekshith.shadow.core.Command
import com.sudeekshith.shadow.core.IntentRouter
import com.sudeekshith.shadow.data.SettingsStore
import com.sudeekshith.shadow.system.ActionExecutor
import com.sudeekshith.shadow.system.Listener
import com.sudeekshith.shadow.system.ShadowVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class Status { Idle, Listening, Thinking, Speaking }

data class UiState(
    val messages: List<ChatTurn> = emptyList(),
    val status: Status = Status.Idle,
    val partial: String = "",
    val level: Float = 0f,
    val conversation: Boolean = false,
    val hasKey: Boolean = false,
    val pendingPermissions: List<String> = emptyList(),
    val openSettings: Boolean = false,
)

class ShadowViewModel(app: Application) : AndroidViewModel(app) {
    val settings = SettingsStore(app)
    val voice = ShadowVoice(app, settings)
    private val listener = Listener(app)
    private val executor = ActionExecutor(app, settings)

    private val _state = MutableStateFlow(UiState(messages = settings.loadHistory(), hasKey = settings.geminiKey.isNotBlank() || settings.groqKey.isNotBlank()))
    val state: StateFlow<UiState> = _state

    private var pendingCommand: Command? = null
    private var pendingAiText: String = ""
    private var silentTries = 0
    private var greeted = false

    init {
        listener.onPartial = { p -> _state.update { it.copy(partial = p) } }
        listener.onLevel = { l -> _state.update { it.copy(level = l) } }
        listener.onResult = { text ->
            silentTries = 0
            _state.update { it.copy(partial = "", level = 0f) }
            handle(text)
        }
        listener.onError = { code ->
            _state.update { it.copy(partial = "", level = 0f, status = Status.Idle) }
            if (Listener.isSilence(code)) {
                silentTries++
                if (_state.value.conversation && silentTries < 2) listen()
                else _state.update { it.copy(conversation = false) }
            } else {
                _state.update { it.copy(conversation = false) }
                addShadow(Listener.describe(code))
            }
        }
    }

    fun refreshKeys() = _state.update { it.copy(hasKey = settings.geminiKey.isNotBlank() || settings.groqKey.isNotBlank()) }

    /** Time-based greeting, spoken once when the app opens. */
    fun greet(thenListen: Boolean) {
        if (greeted) { if (thenListen) startConversation(); return }
        greeted = true
        if (!settings.greetOnOpen) { if (thenListen) startConversation(); return }
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when (h) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Working late" }
        val line = if (part == "Working late") "Working late, ${settings.userName}? Shadow is online. What do you need?"
                   else "$part, ${settings.userName}. Shadow is online. How can I help?"
        say(line, continueListening = thenListen, force = thenListen)
    }

    /** Big orb / mic button. */
    fun onOrbTap() {
        when (_state.value.status) {
            Status.Idle -> startConversation()
            else -> stopAll()
        }
    }

    fun startConversation() {
        silentTries = 0
        _state.update { it.copy(conversation = settings.continuous) }
        listen()
    }

    private fun listen() {
        voice.stop()
        if (!listener.available) { addShadow("Speech recognition isn't available on this phone. Install the Google app, or type to me below."); return }
        _state.update { it.copy(status = Status.Listening, partial = "") }
        listener.start(settings.listenLanguage)
    }

    fun stopAll() {
        listener.stop(); voice.stop()
        _state.update { it.copy(status = Status.Idle, conversation = false, partial = "", level = 0f) }
    }

    fun submitTyped(text: String) {
        if (text.isBlank()) return
        listener.stop(); voice.stop()
        _state.update { it.copy(conversation = false) }
        handle(text.trim())
    }

    private fun handle(text: String) {
        addUser(text)
        val cmd = IntentRouter.parse(text)
        if (cmd != null) { run(listOf(cmd), aiText = ""); return }
        askAi(text)
    }

    private fun clientChain(): List<LlmClient> = buildList {
        if (settings.geminiKey.isNotBlank()) add(GeminiClient(settings.geminiKey, settings.geminiModel))
        if (settings.groqKey.isNotBlank()) add(GroqClient(settings.groqKey))
    }

    private fun askAi(text: String) {
        val chain = clientChain()
        if (chain.isEmpty()) {
            say("I can do phone commands offline, like open apps, alarms, calls and WhatsApp. To answer questions, add your free Gemini key in Settings.")
            return
        }
        _state.update { it.copy(status = Status.Thinking) }
        val history = _state.value.messages.dropLast(1).takeLast(12)
        val now = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.US).format(Date())
        val system = Persona.system(settings.userName, now)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                var lastError: Exception? = null
                for (c in chain) {
                    try { return@withContext Result.success(c.reply(system, history, text)) } catch (e: Exception) { lastError = e }
                }
                Result.failure<LlmReply>(lastError ?: Exception("No AI available"))
            }
            result.onSuccess { r ->
                if (r.commands.isNotEmpty()) run(r.commands, r.text)
                else say(r.text.ifBlank { "Hmm, I have nothing on that one." })
            }.onFailure { e ->
                val msg = e.message ?: "unknown error"
                say(if (msg.contains("Unable to resolve host") || msg.contains("failed to connect") || msg.contains("timeout", true))
                    "I'm offline right now, boss. Phone commands still work, but I need internet to answer questions."
                else "I couldn't reach the AI: $msg")
            }
        }
    }

    private fun run(cmds: List<Command>, aiText: String, askPermissions: Boolean = true) {
        val first = cmds.first()
        val missing = executor.permissionsFor(first)
        if (askPermissions && missing.isNotEmpty()) {
            pendingCommand = first; pendingAiText = aiText
            _state.update { it.copy(pendingPermissions = missing, status = Status.Idle) }
            return
        }
        if (first is Command.OpenSettings) _state.update { it.copy(openSettings = true) }
        val results = cmds.map { executor.execute(it) }
        val stop = cmds.any { it is Command.StopConversation }
        // Prefer the AI's own words when it gave some; the executor's confirmation is the fallback.
        val spoken = if (aiText.isNotBlank() && results.none { it.startsWith("I couldn't") || it.startsWith("Sorry") || it.startsWith("I need") }) aiText
                     else results.joinToString(" ")
        if (stop) {
            _state.update { it.copy(conversation = false) }
            say(spoken, continueListening = false)
        } else {
            // After launching another app, don't keep listening in the background.
            val leavesApp = cmds.any { it is Command.OpenApp || it is Command.Call || it is Command.WhatsApp || it is Command.WebSearch || it is Command.PlayYouTube }
            say(spoken, continueListening = !leavesApp)
        }
    }

    fun onPermissionsResult() {
        val cmd = pendingCommand ?: return
        pendingCommand = null
        _state.update { it.copy(pendingPermissions = emptyList()) }
        if (executor.permissionsFor(cmd).contains(android.Manifest.permission.READ_CONTACTS)) {
            say("I need contacts permission to find that person. You can also tell me the phone number.", continueListening = false)
        } else {
            run(listOf(cmd), pendingAiText, askPermissions = false)   // a missing call permission falls back to the dialer
        }
    }

    fun consumeOpenSettings() = _state.update { it.copy(openSettings = false) }

    private fun say(text: String, continueListening: Boolean = true, force: Boolean = false) {
        addShadow(text)
        if (!settings.speakReplies) {
            _state.update { it.copy(status = Status.Idle) }
            if ((force || _state.value.conversation) && continueListening) listen()
            return
        }
        _state.update { it.copy(status = Status.Speaking) }
        voice.speak(text) {
            _state.update { it.copy(status = Status.Idle) }
            if (continueListening && (force || _state.value.conversation)) listen()
        }
    }

    private fun addUser(t: String) = append(ChatTurn(true, t))
    private fun addShadow(t: String) = append(ChatTurn(false, t))
    private fun append(turn: ChatTurn) {
        _state.update { it.copy(messages = (it.messages + turn).takeLast(60)) }
        settings.saveHistory(_state.value.messages)
    }

    fun clearChat() {
        _state.update { it.copy(messages = emptyList()) }
        settings.saveHistory(emptyList())
    }

    override fun onCleared() {
        listener.stop(); voice.shutdown()
    }
}
