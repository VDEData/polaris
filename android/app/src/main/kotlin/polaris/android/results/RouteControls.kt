package polaris.android.results

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.RadioButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import polaris.android.R
import polaris.android.app.AppGraph
import polaris.android.shell.JourneyPage
import polaris.android.shell.LocalSheet
import polaris.android.shell.MapScene
import polaris.android.shell.MeasuredBar
import polaris.android.shell.SheetPage
import polaris.android.shell.SceneEffect
import polaris.android.shell.factory
import polaris.android.ui.HalfDialog
import polaris.android.ui.Clock
import polaris.android.ui.LegStrip
import polaris.android.ui.Symbol
import polaris.android.ui.minutesText
import polaris.android.ui.polaris
import polaris.android.ui.surface
import polaris.engine.JourneyMap
import polaris.engine.ModeGroup
import polaris.engine.RouteFilter
import polaris.engine.SortOrder
import polaris.engine.TimeMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val changeChoices = listOf<Pair<Int?, String>>(null to "Any", 1 to "Up to 1", 0 to "Direct only")
private val walkChoices = listOf<Pair<Int?, String>>(null to "Any", 10 to "Up to 10 min", 5 to "Up to 5 min")

/**
 * Rules that take routes off the list (iOS's `FilterSheet`). Remembered between searches, because
 * they're usually about the person ("I don't do buses") rather than the trip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterContent(filter: RouteFilter, onChange: (RouteFilter) -> Unit, modifier: Modifier = Modifier) {
    val c = polaris
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("Changes")
        Choices(changeChoices, filter.maxChanges) { onChange(filter.copy(maxChanges = it)) }
        Label("Walking")
        Choices(walkChoices, filter.maxWalkMinutes) { onChange(filter.copy(maxWalkMinutes = it)) }
        Label("Avoid")
        Column(Modifier.surface(c)) {
            ModeGroup.entries.forEach { group ->
                val on = group in filter.avoided
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(on, role = Role.Switch) { avoid -> onChange(filter.copy(avoided = if (avoid) filter.avoided + group else filter.avoided - group)) }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(group.label, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                        group.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkSoft) }
                    }
                    Switch(checked = on, onCheckedChange = null)
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = polaris.inkSoft)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choices(choices: List<Pair<T, String>>, current: T, pick: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == current,
                onClick = { pick(value) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                modifier = Modifier.heightIn(min = 48.dp),
                label = { Text(label, textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
            )
        }
    }
}

/** Filters on a phone: Material's modal sheet, with Reset and Done. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(filter: RouteFilter, onChange: (RouteFilter) -> Unit, onDismiss: () -> Unit) {
    val c = polaris
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.paper) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onChange(RouteFilter()) }, enabled = filter.activeCount > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset") }
                Text("Filters", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = c.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done", fontWeight = FontWeight.SemiBold) }
            }
            FilterContent(filter, onChange)
        }
    }
}

/**
 * Wide, the order and filters dock as one card at the foot of the map half, beside the list they
 * change: change one and watch the list and the map answer together. Compact, as in the
 * mock-up: the order as segments, each filter a menu, so the map half stays mostly map.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortAndFilterCard(sort: SortOrder, filter: RouteFilter, hidden: Int, setSort: (SortOrder) -> Unit, setFilter: (RouteFilter) -> Unit) {
    val c = polaris
    Surface(shape = MaterialTheme.shapes.extraLarge, color = c.paper, shadowElevation = 6.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sort and filter", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = c.ink)
                TextButton(onClick = { setFilter(RouteFilter()) }, enabled = filter.activeCount > 0) { Text("Reset") }
            }
            // Four segments across fit at ordinary sizes; at large text a menu, so no word breaks.
            val large = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f
            if (large) Column(Modifier.surface(c)) {
                MenuRow("Sort", sort.label, SortOrder.entries.map { it.label }) { i -> setSort(SortOrder.entries[i]) }
            } else SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val orders = SortOrder.entries
                orders.forEachIndexed { index, order ->
                    SegmentedButton(
                        selected = order == sort, onClick = { setSort(order) },
                        shape = SegmentedButtonDefaults.itemShape(index, orders.size),
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = order.label },
                        icon = {},
                        label = { Text(shortSort(order), maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                    )
                }
            }
            Column(Modifier.surface(c)) {
                MenuRow("Changes", changeChoices.first { it.first == filter.maxChanges }.second, changeChoices.map { it.second }) { i -> setFilter(filter.copy(maxChanges = changeChoices[i].first)) }
                HorizontalDivider(color = c.hairline)
                MenuRow("Walking", walkChoices.first { it.first == filter.maxWalkMinutes }.second, walkChoices.map { it.second }) { i -> setFilter(filter.copy(maxWalkMinutes = walkChoices[i].first)) }
                HorizontalDivider(color = c.hairline)
                AvoidRow(filter, setFilter)
            }
            if (hidden > 0) Text("$hidden route${if (hidden == 1) "" else "s"} hidden by your filters", style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        }
    }
}

@Composable
private fun MenuRow(label: String, value: String, choices: List<String>, pick: (Int) -> Unit) {
    val c = polaris
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.DropdownList) { open = true }.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$label: ${value.replaceFirstChar { it.lowercase() }}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.ink)
            Symbol(R.drawable.sym_keyboard_arrow_down, null, tint = c.inkSoft)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEachIndexed { i, choice ->
                DropdownMenuItem(
                    text = { Text(choice) },
                    leadingIcon = { RadioButton(selected = choice == value, onClick = null) },
                    onClick = { open = false; pick(i) },
                )
            }
        }
    }
}

@Composable
private fun AvoidRow(filter: RouteFilter, setFilter: (RouteFilter) -> Unit) {
    val c = polaris
    var open by rememberSaveable { mutableStateOf(false) }
    val avoided = ModeGroup.entries.filter { it in filter.avoided }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.DropdownList) { open = true }.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Avoid: " + if (avoided.isEmpty()) "nothing" else avoided.joinToString(", ") { it.label.lowercase() },
                Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.ink,
            )
            Symbol(R.drawable.sym_keyboard_arrow_down, null, tint = c.inkSoft)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ModeGroup.entries.forEach { group ->
                val on = group in filter.avoided
                DropdownMenuItem(
                    text = { Text(group.label) },
                    leadingIcon = { Checkbox(checked = on, onCheckedChange = null) },
                    // Stays open: several can be ticked in one go.
                    onClick = { setFilter(filter.copy(avoided = if (on) filter.avoided - group else filter.avoided + group)) },
                )
            }
        }
    }
}

/** The order's name in a segment, short enough for four across. */
private fun shortSort(order: SortOrder) = when (order) {
    SortOrder.best -> "Best"
    SortOrder.fastest -> "Fastest"
    SortOrder.fewestChanges -> "Fewest changes"
    SortOrder.leastWalking -> "Least walking"
}

/**
 * Depart at or arrive by: Material's time picker in a dialog, with a date row for another day
 * (iOS's wheel doesn't carry over). Times are the phone's own time zone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(kind: TimeKind, current: TimeMode, onDismiss: () -> Unit, onDone: (TimeMode) -> Unit) {
    val zone = ZoneId.systemDefault()
    val start = (current as? TimeMode.DepartAt)?.at ?: (current as? TimeMode.ArriveBy)?.at ?: Instant.now()
    val local = start.atZone(zone)
    val time = rememberTimePickerState(local.hour, local.minute, is24Hour = true)
    var day by rememberSaveable { mutableStateOf(local.toLocalDate().toEpochDay()) }
    var pickingDay by rememberSaveable { mutableStateOf(false) }
    val date = LocalDate.ofEpochDay(day)
    val c = polaris
    HalfDialog(
        onDismissRequest = onDismiss,
        title = if (kind == TimeKind.Depart) "Depart at" else "Arrive by",
        confirmButton = {
            TextButton(onClick = {
                val at = date.atTime(LocalTime.of(time.hour, time.minute)).atZone(zone).toInstant()
                onDone(if (kind == TimeKind.Depart) TimeMode.DepartAt(at) else TimeMode.ArriveBy(at))
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Change the day") { pickingDay = true }.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Day", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.inkSoft)
                    Text(dayLabel(date), style = MaterialTheme.typography.bodyLarge, color = c.tint, fontWeight = FontWeight.SemiBold)
                }
                // The dial's numbers crowd at large text sizes: typed hours and minutes there.
                if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) androidx.compose.material3.TimeInput(time) else TimePicker(time)
            }
    }
    if (pickingDay) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        HalfDialog(
            onDismissRequest = { pickingDay = false },
            title = null,
            contentPadding = 0.dp,
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { day = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() }
                    pickingDay = false
                }) { Text("Done") }
            },
            dismissButton = { TextButton({ pickingDay = false }) { Text("Cancel") } },
        ) { DatePicker(picker, colors = androidx.compose.material3.DatePickerDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)) }
    }
}

private fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    }
}

/**
 * The route's page. The journey page (itinerary, departures, Go) is the next update; for now this
 * says so, and the map shows the route alone. Up and system back return to the routes.
 */
@Composable
fun JourneyPlaceholder(key: JourneyPage, graph: AppGraph, onBack: () -> Unit) {
    val c = polaris
    val sheet = LocalSheet.current
    // Read off the main thread (from memory at once, or from the file after a restart).
    val search by androidx.compose.runtime.produceState<polaris.engine.Snapshot.Search?>(null, key) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { graph.archive.load(key.searchId) }
    }
    val journey = search?.journeys?.firstOrNull { it.id == key.journeyId }
    val night = c.isNight
    val drawing = remember(journey, night) {
        journey?.let { j ->
            val q = search?.query
            JourneyMap.drawing(j, night, q?.origin?.takeUnless { it.isCurrentLocation }?.coordinate, q?.destination?.takeUnless { it.isCurrentLocation }?.coordinate)
        }
    }
    SceneEffect(key, MapScene.Content(drawing))
    val title = journey?.let { "${Clock.of(it.departure)} – ${Clock.of(it.arrival)} · ${minutesText(it.duration)}" } ?: "Route"
    val bar: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Symbol(R.drawable.sym_arrow_back, "Back") }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    SheetPage(key, bar = bar) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(PaddingValues(start = 0.dp, end = 0.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            bar()
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                journey?.let { LegStrip(it.legs) }
                Text(
                    "Journey details (the stops, departures and Go) come in the next update. Back returns to the routes.",
                    style = MaterialTheme.typography.bodyMedium, color = c.inkSoft,
                )
            }
        }
    }
}
