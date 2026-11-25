package polaris.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import polaris.android.R
import polaris.engine.RGB
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val LocalPolaris = staticCompositionLocalOf { PolarisColors.day }

/** The palette of the current appearance. */
val polaris: PolarisColors
    @Composable @ReadOnlyComposable get() = LocalPolaris.current

/**
 * Material 3 in the atlas's colours (no dynamic colour: the palette is the identity), with the
 * serif wordmark style beside the system's sans for everything else. Light and dark follow the
 * system.
 */
@Composable
fun PolarisTheme(night: Boolean, content: @Composable () -> Unit) {
    // The copy is English: in a right-to-left locale the layout mirrors, but a line like
    // "6 routes" or "12:00 – 12:30" keeps its own left-to-right order.
    // Each line still sits on the layout's own side (right, in a right-to-left locale).
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val side = if (rtl) androidx.compose.ui.text.style.TextAlign.Right else androidx.compose.ui.text.style.TextAlign.Left
    fun TextStyle.ltr() = copy(textDirection = TextDirection.ContentOrLtr, textAlign = side)
    val base = Typography()
    val typography = Typography(
        displayLarge = base.displayLarge.ltr(), displayMedium = base.displayMedium.ltr(), displaySmall = base.displaySmall.ltr(),
        headlineLarge = base.headlineLarge.ltr(), headlineMedium = base.headlineMedium.ltr(), headlineSmall = base.headlineSmall.ltr(),
        titleLarge = base.titleLarge.ltr().copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.ltr().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.ltr().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.ltr(), bodyMedium = base.bodyMedium.ltr(), bodySmall = base.bodySmall.ltr(),
        labelLarge = base.labelLarge.ltr(), labelMedium = base.labelMedium.ltr(), labelSmall = base.labelSmall.ltr(),
    )
    CompositionLocalProvider(LocalPolaris provides PolarisColors.of(night)) {
        MaterialTheme(colorScheme = PolarisColors.scheme(night), typography = typography, content = content)
    }
}

object PolarisType {
    /** The wordmark and place names: a serif, as an atlas's labels are. */
    val wordmark = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, textDirection = TextDirection.ContentOrLtr)
}

val SurfaceShape = RoundedCornerShape(18.dp)

/**
 * A grouped surface: white on the paper with a hairline edge, or a gold edge when highlighted
 * (the best route). Only around a group of rows or one distinct object, never nested.
 */
fun Modifier.surface(colors: PolarisColors, highlighted: Boolean = false, selected: Boolean = false): Modifier =
    this.clip(SurfaceShape)
        .background(colors.surface, SurfaceShape)
        .border(
            width = if (selected) 2.dp else if (highlighted) 1.dp else 0.75.dp,
            color = when {
                selected -> colors.tint
                highlighted -> colors.goldInk.copy(alpha = 0.7f)
                else -> colors.hairline
            },
            shape = SurfaceShape,
        )

/** Hours and minutes in the phone's own time zone, as iOS's `Date.clock`. */
object Clock {
    private val format = DateTimeFormatter.ofPattern("HH:mm")
    fun of(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = format.format(instant.atZone(zone))
}

/** "26 min", "1 h 05", as iOS's `minutesText`. */
@Composable
@ReadOnlyComposable
fun minutesText(seconds: Double): String {
    val minutes = Math.round(seconds / 60).toInt()
    return if (minutes < 60) stringResource(R.string.duration_minutes, minutes) else stringResource(R.string.duration_hours_minutes, minutes / 60, minutes % 60)
}

/** A list in words: "a", "a and b", "a, b and c" (or "or"); [joiner] is the word before the last, [separator] the one between the others. */
fun spokenList(items: List<String>, joiner: String, separator: String = ", "): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    else -> items.dropLast(1).joinToString(separator) + " $joiner " + items.last()
}

/**
 * Between the parts of a detail line ("12:09 · platform 2 · 1 change"): no-break spaces on both
 * sides, so a line never starts or ends with the dot; lines break only at spaces inside the parts.
 */
const val SEPARATOR = "\u00A0·\u00A0"

fun RGB.toColor() = androidx.compose.ui.graphics.Color(r.toFloat(), g.toFloat(), b.toFloat())
