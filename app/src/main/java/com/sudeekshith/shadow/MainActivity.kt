package com.sudeekshith.shadow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sudeekshith.shadow.ui.DebugLogScreen
import com.sudeekshith.shadow.ui.MainScreen
import com.sudeekshith.shadow.ui.SettingsScreen
import com.sudeekshith.shadow.ui.ShadowTheme

class MainActivity : ComponentActivity() {
    private val vm: ShadowViewModel by viewModels()
    private var listenAfterMicGrant = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { if (listenAfterMicGrant) vm.startConversation() }
        else Toast.makeText(this, "Shadow needs the microphone to hear you. You can still type.", Toast.LENGTH_LONG).show()
    }
    private val actionPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onPermissionsResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val launchedToListen = intent?.getBooleanExtra(EXTRA_LISTEN, false) == true || intent?.action == Intent.ACTION_ASSIST
        setContent {
            ShadowTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                var showSettings by remember { mutableStateOf(false) }
                var showDebug by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    if (savedInstanceState == null) {
                        if (launchedToListen && hasMic()) vm.greet(thenListen = true)
                        else vm.greet(thenListen = false)
                        if (!hasMic()) { listenAfterMicGrant = false; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
                    }
                }
                LaunchedEffect(state.pendingPermissions) {
                    if (state.pendingPermissions.isNotEmpty()) actionPermissions.launch(state.pendingPermissions.toTypedArray())
                }
                LaunchedEffect(state.openSettings) {
                    if (state.openSettings) { showSettings = true; vm.consumeOpenSettings() }
                }

                if (showDebug) {
                    var entries by remember { mutableStateOf(vm.debugLog.entries()) }
                    BackHandler { showDebug = false }
                    DebugLogScreen(entries, onClear = { vm.debugLog.clear(); entries = emptyList() }, onBack = { showDebug = false })
                } else if (showSettings) {
                    BackHandler { showSettings = false; vm.refreshKeys(); vm.voice.applyVoice() }
                    SettingsScreen(
                        settings = vm.settings,
                        deviceVoices = remember { vm.voice.availableVoices().map { it.name } },
                        onTestVoice = { vm.voice.applyVoice(); vm.voice.speak("Hello ${vm.settings.userName}. I am Shadow, your personal assistant.") {} },
                        onOpenUrl = { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                        onBack = { showSettings = false; vm.refreshKeys(); vm.voice.applyVoice() },
                        onOpenDebugLog = { showDebug = true },
                    )
                } else {
                    MainScreen(
                        state = state,
                        onOrbTap = { if (hasMic()) vm.onOrbTap() else { listenAfterMicGrant = true; micPermission.launch(Manifest.permission.RECORD_AUDIO) } },
                        onSend = vm::submitTyped,
                        onSettings = { vm.stopAll(); showSettings = true },
                        onClear = vm::clearChat,
                        onOrbHoldStart = { if (hasMic()) vm.onOrbHoldStart() else { listenAfterMicGrant = false; micPermission.launch(Manifest.permission.RECORD_AUDIO) } },
                        onOrbHoldEnd = vm::onOrbHoldEnd,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_LISTEN, false) || intent.action == Intent.ACTION_ASSIST) {
            if (hasMic()) vm.startConversation()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.stopAll()
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object { const val EXTRA_LISTEN = "listen" }
}
