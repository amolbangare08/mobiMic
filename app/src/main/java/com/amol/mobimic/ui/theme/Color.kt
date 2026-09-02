package com.amol.mobimic.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * A deliberately small palette.
 *
 * One accent for anything interactive, one for "live", and otherwise greys. Colour
 * is reserved for meaning here: if a row is coloured, something is wrong or
 * something is running. That restraint is most of what makes an interface feel
 * calm rather than busy.
 *
 * Values follow the iOS system palette, which is tuned for exactly this - readable
 * against both pure black and near-white, at small sizes.
 */

// Dark - the primary look. True black, because the target phones are OLED and it
// makes the meters read as light emitted rather than pixels lit.
val DarkCanvas = Color(0xFF000000)
val DarkSurface = Color(0xFF1C1C1E)
val DarkSurfaceRaised = Color(0xFF2C2C2E)
val DarkSeparator = Color(0xFF38383A)
val DarkLabel = Color(0xFFFFFFFF)
val DarkLabelSecondary = Color(0x99EBEBF5)
val DarkLabelTertiary = Color(0x4DEBEBF5)
val DarkFill = Color(0x5C787880)

// Light
val LightCanvas = Color(0xFFF2F2F7)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceRaised = Color(0xFFF2F2F7)
val LightSeparator = Color(0xFFD1D1D6)
val LightLabel = Color(0xFF000000)
val LightLabelSecondary = Color(0x993C3C43)
val LightLabelTertiary = Color(0x4D3C3C43)
val LightFill = Color(0x1F787880)

// Accents. Blue is "you can touch this"; red means audio is live, and is used for
// nothing else, so a glance at the screen answers the only urgent question.
val AccentBlueDark = Color(0xFF0A84FF)
val AccentBlueLight = Color(0xFF007AFF)
val LiveRedDark = Color(0xFFFF453A)
val LiveRedLight = Color(0xFFFF3B30)

val SignalGreenDark = Color(0xFF30D158)
val SignalGreenLight = Color(0xFF34C759)
val WarnAmberDark = Color(0xFFFF9F0A)
val WarnAmberLight = Color(0xFFFF9500)

// Meter gradient: green through amber to red, so level is legible peripherally
// without reading the number.
val MeterLow = Color(0xFF30D158)
val MeterMid = Color(0xFFFFD60A)
val MeterHigh = Color(0xFFFF453A)
