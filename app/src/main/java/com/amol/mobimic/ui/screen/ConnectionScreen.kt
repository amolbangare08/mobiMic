package com.amol.mobimic.ui.screen

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.amol.mobimic.audio.EngineController
import com.amol.mobimic.audio.NativeAudioEngine
import com.amol.mobimic.audio.StreamSettings
import com.amol.mobimic.net.NetworkLinks
import com.amol.mobimic.net.ReceiverProbe
import com.amol.mobimic.ui.component.ColumnScopeRows
import com.amol.mobimic.ui.component.ContentRow
import com.amol.mobimic.ui.component.RowDivider
import com.amol.mobimic.ui.component.Section
import com.amol.mobimic.ui.component.SegmentedControl
import com.amol.mobimic.ui.component.SwitchRow
import com.amol.mobimic.ui.component.ValueRow
import com.amol.mobimic.ui.theme.MonoNumeric
import com.amol.mobimic.ui.theme.semantic

@Composable
fun ConnectionScreen(
    host: String,
    port: Int,
    wireFormat: NativeAudioEngine.WireFormat,
    autoStream: Boolean,
    linkMode: StreamSettings.LinkMode,
    autoDiscover: Boolean,
    links: List<NetworkLinks.Link>,
    discovered: List<ReceiverProbe.Found>,
    scanning: Boolean,
    status: EngineController.Status,
    stats: NativeAudioEngine.Stats,
    onHostChange: (String) -> Unit,
    onPortChange: (Int) -> Unit,
    onWireFormatChange: (NativeAudioEngine.WireFormat) -> Unit,
    onAutoStreamChange: (Boolean) -> Unit,
    onLinkModeChange: (StreamSettings.LinkMode) -> Unit,
    onAutoDiscoverChange: (Boolean) -> Unit,
    onScan: () -> Unit,
    onUseDiscovered: (ReceiverProbe.Found) -> Unit,
    onToggleStreaming: () -> Unit,
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

        Section(
            title = "Link",
            footnote = "USB tethering has far less jitter than Wi-Fi, so the PC can run a " +
                "smaller buffer. Auto takes the cable whenever one is there.",
        ) {
            ContentRow {
                SegmentedControl(
                    options = StreamSettings.LinkMode.entries,
                    selected = linkMode,
                    label = {
                        when (it) {
                            StreamSettings.LinkMode.AUTO -> "Auto"
                            StreamSettings.LinkMode.USB -> "USB"
                            StreamSettings.LinkMode.WIFI -> "Wi-Fi"
                        }
                    },
                    onSelect = onLinkModeChange,
                )
            }
            links.forEach { link ->
                RowDivider()
                ValueRow(
                    label = link.interfaceName,
                    value = link.hostAddress,
                    mono = true,
                    valueColor = if (link.isUsb) semantic.good else Color.Unspecified,
                    detail = link.kind.name.lowercase(),
                )
            }
        }

        Section(title = "Finding the PC") {
            SwitchRow(
                label = "Find automatically",
                checked = autoDiscover,
                onCheckedChange = onAutoDiscoverChange,
                detail = "Asks on every interface, so no address to type",
            )
            RowDivider()
            ActionRow(
                label = if (scanning) "Scanning…" else "Scan for receivers",
                enabled = !scanning,
                busy = scanning,
                onClick = onScan,
            )
            discovered.forEach { found ->
                RowDivider()
                ValueRow(
                    label = "${found.host}:${found.port}",
                    value = "Use",
                    valueColor = MaterialTheme.colorScheme.primary,
                    detail = "via ${found.viaInterface}" + if (found.overUsb) " · USB" else "",
                    onClick = { onUseDiscovered(found) },
                )
            }
            AnimatedVisibility(visible = !scanning && discovered.isEmpty() && links.isNotEmpty()) {
                ContentRow {
                    Text(
                        "Nothing answered. Is the receiver running on the PC?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = semantic.warn,
                    )
                }
            }
        }

        Section(title = "Receiver") {
            ContentRow {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = onHostChange,
                        label = { Text("PC address") },
                        singleLine = true,
                        textStyle = MonoNumeric,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        shape = MaterialTheme.shapes.small,
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = port.toString(),
                        onValueChange = { text -> text.toIntOrNull()?.let(onPortChange) },
                        label = { Text("Port") },
                        singleLine = true,
                        textStyle = MonoNumeric,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = MaterialTheme.shapes.small,
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            RowDivider()
            ContentRow {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Wire format",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    SegmentedControl(
                        options = NativeAudioEngine.WireFormat.entries,
                        selected = wireFormat,
                        label = { it.label },
                        onSelect = onWireFormatChange,
                    )
                }
            }
            RowDivider()
            SwitchRow(
                label = "Stream when capture starts",
                checked = autoStream,
                onCheckedChange = onAutoStreamChange,
            )
            RowDivider()
            ActionRow(
                label = if (status.streaming) "Stop streaming" else "Start streaming",
                enabled = status.running,
                destructive = status.streaming,
                onClick = onToggleStreaming,
            )
        }

        Section(title = "Transport") {
            ValueRow(
                "State",
                if (status.streaming) "Streaming" else "Idle",
                valueColor = if (status.streaming) semantic.good else semantic.labelSecondary,
                detail = if (status.streaming) status.target else null,
            )
            if (status.streaming) {
                RowDivider()
                ValueRow(
                    "Link in use",
                    status.link + if (status.overUsb) " · USB" else "",
                    valueColor = if (status.overUsb) semantic.good else Color.Unspecified,
                )
            }
            RowDivider()
            ValueRow("Packets sent", "${stats.packetsSent}", mono = true)
            RowDivider()
            ValueRow(
                "Send errors", "${stats.sendErrors}", mono = true,
                valueColor = if (stats.sendErrors > 0) semantic.bad else Color.Unspecified,
            )
        }

        Section(
            title = "On the PC",
            footnote = "Install VB-CABLE and set it to 48 kHz, then point your app's " +
                "microphone at CABLE Output.",
        ) {
            ContentRow {
                Text(
                    "python pc/receiver.py",
                    style = MonoNumeric,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ColumnScopeRows.ActionRow(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
    busy: Boolean = false,
) {
    val tint = when {
        !enabled -> semantic.labelTertiary
        destructive -> semantic.live
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = tint,
            )
        } else {
            Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = semantic.separator,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = semantic.labelTertiary,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
)
