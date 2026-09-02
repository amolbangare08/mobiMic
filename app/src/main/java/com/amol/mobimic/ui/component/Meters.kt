package com.amol.mobimic.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.amol.mobimic.ui.theme.MeterHigh
import com.amol.mobimic.ui.theme.MeterLow
import com.amol.mobimic.ui.theme.MeterMid
import com.amol.mobimic.ui.theme.MonoNumeric
import com.amol.mobimic.ui.theme.semantic
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

fun toDb(linear: Float): Float = if (linear <= 0.0000001f) -120f else 20f * log10(linear)

/** Maps dBFS onto 0..1 with -60 dBFS at the left edge. */
fun dbToFraction(db: Float): Float = max(0f, (db + 60f) / 60f)

/**
 * The main level meter.
 *
 * Two things make a meter feel like instrumentation rather than a progress bar: the
 * bar rises instantly but falls slowly, the way physical ballistics behave, and a
 * peak marker holds briefly at the highest recent value. Without the hold you
 * cannot see a transient at all - it is gone before the eye registers it.
 */
@Composable
fun LevelMeter(
    rmsDb: Float,
    peakDb: Float,
    modifier: Modifier = Modifier,
    height: Int = 12,
) {
    val rmsTarget = dbToFraction(rmsDb).coerceIn(0f, 1f)
    val peakTarget = dbToFraction(peakDb).coerceIn(0f, 1f)

    // Fast attack, slow release. animateFloatAsState with a stiff spring rising and
    // a longer tween falling gives the asymmetry without any custom animation clock.
    val rms by animateFloatAsState(
        targetValue = rmsTarget,
        animationSpec = spring(dampingRatio = 1f, stiffness = 900f),
        label = "rms",
    )
    val peak by animateFloatAsState(
        targetValue = peakTarget,
        animationSpec = tween(durationMillis = if (peakTarget > 0.02f) 60 else 700),
        label = "peak",
    )

    val gradient = Brush.horizontalGradient(
        0.0f to MeterLow,
        0.72f to MeterLow,
        0.88f to MeterMid,
        1.0f to MeterHigh,
    )

    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(height.dp / 2))
            .background(semantic.fill)
    ) {
        Box(
            Modifier
                .fillMaxWidth(rms)
                .fillMaxHeight()
                .background(gradient)
        )
        // Peak marker: a thin bright tick, not a second bar, so it never competes
        // with the level itself.
        if (peak > 0.01f) {
            Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
                val x = (size.width * peak).coerceIn(1.5f, size.width - 1.5f)
                drawRect(
                    color = if (peakDb > -1f) MeterHigh else Color.White.copy(alpha = 0.9f),
                    topLeft = Offset(x - 1.5f, 0f),
                    size = Size(3f, size.height),
                )
            }
        }
    }
}

/**
 * Gain reduction, drawn right to left.
 *
 * Compressors are conventionally metered this way: the bar grows leftward from
 * zero as the processor works harder, so an idle chain shows nothing at all. That
 * absence is the useful signal - anything visible means something is acting.
 */
@Composable
fun GainReductionMeter(
    label: String,
    reductionDb: Float,
    maxDb: Float = 24f,
    modifier: Modifier = Modifier,
) {
    val magnitude = abs(reductionDb).coerceIn(0f, maxDb)
    val fraction by animateFloatAsState(
        targetValue = magnitude / maxDb,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 700f),
        label = "gr",
    )
    val active = magnitude > 0.05f

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) MaterialTheme.colorScheme.onSurface else semantic.labelTertiary,
            )
            Text(
                if (active) "−%.1f dB".format(magnitude) else "—",
                style = MonoNumeric,
                color = if (active) semantic.warn else semantic.labelTertiary,
            )
        }
        Box(
            Modifier
                .padding(top = 5.dp)
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(semantic.fill)
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(semantic.warn)
            )
        }
    }
}
