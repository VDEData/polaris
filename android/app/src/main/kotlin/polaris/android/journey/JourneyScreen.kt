package polaris.android.journey

import polaris.engine.Strip
import polaris.engine.TfLLines
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
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
import androidx.compose.ui.semantics.onClick
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
 * One route (iOS's `JourneySheet`): the times and fare, when to leave, Go and Share, the itinerary
 * with a departure strip under each ride, who found it, and the hand-off to another app. Go starts
 * the live trip and opens its page; while that trip runs the button returns to it. The alert bell
 * waits for alerts.
 *
 * On a phone a strip opens its board as a page; wide, the board docks at the foot of the map half
 * beside the itinerary, as iOS does on the Duo.
 */
@Composable
fun JourneyScreen(
    key: JourneyPage,
    graph: AppGraph,
    shell: polaris.android.shell.ShellViewModel,
    onBack: () -> Unit,
    openGo: () -> Unit,
    /** An alert drafted from this route's TfL lines, to check and save (the bell). */
    openAlert: (polaris.engine.RouteAlert) -> Unit = {},
    openBoard: (Leg) -> Unit,
) {
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
                BoardCard(dockedLeg, ui.boards[dockedLeg.id], refreshing = dockedLeg.id in ui.refreshing, refresh = { model.refresh(dockedLeg) }, close = { docked = null })
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

    // Go: starts the live trip for this route (or goes back to it), asking for location on the tap
    // if it hasn't been allowed (Go still runs on the timetable without it).
    val tripVersion by graph.trips.version.collectAsStateWithLifecycle()
    val following = remember(tripVersion, journey?.id) { journey?.let { graph.trips.following(it.id) } }
    // Then notifications, for the trip in the shade and its alerts (Android 13 and later ask).
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) graph.trips.trip.value?.let { trip -> graph.notifier?.let { n -> n.start(trip.query.destination.name, trip.card(), adopt = true) } }
    }
    val notificationsNeeded = { android.os.Build.VERSION.SDK_INT >= 33 && graph.notifier?.allowed == false }
    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) {
            shell.locationAllowed()
            graph.notifier?.startService()
        }
        if (notificationsNeeded()) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    val go: () -> Unit = go@{
        val j = journey ?: return@go
        val q = query ?: return@go
        if (following == null) {
            graph.trips.start(j, q)
            // Asked here, on the tap that needs them, never at launch: location first, then notifications.
            if (!graph.locator.hasPermission) askLocation.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
            else if (notificationsNeeded()) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        openGo()
    }

    // Once for the page, whichever layout shows the countdown (a fold or a turn changes it).
    journey?.let { val (leave, urgent) = leaveText(it); UrgencyAnnouncement(leave, urgent) }

    // The bell: an alert for this route's TfL lines, each from where you get on to where you get
    // off. Only with an alerts server, a line TfL may report on, and no trip running on this route.
    val alertsState by graph.alerts.state.collectAsStateWithLifecycle()
    var draftingAlert by remember { mutableStateOf(false) }
    val alertScope = androidx.compose.runtime.rememberCoroutineScope()
    val alertContext = LocalContext.current
    val bell: (() -> Unit)? = if (journey == null || following != null || !alertsState.configured || !TfLLines.mayHaveLines(journey)) null else ({
        if (!draftingAlert) alertScope.launch {
            draftingAlert = true
            try {
                val draft = graph.lines.draft(journey)
                if (draft != null) openAlert(draft)
                else android.widget.Toast.makeText(alertContext, R.string.journey_alert_none, android.widget.Toast.LENGTH_LONG).show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.widget.Toast.makeText(alertContext, R.string.journey_alert_failed, android.widget.Toast.LENGTH_LONG).show()
            } finally {
                draftingAlert = false
            }
        }
    })

    val title = journey?.let { stringResource(R.string.journey_title, Clock.of(it.departure), Clock.of(it.arrival), durationText(it.duration)) } ?: ""
    SheetPage(key, bar = { TopRow(title, null, journey, query, onBack, bell = bell, bellBusy = draftingAlert) }) {
        val list = rememberLazyListState()
        // After Android stopped the app the list comes back before its route does (read from the
        // archive), so the saved place would be clamped to the top: keep it, and go there once
        // the route is in.
        var savedPlace by rememberSaveable { mutableStateOf<List<Int>?>(null) }
        val loaded by androidx.compose.runtime.rememberUpdatedState(journey != null)
        androidx.compose.runtime.LaunchedEffect(list) {
            androidx.compose.runtime.snapshotFlow { listOf(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }
                .collect { if (loaded) savedPlace = it }
        }
        // Restored once every ride's board has answered (or after 3 s), so the strips have their
        // full height and the same offset lands on the same place.
        val boardsIn = journey != null && journey.transitLegs.all { ui.boards[it.id] is Loadable.Loaded || ui.boards[it.id] is Loadable.Failed }
        var restored by rememberSaveable { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(journey != null, boardsIn) {
            val place = savedPlace
            if (journey == null || restored || place == null || place[0] == 0) return@LaunchedEffect
            if (!boardsIn) kotlinx.coroutines.delay(3_000)
            if (!restored && list.firstVisibleItemIndex == 0) list.scrollToItem(place[0], place[1])
            restored = true
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(bottom = 24.dp), // the sheet stops its pages above the navigation bar
            verticalArrangement = Arrangement.spacedBy(if (layout.short) 6.dp else 14.dp),
        ) {
            // A short window (a phone on its side): when to leave joins the subtitle and Share goes
            // to the overflow, so the first ride and its strip are in the first screenful.
            item(key = "top") {
                val sub = journey?.let { summary(it) }
                if (layout.short && journey != null && query != null) {
                    val (leave, _) = leaveText(journey)
                    TopRow(title, sub?.let { stringResource(R.string.journey_summary, leave, it) }, journey, query, onBack, share = shareAction(journey, query), bell = bell, bellBusy = draftingAlert)
                } else TopRow(title, sub, journey, query, onBack, bell = bell, bellBusy = draftingAlert)
            }
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
                    item(key = "leave") { LeaveAndShare(journey, query, following != null, go, short = layout.short, modifier = Modifier.padding(horizontal = 16.dp)) }
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
private fun TopRow(title: String, subtitle: String?, journey: Journey?, query: JourneyQuery?, onBack: () -> Unit, share: (() -> Unit)? = null, bell: (() -> Unit)? = null, bellBusy: Boolean = false) {
    val c = polaris
    val context = LocalContext.current
    var menu by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Symbol(R.drawable.sym_arrow_back, stringResource(R.string.journey_back_to_routes)) }
        Column(Modifier.weight(1f)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        bell?.let { make ->
            // One 48 dp slot, whether it holds the bell or the wait for TfL's lines.
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (bellBusy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else BellButton(make)
            }
        }
        if (journey != null && query != null) {
            Box {
                IconButton(onClick = { menu = true }) { Symbol(R.drawable.sym_more_vert, stringResource(R.string.journey_more), tint = c.inkSoft) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    share?.let { go -> DropdownMenuItem(text = { Text(stringResource(R.string.journey_share)) }, onClick = { menu = false; go() }) }
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

/** "Leave in 4 min", ticking, and whether it is inside two minutes (the warning colour). */
@Composable
private fun leaveText(journey: Journey): Pair<String, Boolean> {
    val now = ticking()
    val state = leaveState(now, journey.departure)
    val leave = when (state.kind) {
        LeaveKind.at -> stringResource(R.string.journey_leave_at, Clock.of(journey.departure))
        LeaveKind.inMinutes -> stringResource(R.string.journey_leave_in, state.minutes)
        LeaveKind.now -> stringResource(R.string.journey_leave_now)
        LeaveKind.wasDue -> stringResource(R.string.journey_was_due, Clock.of(journey.departure))
    }
    return leave to state.urgent
}

internal enum class LeaveKind { at, inMinutes, now, wasDue }
internal data class LeaveState(val kind: LeaveKind, val minutes: Int, val urgent: Boolean)

/**
 * When to leave, in minutes as the clock times beside it show them (`Strip.shownMinutes`): at
 * 12:00:50 a 12:04 departure is "in 4 min", never a minute short of what the two clocks say.
 */
internal fun leaveState(now: java.time.Instant, departure: java.time.Instant): LeaveState {
    val seconds = departure.epochSecond - now.epochSecond
    val minutes = Strip.shownMinutes(now, departure)
    val kind = when {
        minutes >= 60 -> LeaveKind.at
        minutes >= 1 -> LeaveKind.inMinutes
        minutes >= -1 -> LeaveKind.now
        else -> LeaveKind.wasDue
    }
    return LeaveState(kind, minutes, seconds in -599..119)
}

/**
 * TalkBack hears the countdown once, when it crosses into the last two minutes, not every
 * minute (the text itself is read when the person reaches it).
 */
@Composable
private fun UrgencyAnnouncement(leave: String, urgent: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    // What it was when the page last looked: only a change from not urgent to urgent speaks, so a
    // journey opened inside two minutes says nothing extra (its text is read as usual), and a
    // rotation or fold, which keeps this saved value, doesn't speak again.
    var wasUrgent by rememberSaveable { mutableStateOf<Boolean?>(null) }
    androidx.compose.runtime.LaunchedEffect(urgent) {
        if (wasUrgent == false && urgent) {
            @Suppress("DEPRECATION") view.announceForAccessibility(leave)
        }
        wasUrgent = urgent
    }
}

@Composable
private fun shareAction(journey: Journey, query: JourneyQuery): () -> Unit {
    val context = LocalContext.current
    val shareText = shareText(journey, query)
    val chooser = stringResource(R.string.journey_share_with)
    return { launchIntent(context, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText), chooser)) }
}

/**
 * When to leave, ticking, in the warning colour inside two minutes; then Go, the one filled gold
 * button, beside Share (as the mock-up places them). Stacked at large text. On a short window when
 * to leave is in the subtitle and Share in the overflow, so only Go is here.
 */
@Composable
private fun LeaveAndShare(journey: Journey, query: JourneyQuery, live: Boolean, go: () -> Unit, short: Boolean, modifier: Modifier) {
    val c = polaris
    val (leave, urgent) = leaveText(journey)
    val share = shareAction(journey, query)
    val large = LocalDensity.current.fontScale >= 1.5f
    val leaveRow: @Composable (Modifier) -> Unit = { m ->
        Row(m.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Symbol(R.drawable.sym_schedule, null, Modifier.size(18.dp), tint = if (urgent) c.warning else c.ink)
            Text(leave, style = MaterialTheme.typography.titleSmall, color = if (urgent) c.warning else c.ink)
        }
    }
    val goButton: @Composable (Modifier) -> Unit = { m ->
        val hint = stringResource(R.string.go_hint)
        androidx.compose.material3.Button(
            onClick = go,
            modifier = m.heightIn(min = 48.dp).semantics { onClick(label = hint) { go(); true } },
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = c.gold, contentColor = c.navy),
        ) {
            Symbol(R.drawable.go_arrow, null, Modifier.size(18.dp), tint = c.navy)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(if (live) R.string.go_back_to_trip else R.string.go_start), fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
    val shareButton: @Composable (Modifier) -> Unit = { m ->
        OutlinedButton(onClick = share, modifier = m.heightIn(min = 48.dp), colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = c.tint)) {
            Text(stringResource(R.string.journey_share), fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (short) 4.dp else 10.dp)) {
        if (!short) leaveRow(Modifier)
        when {
            short -> goButton(Modifier.fillMaxWidth())
            large -> {
                goButton(Modifier.fillMaxWidth())
                shareButton(Modifier.fillMaxWidth())
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                goButton(Modifier.weight(1f))
                shareButton(Modifier)
            }
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
                if (leg.mode.isTransit) RideTitle(leg)
            }
            if (leg.mode.isTransit) {
                RideDetail(leg, notices, board, reachable, onBoard)
            } else {
                // The walk and its directions on one line where they fit.
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    WalkTitle(leg)
                    WalkDetail(leg)
                }
            }
            if (isLast) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    // The star sits on the rail, where the line ends.
                    Box(Modifier.width(0.dp)) {
                        // Centred on the rail: 21 dp before where the names start.
                        FractalStar(18.dp, Modifier.wrapContentWidth(unbounded = true).offset(x = (-21).dp), color = star)
                    }
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
    // Wraps at large text rather than splitting "10 min" or losing the chevron; a 48 dp target.
    androidx.compose.foundation.layout.FlowRow(
        Modifier
            .then(if (hasStops) Modifier.clickable(role = Role.Button, onClickLabel = stopsLabel) { showStops = !showStops } else Modifier)
            .heightIn(min = 48.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.Center,
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
        // Rows in their one-line form (the card is wide enough), tall enough for yours and a few around it.
        BoardContent(leg, state, refreshing, refresh, narrow = false, close = close, modifier = Modifier.heightIn(max = 480.dp), docked = true)
    }
}

internal fun launch(context: android.content.Context, url: String) = launchIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

/** Hands off to another app; when none can take it, says so rather than doing nothing. */
internal fun launchIntent(context: android.content.Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, context.getString(R.string.journey_no_app), android.widget.Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        android.widget.Toast.makeText(context, context.getString(R.string.journey_no_app), android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** The bell: what it does is its description for TalkBack and, on a long press or a pointer over it, a tooltip in words. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BellButton(onClick: () -> Unit) {
    val label = stringResource(R.string.journey_alert)
    androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = androidx.compose.material3.rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) { Symbol(R.drawable.sym_notifications, label, tint = polaris.inkSoft) }
    }
}
