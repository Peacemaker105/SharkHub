package com.chris.sharkhub.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Theme system for Shark Hub.
 *
 * A theme is just a [ThemeSpec] — a named palette. The whole app is driven from Material's
 * colorScheme, mapped like this:
 *   accent  -> primary        warn -> secondary      hot -> error       cool -> tertiary
 *   bg      -> background      surface -> surface     surfaceVariant -> surfaceVariant
 *   ink     -> onSurface/onBackground                 dim  -> onSurfaceVariant
 *   hairline borders -> outlineVariant (ink at low alpha)
 *
 * So every screen already follows the selected theme with no per-screen code.
 *
 * To add a theme (e.g. when reworking visuals with Fable): add one ThemeSpec to [Themes.all].
 * Keep the originals below so you always have them to fall back to.
 */
data class ThemeSpec(
    val id: String,
    val name: String,
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val ink: Color,
    val dim: Color,
    val accent: Color,
    val onAccent: Color,
    val warn: Color,
    val hot: Color,
    /** Cooling / seat ventilation on the climate screen (hot doubles as heating). */
    val cool: Color = Color(0xFF5AC8FA),
)

object Themes {
    // --- DEFAULT: matches the launcher icon (design/icon) — navy water, electric-blue fin ---
    val DEEP_SEA = ThemeSpec(
        id = "deep_sea", name = "Deep Sea", dark = true,
        bg = Color(0xFF040A16), surface = Color(0xFF0A1322), surfaceVariant = Color(0xFF101C31),
        ink = Color(0xFFEAF3FF), dim = Color(0xFF8DA1BF),
        accent = Color(0xFF2EB8FF), onAccent = Color(0xFF00121F),
        warn = Color(0xFFFFB547), hot = Color(0xFFFF6B5E), cool = Color(0xFF7FE3FF),
    )

    // --- ORIGINALS (do not delete; these were the shipped defaults) ---
    val VN_MINT = ThemeSpec(
        id = "vn_mint", name = "VN Mint", dark = true,
        bg = Color(0xFF0B0D10), surface = Color(0xFF14171C), surfaceVariant = Color(0xFF1D2128),
        ink = Color(0xFFF2F4F8), dim = Color(0xFF9AA3B2),
        accent = Color(0xFF3DDC97), onAccent = Color(0xFF07120C),
        warn = Color(0xFFFFB454), hot = Color(0xFFFF5C5C),
    )

    // --- STARTER EXTRAS (edit / add more freely) ---
    val SLATE_BLUE = ThemeSpec(
        id = "slate_blue", name = "Slate Blue", dark = true,
        bg = Color(0xFF0A0E14), surface = Color(0xFF141A24), surfaceVariant = Color(0xFF1C2430),
        ink = Color(0xFFEAF0F8), dim = Color(0xFF93A0B4),
        accent = Color(0xFF4C8DFF), onAccent = Color(0xFF04101F),
        warn = Color(0xFFFFC65C), hot = Color(0xFFFF6B6B), cool = Color(0xFF62D6FF),
    )
    val AMBER_HUD = ThemeSpec(
        id = "amber_hud", name = "Amber HUD", dark = true,
        bg = Color(0xFF0C0A07), surface = Color(0xFF17130D), surfaceVariant = Color(0xFF211B12),
        ink = Color(0xFFF6EFE2), dim = Color(0xFFB0A489),
        accent = Color(0xFFFFB43C), onAccent = Color(0xFF1A1300),
        warn = Color(0xFF6FE0B0), hot = Color(0xFFFF5C5C), cool = Color(0xFF6FD3FF),
    )
    val DAYLIGHT = ThemeSpec(
        id = "daylight", name = "Daylight", dark = false,
        bg = Color(0xFFF3F5F8), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFEAEEF3),
        ink = Color(0xFF12151A), dim = Color(0xFF5A6473),
        accent = Color(0xFF1B9E6B), onAccent = Color(0xFFFFFFFF),
        warn = Color(0xFFB5730B), hot = Color(0xFFC43D3D), cool = Color(0xFF1E7FD8),
    )

    val all: List<ThemeSpec> = listOf(DEEP_SEA, VN_MINT, SLATE_BLUE, AMBER_HUD, DAYLIGHT)

    fun byId(id: String?): ThemeSpec = all.firstOrNull { it.id == id } ?: DEEP_SEA
}

// System sans (Roboto on most units): light weights for the big numerals read as instrument-grade
// on a car screen; tabular figures stop digits jittering as values change.
private val Sans = FontFamily.SansSerif

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 76.sp,
        lineHeight = 80.sp, letterSpacing = (-2.5).sp, fontFeatureSettings = "tnum"),
    displayMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 54.sp,
        lineHeight = 60.sp, letterSpacing = (-1.5).sp, fontFeatureSettings = "tnum"),
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 36.sp,
        lineHeight = 42.sp, letterSpacing = (-0.8).sp, fontFeatureSettings = "tnum"),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp,
        lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
        lineHeight = 26.sp, fontFeatureSettings = "tnum"),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp,
        lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
        lineHeight = 18.sp, letterSpacing = 0.6.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp,
        lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
        lineHeight = 14.sp, letterSpacing = 1.6.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** The base typography with the style's numeral and label weights applied. */
private fun typographyFor(style: StyleSpec): Typography = AppTypography.copy(
    displayLarge = AppTypography.displayLarge.copy(fontWeight = style.numeralWeight),
    displayMedium = AppTypography.displayMedium.copy(fontWeight = style.numeralWeight),
    displaySmall = AppTypography.displaySmall.copy(
        fontWeight = if (style.numeralWeight == FontWeight.Light) FontWeight.Normal else style.numeralWeight),
    labelLarge = AppTypography.labelLarge.copy(fontWeight = style.labelWeight),
    labelSmall = AppTypography.labelSmall.copy(
        fontWeight = style.labelWeight, letterSpacing = (1.6f * style.labelTracking).sp),
)

@Composable
fun SharkHubTheme(spec: ThemeSpec, style: StyleSpec = Styles.GLASS, content: @Composable () -> Unit) {
    val hairline = spec.ink.copy(alpha = if (spec.dark) 0.09f else 0.12f)
    val raised = lerp(spec.surfaceVariant, spec.ink, if (spec.dark) 0.06f else 0.04f)
    val tonal = lerp(spec.surface, spec.accent, 0.22f)
    val scheme = if (spec.dark) {
        darkColorScheme(
            primary = spec.accent, onPrimary = spec.onAccent,
            primaryContainer = tonal, onPrimaryContainer = spec.ink,
            secondary = spec.warn, onSecondary = spec.onAccent,
            tertiary = spec.cool, onTertiary = spec.onAccent,
            error = spec.hot, onError = spec.onAccent,
            background = spec.bg, onBackground = spec.ink,
            surface = spec.surface, onSurface = spec.ink,
            surfaceVariant = spec.surfaceVariant, onSurfaceVariant = spec.dim,
            surfaceContainerLowest = spec.bg, surfaceContainerLow = spec.surface,
            surfaceContainer = spec.surface, surfaceContainerHigh = spec.surfaceVariant,
            surfaceContainerHighest = raised,
            outline = spec.dim.copy(alpha = 0.6f), outlineVariant = hairline,
        )
    } else {
        lightColorScheme(
            primary = spec.accent, onPrimary = spec.onAccent,
            primaryContainer = tonal, onPrimaryContainer = spec.ink,
            secondary = spec.warn, onSecondary = spec.onAccent,
            tertiary = spec.cool, onTertiary = spec.onAccent,
            error = spec.hot, onError = spec.onAccent,
            background = spec.bg, onBackground = spec.ink,
            surface = spec.surface, onSurface = spec.ink,
            surfaceVariant = spec.surfaceVariant, onSurfaceVariant = spec.dim,
            surfaceContainerLowest = spec.surface, surfaceContainerLow = spec.bg,
            surfaceContainer = spec.bg, surfaceContainerHigh = spec.surfaceVariant,
            surfaceContainerHighest = raised,
            outline = spec.dim.copy(alpha = 0.6f), outlineVariant = hairline,
        )
    }
    val shapes = AppShapes.copy(
        small = RoundedCornerShape(style.controlRadius * 0.7f),
        medium = RoundedCornerShape(style.controlRadius),
        large = RoundedCornerShape(style.panelRadius),
    )
    CompositionLocalProvider(LocalStyle provides style) {
        MaterialTheme(colorScheme = scheme, typography = typographyFor(style), shapes = shapes, content = content)
    }
}
