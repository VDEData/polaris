package polaris.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Answers every request from the recorded fixtures and remembers what was sent. */
open class RecordingTransport : HttpTransport {
    val sent = mutableListOf<HttpRequest>()

    fun to(host: String) = sent.filter { it.host == host }

    override suspend fun send(request: HttpRequest): HttpResponse {
        synchronized(sent) { sent += request }
        val url = request.url
        val fixture = when {
            "api.transitous.org" in url -> "transitous-plan.json"
            "routes.googleapis.com" in url -> "google-routes.json"
            "/Journey/JourneyResults/" in url -> "tfl-journeys.json"
            "/Line/Mode/" in url -> "tfl-line-status.json"
            "/StopPoint/Mode/" in url -> "tfl-stop-disruptions.json"
            else -> return HttpResponse(404, ByteArray(0))
        }
        return HttpResponse(200, Fixtures.bytes(fixture))
    }
}

class MutableClock(var now: Instant) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}

/**
 * The request budgets in the README are rules: Transitous one request per search, never fanned
 * out, with a descriptive User-Agent; Google counted by Pacific month inside the free 1,000
 * (5 ways under 700, 1 under 900, none past 900, spent before sending); TfL's status two
 * requests per search, kept 3 minutes, skipped when TfL is off.
 */
class BudgetTest {
    private val now = iso("2026-11-24T08:30:00Z")
    private val query = JourneyQuery(
        Place(name = "King's Cross", coordinate = Coordinate(51.5308, -0.1238)),
        Place(name = "Canary Wharf", coordinate = Coordinate(51.5054, -0.0235)),
        TimeMode.DepartAt(iso("2026-10-28T11:55:00Z")),
    )

    private class Rig(
        clockAt: Instant,
        used: Int = 0,
        googleKey: String? = "test-key",
        deep: Boolean = true,
        contact: String? = "https://example.org/polaris",
        val transport: RecordingTransport = RecordingTransport(),
        val app: AppIdentity = AppIdentity("com.example.polaris", "00112233445566778899AABBCCDDEEFF00112233"),
    ) {
        val clock = MutableClock(clockAt)
        val usage = GoogleUsage(InMemoryKeyValueStore()).also { it.spend(used, clockAt) }
        val settings = AppSettings(build = BuildDefaults(googleKey = googleKey, contact = contact)).also { it.deepSearch = deep }
        // The same factory the app uses, with a recording transport in place of the network.
        val model = SearchModel.create(settings, usage, "0.1.0", app, transport, NetworkStatusCache(clock), clock)
        suspend fun search(query: JourneyQuery) = model.search(query, settings)
    }

    @Test fun aDeepSearchSendsTransitousOneRequestWithItsUserAgent() = runTest {
        val rig = Rig(now)
        rig.search(query)
        val transitous = rig.transport.to("api.transitous.org")
        assertEquals(1, transitous.size, "one Transitous request per search, even deep")
        assertEquals("Polaris/0.1.0 (Android transit app; https://example.org/polaris)", transitous[0].headers["User-Agent"])
        assertTrue("numItineraries=8" in transitous[0].url)
        // Every request carries it, not only Transitous's.
        assertTrue(rig.transport.sent.all { it.headers["User-Agent"]?.startsWith("Polaris/0.1.0 (Android transit app") == true })
    }

    @Test fun aDeepSearchsWholeBudget() = runTest {
        val rig = Rig(now)
        rig.search(query)
        val tfl = rig.transport.to("api.tfl.gov.uk")
        assertEquals(3, tfl.count { "/Journey/JourneyResults/" in it.url }, "TfL's three ways")
        assertEquals(2, tfl.count { "/Line/Mode/" in it.url || "/StopPoint/Mode/" in it.url }, "TfL status, two requests")
        assertEquals(5, rig.transport.to("routes.googleapis.com").size)
        assertEquals(11, rig.transport.sent.size)
        assertEquals(5, rig.usage.allowance.usedThisMonth(now))
        val state = rig.model.state.value
        assertFalse(state.isSearching)
        assertTrue(state.journeys.isNotEmpty())
        assertTrue(state.network != null)
        assertEquals(ProviderStatus.Off("Not on Android"), state.statuses[ProviderID.apple])
    }

    @Test fun googleStepsDownAsTheMonthFillsUp() = runTest {
        for ((used, expected) in listOf(0 to 5, 699 to 5, 700 to 1, 899 to 1, 900 to 0)) {
            val rig = Rig(now, used)
            rig.search(query)
            assertEquals(expected, rig.transport.to("routes.googleapis.com").size, "with $used used")
            assertEquals(used + expected, rig.usage.allowance.usedThisMonth(now), "spent with $used used")
            if (expected == 0) assertEquals(ProviderStatus.Off("Free allowance used this month"), rig.model.state.value.statuses[ProviderID.google])
        }
        // A quick search asks one way only.
        val rig = Rig(now, deep = false)
        rig.search(query)
        assertEquals(1, rig.transport.to("routes.googleapis.com").size)
        assertEquals(1, rig.transport.count("/Journey/JourneyResults/"))
    }

    @Test fun googleIsSpentBeforeItIsSent() = runTest {
        // What the month's count says at the moment each Google request goes out.
        val countWhenSent = mutableListOf<Int>()
        lateinit var rig: Rig
        val transport = object : RecordingTransport() {
            override suspend fun send(request: HttpRequest): HttpResponse {
                if (request.host != "routes.googleapis.com") return super.send(request)
                countWhenSent += rig.usage.allowance.usedThisMonth(now)
                super.send(request)
                // Google refuses the key: as on iOS, the requests were still sent, so they still count.
                return HttpResponse(403, ByteArray(0))
            }
        }
        rig = Rig(now, transport = transport)
        rig.search(query)
        assertEquals(listOf(5, 5, 5, 5, 5), countWhenSent, "all five counted before the first was sent")
        assertEquals(5, rig.usage.allowance.usedThisMonth(now))
        assertEquals(ProviderStatus.Failed("Key rejected"), rig.model.state.value.statuses[ProviderID.google])
    }

    @Test fun noKeyNoGoogleAndNothingSpent() = runTest {
        val rig = Rig(now, googleKey = null)
        rig.search(query)
        assertEquals(0, rig.transport.to("routes.googleapis.com").size)
        assertEquals(0, rig.usage.allowance.usedThisMonth(now))
        assertEquals(ProviderStatus.Off("Add a key in Settings"), rig.model.state.value.statuses[ProviderID.google])
    }

    @Test fun tflStatusIsKeptThreeMinutes() = runTest {
        val rig = Rig(now, googleKey = null)
        rig.search(query)
        assertEquals(2, rig.transport.statusRequests())
        rig.clock.now = now.plusSeconds(179)
        rig.search(query)
        assertEquals(2, rig.transport.statusRequests(), "fresh for 3 minutes")
        assertTrue(rig.model.state.value.network != null, "the kept status still flags routes")
        rig.clock.now = now.plusSeconds(181)
        rig.search(query)
        assertEquals(4, rig.transport.statusRequests())
    }

    @Test fun theStatusIsSharedByEverySearchInTheApp() = runTest {
        // Two separate searches made the app's way share the one cache, as Swift's static does.
        NetworkStatusCache.shared.clear()
        val transport = RecordingTransport()
        val settings = AppSettings().also { it.deepSearch = false }
        val first = SearchModel.create(settings, GoogleUsage(InMemoryKeyValueStore()), "0.1.0", AppIdentity(), transport)
        val second = SearchModel.create(settings, GoogleUsage(InMemoryKeyValueStore()), "0.1.0", AppIdentity(), transport)
        first.search(query, settings)
        assertEquals(2, transport.statusRequests(), "the first search fetched it")
        second.search(query, settings)
        assertEquals(2, transport.statusRequests(), "the second search reused it")
        assertTrue(second.state.value.network != null)
        NetworkStatusCache.shared.clear()
    }

    @Test fun tflOffMeansNoStatusEither() = runTest {
        val rig = Rig(now, googleKey = null)
        rig.settings.setEnabled(ProviderID.tfl, false)
        rig.search(query)
        assertEquals(0, rig.transport.to("api.tfl.gov.uk").size)
        assertEquals(1, rig.transport.to("api.transitous.org").size)
        assertEquals(ProviderStatus.Off("Turned off"), rig.model.state.value.statuses[ProviderID.tfl])
    }

    @Test fun aNewSearchCancelsTheOneStillRunning() = runTest {
        // The first search's answers are held back until after the second has finished.
        val release = CompletableDeferred<Unit>()
        val transport = object : RecordingTransport() {
            override suspend fun send(request: HttpRequest): HttpResponse {
                // The first search's answers come late, and as failures, so any that leak show.
                if ("51.5308" in request.url) {
                    release.await()
                    return HttpResponse(500, ByteArray(0))
                }
                return super.send(request)
            }
        }
        val rig = Rig(now, googleKey = null, transport = transport)
        val elsewhere = query.copy(origin = Place(name = "Paddington", coordinate = Coordinate(51.5154, -0.1755)))
        var afterFirst = false
        val first = launch {
            rig.search(query)
            // Only the search is cancelled, not the coroutine that asked for it.
            afterFirst = true
        }
        runCurrent()
        assertTrue(rig.model.state.value.isSearching)
        val second = launch { rig.search(elsewhere) }
        advanceUntilIdle()
        assertTrue(afterFirst, "the first caller carried on after its search was replaced")
        assertTrue(first.isCompleted && !first.isCancelled)
        assertTrue(second.isCompleted)
        assertEquals(6, rig.transport.sent.size, "the first search's held requests never went out")
        val settled = rig.model.state.value
        assertEquals(elsewhere, settled.query)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(settled, rig.model.state.value, "nothing from the first search arrived afterwards")
    }

    @Test fun aReplacedSearchsLateAnswersAreDropped() = runTest {
        // A transport that won't be interrupted: the first search's Transitous answer still comes
        // back after the search was cancelled, and nothing between it and the state can stop it.
        // It must not reach the state.
        val release = CompletableDeferred<Unit>()
        val transport = object : RecordingTransport() {
            override suspend fun send(request: HttpRequest): HttpResponse {
                if ("51.5308" in request.url && request.host == "api.transitous.org") {
                    withContext(NonCancellable) { release.await() }
                    return HttpResponse(500, ByteArray(0))
                }
                return super.send(request)
            }
        }
        val rig = Rig(now, googleKey = null, transport = transport)
        rig.settings.setEnabled(ProviderID.tfl, false)
        val elsewhere = query.copy(origin = Place(name = "Paddington", coordinate = Coordinate(51.5154, -0.1755)))
        launch { rig.search(query) }
        runCurrent()
        launch { rig.search(elsewhere) }
        advanceUntilIdle()
        val settled = rig.model.state.value
        assertEquals(ProviderStatus.Done(6), settled.statuses[ProviderID.transitous])
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(settled, rig.model.state.value, "the first search's late failure didn't overwrite the second's answer")
    }

    @Test fun searchesStartedTogetherDontSpendPastTheThreshold() = runTest {
        // 698 used: one deep search may ask all five ways, which takes the month past 700, so the
        // other may ask only one. The clock holds both searches at the same point until both have
        // reached it, so their plans always overlap; counting must still come out at 6, not 10.
        val usage = GoogleUsage(InMemoryKeyValueStore()).also { it.spend(698, now) }
        val transport = RecordingTransport()
        val together = java.util.concurrent.CyclicBarrier(2)
        val clock = object : Clock() {
            override fun instant(): Instant {
                runCatching { together.await(2, java.util.concurrent.TimeUnit.SECONDS) }
                return now
            }
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId?): Clock = this
        }
        val settings = AppSettings(build = BuildDefaults(googleKey = "test-key"))
        val models = List(2) { SearchModel.create(settings, usage, "0.1.0", AppIdentity(), transport, NetworkStatusCache(MutableClock(now)), clock) }
        withContext(Dispatchers.Default) { models.map { async { it.search(query, settings) } }.awaitAll() }
        assertEquals(6, transport.to("routes.googleapis.com").size)
        assertEquals(704, usage.allowance.usedThisMonth(now))
    }

    @Test fun theUserAgentAlwaysNamesAContact() {
        val project = "Polaris/0.1.0 (Android transit app; https://github.com/VDEData/polaris)"
        assertEquals(project, HTTP.userAgent("0.1.0", null))
        assertEquals(project, HTTP.userAgent("0.1.0", "someone"))
        assertEquals("Polaris/0.1.0 (Android transit app; someone@example.org)", HTTP.userAgent("0.1.0", " someone@example.org "))
        assertEquals(Contact.PROJECT, Contact.valid(Contact.PROJECT))
        // Mirrors FirstRunTests.swift.
        assertEquals("someone@example.org", Contact.valid(" someone@example.org "))
        assertTrue(Contact.valid("https://github.com/someone/polaris") != null)
        assertEquals(null, Contact.valid(""))
        assertEquals(null, Contact.valid("ftp://example.org"))
        assertEquals(null, Contact.valid("a@b.c\r\nX-Evil: 1"), "nothing that could break the header")
        assertEquals(null, Contact.valid("a".repeat(200) + "@example.org"))
    }

    /** Mirrors FirstRunTests.theUserAgentAlwaysNamesAContact: settings' if valid, else the build's, else the project's. */
    @Test fun theContactIsEnteredThenBuiltThenTheProjects() {
        val project = "Polaris/0.1.0 (Android transit app; https://github.com/VDEData/polaris)"
        fun agent(entered: String?, built: String?): String {
            val settings = AppSettings(build = BuildDefaults(contact = built))
            if (entered != null) settings.contactOverride = entered
            return HTTP(transport = { HttpResponse(200, ByteArray(0)) }, appVersion = "0.1.0") { settings.contact }.userAgent
        }
        assertEquals(project, agent(null, null), "none configured")
        assertEquals(project, agent("", ""), "a local.properties line left empty")
        assertEquals("Polaris/0.1.0 (Android transit app; https://example.org/my-polaris)", agent(null, "https://example.org/my-polaris"))
        assertEquals("Polaris/0.1.0 (Android transit app; someone@example.org)", agent(" someone@example.org ", "https://example.org/my-polaris"))
        assertEquals("Polaris/0.1.0 (Android transit app; https://example.org/my-polaris)", agent("someone", "https://example.org/my-polaris"),
            "an invalid entry falls back to the build's")
        assertEquals(project, agent("someone", "not a contact"))
    }

    @Test fun enteredKeysBeatTheBuildsAndBlankFallsBack() {
        val secrets = InMemorySecretStore()
        val settings = AppSettings(secrets, BuildDefaults(googleKey = "built-in", tflKey = " ", contact = "build@example.org"))
        assertEquals("built-in", settings.googleKey)
        assertEquals(null, settings.tflKey, "blank is no key")
        assertTrue(settings.hasBuiltInGoogleKey)
        settings.googleKeyOverride = "entered"
        assertEquals("entered", settings.googleKey)
        settings.googleKeyOverride = "  "
        assertEquals("built-in", settings.googleKey)
        assertEquals("build@example.org", settings.contact)
        settings.contactOverride = "me@example.org"
        assertEquals("me@example.org", settings.contact)
        settings.contactOverride = "not a contact"
        assertEquals("build@example.org", settings.contact)
    }

    @Test fun googleRequestsSayWhichAndroidAppTheyComeFrom() = runTest {
        // Through the factory the app uses, with the identity the app reads from its own signature.
        val identity = AppIdentity("com.example.polaris", AppIdentity.fingerprint("abc".encodeToByteArray()))
        val rig = Rig(now, app = identity)
        rig.search(query)
        val google = rig.transport.to("routes.googleapis.com")
        assertEquals(5, google.size)
        assertTrue(google.all { it.headers["X-Android-Package"] == "com.example.polaris" })
        // SHA-1 of "abc", as Google wants it: upper-case hex, no colons.
        assertTrue(google.all { it.headers["X-Android-Cert"] == "A9993E364706816ABA3E25717850C26C9CD0D89D" })
        val bodies = google.map { it.body!!.decodeToString() }
        assertTrue(bodies.all { "\"departureTime\":\"2026-10-28T11:55:00Z\"" in it && "arrivalTime" !in it }, "unset values are left out, as Swift's encoder does")
        assertTrue(bodies.any { "\"allowedTravelModes\":[\"TRAIN\",\"SUBWAY\",\"LIGHT_RAIL\",\"RAIL\"]" in it })
        // A build that can't read its signature sends the package alone.
        val bare = Rig(now, app = AppIdentity("com.example.polaris"))
        bare.search(query)
        assertTrue(bare.transport.to("routes.googleapis.com").none { "X-Android-Cert" in it.headers })
        // A request carrying the key never follows a redirect (TransportTest checks the transport keeps to that).
        assertTrue(google.none { it.followRedirects })
        assertTrue(rig.transport.sent.filter { it.host != "routes.googleapis.com" }.all { it.followRedirects })
    }

    private fun RecordingTransport.statusRequests() = sent.count { "/Line/Mode/" in it.url || "/StopPoint/Mode/" in it.url }
    private fun RecordingTransport.count(path: String) = sent.count { path in it.url }
}

/** Mirrors the allowance tests in ReplanTests.swift. */
class GoogleAllowanceTest {
    private val now = iso("2026-11-24T06:30:00Z")

    @Test fun searchesStepDownAsTheMonthFillsUp() {
        fun requests(used: Int, deep: Boolean = true) = GoogleAllowance().spending(used, now).searchRequests(deep, 5, now)
        assertEquals(5, requests(0))
        assertEquals(5, requests(699))
        assertEquals(1, requests(700))
        assertEquals(1, requests(899))
        assertEquals(0, requests(900))
        assertEquals(1, requests(0, deep = false))
        assertEquals(0, requests(950, deep = false))
        // A new month (Google's, in California) starts again.
        assertEquals(5, GoogleAllowance().spending(950, now).searchRequests(true, 5, iso("2026-12-01T09:00:00Z")))
    }

    @Test fun googlesMonthIsPacificTime() {
        val allowance = GoogleAllowance().spending(900, now)
        // 05:00 on 1 December in London is still 30 November in California: same month.
        assertEquals(900, allowance.usedThisMonth(iso("2026-12-01T05:00:00Z")))
        assertEquals(0, allowance.usedThisMonth(iso("2026-12-01T09:00:00Z")))
        assertEquals("2026-11", GoogleAllowance.month(iso("2026-12-01T07:59:59Z")))
        assertEquals("2026-12", GoogleAllowance.month(iso("2026-12-01T08:00:00Z")))
    }

    @Test fun inSummerGooglesMonthTurnsAnHourEarlier() {
        // Pacific Daylight Time is seven hours behind UTC, not eight.
        assertEquals("2026-07", GoogleAllowance.month(iso("2026-08-01T06:59:59Z")))
        assertEquals("2026-08", GoogleAllowance.month(iso("2026-08-01T07:00:00Z")))
        val july = GoogleAllowance().spending(900, iso("2026-08-01T06:00:00Z"))
        assertEquals("2026-07", july.month)
        assertEquals(900, july.usedThisMonth(iso("2026-08-01T06:59:59Z")))
        assertEquals(0, july.usedThisMonth(iso("2026-08-01T07:00:00Z")))
    }

    @Test fun spendingInANewMonthStartsTheCountAgain() {
        val july = GoogleAllowance().spending(900, iso("2026-08-01T06:00:00Z"))
        assertEquals(GoogleAllowance("2026-08", 1), july.spending(1, iso("2026-08-01T07:00:00Z")))
        assertEquals(GoogleAllowance("2026-07", 901), july.spending(1, iso("2026-08-01T06:59:59Z")))
    }

    @Test fun theCountIsKeptOnThePhone() {
        val store = InMemoryKeyValueStore()
        GoogleUsage(store).spend(5, now)
        GoogleUsage(store).spend(1, now)
        assertEquals(6, GoogleUsage(store).allowance.usedThisMonth(now))
        assertEquals(0, GoogleUsage(store).allowance.usedThisMonth(iso("2026-12-01T09:00:00Z")))
    }

    @Test fun searchesPlanTheirGoogleRequests() {
        var allowance = GoogleAllowance()
        assertEquals(SearchModel.GooglePlan.Ask(5), SearchModel.googlePlan(true, true, true, allowance, now))
        assertEquals(SearchModel.GooglePlan.Ask(1), SearchModel.googlePlan(true, true, false, allowance, now))
        assertEquals(SearchModel.GooglePlan.Off("Turned off"), SearchModel.googlePlan(false, true, true, allowance, now))
        assertEquals(SearchModel.GooglePlan.Off("Add a key in Settings"), SearchModel.googlePlan(true, false, true, allowance, now))
        allowance = allowance.spending(750, now)
        assertEquals(SearchModel.GooglePlan.Ask(1), SearchModel.googlePlan(true, true, true, allowance, now))
        allowance = allowance.spending(150, now)
        assertIs<SearchModel.GooglePlan.Off>(SearchModel.googlePlan(true, true, true, allowance, now))
        assertEquals(SearchModel.GooglePlan.Off("Free allowance used this month"), SearchModel.googlePlan(true, true, true, allowance, now))
    }
}
