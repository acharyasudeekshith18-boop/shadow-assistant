package com.sudeekshith.shadow

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sudeekshith.shadow.ai.ChatTurn
import com.sudeekshith.shadow.ai.AiRouter
import com.sudeekshith.shadow.ai.AllRoutesFailed
import com.sudeekshith.shadow.ai.HistoryTrim
import com.sudeekshith.shadow.ai.Persona
import com.sudeekshith.shadow.ai.RoutedReply
import com.sudeekshith.shadow.core.Command
import com.sudeekshith.shadow.core.IntentRouter
import com.sudeekshith.shadow.core.ListenPolicy
import com.sudeekshith.shadow.data.DebugLog
import com.sudeekshith.shadow.data.SettingsStore
import com.sudeekshith.shadow.system.ActionExecutor
import com.sudeekshith.shadow.system.Listener
import com.sudeekshith.shadow.system.ShadowVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val holding: Boolean = false,
    val hint: String = "",
)

class ShadowViewModel(app: Application) : AndroidViewModel(app) {
    val settings = SettingsStore(app)
    val debugLog = DebugLog(app)
    val voice = ShadowVoice(app, settings)
    private val listener = Listener(app) { debugLog.add(it) }
    private val executor = ActionExecutor(app, settings)

    private val _state = MutableStateFlow(UiState(messages = settings.loadHistory(), hasKey = settings.geminiKey.isNotBlank() || settings.groqKey.isNotBlank()))
    val state: StateFlow<UiState> = _state

    private var pendingCommand: Command? = null
    private var pendingAiText: String = ""
    private var silentTries = 0
    private var greeted = false
    private var listenJob: Job? = null
    private var aiJob: Job? = null

    init {
        listener.onLive = { p -> _state.update { it.copy(partial = p, hint = "") } }
        listener.onLevel = { l -> _state.update { it.copy(level = l) } }
        listener.onFinished = { text ->
            silentTries = 0
            _state.update { it.copy(partial = "", level = 0f, holding = false) }
            handle(text)
        }
        listener.onNothingHeard = {
            _state.update { it.copy(partial = "", level = 0f, status = Status.Idle, holding = false) }
            silentTries++
            // Continuous mode survives a couple of silences (he may just be thinking).
            if (_state.value.conversation && silentTries < ListenPolicy.MAX_SILENT_LISTENS) listen(delayMs = 150)
            else _state.update { it.copy(conversation = false, hint = "Didn't hear anything. Tap the orb, or hold it while you talk.") }
        }
        listener.onError = { code ->
            debugLog.add("Speech: ${Listener.name(code)} (code $code)")
            _state.update { it.copy(partial = "", level = 0f, status = Status.Idle, conversation = false, holding = false) }
            addShadow(Listener.describe(code), meta = true)
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

    /** Tap on the orb: idle -> listen; listening -> send what was heard; thinking/speaking -> stop. */
    fun onOrbTap() {
        when (_state.value.status) {
            Status.Idle -> startConversation()
            Status.Listening -> if (listener.isActive) listener.release() else stopAll()
            else -> stopAll()
        }
    }

    /** Press-and-hold the orb: push-to-talk. Listens until release, never cuts off. */
    fun onOrbHoldStart() {
        listenJob?.cancel(); aiJob?.cancel()
        voice.stop()
        if (!listener.available) { addShadow("Speech recognition isn't available on this phone. Install the Google app, or type to me below.", meta = true); return }
        _state.update { it.copy(status = Status.Listening, partial = "", hint = "", holding = true, conversation = false) }
        listener.begin(settings.listenLanguage, settings.patience, hold = true)
    }

    fun onOrbHoldEnd() {
        if (_state.value.holding) {
            _state.update { it.copy(holding = false) }
            listener.release()
        }
    }

    fun startConversation() {
        silentTries = 0
        _state.update { it.copy(conversation = settings.continuous, hint = "") }
        listen()
    }

    private fun listen(delayMs: Long = 0) {
        voice.stop()
        listenJob?.cancel()
        if (!listener.available) { addShadow("Speech recognition isn't available on this phone. Install the Google app, or type to me below.", meta = true); return }
        _state.update { it.copy(status = Status.Listening, partial = "", holding = false) }
        listenJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            listener.begin(settings.listenLanguage, settings.patience, hold = false)
        }
    }

    fun stopAll() {
        listenJob?.cancel(); aiJob?.cancel()
        listener.cancel(); voice.stop()
        _state.update { it.copy(status = Status.Idle, conversation = false, partial = "", level = 0f, holding = false) }
    }

    fun submitTyped(text: String) {
        if (text.isBlank()) return
        listenJob?.cancel(); listener.cancel(); voice.stop()
        _state.update { it.copy(conversation = false, status = Status.Idle, partial = "") }
        handle(text.trim())
    }

    private fun handle(text: String) {
        addUser(text)
        val cmd = IntentRouter.parse(text)
        if (cmd != null) { run(listOf(cmd), aiText = ""); return }
        askAi(text)
    }

    private fun askAi(text: String) {
        val routes = AiRouter.routesFor(settings.geminiKey, settings.groqKey, settings.geminiModel)
        if (routes.isEmpty()) {
            say("I can do phone commands offline, like open apps, alarms, calls and WhatsApp. To answer questions, add your free Gemini key in Settings.")
            return
        }
        _state.update { it.copy(status = Status.Thinking) }
        val history = HistoryTrim.trim(_state.value.messages.dropLast(1))
        val now = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.US).format(Date())
        val system = Persona.system(settings.userName, now)
        val router = AiRouter(routes, settings.modelHealth, log = { debugLog.add("AI: $it") })
        aiJob = viewModelScope.launch {
            val result: Result<RoutedReply> = withContext(Dispatchers.IO) {
                try { Result.success(router.ask(system, history, text)) } catch (e: Exception) { Result.failure(e) }
            }
            if (_state.value.status != Status.Thinking) return@launch   // he tapped stop meanwhile
            result.onSuccess { r ->
                val notice = r.notices.joinToString(" ")
                if (notice.isNotBlank()) addShadow(notice, meta = true)
                val reply = r.reply
                if (reply.commands.isNotEmpty()) run(reply.commands, reply.text, prefix = notice)
                else say(reply.text, spokenPrefix = notice)
            }.onFailure { e ->
                val spoken = (e as? AllRoutesFailed)?.spoken ?: "Something went wrong: ${e.message}"
                if (e !is AllRoutesFailed) debugLog.add("AI crash: ${e::class.java.simpleName}: ${e.message}")
                say(spoken, meta = true)
            }
        }
    }

    private fun run(cmds: List<Command>, aiText: String, askPermissions: Boolean = true, prefix: String = "") {
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
            say(spoken, continueListening = false, spokenPrefix = prefix)
        } else {
            // After launching another app, don't keep listening in the background.
            val leavesApp = cmds.any { it is Command.OpenApp || it is Command.Call || it is Command.WhatsApp || it is Command.WebSearch || it is Command.PlayYouTube }
            say(spoken, continueListening = !leavesApp, spokenPrefix = prefix)
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

    /** Shows [text], speaks it (with an optional spoken-only [spokenPrefix]), then listens again in conversation mode — only after the voice has fully finished. */
    private fun say(text: String, continueListening: Boolean = true, force: Boolean = false, meta: Boolean = false, spokenPrefix: String = "") {
        addShadow(text, meta)
        if (!settings.speakReplies) {
            _state.update { it.copy(status = Status.Idle) }
            if ((force || _state.value.conversation) && continueListening) listen(delayMs = 300)
            return
        }
        _state.update { it.copy(status = Status.Speaking) }
        val spoken = if (spokenPrefix.isNotBlank()) "$spokenPrefix $text" else text
        voice.speak(spoken) {
            _state.update { it.copy(status = Status.Idle) }
            // Short pause after the last word so the mic doesn't catch the end of Shadow's own voice.
            if (continueListening && (force || _state.value.conversation)) listen(delayMs = 450)
        }
    }

    private fun addUser(t: String) = append(ChatTurn(true, t))
    private fun addShadow(t: String, meta: Boolean = false) = append(ChatTurn(false, t, meta))
    private fun append(turn: ChatTurn) {
        _state.update { it.copy(messages = (it.messages + turn).takeLast(60)) }
        settings.saveHistory(_state.value.messages)
    }

    fun clearChat() {
        _state.update { it.copy(messages = emptyList()) }
        settings.saveHistory(emptyList())
    }

    override fun onCleared() {
        listener.shutdown(); voice.shutdown()
    }
}
