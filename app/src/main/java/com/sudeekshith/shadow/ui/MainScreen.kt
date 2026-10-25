package com.sudeekshith.shadow.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sudeekshith.shadow.R
import com.sudeekshith.shadow.Status
import com.sudeekshith.shadow.UiState
import com.sudeekshith.shadow.ai.ChatTurn

@Composable
fun MainScreen(
    state: UiState,
    onOrbTap: () -> Unit,
    onSend: (String) -> Unit,
    onSettings: () -> Unit,
    onClear: () -> Unit,
    onOrbHoldStart: () -> Unit = {},
    onOrbHoldEnd: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(ShadowColors.Deep, ShadowColors.Void, ShadowColors.Void)))
            .statusBarsPadding().navigationBarsPadding().imePadding()
    ) {
        // top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("S H A D O W", color = ShadowColors.Glow, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(if (state.hasKey) "AI online · voice ready" else "Offline mode · add your free Gemini key",
                    color = if (state.hasKey) ShadowColors.Muted else ShadowColors.Magenta, fontSize = 12.sp)
            }
            IconButton(onClick = onClear) { Icon(Icons.Filled.Delete, "Clear chat", tint = ShadowColors.Muted) }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings", tint = ShadowColors.Glow) }
        }

        // orb
        Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
            ShadowOrb(state.status, state.level,
                Modifier.size(240.dp).clip(CircleShape)
                    .orbGestures(onTap = onOrbTap, onHoldStart = onOrbHoldStart, onHoldEnd = onOrbHoldEnd))
        }
        Text(
            when (state.status) {
                Status.Idle -> if (state.conversation) "…" else "TAP TO TALK · OR HOLD THE ORB"
                Status.Listening -> if (state.holding) "LISTENING · RELEASE TO SEND" else "LISTENING · TAP TO SEND"
                Status.Thinking -> "THINKING…"
                Status.Speaking -> "SPEAKING · TAP TO STOP"
            },
            Modifier.fillMaxWidth(), color = ShadowColors.Glow, textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 3.sp
        )
        if (state.partial.isNotBlank()) {
            Text("“${state.partial}”", Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                color = ShadowColors.Text, textAlign = TextAlign.Center, fontSize = 15.sp)
        }
        if (state.hint.isNotBlank() && state.partial.isBlank()) {
            Text(state.hint, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp), color = ShadowColors.Muted,
                textAlign = TextAlign.Center, fontSize = 12.sp)
        }
        if (state.conversation) {
            Text("Conversation mode · say “bye” to stop", Modifier.fillMaxWidth(), color = ShadowColors.Muted,
                textAlign = TextAlign.Center, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))

        // chat
        val list = rememberLazyListState()
        LaunchedEffect(state.messages.size) { if (state.messages.isNotEmpty()) list.animateScrollToItem(state.messages.lastIndex) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(state.messages) { _, m -> Bubble(m) }
        }

        // input row
        var text by remember { mutableStateOf("") }
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Type to Shadow…", color = ShadowColors.Muted) }, singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend(text); text = "" }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ShadowColors.Violet, unfocusedBorderColor = ShadowColors.PanelHi,
                    focusedTextColor = ShadowColors.Text, unfocusedTextColor = ShadowColors.Text,
                    focusedContainerColor = ShadowColors.Panel, unfocusedContainerColor = ShadowColors.Panel)
            )
            Spacer(Modifier.size(8.dp))
            val sendMode = text.isNotBlank()
            Box(
                Modifier.size(54.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(ShadowColors.Glow, ShadowColors.Violet)))
                    .clickable { if (sendMode) { onSend(text); text = "" } else onOrbTap() },
                contentAlignment = Alignment.Center
            ) {
                if (sendMode) Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = ShadowColors.Void)
                else Icon(painterResource(R.drawable.ic_mic), "Talk", tint = ShadowColors.Void, modifier = Modifier.size(26.dp))
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatTurn) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        val shape = RoundedCornerShape(18.dp, 18.dp, if (m.fromUser) 4.dp else 18.dp, if (m.fromUser) 18.dp else 4.dp)
        Column(
            Modifier.widthIn(max = 300.dp).clip(shape)
                .background(if (m.fromUser) ShadowColors.Violet.copy(alpha = 0.85f) else if (m.meta) ShadowColors.Void else ShadowColors.Panel)
                .border(1.dp, if (m.fromUser) ShadowColors.Violet else ShadowColors.PanelHi, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (!m.fromUser) Text("SHADOW", color = ShadowColors.Glow, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
            Text(m.text, color = if (m.meta) ShadowColors.Muted else ShadowColors.Text, fontSize = if (m.meta) 13.sp else 15.sp, lineHeight = 20.sp)
        }
    }
}

/** Tap = talk (tap again to send), press-and-hold = push-to-talk: listens while held, sends on release. */
private fun Modifier.orbGestures(onTap: () -> Unit, onHoldStart: () -> Unit, onHoldEnd: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val up = withTimeoutOrNull(HOLD_MS) { waitForUpOrCancellation() }
            if (up != null) {
                onTap()
            } else if (currentEvent.changes.any { it.pressed }) {
                onHoldStart()
                waitForUpOrCancellation()
                onHoldEnd()
            }
        }
    }

private const val HOLD_MS = 400L
