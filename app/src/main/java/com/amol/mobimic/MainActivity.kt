package com.amol.mobimic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amol.mobimic.audio.EngineController
import com.amol.mobimic.service.MicService
import com.amol.mobimic.ui.MobiMicViewModel
import com.amol.mobimic.ui.screen.ConnectionScreen
import com.amol.mobimic.ui.screen.HomeScreen
import com.amol.mobimic.ui.screen.MixerScreen
import com.amol.mobimic.ui.theme.MobiMicTheme
import com.amol.mobimic.ui.theme.semantic

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MobiMicTheme {
                MobiMicApp()
            }
        }
    }
}

private enum class Tab(val label: String) {
    Home("Level"),
    Mixer("Mixer"),
    Connection("Connect"),
}

@Composable
private fun MobiMicApp(viewModel: MobiMicViewModel = viewModel()) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(Tab.Home) }

    val status by viewModel.engineStatus.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val dsp by viewModel.dsp.collectAsState()
    val presetName by viewModel.presetName.collectAsState()
    val host by viewModel.host.collectAsState()
    val port by viewModel.port.collectAsState()
    val wireFormat by viewModel.wireFormat.collectAsState()
    val autoStream by viewModel.autoStream.collectAsState()
    val linkMode by viewModel.linkMode.collectAsState()
    val autoDiscover by viewModel.autoDiscover.collectAsState()
    val links by viewModel.links.collectAsState()
    val discovered by viewModel.discovered.collectAsState()
    val scanning by viewModel.scanning.collectAsState()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        hasPermission = granted[Manifest.permission.RECORD_AUDIO] == true
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = semantic.canvas,
        topBar = { LargeTitle(title = "mobiMic", live = status.running) },
        bottomBar = {
            TabBar(current = tab, onSelect = { tab = it })
        },
    ) { padding ->
        // Cross-fade rather than slide: these are peers, not a hierarchy, and a
        // horizontal slide would imply an order that does not exist.
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
            label = "tab",
        ) { current ->
            when (current) {
                Tab.Home -> HomeScreen(
                    capabilities = viewModel.capabilities,
                    status = status,
                    stats = stats,
                    hasPermission = hasPermission,
                    onRequestPermission = {
                        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissions += Manifest.permission.POST_NOTIFICATIONS
                        }
                        permissionLauncher.launch(permissions.toTypedArray())
                    },
                    onToggleCapture = {
                        if (status.running) MicService.stop(context) else MicService.start(context)
                    },
                    onToggleRecording = {
                        if (status.recording) {
                            EngineController.stopRecording()
                        } else {
                            val dir = context.getExternalFilesDir(null) ?: context.filesDir
                            EngineController.startRecording(dir)
                        }
                    },
                    modifier = Modifier.padding(padding),
                )

                Tab.Mixer -> MixerScreen(
                    dsp = dsp,
                    presetName = presetName,
                    onPreset = viewModel::applyPreset,
                    onChange = viewModel::updateDsp,
                    modifier = Modifier.padding(padding),
                )

                Tab.Connection -> ConnectionScreen(
                    host = host,
                    port = port,
                    wireFormat = wireFormat,
                    autoStream = autoStream,
                    linkMode = linkMode,
                    autoDiscover = autoDiscover,
                    links = links,
                    discovered = discovered,
                    scanning = scanning,
                    status = status,
                    stats = stats,
                    onHostChange = viewModel::setHost,
                    onPortChange = viewModel::setPort,
                    onWireFormatChange = viewModel::setWireFormat,
                    onAutoStreamChange = viewModel::setAutoStream,
                    onLinkModeChange = viewModel::setLinkMode,
                    onAutoDiscoverChange = viewModel::setAutoDiscover,
                    onScan = viewModel::scanForReceivers,
                    onUseDiscovered = viewModel::useDiscovered,
                    onToggleStreaming = {
                        if (status.streaming) {
                            MicService.stopStreaming(context)
                        } else {
                            MicService.startStreaming(context)
                        }
                    },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

/**
 * A large title with a live dot.
 *
 * No app bar chrome, no divider: on a black canvas the title alone establishes the
 * top of the page, and anything more would be decoration. The dot is the only
 * always-visible indication that audio is running, which matters when the app is
 * on a screen you glance at rather than read.
 */
@Composable
private fun LargeTitle(title: String, live: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(semantic.canvas)
            // Edge-to-edge means the status bar sits over us unless we say otherwise.
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (live) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(semantic.live))
        }
    }
}

/**
 * A flat tab bar.
 *
 * Text-only, with the selected item taking the accent. Icons were tried and removed:
 * three labels are clearer than three glyphs that all mean "audio thing", and the
 * bar reads as part of the page rather than a floating slab.
 */
@Composable
private fun TabBar(current: Tab, onSelect: (Tab) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.background(semantic.canvas)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(semantic.separator)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 10.dp),
        ) {
            Tab.entries.forEach { entry ->
                val selected = entry == current
                Box(
                    Modifier
                        .weight(1f)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) {
                            if (!selected) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(entry)
                            }
                        }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        entry.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else semantic.labelSecondary,
                    )
                }
            }
        }
    }
}
