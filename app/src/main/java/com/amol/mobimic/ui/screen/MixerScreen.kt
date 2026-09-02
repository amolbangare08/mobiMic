package com.amol.mobimic.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.amol.mobimic.audio.DspSettings
import com.amol.mobimic.audio.Presets
import com.amol.mobimic.ui.component.ContentRow
import com.amol.mobimic.ui.component.ParamSlider
import com.amol.mobimic.ui.component.RowDivider
import com.amol.mobimic.ui.component.Section
import com.amol.mobimic.ui.theme.MonoNumeric
import com.amol.mobimic.ui.theme.semantic
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun MixerScreen(
    dsp: DspSettings,
    presetName: String,
    onPreset: (String, DspSettings) -> Unit,
    onChange: ((DspSettings) -> DspSettings) -> Unit,
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

        PresetRow(presetName, onPreset)

        EqPanel(dsp, onChange)

        Module("Chain", dsp.enabled, { on -> onChange { it.copy(enabled = on) } }) {
            ParamSlider("Input gain", dsp.inputGainDb, -12f..24f, " dB", enabled = dsp.enabled) { v ->
                onChange { it.copy(inputGainDb = v) }
            }
            ParamSlider("Output gain", dsp.outputGainDb, -24f..12f, " dB", enabled = dsp.enabled) { v ->
                onChange { it.copy(outputGainDb = v) }
            }
        }

        Module("High-pass", dsp.hpfEnabled, { on -> onChange { it.copy(hpfEnabled = on) } },
            caption = "24 dB per octave") {
            ParamSlider("Corner", dsp.hpfHz, 40f..160f, " Hz", decimals = 0, enabled = dsp.hpfEnabled) { v ->
                onChange { it.copy(hpfHz = v) }
            }
        }

        Module("Gate", dsp.gateEnabled, { on -> onChange { it.copy(gateEnabled = on) } }) {
            ParamSlider("Threshold", dsp.gateThresholdDb, -70f..-20f, " dB", enabled = dsp.gateEnabled) { v ->
                onChange { it.copy(gateThresholdDb = v) }
            }
            ParamSlider("Ratio", dsp.gateRatio, 1f..12f, ":1", enabled = dsp.gateEnabled) { v ->
                onChange { it.copy(gateRatio = v) }
            }
            ParamSlider("Hold", dsp.gateHoldMs, 0f..300f, " ms", decimals = 0, enabled = dsp.gateEnabled) { v ->
                onChange { it.copy(gateHoldMs = v) }
            }
            ParamSlider("Release", dsp.gateReleaseMs, 30f..600f, " ms", decimals = 0, enabled = dsp.gateEnabled) { v ->
                onChange { it.copy(gateReleaseMs = v) }
            }
        }

        Module("Noise suppression", dsp.nsEnabled, { on -> onChange { it.copy(nsEnabled = on) } },
            caption = "Spectral, adds 5.3 ms") {
            ParamSlider("Amount", dsp.nsMix, 0f..1f, "", decimals = 2, enabled = dsp.nsEnabled) { v ->
                onChange { it.copy(nsMix = v) }
            }
        }

        Module("De-esser", dsp.deEsserEnabled, { on -> onChange { it.copy(deEsserEnabled = on) } }) {
            ParamSlider("Split", dsp.deEsserSplitHz, 3000f..9000f, " Hz", decimals = 0, enabled = dsp.deEsserEnabled) { v ->
                onChange { it.copy(deEsserSplitHz = v) }
            }
            ParamSlider("Threshold", dsp.deEsserThresholdDb, -50f..-10f, " dB", enabled = dsp.deEsserEnabled) { v ->
                onChange { it.copy(deEsserThresholdDb = v) }
            }
            ParamSlider("Ratio", dsp.deEsserRatio, 1f..10f, ":1", enabled = dsp.deEsserEnabled) { v ->
                onChange { it.copy(deEsserRatio = v) }
            }
        }

        Module("Compressor", dsp.compressorEnabled, { on -> onChange { it.copy(compressorEnabled = on) } }) {
            ParamSlider("Threshold", dsp.compThresholdDb, -50f..0f, " dB", enabled = dsp.compressorEnabled) { v ->
                onChange { it.copy(compThresholdDb = v) }
            }
            ParamSlider("Ratio", dsp.compRatio, 1f..12f, ":1", enabled = dsp.compressorEnabled) { v ->
                onChange { it.copy(compRatio = v) }
            }
            ParamSlider("Knee", dsp.compKneeDb, 0f..18f, " dB", enabled = dsp.compressorEnabled) { v ->
                onChange { it.copy(compKneeDb = v) }
            }
            ParamSlider("Attack", dsp.compAttackMs, 1f..50f, " ms", enabled = dsp.compressorEnabled) { v ->
                onChange { it.copy(compAttackMs = v) }
            }
            ParamSlider("Release", dsp.compReleaseMs, 30f..500f, " ms", decimals = 0, enabled = dsp.compressorEnabled) { v ->
                onChange { it.copy(compReleaseMs = v) }
            }
        }

        Module("Saturation", dsp.saturationEnabled, { on -> onChange { it.copy(saturationEnabled = on) } },
            caption = "2x oversampled") {
            ParamSlider("Drive", dsp.saturationDriveDb, 0f..18f, " dB", enabled = dsp.saturationEnabled) { v ->
                onChange { it.copy(saturationDriveDb = v) }
            }
            ParamSlider("Mix", dsp.saturationMix, 0f..1f, "", decimals = 2, enabled = dsp.saturationEnabled) { v ->
                onChange { it.copy(saturationMix = v) }
            }
        }

        Module("Limiter", dsp.limiterEnabled, { on -> onChange { it.copy(limiterEnabled = on) } }) {
            ParamSlider("Ceiling", dsp.limiterCeilingDb, -6f..0f, " dB", enabled = dsp.limiterEnabled) { v ->
                onChange { it.copy(limiterCeilingDb = v) }
            }
            ParamSlider("Release", dsp.limiterReleaseMs, 20f..400f, " ms", decimals = 0, enabled = dsp.limiterEnabled) { v ->
                onChange { it.copy(limiterReleaseMs = v) }
            }
            ParamSlider("Lookahead", dsp.limiterLookaheadMs, 0.5f..8f, " ms", enabled = dsp.limiterEnabled) { v ->
                onChange { it.copy(limiterLookaheadMs = v) }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Presets as a horizontal row of pills.
 *
 * These are the first thing most people touch and the last thing they change, so
 * they sit at the top and stay out of the way afterwards.
 */
@Composable
private fun PresetRow(presetName: String, onPreset: (String, DspSettings) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Presets.all.forEach { (name, preset) ->
            val selected = presetName == name
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else semantic.groupedSurface
                    )
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onPreset(name, preset)
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) Color.White else semantic.labelSecondary,
                )
            }
        }
    }
}

/**
 * A processing block: title, its own switch, and controls that grey out when it is
 * off rather than disappearing, so the layout never jumps under your thumb.
 */
@Composable
private fun Module(
    title: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    caption: String? = null,
    content: @Composable () -> Unit,
) {
    Section {
        ContentRow {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (caption != null) {
                            Text(
                                caption,
                                style = MaterialTheme.typography.bodySmall,
                                color = semantic.labelTertiary,
                            )
                        }
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = onEnabledChange,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = semantic.good,
                            checkedThumbColor = Color.White,
                            checkedBorderColor = Color.Transparent,
                            uncheckedTrackColor = semantic.fill,
                            uncheckedThumbColor = Color.White,
                            uncheckedBorderColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
        RowDivider()
        ContentRow {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        }
    }
}

@Composable
private fun EqPanel(dsp: DspSettings, onChange: ((DspSettings) -> DspSettings) -> Unit) {
    Module("EQ", dsp.eqEnabled, { on -> onChange { it.copy(eqEnabled = on) } },
        caption = "Five bands") {
        EqCurve(dsp)
        Spacer(Modifier.height(4.dp))
        dsp.eq.forEachIndexed { index, band ->
            Text(
                band.label,
                style = MaterialTheme.typography.labelMedium,
                color = semantic.labelSecondary,
                modifier = Modifier.padding(top = 10.dp),
            )
            ParamSlider("Frequency", band.frequencyHz, 40f..16000f, " Hz",
                decimals = 0, enabled = dsp.eqEnabled) { v ->
                onChange { settings -> settings.withBand(index) { it.copy(frequencyHz = v) } }
            }
            ParamSlider("Gain", band.gainDb, -15f..15f, " dB", enabled = dsp.eqEnabled) { v ->
                onChange { settings -> settings.withBand(index) { it.copy(gainDb = v) } }
            }
            ParamSlider("Q", band.q, 0.2f..6f, "", decimals = 2, enabled = dsp.eqEnabled) { v ->
                onChange { settings -> settings.withBand(index) { it.copy(q = v) } }
            }
        }
    }
}

/**
 * The response curve.
 *
 * Evaluated from the same transfer functions the native filters implement, so it
 * shows what the audio thread is doing rather than an artist's impression. The fill
 * under the curve is what makes a small graph readable at a glance - the eye picks
 * up an area far faster than a one-pixel line.
 */
@Composable
private fun EqCurve(dsp: DspSettings) {
    val curveColor = MaterialTheme.colorScheme.primary
    val gridColor = semantic.separator
    val zeroColor = semantic.labelTertiary

    Box(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(semantic.canvas)
    ) {
        Canvas(Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 8.dp, vertical = 10.dp)) {
            val minHz = 30.0
            val maxHz = 18000.0
            val maxDb = 18.0

            fun xFor(hz: Double) = (ln(hz / minHz) / ln(maxHz / minHz)).toFloat() * size.width
            fun yFor(db: Double) = (0.5 - db / (2 * maxDb)).toFloat() * size.height

            listOf(100.0, 1000.0, 10000.0).forEach { hz ->
                val x = xFor(hz)
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }
            drawLine(zeroColor, Offset(0f, yFor(0.0)), Offset(size.width, yFor(0.0)), strokeWidth = 1f)

            val steps = 220
            val points = ArrayList<Offset>(steps + 1)
            for (i in 0..steps) {
                val hz = minHz * (maxHz / minHz).pow(i.toDouble() / steps)
                var db = 0.0
                if (dsp.hpfEnabled) db += highPassDb(hz, dsp.hpfHz.toDouble())
                if (dsp.eqEnabled) {
                    dsp.eq.forEachIndexed { index, band ->
                        if (band.enabled && abs(band.gainDb) > 0.01f) {
                            db += bandDb(index, hz, band.frequencyHz.toDouble(),
                                band.gainDb.toDouble(), band.q.toDouble())
                        }
                    }
                }
                points += Offset(xFor(hz), yFor(db.coerceIn(-maxDb, maxDb)))
            }

            val line = Path().apply {
                points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(size.width, yFor(0.0))
                lineTo(0f, yFor(0.0))
                close()
            }
            drawPath(
                fill,
                Brush.verticalGradient(
                    listOf(curveColor.copy(alpha = 0.28f), curveColor.copy(alpha = 0.02f))
                ),
            )
            drawPath(line, curveColor, style = Stroke(width = 3.5f))
        }
    }
}

private const val CURVE_SAMPLE_RATE = 48000.0

private enum class BiquadType { Peaking, LowShelf, HighShelf, HighPass }

/** Exact magnitude of the RBJ biquads the native TPT filters are equivalent to. */
private fun biquadDb(
    type: BiquadType,
    centreHz: Double,
    gainDb: Double,
    q: Double,
    hz: Double,
): Double {
    val a = 10.0.pow(gainDb / 40.0)
    val w0 = 2.0 * PI * centreHz / CURVE_SAMPLE_RATE
    val cosW0 = cos(w0)
    val alpha = sin(w0) / (2.0 * q)
    val sqrtA = sqrt(a)

    val b0: Double; val b1: Double; val b2: Double
    val a0: Double; val a1: Double; val a2: Double

    when (type) {
        BiquadType.Peaking -> {
            b0 = 1.0 + alpha * a; b1 = -2.0 * cosW0; b2 = 1.0 - alpha * a
            a0 = 1.0 + alpha / a; a1 = -2.0 * cosW0; a2 = 1.0 - alpha / a
        }
        BiquadType.LowShelf -> {
            b0 = a * ((a + 1) - (a - 1) * cosW0 + 2 * sqrtA * alpha)
            b1 = 2 * a * ((a - 1) - (a + 1) * cosW0)
            b2 = a * ((a + 1) - (a - 1) * cosW0 - 2 * sqrtA * alpha)
            a0 = (a + 1) + (a - 1) * cosW0 + 2 * sqrtA * alpha
            a1 = -2 * ((a - 1) + (a + 1) * cosW0)
            a2 = (a + 1) + (a - 1) * cosW0 - 2 * sqrtA * alpha
        }
        BiquadType.HighShelf -> {
            b0 = a * ((a + 1) + (a - 1) * cosW0 + 2 * sqrtA * alpha)
            b1 = -2 * a * ((a - 1) + (a + 1) * cosW0)
            b2 = a * ((a + 1) + (a - 1) * cosW0 - 2 * sqrtA * alpha)
            a0 = (a + 1) - (a - 1) * cosW0 + 2 * sqrtA * alpha
            a1 = 2 * ((a - 1) - (a + 1) * cosW0)
            a2 = (a + 1) - (a - 1) * cosW0 - 2 * sqrtA * alpha
        }
        BiquadType.HighPass -> {
            b0 = (1 + cosW0) / 2; b1 = -(1 + cosW0); b2 = (1 + cosW0) / 2
            a0 = 1 + alpha; a1 = -2 * cosW0; a2 = 1 - alpha
        }
    }

    val w = 2.0 * PI * hz / CURVE_SAMPLE_RATE
    val c1 = cos(-w); val s1 = sin(-w)
    val c2 = cos(-2 * w); val s2 = sin(-2 * w)

    val numRe = b0 + b1 * c1 + b2 * c2
    val numIm = b1 * s1 + b2 * s2
    val denRe = a0 + a1 * c1 + a2 * c2
    val denIm = a1 * s1 + a2 * s2

    val num = sqrt(numRe * numRe + numIm * numIm)
    val den = sqrt(denRe * denRe + denIm * denIm).coerceAtLeast(1e-12)
    return 20.0 * log10((num / den).coerceAtLeast(1e-6))
}

/** Two Butterworth sections, matching kButterworthQ in DspChain.cpp. */
private fun highPassDb(hz: Double, cornerHz: Double): Double =
    biquadDb(BiquadType.HighPass, cornerHz, 0.0, 0.5412, hz) +
        biquadDb(BiquadType.HighPass, cornerHz, 0.0, 1.3066, hz)

private fun bandDb(index: Int, hz: Double, centreHz: Double, gainDb: Double, q: Double): Double {
    val type = when (index) {
        0 -> BiquadType.LowShelf
        4 -> BiquadType.HighShelf
        else -> BiquadType.Peaking
    }
    return biquadDb(type, centreHz, gainDb, q, hz)
}
