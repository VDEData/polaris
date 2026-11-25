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
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import polaris.android.R
import polaris.android.app.AppGraph
import polaris.android.shell.LocalScene
import polaris.android.shell.LocalSheet
import polaris.android.shell.Rest
import androidx.compose.ui.text.withLink
import polaris.android.shell.LocalWindowLayout
import polaris.android.shell.MapScene
import polaris.android.shell.MeasuredBar
import polaris.android.shell.SheetPage
import polaris.android.shell.ResultsPage
import polaris.android.shell.SceneEffect
import polaris.android.shell.SearchFor
import polaris.android.shell.ShellViewModel
import polaris.android.shell.factory
import polaris.android.ui.HalfDialog
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
    // Docked only where the map half has the height for it; a short landscape window keeps them in the card.
    val layout = LocalWindowLayout.current
    val docks = sheet.wide && !layout.short
    val large = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f
    val belowFull = sheet.state?.rest != Rest.Full
    if (docks) {
        DisposableEffect(key, ui.sort, ui.filter, ui.ranked.hidden) {
            scene.dock(key) { SortAndFilterCard(ui.sort, ui.filter, ui.ranked.hidden, model::setSort, model::setFilter) }
            onDispose { scene.dock(key, null) }
        }
    }

    SheetPage(key, bar = { Bar(ui.query, onBack) }) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            // Tighter on a short window, so the whole first route fits beside the map.
            verticalArrangement = Arrangement.spacedBy(if (layout.short) 6.dp else 12.dp),
        ) {
            item(key = "trip") {
                TripHeader(
                    ui.query, ui.resolved, compact = layout.short, onBack = onBack,
                    edit = { isOrigin -> openSearch(SearchFor.ResultsEnd(key.id, isOrigin, near = otherEnd(ui.query, isOrigin))) },
                    swap = model::swap,
                    searchAgain = model::searchAgain,
                )
            }
            item(key = "controls") {
                Controls(
                    ui, wide = docks,
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
                    Message(stringResource(R.string.location_no_fix_title), stringResource(R.string.results_no_fix_body), stringResource(R.string.location_choose_start)) {
                        val isOrigin = ui.query.origin.isCurrentLocation
                        openSearch(SearchFor.ResultsEnd(key.id, isOrigin, near = otherEnd(ui.query, isOrigin)))
                    }
                }
                ui.stale -> item(key = "stale") {
                    Message(stringResource(R.string.results_stale_title), stringResource(R.string.results_stale_body), stringResource(R.string.common_search_again), model::searchAgain)
                }
                ui.old -> item(key = "old") {
                    Message(stringResource(R.string.results_old_title, ui.restoredFrom?.let { Clock.of(it) }.toString()), stringResource(R.string.results_old_body), stringResource(R.string.common_search_again), model::searchAgain)
                }
            }
            val notices = ui.notices
            // One summary row (worst notice, "+N") wherever a list would push the first route out of
            // the first screenful: two or more notices below full height, large text, or a short window.
            val summarise = layout.short || (notices.size > 1 && (large || (belowFull && !docks)))
            if (notices.isNotEmpty()) item(key = "notices") { NoticeList(notices, summarised = summarise) }
            routeCards(ui, model, onOpen)
        }
    }

    if (showFilters && !docks) FilterSheet(ui.filter, onChange = model::setFilter, onDismiss = { showFilters = false })
    timeKind?.let { kind -> TimeDialog(kind, ui.query.time, onDismiss = { timeKind = null }) { model.setTime(it); timeKind = null } }
    if (showSources) SourcesDialog(ui.statuses, ui.ranked.top.size + ui.ranked.more.size) { showSources = false }
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
                Text(if (ui.showMore) stringResource(R.string.common_show_fewer) else moreLabel(ranked.more), fontWeight = FontWeight.SemiBold)
                Symbol(R.drawable.sym_keyboard_arrow_down, null, Modifier.size(20.dp).graphicsLayer { rotationZ = if (ui.showMore) 180f else 0f })
            }
        }
        if (ui.showMore) items(ranked.more, key = { "more-" + it.id }) { option ->
            RouteCard(option, isBest = false, isShown = option.id == ui.shown?.id, reference = reference, arriveBy = ui.query.time.isArriveBy, onClick = { open(option) })
        }
    }
    if (ranked.hidden > 0) item(key = "hidden") {
        HiddenNote(ranked.hidden, all = ranked.top.isEmpty() && ranked.more.isEmpty()) { model.setFilter(RouteFilter()) }
    }
    if (ranked.top.isEmpty() && ranked.more.isEmpty() && (ui.isSearching || ui.restoring)) item(key = "waiting") {
        Text(
            if (ui.restoring) stringResource(R.string.results_restoring) else stringResource(R.string.results_waiting),
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium, color = polaris.inkSoft,
        )
    }
    if (ranked.top.isEmpty() && ranked.more.isEmpty() && ranked.hidden == 0 && !ui.isSearching && !ui.stale && !ui.noFix && !ui.restoring) {
        item(key = "empty") { EmptyState() }
    }
}

/** What each card's "+3 min" is measured against: the earliest arrival (or, arriving by a time, the latest departure). */
private fun reference(ui: ResultsViewModel.Ui) =
    if (ui.query.time.isArriveBy) ui.ranked.top.maxOfOrNull { it.journey.departure } else ui.ranked.top.minOfOrNull { it.journey.arrival }

@Composable
private fun moreLabel(more: List<RouteOption>): String {
    val blocked = more.count { it.journey.isBlocked }
    val n = more.size
    return when (blocked) {
        0 -> pluralStringResource(R.plurals.results_more_slower, n, n)
        n -> pluralStringResource(R.plurals.results_more_closed, n, n)
        else -> stringResource(R.string.results_more_mixed, n, blocked)
    }
}

/** The map for this page: every top route framed, the shown one drawn in its colours, the others faint. */
private fun sceneFor(ui: ResultsViewModel.Ui, night: Boolean): MapScene.Content {
    val shown = ui.shown ?: return MapScene.Content()
    // Current Location's saved coordinate is a placeholder: only a resolved fix is drawn.
    val q = ui.resolved ?: ui.query
    val known = { p: Place -> if (!p.isCurrentLocation || ui.resolved != null) p.coordinate else null }
    val drawingOf = { j: Journey -> JourneyMap.drawing(j, night, known(q.origin), known(q.destination)) }
    val main = drawingOf(shown.journey)
    val others = ui.ranked.top.take(4).filter { it.id != shown.id }.map { drawingOf(it.journey) }
    val boxes = (others + main).mapNotNull { it.bounds }
    val frame = if (boxes.isEmpty()) null else JourneyMap.Bounds(boxes.minOf { it.south }, boxes.minOf { it.west }, boxes.maxOf { it.north }, boxes.maxOf { it.east })
    return MapScene.Content(main, others, frame)
}

private fun placeName(p: Place) = p.name

/** The other end of the trip as "lat,lon", to bias a search for this end; nothing for Current Location. */
private fun otherEnd(q: JourneyQuery, isOrigin: Boolean): String? = (if (isOrigin) q.destination else q.origin).takeUnless { it.isCurrentLocation }?.coordinate?.pair

@Composable
private fun Bar(query: JourneyQuery, onBack: () -> Unit) {
    val c = polaris
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Symbol(R.drawable.sym_arrow_back, stringResource(R.string.common_back)) }
        Text(
            stringResource(R.string.results_trip_title, placeName(query.origin), placeName(query.destination)),
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
private fun TripHeader(query: JourneyQuery, resolved: JourneyQuery?, compact: Boolean, onBack: () -> Unit, edit: (Boolean) -> Unit, swap: () -> Unit, searchAgain: () -> Unit) {
    val c = polaris
    val context = LocalContext.current
    var menu by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 0.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 0.dp)) { Symbol(R.drawable.sym_arrow_back, stringResource(R.string.common_back)) }
        // A short window (a phone on its side): from and to on one row, as iOS's compact trip editor,
        // so the first route card fits under it.
        if (compact) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { End(stringResource(R.string.trip_from), query.origin, isStart = true, oneLine = true) { edit(true) } }
            Symbol(R.drawable.sym_arrow_forward, null, Modifier.size(16.dp).padding(horizontal = 2.dp), tint = c.inkFaint)
            Box(Modifier.weight(1f)) { End(stringResource(R.string.trip_to), query.destination, isStart = false, oneLine = true) { edit(false) } }
        } else Column(Modifier.weight(1f)) {
            End(stringResource(R.string.trip_from), query.origin, isStart = true) { edit(true) }
            End(stringResource(R.string.trip_to), query.destination, isStart = false) { edit(false) }
        }
        IconButton(
            onClick = swap,
            enabled = !(query.origin.isCurrentLocation && query.destination.isCurrentLocation),
        ) { Symbol(R.drawable.sym_swap_vert, stringResource(R.string.results_swap), tint = c.tint) }
        Box {
            IconButton(onClick = { menu = true }) { Symbol(R.drawable.sym_more_vert, stringResource(R.string.common_more_options)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.common_search_again)) }, leadingIcon = { Symbol(R.drawable.sym_refresh, null) }, onClick = { menu = false; searchAgain() })
                HorizontalDivider()
                Text(stringResource(R.string.results_open_trip_in), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = c.inkSoft)
                OpenIn.entries.forEach { app ->
                    DropdownMenuItem(text = { Text(app.label) }, leadingIcon = { Symbol(R.drawable.sym_open_in_new, null) }, onClick = {
                        menu = false
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.url(query, resolved)))) }
                    })
                }
            }
        }
    }
}

@Composable
private fun End(label: String, place: Place, isStart: Boolean, oneLine: Boolean = false, onClick: () -> Unit) {
    val c = polaris
    val description = stringResource(R.string.common_label_value, label, place.name)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.results_change)) { onClick() }
            .semantics(mergeDescendants = true) { contentDescription = description }
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
            color = if (isStart) c.inkSoft else c.ink, maxLines = if (oneLine) 1 else 2, overflow = TextOverflow.Ellipsis,
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
    // Wraps onto a second line rather than hiding a control off the edge (long sort names, large text).
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { timeMenu = true }, colors = tinted(), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(timeLabel(ui.query.time), maxLines = 1)
                Symbol(R.drawable.sym_keyboard_arrow_down, null, Modifier.size(18.dp))
            }
            DropdownMenu(expanded = timeMenu, onDismissRequest = { timeMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.results_leave_now)) }, leadingIcon = { Symbol(R.drawable.sym_schedule, null) }, onClick = { timeMenu = false; pickTime(null) })
                DropdownMenuItem(text = { Text(stringResource(R.string.results_depart_at_menu)) }, leadingIcon = { Symbol(R.drawable.sym_arrow_forward, null) }, onClick = { timeMenu = false; pickTime(TimeKind.Depart) })
                DropdownMenuItem(text = { Text(stringResource(R.string.results_arrive_by_menu)) }, leadingIcon = { Symbol(R.drawable.sym_location_on, null) }, onClick = { timeMenu = false; pickTime(TimeKind.Arrive) })
            }
        }
        if (!wide) {
            val sortLabel = stringResource(R.string.results_sort, ui.sort.label)
            Box {
                OutlinedButton(onClick = { sortMenu = true }, colors = tinted(), modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = sortLabel }) {
                    Text(sortLabel, maxLines = 1)
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
            val filtersLabel = stringResource(R.string.results_filters)
            val filtersDescription = if (active > 0) stringResource(R.string.results_filters_on, active) else filtersLabel
            OutlinedButton(
                onClick = openFilters,
                colors = tinted(),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = filtersDescription },
            ) {
                Text(filtersLabel, maxLines = 1)
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

@Composable
private fun timeLabel(time: TimeMode) = when (time) {
    TimeMode.LeaveNow -> stringResource(R.string.results_leave_now)
    is TimeMode.DepartAt -> stringResource(R.string.results_depart_time, Clock.of(time.at))
    is TimeMode.ArriveBy -> stringResource(R.string.results_arrive_by_time, Clock.of(time.at))
}

/** Who has answered (the star's points fill in as each source does), what was found, and who didn't answer. */
@Composable
private fun StatusLine(ui: ResultsViewModel.Ui, showSources: () -> Unit, searchAgain: () -> Unit) {
    val c = polaris
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.results_show_sources)) { showSources() }
                .heightIn(min = 48.dp)
                .padding(horizontal = 4.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FractalStar(22.dp, lit = listOf(ProviderID.google, ProviderID.tfl, ProviderID.transitous, ProviderID.apple).map { ui.statuses[it] is ProviderStatus.Done })
            Text(summary(ui, rememberSummaryWords()), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
            Symbol(R.drawable.sym_info, null, Modifier.size(18.dp), tint = c.inkFaint)
        }
        if (!ui.isSearching && !ui.stale) {
            IconButton(onClick = searchAgain) { Symbol(R.drawable.sym_refresh, stringResource(R.string.common_search_again), tint = c.tint) }
        }
    }
}

/** As iOS's summary line: "3 routes so far · asking TfL", "5 routes from Google and TfL · Transitous didn't answer". */
fun summary(ui: ResultsViewModel.Ui, words: SummaryWords = SummaryWords.English): String {
    if (ui.locating) return words.finding
    val routes = ui.ranked.top.size + ui.ranked.more.size
    val found = words.routes(routes)
    val order = ProviderID.entries
    val asking = order.filter { ui.statuses[it] == ProviderStatus.Loading }.map { it.displayName }
    if (asking.isNotEmpty()) return if (routes > 0) words.soFarAsking(found, spokenList(asking, words.and)) else words.asking(spokenList(asking, words.and))
    val answered = order.filter { it != ProviderID.apple && ui.statuses[it] is ProviderStatus.Done }.map { it.displayName }
    val failed = order.filter { ui.statuses[it] is ProviderStatus.Failed }.map { it.displayName }
    if (ui.statuses.isEmpty()) return words.notSearched
    var text = if (answered.isEmpty()) words.noSourceAnswered else words.foundFrom(found, spokenList(answered, words.and))
    if (failed.isNotEmpty()) text = words.didntAnswer(text, spokenList(failed, words.and))
    return text
}

/** The words [summary] is made of. The screen's come from strings.xml ([rememberSummaryWords]). */
interface SummaryWords {
    val finding: String
    /** The word before a list's last item. */
    val and: String
    fun routes(count: Int): String
    fun soFarAsking(found: String, sources: String): String
    fun asking(sources: String): String
    val notSearched: String
    val noSourceAnswered: String
    fun foundFrom(found: String, sources: String): String
    fun didntAnswer(text: String, sources: String): String

    /** For code with no resources (the JVM tests): the only place [summary]'s English stays in code, kept the same as strings.xml. */
    object English : SummaryWords {
        override val finding = "Finding where you are…"
        override val and = "and"
        override fun routes(count: Int) = "$count route${if (count == 1) "" else "s"}"
        override fun soFarAsking(found: String, sources: String) = "$found so far · asking $sources"
        override fun asking(sources: String) = "Asking $sources"
        override val notSearched = "Not searched"
        override val noSourceAnswered = "No source answered"
        override fun foundFrom(found: String, sources: String) = "$found from $sources"
        override fun didntAnswer(text: String, sources: String) = "$text · $sources didn't answer"
    }
}

/** [SummaryWords] from the app's resources, in the phone's language. */
@Composable
fun rememberSummaryWords(): SummaryWords {
    val resources = LocalResources.current
    return remember(resources) {
        object : SummaryWords {
            override val finding get() = resources.getString(R.string.common_finding_you)
            override val and get() = resources.getString(R.string.list_and)
            override fun routes(count: Int) = resources.getQuantityString(R.plurals.summary_routes, count, count.toString())
            override fun soFarAsking(found: String, sources: String) = resources.getString(R.string.summary_so_far_asking, found, sources)
            override fun asking(sources: String) = resources.getString(R.string.summary_asking, sources)
            override val notSearched get() = resources.getString(R.string.summary_not_searched)
            override val noSourceAnswered get() = resources.getString(R.string.summary_no_source)
            override fun foundFrom(found: String, sources: String) = resources.getString(R.string.summary_found_from, found, sources)
            override fun didntAnswer(text: String, sources: String) = resources.getString(R.string.summary_didnt_answer, text, sources)
        }
    }
}

/** Who answered, who didn't, and why: iOS's sources panel. */
@Composable
private fun SourcesDialog(statuses: Map<ProviderID, ProviderStatus>, routes: Int, onDismiss: () -> Unit) {
    val c = polaris
    HalfDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.sources_title),
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_done)) } },
    ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProviderID.entries.filter { it != ProviderID.apple }.forEach { id ->
                    val status = statuses[id] ?: return@forEach
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
                        Text(id.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.ink)
                        Text(
                            when (status) {
                                ProviderStatus.Loading -> stringResource(R.string.common_searching)
                                is ProviderStatus.Done -> pluralStringResource(R.plurals.sources_journeys, status.count, status.count)
                                is ProviderStatus.Failed -> plainFailure(status.reason)
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
                    pluralStringResource(R.plurals.sources_note, routes, routes),
                    style = MaterialTheme.typography.bodySmall, color = c.inkSoft,
                )
                DataCredits()
            }
    }
}

/** What TfL's and Transitous's terms ask apps using their data to show, with the links they ask for. */
@Composable
fun DataCredits(modifier: Modifier = Modifier) {
    val c = polaris
    val link = androidx.compose.ui.text.TextLinkStyles(androidx.compose.ui.text.SpanStyle(color = c.tint))
    fun url(text: String, href: String) = androidx.compose.ui.text.LinkAnnotation.Url(href, link)
    val tfl = stringResource(R.string.sources_credits_tfl)
    val timetablesFrom = stringResource(R.string.sources_credits_timetables_from)
    val withCredit = stringResource(R.string.sources_credits_with)
    val osm = stringResource(R.string.sources_credits_osm)
    val credits = androidx.compose.ui.text.buildAnnotatedString {
        append(tfl)
        append(timetablesFrom)
        withLink(url("Transitous", "https://transitous.org/sources")) { append("Transitous") }
        append(" (")
        withLink(url("sources", "https://transitous.org/sources")) { append("transitous.org/sources") }
        append(withCredit)
        withLink(url("OpenStreetMap", "https://www.openstreetmap.org/copyright")) { append(osm) }
        append(".")
    }
    Text(credits, modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
}

/** "HTTP 503" means little to anyone: say it plainly. Offline and timed out already read well. */
@Composable
fun plainFailure(reason: String): String = when {
    reason.startsWith("HTTP") -> stringResource(R.string.sources_didnt_answer)
    reason == "Interrupted" -> stringResource(R.string.sources_interrupted)
    else -> reason
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
private fun NoticeList(notices: List<ServiceNotice>, summarised: Boolean = false) {
    val c = polaris
    var all by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(setOf<String>()) }
    val closed = notices.any { it.severity == ServiceNotice.Severity.closed }
    // Where height is short (a phone on its side): one row, the worst notice and how many more,
    // until tapped, so the first route stays in view (iOS's summarised notices).
    if (summarised && !all) {
        val worst = notices.first()
        val minor = worst.severity == ServiceNotice.Severity.minor
        val description = if (notices.size > 1) pluralStringResource(R.plurals.notices_summary_description, notices.size - 1, worst.title, notices.size - 1) else worst.title
        Row(
            Modifier.fillMaxWidth().surface(c)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.notices_show)) { all = true }
                .semantics(mergeDescendants = true) { contentDescription = description }
                .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Symbol(if (minor) R.drawable.sym_info else R.drawable.sym_warning, null, Modifier.size(18.dp), tint = if (minor) c.inkSoft else c.warning)
            Text(worst.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (notices.size > 1) Text(stringResource(R.string.notices_plus, notices.size - 1), style = MaterialTheme.typography.labelMedium, color = c.inkSoft)
        }
        return
    }
    val shown = if (all) notices else notices.take(3)
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
                    .clickable(role = Role.Button, onClickLabel = if (expanded) stringResource(R.string.notices_show_less) else stringResource(R.string.notices_show_full)) { open = if (expanded) open - id else open + id }
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
                        Text(stringResource(R.string.notices_closed_note), style = MaterialTheme.typography.bodySmall, color = c.warning)
                    }
                    if (notice.detail.isNotEmpty()) {
                        Text(notice.detail, style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                        // A visible cue that TfL's full words are a tap away.
                        Text(if (expanded) stringResource(R.string.notices_show_less) else stringResource(R.string.notices_read_tfl), style = MaterialTheme.typography.labelMedium, color = c.tint)
                    }
                }
            }
        }
        if (notices.size > 3) {
            HorizontalDivider(Modifier.padding(start = 40.dp), color = c.hairline)
            TextButton(onClick = { all = !all }, modifier = Modifier.padding(start = 28.dp).heightIn(min = 48.dp)) {
                Text(if (all) stringResource(R.string.common_show_fewer) else pluralStringResource(R.plurals.notices_more_reported, notices.size - 3, notices.size - 3), fontWeight = FontWeight.SemiBold)
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
        if (minutes > 0) (if (arriveBy) stringResource(R.string.route_leave_earlier, minutes) else stringResource(R.string.route_later, minutes)) else null
    }
    val boarding = j.transitLegs.firstOrNull()?.let { ride ->
        listOfNotNull(Clock.of(ride.departure), ride.platform?.let(ride::standing), ride.fromName).joinToString(" · ")
    }
    val details = details(j)
    val and = stringResource(R.string.list_and)
    val or = stringResource(R.string.list_or)
    val otherTimes = spokenList(option.otherDepartures.take(3).map { Clock.of(it) }, or)
    val spoken = listOfNotNull(
        if (isBest) stringResource(R.string.route_best) else null, j.worstNotice?.title, minutesText(j.duration),
        stringResource(R.string.route_leave_arrive, Clock.of(j.departure), Clock.of(j.arrival)),
        spokenList(j.transitLegs.mapNotNull { it.line }, and), boarding, details,
        stringResource(R.string.route_found_by, spokenList(option.providers.map { it.displayName }, and)),
        if (option.otherDepartures.isNotEmpty()) stringResource(R.string.route_or_leave_at_spoken, otherTimes) else null,
        if (j.isRealtime) stringResource(R.string.route_live_times) else null,
    ).joinToString(", ")

    Column(
        Modifier
            .fillMaxWidth()
            .surface(c, highlighted = isBest, selected = isShown)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.route_show)) { onClick() }
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(horizontal = 16.dp, vertical = if (LocalWindowLayout.current.short) 10.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The times stay whole; at large text the duration moves under them rather than splitting them.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isBest) FractalStar(18.dp)
                Text(stringResource(R.string.route_time_range, Clock.of(j.departure), Clock.of(j.arrival)), style = MaterialTheme.typography.titleMedium, color = c.ink, softWrap = false)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(minutesText(j.duration).replace(' ', '\u00A0'), style = MaterialTheme.typography.titleMedium, color = c.ink, softWrap = false)
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
            if (j.hasDisruption && j.notices.isEmpty()) {
                // The source said something is wrong on this route without saying what.
                Symbol(R.drawable.sym_warning, null, Modifier.size(14.dp), tint = c.warning)
                Text(stringResource(R.string.route_disruption), style = MaterialTheme.typography.bodySmall, color = c.warning)
            }
            option.providers.forEach { SourceBadge(it) }
        }
        if (option.otherDepartures.isNotEmpty()) {
            Text(
                stringResource(R.string.route_or_leave_at, otherTimes),
                style = MaterialTheme.typography.bodySmall, color = c.inkFaint,
            )
        }
    }
}

@Composable
private fun details(j: Journey): String {
    val parts = mutableListOf(if (j.changes == 0) stringResource(R.string.route_direct) else pluralStringResource(R.plurals.route_changes, j.changes, j.changes))
    val walk = (j.walkingTime / 60).roundToInt()
    if (walk > 0) parts += stringResource(R.string.route_walk, walk)
    j.fare?.let { parts += it }
    return parts.joinToString(" · ")
}

@Composable
private fun HiddenNote(count: Int, all: Boolean, clear: () -> Unit) {
    val c = polaris
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (all) stringResource(R.string.results_all_hidden) else pluralStringResource(R.plurals.results_hidden, count, count),
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.inkSoft,
        )
        TextButton(onClick = clear, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.results_clear_filters), fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun EmptyState() {
    val c = polaris
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FractalStar(44.dp, lit = listOf(false, false, false, false))
        Text(stringResource(R.string.results_empty_title), style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() })
        Text(
            stringResource(R.string.results_empty_body),
            style = MaterialTheme.typography.bodyMedium, color = c.inkSoft, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Another app's view of the same trip: Citymapper and Google Maps (Apple Maps has no Android app). Their web links open the app when it is installed. */
enum class OpenIn(val label: String) {
    Citymapper("Citymapper"), GoogleMaps("Google Maps");

    /**
     * Current Location is left for the other app to work out where it has no fix of ours (its
     * saved coordinate is a placeholder); either end is treated the same way.
     */
    fun url(q: JourneyQuery, resolved: JourneyQuery? = null): String {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        fun at(p: Place, r: Place?): Place? = if (!p.isCurrentLocation) p else r?.takeIf { resolved != null }
        val from = at(q.origin, resolved?.origin)
        val to = at(q.destination, resolved?.destination)
        return when (this) {
            Citymapper -> buildString {
                append("https://citymapper.com/directions?")
                val parts = mutableListOf<String>()
                to?.let { parts += "endcoord=${enc(it.coordinate.pair)}"; parts += "endname=${enc(it.name)}" }
                from?.let { parts += "startcoord=${enc(it.coordinate.pair)}"; parts += "startname=${enc(it.name)}" }
                (q.time as? TimeMode.ArriveBy)?.let { parts += "arrival_time=${enc(Time.isoString(it.at))}" }
                append(parts.joinToString("&"))
            }
            GoogleMaps -> buildString {
                append("https://www.google.com/maps/dir/?api=1&travelmode=transit")
                to?.let { append("&destination=").append(enc(it.coordinate.pair)) }
                from?.let { append("&origin=").append(enc(it.coordinate.pair)) }
            }
        }
    }
}
