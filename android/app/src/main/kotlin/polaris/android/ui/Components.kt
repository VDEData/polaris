package polaris.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
import polaris.android.R
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import polaris.engine.JourneyMap
import polaris.engine.Leg
import polaris.engine.LegMode
import polaris.engine.ProviderID
import polaris.engine.RGB
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Polaris's mark: a north star whose four points each carry a smaller star, and theirs smaller
 * again (Theme.swift's `FractalStar`). While a search runs, each branch stands for one source
 * and fills in when that source answers. Decorative: hidden from TalkBack.
 */
@Composable
fun FractalStar(size: Dp, modifier: Modifier = Modifier, lit: List<Boolean> = listOf(true, true, true, true), color: Color = polaris.goldInk) {
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        val centre = Offset(this.size.width / 2, this.size.height / 2)
        val radius = this.size.height / 2 / 1.52f
        drawStar(centre, radius, 0, true, color)
        directions.forEachIndexed { index, d ->
            val tip = tip(d, radius)
            drawStar(centre + tip, radius * 0.38f, 1, lit.getOrElse(index) { true }, color)
        }
    }
}

private val directions = listOf(Offset(0f, -1f), Offset(1f, 0f), Offset(0f, 1f), Offset(-1f, 0f))

private fun tip(d: Offset, radius: Float): Offset {
    val reach = if (d.x == 0f) radius else radius * 0.72f
    return Offset(d.x * reach, d.y * reach)
}

private fun DrawScope.drawStar(centre: Offset, radius: Float, depth: Int, lit: Boolean, color: Color) {
    val path = northStar(centre, radius, radius * 2 * 0.045f)
    if (lit) drawPath(path, color) else drawPath(path, color.copy(alpha = 0.45f), style = Stroke(max(0.6f, radius * 0.08f)))
    if (depth >= 2) return
    for (d in directions) drawStar(centre + tip(d, radius), radius * 0.34f, depth + 1, lit, color)
}

/** A four-pointed star with long north and south rays (Theme.swift's `NorthStar`). */
private fun northStar(c: Offset, v: Float, waist: Float): Path {
    val h = v * 0.72f
    return Path().apply {
        moveTo(c.x, c.y - v)
        quadraticTo(c.x + waist, c.y - waist, c.x + h, c.y)
        quadraticTo(c.x + waist, c.y + waist, c.x, c.y + v)
        quadraticTo(c.x - waist, c.y + waist, c.x - h, c.y)
        quadraticTo(c.x - waist, c.y - waist, c.x, c.y - v)
        close()
    }
}

/** A line's colour as drawn on this appearance: near-black lines (the Northern) lift at night, as `Theme.line` does. */
fun lineColor(color: RGB?, night: Boolean): Color {
    val own = color ?: JourneyMap.unknownLine
    return (if (night && own.luminance < 0.2) JourneyMap.nightLift else own).toColor()
}

/** The text on a line's pill: navy on a pale line colour (in both appearances), white on a dark one. */
fun onLineColor(color: RGB?, night: Boolean): Color {
    val drawn = if (night && (color?.luminance ?: 0.4) < 0.2) JourneyMap.nightLift else color
    return if ((drawn?.luminance ?: 0.4) < 0.6) Color.White else PolarisColors.Navy
}

@DrawableRes
fun modeIcon(mode: LegMode): Int = when (mode) {
    LegMode.bus, LegMode.coach -> R.drawable.sym_directions_bus
    LegMode.tube -> R.drawable.sym_subway
    LegMode.dlr, LegMode.tram -> R.drawable.sym_tram
    LegMode.ferry -> R.drawable.sym_directions_boat
    LegMode.elizabeth, LegMode.overground -> R.drawable.sym_train
    LegMode.walk, LegMode.cycle -> R.drawable.sym_directions_walk
    else -> R.drawable.sym_directions_railway
}

/** One of the app's Material Symbols, decorative unless given a description. */
@Composable
fun Symbol(@DrawableRes id: Int, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = androidx.compose.material3.LocalContentColor.current) {
    Icon(painterResource(id), contentDescription, modifier, tint = tint)
}

/** "Hammersmith & City" is "H&C" on a pill, as on iOS. */
fun shortLineName(line: String): String {
    val name = line.replace(" line", "").replace("Hammersmith & City", "H&C").replace("Waterloo & City", "W&C")
    if (name != name.lowercase() || name.firstOrNull()?.isLetter() != true) return name
    return name.replaceFirstChar { it.uppercase() }
}

/** A line's label in its own colour: mode icon plus its name. Never truncated: it stays whole. */
@Composable
fun LinePill(leg: Leg, modifier: Modifier = Modifier) {
    val c = polaris
    // A pill keeps its line's own colour in both appearances, as iOS's `LinePill` does; only lines
    // drawn on the map and down the itinerary lift near-black at night. A dark pill at night, or a
    // pale one by day, gets a hairline edge instead.
    val own = leg.lineColor ?: JourneyMap.unknownLine
    val fill = own.toColor()
    val ink = if (own.luminance < 0.6) Color.White else PolarisColors.Navy
    val pale = if (c.isNight) own.luminance < 0.2 else own.luminance > 0.7
    Row(
        modifier
            .background(fill, RoundedCornerShape(6.dp))
            .then(if (pale) Modifier.border(if (c.isNight) 1.dp else 0.5.dp, if (c.isNight) Color.White.copy(alpha = 0.35f) else c.hairline, RoundedCornerShape(6.dp)) else Modifier)
            .heightIn(min = 24.dp)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Symbol(modeIcon(leg.mode), null, Modifier.size(14.dp), tint = ink)
        leg.line?.let { Text(shortLineName(it), color = ink, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
    }
}

/**
 * The journey at a glance: its lines in order, plus any walk long enough to matter (iOS's
 * `LegStrip`), wrapping onto more lines rather than cutting a pill.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LegStrip(legs: List<Leg>, modifier: Modifier = Modifier) {
    val notable = legs.filter { it.mode.isTransit || it.duration >= 5 * 60 }
    val shown = notable.ifEmpty { legs }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEachIndexed { index, leg ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (index > 0) Symbol(R.drawable.sym_chevron_right, null, Modifier.size(16.dp), tint = polaris.inkFaint)
                if (leg.mode.isTransit) {
                    LinePill(leg)
                } else {
                    val minutes = (leg.duration / 60).roundToInt()
                    Row(
                        Modifier.semantics(mergeDescendants = true) { contentDescription = "Walk $minutes min" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Symbol(R.drawable.sym_directions_walk, null, Modifier.size(16.dp), tint = polaris.inkSoft)
                        Text("$minutes", style = MaterialTheme.typography.labelMedium, color = polaris.inkSoft)
                    }
                }
            }
        }
    }
}

/** Which source found a journey. Plain on purpose: the lines carry the colour. */
@Composable
fun SourceBadge(provider: ProviderID, modifier: Modifier = Modifier) {
    Text(
        provider.badge,
        modifier
            .background(polaris.ink.copy(alpha = 0.07f), CircleShape)
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .semantics { contentDescription = provider.displayName },
        style = MaterialTheme.typography.labelSmall,
        color = polaris.inkSoft,
        maxLines = 1,
    )
}

/** A heading over a group of rows, sentence case, announced as a heading. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = polaris.inkSoft,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}
