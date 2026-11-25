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
        val version by graph.departures.version.collectAsStateWithLifecycle()
        val loaded = remember(version) { graph.departures.nearby?.value != null }
        SectionHeader(stringResource(R.string.nearby_title)) {
            if (loaded) {
                if (loading) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { shell.refreshNearby(force = true) }) { Symbol(R.drawable.sym_refresh, stringResource(R.string.nearby_refresh), tint = polaris.tint) }
            }
        }
    }
    item(key = "nearby") {
        val shows by shell.showsLocation.collectAsStateWithLifecycle()
        val version by graph.departures.version.collectAsStateWithLifecycle()
        val state = remember(version) { graph.departures.nearby }
        // Asked once Home is showing and location is allowed; the store keeps it a minute.
        LaunchedEffect(shows) { if (shows) shell.refreshNearby() }
        NearbyList(state, locationOff = !graph.locator.hasPermission, collapsed = if (wide) 4 else 3, allowLocation = shell::locateMe, openLine = openLine)
    }
}

@Composable
private fun NearbyList(state: Loadable<List<NearbyLine>>?, locationOff: Boolean, collapsed: Int, allowLocation: () -> Unit, openLine: (NearbyLine) -> Unit) {
    val c = polaris
    var showAll by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().surface(c)) {
        val lines = state?.value
        when {
            locationOff -> {
                Note(stringResource(R.string.nearby_location_off))
                TextButton(onClick = allowLocation, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp).heightIn(min = 48.dp)) { Text(stringResource(R.string.nearby_use_location)) }
            }
            lines != null && lines.isEmpty() -> Note(stringResource(R.string.nearby_none))
            lines != null -> {
                val now = ticking(30)
                val all = lines.take(10)
                val shown = if (showAll) all else all.take(collapsed)
                shown.forEachIndexed { index, line ->
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 12.dp), color = c.hairline)
                    NearbyRow(line, now) { openLine(line) }
                }
                if (all.size > collapsed) {
                    HorizontalDivider(Modifier.padding(start = 12.dp), color = c.hairline)
                    TextButton(onClick = { showAll = !showAll }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (showAll) stringResource(R.string.nearby_show_fewer) else stringResource(R.string.nearby_show_more, all.size - collapsed), fontWeight = FontWeight.Medium)
                    }
                }
            }
            state is Loadable.Failed -> Note(reasonText(state.reason))
            else -> Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.nearby_finding), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = polaris.inkSoft)
}

@Composable
private fun NearbyRow(line: NearbyLine, now: Instant, onClick: () -> Unit) {
    val c = polaris
    // At large text, the next two only, so the line and the times stay whole and the names give way.
    val narrow = LocalDensity.current.fontScale >= 1.3f
    val minutes = line.departures.take(if (narrow) 2 else 3).map { maxOf(0L, (it.expected.epochSecond - now.epochSecond) / 60).toInt() }
    val nowWord = stringResource(R.string.nearby_now)
    val times = minutes.joinToString(", ") { if (it == 0) nowWord else it.toString() }
        .let { if (minutes.isEmpty() || minutes.last() == 0) it else stringResource(R.string.nearby_minutes_suffix, it) }
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.nearby_open), onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DeparturePill(line.mode, line.line, line.lineColor, Modifier.widthIn(min = 44.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (line.headsign.isEmpty()) line.line else stringResource(R.string.nearby_to, line.headsign),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.nearby_stop_distance, line.stopName, roundedMetres(line.distance)),
                style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (line.departures.any { it.isRealtime }) LiveDot()
            Text(times, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.ink, maxLines = 1, softWrap = false)
            Symbol(R.drawable.sym_chevron_right, null, Modifier.size(16.dp), tint = c.inkFaint)
        }
    }
}
