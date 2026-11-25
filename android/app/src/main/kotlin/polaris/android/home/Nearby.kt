package polaris.android.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import polaris.android.R
import polaris.android.app.AppGraph
import polaris.android.journey.DeparturePill
import polaris.android.journey.LiveDot
import polaris.android.journey.reasonText
import polaris.android.journey.roundedMetres
import polaris.android.journey.ticking
import polaris.android.shell.ShellViewModel
import polaris.android.ui.SectionHeader
import polaris.android.ui.Symbol
import polaris.android.ui.polaris
import polaris.android.ui.surface
import polaris.android.ui.shortLineName
import polaris.engine.Loadable
import polaris.engine.NearbyLine
import java.time.Instant

/**
 * "Leaving near you" (iOS's `NearbyList`): lines leaving stops within a short walk, soonest first
 * in each direction, three rows (four wide) then "Show N more", each opening the line's page. One
 * request once the person has allowed location, kept a minute; Refresh asks again.
 */
fun LazyListScope.nearbySection(graph: AppGraph, shell: ShellViewModel, wide: Boolean, openLine: (NearbyLine) -> Unit) {
    item(key = "nearby-h") {
        val loading by shell.nearbyLoading.collectAsStateWithLifecycle()
        val noFix by shell.nearbyNoFix.collectAsStateWithLifecycle()
        val version by graph.departures.version.collectAsStateWithLifecycle()
        val state = remember(version) { graph.departures.nearby }
        SectionHeader(stringResource(R.string.nearby_title)) {
            // One 48 dp slot, whether it holds Refresh, the spinner or nothing, so the heading never jumps.
            androidx.compose.foundation.layout.Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else if (state != null && state != Loadable.Loading || noFix) {
                    IconButton(onClick = { shell.refreshNearby(force = true) }) { Symbol(R.drawable.sym_refresh, stringResource(R.string.nearby_refresh), tint = polaris.tint) }
                }
            }
        }
    }
    item(key = "nearby") {
        val shows by shell.showsLocation.collectAsStateWithLifecycle()
        val noFix by shell.nearbyNoFix.collectAsStateWithLifecycle()
        val loading by shell.nearbyLoading.collectAsStateWithLifecycle()
        val version by graph.departures.version.collectAsStateWithLifecycle()
        val state = remember(version) { graph.departures.nearby }
        // Asked once Home is showing and location is allowed, and again when Home comes back or
        // the app returns, if the minute has passed (no fix is asked for within it).
        androidx.lifecycle.compose.LifecycleResumeEffect(shows) {
            if (shows) shell.refreshNearby()
            onPauseOrDispose { }
        }
        NearbyList(
            state, locationOff = !graph.locator.hasPermission, noFix = noFix && state?.value == null && !loading,
            collapsed = if (wide) 4 else 3, allowLocation = shell::locateMe, retry = { shell.refreshNearby(force = true) }, openLine = openLine,
        )
    }
}

@Composable
private fun NearbyList(
    state: Loadable<List<NearbyLine>>?,
    locationOff: Boolean,
    noFix: Boolean,
    collapsed: Int,
    allowLocation: () -> Unit,
    retry: () -> Unit,
    openLine: (NearbyLine) -> Unit,
) {
    val c = polaris
    var showAll by rememberSaveable { mutableStateOf(false) }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    val narrow = maxWidth < 400.dp
    Column(Modifier.fillMaxWidth().surface(c)) {
        val lines = state?.value
        when {
            locationOff -> {
                Note(stringResource(R.string.nearby_location_off))
                Action(stringResource(R.string.nearby_use_location), allowLocation)
            }
            noFix -> {
                Note(stringResource(R.string.nearby_no_fix))
                Action(stringResource(R.string.nearby_try_again), retry)
            }
            lines != null && lines.isEmpty() -> Note(stringResource(R.string.nearby_none))
            lines != null -> {
                val now = ticking(30)
                // Gone more than 30 s ago: off the list, as the board does (iOS asks again instead).
                val live = lines.mapNotNull { line ->
                    line.departures.filter { it.expected > now.minusSeconds(30) }.takeIf { it.isNotEmpty() }?.let { line.copy(departures = it) }
                }.take(10)
                if (live.isEmpty()) {
                    Note(stringResource(R.string.nearby_all_gone))
                    Action(stringResource(R.string.board_refresh), retry)
                }
                val shown = if (showAll) live else live.take(collapsed)
                // One width for every pill, the widest, so names start at the same place on every row.
                val pillWidth = pillColumnWidth(live)
                shown.forEachIndexed { index, line ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = c.hairline)
                    NearbyRow(line, now, pillWidth, narrow) { openLine(line) }
                }
                if (live.size > collapsed) {
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = c.hairline)
                    TextButton(onClick = { showAll = !showAll }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (showAll) stringResource(R.string.nearby_show_fewer) else stringResource(R.string.nearby_show_more, live.size - collapsed), fontWeight = FontWeight.Medium)
                    }
                }
            }
            state is Loadable.Failed -> {
                Note(reasonText(state.reason))
                Action(stringResource(R.string.nearby_try_again), retry)
            }
            else -> Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.nearby_finding), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
            }
        }
    }
    }
}

/** "to Paddington"; a headsign that is already a "via …" stands as it is; none, the line's name. */
@Composable
fun headsignText(line: String, headsign: String): String = when {
    headsign.isEmpty() -> line
    headsign.lowercase().startsWith("via ") -> headsign
    else -> stringResource(R.string.nearby_to, headsign)
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = polaris.inkSoft)
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp).heightIn(min = 48.dp)) { Text(label) }
}

/** The widest pill among [lines], measured, so every row's text starts at the same place. */
@Composable
private fun pillColumnWidth(lines: List<NearbyLine>): androidx.compose.ui.unit.Dp {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    val density = LocalDensity.current
    val widest = remember(lines.map { it.line }, density.fontScale) {
        lines.maxOfOrNull { measurer.measure(shortLineName(it.line), style).size.width } ?: 0
    }
    // Text, plus the pill's 14 dp icon, 3 dp gap and 7 dp padding each side.
    return with(density) { widest.toDp() } + 31.dp
}

@Composable
private fun NearbyRow(line: NearbyLine, now: Instant, pillWidth: androidx.compose.ui.unit.Dp, narrow: Boolean, onClick: () -> Unit) {
    val c = polaris
    // At large text the row stacks (line and times, then where to, then the stop and distance),
    // each line wrapping, so nothing is cut short.
    val large = LocalDensity.current.fontScale >= 1.3f
    // Under 400 dp for the list (a phone, the card on a fold), the next two, as iOS does when narrow.
    val minutes = line.departures.take(if (large || narrow) 2 else 3).map { maxOf(0L, (it.expected.epochSecond - now.epochSecond) / 60).toInt() }
    val nowWord = stringResource(R.string.nearby_now)
    val times = minutes.joinToString(", ") { if (it == 0) nowWord else it.toString() }
        .let { if (minutes.isEmpty() || minutes.last() == 0) it else stringResource(R.string.nearby_minutes_suffix, it) }
    val toText = headsignText(line.line, line.headsign)
    val metres = roundedMetres(line.distance)
    val timesView: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (line.departures.any { it.isRealtime }) LiveDot()
            Text(times, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.ink, maxLines = 1, softWrap = false)
            Symbol(R.drawable.sym_chevron_right, null, Modifier.size(16.dp), tint = c.inkFaint)
        }
    }
    val rowModifier = Modifier.fillMaxWidth()
        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.nearby_open), onClick = onClick)
        .heightIn(min = 56.dp)
        .padding(horizontal = 12.dp, vertical = 8.dp)
        .semantics(mergeDescendants = true) {}
    if (large) {
        Column(rowModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeparturePill(line.mode, line.line, line.lineColor)
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                timesView()
            }
            Text(toText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = c.ink)
            Text(stringResource(R.string.nearby_stop_distance, line.stopName, metres), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        }
        return
    }
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.foundation.layout.Box(Modifier.width(pillWidth)) { DeparturePill(line.mode, line.line, line.lineColor) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Names wrap to a second line before they are shortened.
            Text(toText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // The distance first, in the same place on every row, then the stop, which wraps to a
            // second line before it is shortened; three pieces, so right to left lays them out
            // properly and never runs them together.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.nearby_metres, metres), style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 1, softWrap = false)
                Text(stringResource(R.string.nearby_dot), style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 1)
                Text(line.stopName, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        timesView()
    }
}
