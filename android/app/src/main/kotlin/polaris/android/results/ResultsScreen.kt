package polaris.android.results

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import polaris.android.R
import polaris.android.app.AppGraph
import polaris.android.shell.LocalScene
import polaris.android.shell.LocalSheet
import polaris.android.shell.MapScene
import polaris.android.shell.MeasuredBar
import polaris.android.shell.ResultsPage
import polaris.android.shell.SceneEffect
import polaris.android.shell.SearchFor
import polaris.android.shell.ShellViewModel
import polaris.android.shell.factory
import polaris.android.ui.Clock
import polaris.android.ui.FractalStar
import polaris.android.ui.LegStrip
import polaris.android.ui.SourceBadge
import polaris.android.ui.Symbol
import polaris.android.ui.minutesText
import polaris.android.ui.polaris
import polaris.android.ui.spokenList
import polaris.android.ui.surface
import polaris.android.ui.SurfaceShape
import polaris.engine.Time
import polaris.engine.Journey
import polaris.engine.JourneyMap
import polaris.engine.JourneyQuery
import polaris.engine.Place
import polaris.engine.ProviderID
import polaris.engine.ProviderStatus
import polaris.engine.RouteFilter
import polaris.engine.RouteOption
import polaris.engine.ServiceNotice
import polaris.engine.SortOrder
import polaris.engine.TimeMode
import kotlin.math.roundToInt

/**
 * The routes for one trip (iOS's `ResultsSheet`), filling in as each source answers. The trip is
 * edited in place at the top; the time, the order and the filters sit in one row under it (on
 * the map half, docked, when the window is wide). Tapping a route shows it on the map and opens
 * its page, which for now is a placeholder: the journey page comes in the next update.
 */
@Composable
fun ResultsScreen(
    key: ResultsPage,
    graph: AppGraph,
    shell: ShellViewModel,
    openSearch: (SearchFor) -> Unit,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val initial = remember(key) { key.journeyQuery }
    if (initial == null) {
        // A key this version can't read: nothing to show but the way back.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val model: ResultsViewModel = viewModel(factory = factory { ResultsViewModel(it, graph, key.id, initial) })
    val ui by model.ui.collectAsStateWithLifecycle()
    val sheet = LocalSheet.current
    val scene = LocalScene.current
    val c = polaris
    val night = c.isNight

    // A start or end picked in the search view comes back here.
    val pick by shell.endPick.collectAsStateWithLifecycle()
    LaunchedEffect(pick) {
        val p = pick ?: return@LaunchedEffect
        if (p.page != key.id) return@LaunchedEffect
        shell.endPickTaken()
        if (p.isOrigin) model.setOrigin(p.place) else model.setDestination(p.place)
    }

    // The map: every listed route framed, the shown one in colour, the others faint.
    val content = remember(ui.ranked, ui.selectedId, night, ui.query) { sceneFor(ui, night) }
    SceneEffect(key, content)

    var showFilters by rememberSaveable { mutableStateOf(false) }
    var timeKind by rememberSaveable { mutableStateOf<TimeKind?>(null) }
    var showSources by rememberSaveable { mutableStateOf(false) }

    // Wide, sort and filters dock on the map half, beside the list they change.
    if (sheet.wide) {
        DisposableEffect(key, ui.sort, ui.filter, ui.ranked.hidden) {
            scene.dock(key) { SortAndFilterCard(ui.sort, ui.filter, ui.ranked.hidden, model::setSort, model::setFilter) }
            onDispose { scene.dock(key, null) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        MeasuredBar({ h -> sheet.reportBar(key, h) }) { Bar(ui.query, onBack) }
        if (sheet.atLowRest) {
            Bar(ui.query, onBack)
            return@Box
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "trip") {
                TripHeader(
                    ui.query, onBack,
                    edit = { isOrigin -> openSearch(SearchFor.ResultsEnd(key.id, isOrigin)) },
                    swap = model::swap,
                    searchAgain = model::searchAgain,
                )
            }
            item(key = "controls") {
                Controls(
                    ui, wide = sheet.wide,
                    pickTime = { kind -> if (kind == null) model.setTime(TimeMode.LeaveNow) else timeKind = kind },
                    setSort = model::setSort,
                    openFilters = { showFilters = true },
                )
            }
            item(key = "status") {
                Column {
                    StatusLine(ui, showSources = { showSources = true }, searchAgain = model::searchAgain)
                    // While sources are still answering: a quiet line, as well as the star's points.
                    if (ui.isSearching) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 4.dp), color = c.tint, trackColor = c.hairline)
                }
            }
            when {
                ui.noFix -> item(key = "nofix") {
                    Message("Can't find where you are", "Location is off or no fix came. Pick a start instead.", "Choose a start") {
                        openSearch(SearchFor.ResultsEnd(key.id, isOrigin = ui.query.origin.isCurrentLocation))
                    }
                }
                ui.stale -> item(key = "stale") {
                    Message("These routes weren't kept", "Android closed Polaris while it was in the background.", "Search again", model::searchAgain)
                }
            }
            val notices = ui.notices
            if (notices.isNotEmpty()) item(key = "notices") { NoticeList(notices) }
            routeCards(ui, model, onOpen)
        }
    }

    if (showFilters && !sheet.wide) FilterSheet(ui.filter, onChange = model::setFilter, onDismiss = { showFilters = false })
    timeKind?.let { kind -> TimeDialog(kind, ui.query.time, onDismiss = { timeKind = null }) { model.setTime(it); timeKind = null } }
    if (showSources) SourcesDialog(ui.statuses) { showSources = false }
}

private fun LazyListScope.routeCards(ui: ResultsViewModel.Ui, model: ResultsViewModel, onOpen: (String) -> Unit) {
    val ranked = ui.ranked
    val reference = reference(ui)
    val open: (RouteOption) -> Unit = { option ->
        model.select(option.id)
        onOpen(option.id)
    }
    items(ranked.top, key = { it.id }) { option ->
        RouteCard(option, isBest = option.id == ui.best?.id, isShown = option.id == ui.shown?.id && option.id != ui.best?.id,
            reference = reference, arriveBy = ui.query.time.isArriveBy, onClick = { open(option) })
    }
    if (ranked.more.isNotEmpty()) {
        item(key = "more") {
            TextButton(onClick = model::toggleMore, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (ui.showMore) "Show fewer" else moreLabel(ranked.more), fontWeight = FontWeight.SemiBold)
                Symbol(R.drawable.sym_keyboard_arrow_down, null, Modifier.size(20.dp).then(if (ui.showMore) Modifier.semantics { } else Modifier))
            }
        }
        if (ui.showMore) items(ranked.more, key = { "more-" + it.id }) { option ->
            RouteCard(option, isBest = false, isShown = option.id == ui.shown?.id, reference = reference, arriveBy = ui.query.time.isArriveBy, onClick = { open(option) })
        }
    }
    if (ranked.hidden > 0) item(key = "hidden") {
        HiddenNote(ranked.hidden, all = ranked.top.isEmpty() && ranked.more.isEmpty()) { model.setFilter(RouteFilter()) }
    }
    if (ranked.top.isEmpty() && ranked.more.isEmpty() && ranked.hidden == 0 && !ui.isSearching && !ui.stale && !ui.noFix) {
        item(key = "empty") { EmptyState() }
    }
}

/** What each card's "+3 min" is measured against: the earliest arrival (or, arriving by a time, the latest departure). */
private fun reference(ui: ResultsViewModel.Ui) =
    if (ui.query.time.isArriveBy) ui.ranked.top.maxOfOrNull { it.journey.departure } else ui.ranked.top.minOfOrNull { it.journey.arrival }

private fun moreLabel(more: List<RouteOption>): String {
    val blocked = more.count { it.journey.isBlocked }
    val n = more.size
    return when (blocked) {
        0 -> "$n slower or longer route${if (n == 1) "" else "s"}"
        n -> "$n route${if (n == 1) "" else "s"} through a closure"
        else -> "$n more routes, $blocked through a closure"
    }
}

/** The map for this page: every top route framed, the shown one drawn in its colours, the others faint. */
private fun sceneFor(ui: ResultsViewModel.Ui, night: Boolean): MapScene.Content {
    val shown = ui.shown ?: return MapScene.Content()
    val q = ui.query
    val drawingOf = { j: Journey -> JourneyMap.drawing(j, night, q.origin.coordinate.takeUnless { q.origin.isCurrentLocation && it.latitude == 0.0 }, q.destination.coordinate) }
    val main = drawingOf(shown.journey)
    val others = ui.ranked.top.take(4).filter { it.id != shown.id }.map { drawingOf(it.journey) }
    val boxes = (others + main).mapNotNull { it.bounds }
    val frame = if (boxes.isEmpty()) null else JourneyMap.Bounds(boxes.minOf { it.south }, boxes.minOf { it.west }, boxes.maxOf { it.north }, boxes.maxOf { it.east })
    return MapScene.Content(main, others, frame)
}

private fun placeName(p: Place) = p.name

@Composable
private fun Bar(query: JourneyQuery, onBack: () -> Unit) {
    val c = polaris
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Symbol(R.drawable.sym_arrow_back, "Back") }
        Text(
            "${placeName(query.origin)} → ${placeName(query.destination)}",
            Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The trip as the page's title, editable: up, From and To (each a button), swap, and the
 * overflow with another app. In the mock-up the trip is the title; here each end is also the
 * way to change it.
 */
@Composable
private fun TripHeader(query: JourneyQuery, onBack: () -> Unit, edit: (Boolean) -> Unit, swap: () -> Unit, searchAgain: () -> Unit) {
    val c = polaris
    val context = LocalContext.current
    var menu by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 0.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 0.dp)) { Symbol(R.drawable.sym_arrow_back, "Back") }
        Column(Modifier.weight(1f)) {
            End("From", query.origin, isStart = true) { edit(true) }
            End("To", query.destination, isStart = false) { edit(false) }
        }
        IconButton(
            onClick = swap,
            enabled = !(query.origin.isCurrentLocation && query.destination.isCurrentLocation),
        ) { Symbol(R.drawable.sym_swap_vert, "Swap start and destination", tint = c.tint) }
        Box {
            IconButton(onClick = { menu = true }) { Symbol(R.drawable.sym_more_vert, "More options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Search again") }, leadingIcon = { Symbol(R.drawable.sym_refresh, null) }, onClick = { menu = false; searchAgain() })
                HorizontalDivider()
                Text("Open this trip in", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = c.inkSoft)
                OpenIn.entries.forEach { app ->
                    DropdownMenuItem(text = { Text(app.label) }, leadingIcon = { Symbol(R.drawable.sym_open_in_new, null) }, onClick = {
                        menu = false
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.url(query)))) }
                    })
                }
            }
        }
    }
}

@Composable
private fun End(label: String, place: Place, isStart: Boolean, onClick: () -> Unit) {
    val c = polaris
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Change") { onClick() }
            .semantics(mergeDescendants = true) { contentDescription = "$label: ${place.name}" }
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
            if (isStart) Box(Modifier.size(11.dp).background(Color.White, CircleShape).padding(2.dp).background(c.here, CircleShape))
            else FractalStar(18.dp)
        }
        Text(
            place.name,
            Modifier.weight(1f),
            style = if (isStart) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
            color = if (isStart) c.inkSoft else c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

enum class TimeKind { Depart, Arrive }

/**
 * The time, the order and the filters: one row that scrolls sideways if it must (at large text),
 * never a wrapping cloud of chips. Wide, the order and filters are docked on the map instead.
 */
@Composable
private fun Controls(ui: ResultsViewModel.Ui, wide: Boolean, pickTime: (TimeKind?) -> Unit, setSort: (SortOrder) -> Unit, openFilters: () -> Unit) {
    val c = polaris
    var timeMenu by rememberSaveable { mutableStateOf(false) }
    var sortMenu by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { timeMenu = true }, colors = tinted(), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(timeLabel(ui.query.time), maxLines = 1)
                Symbol(R.drawable.sym_keyboard_arrow_down, null, Modifier.size(18.dp))
            }
            DropdownMenu(expanded = timeMenu, onDismissRequest = { timeMenu = false }) {
                DropdownMenuItem(text = { Text("Leave now") }, leadingIcon = { Symbol(R.drawable.sym_schedule, null) }, onClick = { timeMenu = false; pickTime(null) })
                DropdownMenuItem(text = { Text("Depart at…") }, leadingIcon = { Symbol(R.drawable.sym_arrow_forward, null) }, onClick = { timeMenu = false; pickTime(TimeKind.Depart) })
                DropdownMenuItem(text = { Text("Arrive by…") }, leadingIcon = { Symbol(R.drawable.sym_location_on, null) }, onClick = { timeMenu = false; pickTime(TimeKind.Arrive) })
            }
        }
        if (!wide) {
            Box {
                OutlinedButton(onClick = { sortMenu = true }, colors = tinted(), modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Sort: ${ui.sort.label}" }) {
                    Text(if (ui.sort == SortOrder.best) "Sort: Best" else ui.sort.label, maxLines = 1)
                    Symbol(R.drawable.sym_keyboard_arrow_down, null, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortOrder.entries.forEach { order ->
                        DropdownMenuItem(
                            text = { Text(order.label) },
                            leadingIcon = { RadioButton(selected = order == ui.sort, onClick = null) },
                            modifier = Modifier.semantics { selected = order == ui.sort },
                            onClick = { sortMenu = false; setSort(order) },
                        )
                    }
                }
            }
            val active = ui.filter.activeCount
            OutlinedButton(
                onClick = openFilters,
                colors = tinted(),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = if (active > 0) "Filters, $active on" else "Filters" },
            ) {
                Text("Filters", maxLines = 1)
                if (active > 0) {
                    Spacer(Modifier.width(6.dp))
                    Badge(containerColor = c.tint, contentColor = if (c.isNight) c.navy else Color.White) { Text("$active") }
                }
            }
        }
    }
}

@Composable
private fun tinted() = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = polaris.tint)

private fun timeLabel(time: TimeMode) = when (time) {
    TimeMode.LeaveNow -> "Leave now"
    is TimeMode.DepartAt -> "Depart ${Clock.of(time.at)}"
    is TimeMode.ArriveBy -> "Arrive by ${Clock.of(time.at)}"
}

/** Who has answered (the star's points fill in as each source does), what was found, and who didn't answer. */
@Composable
private fun StatusLine(ui: ResultsViewModel.Ui, showSources: () -> Unit, searchAgain: () -> Unit) {
    val c = polaris
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClickLabel = "Show sources") { showSources() }
                .heightIn(min = 48.dp)
                .padding(horizontal = 4.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FractalStar(22.dp, lit = listOf(ProviderID.google, ProviderID.tfl, ProviderID.transitous, ProviderID.apple).map { ui.statuses[it] is ProviderStatus.Done })
            Text(summary(ui), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
            Symbol(R.drawable.sym_info, null, Modifier.size(18.dp), tint = c.inkFaint)
        }
        if (!ui.isSearching && !ui.stale) {
            IconButton(onClick = searchAgain) { Symbol(R.drawable.sym_refresh, "Search again", tint = c.tint) }
        }
    }
}

/** As iOS's summary line: "3 routes so far · asking TfL", "5 routes from Google and TfL · Transitous didn't answer". */
fun summary(ui: ResultsViewModel.Ui): String {
    if (ui.locating) return "Finding where you are…"
    val routes = ui.ranked.top.size + ui.ranked.more.size
    val found = "$routes route${if (routes == 1) "" else "s"}"
    val order = ProviderID.entries
    val asking = order.filter { ui.statuses[it] == ProviderStatus.Loading }.map { it.displayName }
    if (asking.isNotEmpty()) return (if (routes > 0) "$found so far · " else "") + "asking ${spokenList(asking)}"
    val answered = order.filter { it != ProviderID.apple && ui.statuses[it] is ProviderStatus.Done }.map { it.displayName }
    val failed = order.filter { ui.statuses[it] is ProviderStatus.Failed }.map { it.displayName }
    if (ui.statuses.isEmpty()) return "Not searched"
    var text = if (answered.isEmpty()) "No source answered" else "$found from ${spokenList(answered)}"
    if (failed.isNotEmpty()) text += " · ${spokenList(failed)} didn't answer"
    return text
}

/** Who answered, who didn't, and why: iOS's sources panel. */
@Composable
private fun SourcesDialog(statuses: Map<ProviderID, ProviderStatus>, onDismiss: () -> Unit) {
    val c = polaris
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text("Done") } },
        title = { Text("Sources") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProviderID.entries.filter { it != ProviderID.apple }.forEach { id ->
                    val status = statuses[id] ?: return@forEach
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
                        Text(id.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.ink)
                        Text(
                            when (status) {
                                ProviderStatus.Loading -> "Searching…"
                                is ProviderStatus.Done -> "${status.count} found"
                                is ProviderStatus.Failed -> status.reason
                                is ProviderStatus.Off -> status.reason
                            },
                            Modifier.weight(1.4f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = when (status) {
                                is ProviderStatus.Failed -> c.warning
                                is ProviderStatus.Off -> c.inkFaint
                                else -> c.inkSoft
                            },
                        )
                    }
                }
                Text(
                    "A route several sources found shows once, with each one's badge. The star's points fill in as Google, TfL and Transitous answer.",
                    style = MaterialTheme.typography.bodySmall, color = c.inkSoft,
                )
            }
        },
    )
}

@Composable
private fun Message(title: String, body: String, action: String, onAction: () -> Unit) {
    val c = polaris
    Column(Modifier.fillMaxWidth().surface(c).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
        TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
    }
}

/**
 * What TfL reports on the lines and stations these routes use, worst first, three at a time
 * (iOS's `NoticeList`). Delays and closures in the warning colour, minor notices in grey, as on
 * iOS; tap a notice for TfL's own words.
 */
@Composable
private fun NoticeList(notices: List<ServiceNotice>) {
    val c = polaris
    var all by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(setOf<String>()) }
    val shown = if (all) notices else notices.take(3)
    val closed = notices.any { it.severity == ServiceNotice.Severity.closed }
    Column(
        Modifier.fillMaxWidth()
            .background(if (closed) c.warning.copy(alpha = 0.06f) else Color.Transparent, SurfaceShape)
            .surface(c, highlighted = false),
    ) {
        shown.forEachIndexed { index, notice ->
            if (index > 0) HorizontalDivider(Modifier.padding(start = 40.dp), color = c.hairline)
            val id = notice.title + notice.detail
            val expanded = id in open
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "Show less" else "Show TfL's full notice") { open = if (expanded) open - id else open + id }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val minor = notice.severity == ServiceNotice.Severity.minor
                Symbol(if (minor) R.drawable.sym_info else R.drawable.sym_warning, null, Modifier.size(18.dp), tint = if (minor) c.inkSoft else c.warning)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(notice.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = c.ink)
                        SourceBadge(notice.source)
                    }
                    if (notice.severity == ServiceNotice.Severity.closed) {
                        Text("Routes through it are under the others, below.", style = MaterialTheme.typography.bodySmall, color = c.warning)
                    }
                    if (notice.detail.isNotEmpty()) {
                        Text(notice.detail, style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (notices.size > 3) {
            HorizontalDivider(Modifier.padding(start = 40.dp), color = c.hairline)
            TextButton(onClick = { all = !all }, modifier = Modifier.padding(start = 28.dp).heightIn(min = 48.dp)) {
                Text(if (all) "Show fewer" else "${notices.size - 3} more reported", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * One route: when it leaves and arrives and how long, its lines in their colours, the worst
 * notice on it, the first boarding, changes, walking, fare and who found it, and when else to
 * leave on the same lines. The best route carries the gold star. One TalkBack node, spoken as
 * iOS speaks it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RouteCard(option: RouteOption, isBest: Boolean, isShown: Boolean, reference: java.time.Instant?, arriveBy: Boolean, onClick: () -> Unit) {
    val c = polaris
    val j = option.journey
    val delta = reference?.let {
        val minutes = (((if (arriveBy) it.epochSecond - j.departure.epochSecond else j.arrival.epochSecond - it.epochSecond)) / 60.0).roundToInt()
        if (minutes > 0) (if (arriveBy) "leave $minutes min earlier" else "+$minutes min") else null
    }
    val boarding = j.transitLegs.firstOrNull()?.let { ride ->
        listOfNotNull(Clock.of(ride.departure), ride.platform?.let(ride::standing), ride.fromName).joinToString(" · ")
    }
    val details = details(j)
    val spoken = listOfNotNull(
        if (isBest) "Best route" else null, j.worstNotice?.title, minutesText(j.duration),
        "leave ${Clock.of(j.departure)}, arrive ${Clock.of(j.arrival)}",
        spokenList(j.transitLegs.mapNotNull { it.line }), boarding, details,
        "found by ${spokenList(option.providers.map { it.displayName })}",
        if (option.otherDepartures.isNotEmpty()) "or leave at ${spokenList(option.otherDepartures.take(3).map { Clock.of(it) }, "or")}" else null,
        if (j.isRealtime) "live times" else null,
    ).joinToString(", ")

    Column(
        Modifier
            .fillMaxWidth()
            .surface(c, highlighted = isBest, selected = isShown)
            .clickable(role = Role.Button, onClickLabel = "Show this route") { onClick() }
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isBest) FractalStar(18.dp)
            Text("${Clock.of(j.departure)} – ${Clock.of(j.arrival)}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = c.ink)
            Column(horizontalAlignment = Alignment.End) {
                Text(minutesText(j.duration), style = MaterialTheme.typography.titleMedium, color = c.ink)
                delta?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkSoft) }
            }
        }
        LegStrip(j.legs)
        j.worstNotice?.let { notice ->
            val minor = notice.severity == ServiceNotice.Severity.minor
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol(if (minor) R.drawable.sym_info else R.drawable.sym_warning, null, Modifier.size(16.dp), tint = if (minor) c.inkSoft else c.warning)
                Text(
                    notice.title, style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (notice.severity == ServiceNotice.Severity.closed) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (minor) c.inkSoft else c.warning, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        boarding?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(details, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
            if (j.hasDisruption && j.notices.isEmpty()) Symbol(R.drawable.sym_warning, null, Modifier.size(14.dp), tint = c.warning)
            option.providers.forEach { SourceBadge(it) }
        }
        if (option.otherDepartures.isNotEmpty()) {
            Text(
                "Or leave at ${spokenList(option.otherDepartures.take(3).map { Clock.of(it) }, "or")}",
                style = MaterialTheme.typography.bodySmall, color = c.inkFaint,
            )
        }
    }
}

private fun details(j: Journey): String {
    val parts = mutableListOf(if (j.changes == 0) "Direct" else if (j.changes == 1) "1 change" else "${j.changes} changes")
    val walk = (j.walkingTime / 60).roundToInt()
    if (walk > 0) parts += "$walk min walk"
    j.fare?.let { parts += it }
    return parts.joinToString(" · ")
}

@Composable
private fun HiddenNote(count: Int, all: Boolean, clear: () -> Unit) {
    val c = polaris
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (all) "Every route is hidden by your filters" else "$count route${if (count == 1) "" else "s"} hidden by your filters",
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.inkSoft,
        )
        TextButton(onClick = clear, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear filters", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun EmptyState() {
    val c = polaris
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FractalStar(44.dp, lit = listOf(false, false, false, false))
        Text("No routes found", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() })
        Text(
            "None of the sources came back with a journey. Try another app from the menu above.",
            style = MaterialTheme.typography.bodyMedium, color = c.inkSoft, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Another app's view of the same trip: Citymapper and Google Maps (Apple Maps has no Android app). Their web links open the app when it is installed. */
enum class OpenIn(val label: String) {
    Citymapper("Citymapper"), GoogleMaps("Google Maps");

    fun url(q: JourneyQuery): String {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val from = q.origin
        val to = q.destination
        return when (this) {
            Citymapper -> buildString {
                append("https://citymapper.com/directions?endcoord=").append(enc(to.coordinate.pair)).append("&endname=").append(enc(to.name))
                if (!from.isCurrentLocation) append("&startcoord=").append(enc(from.coordinate.pair)).append("&startname=").append(enc(from.name))
                (q.time as? TimeMode.ArriveBy)?.let { append("&arrival_time=").append(enc(Time.isoString(it.at))) }
            }
            GoogleMaps -> buildString {
                append("https://www.google.com/maps/dir/?api=1&destination=").append(enc(to.coordinate.pair)).append("&travelmode=transit")
                if (!from.isCurrentLocation) append("&origin=").append(enc(from.coordinate.pair))
            }
        }
    }
}
