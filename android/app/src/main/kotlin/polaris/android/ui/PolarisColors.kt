package polaris.android.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The star-atlas palette from Polaris/Design/Theme.swift: cool chart paper, navy ink, chart-blue
 * for anything you can tap, and one gold star. At night: night-sky paper and pale ink, the gold
 * unchanged. Text on gold or on a pale line colour is navy in both.
 */
data class PolarisColors(
    val paper: Color,
    val surface: Color,
    val ink: Color,
    /** Secondary text: ink, quieter. */
    val inkSoft: Color,
    /** Tertiary text and chevrons. */
    val inkFaint: Color,
    val tint: Color,
    val goldInk: Color,
    val hairline: Color,
    val here: Color,
    val banner: Color,
    val casing: Color,
    /** Used only for what it means: something will take longer or won't work. */
    val warning: Color,
    val live: Color,
    val isNight: Boolean,
) {
    val navy get() = Navy
    val gold get() = Gold

    companion object {
        val Navy = Color(0xFF0D1530)
        val Gold = Color(0xFFF2B82E)

        val day = PolarisColors(
            paper = Color(0xFFF5F6FA), surface = Color.White, ink = Navy,
            // On white and on the paper: soft 7.5 and 7.0 to 1, faint 5.5 and 5.1 to 1 (text needs 4.5).
            inkSoft = Color(0xFF4A5470), inkFaint = Color(0xFF5F6882),
            tint = Color(0xFF2554C7),
            goldInk = Color(0xFF9A6A00), hairline = Color(0x1A0D1530), here = Color(0xFF2F6FEB),
            banner = Navy, casing = Color.White,
            warning = Color(0xFFC2410C), live = Color(0xFF167A43), isNight = false,
        )
        val night = PolarisColors(
            paper = Color(0xFF0B0F1C), surface = Color(0xFF171C2C), ink = Color(0xFFE9EDF6),
            inkSoft = Color(0xFFB4BCD0), inkFaint = Color(0xFF8E97AE),
            tint = Color(0xFF7AA2FF),
            goldInk = Color(0xFFF2C14E), hairline = Color(0x1FFFFFFF), here = Color(0xFF5A93FF),
            banner = Color(0xFF232C4A), casing = Color(0xFF0B0F1C),
            warning = Color(0xFFFF8F5A), live = Color(0xFF4FD18B), isNight = true,
        )

        fun of(night: Boolean) = if (night) this.night else day

        /** Material's roles in the atlas's colours, so system components sit in the same palette. */
        fun scheme(night: Boolean): ColorScheme {
            val c = of(night)
            return if (night) darkColorScheme(
                primary = c.tint, onPrimary = Navy, background = c.paper, onBackground = c.ink,
                surface = c.paper, onSurface = c.ink, onSurfaceVariant = c.inkSoft,
                surfaceContainerLowest = c.paper, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
                surfaceContainerHigh = c.surface, surfaceContainerHighest = Color(0xFF222842),
                surfaceVariant = Color(0xFF222842),
                // Field edges need 3:1 on the surface; the hairline is for dividers only.
                outline = Color(0xFF7F8BA8), outlineVariant = c.hairline, secondary = c.goldInk,
                secondaryContainer = Color(0xFF23305A), onSecondaryContainer = c.ink,
                primaryContainer = Color(0xFF23305A), onPrimaryContainer = c.ink,
                tertiaryContainer = Color(0xFF23305A), onTertiaryContainer = c.ink,
                tertiary = c.goldInk, error = c.warning, onError = Navy,
                inverseSurface = c.ink, inverseOnSurface = c.paper,
            ) else lightColorScheme(
                primary = c.tint, onPrimary = Color.White, background = c.paper, onBackground = c.ink,
                surface = c.paper, onSurface = c.ink, onSurfaceVariant = c.inkSoft,
                surfaceContainerLowest = Color.White, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
                surfaceContainerHigh = c.surface, surfaceContainerHighest = Color(0xFFE9ECF4),
                surfaceVariant = Color(0xFFE9ECF4),
                outline = Color(0xFF6E7791), outlineVariant = c.hairline, secondary = c.goldInk,
                secondaryContainer = Color(0xFFDCE5F8), onSecondaryContainer = Navy,
                // The time picker's selected field and similar: chart-blue tints, never Material's purple.
                primaryContainer = Color(0xFFDCE5F8), onPrimaryContainer = Navy,
                tertiaryContainer = Color(0xFFDCE5F8), onTertiaryContainer = Navy,
                tertiary = c.goldInk, error = c.warning, onError = Color.White,
                inverseSurface = Navy, inverseOnSurface = c.paper,
            )
        }
    }
}
