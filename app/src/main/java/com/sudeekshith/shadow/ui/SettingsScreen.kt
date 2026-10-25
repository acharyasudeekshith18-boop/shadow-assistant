package com.sudeekshith.shadow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sudeekshith.shadow.data.SettingsStore

@Composable
fun SettingsScreen(
    settings: SettingsStore,
    deviceVoices: List<String>,
    onTestVoice: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
) {
    var gemini by remember { mutableStateOf(settings.geminiKey) }
    var groq by remember { mutableStateOf(settings.groqKey) }
    var eleven by remember { mutableStateOf(settings.elevenKey) }
    var name by remember { mutableStateOf(settings.userName) }
    var model by remember { mutableStateOf(settings.geminiModel) }
    var lang by remember { mutableStateOf(settings.listenLanguage) }
    var cc by remember { mutableStateOf(settings.countryCode) }
    var continuous by remember { mutableStateOf(settings.continuous) }
    var speak by remember { mutableStateOf(settings.speakReplies) }
    var greet by remember { mutableStateOf(settings.greetOnOpen) }
    var engine by remember { mutableStateOf(settings.voiceEngine) }
    var devVoice by remember { mutableStateOf(settings.deviceVoice) }
    var pitch by remember { mutableFloatStateOf(settings.pitch) }
    var speed by remember { mutableFloatStateOf(settings.speed) }
    var gVoice by remember { mutableStateOf(settings.geminiVoice) }

    fun save() {
        settings.geminiKey = gemini; settings.groqKey = groq; settings.elevenKey = eleven
        settings.userName = name; settings.geminiModel = model; settings.listenLanguage = lang; settings.countryCode = cc
        settings.continuous = continuous; settings.speakReplies = speak; settings.greetOnOpen = greet
        settings.voiceEngine = engine; settings.deviceVoice = devVoice; settings.pitch = pitch; settings.speed = speed
        settings.geminiVoice = gVoice
    }

    Column(Modifier.fillMaxSize().background(ShadowColors.Void).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { save(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = ShadowColors.Glow) }
            Text("SETTINGS", color = ShadowColors.Glow, fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Section("AI brain (free keys)")
            Text("Keys are stored encrypted on this phone only. They are never uploaded anywhere except to Google/Groq when you ask a question.",
                color = ShadowColors.Muted, fontSize = 12.sp)
            KeyField("Gemini API key", gemini) { gemini = it }
            TextButton(onClick = { onOpenUrl("https://aistudio.google.com/apikey") }) { Text("Get a free Gemini key →", color = ShadowColors.Glow) }
            KeyField("Groq key (optional backup)", groq) { groq = it }
            TextButton(onClick = { onOpenUrl("https://console.groq.com/keys") }) { Text("Get a free Groq key →", color = ShadowColors.Glow) }
            Field("Gemini model", model) { model = it }

            Section("You")
            Field("Your name", name) { name = it }
            Text("Listening language", color = ShadowColors.Text)
            listOf("en-IN" to "English (India)", "kn-IN" to "ಕನ್ನಡ Kannada", "hi-IN" to "हिन्दी Hindi").forEach { (code, label) ->
                RadioRow(label, lang == code) { lang = code }
            }
            Field("Country code for WhatsApp numbers", cc, KeyboardType.Number) { cc = it }

            Section("Conversation")
            SwitchRow("Keep listening after replying (conversation mode)", continuous) { continuous = it }
            SwitchRow("Speak replies out loud", speak) { speak = it }
            SwitchRow("Greet me when I open Shadow", greet) { greet = it }

            Section("Shadow's voice")
            RadioRow("Phone voice (free, works offline)", engine == "device") { engine = "device" }
            RadioRow("Gemini voice (more human, uses your Gemini key's free limits)", engine == "gemini") { engine = "gemini" }
            RadioRow("ElevenLabs voice (most human, needs a free ElevenLabs key)", engine == "elevenlabs") { engine = "elevenlabs" }
            if (engine == "device") {
                VoicePicker(deviceVoices, devVoice) { devVoice = it }
                Text("Pitch ${"%.2f".format(pitch)}", color = ShadowColors.Text)
                Slider(pitch, { pitch = it }, valueRange = 0.5f..1.5f)
                Text("Speed ${"%.2f".format(speed)}", color = ShadowColors.Text)
                Slider(speed, { speed = it }, valueRange = 0.6f..1.6f)
            }
            if (engine == "gemini") {
                Text("Gemini voice", color = ShadowColors.Text)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Charon", "Fenrir", "Orus", "Puck").forEach { v ->
                        OutlinedButton(onClick = { gVoice = v }, colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (gVoice == v) ShadowColors.PanelHi else ShadowColors.Void)) { Text(v, color = ShadowColors.Text, fontSize = 12.sp) }
                    }
                }
            }
            if (engine == "elevenlabs") {
                KeyField("ElevenLabs API key", eleven) { eleven = it }
                TextButton(onClick = { onOpenUrl("https://elevenlabs.io/app/settings/api-keys") }) { Text("Get a free ElevenLabs key →", color = ShadowColors.Glow) }
            }
            Button(onClick = { save(); onTestVoice() }, colors = ButtonDefaults.buttonColors(containerColor = ShadowColors.Violet)) {
                Text("Test Shadow's voice")
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { save(); onBack() }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ShadowColors.Magenta)) { Text("Save") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable private fun Section(t: String) =
    Text(t.uppercase(), color = ShadowColors.Glow, fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 10.dp))

@Composable private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = ShadowColors.Violet, unfocusedBorderColor = ShadowColors.PanelHi,
    focusedTextColor = ShadowColors.Text, unfocusedTextColor = ShadowColors.Text,
    focusedLabelColor = ShadowColors.Glow, unfocusedLabelColor = ShadowColors.Muted)

@Composable private fun Field(label: String, v: String, kb: KeyboardType = KeyboardType.Text, on: (String) -> Unit) =
    OutlinedTextField(v, on, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = kb), colors = fieldColors(), shape = RoundedCornerShape(12.dp))

@Composable private fun KeyField(label: String, v: String, on: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(v, on, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show", color = ShadowColors.Muted, fontSize = 12.sp) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), colors = fieldColors(), shape = RoundedCornerShape(12.dp))
}

@Composable private fun RadioRow(label: String, selected: Boolean, on: () -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, on)
        Text(label, color = ShadowColors.Text, fontSize = 14.sp)
    }

@Composable private fun SwitchRow(label: String, v: Boolean, on: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ShadowColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(v, on)
    }

@Composable private fun VoicePicker(voices: List<String>, selected: String, on: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Voice: " + selected.ifBlank { "Auto (deepest English voice)" }, color = ShadowColors.Text, fontSize = 13.sp)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("Auto (deepest English voice)") }, onClick = { on(""); open = false })
            voices.take(40).forEach { v -> DropdownMenuItem(text = { Text(v, fontSize = 13.sp) }, onClick = { on(v); open = false }) }
        }
    }
}
