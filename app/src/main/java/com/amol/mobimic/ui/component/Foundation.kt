package com.amol.mobimic.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amol.mobimic.ui.theme.MonoNumeric
import com.amol.mobimic.ui.theme.semantic

/**
 * The inset grouped list.
 *
 * One container, one caption above it, hairlines between rows rather than around
 * them. It is a well-worn pattern because it works: the card edge groups, and the
 * hairline separates, so no row needs a border of its own and the screen stays
 * quiet even when it is dense with numbers.
 */
@Composable
fun Section(
    title: String? = null,
    footnote: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScopeRows.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = semantic.labelSecondary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(semantic.groupedSurface)
        ) {
            ColumnScopeRows().content()
        }
        if (footnote != null) {
            Text(
                text = footnote,
                style = MaterialTheme.typography.bodySmall,
                color = semantic.labelTertiary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
            )
        }
    }
}

/**
 * Receiver scope for section content.
 *
 * Its only job is to let a row know it is inside a section, so [Row] separators can
 * be drawn by the rows themselves and callers never hand-place dividers.
 */
class ColumnScopeRows

/** A hairline, inset from the leading edge the way a grouped list draws it. */
@Composable
fun ColumnScopeRows.RowDivider(inset: Boolean = true) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = if (inset) 16.dp else 0.dp)
            .height(0.5.dp)
            .background(semantic.separator)
    )
}

/**
 * Label on the left, value on the right. The workhorse.
 *
 * [valueColor] is how state is shown: an unremarkable value stays in the secondary
 * grey, and only something worth noticing takes colour.
 */
@Composable
fun ColumnScopeRows.ValueRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    mono: Boolean = false,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.labelTertiary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = if (mono) MonoNumeric else MaterialTheme.typography.bodyLarge,
            color = if (valueColor == Color.Unspecified) semantic.labelSecondary else valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A row whose right side is a switch. */
@Composable
fun ColumnScopeRows.SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    detail: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.labelTertiary,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
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

/** Free-form content inside a section, with the section's own padding. */
@Composable
fun ColumnScopeRows.ContentRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
}
