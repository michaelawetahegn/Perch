package dev.mkiros.perch.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * How the reader wants the app to look. Persisted by Settings (T27); [System] follows
 * the platform setting, which is the default because §2 assumes night reading.
 */
enum class ThemeMode { System, Light, Dark }

/**
 * Corners per DESIGN.md §4: `medium` (12dp) for sheets, dialogs, and code blocks.
 * The rest of the M3 defaults already match, but stating them keeps the values in one
 * place rather than implied by the library version.
 */
private val PerchShapes = Shapes(
    extraSmall = RoundedCornerShape(Dimens.xs),
    small = RoundedCornerShape(Dimens.sm),
    medium = RoundedCornerShape(Dimens.md),
    large = RoundedCornerShape(Dimens.lg),
    extraLarge = RoundedCornerShape(Dimens.xl),
)

/**
 * The single theme wrapper for every Perch screen.
 *
 * Colour follows §2: Material 3 dynamic colour on API 31+, and below that the
 * hand-built `#3F6E5A`-seeded scheme in [PerchLightColors] / [PerchDarkColors].
 * [dynamicColor] exists so screenshot tests (T29) can pin the fallback scheme and get
 * the same pixels on any device.
 */
@Composable
fun PerchTheme(
    mode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val supportsDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        supportsDynamic -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        dark -> PerchDarkColors
        else -> PerchLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PerchTypography,
        shapes = PerchShapes,
        content = content,
    )
}
