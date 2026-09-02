package com.amol.mobimic.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.amol.mobimic.ui.theme.MonoNumeric
import com.amol.mobimic.ui.theme.semantic
import androidx.compose.foundation.clickable

/**
 * The transport control.
 *
 * A single large target, because it is the only thing on the screen you touch in a
 * hurry. Idle it is a filled circle; live it becomes a rounded square inside a
 * breathing ring - the shape change reads instantly even in peripheral vision,
 * which a colour change alone does not.
 *
 * Haptics on press: this is a control with real consequences, and a physical
 * confirmation is worth more than an animation.
 */
@Composable
fun TransportButton(
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "press",
    )
    val innerRadius by animateFloatAsState(
        targetValue = if (running) 0.22f else 0.5f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 420f),
        label = "shape",
    )
    val innerScale by animateFloatAsState(
        targetValue = if (running) 0.46f else 0.86f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 420f),
        label = "size",
    )
    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> semantic.labelTertiary
            running -> semantic.live
            else -> semantic.good
        },
        animationSpec = tween(280),
        label = "tint",
    )

    // A slow pulse on the ring while live. Subtle enough to ignore, present enough
    // to answer "is it still running?" without reading anything.
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ringAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = if (running) 0.85f else 0.35f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "ring",
    )

    Box(
        modifier
            .size(84.dp)
            .scale(scale)
            .clip(CircleShape)
            .border(BorderStroke(2.dp, tint.copy(alpha = if (running) ringAlpha else 0.45f)), CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size((84 * innerScale).dp)
                .clip(RoundedCornerShape((84 * innerScale * innerRadius).dp))
                .background(tint)
        )
    }
}

/** A small state pill: a dot and a word. Used for anything binary and glanceable. */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/**
 * Segmented control.
 *
 * Preferred over a row of chips for mutually exclusive choices: the shared track
 * makes it obvious that exactly one is selected, and it takes one line instead of
 * wrapping unpredictably.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(semantic.canvas)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val background by animateColorAsState(
                targetValue = if (isSelected) semantic.fill else Color.Transparent,
                animationSpec = tween(180),
                label = "seg",
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(background)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        if (!isSelected) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(option)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else semantic.labelSecondary,
                )
            }
        }
    }
}

/**
 * A labelled slider, drawn rather than themed.
 *
 * Material's stock slider now renders a tall bar thumb and a "stop indicator" dot
 * at the track end. Both are deliberate choices in that design language and both
 * look like rendering faults next to everything else here, and no colour override
 * removes them - so the control is drawn directly. It is about forty lines, and it
 * buys a thumb that looks like a thumb.
 *
 * The value sits above the track rather than in a floating bubble, so it stays
 * readable while your thumb is on it.
 */
@Composable
fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String = "",
    decimals: Int = 1,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
) {
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)

    val trackColor = semantic.fill
    val activeColor = if (enabled) MaterialTheme.colorScheme.primary else semantic.labelTertiary
    val thumbColor = if (enabled) Color.White else semantic.labelSecondary

    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { 11.dp.toPx() }

    fun emit(x: Float) {
        if (widthPx <= 0) return
        val usable = (widthPx - 2 * thumbRadiusPx).coerceAtLeast(1f)
        val f = ((x - thumbRadiusPx) / usable).coerceIn(0f, 1f)
        onValueChange(range.start + f * span)
    }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else semantic.labelTertiary,
            )
            Text(
                "%.${decimals}f$unit".format(value),
                style = MonoNumeric,
                color = if (enabled) semantic.labelSecondary else semantic.labelTertiary,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .onSizeChanged { widthPx = it.width }
                .pointerInput(enabled, range) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset -> emit(offset.x) }
                }
                .pointerInput(enabled, range) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        emit(change.position.x)
                    }
                }
        ) {
            Canvas(Modifier.fillMaxWidth().height(36.dp)) {
                val centreY = size.height / 2f
                val trackHeight = 5.dp.toPx()
                val left = thumbRadiusPx
                val right = size.width - thumbRadiusPx
                val thumbX = left + (right - left) * fraction

                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(left, centreY - trackHeight / 2f),
                    size = Size((right - left).coerceAtLeast(0f), trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f),
                )
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(left, centreY - trackHeight / 2f),
                    size = Size((thumbX - left).coerceAtLeast(0f), trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f),
                )
                // A soft shadow lifts the thumb off the track; without it the control
                // reads as flat and the grab target is ambiguous.
                drawCircle(
                    color = Color.Black.copy(alpha = 0.35f),
                    radius = thumbRadiusPx,
                    center = Offset(thumbX, centreY + 1.5f),
                )
                drawCircle(color = thumbColor, radius = thumbRadiusPx, center = Offset(thumbX, centreY))
            }
        }
    }
}
