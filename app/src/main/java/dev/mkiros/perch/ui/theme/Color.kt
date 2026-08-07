package dev.mkiros.perch.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The fallback colour scheme for API 26–30, where `dynamicColorScheme` does not exist
 * (DESIGN.md §2). Tonal palettes generated from the seed **`#3F6E5A`** — a muted forest
 * green — at the standard Material 3 tone stops, then assigned to roles with the
 * baseline M3 mapping. Chroma per palette follows M3: primary keeps the seed hue at
 * full chroma, secondary a third of it, tertiary is hue+60 at half, and the two
 * neutrals are near-grey with just enough green to read as one family.
 *
 * These raw tones are private on purpose: nothing outside this file may name a tone.
 * Screens address colour through `MaterialTheme.colorScheme` roles only, so the dynamic
 * and fallback schemes stay interchangeable.
 */
private val Primary10 = Color(0xFF002115)
private val Primary20 = Color(0xFF003826)
private val Primary30 = Color(0xFF005139)
private val Primary40 = Color(0xFF006C4D)
private val Primary80 = Color(0xFF71DAAF)
private val Primary90 = Color(0xFF8EF7CB)
private val Primary100 = Color(0xFFFFFFFF)

private val Secondary10 = Color(0xFF121E19)
private val Secondary20 = Color(0xFF26332D)
private val Secondary30 = Color(0xFF3C4A43)
private val Secondary40 = Color(0xFF53625B)
private val Secondary80 = Color(0xFFBACAC2)
private val Secondary90 = Color(0xFFD6E6DE)
private val Secondary100 = Color(0xFFFFFFFF)

private val Tertiary10 = Color(0xFF021F25)
private val Tertiary20 = Color(0xFF19343B)
private val Tertiary30 = Color(0xFF304B52)
private val Tertiary40 = Color(0xFF47636A)
private val Tertiary80 = Color(0xFFAECBD4)
private val Tertiary90 = Color(0xFFCAE8F0)
private val Tertiary100 = Color(0xFFFFFFFF)

private val Neutral00 = Color(0xFF000000)
private val Neutral04 = Color(0xFF06100C)
private val Neutral06 = Color(0xFF0D1511)
private val Neutral10 = Color(0xFF161D1A)
private val Neutral12 = Color(0xFF1A211E)
private val Neutral17 = Color(0xFF252B28)
private val Neutral20 = Color(0xFF2B322F)
private val Neutral22 = Color(0xFF2F3633)
private val Neutral24 = Color(0xFF343B37)
private val Neutral87 = Color(0xFFD3DCD8)
private val Neutral90 = Color(0xFFDBE5E0)
private val Neutral92 = Color(0xFFE1EAE6)
private val Neutral94 = Color(0xFFE7F0EB)
private val Neutral95 = Color(0xFFEAF3EE)
private val Neutral96 = Color(0xFFEDF6F1)
private val Neutral98 = Color(0xFFF2FBF7)
private val Neutral100 = Color(0xFFFFFFFF)

private val NeutralVariant30 = Color(0xFF3B4A43)
private val NeutralVariant50 = Color(0xFF6A7B73)
private val NeutralVariant60 = Color(0xFF84958C)
private val NeutralVariant80 = Color(0xFFB9CBC2)
private val NeutralVariant90 = Color(0xFFD4E7DE)

// Error keeps the Material 3 baseline red. §2 reserves `error` for per-source failure
// only, and a seeded red would drift towards the green hue and read as a warning.
private val ErrorLight = Color(0xFFBA1A1A)
private val OnErrorLight = Color(0xFFFFFFFF)
private val ErrorContainerLight = Color(0xFFFFDAD6)
private val OnErrorContainerLight = Color(0xFF410002)
private val ErrorDark = Color(0xFFFFB4AB)
private val OnErrorDark = Color(0xFF690005)
private val ErrorContainerDark = Color(0xFF93000A)
private val OnErrorContainerDark = Color(0xFFFFDAD6)

internal val PerchLightColors: ColorScheme = lightColorScheme(
    primary = Primary40,
    onPrimary = Primary100,
    primaryContainer = Primary90,
    onPrimaryContainer = Primary10,
    inversePrimary = Primary80,
    secondary = Secondary40,
    onSecondary = Secondary100,
    secondaryContainer = Secondary90,
    onSecondaryContainer = Secondary10,
    tertiary = Tertiary40,
    onTertiary = Tertiary100,
    tertiaryContainer = Tertiary90,
    onTertiaryContainer = Tertiary10,
    background = Neutral98,
    onBackground = Neutral10,
    surface = Neutral98,
    onSurface = Neutral10,
    surfaceVariant = NeutralVariant90,
    onSurfaceVariant = NeutralVariant30,
    surfaceTint = Primary40,
    inverseSurface = Neutral20,
    inverseOnSurface = Neutral95,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    outline = NeutralVariant50,
    outlineVariant = NeutralVariant80,
    scrim = Neutral00,
    surfaceBright = Neutral98,
    surfaceDim = Neutral87,
    surfaceContainer = Neutral94,
    surfaceContainerHigh = Neutral92,
    surfaceContainerHighest = Neutral90,
    surfaceContainerLow = Neutral96,
    surfaceContainerLowest = Neutral100,
)

internal val PerchDarkColors: ColorScheme = darkColorScheme(
    primary = Primary80,
    onPrimary = Primary20,
    primaryContainer = Primary30,
    onPrimaryContainer = Primary90,
    inversePrimary = Primary40,
    secondary = Secondary80,
    onSecondary = Secondary20,
    secondaryContainer = Secondary30,
    onSecondaryContainer = Secondary90,
    tertiary = Tertiary80,
    onTertiary = Tertiary20,
    tertiaryContainer = Tertiary30,
    onTertiaryContainer = Tertiary90,
    background = Neutral06,
    onBackground = Neutral90,
    surface = Neutral06,
    onSurface = Neutral90,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,
    surfaceTint = Primary80,
    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    outline = NeutralVariant60,
    outlineVariant = NeutralVariant30,
    scrim = Neutral00,
    surfaceBright = Neutral24,
    surfaceDim = Neutral06,
    surfaceContainer = Neutral12,
    surfaceContainerHigh = Neutral17,
    surfaceContainerHighest = Neutral22,
    surfaceContainerLow = Neutral10,
    surfaceContainerLowest = Neutral04,
)
