package polaris.android.shell

import polaris.android.settings.SettingsScreen
import polaris.android.settings.LicencesScreen
import polaris.android.settings.SetupScreen
import polaris.android.journey.JourneyScreen
import polaris.android.go.GoScreen
import polaris.android.journey.BoardScreen
import polaris.android.journey.LineScreen
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.IconButton
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import androidx.compose.ui.res.stringResource
import polaris.android.R
import polaris.android.app.AppGraph
import polaris.android.home.HomeScreen
import polaris.android.map.PolarisMap
import polaris.android.results.ResultsScreen
import polaris.android.search.PlaceSearchScreen
import polaris.android.ui.HalfDialog
import polaris.android.ui.Symbol
import polaris.android.ui.polaris
import polaris.engine.Snapshot

/** The window's pages: the map with its sheet, and a place search over it. */
@Serializable data object MainWindow : NavKey
@Serializable data class SearchWindow(val kind: SearchFor) : NavKey
/** Settings, the licences under it, and first-run setup: cover pages over the map (over the leading card when wide). */
@Serializable data object SettingsWindow : NavKey
@Serializable data object LicencesWindow : NavKey
@Serializable data object SetupWindow : NavKey

/** Builds the app's view models from the graph; every screen's model takes its dependencies directly. */
inline fun <reified T : androidx.lifecycle.ViewModel> factory(crossinline make: (androidx.lifecycle.SavedStateHandle) -> T) =
    viewModelFactory { initializer { make(createSavedStateHandle()) } }

/**
 * One map, one sheet, as on iOS (`RootView`): the map edge to edge under a sheet whose pages push
 * inside it (Home, then the routes, then a route). System back and predictive back return
 * through them. What the map shows is set by the page on top.
 *
 * Wide (an unfolded foldable held wide, a tablet in landscape) the sheet becomes a card on the
 * leading half, stopping short of the fold, with the map whole underneath; tall-open and phones
 * use the sheet.
 */
@Composable
fun PolarisApp(
    graph: AppGraph,
    night: Boolean,
    startTrip: polaris.engine.JourneyQuery? = null,
    showSetup: Boolean = false,
    goRequest: polaris.android.GoRequest? = null,
    goRequestTaken: () -> Unit = {},
) {
    val shell: ShellViewModel = viewModel(factory = factory { ShellViewModel(graph) })
    // First-run setup is over everything on a fresh start; after that the saved stack decides.
    val window = rememberNavBackStack(*(if (showSetup) arrayOf<NavKey>(MainWindow, SetupWindow) else arrayOf<NavKey>(MainWindow)))
    val pages = rememberNavBackStack(HomePage)
    val scene = remember { MapScene() }

    LaunchedEffect(shell) {
        shell.navigation.collect { page ->
            // Never the same trip twice on top of itself.
            val top = pages.lastOrNull()
            if (!(page is ResultsPage && top is ResultsPage && top.query == page.query)) pages.add(page)
        }
    }
    LaunchedEffect(pages.lastOrNull()) { shell.topChanged(pages.lastOrNull() is HomePage) }
    // A debug check's trip, opened once on a fresh start (never in release builds).
    var startedTrip by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startTrip) {
        if (startTrip != null && !startedTrip && pages.size == 1) pages.add(ResultsPage(startTrip))
        startedTrip = true
    }
    // A trip the app was stopped during: back in Go, on top of whatever the saved stack had.
    val tripOpens by graph.trips.opens.collectAsStateLifecycle()
    // Before any page is composed: a Go page restored with the stack finds its trip already back,
    // rather than closing itself for want of one (and losing its sheet height and scroll place).
    remember(graph) { graph.trips.resumePending() }
    // Go's notification tapped: Go on top, and Switch taken there (after a cold launch the trip is
    // the resumed one, whose offer wasn't kept: it says the faster way has gone).
    LaunchedEffect(goRequest) {
        val request = goRequest ?: return@LaunchedEffect
        goRequestTaken()
        graph.trips.resumePending()
        val trip = graph.trips.trip.value ?: return@LaunchedEffect
        // A verified tap takes the offer; an unverified one (after a cold launch the token is new)
        // only reaches a trip with no live offer, which then says the faster way has gone.
        if (request.switch && (request.verified || trip.offer == null)) trip.acceptOffer()
        if (pages.lastOrNull() != GoPage) {
            pages.removeAll { it == GoPage }
            pages.add(GoPage)
        }
    }
    // A trip that ended by itself (arrived, or past its time): only the trip's own page goes, and
    // whatever was open under it (the route, its routes, Home) is where the message shows.
    val endedItself by graph.trips.endedItself.collectAsStateLifecycle()
    var endingsSeen by rememberSaveable { mutableStateOf(endedItself) }
    LaunchedEffect(endedItself) {
        if (endedItself == endingsSeen) return@LaunchedEffect
        endingsSeen = endedItself
        pages.removeAll { it == GoPage }
    }
    LaunchedEffect(tripOpens) {
        if (!tripOpens) return@LaunchedEffect
        graph.trips.opened()
        if (pages.lastOrNull() != GoPage) {
            pages.removeAll { it == GoPage }
            pages.add(GoPage)
        }
    }
    LaunchedEffect(shell) {
        shell.closedSearches.collect { kind -> window.removeAll { it is SearchWindow && it.kind == kind } }
    }
    // Pages' archived searches that are no longer on the stack are deleted.
    LaunchedEffect(pages.toList()) {
        val keep = pages.filterIsInstance<ResultsPage>().map { it.id }.toSet()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { graph.archive.keepOnly(keep) }
    }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        shell.permissionResult(granted = result.values.any { it } || graph.locator.hasPermission, answered = result.isNotEmpty())
    }
    val need by shell.need.collectAsStateLifecycle()
    LaunchedEffect(need) {
        if (need?.ask == true) {
            shell.asking()
            ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    val openSearch: (SearchFor) -> Unit = { kind -> if (window.lastOrNull() !is SearchWindow) window.add(SearchWindow(kind)) }
    val openSettings: () -> Unit = { if (window.lastOrNull() == MainWindow) window.add(SettingsWindow) }
    val cover = DialogSceneStrategy.dialog(DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false))

    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val hinges = currentWindowAdaptiveInfo().windowPosture.hingeList
    // A flat fold reports isSeparating false; the card still stops short of it.
    val hinge = hinges.firstOrNull { it.isVertical }
    // A fold running across the window (an unfolded book-style phone turned tall): the phone
    // layout with more room, as the proposal decides for tall-open, not a card above the fold.
    // With no hinge reported, the window's shape decides: a book-style foldable open and turned on
    // its side is near-square and wider than tall (its fold now runs across), so it is open tall.
    val foldAcross = hinges.any { !it.isVertical }
    val foldBottom = hinges.firstOrNull { !it.isVertical }?.bounds?.bottom
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Wide: an unfolded phone held wide, a tablet in landscape, and a phone in landscape (under
    // 480 dp tall a sheet leaves no room for a route card; a card beside the map does).
    val turnedOpen = hinges.isEmpty() && maxWidth >= 840.dp && maxHeight >= 840.dp && maxWidth > maxHeight && maxHeight >= maxWidth * 0.8f
    val wide = !foldAcross && !turnedOpen && ((maxWidth >= 840.dp && maxHeight >= 480.dp) || (maxHeight < 480.dp && maxWidth >= 600.dp))
    val widthPx = constraints.maxWidth.toFloat()
    // Up to the fold, 48 dp short of it; with no fold, 400 dp (less on a short landscape phone). The card starts 16 dp in.
    // A book-style foldable held open wide whose fold WindowManager didn't report (it can be late
    // or silent): a near-square wide window up to 1000 dp is laid out as if the fold were in the
    // middle, so the card's width and the map half are the same whether or not it was reported.
    val unreportedFold = hinge == null && maxWidth >= 840.dp && maxWidth <= 1000.dp && maxHeight >= maxWidth * 0.8f
    val foldStart = hinge?.bounds?.let { if (direction == LayoutDirection.Ltr) it.left else widthPx - it.right }
        ?: if (unreportedFold) widthPx / 2 else null
    val foldEnd = hinge?.bounds?.let { if (direction == LayoutDirection.Ltr) it.right else widthPx - it.left }
        ?: if (unreportedFold) widthPx / 2 else null
    val cardWidth: Dp = if (!wide) Dp.Unspecified else with(density) {
        if (foldStart != null) (foldStart.toDp() - 48.dp - 16.dp).coerceIn(320.dp, 560.dp) else minOf(400.dp, maxWidth * 0.45f)
    }
    // The map's own half starts past the fold, so its credit and framed routes never straddle it.
    // The card sits inside the safe area (a camera cutout on the leading side in landscape), so the map half starts past both.
    val safeStart = WindowInsets.safeDrawing.asPaddingValues().calculateStartPadding(direction)
    val besideCard: Dp = if (!wide) 0.dp else with(density) { maxOf(safeStart + 16.dp + cardWidth + 16.dp, (foldEnd?.toDp() ?: 0.dp) + 16.dp) }
    // A wide window whose fold wasn't reported (WindowManager can be late or silent): the map half
    // still never starts within 24 dp of the middle, where a book-style fold would be, so its credit
    // and what it frames stay clear of a hinge there. On a tablet this only nudges the map's start.
    val middle = maxWidth / 2
    val mapStart: Dp = if (wide && hinge == null && besideCard > middle - 24.dp && besideCard < middle + 24.dp) middle + 24.dp else besideCard
    val layout = WindowLayout(wide = wide, short = maxHeight < 480.dp, cardWidth = cardWidth, mapStart = mapStart, safeStart = safeStart, foldBottomPx = foldBottom ?: if (turnedOpen) constraints.maxHeight / 2f else null)
    CompositionLocalProvider(LocalWindowLayout provides layout) {
    NavDisplay(
        backStack = window,
        onBack = { window.removeLastOrNull() },
        sceneStrategies = listOf(DialogSceneStrategy()),
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider = entryProvider {
            entry<MainWindow> {
                CompositionLocalProvider(LocalScene provides scene) {
                    MapAndSheet(graph, shell, pages, night, openSearch, openSettings)
                }
            }
            entry<SearchWindow>(metadata = DialogSceneStrategy.dialog(DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false))) { key ->
                PlaceSearchScreen(
                    graph = graph,
                    shell = shell,
                    kind = key.kind,
                    pages = pages,
                    night = night,
                    onClose = { window.removeAll { it == key } },
                )
            }
            entry<SettingsWindow>(metadata = cover) {
                SettingsScreen(
                    graph, shell, night, openSearch,
                    openLicences = { if (window.lastOrNull() == SettingsWindow) window.add(LicencesWindow) },
                    onClose = { window.removeAll { it == SettingsWindow || it == LicencesWindow } },
                )
            }
            entry<LicencesWindow>(metadata = cover) {
                LicencesScreen(night) { window.removeAll { it == LicencesWindow } }
            }
            entry<SetupWindow>(metadata = cover) {
                SetupScreen(graph, night) { window.removeAll { it == SetupWindow } }
            }
        },
    )
    when (val n = need) {
        is ShellViewModel.Need.Stranded -> CompositionLocalProvider(LocalWindowLayout provides layout) {
            HalfDialog(
                onDismissRequest = shell::dismissNeed,
                title = stringResource(R.string.location_no_fix_title),
                confirmButton = { TextButton({ openSearch(shell.chooseStart(n.destination)) }) { Text(stringResource(R.string.location_choose_start)) } },
                dismissButton = { TextButton(shell::dismissNeed) { Text(stringResource(R.string.common_cancel)) } },
            ) { Text(if (n.locationOff) stringResource(R.string.location_off_pick_start) else stringResource(R.string.location_pick_start)) }
        }
        ShellViewModel.Need.LocationOff -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            CompositionLocalProvider(LocalWindowLayout provides layout) {
            HalfDialog(
                onDismissRequest = shell::dismissNeed,
                title = stringResource(R.string.location_off_title),
                confirmButton = {
                    TextButton({
                        shell.dismissNeed()
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)),
                            )
                        }
                    }) { Text(stringResource(R.string.location_open_settings)) }
                },
                dismissButton = { TextButton(shell::dismissNeed) { Text(stringResource(R.string.location_not_now)) } },
            ) { Text(stringResource(R.string.location_off_body)) }
            }
        }
        else -> Unit
    }
    }
    }

}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateLifecycle(): androidx.compose.runtime.State<T> = collectAsStateWithLifecycle()

/** Which rest a page asks for when it comes on top: Home goes back to the person's own, the others to half. */
private fun preferredRest(key: NavKey?): Rest? = when (key) {
    null, is HomePage -> null
    // Go's rest is the low bar (its header and the step button), as iOS's Go rest is: the map leads.
    is GoPage -> Rest.Low
    else -> Rest.Half
}

@Composable
private fun MapAndSheet(
    graph: AppGraph,
    shell: ShellViewModel,
    pages: androidx.navigation3.runtime.NavBackStack<NavKey>,
    night: Boolean,
    openSearch: (SearchFor) -> Unit,
    openSettings: () -> Unit,
) {
    val scene = LocalScene.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    val top = pages.lastOrNull()
    val content = scene.of(top)
    val showsLocation by shell.showsLocation.collectAsStateLifecycle()
    val recentre by shell.recentre.collectAsStateLifecycle()
    val sheet = rememberSaveable(saver = SheetState.Saver) { SheetState(Rest.Half, Rest.Half) }
    val bars = remember { mutableStateMapOf<NavKey, Int>() }
    val movablePages = remember(graph, shell, pages) {
        movableContentOf { host: SheetHost ->
            CompositionLocalProvider(LocalSheet provides host) { SheetPages(graph, shell, pages, openSearch, openSettings) }
        }
    }
    var dockHeight by remember { mutableStateOf(0) }
    var bannerHeight by remember { mutableStateOf(0) }
    val banner = scene.bannerOf(top)

    // A page coming on top asks for its rest; a rotation (same page on top) asks for nothing.
    var lastTop by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(top) {
        val name = top.toString()
        if (lastTop == null) {
            lastTop = name
            return@LaunchedEffect
        }
        if (name == lastTop) return@LaunchedEffect
        lastTop = name
        val rest = preferredRest(top)
        if (rest == null) sheet.restore(scope) else sheet.request(scope, rest)
    }

    val layout = LocalWindowLayout.current

    BoxWithConstraints(Modifier.fillMaxSize().background(polaris.paper)) {
        val heightPx = constraints.maxHeight.toFloat()
        val wide = layout.wide
        val short = layout.short
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        val cardStart = 16.dp
        val cardWidth = layout.cardWidth

        if (!wide) {
            val barPx = bars[top] ?: with(density) { (72.dp).roundToPx() }
            val handlePx = with(density) { 28.dp.toPx() }
            val anchors = buildMap {
                put(Rest.Low, heightPx - (barPx + handlePx + with(density) { navBottom.toPx() }))
                // At the largest text the half rest sits higher (70% of the window), so the first route card
                // fits whole under the page's two-row trip and wrapped controls.
                // Open tall, the half rest is 48 dp below the fold, so neither the sheet's edge nor the map's
                // credit sits on it.
                val foldRest = layout.foldBottomPx?.let { it + with(density) { 48.dp.toPx() } }?.takeIf { it < heightPx * 0.7f }
                val half = foldRest ?: (heightPx * if (short) 0.42f else if (density.fontScale >= 1.5f) 0.30f else 0.52f)
                // Go's banner, however many lines it grows to, stays whole above the half rest, with the
                // credit and a strip of map under it, so the credit never sits on the banner.
                // 176 dp: the map fits only with 120 dp clear inside its padding (24 dp under the
                // banner, 32 dp over the credit), so your dot is framed above the credit.
                val underBanner = if (banner != null && bannerHeight > 0) with(density) { statusTop.toPx() + 12.dp.toPx() + bannerHeight + 176.dp.toPx() } else 0f
                put(Rest.Half, maxOf(half, underBanner).coerceAtMost(heightPx * 0.6f))
                // Full height covers the map (and with it the credit): the sheet runs under the status bar.
                if (!short) put(Rest.Full, 0f)
            }
            LaunchedEffect(anchors) { sheet.updateAnchors(scope, anchors) }
        }

        val host = remember(wide, sheet) {
            SheetHost(
                state = if (wide) null else sheet,
                wide = wide,
                requestRest = { sheet.request(scope, it) },
                restoreRest = { sheet.restore(scope) },
                onBar = { key, h -> bars[key] = h },
            )
        }

        // What covers the map, for its credit and for fitting routes into the part left showing.
        val bannerDp = if (banner != null) with(density) { bannerHeight.toDp() } + 8.dp else 0.dp
        val covered = if (wide) {
            PaddingValues(start = layout.mapStart, top = statusTop + 16.dp + bannerDp, end = 72.dp, bottom = navBottom)
        } else {
            val sheetTop = if (sheet.top.isNaN()) heightPx else sheet.top
            PaddingValues(top = statusTop + 8.dp + bannerDp, end = 64.dp, bottom = with(density) { (heightPx - sheetTop).coerceAtLeast(0f).toDp() })
        }

        PolarisMap(
            drawing = content.drawing,
            others = content.others,
            frame = content.frame,
            night = night,
            modifier = Modifier.fillMaxSize().semantics { isTraversalGroup = true; traversalIndex = 1f },
            contentPadding = covered,
            // Wide, routes are fitted above the docked card too (the credit stays at the foot).
            // Routes are framed above the credit too, so it never covers the start of a walk or your dot.
            fitPadding = if (wide && scene.dockOf(top) != null) PaddingValues(
                start = covered.calculateStartPadding(direction), top = covered.calculateTopPadding(), end = covered.calculateEndPadding(direction),
                bottom = with(density) { dockHeight.toDp() } + 64.dp,
            ) else PaddingValues(
                start = covered.calculateStartPadding(direction), top = covered.calculateTopPadding(), end = covered.calculateEndPadding(direction),
                bottom = covered.calculateBottomPadding() + 32.dp,
            ),
            // The docked card (wide) and Go's banner are measured after the routes come: a change
            // in either fits the frame again inside the room left.
            // On Go the step is framed again in whatever the sheet's rest leaves showing, so your dot and
            // the next point stay above the credit when the sheet rises (an offer raises it).
            refitOn = listOf(if (wide) dockHeight else null, if (banner != null) bannerHeight else null, if (banner != null && !wide) sheet.rest else null),
            showLocation = showsLocation,
            recentre = recentre,
            estimate = content.estimate,
            fitKey = content.fitKey,
            overlay = {
                // The status bar's clock and icons sit on a soft band of paper, not straight on map labels.
                // Opaque behind the bar itself, fading out over 16 dp below it, at every rest.
                val band = polaris.paper
                Box(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().height(statusTop + 16.dp)
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                0f to band.copy(alpha = 0.96f),
                                (statusTop / (statusTop + 16.dp)) to band.copy(alpha = 0.96f),
                                1f to band.copy(alpha = 0f),
                            ),
                        ),
                )
                banner?.let { card ->
                    // Go's instruction, across the top of the map (of the map half when wide), below the status bar.
                    Box(
                        Modifier.align(if (wide) Alignment.TopStart else Alignment.TopCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                            .padding(start = if (wide) layout.mapStart - layout.safeStart else 10.dp, end = 10.dp, top = 8.dp)
                            // A phone's width, centred, in a very wide phone layout (open tall), not a bar with its words in a third of it.
                            .then(if (wide) Modifier.widthIn(max = 520.dp) else Modifier.widthIn(max = 440.dp).fillMaxWidth())
                            .onSizeChanged { bannerHeight = it.height },
                    ) { card() }
                }
                LocationButton(
                    Modifier.align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                        .padding(top = 12.dp + bannerDp, end = 12.dp),
                ) { shell.locateMe() }
                if (wide) {
                    scene.dockOf(top)?.let { dock ->
                        // At the foot of the map half, above the map's credit.
                        Box(
                            Modifier.align(Alignment.BottomEnd)
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.End))
                                .padding(end = 16.dp, bottom = 64.dp, top = statusTop + 80.dp)
                                .widthIn(max = 380.dp),
                        ) { Box(Modifier.onSizeChanged { dockHeight = it.height }) { dock() } }
                    }
                }
            },
        )

        // One set of pages that moves between the sheet and the wide card when the phone folds or
        // turns, so each page keeps its state (its scroll position included) rather than starting again.
        val sheetContent: @Composable () -> Unit = { movablePages(host) }

        if (wide) {
            Surface(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
                    .padding(start = cardStart, top = if (short) 4.dp else 12.dp, bottom = if (short) 4.dp else 12.dp)
                    .width(cardWidth)
                    .fillMaxHeight()
                    .semantics { isTraversalGroup = true; traversalIndex = 0f },
                shape = RoundedCornerShape(28.dp),
                color = polaris.paper,
                shadowElevation = 6.dp,
                // At night a shadow doesn't show on the dark map: a hairline edge does.
                border = if (polaris.isNight) androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.16f)) else null,
            ) { sheetContent() }
        } else {
            val fullHeight = with(density) { (heightPx - (sheet.anchors[Rest.Full] ?: sheet.anchors[Rest.Half] ?: 0f)).toDp() }
            BottomSheet(
                sheet,
                Modifier.align(Alignment.TopCenter).height(fullHeight).semantics { isTraversalGroup = true; traversalIndex = 0f },
            ) { sheetContent() }
        }
    }
}

@Composable
private fun SheetPages(
    graph: AppGraph,
    shell: ShellViewModel,
    pages: androidx.navigation3.runtime.NavBackStack<NavKey>,
    openSearch: (SearchFor) -> Unit,
    openSettings: () -> Unit,
) {
    // Pages slide in from the end; going back, the page on top slides away over the one under it
    // (and follows the finger during predictive back). Each page is opaque, so they never overlap.
    val paper = polaris.paper
    val end = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val page: @Composable (@Composable () -> Unit) -> Unit = { content -> Box(Modifier.fillMaxSize().background(paper)) { content() } }
    NavDisplay(
        backStack = pages,
        onBack = { if (pages.size > 1) pages.removeLastOrNull() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        // From the end edge: the right, or the left in a right-to-left language.
        transitionSpec = { slideInHorizontally { it * end } togetherWith ExitTransition.KeepUntilTransitionsFinished },
        popTransitionSpec = { (EnterTransition.None togetherWith slideOutHorizontally { it * end }).apply { targetContentZIndex = -1f } },
        predictivePopTransitionSpec = { (EnterTransition.None togetherWith slideOutHorizontally { it * end }).apply { targetContentZIndex = -1f } },
        entryProvider = entryProvider {
            entry<HomePage> { key -> page { HomeScreen(key, graph, shell, openSearch, openSettings, openLine = { line -> if (pages.lastOrNull() == key) pages.add(LinePage(line.id, line.stopName)) }, openGo = { if (pages.lastOrNull() == key) pages.add(GoPage) }) } }
            entry<ResultsPage> { key ->
                page {
                    ResultsScreen(key, graph, shell, openSearch, onBack = { pages.removeLastOrNull() }) { journeyId ->
                        pages.add(JourneyPage(key.id, journeyId))
                    }
                }
            }
            entry<JourneyPage> { key ->
                page {
                    JourneyScreen(
                        key, graph, shell, onBack = { pages.removeLastOrNull() },
                        openGo = { if (pages.lastOrNull() == key) pages.add(GoPage) },
                    ) { leg ->
                        if (pages.lastOrNull() == key) pages.add(BoardPage(key.searchId, key.journeyId, leg.id))
                    }
                }
            }
            entry<GoPage> { key ->
                page {
                    GoScreen(key, graph, openSettings, onLeave = { pages.removeAll { it == GoPage } })
                }
            }
            entry<BoardPage> { key -> page { BoardScreen(key, graph, onBack = { pages.removeLastOrNull() }) } }
            entry<LinePage> { key -> page { LineScreen(key, graph, shell, onBack = { pages.removeLastOrNull() }) } }
        },
    )
}

/** The one floating control, top right, clear of the sheet at every rest. */
@Composable
private fun LocationButton(modifier: Modifier, onClick: () -> Unit) {
    val description = stringResource(R.string.location_show_mine)
    Surface(modifier.size(48.dp), shape = CircleShape, color = polaris.surface, shadowElevation = 3.dp) {
        IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
            Symbol(R.drawable.sym_my_location, null, tint = polaris.tint)
        }
    }
}
