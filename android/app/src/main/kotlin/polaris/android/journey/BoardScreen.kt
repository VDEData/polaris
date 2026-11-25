package polaris.android.journey

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import polaris.android.R
import polaris.android.home.headsignText
import polaris.android.app.AppGraph
import polaris.android.shell.BoardPage
import polaris.android.shell.LinePage
import polaris.android.shell.MapScene
import polaris.android.shell.SceneEffect
import polaris.android.shell.SheetPage
import polaris.android.shell.ShellViewModel
import polaris.android.shell.factory
import polaris.android.ui.Clock
import polaris.android.ui.LinePill
import polaris.android.ui.Symbol
import polaris.android.ui.polaris
import polaris.android.ui.surface
import polaris.engine.DepartureBoard
import polaris.engine.JourneyMap
import polaris.engine.Leg
import polaris.engine.Loadable
import polaris.engine.NearbyLine

/**
 * The full board for one boarding stop (iOS's `BoardSheet`): everything that also stops where the
 * journey gets off, earlier and later, with yours marked, scrolled to yours. Pushed from a strip
 * on a phone. Refresh is a button: pulling down inside the sheet moves the sheet.
 */
@Composable
fun BoardScreen(key: BoardPage, graph: AppGraph, onBack: () -> Unit) {
    // The journey page's model for the same route: the board it already holds, and one request at most if not.
    val model: JourneyViewModel = viewModel(factory = factory { JourneyViewModel(graph, key.searchId, key.journeyId, onlyLeg = key.legId) })
    val ui by model.ui.collectAsStateWithLifecycle()
    val leg = ui.journey?.legs?.firstOrNull { it.id == key.legId }
    val night = polaris.isNight
    // The route stays drawn; the map frames the stop the board is for.
    val journey = ui.journey
    val query = ui.query
    val content = remember(journey, leg, night) {
        val drawing = journey?.let { JourneyMap.drawing(it, night, query?.origin?.takeUnless { p -> p.isCurrentLocation }?.coordinate, query?.destination?.takeUnless { p -> p.isCurrentLocation }?.coordinate) }
        val stop = leg?.start
        MapScene.Content(drawing, frame = stop?.let { s -> JourneyMap.Bounds(s.latitude - 0.003, s.longitude - 0.005, s.latitude + 0.003, s.longitude + 0.005) } ?: drawing?.bounds)
    }
    SceneEffect(key, content)
    val title = stringResource(R.string.board_title)
    SheetPage(key, bar = { PageBar(title, onBack) }) {
        if (leg == null) {
            Column {
                PageBar(title, onBack)
                if (!ui.loading) Text(stringResource(R.string.journey_gone_body), Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = polaris.inkSoft)
            }
        } else {
            BoardContent(leg, ui.boards[leg.id], leg.id in ui.refreshing, refresh = { model.refresh(leg) }, narrow = false, back = onBack)
        }
    }
}

/** The page's bar: up and the title. */
@Composable
internal fun PageBar(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Symbol(R.drawable.sym_arrow_back, stringResource(R.string.common_back)) }
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = polaris.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

/** The board's list, for the page and for the card docked on the map half. */
@Composable
fun BoardContent(
    leg: Leg,
    state: Loadable<DepartureBoard>?,
    refreshing: Boolean,
    refresh: () -> Unit,
    narrow: Boolean,
    modifier: Modifier = Modifier,
    back: (() -> Unit)? = null,
    close: (() -> Unit)? = null,
    docked: Boolean = false,
) {
    val c = polaris
    val now = ticking()
    val board = state?.value
    val list = rememberLazyListState()
    // Scrolled to yours once, when the board first comes in.
    var scrolled by rememberSaveable(leg.id) { mutableStateOf(false) }
    val plannedIndex = board?.departures?.indexOfFirst { it.id == board.plannedId } ?: -1
    val large = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f
    LaunchedEffect(plannedIndex) {
        if (!scrolled && plannedIndex >= 0) {
            scrolled = true
            // The header and alerts come first; then the rows (centred roughly by leaving two above).
            // Two rows above yours, or one where rows are tall (large text) or few show (the docked card).
            val above = if (docked || large) 1 else 2
            list.scrollToItem(HEADER_ITEMS + (board?.alerts?.size ?: 0) + maxOf(0, plannedIndex - above))
        }
    }
    Column(modifier.fillMaxWidth().then(if (narrow) Modifier else Modifier.fillMaxSize())) {
    // The stop, its line, Refresh and Close stay put while the rows scroll (scrolled to yours).
    Column(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = if (narrow) 8.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                if (back != null) IconButton(onClick = back, modifier = Modifier.padding(start = 0.dp)) { Symbol(R.drawable.sym_arrow_back, stringResource(R.string.common_back)) }
                Text(
                    board?.stopName ?: leg.fromName,
                    Modifier.weight(1f).padding(start = if (back == null) 4.dp else 0.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (refreshing) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                else IconButton(onClick = refresh) { Symbol(R.drawable.sym_refresh, stringResource(R.string.board_refresh), tint = c.tint) }
                if (close != null) IconButton(onClick = close) { Symbol(R.drawable.sym_close, stringResource(R.string.board_close), tint = c.inkSoft) }
            }
            androidx.compose.foundation.layout.FlowRow(
                Modifier.padding(start = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                LinePill(leg)
                Text(stringResource(R.string.board_and_else, leg.toName), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
            }
    }
    LazyColumn(
        Modifier.fillMaxWidth().weight(1f, fill = !narrow),
        state = list,
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            bottom = 16.dp, // in the sheet the page already stops above the navigation bar; docked, there is none
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        when {
            board != null -> {
                board.alerts.forEachIndexed { i, alert -> item(key = "alert-$i") { Column(Modifier.padding(horizontal = 4.dp)) { AlertLine(alert) } } }
                if (board.departures.isEmpty()) {
                    item(key = "empty") { Note(stringResource(R.string.board_nothing, leg.toName, Clock.of(leg.departure))) }
                } else {
                    rows(board, now, leg.toName, narrow)
                }
                item(key = "source") {
                    Text(stringResource(R.string.board_source, leg.toName), Modifier.padding(horizontal = 4.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
                }
            }
            state is Loadable.Failed -> item(key = "failed") { Note(reasonText(state.reason)) }
            else -> item(key = "loading") {
                Row(Modifier.fillMaxWidth().heightIn(min = 120.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
        }
    }
    }
}

private const val HEADER_ITEMS = 0

private fun LazyListScope.rows(board: DepartureBoard, now: java.time.Instant, destination: String, narrow: Boolean) {
    itemsIndexed(board.departures, key = { _, d -> d.id }) { index, d ->
        val c = polaris
        val first = index == 0
        val last = index == board.departures.lastIndex
        // One surface around the rows, drawn row by row so the list stays lazy.
        val shape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = if (first) 18.dp else 0.dp, topEnd = if (first) 18.dp else 0.dp,
            bottomStart = if (last) 18.dp else 0.dp, bottomEnd = if (last) 18.dp else 0.dp,
        )
        Column(Modifier.fillMaxWidth().clip(shape).background(c.surface, shape)) {
            if (!first) HorizontalDivider(Modifier.padding(start = 74.dp), color = c.hairline)
            BoardRow(d, isPlanned = d.id == board.plannedId, now = now, difference = if (d.id == board.plannedId) null else board.arrivalDifference(d), destination = destination, narrow = narrow)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.fillMaxWidth().surface(polaris).padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = polaris.inkSoft)
}

/**
 * A line leaving a stop near you (iOS's `LineSheet`, without live buses yet): its next departures
 * from the one nearby request. No request of its own; Refresh asks for what's nearby again.
 */
@Composable
fun LineScreen(key: LinePage, graph: AppGraph, shell: ShellViewModel, onBack: () -> Unit) {
    val c = polaris
    val version by graph.departures.version.collectAsStateWithLifecycle()
    val line: NearbyLine? = remember(version, key) { graph.departures.nearby?.value?.firstOrNull { it.id == key.lineId && it.stopName == key.stopName } }
    val content = remember(line) {
        line?.stopCoordinate?.let { s -> MapScene.Content(frame = JourneyMap.Bounds(s.latitude - 0.002, s.longitude - 0.003, s.latitude + 0.002, s.longitude + 0.003)) } ?: MapScene.Content()
    }
    SceneEffect(key, content)
    val title = key.stopName
    val refreshing by shell.nearbyLoading.collectAsStateWithLifecycle()
    val refresh = { shell.refreshNearby(force = true) }
    SheetPage(key, bar = { PageBar(title, onBack) }) {
        val now = ticking(30)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "bar") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Symbol(R.drawable.sym_arrow_back, stringResource(R.string.common_back)) }
                    Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (refreshing) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = refresh) { Symbol(R.drawable.sym_refresh, stringResource(R.string.board_refresh), tint = c.tint) }
                }
            }
            if (line == null) {
                item(key = "gone") {
                    Column(Modifier.fillMaxWidth().surface(c).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.line_gone), style = MaterialTheme.typography.titleSmall, color = c.ink)
                        Text(stringResource(R.string.line_gone_body), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                        TextButton(onClick = refresh, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.board_refresh)) }
                    }
                }
            } else {
                item(key = "header") {
                    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            DeparturePill(line.mode, line.line, line.lineColor)
                            Text(
                                headsignText(line.line, line.headsign),
                                style = MaterialTheme.typography.titleMedium, color = c.ink,
                            )
                        }
                        Text(stringResource(R.string.line_stop_away, line.stopName, roundedMetres(line.distance)), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                    }
                }
                item(key = "times") {
                    Column(Modifier.fillMaxWidth().surface(c).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.line_leaving), Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge, color = c.inkSoft)
                        // Gone more than 30 s ago: off the list.
                        val live = line.departures.filter { it.expected > now.minusSeconds(30) }
                        if (live.isEmpty()) Text(stringResource(R.string.line_all_gone), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                        live.forEach { d ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(Clock.of(d.expected), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = c.ink)
                                if (d.isRealtime) LiveDot()
                                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                                val minutes = ((d.expected.epochSecond - now.epochSecond) / 60).toInt()
                                Text(if (minutes < 1) stringResource(R.string.board_now) else stringResource(R.string.board_minutes, minutes), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                            }
                        }
                    }
                }
                item(key = "source") { Text(stringResource(R.string.line_source), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = c.inkSoft) }
            }
        }
    }
}

/** To the nearest 10 m, as iOS shows it. */
fun roundedMetres(metres: Double): Int = (Math.round(metres / 10) * 10).toInt()
