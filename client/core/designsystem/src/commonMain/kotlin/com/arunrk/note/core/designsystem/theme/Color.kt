package com.arunrk.note.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * A calm, paper-like palette. Notes are read for long stretches, so the surface
 * carries a slight warmth rather than pure white, and the accent is reserved for
 * genuinely interactive elements.
 */

private val Ink = Color(0xFF1A1C1E)
private val Paper = Color(0xFFFDFCF7)
private val PaperDim = Color(0xFFF4F1E8)

private val Indigo = Color(0xFF4A5DC7)
private val IndigoLight = Color(0xFFB9C3FF)
private val IndigoContainer = Color(0xFFDEE1FF)

private val Amber = Color(0xFF7A5900)
private val AmberContainer = Color(0xFFFFDEA6)

private val Crimson = Color(0xFFBA1A1A)
private val CrimsonContainer = Color(0xFFFFDAD6)

val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = IndigoContainer,
    onPrimaryContainer = Color(0xFF00105C),

    secondary = Color(0xFF5B5D72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E1F9),
    onSecondaryContainer = Color(0xFF181A2C),

    tertiary = Amber,
    onTertiary = Color.White,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = Color(0xFF261A00),

    error = Crimson,
    onError = Color.White,
    errorContainer = CrimsonContainer,
    onErrorContainer = Color(0xFF410002),

    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperDim,
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC6C6D0),
)

val DarkColors = darkColorScheme(
    primary = IndigoLight,
    onPrimary = Color(0xFF1B2C8F),
    primaryContainer = Color(0xFF3243AE),
    onPrimaryContainer = IndigoContainer,

    secondary = Color(0xFFC4C5DD),
    onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF434559),
    onSecondaryContainer = Color(0xFFE0E1F9),

    tertiary = Color(0xFFF0C048),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF5B4300),
    onTertiaryContainer = AmberContainer,

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = CrimsonContainer,

    background = Color(0xFF1A1C1E),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C6D0),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF45464F),
)

/**
 * Colours for the five sync states.
 *
 * Never signalled by colour alone - every badge pairs these with a distinct
 * icon and a text label, because roughly one in twelve men has some form of
 * colour vision deficiency and "amber vs green dot" is exactly the distinction
 * they lose.
 */
data class SyncStatusColors(
    val synced: Color,
    val syncing: Color,
    val pending: Color,
    val failed: Color,
    val conflict: Color,
)

val LightSyncStatusColors = SyncStatusColors(
    synced = Color(0xFF3B7A57),
    syncing = Indigo,
    pending = Color(0xFF7A5900),
    failed = Crimson,
    conflict = Color(0xFF8A4B00),
)

val DarkSyncStatusColors = SyncStatusColors(
    synced = Color(0xFF7ED9A6),
    syncing = IndigoLight,
    pending = Color(0xFFF0C048),
    failed = Color(0xFFFFB4AB),
    conflict = Color(0xFFFFB77C),
)
