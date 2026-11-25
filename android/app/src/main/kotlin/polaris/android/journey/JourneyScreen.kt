package polaris.android.journey

import polaris.engine.Strip
import polaris.engine.LegMode
import polaris.engine.Loadable
import polaris.engine.DepartureBoard
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import polaris.android.R
import polaris.android.app.AppGraph
import polaris.android.results.OpenIn
import polaris.android.shell.JourneyPage
import polaris.android.shell.LocalScene
import polaris.android.shell.LocalSheet
import polaris.android.shell.LocalWindowLayout
import polaris.android.shell.MapScene
import polaris.android.shell.SceneEffect
import polaris.android.shell.SheetPage
import polaris.android.shell.factory
import polaris.android.ui.Clock
import polaris.android.ui.FractalStar
import polaris.android.ui.LinePill
import polaris.android.ui.Symbol
import polaris.android.ui.polaris
import polaris.android.ui.shortLineName
import polaris.android.ui.surface
import polaris.android.ui.lineColor
import polaris.engine.Journey
import polaris.engine.JourneyMap
import polaris.engine.JourneyQuery
import polaris.engine.Leg
import polaris.engine.ServiceAlert
import polaris.engine.ServiceNotice
import java.time.Instant

/**
 * One route (iOS's `JourneySheet`): the times and fare, when to leave, Share, the itinerary with a
 * departure strip under each ride, who found it, and the hand-off to another app. Go (following
 * the trip live) comes with the next update, so there is no Go button yet; the alert bell waits
 * for alerts.
 *
 * On a phone a strip opens its board as a page; wide, the board docks at the foot of the map half
 * beside the itinerary, as iOS does on the Duo.
 */
@Composable
fun JourneyScreen(key: JourneyPage, graph: AppGraph, onBack: () -> Unit, openBoard: (Leg) -> Unit) {
    val model: JourneyViewModel = viewModel(factory = factory { JourneyViewModel(graph, key.searchId, key.journeyId) })
    val ui by model.ui.collectAsStateWithLifecycle()
    val sheet = LocalSheet.current
    val scene = LocalScene.current
    val layout = LocalWindowLayout.current
    val c = polaris
    val night = c.isNight
    val journey = ui.journey
    val query = ui.query

    // The map: this route alone, framed; a ride tapped is framed on its own.
    var focused by rememberSaveable { mutableStateOf<String?>(null) }
    val content = remember(journey, query, night, focused) {
        journey?.let { j ->
            val drawing = JourneyMap.drawing(j, night, query?.origin?.takeUnless { it.isCurrentLocation }?.coordinate, query?.destination?.takeUnless { it.isCurrentLocation }?.coordinate)
            val leg = j.legs.firstOrNull { it.id == focused }
            val frame = leg?.path?.takeIf { it.size > 1 }?.let { p -> JourneyMap.Bounds(p.minOf { it.latitude }, p.minOf { it.longitude }, p.maxOf { it.latitude }, p.maxOf { it.longitude }) }
            MapScene.Content(drawing, frame = frame ?: drawing.bounds)
        } ?: MapScene.Content()
    }
    SceneEffect(key, content)

    // Wide (not a short landscape window): a picked ride's board docks on the map half.
    val docks = sheet.wide && !layout.short
    var docked by rememberSaveable { mutableStateOf<String?>(null) }
    val dockedLeg = journey?.legs?.firstOrNull { it.id == docked }
    if (docks && dockedLeg != null) {
        DisposableEffect(key, dockedLeg, ui.boards[dockedLeg.id]) {
            scene.dock(key) {
                BoardCard(dockedLeg, ui.boards[dockedLeg.id], refreshing = ui.refreshing, refresh = { model.refresh(dockedLeg) }, close = { docked = null })
            }
            onDispose { scene.dock(key, null) }
        }
    }
    val open: (Leg) -> Unit = { leg -> if (docks) docked = leg.id else openBoard(leg) }
    // Folded or turned tall with a board docked: keep it in view as a page, as iOS does.
    androidx.compose.runtime.LaunchedEffect(docks, dockedLeg) {
        if (!docks && dockedLeg != null) {
            docked = null
            openBoard(dockedLeg)
        }
    }

    val title = journey?.let { stringResource(R.string.journey_title, Clock.of(it.departure), Clock.of(it.arrival), durationText(it.duration)) } ?: ""
    SheetPage(key, bar = { TopRow(title, null, journey, query, onBack) }) {
        val list = rememberLazyListState()
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "top") { TopRow(title, journey?.let { summary(it) }, journey, query, onBack) }
            when {
                ui.loading -> item(key = "loading") {
                    Text(stringResource(R.string.journey_loading), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                }
                journey == null || query == null -> item(key = "gone") {
                    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().surface(c).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.journey_gone), style = MaterialTheme.typography.titleSmall, color = c.ink)
                        Text(stringResource(R.string.journey_gone_body), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.journey_back_to_routes)) }
                    }
                }
                else -> {
                    item(key = "leave") { LeaveAndShare(journey, query, Modifier.padding(horizontal = 16.dp)) }
                    item(key = "itinerary") {
                        Itinerary(
                            journey, query, ui,
                            onFocus = { leg ->
                                focused = leg.id
                                if (docks && leg.mode.isTransit) docked = leg.id
                            },
                            onBoard = open,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    item(key = "found") { FoundBy(journey, Modifier.padding(horizontal = 20.dp)) }
                    item(key = "other") { OtherApps(query, Modifier.padding(horizontal = 16.dp)) }
                }
            }
        }
    }
}

/** "1 change · 8 min walk", with the fare. */
@Composable
private fun summary(j: Journey): String {
    val changes = if (j.changes == 0) stringResource(R.string.journey_direct) else pluralStringResource(R.plurals.journey_changes, j.changes, j.changes)
    val walk = stringResource(R.string.journey_walk_minutes, Strip.roundAway(j.walkingTime / 60))
    return j.fare?.let { stringResource(R.string.journey_summary_fare, changes, walk, it) } ?: stringResource(R.string.journey_summary, changes, walk)
}

/** The page's top row (and its bar at the low rest): up, the times, and the overflow with other apps. */
@Composable
private fun TopRow(title: String, subtitle: String?, journey: Journey?, query: JourneyQuery?, onBack: () -> Unit) {
    val c = polaris
    val context = LocalContext.current
    var menu by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Symbol(R.drawable.sym_arrow_back, stringResource(R.string.journey_back_to_routes)) }
        Column(Modifier.weight(1f)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (journey != null && query != null) {
            Box {
                IconButton(onClick = { menu = true }) { Symbol(R.drawable.sym_more_vert, stringResource(R.string.journey_more), tint = c.inkSoft) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    OpenIn.entries.forEach { app ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.journey_open_in, app.label)) },
                            leadingIcon = { Symbol(R.drawable.sym_open_in_new, null) },
                            onClick = { menu = false; launch(context, app.url(query)) },
                        )
                    }
                }
            }
        }
    }
}

/** When to leave, ticking, in the warning colour inside two minutes; and Share. Stacked at large text. */
@Composable
private fun LeaveAndShare(journey: Journey, query: JourneyQuery, modifier: Modifier) {
    val c = polaris
    val context = LocalContext.current
    val now = ticking()
    val seconds = journey.departure.epochSecond - now.epochSecond
    val minutes = Math.floorDiv(seconds, 60L).toInt()
    val leave = when {
        minutes >= 60 -> stringResource(R.string.journey_leave_at, Clock.of(journey.departure))
        minutes >= 1 -> stringResource(R.string.journey_leave_in, minutes)
        minutes >= -1 -> stringResource(R.string.journey_leave_now)
        else -> stringResource(R.string.journey_was_due, Clock.of(journey.departure))
    }
    val urgent = seconds in -599..119
    val shareText = shareText(journey, query)
    val chooser = stringResource(R.string.journey_share_with)
    val share = {
        launchIntent(context, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText), chooser))
    }
    val leaveRow: @Composable (Modifier) -> Unit = { m ->
        Row(m.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Symbol(R.drawable.sym_schedule, null, Modifier.size(18.dp), tint = if (urgent) c.warning else c.ink)
            Text(leave, style = MaterialTheme.typography.titleSmall, color = if (urgent) c.warning else c.ink)
        }
    }
    val shareButton: @Composable (Modifier) -> Unit = { m ->
        OutlinedButton(onClick = share, modifier = m.heightIn(min = 48.dp), colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = c.tint)) {
            Text(stringResource(R.string.journey_share), fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
    if (LocalDensity.current.fontScale >= 1.5f) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            leaveRow(Modifier)
            shareButton(Modifier.fillMaxWidth())
        }
    } else {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            leaveRow(Modifier.weight(1f))
            shareButton(Modifier)
        }
    }
}

@Composable
private fun shareText(journey: Journey, query: JourneyQuery): String {
    val lines = journey.transitLegs.mapNotNull { it.line?.let(::shortLineName) }
    val arriving = Clock.of(journey.arrival)
    if (lines.isEmpty()) return stringResource(R.string.journey_share_walking, query.destination.name, arriving)
    val and = stringResource(R.string.journey_list_and, "\u0000", "\u0001")
    val comma = stringResource(R.string.journey_list_comma, "\u0000", "\u0001")
    val joined = lines.dropLast(1).reduceOrNull { a, b -> comma.replace("\u0000", a).replace("\u0001", b) }
        ?.let { and.replace("\u0000", it).replace("\u0001", lines.last()) } ?: lines.last()
    return stringResource(R.string.journey_share_taking, query.destination.name, joined, arriving)
}

/** The journey leg by leg on a timeline: walks with directions, rides with their stops, platform and departures. */
@Composable
private fun Itinerary(journey: Journey, query: JourneyQuery, ui: JourneyViewModel.Ui, onFocus: (Leg) -> Unit, onBoard: (Leg) -> Unit, modifier: Modifier) {
    val c = polaris
    Column(modifier.fillMaxWidth().surface(c).padding(16.dp)) {
        journey.legs.forEachIndexed { index, leg ->
            LegRow(
                leg = leg, index = index, isLast = index == journey.legs.lastIndex,
                origin = query.origin.name, destination = query.destination.name,
                notices = journey.notices.filter { it.leg == leg.id },
                board = ui.boards[leg.id], reachable = ui.reachable(index),
                onFocus = { onFocus(leg) }, onBoard = { onBoard(leg) },
            )
        }
    }
}

@Composable
private fun LegRow(
    leg: Leg,
    index: Int,
    isLast: Boolean,
    origin: String,
    destination: String,
    notices: List<ServiceNotice>,
    board: Loadable<DepartureBoard>?,
    reachable: Instant?,
    onFocus: () -> Unit,
    onBoard: () -> Unit,
) {
    val c = polaris
    val large = LocalDensity.current.fontScale >= 1.5f
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val railColor = if (leg.mode.isTransit) lineColor(leg.lineColor, c.isNight) else c.ink.copy(alpha = 0.35f)
    val surfaceColor = c.surface
    val star = c.goldInk
    val fromName = if (index == 0 && !leg.mode.isTransit) origin else leg.fromName.ifEmpty { stringResource(R.string.journey_start) }
    val timeWidth = if (large) 0.dp else 50.dp
    val railX = timeWidth + 9.dp
    Row(
        Modifier.fillMaxWidth().drawBehind {
            // The rail: a dot at the stop, the line's colour down a ride, dots down a walk.
            val x = (if (rtl) size.width - railX.toPx() else railX.toPx())
            val top = 10.dp.toPx()
            val bottom = if (isLast) size.height - 30.dp.toPx() else size.height + 2.dp.toPx()
            if (leg.mode.isTransit) {
                drawLine(railColor, Offset(x, top), Offset(x, bottom), strokeWidth = 5.dp.toPx())
            } else {
                drawLine(railColor, Offset(x, top + 6.dp.toPx()), Offset(x, bottom), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.5f, 6.dp.toPx())))
            }
            drawCircle(surfaceColor, 6.5.dp.toPx(), Offset(x, top))
            drawCircle(railColor, 6.5.dp.toPx(), Offset(x, top), style = Stroke(2.5.dp.toPx()))
        },
    ) {
        if (!large) {
            Text(Clock.of(leg.departure), Modifier.width(timeWidth).padding(top = 1.dp), style = MaterialTheme.typography.labelLarge, color = c.ink, maxLines = 1, softWrap = false)
        }
        Spacer(Modifier.width(18.dp + 12.dp))
        Column(Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val showOnMap = stringResource(R.string.journey_show_on_map)
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = showOnMap, onClick = onFocus), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (large) "${Clock.of(leg.departure)} $fromName" else fromName, style = MaterialTheme.typography.titleSmall, color = c.ink)
                if (leg.mode.isTransit) RideTitle(leg) else WalkTitle(leg)
            }
            if (leg.mode.isTransit) {
                RideDetail(leg, notices, board, reachable, onBoard)
            } else {
                WalkDetail(leg)
            }
            if (isLast) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    FractalStar(18.dp, Modifier.padding(end = 0.dp), color = star)
                    Spacer(Modifier.width(6.dp))
                    Text(destination, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = c.ink)
                    Text(stringResource(R.string.journey_arrive, Clock.of(leg.arrival)), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun WalkTitle(leg: Leg) {
    val c = polaris
    val duration = durationText(leg.duration)
    val text = leg.distanceMeters?.takeIf { it > 0 }?.let { stringResource(R.string.journey_walk_distance, duration, distanceText(it)) } ?: stringResource(R.string.journey_walk, duration)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Symbol(R.drawable.sym_directions_walk, null, Modifier.size(16.dp), tint = c.inkSoft)
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
    }
}

/** Walking directions: Polaris has no walking router on Android, so this hands the walk to the maps app. */
@Composable
private fun WalkDetail(leg: Leg) {
    val c = polaris
    val context = LocalContext.current
    val start = leg.start
    val end = leg.end
    if (start == null || end == null || leg.duration < 60) return
    val label = stringResource(R.string.journey_directions_label)
    TextButton(
        onClick = { launch(context, "https://www.google.com/maps/dir/?api=1&travelmode=walking&origin=${start.pair}&destination=${end.pair}") },
        modifier = Modifier.heightIn(min = 48.dp).semantics { this.contentDescription = label },
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        Symbol(R.drawable.sym_directions_walk, null, Modifier.size(18.dp), tint = c.tint)
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.journey_directions), fontWeight = FontWeight.SemiBold, color = c.tint)
        Spacer(Modifier.width(4.dp))
        Symbol(R.drawable.sym_open_in_new, null, Modifier.size(14.dp), tint = c.tint)
    }
}

@Composable
private fun RideTitle(leg: Leg) {
    val c = polaris
    val direction = leg.headsign?.let { if (it.lowercase().startsWith("via")) it else stringResource(R.string.journey_towards, it) }
    // The line's name stays whole; the direction goes under it when the two don't fit on one line.
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        LinePill(leg)
        direction?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkSoft) }
    }
}

@Composable
private fun RideDetail(leg: Leg, notices: List<ServiceNotice>, board: Loadable<DepartureBoard>?, reachable: Instant?, onBoard: () -> Unit) {
    val c = polaris
    notices.forEach { n -> AlertLine(ServiceAlert(n.title, n.detail.ifEmpty { null }), minor = n.severity == ServiceNotice.Severity.minor) }
    var showStops by rememberSaveable(leg.id) { mutableStateOf(false) }
    val hasStops = leg.intermediateStops.isNotEmpty()
    val stopsLabel = stringResource(if (showStops) R.string.journey_hide_stops else R.string.journey_show_stops)
    Row(
        Modifier
            .then(if (hasStops) Modifier.clickable(role = Role.Button, onClickLabel = stopsLabel) { showStops = !showStops } else Modifier)
            .heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The platform up front, in ink: it decides where you stand.
        leg.platform?.let { p ->
            Text(
                stringResource(if (leg.mode == LegMode.bus || leg.mode == LegMode.coach) R.string.journey_stop_letter else R.string.journey_platform, p),
                Modifier.background(c.ink.copy(alpha = 0.08f), CircleShape).padding(horizontal = 7.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = c.ink, maxLines = 1, softWrap = false,
            )
        }
        Text(stopsText(leg), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        if (hasStops) {
            Symbol(R.drawable.sym_keyboard_arrow_down, null, Modifier.size(18.dp).graphicsLayer { rotationZ = if (showStops) 180f else 0f }, tint = c.inkSoft)
        }
        if (leg.isRealtime) LiveDot()
    }
    if (showStops) {
        Column(Modifier.padding(start = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            leg.intermediateStops.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkSoft) }
        }
    }
    DepartureStrip(leg, board, reachable, onOpen = onBoard)
    // The line's status, unless TfL's notice above already says it.
    if (notices.isEmpty()) board?.value?.alerts?.firstOrNull()?.let { AlertLine(it) }
}

@Composable
private fun FoundBy(journey: Journey, modifier: Modifier) {
    val c = polaris
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.journey_found_by), Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge, color = c.inkSoft)
        journey.providers.forEach { provider ->
            Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).background(c.tint, CircleShape))
                Text(provider.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = c.ink)
                Text(journey.sources.filter { it.provider == provider }.joinToString(", ") { it.variant }, style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
            }
        }
    }
}

/** "Check with another app": the same trip in Citymapper or Google Maps (iOS's `OpenInRow`). */
@Composable
private fun OtherApps(query: JourneyQuery, modifier: Modifier) {
    val c = polaris
    val context = LocalContext.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.journey_check_other), Modifier.padding(horizontal = 4.dp).semantics { heading() }, style = MaterialTheme.typography.labelLarge, color = c.inkSoft)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenIn.entries.forEach { app ->
                OutlinedButton(onClick = { launch(context, app.url(query)) }, modifier = Modifier.heightIn(min = 48.dp), colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = c.tint)) {
                    Text(app.label, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    Symbol(R.drawable.sym_open_in_new, null, Modifier.size(16.dp))
                }
            }
        }
    }
}

/** A board as a card at the foot of the map half, wide open: the rows narrow, a refresh and a close. */
@Composable
fun BoardCard(leg: Leg, state: Loadable<DepartureBoard>?, refreshing: Boolean, refresh: () -> Unit, close: () -> Unit) {
    val c = polaris
    androidx.compose.material3.Surface(shape = RoundedCornerShape(24.dp), color = c.paper, shadowElevation = 6.dp) {
        BoardContent(leg, state, refreshing, refresh, narrow = true, close = close, modifier = Modifier.heightIn(max = 360.dp))
    }
}

internal fun launch(context: android.content.Context, url: String) = launchIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

internal fun launchIntent(context: android.content.Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
