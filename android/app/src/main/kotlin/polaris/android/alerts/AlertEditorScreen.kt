package polaris.android.alerts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import polaris.android.R
import polaris.android.settings.CoverPage
import polaris.android.settings.coverKeyboardPadding
import polaris.android.ui.HalfDialog
import polaris.android.ui.LinePill
import polaris.android.ui.SectionHeader
import polaris.android.ui.Symbol
import polaris.android.ui.polaris
import polaris.android.ui.staysAboveKeyboard
import polaris.android.ui.surface
import polaris.engine.AlertStore
import polaris.engine.PushAvailability
import polaris.engine.RouteAlert

/**
 * One alert: which lines, between where, on which days and at what times (iOS's `AlertEditor`).
 * Save is in the page's bar, so it is always in the first screenful; saving the first alert is
 * when notifications are asked for, if the build can receive any.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertEditorScreen(
    model: AlertEditorViewModel,
    state: AlertStore.State,
    night: Boolean,
    addLine: () -> Unit,
    onClose: () -> Unit,
) {
    val alert by model.alert.collectAsStateWithLifecycle()
    val customDays by model.customDays.collectAsStateWithLifecycle()
    val c = polaris
    val context = LocalContext.current
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // Which time is being set: its row, and whether its end (else its start).
    var picking by rememberSaveable { mutableStateOf<Pair<Int, Boolean>?>(null) }

    // Notifications are asked for as the alert is saved, once: the person has just said they want to hear.
    val permission = rememberNotificationPermission(then = onClose)
    val save = {
        if (model.save()) {
            if (state.push == PushAvailability.ready && !permission.granted && !permission.asked) permission.ask() else onClose()
        }
    }
    val leave = { if (model.isDirty) confirmDiscard = true else onClose() }
    BackHandler(enabled = model.isDirty) { confirmDiscard = true }

    CoverPage(night) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = leave) {
                if (model.isNew) Symbol(R.drawable.sym_close, stringResource(R.string.alert_cancel)) else Symbol(R.drawable.sym_arrow_back, stringResource(R.string.alert_back))
            }
            Text(
                if (model.isNew) stringResource(R.string.alert_new) else alert.displayName,
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Button(onClick = save, enabled = alert.isValid, modifier = Modifier.padding(start = 8.dp).heightIn(min = 48.dp)) { Text(stringResource(R.string.alert_save)) }
        }
        val (aboveKeyboard, underLast) = coverKeyboardPadding()
        val list = androidx.compose.foundation.lazy.rememberLazyListState()
        // A line under the bar once the page has moved under it, so what is half hidden reads as scrolled, not cut.
        if (list.canScrollBackward) HorizontalDivider(color = c.hairline)
        LazyColumn(
            Modifier.fillMaxSize().padding(bottom = aboveKeyboard),
            state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = underLast),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val status = if (model.isNew) null else state.status[alert.id]
            if (status != null) {
                item(key = "now-h") { SectionHeader(stringResource(R.string.alert_now)) }
                item(key = "now") {
                    Column(Modifier.fillMaxWidth().surface(c)) {
                        if (status.issues.isEmpty()) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Symbol(R.drawable.sym_check_circle, null, Modifier.size(20.dp), tint = c.live)
                                Text(stringResource(R.string.alerts_good_service), style = MaterialTheme.typography.bodyLarge, color = c.live)
                            }
                        }
                        status.issues.forEachIndexed { i, issue ->
                            if (i > 0) HorizontalDivider(color = c.hairline)
                            AlertIssueRow(issue)
                        }
                    }
                }
                if (status.upcoming.isNotEmpty()) {
                    item(key = "planned-h") { SectionHeader(stringResource(R.string.alert_planned)) }
                    item(key = "planned") {
                        Column(Modifier.fillMaxWidth().surface(c)) {
                            status.upcoming.forEachIndexed { i, issue ->
                                if (i > 0) HorizontalDivider(color = c.hairline)
                                AlertIssueRow(issue, planned = true)
                            }
                        }
                    }
                }
            }

            item(key = "lines-h") { SectionHeader(stringResource(R.string.alert_lines)) }
            item(key = "lines") {
                Column(Modifier.fillMaxWidth().surface(c)) {
                    alert.segments.forEach { segment ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            LinePill(segment.legMode, segment.line.name, segment.color)
                            Text(segment.stretch, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyLarge, color = c.ink)
                            IconButton(onClick = { model.removeSegment(segment) }) {
                                Symbol(R.drawable.sym_close, stringResource(R.string.alert_remove_line, "${segment.line.name}, ${segment.stretch}"), tint = c.inkSoft)
                            }
                        }
                        HorizontalDivider(color = c.hairline)
                    }
                    // The server takes eight lines an alert: at eight, no more are offered.
                    if (alert.segments.size < RouteAlert.MAX_SEGMENTS) AddRow(stringResource(R.string.alerts_add), addLine)
                }
            }
            item(key = "lines-foot") {
                Footer(
                    when {
                        alert.segments.isEmpty() -> stringResource(R.string.alert_needs_line)
                        alert.segments.size >= RouteAlert.MAX_SEGMENTS -> stringResource(R.string.alert_lines_full, RouteAlert.MAX_SEGMENTS.toString())
                        else -> stringResource(R.string.alert_lines_footer)
                    },
                )
            }

            item(key = "when-h") { SectionHeader(stringResource(R.string.alert_when)) }
            item(key = "when") {
                Column(Modifier.fillMaxWidth().surface(c)) {
                    if (alert.ends != null) {
                        WayRow(stringResource(R.string.alert_which_way), alert, alert.way, own = true, follow = null) { model.setWay(it) }
                        HorizontalDivider(color = c.hairline)
                    }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.alert_days), style = MaterialTheme.typography.bodyLarge, color = c.ink)
                        val choice = AlertEditorViewModel.daysChoice(alert.days, customDays)
                        val labels = mapOf(
                            AlertEditorViewModel.Days.Weekdays to R.string.alert_days_weekdays, AlertEditorViewModel.Days.EveryDay to R.string.alert_days_every,
                            AlertEditorViewModel.Days.Weekends to R.string.alert_days_weekends, AlertEditorViewModel.Days.Custom to R.string.alert_days_custom,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AlertEditorViewModel.Days.entries.forEach { option ->
                                FilterChip(selected = choice == option, onClick = { model.chooseDays(option) }, label = { Text(stringResource(labels.getValue(option))) })
                            }
                        }
                        if (choice == AlertEditorViewModel.Days.Custom) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                (1..7).forEach { day ->
                                    val name = RouteAlert.DAYS[day - 1]
                                    FilterChip(
                                        selected = day in alert.days, onClick = { model.toggleDay(day) },
                                        label = { Text(RouteAlert.SHORT_DAYS[day - 1]) },
                                        modifier = Modifier.semantics { contentDescription = name },
                                    )
                                }
                            }
                            if (alert.days.isEmpty()) Text(stringResource(R.string.alert_needs_day), style = MaterialTheme.typography.bodySmall, color = c.warning)
                        }
                    }
                    alert.windows.forEachIndexed { index, window ->
                        HorizontalDivider(color = c.hairline)
                        TimeRow(
                            alert, index, window,
                            pickStart = { picking = index to false }, pickEnd = { picking = index to true },
                            setWay = { model.setTimeWay(index, it) }, remove = { model.removeTime(index) },
                        )
                    }
                    if (alert.windows.size < RouteAlert.MAX_WINDOWS) {
                        HorizontalDivider(color = c.hairline)
                        AddRow(stringResource(R.string.alert_add_time), model::addTime)
                    }
                }
            }
            item(key = "when-foot") {
                val ends = alert.ends
                when {
                    alert.windows.isEmpty() -> Footer(stringResource(R.string.alert_needs_time), warn = true)
                    alert.windows.size >= RouteAlert.MAX_WINDOWS -> Footer(stringResource(R.string.alert_times_full, RouteAlert.MAX_WINDOWS.toString()))
                    ends == null -> Unit
                    alert.waysDiffer -> Footer(stringResource(R.string.alert_ways_footer_differ))
                    alert.way != RouteAlert.Direction.both -> Footer(stringResource(R.string.alert_ways_footer_one, if (alert.way == RouteAlert.Direction.out) ends.second else ends.first))
                    else -> Footer(stringResource(R.string.alert_ways_footer))
                }
            }

            item(key = "minor") {
                Row(
                    Modifier.fillMaxWidth().surface(c)
                        .toggleable(value = alert.minorDelays, role = Role.Switch, onValueChange = model::setMinorDelays)
                        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.alert_minor), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.ink)
                    Switch(checked = alert.minorDelays, onCheckedChange = null)
                }
            }
            item(key = "minor-foot") { Footer(stringResource(R.string.alert_minor_footer)) }

            item(key = "name") {
                // The text lives here while it is typed, so fast typing never loses a character or moves the cursor.
                var name by remember(model) { mutableStateOf(alert.name) }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(AlertEditorViewModel.MAX_NAME); model.setName(name) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).staysAboveKeyboard(),
                    label = { Text(stringResource(R.string.alert_name)) },
                    placeholder = { Text(alert.defaultName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                )
            }

            if (!model.isNew) item(key = "delete") {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Symbol(R.drawable.sym_delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.alert_delete), Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    picking?.let { (index, isEnd) ->
        val window = alert.windows.getOrNull(index)
        if (window == null) picking = null else TimeDialog(
            title = stringResource(if (isEnd) R.string.alert_time_until else R.string.alert_time_from),
            minutes = if (isEnd) window.end else window.start,
            onDismiss = { picking = null },
        ) { chosen ->
            if (isEnd) model.setTime(index, end = chosen) else model.setTime(index, start = chosen)
            picking = null
        }
    }
    if (confirmDiscard) HalfDialog(
        onDismissRequest = { confirmDiscard = false },
        title = stringResource(R.string.alert_discard_title),
        confirmButton = { TextButton({ confirmDiscard = false; onClose() }) { Text(stringResource(R.string.alert_discard_confirm)) } },
        dismissButton = { TextButton({ confirmDiscard = false }) { Text(stringResource(R.string.alert_keep_editing)) } },
    ) { Text(stringResource(if (model.isNew) R.string.alert_discard_body else R.string.alert_discard_body_saved)) }
    if (confirmDelete) HalfDialog(
        onDismissRequest = { confirmDelete = false },
        title = stringResource(R.string.alert_delete_title),
        confirmButton = { TextButton({ confirmDelete = false; model.delete(); onClose() }) { Text(stringResource(R.string.alert_delete_confirm), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
    ) { Text(stringResource(R.string.alert_delete_body, alert.displayName)) }
}

@Composable
private fun Footer(text: String, warn: Boolean = false) {
    Text(text, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = if (warn) polaris.warning else polaris.inkSoft)
}

@Composable
private fun AddRow(text: String, onClick: () -> Unit) {
    val c = polaris
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Symbol(R.drawable.sym_add, null, Modifier.size(20.dp), tint = c.tint)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = c.tint)
    }
}

/**
 * A choice of way, as a row that opens a menu: Both ways, To one end, To the other. For a time's
 * own way, [own] says whether it was chosen (else it shows the alert's, quieter) and [follow]
 * goes back to following Which way.
 */
@Composable
private fun WayRow(label: String, alert: RouteAlert, current: RouteAlert.Direction, own: Boolean, follow: (() -> Unit)?, modifier: Modifier = Modifier, choose: (RouteAlert.Direction) -> Unit) {
    val c = polaris
    var open by remember { mutableStateOf(false) }
    val value = alert.label(current)
    val followsWords = stringResource(R.string.alert_follows_way)
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.DropdownList) { open = true }
                .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) { contentDescription = label; stateDescription = if (follow == null || own) value else "$value, $followsWords" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.ink)
            Column(horizontalAlignment = Alignment.End) {
                Text(value, style = MaterialTheme.typography.bodyLarge, color = if (own) c.tint else c.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // In words, not only by colour: whether this time has its own way or goes the alert's.
                if (follow != null) Text(stringResource(if (own) R.string.alert_way_set else R.string.alert_follows_way), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
            }
            Symbol(R.drawable.sym_keyboard_arrow_down, null, Modifier.size(20.dp), tint = c.inkFaint)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(RouteAlert.Direction.both, RouteAlert.Direction.out, RouteAlert.Direction.back).forEach { way ->
                DropdownMenuItem(
                    text = { Text(alert.label(way)) },
                    onClick = { open = false; choose(way) },
                    leadingIcon = { if (own && way == current) Symbol(R.drawable.go_check, null, Modifier.size(18.dp), tint = c.tint) else Box(Modifier.size(18.dp)) },
                )
            }
            if (follow != null) {
                // Always there for a time, ticked while the time follows: the menu never opens with nothing ticked.
                HorizontalDivider(color = c.hairline)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.alert_follow_way)) },
                    onClick = { open = false; follow() },
                    leadingIcon = { if (!own) Symbol(R.drawable.go_check, null, Modifier.size(18.dp), tint = c.tint) else Box(Modifier.size(18.dp)) },
                )
            }
        }
    }
}

/** One time you travel: when it starts and ends (each opens the time picker), its own way on a stretch, and its removal. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeRow(
    alert: RouteAlert,
    index: Int,
    window: RouteAlert.TimeWindow,
    pickStart: () -> Unit,
    pickEnd: () -> Unit,
    setWay: (RouteAlert.Direction?) -> Unit,
    remove: () -> Unit,
) {
    val c = polaris
    val from = RouteAlert.clock(window.start)
    val to = RouteAlert.clock(window.end)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // The two times and "to" wrap together at the largest text, rather than pushing the remove button off.
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                val startLabel = stringResource(R.string.alert_time_from_label, from)
                OutlinedButton(onClick = pickStart, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = startLabel }, contentPadding = PaddingValues(horizontal = 16.dp)) {
                    Text(from, style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(R.string.alert_time_to), style = MaterialTheme.typography.bodyLarge, color = c.inkSoft)
                val endLabel = stringResource(R.string.alert_time_to_label, to)
                OutlinedButton(onClick = pickEnd, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = endLabel }, contentPadding = PaddingValues(horizontal = 16.dp)) {
                    Text(to, style = MaterialTheme.typography.titleMedium)
                }
            }
            IconButton(onClick = remove) { Symbol(R.drawable.sym_close, stringResource(R.string.alert_remove_time, from, to), tint = c.inkSoft) }
        }
        if (window.start == window.end) Text(stringResource(R.string.alert_time_same), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = c.warning)
        if (alert.ends != null) {
            WayRow(
                stringResource(R.string.alert_way_for_time), alert, alert.way(window), own = window.direction != null,
                follow = { setWay(null) }, choose = { setWay(it) },
            )
        }
    }
}

/** Material's time picker in a dialog: the dial where there is room for it, the two fields on a short window (a folded cover turned sideways, a phone in landscape). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, minutes: Int, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    val picker = rememberTimePickerState(initialHour = (minutes / 60) % 24, initialMinute = minutes % 60, is24Hour = true)
    val short = LocalConfiguration.current.screenHeightDp < 620 || LocalDensity.current.fontScale >= 1.5f
    HalfDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmButton = { TextButton({ onSet(picker.hour * 60 + picker.minute) }) { Text(stringResource(R.string.alert_time_set)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (short) TimeInput(picker) else TimePicker(picker)
        }
    }
}
