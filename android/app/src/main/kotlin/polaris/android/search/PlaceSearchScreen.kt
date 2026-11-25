package polaris.android.search

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.compose.ui.res.stringResource
import polaris.android.R
import polaris.android.app.AppGraph
import polaris.android.shell.LocalWindowLayout
import polaris.android.shell.ResultsPage
import polaris.android.shell.SearchFor
import polaris.android.shell.ShellViewModel
import polaris.android.shell.factory
import polaris.android.ui.SectionHeader
import polaris.android.ui.Symbol
import polaris.android.ui.modeIcon
import polaris.android.ui.polaris
import polaris.engine.Coordinate
import polaris.engine.LegMode
import polaris.engine.Place
import polaris.engine.PlaceMatch
import polaris.engine.Snapshot

/**
 * The place search view (iOS's `PlaceSearchView`): full screen on a phone, over the leading card
 * when the window is wide (never across the fold). The keyboard is up at once; system back
 * closes it. Empty, it offers Current Location (where iOS does), Home, Work and recent places;
 * typed, Transitous's places, with a last row handing a chain or kind of place to the person's
 * maps app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceSearchScreen(
    graph: AppGraph,
    shell: ShellViewModel,
    kind: SearchFor,
    pages: NavBackStack<NavKey>,
    night: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    // Near the trip's other end when editing a results page; else near the person if allowed.
    val bias = remember(kind) { biasFor(kind, pages, graph) }
    val model: PlaceSearchViewModel = viewModel(factory = factory { PlaceSearchViewModel(it, graph, bias) })
    val ui by model.ui.collectAsStateWithLifecycle()
    val places by shell.places.collectAsStateWithLifecycle()
    val c = polaris
    val layout = LocalWindowLayout.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // The dialog's own window: drawn edge to edge, its bar icons readable on the paper.
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !night
                isAppearanceLightNavigationBars = !night
            }
            w.setDimAmount(if (layout.wide) 0.25f else 0f)
        }
    }

    val pick: (Place) -> Unit = { place ->
        shell.picked(kind, place)
        onClose()
    }
    val mapsIntent = remember(ui.text) { Intent(Intent.ACTION_VIEW, Uri.parse(PlaceSearchViewModel.mapsLink(ui.text, bias))) }
    val hasMapsApp = remember { Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=pharmacy")).resolveActivity(context.packageManager) != null }

    Box(Modifier.fillMaxSize()) {
        Surface(
            modifier = if (layout.wide) {
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp)
                    .width(layout.cardWidth)
                    .fillMaxHeight()
            } else Modifier.fillMaxSize(),
            shape = if (layout.wide) RoundedCornerShape(28.dp) else RoundedCornerShape(0.dp),
            color = c.paper,
        ) {
            // A reading width on a wide phone layout (tall-open, a tablet held tall), as the sheet has.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxSize().windowInsetsPadding(
                    if (layout.wide) WindowInsets(0) else WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ),
            ) {
                Text(
                    stringResource(kind.title),
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium, color = c.ink,
                )
                SearchBarDefaults.InputField(
                    query = ui.text,
                    onQueryChange = model::type,
                    onSearch = { },
                    expanded = true,
                    onExpandedChange = { if (!it) onClose() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).focusRequester(focus),
                    placeholder = { Text(stringResource(R.string.search_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        IconButton(onClick = onClose) { Symbol(R.drawable.sym_arrow_back, stringResource(R.string.search_close)) }
                    },
                    trailingIcon = if (ui.text.isNotEmpty()) ({
                        IconButton(onClick = { model.type("") }) { Symbol(R.drawable.sym_close, stringResource(R.string.search_clear_text)) }
                    }) else null,
                )
                HorizontalDivider(color = c.hairline)
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        bottom = 16.dp + (if (layout.wide) 0.dp else WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding()),
                    ),
                ) {
                    when {
                        ui.restored -> item {
                            PlaceRow(R.drawable.sym_refresh, c.tint, stringResource(R.string.search_again_for, ui.text), null) { model.searchRestored() }
                        }
                        !ui.isQuery -> {
                            if (kind.allowsCurrentLocation) item {
                                PlaceRow(R.drawable.sym_my_location, c.here, stringResource(R.string.search_current_location), null) { shell.pickCurrentLocation(kind) }
                            }
                            places.home?.let { home -> if (kind != SearchFor.Home) item { PlaceRow(R.drawable.sym_home, c.tint, stringResource(R.string.common_home), home.name) { pick(home) } } }
                            places.work?.let { work -> if (kind != SearchFor.Work) item { PlaceRow(R.drawable.sym_work, c.tint, stringResource(R.string.common_work), work.name) { pick(work) } } }
                            if (places.recents.isNotEmpty()) {
                                item { SectionHeader(stringResource(R.string.common_recent), Modifier.padding(horizontal = 12.dp)) }
                                items(places.recents, key = { "recent-" + it.id }) { place ->
                                    PlaceRow(R.drawable.sym_history, c.inkSoft, place.name, place.subtitle) { pick(place) }
                                }
                            }
                            if (ui.text.isNotBlank()) item { Note(stringResource(R.string.search_keep_typing)) }
                        }
                        else -> {
                            ui.error?.let { error ->
                                item { ErrorRow(error) { model.type(ui.text) } }
                            }
                            if (ui.isSearching && ui.results.isEmpty()) item { Searching() }
                            if (ui.noResults) item { Note(stringResource(R.string.search_no_match, ui.text.trim())) }
                            items(ui.results, key = { it.place.id }) { match ->
                                PlaceRow(iconFor(match), if (match.kind == PlaceMatch.Kind.stop) c.tint else c.inkSoft, match.place.name, match.place.subtitle) { pick(match.place) }
                            }
                            item {
                                // The geocoder's data is OpenStreetMap's, which asks for this credit.
                                androidx.compose.material3.Text(
                                    stringResource(R.string.search_places_credit),
                                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelSmall, color = c.inkFaint,
                                )
                            }
                            if (hasMapsApp) item {
                                HorizontalDivider(Modifier.padding(start = 56.dp), color = c.hairline)
                                PlaceRow(R.drawable.sym_travel_explore, c.tint, stringResource(R.string.search_in_maps_app, ui.text.trim()), stringResource(R.string.search_in_maps_app_detail)) {
                                    runCatching { context.startActivity(mapsIntent) }
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

private fun iconFor(match: PlaceMatch): Int = when (match.kind) {
    PlaceMatch.Kind.stop -> {
        val modes = match.modes.map { it.uppercase() }
        when {
            modes.any { "SUBWAY" in it || "METRO" in it } -> modeIcon(LegMode.tube)
            modes.any { "RAIL" in it } -> modeIcon(LegMode.rail)
            modes.any { "TRAM" in it } -> modeIcon(LegMode.tram)
            modes.any { "BUS" in it || "COACH" in it } -> modeIcon(LegMode.bus)
            modes.any { "FERRY" in it } -> modeIcon(LegMode.ferry)
            else -> modeIcon(LegMode.rail)
        }
    }
    PlaceMatch.Kind.address -> R.drawable.sym_location_on
    PlaceMatch.Kind.place -> R.drawable.sym_map
}

private fun biasFor(kind: SearchFor, pages: List<NavKey>, graph: AppGraph): Coordinate? {
    if (kind is SearchFor.ResultsEnd) {
        // The trip as it is now on the page (after any swap or edit), carried by the request.
        kind.near?.split(",")?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 }?.let { return Coordinate(it[0], it[1]) }
    }
    if (kind is SearchFor.Origin) Snapshot.decodePlace(kind.destination)?.let { return it.coordinate }
    return graph.locator.last()
}

@Composable
private fun PlaceRow(icon: Int, tint: Color, title: String, subtitle: String?, onClick: () -> Unit) {
    val c = polaris
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Symbol(icon, null, Modifier.size(24.dp), tint = tint)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium, color = polaris.inkSoft,
    )
}

@Composable
private fun Searching() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.common_searching), style = MaterialTheme.typography.bodyMedium, color = polaris.inkSoft)
    }
}

@Composable
private fun ErrorRow(reason: String, retry: () -> Unit) {
    val c = polaris
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Symbol(R.drawable.sym_wifi_off, null, Modifier.size(24.dp), tint = c.warning)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.search_failed), style = MaterialTheme.typography.bodyLarge, color = c.ink)
            // "HTTP 503" alone means little: say who didn't answer, with the code for anyone who reports it.
            Text(if (reason.startsWith("HTTP")) stringResource(R.string.search_transitous_down) else reason, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        }
        TextButton(onClick = retry) { Text(stringResource(R.string.search_try_again)) }
    }
}
