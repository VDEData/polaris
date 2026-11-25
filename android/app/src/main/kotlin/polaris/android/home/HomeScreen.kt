package polaris.android.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.compose.ui.res.stringResource
import polaris.android.R
import polaris.android.shell.MapScene
import polaris.android.shell.SceneEffect
import polaris.engine.JourneyMap
import polaris.android.shell.LocalSheet
import polaris.android.shell.MeasuredBar
import polaris.android.shell.SheetPage
import polaris.android.shell.SearchFor
import polaris.android.shell.ShellViewModel
import polaris.android.ui.FractalStar
import polaris.android.ui.PolarisType
import polaris.android.ui.SectionHeader
import polaris.android.ui.Symbol
import polaris.android.ui.polaris
import polaris.android.ui.surface
import polaris.engine.Place

/**
 * Home, the sheet's root (iOS's `HomeSheet`): the wordmark with Settings in its overflow, where
 * to, Home and Work, recent places, and "Leaving near you". Location is never asked for here
 * unasked: what's leaving nearby is asked for (one request, kept a minute) only once the person
 * has allowed location, and offers a button to allow it otherwise.
 *
 * Not here yet: "Your lines", which comes with alerts.
 */
@Composable
fun HomeScreen(
    key: NavKey,
    graph: polaris.android.app.AppGraph,
    shell: ShellViewModel,
    openSearch: (SearchFor) -> Unit,
    openSettings: () -> Unit,
    openLine: (polaris.engine.NearbyLine) -> Unit,
) {
    val places by shell.places.collectAsStateWithLifecycle()
    val finding by shell.finding.collectAsStateWithLifecycle()
    val sheet = LocalSheet.current
    val c = polaris

    // The one-row bar shows at the sheet's low rest, over the list, which stays as it was.
    // The map on Home: around where the person last was, once they've allowed location, framed in
    // the part of the map left showing (beside the card when wide). No fix is asked for here.
    val shows by shell.showsLocation.collectAsStateWithLifecycle()
    val here = remember(shows) { if (shows) graph.locator.last() else null }
    val content = remember(here) {
        here?.let { MapScene.Content(frame = JourneyMap.Bounds(it.latitude - 0.006, it.longitude - 0.01, it.latitude + 0.006, it.longitude + 0.01)) }
            ?: MapScene.Content()
    }
    SceneEffect(key, content)

    SheetPage(key, bar = { Bar(openSearch) }) {
        val list = rememberLazyListState()
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp,
                bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                    FractalStar(26.dp)
                    Text(stringResource(R.string.app_name), style = PolarisType.wordmark, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
                    SettingsMenu(openSettings)
                }
            }
            item { WhereTo(Modifier.fillMaxWidth()) { openSearch(SearchFor.Destination) } }
            if (finding) item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.common_finding_you), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                }
            }
            item {
                val home: @Composable (Modifier) -> Unit = { m ->
                    SavedTile(stringResource(R.string.common_home), R.drawable.sym_home, places.home, m,
                        go = { shell.planTo(it) }, set = { openSearch(SearchFor.Home) }, clear = { shell.setHome(null) })
                }
                val work: @Composable (Modifier) -> Unit = { m ->
                    SavedTile(stringResource(R.string.common_work), R.drawable.sym_work, places.work, m,
                        go = { shell.planTo(it) }, set = { openSearch(SearchFor.Work) }, clear = { shell.setWork(null) })
                }
                // Side by side; stacked at large text sizes, where two would cut the names short.
                if (LocalDensity.current.fontScale >= 1.5f) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { home(Modifier.fillMaxWidth()); work(Modifier.fillMaxWidth()) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { home(Modifier.weight(1f)); work(Modifier.weight(1f)) }
                }
            }
            if (places.recents.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.common_recent)) }
                item {
                    Column(Modifier.surface(c)) {
                        places.recents.take(5).forEachIndexed { index, place ->
                            if (index > 0) HorizontalDivider(Modifier.padding(start = 52.dp), color = c.hairline)
                            RecentRow(place, go = { shell.planTo(place) },
                                setHome = { shell.setHome(place) }, setWork = { shell.setWork(place) }, forget = { shell.forget(place) })
                        }
                    }
                }
            }
            // "Leaving near you" is a nice extra, so it sits last and shows only a few rows (as on iOS).
            nearbySection(graph, shell, wide = sheet.wide, openLine = openLine)
        }
    }
}

@Composable
private fun Bar(openSearch: (SearchFor) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FractalStar(26.dp)
        WhereTo(Modifier.weight(1f)) { openSearch(SearchFor.Destination) }
    }
}

/** "Where to?": Material's search bar look, opening the full search view. */
@Composable
fun WhereTo(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = polaris
    val description = stringResource(R.string.home_search_description)
    Row(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(c.surface, RoundedCornerShape(28.dp))
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.home_search_click_label)) { onClick() }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Symbol(R.drawable.sym_search, null, tint = c.inkSoft)
        Text(stringResource(R.string.home_where_to), style = MaterialTheme.typography.bodyLarge, color = c.inkSoft)
    }
}

/**
 * Home or Work: a tap goes there (or sets it when empty); long-press, or the visible overflow
 * button, offers Change and Remove, since long-press alone isn't discoverable on Android.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedTile(
    title: String,
    icon: Int,
    place: Place?,
    modifier: Modifier,
    go: (Place) -> Unit,
    set: () -> Unit,
    clear: () -> Unit,
) {
    val c = polaris
    var menu by rememberSaveable { mutableStateOf(false) }
    val moreFor = stringResource(R.string.home_more_for, title)
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .surface(c)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = if (place == null) stringResource(R.string.home_set_place, title) else stringResource(R.string.home_plan_trip_to, title),
                    onLongClickLabel = if (place != null) stringResource(R.string.home_change_or_remove, title) else null,
                    onLongClick = if (place != null) ({ menu = true }) else null,
                ) { if (place != null) go(place) else set() }
                .heightIn(min = 56.dp)
                .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = if (place != null) 0.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(34.dp).background(c.tint.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) {
                Symbol(icon, null, Modifier.size(18.dp), tint = c.tint)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Three lines in a half-width tile before a name is shortened, so it ends at a word.
                Text(place?.name ?: stringResource(R.string.home_add), style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (place != null) {
                IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = moreFor }) {
                    Symbol(R.drawable.sym_more_vert, null, tint = c.inkSoft)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.home_change_place, title)) }, leadingIcon = { Symbol(R.drawable.sym_edit, null) }, onClick = { menu = false; set() })
            DropdownMenuItem(text = { Text(stringResource(R.string.home_remove_place, title)) }, leadingIcon = { Symbol(R.drawable.sym_delete, null) }, onClick = { menu = false; clear() })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(place: Place, go: () -> Unit, setHome: () -> Unit, setWork: () -> Unit, forget: () -> Unit) {
    val c = polaris
    var menu by rememberSaveable { mutableStateOf(false) }
    val moreFor = stringResource(R.string.home_more_for, place.name)
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(role = Role.Button, onClickLabel = stringResource(R.string.home_plan_trip_here), onLongClickLabel = stringResource(R.string.common_more_options), onLongClick = { menu = true }, onClick = go)
                .heightIn(min = 56.dp)
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Symbol(R.drawable.sym_history, null, Modifier.width(28.dp), tint = c.inkSoft)
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(place.name, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                place.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = moreFor }) {
                Symbol(R.drawable.sym_more_vert, null, tint = c.inkSoft)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.home_set_as_home)) }, leadingIcon = { Symbol(R.drawable.sym_home, null) }, onClick = { menu = false; setHome() })
            DropdownMenuItem(text = { Text(stringResource(R.string.home_set_as_work)) }, leadingIcon = { Symbol(R.drawable.sym_work, null) }, onClick = { menu = false; setWork() })
            DropdownMenuItem(text = { Text(stringResource(R.string.common_remove)) }, leadingIcon = { Symbol(R.drawable.sym_delete, null) }, onClick = { menu = false; forget() })
        }
    }
}

/** Settings, from the overflow beside the wordmark (Android's place for it; no gear icon needed). */
@Composable
private fun SettingsMenu(openSettings: () -> Unit) {
    var menu by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Symbol(R.drawable.sym_more_vert, stringResource(R.string.home_more), tint = polaris.inkSoft) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.settings_open)) }, onClick = { menu = false; openSettings() })
        }
    }
}
