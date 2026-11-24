package polaris.android.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The star-atlas palette from Polaris/Design/Theme.swift: cool chart paper, navy ink, chart-blue
 * for anything you can tap, and one gold star. At night: night-sky paper and pale ink, the gold
 * unchanged. Only what the plumbing needs so far; the screens will add the rest.
 */
data class PolarisColors(
    val paper: Color,
    val surface: Color,
    val ink: Color,
    val tint: Color,
    val goldInk: Color,
    val hairline: Color,
    val here: Color,
    val banner: Color,
    val casing: Color,
) {
    val navy get() = Navy
    val gold get() = Gold

    companion object {
        val Navy = Color(0xFF0D1530)
        val Gold = Color(0xFFF2B82E)

        val day = PolarisColors(
            paper = Color(0xFFF5F6FA), surface = Color.White, ink = Navy, tint = Color(0xFF2554C7),
            goldInk = Color(0xFF9A6A00), hairline = Color(0x1A0D1530), here = Color(0xFF2F6FEB),
            banner = Navy, casing = Color.White,
        )
        val night = PolarisColors(
            paper = Color(0xFF0B0F1C), surface = Color(0xFF171C2C), ink = Color(0xFFE9EDF6), tint = Color(0xFF7AA2FF),
            goldInk = Color(0xFFF2C14E), hairline = Color(0x1FFFFFFF), here = Color(0xFF5A93FF),
            banner = Color(0xFF232C4A), casing = Color(0xFF0B0F1C),
        )

        fun of(night: Boolean) = if (night) this.night else day

        /** Material's roles in the atlas's colours, so system components sit in the same palette. */
        fun scheme(night: Boolean): ColorScheme {
            val c = of(night)
            return if (night) darkColorScheme(
                primary = c.tint, onPrimary = Navy, background = c.paper, onBackground = c.ink,
                surface = c.paper, onSurface = c.ink, surfaceContainer = c.surface, surfaceContainerHigh = c.surface,
                // Field edges need 3:1 on the surface; the hairline is for dividers only.
                outline = Color(0xFF7F8BA8), outlineVariant = c.hairline, secondary = c.goldInk,
                secondaryContainer = Color(0xFF23305A), onSecondaryContainer = c.ink,
            ) else lightColorScheme(
                primary = c.tint, onPrimary = Color.White, background = c.paper, onBackground = c.ink,
                surface = c.paper, onSurface = c.ink, surfaceContainer = c.surface, surfaceContainerHigh = c.surface,
                outline = Color(0xFF6E7791), outlineVariant = c.hairline, secondary = c.goldInk,
                secondaryContainer = Color(0xFFDCE5F8), onSecondaryContainer = Navy,
            )
        }
    }
}
