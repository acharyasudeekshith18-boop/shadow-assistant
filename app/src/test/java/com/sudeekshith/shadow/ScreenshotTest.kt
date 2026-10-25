package com.sudeekshith.shadow

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.sudeekshith.shadow.ai.ChatTurn
import com.sudeekshith.shadow.data.SettingsStore
import com.sudeekshith.shadow.ui.MainScreen
import com.sudeekshith.shadow.ui.SettingsScreen
import com.sudeekshith.shadow.ui.ShadowTheme
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the real Compose screens on the JVM and saves PNGs to docs/screenshots (for the README). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val out = System.getProperty("user.dir")!! + "/../docs/screenshots/"

    private val chat = listOf(
        ChatTurn(false, "Good morning, Sudeekshith. Shadow is online. How can I help?"),
        ChatTurn(true, "What is Newton's third law?"),
        ChatTurn(false, "Every action has an equal and opposite reaction, boss. Like a rocket pushing gas down so it goes up."),
        ChatTurn(true, "Send a WhatsApp to Rahul saying I'm coming in 5 minutes"),
        ChatTurn(false, "WhatsApp is ready with your message to Rahul. Just tap send."),
    )

    private fun shot(name: String, state: UiState, advanceMs: Long = 700) {
        compose.mainClock.autoAdvance = false
        compose.setContent { ShadowTheme { MainScreen(state, {}, {}, {}, {}) } }
        compose.mainClock.advanceTimeBy(advanceMs)
        compose.onRoot().captureRoboImage(out + name)
    }

    @Test fun idle() = shot("01-home.png", UiState(messages = chat, hasKey = true))
    @Test fun listening() = shot("02-listening.png", UiState(messages = chat.take(3), status = Status.Listening, level = 0.8f,
        partial = "set an alarm for 6 am", conversation = true, hasKey = true), 900)
    @Test fun speaking() = shot("03-speaking.png", UiState(messages = chat, status = Status.Speaking, conversation = true, hasKey = true), 300)
    @Test fun offline() = shot("05-first-run.png", UiState(messages = listOf(ChatTurn(false, "Good evening, Sudeekshith. Shadow is online. How can I help?")), hasKey = false))

    @Test fun settings() {
        val s = SettingsStore(ApplicationProvider.getApplicationContext())
        compose.setContent { ShadowTheme { SettingsScreen(s, listOf("en-in-x-ene-network", "en-in-x-end-local"), {}, {}, {}) } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(out + "04-settings.png")
    }
}
