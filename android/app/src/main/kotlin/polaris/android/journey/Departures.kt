package polaris.android.journey

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import polaris.android.R
import polaris.android.ui.SEPARATOR
import polaris.android.ui.Clock
import polaris.android.ui.PolarisColors
import polaris.android.ui.Symbol
import polaris.android.ui.modeIcon
import polaris.android.ui.polaris
import polaris.android.ui.shortLineName
import polaris.android.ui.toColor
import polaris.engine.Departure
import polaris.engine.DepartureBoard
import polaris.engine.JourneyMap
import polaris.engine.Leg
import polaris.engine.LegMode
import polaris.engine.Loadable
import polaris.engine.RGB
import polaris.engine.ServiceAlert
import polaris.engine.Strip
import java.time.Instant
import kotlin.math.roundToInt

/** The time now, moving on every [seconds], for countdowns and "gone". */
@Composable
fun ticking(seconds: Long = 20): Instant {
    val now by produceState(Instant.now()) {
        while (true) {
            delay(seconds * 1000)
            value = Instant.now()
        }
    }
    return now
}

/** "26 min", "1 h 05", as iOS's `minutesText`. */
@Composable
fun durationText(seconds: Double): String {
    val minutes = Strip.roundAway(seconds / 60)
    return if (minutes < 60) stringResource(R.string.journey_duration_minutes, minutes)
    else stringResource(R.string.journey_duration_hours, minutes / 60, "%02d".format(java.util.Locale.ROOT, minutes % 60))
}

/** "+3 min", "−2 min". */
@Composable
fun signedText(minutes: Int): String =
    if (minutes >= 0) stringResource(R.string.strip_later, minutes) else stringResource(R.string.strip_sooner, -minutes)

/** A line's label for a departure (no leg to hand): mode icon plus its name, in its colour. */
@Composable
fun DeparturePill(mode: LegMode, line: String, color: RGB?, modifier: Modifier = Modifier) {
    val c = polaris
    val own = color ?: JourneyMap.unknownLine
    val ink = if (own.luminance < 0.6) Color.White else PolarisColors.Navy
    val pale = if (c.isNight) own.luminance < 0.2 else own.luminance > 0.7
    Row(
        modifier
            .background(own.toColor(), RoundedCornerShape(6.dp))
            .then(if (pale) Modifier.border(if (c.isNight) 1.dp else 0.5.dp, if (c.isNight) Color.White.copy(alpha = 0.35f) else c.hairline, RoundedCornerShape(6.dp)) else Modifier)
            .heightIn(min = 24.dp)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Symbol(modeIcon(mode), null, Modifier.size(14.dp), tint = ink)
        Text(shortLineName(line), color = ink, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

/** The small green dot for times from a live feed. */
@Composable
fun LiveDot(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.journey_live)
    Box(modifier.size(7.dp).background(polaris.live, CircleShape).semantics { contentDescription = label })
}

/** Why a board or nearby list couldn't load, in words. */
@Composable
fun reasonText(reason: Loadable.Reason): String = stringResource(
    when (reason) {
        Loadable.Reason.offline -> R.string.board_offline
        Loadable.Reason.notAnswering -> R.string.board_not_answering
        Loadable.Reason.failed -> R.string.board_failed
    },
)

/**
 * The board in one line (iOS's `DepartureStrip`): the ones just before yours, yours, and the next
 * few, as many as fit (2+3, 1+3, 1+2, then yours and the next), with what missing yours (or making
 * an earlier one) does to your arrival, and the way to the whole board.
 */
@Composable
fun DepartureStrip(leg: Leg, state: Loadable<DepartureBoard>?, reachable: Instant?, onOpen: (() -> Unit)?) {
    val c = polaris
    val board = state?.value
    when {
        board != null && board.departures.isNotEmpty() -> {
            val now = ticking()
            val openLabel = stringResource(R.string.strip_open_board)
            Column(
                Modifier.fillMaxWidth()
                    .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClickLabel = openLabel, onClick = onOpen) else Modifier)
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Ticks(leg, board, now, reachable)
                verdictText(leg, board, now, reachable)?.let { (text, warns) ->
                    Text(text, style = MaterialTheme.typography.bodySmall, color = if (warns) c.warning else c.inkSoft)
                }
                if (onOpen != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(R.string.strip_all, board.stopName),
                            Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.labelLarge, color = c.tint, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Symbol(R.drawable.sym_chevron_right, null, Modifier.size(16.dp), tint = c.tint)
                    }
                }
            }
        }
        state is Loadable.Loaded || state is Loadable.Failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Symbol(R.drawable.sym_schedule, null, Modifier.size(16.dp), tint = c.inkFaint)
            Text(stringResource(R.string.strip_none), style = MaterialTheme.typography.bodySmall, color = c.inkFaint)
        }
        else -> Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = c.inkSoft)
            Text(stringResource(R.string.strip_checking), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        }
    }
}

/** The tiles, as many as fit the width, in the fixed choices iOS's `ViewThatFits` tries. */
@Composable
private fun Ticks(leg: Leg, board: DepartureBoard, now: Instant, reachable: Instant?) {
    val all = Strip.window(board, leg.departure, before = 2, after = 3)
    val anchor = all.indexOfFirst { it.id == board.plannedId }.takeIf { it >= 0 }
        ?: all.indexOfFirst { it.expected >= leg.departure.minusSeconds(60) }.takeIf { it >= 0 } ?: 0
    // Tiles grow with the text size, as iOS's @ScaledMetric does.
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val tileWidth = 44.dp * scale
    val gap = 4.dp
    val gone = maxOf(now, reachable ?: now)
    Layout(
        content = {
            all.forEach { d ->
                Tile(d, Strip.caption(d, board, leg.departure, now), d.id == board.plannedId, d.expected < gone, tileWidth)
            }
        },
    ) { measurables, constraints ->
        // Each tile at least its width, wider when its words need it ("cancelled"), never cut.
        val placeables = measurables.map { it.measure(Constraints(minWidth = tileWidth.roundToPx())) }
        val gapPx = gap.roundToPx()
        val widthOf = { range: IntRange -> range.sumOf { placeables[it].width } + gapPx * (range.count() - 1) }
        // 2 before and 3 after, then 1+3, 1+2, and yours with the next.
        val range = listOf(2 to 3, 1 to 3, 1 to 2, 0 to 1).map { (b, a) -> maxOf(0, anchor - b) until minOf(all.size, anchor + a + 1) }
            .firstOrNull { widthOf(it) <= constraints.maxWidth } ?: (anchor..anchor)
        val shown = range.toList()
        val height = shown.maxOfOrNull { placeables[it].height } ?: 0
        layout(minOf(constraints.maxWidth, widthOf(range)), height) {
            var x = 0
            shown.forEach { index -> placeables[index].placeRelative(x, 0); x += placeables[index].width + gapPx }
        }
    }
}

@Composable
private fun Tile(departure: Departure, caption: Strip.Caption, isPlanned: Boolean, isGone: Boolean, width: androidx.compose.ui.unit.Dp) {
    val c = polaris
    // At night a wash of gold over navy turns olive, so yours is solid gold with navy text.
    val onGold = isPlanned && c.isNight
    val words = when (caption) {
        Strip.Caption.Cancelled -> stringResource(R.string.strip_cancelled)
        Strip.Caption.Gone -> stringResource(R.string.strip_gone)
        Strip.Caption.Yours -> stringResource(R.string.strip_yours)
        Strip.Caption.Same -> stringResource(R.string.strip_same)
        is Strip.Caption.Minutes -> signedText(caption.minutes)
    }
    val time = Clock.of(departure.expected)
    val spoken = stringResource(R.string.strip_tile, time, words)
    Column(
        Modifier
            .widthIn(min = width)
            .heightIn(min = 40.dp)
            .alpha(if (isGone && !isPlanned) 0.5f else 1f)
            .background(if (isPlanned) PolarisColors.Gold.copy(alpha = if (onGold) 1f else 0.32f) else c.ink.copy(alpha = 0.045f), RoundedCornerShape(9.dp))
            .padding(vertical = 3.dp, horizontal = 4.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            time,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isPlanned) FontWeight.Bold else FontWeight.Medium,
            textDecoration = if (departure.isCancelled) TextDecoration.LineThrough else null,
            color = if (departure.isLate || departure.isCancelled) (if (onGold) Color(0xFF8C1F00) else c.warning) else (if (onGold) PolarisColors.Navy else c.ink),
            maxLines = 1, softWrap = false,
        )
        Text(
            words,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isPlanned) FontWeight.SemiBold else FontWeight.Normal,
            color = if (onGold) PolarisColors.Navy else if (isPlanned) c.ink else c.inkSoft,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
        )
    }
}

/** The cost of missing it, or the reward for running, and whether it warns. */
@Composable
private fun verdictText(leg: Leg, board: DepartureBoard, now: Instant, reachable: Instant?): Pair<String, Boolean>? {
    val verdict = Strip.verdict(board, now, reachable) ?: return null
    val there = leg.toName.ifEmpty { stringResource(R.string.strip_there) }
    return when (verdict) {
        is Strip.Verdict.Cancelled -> (
            verdict.next?.let { stringResource(R.string.strip_cancelled_next, Clock.of(verdict.scheduled), Clock.of(it)) }
                ?: stringResource(R.string.strip_cancelled_only, Clock.of(verdict.scheduled))
            ) to true
        is Strip.Verdict.Plain -> {
            val parts = listOfNotNull(
                verdict.late?.let { stringResource(R.string.strip_late, it) },
                verdict.earlier?.let { stringResource(R.string.strip_earlier, Clock.of(it), there, verdict.gain ?: 0) },
                verdict.next?.let { stringResource(R.string.strip_miss, Clock.of(it), verdict.cost ?: 0) },
            )
            parts.joinToString(" ") to verdict.warns
        }
    }
}

/** An operator's status line ("Minor delays"), opening to the full message (iOS's `AlertLine`). */
@Composable
fun AlertLine(alert: ServiceAlert, minor: Boolean = false) {
    val c = polaris
    var expanded by rememberSaveable(alert.header, alert.detail) { mutableStateOf(false) }
    val hint = stringResource(R.string.journey_read_notice)
    Row(
        Modifier.fillMaxWidth()
            .then(if (alert.detail != null) Modifier.clickable(role = Role.Button, onClickLabel = if (expanded) null else hint) { expanded = !expanded } else Modifier)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Symbol(if (minor) R.drawable.sym_info else R.drawable.sym_warning, null, Modifier.size(16.dp).padding(top = 1.dp), tint = if (minor) c.inkSoft else c.warning)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(alert.header, style = MaterialTheme.typography.labelLarge, color = c.ink)
            if (expanded) alert.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkSoft) }
        }
    }
}

/** One row of a board: when it goes, what it is, when it reaches your stop, and yours or the difference. */
@Composable
fun BoardRow(departure: Departure, isPlanned: Boolean, now: Instant, difference: Double?, destination: String, narrow: Boolean) {
    val c = polaris
    // At large text a row is narrow wherever it is: the headsign under the line, never cut.
    val narrow = narrow || LocalDensity.current.fontScale >= 1.3f
    val gone = departure.expected < now
    val status = when {
        departure.isCancelled -> stringResource(R.string.board_cancelled)
        gone -> stringResource(R.string.board_gone)
        else -> {
            val minutes = ((departure.expected.epochSecond - now.epochSecond) / 60).toInt()
            if (minutes < 1) stringResource(R.string.board_now) else if (minutes < 60) stringResource(R.string.board_minutes, minutes) else Clock.of(departure.expected)
        }
    }
    val detail = listOfNotNull(
        departure.arrivalThere?.let { if (narrow) stringResource(R.string.board_arrives, Clock.of(it)) else stringResource(R.string.board_reaches, destination, Clock.of(it)) },
        departure.platform?.let { stringResource(if (departure.mode == LegMode.bus || departure.mode == LegMode.coach) R.string.board_stop else R.string.board_platform, it) },
    // The dot stays with what comes before it, so a wrapped line never starts with it.
    ).joinToString(SEPARATOR)
    Row(
        Modifier.fillMaxWidth()
            .alpha(if (gone && !isPlanned) 0.5f else 1f)
            .then(
                if (!isPlanned) Modifier
                else if (c.isNight) Modifier.padding(3.dp).background(PolarisColors.Gold.copy(alpha = 0.07f), RoundedCornerShape(12.dp)).border(1.dp, c.goldInk.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                else Modifier.background(PolarisColors.Gold.copy(alpha = 0.18f)),
            )
            .heightIn(min = 60.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.widthIn(min = 50.dp)) {
            Text(
                Clock.of(departure.expected),
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                textDecoration = if (departure.isCancelled) TextDecoration.LineThrough else null,
                color = if (departure.isLate || departure.isCancelled) c.warning else c.ink, maxLines = 1, softWrap = false,
            )
            if (departure.isLate) {
                val scheduled = Clock.of(departure.scheduled)
                val said = stringResource(R.string.board_scheduled, scheduled)
                Text(scheduled, Modifier.semantics { contentDescription = said }, style = MaterialTheme.typography.bodySmall, textDecoration = TextDecoration.LineThrough, color = c.inkSoft, maxLines = 1, softWrap = false)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val headsign = departure.headsign.ifEmpty { departure.line }
            if (narrow) {
                // Narrow, the line's name and the headsign stay whole: the headsign under the pill.
                DeparturePill(departure.mode, departure.line, departure.lineColor)
                Text(headsign, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DeparturePill(departure.mode, departure.line, departure.lineColor)
                    Text(headsign, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (departure.isRealtime) LiveDot()
                Text(
                    status, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isPlanned) FontWeight.Bold else FontWeight.Medium,
                    color = if (departure.isCancelled) c.warning else c.ink, maxLines = 1, softWrap = false,
                )
            }
            if (isPlanned) {
                Text(stringResource(R.string.board_yours), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = c.goldInk)
            } else if (difference != null && !departure.isCancelled) {
                val m = Strip.signedMinutes(difference)
                Text(if (m == 0) stringResource(R.string.strip_same) else signedText(m), style = MaterialTheme.typography.labelMedium, color = c.inkSoft, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** "350 m", "1.2 km". */
@Composable
fun distanceText(metres: Double): String =
    if (metres < 1000) stringResource(R.string.journey_metres, ((metres / 10).roundToInt() * 10))
    else stringResource(R.string.journey_kilometres, "%.1f".format(java.util.Locale.UK, metres / 1000))

/** "2 stops · 6 min". */
@Composable
fun stopsText(leg: Leg): String {
    val duration = durationText(leg.duration)
    return leg.stopCount?.let { stringResource(R.string.journey_stops_duration, pluralStringResource(R.plurals.journey_stops, it, it), duration) } ?: duration
}

