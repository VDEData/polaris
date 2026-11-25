package polaris.android

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import polaris.android.app.AppGraph
import polaris.android.app.MemorySearchArchive
import polaris.android.journey.JourneyViewModel
import polaris.android.results.ResultsViewModel
import polaris.android.settings.SettingsViewModel
import polaris.android.shell.ShellViewModel
import polaris.engine.AppSettings
import polaris.engine.BuildDefaults
import polaris.engine.Coordinate
import polaris.engine.GoogleUsage
import polaris.engine.HttpRequest
import polaris.engine.HttpResponse
import polaris.engine.HttpTransport
import polaris.engine.InMemoryKeyValueStore
import polaris.engine.InMemorySecretStore
import polaris.engine.JourneyQuery
import polaris.engine.Loadable
import polaris.engine.NetworkStatusCache
import polaris.engine.Place
import polaris.engine.PlaceStore
import polaris.engine.SecretRead
import polaris.engine.SecretStore
import polaris.engine.TimeMode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The journey page, boards, "Leaving near you" and Settings, through the view models the screens
 * use, on the JVM. Request budgets counted at the transport: one board per ride when a journey
 * opens, nothing more for a recreated page or a board page within the minute, one nearby request
 * once location is allowed and none without it. Keys: saved, kept when unreadable, refused when
 * not saved, and never put in the saved instance state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JourneyAndSettingsTest {
    private val dispatcher = StandardTestDispatcher()
    private val kingsCross = Place(id = "kx", name = "King's Cross", coordinate = Coordinate(51.5308, -0.1238))
    private val wharf = Place(id = "cw", name = "Canary Wharf", coordinate = Coordinate(51.5054, -0.0235))
    private val trip = JourneyQuery(kingsCross, wharf, TimeMode.DepartAt(java.time.Instant.parse("2026-10-28T11:55:00Z")))

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** The shared recordings, plus stop times for boards and nearby; counts stop-times requests apart. */
    private class Transport : HttpTransport {
        val routes = CountingTransport()
        var boards = 0
        var nearby = 0
        override suspend fun send(request: HttpRequest): HttpResponse {
            if ("/api/v6/stoptimes" !in request.url) return routes.send(request)
            val board = "fetchStops=true" in request.url
            if (board) boards++ else nearby++
            val name = if (board) "transitous-departures.json" else "transitous-nearby.json"
            return HttpResponse(200, javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() })
        }
    }

    private class Rig(secrets: SecretStore = InMemorySecretStore(), locator: FakeLocator = FakeLocator(), scope: CoroutineScope) {
        val transport = Transport()
        val kv = InMemoryKeyValueStore()
        val archive = MemorySearchArchive()
        val settings = AppSettings(secrets, BuildDefaults(contact = "https://example.org/polaris"), kv)
        val graph = AppGraph(settings, GoogleUsage(kv), PlaceStore(kv), transport, archive, locator, networkCache = NetworkStatusCache(), background = scope)
    }

    private fun TestScope.rig(secrets: SecretStore = InMemorySecretStore(), locator: FakeLocator = FakeLocator()) =
        Rig(secrets, locator, CoroutineScope(SupervisorJob() + dispatcher))

    /** Opens the recorded trip's results so its search is in the archive, as a results page leaves it. */
    private fun TestScope.searched(rig: Rig): ResultsViewModel =
        ResultsViewModel(SavedStateHandle(), rig.graph, "page-1", trip, io = dispatcher).also { advanceUntilIdle() }

    @Test fun aJourneyPageAsksForOneBoardPerRideAndNoMoreWhenRecreated() = runTest(dispatcher) {
        val rig = rig()
        val results = searched(rig)
        val journey = results.ui.value.ranked.top.first().journey
        val rides = journey.transitLegs.size
        assertTrue(rides >= 1)

        val page = JourneyViewModel(rig.graph, "page-1", journey.id, io = dispatcher).also { advanceUntilIdle() }
        assertEquals(journey.id, page.ui.value.journey?.id)
        assertEquals(rides, rig.transport.boards, "one board per ride")
        assertEquals(rides, page.ui.value.boards.size)

        // The page recreated (a rotation, a fold) and a ride's board page: within the minute, nothing more.
        JourneyViewModel(rig.graph, "page-1", journey.id, io = dispatcher).also { advanceUntilIdle() }
        val board = JourneyViewModel(rig.graph, "page-1", journey.id, onlyLeg = journey.transitLegs.first().id, io = dispatcher).also { advanceUntilIdle() }
        assertEquals(rides, rig.transport.boards)
        assertNotNull(board.ui.value.boards[journey.transitLegs.first().id])

        // The board's Refresh right after it loaded is held by the 15 s floor (the store's own test covers it after).
        board.refresh(journey.transitLegs.first())
        advanceUntilIdle()
        assertEquals(rides, rig.transport.boards)
    }

    @Test fun aBoardPageOnItsOwnAsksForItsOneRide() = runTest(dispatcher) {
        val rig = rig()
        val journey = searched(rig).ui.value.ranked.top.first { it.journey.transitLegs.size > 1 }.journey
        JourneyViewModel(rig.graph, "page-1", journey.id, onlyLeg = journey.transitLegs.last().id, io = dispatcher)
        advanceUntilIdle()
        assertEquals(1, rig.transport.boards)
    }

    @Test fun aJourneyWhoseSearchIsGoneSaysSoAndAsksNothing() = runTest(dispatcher) {
        val rig = rig()
        val page = JourneyViewModel(rig.graph, "gone", "nothing", io = dispatcher).also { advanceUntilIdle() }
        assertFalse(page.ui.value.loading)
        assertNull(page.ui.value.journey)
        assertEquals(0, rig.transport.boards)
        assertEquals(0, rig.transport.routes.total)
    }

    @Test fun leavingNearYouAsksOnceWithLocationAndNeverWithout() = runTest(dispatcher) {
        val without = rig(locator = FakeLocator(hasPermission = false))
        ShellViewModel(without.graph).refreshNearby()
        advanceUntilIdle()
        assertEquals(0, without.transport.nearby)
        assertEquals(0, without.graph.locator.let { (it as FakeLocator).asked })

        val rig = rig()
        val shell = ShellViewModel(rig.graph)
        shell.refreshNearby(); advanceUntilIdle()
        shell.refreshNearby(); advanceUntilIdle()
        assertEquals(1, rig.transport.nearby, "kept a minute")
        assertTrue(rig.graph.departures.nearby is Loadable.Loaded)
        shell.refreshNearby(force = true); advanceUntilIdle()
        assertEquals(1, rig.transport.nearby, "Refresh within the floor sends nothing")
        assertEquals(0, rig.transport.routes.total, "and nothing else")
    }

    @Test fun aKeyIsSavedAsTypedAndNeverKeptInTheSavedState() = runTest(dispatcher) {
        val rig = rig()
        val handle = SavedStateHandle()
        val model = SettingsViewModel(handle, rig.graph, io = dispatcher).also { advanceUntilIdle() }
        assertEquals(SettingsViewModel.KeyState.notSet, model.ui.value.field(SettingsViewModel.Item.tfl).state)
        model.type(SettingsViewModel.Item.tfl, "a")
        model.type(SettingsViewModel.Item.tfl, "abc123")
        model.flush(); advanceUntilIdle()
        assertEquals("abc123", rig.settings.tflKey, "the last text typed is the one kept")
        assertEquals(SettingsViewModel.KeyState.saved, model.ui.value.field(SettingsViewModel.Item.tfl).state)
        assertFalse(handle.contains(SettingsViewModel.Item.tfl.name), "a key never goes into the saved state")
        // A recreated screen reads it back from the store.
        val again = SettingsViewModel(SavedStateHandle(), rig.graph, io = dispatcher).also { advanceUntilIdle() }
        assertEquals("abc123", again.ui.value.field(SettingsViewModel.Item.tfl).text)
        // Cleared: back to nothing set.
        again.type(SettingsViewModel.Item.tfl, "")
        again.flush(); advanceUntilIdle()
        assertNull(rig.settings.tflKey)
        assertEquals(SettingsViewModel.KeyState.notSet, again.ui.value.field(SettingsViewModel.Item.tfl).state)
        // The contact, which isn't secret, is kept across a rotation.
        again.type(SettingsViewModel.Item.contact, "someone@example.org")
        assertTrue(again.ui.value.contactInvalid.not())
        again.type(SettingsViewModel.Item.contact, "someone")
        assertTrue(again.ui.value.contactInvalid)
        assertEquals(0, rig.transport.routes.total + rig.transport.boards + rig.transport.nearby, "Settings sends nothing")
    }

    /** A store whose TfL key can't be read now, and whose writes can be made to fail. */
    private class Locked(var failWrites: Boolean = false) : SecretStore {
        val written = mutableListOf<String>()
        override fun read(account: String) = if (account == AppSettings.TFL) SecretRead.Unreadable else SecretRead.Absent
        override fun write(value: String, account: String): Boolean {
            if (failWrites) return false
            written += account
            return true
        }
    }

    @Test fun anUnreadableKeyIsShownAsSuchAndABlankFieldNeverDeletesIt() = runTest(dispatcher) {
        val store = Locked()
        val rig = rig(secrets = store)
        val model = SettingsViewModel(SavedStateHandle(), rig.graph, io = dispatcher).also { advanceUntilIdle() }
        val field = model.ui.value.field(SettingsViewModel.Item.tfl)
        assertEquals(SettingsViewModel.KeyState.unreadable, field.state)
        assertEquals("", field.text)
        model.type(SettingsViewModel.Item.tfl, "")
        model.flush(); advanceUntilIdle()
        assertEquals(SettingsViewModel.KeyState.unreadable, model.ui.value.field(SettingsViewModel.Item.tfl).state)
        assertTrue(store.written.isEmpty(), "nothing deleted")
        // A stray keystroke and a backspace over it only draft: the sealed value is never touched.
        model.type(SettingsViewModel.Item.tfl, "x")
        model.type(SettingsViewModel.Item.tfl, "")
        model.flush(); advanceUntilIdle()
        assertTrue(store.written.isEmpty(), "typing over an unreadable key writes nothing")
        // Remove is refused over it too; Replace, with a draft, is the one way to change it.
        model.remove(SettingsViewModel.Item.tfl)
        model.type(SettingsViewModel.Item.tfl, " new-key ")
        model.flush(); advanceUntilIdle()
        assertTrue(store.written.isEmpty())
        model.replace(SettingsViewModel.Item.tfl)
        model.flush(); advanceUntilIdle()
        assertEquals(listOf(AppSettings.TFL), store.written)
    }

    @Test fun aSavedKeyIsRemovedOnlyByRemove() = runTest(dispatcher) {
        val rig = rig()
        rig.settings.setTflKeyOverride("kept")
        val model = SettingsViewModel(SavedStateHandle(), rig.graph, io = dispatcher).also { advanceUntilIdle() }
        assertEquals(SettingsViewModel.KeyState.saved, model.ui.value.field(SettingsViewModel.Item.tfl).state)
        model.remove(SettingsViewModel.Item.tfl)
        model.flush(); advanceUntilIdle()
        assertNull(rig.settings.tflKey)
        assertEquals(SettingsViewModel.KeyState.notSet, model.ui.value.field(SettingsViewModel.Item.tfl).state)
    }

    @Test fun keysNeverReachALog() = runTest(dispatcher) {
        val rig = rig()
        rig.settings.setGoogleKeyOverride("AIzaSECRETVALUE")
        val model = SettingsViewModel(SavedStateHandle(), rig.graph, io = dispatcher).also { advanceUntilIdle() }
        val printed = model.ui.value.toString() + model.ui.value.field(SettingsViewModel.Item.google).toString()
        assertFalse("SECRET" in printed, printed)
        assertTrue("redacted" in printed)
        // And Google is usable once it has a key; without one its switch says it needs one.
        assertTrue(model.ui.value.googleUsable)
        assertFalse(SettingsViewModel(SavedStateHandle(), rig().graph, io = dispatcher).also { advanceUntilIdle() }.ui.value.googleUsable)
    }

    @Test fun nearbyWithNoFixSaysSoAndTryAgainAsksAgain() = runTest(dispatcher) {
        val locator = FakeLocator(here = null)
        val rig = rig(locator = locator)
        val shell = ShellViewModel(rig.graph)
        shell.refreshNearby(); advanceUntilIdle()
        assertTrue(shell.nearbyNoFix.value, "no fix: Home says so instead of spinning")
        assertEquals(0, rig.transport.nearby)
        locator.here = Coordinate(51.5308, -0.1238)
        shell.refreshNearby(force = true); advanceUntilIdle()
        assertFalse(shell.nearbyNoFix.value)
        assertEquals(1, rig.transport.nearby)
        // Home coming back within the minute asks for no fix and sends nothing.
        val asked = locator.asked
        shell.refreshNearby(); advanceUntilIdle()
        assertEquals(asked, locator.asked)
        assertEquals(1, rig.transport.nearby)
        // Refresh straight away is held by the floor.
        shell.refreshNearby(force = true); advanceUntilIdle()
        assertEquals(1, rig.transport.nearby)
    }

    @Test fun aKeyThatCouldNotBeSavedSaysSo() = runTest(dispatcher) {
        val rig = rig(secrets = Locked(failWrites = true))
        val model = SettingsViewModel(SavedStateHandle(), rig.graph, io = dispatcher).also { advanceUntilIdle() }
        model.type(SettingsViewModel.Item.google, "key")
        model.flush(); advanceUntilIdle()
        assertEquals(SettingsViewModel.KeyState.notSaved, model.ui.value.field(SettingsViewModel.Item.google).state)
    }

    @Test fun appearanceAndSourcesAreKeptAndFollowedAtOnce() = runTest(dispatcher) {
        val rig = rig()
        val model = SettingsViewModel(SavedStateHandle(), rig.graph, io = dispatcher).also { advanceUntilIdle() }
        model.setAppearance(polaris.engine.Appearance.dark)
        assertEquals(polaris.engine.Appearance.dark, rig.graph.appearance.value, "the activity follows it")
        advanceUntilIdle()
        assertEquals(polaris.engine.Appearance.dark, AppSettings(preferences = rig.kv).appearance, "and it's kept")
        model.setEnabled(polaris.engine.ProviderID.tfl, false)
        model.setDeepSearch(false)
        advanceUntilIdle()
        assertFalse(rig.settings.isEnabled(polaris.engine.ProviderID.tfl))
        assertFalse(AppSettings(preferences = rig.kv).deepSearch)
    }

    @Test fun setupIsTakenOnceAPerProcessLaunch() {
        val kv = InMemoryKeyValueStore()
        val first = AppGraph(AppSettings(preferences = kv), GoogleUsage(kv), PlaceStore(kv), CountingTransport(), MemorySearchArchive(), FakeLocator(), firstRun = true)
        assertTrue(first.takeSetup())
        assertFalse(first.takeSetup(), "a second screen in the same process doesn't show it again")
    }
}

class NoticesTest {
    private val lines = (java.io.File("../THIRD_PARTY_NOTICES.md").takeIf { it.exists() } ?: java.io.File("THIRD_PARTY_NOTICES.md")).readLines()

    @Test fun theAppsLicencesLeaveOutWhatIsWrittenForTheRepository() {
        val blocks = polaris.android.settings.noticeBlocks(lines)
        val all = blocks.joinToString("\n") { it.text }
        for (repoWords in listOf("this folder", "../LICENSE", "app/src", "sym_*", "<!--")) assertFalse(repoWords in all, repoWords)
        // Markdown's marks are gone from the prose (licence texts are reproduced as they are).
        val prose = blocks.filter { !it.preformatted }
        assertTrue(prose.none { "**" in it.text || "`" in it.text }, prose.firstOrNull { "**" in it.text || "`" in it.text }?.text)
        assertTrue(blocks.first().heading)
    }

    @Test fun everyWordOutsideTheRepoOnlyPassagesIsKept() {
        val kept = mutableListOf<String>()
        var skip = false
        for (l in lines) {
            val t = l.trim()
            if (t == "<!-- repo-only -->") { skip = true; continue }
            if (t == "<!-- /repo-only -->") { skip = false; continue }
            if (t.startsWith("<!--")) { if (!t.endsWith("-->")) skip = true; continue }
            if (!skip && !t.startsWith("```")) kept += l
        }
        val words = { t: String -> t.replace("**", "").replace("`", "").replace(Regex("""[\[\]()#-]"""), " ").split(Regex("\\s+")).filter { it.isNotEmpty() } }
        assertEquals(words(kept.joinToString(" ")), words(polaris.android.settings.noticeBlocks(lines).joinToString(" ") { it.text }))
        // MapLibre's own notice, which its licence asks for, is there, its licence texts line for line.
        val blocks = polaris.android.settings.noticeBlocks(lines)
        assertTrue(blocks.any { "MapLibre" in it.text })
        assertTrue(blocks.any { it.preformatted && "\n" in it.text })
    }
}
