package polaris.android

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import polaris.android.app.AppGraph
import polaris.android.app.Locator
import polaris.android.app.MemorySearchArchive
import polaris.android.results.ResultsViewModel
import polaris.android.search.PlaceSearchViewModel
import polaris.android.shell.ResultsPage
import polaris.android.shell.SearchFor
import polaris.android.shell.ShellViewModel
import polaris.engine.AppSettings
import polaris.engine.BuildDefaults
import polaris.engine.Coordinate
import polaris.engine.GoogleUsage
import polaris.engine.HttpRequest
import polaris.engine.HttpResponse
import polaris.engine.HttpTransport
import polaris.engine.InMemoryKeyValueStore
import polaris.engine.JourneyQuery
import polaris.engine.ModeGroup
import polaris.engine.NetworkStatusCache
import polaris.engine.Place
import polaris.engine.PlaceStore
import polaris.engine.ProviderID
import polaris.engine.ProviderStatus
import polaris.engine.RouteFilter
import polaris.engine.SortOrder
import polaris.engine.TimeMode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Answers from the shared recordings and counts what was sent, by host. */
class CountingTransport(var failTransitous: Boolean = false, var lineStatus: String? = null) : HttpTransport {
    val sent = mutableListOf<HttpRequest>()
    fun to(host: String) = synchronized(sent) { sent.count { it.host == host } }
    val total get() = synchronized(sent) { sent.size }

    override suspend fun send(request: HttpRequest): HttpResponse {
        synchronized(sent) { sent += request }
        val url = request.url
        val name = when {
            "/api/v1/geocode" in url -> "transitous-geocode-kings-cross.json"
            "api.transitous.org" in url -> if (failTransitous) return HttpResponse(503, ByteArray(0)) else "transitous-plan.json"
            "/Journey/JourneyResults/" in url -> "tfl-journeys.json"
            "/Line/Mode/" in url -> lineStatus?.let { return HttpResponse(200, it.encodeToByteArray()) } ?: "tfl-line-status.json"
            "/StopPoint/Mode/" in url -> "tfl-stop-disruptions.json"
            else -> return HttpResponse(404, ByteArray(0))
        }
        val stream = javaClass.classLoader!!.getResourceAsStream(name) ?: error("fixture $name")
        return HttpResponse(200, stream.use { it.readBytes() })
    }
}

class FakeLocator(var here: Coordinate? = Coordinate(51.53, -0.12), override var hasPermission: Boolean = true) : Locator {
    var asked = 0
    override suspend fun current(): Coordinate? {
        asked++
        return if (hasPermission) here else null
    }
    override fun last(): Coordinate? = if (hasPermission) here else null
}

/**
 * The screens' view models, on the JVM. The request budgets in the README are rules, so each
 * test counts what reached the network through the view model the screen uses: one search per
 * person's action, Transitous once per search, nothing for sorting, filtering, picking a route,
 * folding "more", or the view model being recreated after Android stopped the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreensTest {
    private val dispatcher = StandardTestDispatcher()
    private val kingsCross = Place(id = "kx", name = "King's Cross", coordinate = Coordinate(51.5308, -0.1238))
    private val wharf = Place(id = "cw", name = "Canary Wharf", coordinate = Coordinate(51.5054, -0.0235))
    private val trip = JourneyQuery(kingsCross, wharf, TimeMode.DepartAt(java.time.Instant.parse("2026-10-28T11:55:00Z")))

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Rig(
        val transport: CountingTransport = CountingTransport(),
        val locator: FakeLocator = FakeLocator(),
        val kv: InMemoryKeyValueStore = InMemoryKeyValueStore(),
        val archive: MemorySearchArchive = MemorySearchArchive(),
    ) {
        // No Google key, as in a stranger's build: Google is off and sends nothing.
        val settings = AppSettings(build = BuildDefaults(contact = "https://example.org/polaris"), preferences = kv)
        // Each rig its own TfL status cache, so tests don't share the app-wide one.
        val graph = AppGraph(settings, GoogleUsage(kv), PlaceStore(kv), transport, archive, locator, networkCache = NetworkStatusCache())
    }

    private fun TestScope.results(rig: Rig, handle: SavedStateHandle = SavedStateHandle(), id: String = "page-1", query: JourneyQuery = trip) =
        ResultsViewModel(handle, rig.graph, id, query, io = dispatcher).also { advanceUntilIdle() }

    @Test fun openingTheResultsPageSearchesOnceAndTheRoutesArrive() = runTest(dispatcher) {
        val rig = Rig()
        val model = results(rig)
        assertEquals(1, rig.transport.to("api.transitous.org"))
        // Deep search is on by default: TfL is asked three ways, plus the two status requests.
        assertEquals(5, rig.transport.to("api.tfl.gov.uk"))
        assertEquals(0, rig.transport.to("routes.googleapis.com"))
        val ui = model.ui.value
        assertTrue(ui.ranked.top.isNotEmpty())
        assertIs<ProviderStatus.Off>(ui.statuses[ProviderID.google])
        assertNotNull(ui.best)
    }

    @Test fun sortingFilteringSelectingAndFoldingSendNothing() = runTest(dispatcher) {
        val rig = Rig()
        val model = results(rig)
        val before = rig.transport.total
        model.setSort(SortOrder.fewestChanges)
        model.setFilter(RouteFilter(maxChanges = 0, avoided = setOf(ModeGroup.bus)))
        model.select(model.ui.value.journeys.first().id)
        model.toggleMore()
        model.setFilter(RouteFilter())
        advanceUntilIdle()
        assertEquals(before, rig.transport.total)
        // And the order and filters are remembered for the next search, as on iOS.
        assertEquals(SortOrder.fewestChanges, rig.settings.sortOrder)
        assertEquals(SortOrder.fewestChanges, AppSettings(preferences = rig.kv).sortOrder)
    }

    @Test fun filtersHideRoutesAndSayHowMany() = runTest(dispatcher) {
        val rig = Rig()
        val model = results(rig)
        model.setFilter(RouteFilter(maxChanges = 0))
        val ui = model.ui.value
        assertTrue(ui.ranked.hidden > 0)
        assertTrue((ui.ranked.top + ui.ranked.more).all { it.journey.changes == 0 })
    }

    @Test fun eachEditIsOneSearch() = runTest(dispatcher) {
        val rig = Rig()
        val model = results(rig)
        model.swap(); advanceUntilIdle()
        model.setTime(TimeMode.ArriveBy(java.time.Instant.parse("2026-10-28T13:00:00Z"))); advanceUntilIdle()
        model.setDestination(Place(name = "Bank", coordinate = Coordinate(51.513, -0.089))); advanceUntilIdle()
        model.searchAgain(); advanceUntilIdle()
        assertEquals(5, rig.transport.to("api.transitous.org"))
        // The same trip again is no edit and no search.
        model.setTime(model.ui.value.query.time); advanceUntilIdle()
        assertEquals(5, rig.transport.to("api.transitous.org"))
    }

    @Test fun tflStatusIsKeptThreeMinutesAcrossSearches() = runTest(dispatcher) {
        val rig = Rig()
        val model = results(rig)
        val status = rig.transport.sent.count { "/Line/Mode/" in it.url }
        model.searchAgain(); advanceUntilIdle()
        // The status is kept 3 minutes (one cache for the app), so a second search adds none.
        assertEquals(1, status)
        assertEquals(status, rig.transport.sent.count { "/Line/Mode/" in it.url })
    }

    @Test fun aRecreatedPageShowsTheSameRoutesWithoutAskingAgain() = runTest(dispatcher) {
        val rig = Rig()
        val handle = SavedStateHandle()
        val first = results(rig, handle)
        first.select(first.ui.value.ranked.top.last().id)
        val sent = rig.transport.total
        // Android stopped the app: a new view model from the same saved state and archive.
        val again = results(rig, SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        assertEquals(sent, rig.transport.total)
        assertEquals(first.ui.value.ranked.top.map { it.id }, again.ui.value.ranked.top.map { it.id })
        assertEquals(first.ui.value.selectedId, again.ui.value.selectedId)
    }

    @Test fun theArchivedCopyIsTheLastStateNotAnEarlierOne() = runTest(dispatcher) {
        // Writes that land out of order: the archive records every save, in the order they land.
        class Recording : polaris.android.app.SearchArchive {
            val saves = mutableListOf<polaris.engine.Snapshot.Search>()
            var held: polaris.engine.Snapshot.Search? = null
            override fun hold(id: String, search: polaris.engine.Snapshot.Search) { held = search }
            override fun save(id: String, search: polaris.engine.Snapshot.Search) { saves += search }
            override fun load(id: String) = saves.lastOrNull()
        }
        val archive = Recording()
        val rig = Rig()
        val graph = AppGraph(rig.settings, GoogleUsage(rig.kv), PlaceStore(rig.kv), rig.transport, archive, rig.locator, networkCache = NetworkStatusCache())
        val model = ResultsViewModel(SavedStateHandle(), graph, "page", trip, io = dispatcher).also { advanceUntilIdle() }
        val final = model.ui.value.journeys.map { it.id }
        assertEquals(final, archive.held!!.journeys.map { it.id })
        assertEquals(final, archive.saves.last().journeys.map { it.id })
        assertTrue(archive.saves.size <= archive.saves.map { it.savedAt }.size)
    }

    @Test fun aRecreatedPageWhoseCopyIsGoneOffersSearchAgainAndSendsNothing() = runTest(dispatcher) {
        val rig = Rig()
        val handle = SavedStateHandle()
        results(rig, handle)
        rig.archive.saved.clear()
        val sent = rig.transport.total
        val again = results(rig, SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        assertEquals(sent, rig.transport.total)
        assertTrue(again.ui.value.stale)
        val transitous = rig.transport.to("api.transitous.org")
        again.searchAgain(); advanceUntilIdle()
        assertEquals(transitous + 1, rig.transport.to("api.transitous.org"))
        assertTrue(again.ui.value.journeys.isNotEmpty())
    }

    @Test fun aTripFromHereGetsAFreshFixAndNoSearchWithoutOne() = runTest(dispatcher) {
        val rig = Rig()
        val here = ShellViewModel.currentLocation(Coordinate(0.0, 0.0))
        val model = results(rig, query = trip.copy(origin = here))
        assertEquals(1, rig.locator.asked)
        val sent = rig.transport.sent.first { "api.transitous.org" in it.url }.url
        assertTrue("51.53" in sent, "searched from the fresh fix, not the placeholder")

        val lost = Rig(locator = FakeLocator(here = null))
        val stuck = results(lost, query = trip.copy(origin = here))
        assertEquals(0, lost.transport.total)
        assertTrue(stuck.ui.value.noFix)
    }

    /** On a device the main dispatcher runs at once, so a search may finish inside the constructor. */
    @Test fun theResultsPageCanBeBuiltOnAnImmediateMainDispatcher() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val rig = Rig()
        val model = ResultsViewModel(SavedStateHandle(), rig.graph, "page", trip, io = UnconfinedTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertTrue(model.ui.value.journeys.isNotEmpty())
        assertEquals(model.ui.value.journeys.map { it.id }, rig.archive.load("page")!!.journeys.map { it.id })
    }

    @Test fun aRouteThroughAClosureLosesTheStarAndGoesUnderTheOthers() = runTest(dispatcher) {
        val closed = """[{"name":"Elizabeth line","modeName":"elizabeth-line","lineStatuses":[{"statusSeverity":20,"statusSeverityDescription":"Service Closed","reason":"No service."}]}]"""
        val open = results(Rig()).ui.value
        assertTrue(open.best!!.journey.transitLegs.any { it.line?.contains("Elizabeth") == true }, "the recordings' best route uses the Elizabeth line")
        val ui = results(Rig(transport = CountingTransport(lineStatus = closed))).ui.value
        val best = assertNotNull(ui.best)
        assertTrue(!best.journey.isBlocked && best.journey.transitLegs.none { it.line?.contains("Elizabeth") == true })
        assertTrue(ui.ranked.more.any { it.journey.isBlocked })
        assertTrue(ui.ranked.top.none { it.journey.isBlocked })
    }

    @Test fun aSourceThatFailsSaysSoAndTheOthersStillShow() = runTest(dispatcher) {
        val rig = Rig(transport = CountingTransport(failTransitous = true))
        val model = results(rig)
        assertIs<ProviderStatus.Failed>(model.ui.value.statuses[ProviderID.transitous])
        assertTrue(model.ui.value.journeys.isNotEmpty())
        assertTrue("Transitous didn't answer" in polaris.android.results.summary(model.ui.value))
    }

    @Test fun placeSearchSendsOneRequestPerPauseAndNothingUnderThreeLetters() = runTest(dispatcher) {
        val rig = Rig()
        val model = PlaceSearchViewModel(SavedStateHandle(), rig.graph, null)
        model.type("k"); model.type("ki")
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0, rig.transport.total)
        for (text in listOf("kin", "king", "kings", "kings c", "kings cross")) {
            model.type(text)
            advanceTimeBy(100); runCurrent()
        }
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, rig.transport.to("api.transitous.org"))
        assertTrue(rig.transport.sent.single().url.contains("text=kings%20cross") || rig.transport.sent.single().url.contains("text=kings+cross"))
        assertTrue(model.ui.value.results.isNotEmpty())
        assertEquals(null, model.ui.value.error)
    }

    @Test fun placeSearchTextComesBackAfterARestartWithoutBeingSearched() = runTest(dispatcher) {
        val rig = Rig()
        val model = PlaceSearchViewModel(SavedStateHandle(mapOf(PlaceSearchViewModel.TEXT to "kings cross")), rig.graph, null)
        advanceUntilIdle()
        assertEquals(0, rig.transport.total)
        assertTrue(model.ui.value.restored)
        model.searchRestored()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, rig.transport.total)
    }

    @Test fun theMapsAppLinkCarriesTheWordsAndTheBias() {
        assertEquals("geo:51.5,-0.12?q=apple%20store", PlaceSearchViewModel.mapsLink(" apple store ", Coordinate(51.5, -0.12)))
        assertEquals("geo:0,0?q=pharmacy", PlaceSearchViewModel.mapsLink("pharmacy", null))
    }

    @Test fun homeSendsNothingAndAsksForLocationOnlyWhenPlanningFromHere() = runTest(dispatcher) {
        val rig = Rig(locator = FakeLocator(hasPermission = false))
        val shell = ShellViewModel(rig.graph)
        advanceUntilIdle()
        assertEquals(0, rig.transport.total)
        assertEquals(0, rig.locator.asked)
        assertEquals(null, shell.need.value)

        shell.picked(SearchFor.Destination, wharf)
        assertIs<ShellViewModel.Need.Trip>(shell.need.value)
        assertEquals(listOf(wharf), rig.graph.places.recents)
        // Refused: "Can't find where you are", and Choose a start opens the start's search.
        shell.permissionResult(false)
        val stranded = assertIs<ShellViewModel.Need.Stranded>(shell.need.value)
        assertTrue(stranded.locationOff)
        assertIs<SearchFor.Origin>(shell.chooseStart(stranded.destination))
        assertEquals(0, rig.transport.total)
    }

    @Test fun aRefusedLocationButtonOrCurrentLocationSaysLocationIsOff() = runTest(dispatcher) {
        val rig = Rig(locator = FakeLocator(hasPermission = false))
        val shell = ShellViewModel(rig.graph)
        shell.locateMe()
        assertIs<ShellViewModel.Need.Map>(shell.need.value)
        shell.permissionResult(false)
        assertEquals(ShellViewModel.Need.LocationOff, shell.need.value)
        shell.dismissNeed()
        shell.pickCurrentLocation(SearchFor.ResultsEnd("page", isOrigin = true))
        shell.permissionResult(false)
        assertEquals(ShellViewModel.Need.LocationOff, shell.need.value)
        assertEquals(null, shell.endPick.value)
    }

    @Test fun currentLocationPickedForAResultsEndReachesThatPage() = runTest(dispatcher) {
        val rig = Rig()
        val shell = ShellViewModel(rig.graph)
        shell.pickCurrentLocation(SearchFor.ResultsEnd("page", isOrigin = true))
        val pick = assertNotNull(shell.endPick.value)
        assertEquals("page", pick.page)
        assertTrue(pick.isOrigin && pick.place.isCurrentLocation)
        assertEquals(0, rig.transport.total)
    }

    @Test fun planningFromHerePushesOneResultsPage() = runTest(dispatcher) {
        val rig = Rig()
        val shell = ShellViewModel(rig.graph)
        shell.planTo(wharf)
        advanceUntilIdle()
        val page = assertIs<ResultsPage>(shell.navigation.first())
        assertTrue(page.journeyQuery!!.origin.isCurrentLocation)
        assertEquals(0, rig.transport.total, "the shell only opens the page; the page searches")
    }

    @Test fun homeAndWorkAreSetChangedAndCleared() = runTest(dispatcher) {
        val rig = Rig()
        val shell = ShellViewModel(rig.graph)
        shell.picked(SearchFor.Home, kingsCross)
        shell.picked(SearchFor.Work, wharf)
        assertEquals(kingsCross, shell.places.value.home)
        shell.picked(SearchFor.Home, wharf)
        assertEquals(wharf, PlaceStore(rig.kv).home)
        shell.setWork(null)
        assertEquals(null, PlaceStore(rig.kv).work)
        assertEquals(0, rig.transport.total)
    }
}
