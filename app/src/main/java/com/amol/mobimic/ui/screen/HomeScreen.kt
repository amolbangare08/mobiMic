package com.amol.mobimic.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amol.mobimic.audio.AudioEffectsControl
import com.amol.mobimic.audio.EngineController
import com.amol.mobimic.audio.MicCapabilities
import com.amol.mobimic.audio.NativeAudioEngine
import com.amol.mobimic.ui.component.ColumnScopeRows
import com.amol.mobimic.ui.component.ContentRow
import com.amol.mobimic.ui.component.GainReductionMeter
import com.amol.mobimic.ui.component.LevelMeter
import com.amol.mobimic.ui.component.Section
import com.amol.mobimic.ui.component.StatusPill
import com.amol.mobimic.ui.component.TransportButton
import com.amol.mobimic.ui.component.ValueRow
import com.amol.mobimic.ui.component.RowDivider
import com.amol.mobimic.ui.component.toDb
import com.amol.mobimic.ui.theme.MonoNumeric
import com.amol.mobimic.ui.theme.MonoNumericLarge
import com.amol.mobimic.ui.theme.semantic

@Composable
fun HomeScreen(
    capabilities: MicCapabilities,
    status: EngineController.Status,
    stats: NativeAudioEngine.Stats,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onToggleCapture: () -> Unit,
    onToggleRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        HeroPanel(
            status = status,
            stats = stats,
            hasPermission = hasPermission,
            onRequestPermission = onRequestPermission,
            onToggleCapture = onToggleCapture,
            onToggleRecording = onToggleRecording,
        )

        AnimatedVisibility(
            visible = status.error != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(semantic.bad.copy(alpha = 0.14f))
                    .padding(16.dp)
            ) {
                Text(
                    status.error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = semantic.bad,
                )
            }
        }

        CapturePathSection(capabilities, status, stats)

        if (status.running) {
            ProcessingSection(stats)
        }

        StreamSection(stats)

        AnimatedVisibility(visible = status.recordingPath != null && stats.recordedFrames > 0) {
            RecordingSection(status, stats)
        }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * The part you look at while actually using the app: level, and the one control.
 *
 * Everything else on this screen is diagnostics, and is placed below the fold
 * accordingly - it matters when setting up and never again.
 */
@Composable
private fun HeroPanel(
    status: EngineController.Status,
    stats: NativeAudioEngine.Stats,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onToggleCapture: () -> Unit,
    onToggleRecording: () -> Unit,
) {
    val peakDb = toDb(stats.peak)
    val clipping = peakDb > -1f

    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(semantic.groupedSurface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        !hasPermission -> "Microphone off"
                        status.running -> "Live"
                        else -> "Ready"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    when {
                        !hasPermission -> "Permission needed"
                        status.streaming -> "Streaming to ${status.target}"
                        status.running -> "Capturing, not streaming"
                        else -> "Tap to start"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = semantic.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            if (status.running) {
                StatusPill(
                    text = if (status.overUsb) "USB" else "Wi-Fi",
                    color = if (status.overUsb) semantic.good else MaterialTheme.colorScheme.primary,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                val silent = stats.peak <= 0f
                Text(
                    if (silent) "—" else "%.1f".format(peakDb),
                    style = MaterialTheme.typography.displaySmall,
                    color = when {
                        silent -> semantic.labelTertiary
                        clipping -> semantic.bad
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    "dBFS peak",
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.labelTertiary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            LevelMeter(rmsDb = toDb(stats.rms), peakDb = peakDb, height = 14)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("−60", style = MonoNumeric, color = semantic.labelTertiary)
                Text("−12", style = MonoNumeric, color = semantic.labelTertiary)
                Text("0", style = MonoNumeric, color = semantic.labelTertiary)
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(
                running = status.running,
                enabled = true,
                onClick = { if (hasPermission) onToggleCapture() else onRequestPermission() },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when {
                        !hasPermission -> "Grant access"
                        status.running -> "Stop"
                        else -> "Start"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (stats.sampleRate > 0)
                        "%.1f ms · %.0f kHz".format(stats.totalLatencyMs, stats.sampleRate / 1000f)
                    else "Not capturing",
                    style = MonoNumeric,
                    color = semantic.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RecordChip(
                recording = status.recording,
                enabled = status.running,
                onClick = onToggleRecording,
            )
        }
    }
}

@Composable
private fun RecordChip(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = when {
        !enabled -> semantic.labelTertiary
        recording -> semantic.live
        else -> semantic.labelSecondary
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(tint.copy(alpha = 0.14f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(
            if (recording) "Stop WAV" else "Record",
            style = MaterialTheme.typography.labelLarge,
            color = tint,
        )
    }
}

@Composable
private fun CapturePathSection(
    capabilities: MicCapabilities,
    status: EngineController.Status,
    stats: NativeAudioEngine.Stats,
) {
    val supported = capabilities.unprocessedSupported
    Section(
        title = "Capture path",
        footnote = if (supported == false)
            "This device applies vendor processing that cannot be fully disabled. " +
                "Expect gating and level riding on the raw capture."
        else null,
    ) {
        ValueRow(
            label = "Unprocessed source",
            value = when (supported) {
                true -> "Supported"
                false -> "Not supported"
                null -> "Unknown"
            },
            valueColor = when (supported) {
                true -> semantic.good
                false -> semantic.bad
                null -> semantic.warn
            },
        )
        if (status.running) {
            RowDivider()
            ValueRow(
                label = "Preset in use",
                value = status.preset.name.lowercase().replaceFirstChar { it.uppercase() },
                valueColor = if (status.preset == NativeAudioEngine.InputPresetUsed.UNPROCESSED)
                    semantic.good else semantic.warn,
            )
            RowDivider()
            ValueRow(
                label = "Audio path",
                value = buildString {
                    append(if (stats.lowLatencyGranted) "Low latency" else "Legacy")
                    if (stats.mmapUsed) append(" · MMAP")
                },
                valueColor = if (stats.lowLatencyGranted) semantic.good else semantic.warn,
                detail = "${stats.framesPerBurst} frame burst · %.1f ms".format(stats.burstMs),
            )
            RowDivider()
            EffectRow("AGC", status.effects.agc)
            RowDivider()
            EffectRow("Noise suppressor", status.effects.noiseSuppressor)
            RowDivider()
            EffectRow("Echo canceller", status.effects.echoCanceler)
        }
    }
}

@Composable
private fun ColumnScopeRows.EffectRow(
    label: String,
    state: AudioEffectsControl.State,
) {
    ValueRow(
        label = label,
        value = when (state) {
            AudioEffectsControl.State.UNAVAILABLE -> "Not present"
            AudioEffectsControl.State.DISABLED -> "Off"
            AudioEffectsControl.State.STUCK_ON -> "Stuck on"
            AudioEffectsControl.State.FAILED -> "Unknown"
            AudioEffectsControl.State.SKIPPED -> "Not checked"
        },
        valueColor = when (state) {
            AudioEffectsControl.State.UNAVAILABLE, AudioEffectsControl.State.DISABLED -> semantic.good
            AudioEffectsControl.State.STUCK_ON -> semantic.bad
            AudioEffectsControl.State.FAILED -> semantic.warn
            AudioEffectsControl.State.SKIPPED -> semantic.labelTertiary
        },
    )
}

@Composable
private fun ProcessingSection(stats: NativeAudioEngine.Stats) {
    Section(title = "Processing") {
        ContentRow {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GainReductionMeter("Gate", stats.gateReductionDb, maxDb = 60f)
                GainReductionMeter("Compressor", stats.compReductionDb)
                GainReductionMeter("De-esser", stats.deEsserReductionDb, maxDb = 12f)
                GainReductionMeter("Limiter", stats.limiterReductionDb, maxDb = 12f)
            }
        }
    }
}

@Composable
private fun StreamSection(stats: NativeAudioEngine.Stats) {
    Section(title = "Stream") {
        ValueRow("Latency", "%.1f ms".format(stats.totalLatencyMs), mono = true,
            detail = "Capture plus DSP, on the phone")
        RowDivider()
        ValueRow("Sample rate", "${stats.sampleRate} Hz", mono = true)
        RowDivider()
        ValueRow("Callback", "${stats.callbackFrames} frames", mono = true)
        RowDivider()
        ValueRow(
            "XRuns", "${stats.xRuns}", mono = true,
            valueColor = if (stats.xRuns > 0) semantic.warn else Color.Unspecified,
        )
        RowDivider()
        ValueRow(
            "Callback load", "%.0f%%".format(stats.callbackLoad * 100), mono = true,
            valueColor = if (stats.callbackLoad > 0.5f) semantic.bad else Color.Unspecified,
            detail = "Worst case, against a %.1f ms deadline".format(stats.burstMs),
        )
        RowDivider()
        ValueRow(
            "Frames dropped", "${stats.framesDropped}", mono = true,
            valueColor = if (stats.framesDropped > 0) semantic.bad else Color.Unspecified,
        )
        RowDivider()
        ValueRow("Packets sent", "${stats.packetsSent}", mono = true)
    }
}

@Composable
private fun RecordingSection(status: EngineController.Status, stats: NativeAudioEngine.Stats) {
    Section(
        title = if (status.recording) "Recording" else "Last capture",
        footnote = status.recordingPath,
    ) {
        ContentRow {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "%.1f s".format(stats.recordedSeconds),
                    style = MonoNumericLarge,
                    color = if (status.recording) semantic.live else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "32-bit float WAV",
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.labelTertiary,
                )
            }
        }
    }
}
