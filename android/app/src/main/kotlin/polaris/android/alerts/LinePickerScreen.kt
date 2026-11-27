package polaris.android.alerts

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import polaris.android.R
import polaris.android.settings.CoverPage
import polaris.android.settings.coverBottom
import polaris.android.ui.LinePill
import polaris.android.ui.SectionHeader
import polaris.android.ui.Symbol
import polaris.android.ui.polaris
import polaris.android.ui.surface
import polaris.engine.DepartureStore
import polaris.engine.LineColors
import polaris.engine.Loadable
import polaris.engine.RouteAlert
import polaris.engine.TfLLines

/** Something asked of TfL for a picker: on its way, here, or not got. */
private sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    /** [offline]: there was no way to the internet at all (said as that, not as TfL's fault). */
    data class Failed(val offline: Boolean = false) : Loaded<Nothing>
    data class Here<T>(val value: T) : Loaded<T>
}

/** Asks once when the page shows, and again on Try again. */
@Composable
private fun <T> loaded(key: Any, load: suspend () -> T): Pair<Loaded<T>, () -> Unit> {
    var state by remember(key) { mutableStateOf<Loaded<T>>(Loaded.Loading) }
    var attempt by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, attempt) {
        state = Loaded.Loading
        state = try {
            Loaded.Here(load())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Loaded.Failed(offline = DepartureStore.describe(e) == Loadable.Reason.offline)
        }
    }
    return state to { attempt++ }
}

@Composable
private fun PickerHeader(title: String, back: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Symbol(R.drawable.sym_arrow_back, back) }
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = polaris.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A picker's top: its title, then its search. On a short window (a phone on its side, a short
 * closed screen) the two are one row, the title as the field's label, so that with the keyboard
 * up there is still room under it for what the search finds.
 */
@Composable
private fun PickerTop(title: String, back: String, onBack: () -> Unit, under: String?, search: String, label: String, onChange: (String) -> Unit) {
    if (shortWindow()) {
        val c = polaris
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Symbol(R.drawable.sym_arrow_back, back) }
            // A field no taller than the back button: every line of height saved is a result that shows above the keyboard.
            val name = under ?: title
            androidx.compose.foundation.text.BasicTextField(
                value = search, onValueChange = onChange,
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).semantics { contentDescription = name },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.tint),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                decorationBox = { field ->
                    Row(
                        Modifier.border(1.dp, c.inkFaint, RoundedCornerShape(10.dp)).padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Symbol(R.drawable.sym_search, null, Modifier.size(20.dp), tint = c.inkSoft)
                        Box(Modifier.weight(1f)) {
                            if (search.isEmpty()) Text(name, style = MaterialTheme.typography.bodyLarge, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            field()
                        }
                        if (search.isNotEmpty()) IconButton(onClick = { onChange("") }, modifier = Modifier.size(40.dp)) { Symbol(R.drawable.sym_close, stringResource(R.string.common_remove), Modifier.size(20.dp)) }
                    }
                },
            )
        }
        return
    }
    PickerHeader(title, back, onBack)
    under?.let { Text(it, Modifier.padding(start = 20.dp, end = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.bodyMedium, color = polaris.inkSoft) }
    SearchField(search, label, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onChange)
}

/** A window too short to stack a title, a search and its results over a keyboard (a phone on its side, a short closed screen). */
@Composable
private fun shortWindow(): Boolean = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480

@Composable
private fun SearchField(value: String, label: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = modifier,
        label = { Text(label) },
        leadingIcon = { Symbol(R.drawable.sym_search, null) },
        trailingIcon = if (value.isNotEmpty()) ({ IconButton(onClick = { onChange("") }) { Symbol(R.drawable.sym_close, stringResource(R.string.common_remove)) } }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Search),
    )
}

@Composable
private fun Waiting() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp) }
}

@Composable
private fun Problem(text: String, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = polaris.inkSoft)
        TextButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 0.dp)) { Text(stringResource(R.string.alerts_try_again)) }
    }
}

/** Choose a line (iOS's `LinePicker`): TfL's lines by kind, with a search. Then its stations. */
@Composable
fun LinePickerScreen(lines: TfLLines, night: Boolean, onPick: (TfLLines.Line) -> Unit, onBack: () -> Unit) {
    val c = polaris
    var search by rememberSaveable { mutableStateOf("") }
    val (state, retry) = loaded(lines) { lines.lines() }
    // The keyboard goes with the search it was for, rather than staying up over the stations.
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val pick: (TfLLines.Line) -> Unit = { line -> focus.clearFocus(); keyboard?.hide(); onPick(line) }
    CoverPage(night) {
        PickerTop(stringResource(R.string.lines_title), stringResource(R.string.lines_close), onBack, null, search, stringResource(R.string.lines_search)) { search = it }
        when (state) {
            Loaded.Loading -> Waiting()
            is Loaded.Failed -> Problem(stringResource(if (state.offline) R.string.lines_offline else R.string.lines_failed), retry)
            is Loaded.Here -> {
                val wanted = search.trim()
                val groups = TfLLines.GROUPS.mapNotNull { (mode, title) ->
                    val shown = state.value.filter { it.mode == mode && (wanted.isEmpty() || it.name.contains(wanted, ignoreCase = true)) }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                    if (shown.isEmpty()) null else title to shown
                }
                // On a short window a search's matches come straight under the field, with no kind's heading taking the room of one.
                val plain = shortWindow() && wanted.isNotEmpty()
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = coverBottom()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (groups.isEmpty()) item(key = "none") { Text(stringResource(R.string.lines_none, wanted), Modifier.padding(4.dp), style = MaterialTheme.typography.bodyLarge, color = c.inkSoft) }
                    groups.forEach { (title, shown) ->
                        if (!plain) item(key = "h-$title") { SectionHeader(title) }
                        item(key = "g-$title") {
                            Column(Modifier.fillMaxWidth().surface(c)) {
                                shown.forEachIndexed { index, line ->
                                    if (index > 0) HorizontalDivider(color = c.hairline)
                                    Row(
                                        Modifier.fillMaxWidth().clickable(role = Role.Button) { pick(line) }.heightIn(min = if (plain) 48.dp else 56.dp).padding(horizontal = 16.dp, vertical = if (plain) 4.dp else 8.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        // The line's own colour and kind, with no name on it: the name is beside it.
                                        Box(Modifier.widthIn(min = 32.dp)) { LinePill(line.legMode, null, LineColors.color(line.name, line.legMode)) }
                                        Text(line.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.ink)
                                        Symbol(R.drawable.sym_chevron_right, null, Modifier.size(20.dp), tint = c.inkFaint)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Where you get on (or the whole line), then where you get off (iOS's `StationPicker`). [from] is
 * null while choosing where you get on; choosing where you get off gives the stretch.
 */
@Composable
fun StationPickerScreen(
    lines: TfLLines,
    line: TfLLines.Line,
    fromId: String?,
    fromName: String?,
    night: Boolean,
    onFrom: (TfLLines.Station) -> Unit,
    onPick: (RouteAlert.Segment) -> Unit,
    onBack: () -> Unit,
) {
    val c = polaris
    val context = androidx.compose.ui.platform.LocalContext.current
    var search by rememberSaveable { mutableStateOf("") }
    val (state, retry) = loaded(line.id) { lines.route(line.id) }
    CoverPage(night) {
        PickerTop(
            stringResource(if (fromName != null) R.string.stations_from_to else R.string.stations_get_on), stringResource(R.string.stations_back), onBack,
            fromName?.let { stringResource(if (shortWindow()) R.string.stations_getting_on_off else R.string.stations_getting_on, it) },
            search, stringResource(R.string.stations_search),
        ) { search = it }
        when (state) {
            Loaded.Loading -> Waiting()
            is Loaded.Failed -> Problem(if (state.offline) stringResource(R.string.stations_offline, line.name) else stringResource(R.string.stations_failed, line.name), retry)
            is Loaded.Here -> {
                val route = state.value
                val from = fromId?.let(route::stationWithId)
                val wanted = search.trim()
                val stations = route.stations.filter { it.id != fromId && (wanted.isEmpty() || it.name.contains(wanted, ignoreCase = true)) }
                // A long line has a few hundred stations: each is its own row of the list, drawn as it comes into view.
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = coverBottom())) {
                    if (fromId == null && wanted.isEmpty()) {
                        item(key = " whole") {
                            Row(
                                Modifier.fillMaxWidth().surface(c).clickable(role = Role.Button) { onPick(route.segment(line, null, null)) }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                LinePill(line.legMode, null, LineColors.color(line.name, line.legMode))
                                Text(stringResource(R.string.stations_whole_line), style = MaterialTheme.typography.bodyLarge, color = c.tint)
                            }
                        }
                        item(key = " or") { Text(stringResource(R.string.stations_or_choose), Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = c.inkSoft) }
                    }
                    if (stations.isEmpty()) item(key = " none") { Text(stringResource(R.string.stations_none, wanted), Modifier.padding(4.dp), style = MaterialTheme.typography.bodyLarge, color = c.inkSoft) }
                    items(stations.size, key = { stations[it].id }) { index ->
                        val station = stations[index]
                        val shape = RoundedCornerShape(
                            topStart = if (index == 0) 18.dp else 0.dp, topEnd = if (index == 0) 18.dp else 0.dp,
                            bottomStart = if (index == stations.lastIndex) 18.dp else 0.dp, bottomEnd = if (index == stations.lastIndex) 18.dp else 0.dp,
                        )
                        Column(Modifier.fillMaxWidth().clip(shape).background(c.surface)) {
                            if (index > 0) HorizontalDivider(color = c.hairline)
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(role = Role.Button) {
                                        if (fromId == null) onFrom(station) else {
                                            // Two branches (Edgware and High Barnet): no one stretch joins them, so it is the whole line, and that is said.
                                            if (from != null && !route.joins(from, station)) {
                                                android.widget.Toast.makeText(context, context.getString(R.string.stations_no_stretch, from.name, station.name, line.name), android.widget.Toast.LENGTH_LONG).show()
                                            }
                                            onPick(route.segment(line, from, station))
                                        }
                                    }
                                    .heightIn(min = if (shortWindow()) 48.dp else 52.dp).padding(horizontal = 16.dp, vertical = if (shortWindow()) 4.dp else 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(station.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.ink)
                                if (fromId == null) Symbol(R.drawable.sym_chevron_right, null, Modifier.size(20.dp), tint = c.inkFaint)
                            }
                        }
                    }
                }
            }
        }
    }
}
