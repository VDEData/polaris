package polaris.engine

import kotlinx.coroutines.test.runTest
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
class RecordingTransport : HttpTransport {
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

    private class Rig(clockAt: Instant, used: Int = 0, val contact: String? = "https://example.org/polaris") {
        val transport = RecordingTransport()
        val clock = MutableClock(clockAt)
        val store = InMemoryKeyValueStore()
        val usage = GoogleUsage(store).also { it.spend(used, clockAt) }
        val http = HTTP(transport, "0.1.0") { contact }
        val model = SearchModel(http, usage, NetworkStatusCache(clock), clock, packageName = "com.example.polaris")
        fun settings(googleKey: String? = "test-key", deep: Boolean = true) =
            AppSettings(build = BuildDefaults(googleKey = googleKey)).also { it.deepSearch = deep }
    }

    @Test fun aDeepSearchSendsTransitousOneRequestWithItsUserAgent() = runTest {
        val rig = Rig(now)
        rig.model.search(query, rig.settings())
        val transitous = rig.transport.to("api.transitous.org")
        assertEquals(1, transitous.size, "one Transitous request per search, even deep")
        assertEquals("Polaris/0.1.0 (Android transit app; https://example.org/polaris)", transitous[0].headers["User-Agent"])
        assertTrue("numItineraries=8" in transitous[0].url)
        // Every request carries it, not only Transitous's.
        assertTrue(rig.transport.sent.all { it.headers["User-Agent"]?.startsWith("Polaris/0.1.0 (Android transit app") == true })
    }

    @Test fun aDeepSearchsWholeBudget() = runTest {
        val rig = Rig(now)
        rig.model.search(query, rig.settings())
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
            rig.model.search(query, rig.settings())
            assertEquals(expected, rig.transport.to("routes.googleapis.com").size, "with $used used")
            assertEquals(used + expected, rig.usage.allowance.usedThisMonth(now), "spent with $used used")
            if (expected == 0) assertEquals(ProviderStatus.Off("Free allowance used this month"), rig.model.state.value.statuses[ProviderID.google])
        }
        // A quick search asks one way only.
        val rig = Rig(now)
        rig.model.search(query, rig.settings(deep = false))
        assertEquals(1, rig.transport.to("routes.googleapis.com").size)
        assertEquals(1, rig.transport.count("/Journey/JourneyResults/"))
    }

    @Test fun googleIsSpentBeforeItIsSent() = runTest {
        val rig = Rig(now)
        val failing = HTTP({ request -> rig.transport.send(request); HttpResponse(500, ByteArray(0)) }, "0.1.0")
        SearchModel(failing, rig.usage, NetworkStatusCache(rig.clock), rig.clock).search(query, rig.settings())
        // Every request sent counts against the allowance, answered or not.
        assertEquals(5, rig.transport.to("routes.googleapis.com").size)
        assertEquals(5, rig.usage.allowance.usedThisMonth(now))
    }

    @Test fun noKeyNoGoogleAndNothingSpent() = runTest {
        val rig = Rig(now)
        rig.model.search(query, rig.settings(googleKey = null))
        assertEquals(0, rig.transport.to("routes.googleapis.com").size)
        assertEquals(0, rig.usage.allowance.usedThisMonth(now))
        assertEquals(ProviderStatus.Off("Add a key in Settings"), rig.model.state.value.statuses[ProviderID.google])
    }

    @Test fun tflStatusIsKeptThreeMinutes() = runTest {
        val rig = Rig(now)
        val settings = rig.settings(googleKey = null)
        rig.model.search(query, settings)
        assertEquals(2, rig.transport.statusRequests())
        rig.clock.now = now.plusSeconds(179)
        rig.model.search(query, settings)
        assertEquals(2, rig.transport.statusRequests(), "fresh for 3 minutes")
        assertTrue(rig.model.state.value.network != null, "the kept status still flags routes")
        rig.clock.now = now.plusSeconds(181)
        rig.model.search(query, settings)
        assertEquals(4, rig.transport.statusRequests())
    }

    @Test fun tflOffMeansNoStatusEither() = runTest {
        val rig = Rig(now)
        val settings = rig.settings(googleKey = null).also { it.setEnabled(ProviderID.tfl, false) }
        rig.model.search(query, settings)
        assertEquals(0, rig.transport.to("api.tfl.gov.uk").size)
        assertEquals(1, rig.transport.to("api.transitous.org").size)
        assertEquals(ProviderStatus.Off("Turned off"), rig.model.state.value.statuses[ProviderID.tfl])
    }

    @Test fun theUserAgentNamesAContactOnlyWhenThereIsAValidOne() {
        assertEquals("Polaris/0.1.0 (Android transit app)", HTTP.userAgent("0.1.0", null))
        assertEquals("Polaris/0.1.0 (Android transit app)", HTTP.userAgent("0.1.0", "someone"))
        assertEquals("Polaris/0.1.0 (Android transit app; someone@example.org)", HTTP.userAgent("0.1.0", " someone@example.org "))
        // Mirrors FirstRunTests.swift.
        assertEquals("someone@example.org", Contact.valid(" someone@example.org "))
        assertTrue(Contact.valid("https://github.com/someone/polaris") != null)
        assertEquals(null, Contact.valid(""))
        assertEquals(null, Contact.valid("ftp://example.org"))
        assertEquals(null, Contact.valid("a@b.c\r\nX-Evil: 1"), "nothing that could break the header")
        assertEquals(null, Contact.valid("a".repeat(200) + "@example.org"))
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

    @Test fun googleRequestsSayWhichAndroidAppTheyComeFrom() {
        val provider = GoogleRoutesProvider(HTTP({ HttpResponse(200, ByteArray(0)) }, "0"), "k", "com.example.polaris", "AB:CD")
        val request = provider.request(query, GoogleRoutesProvider.variants[3], "k")
        assertEquals("com.example.polaris", request.headers["X-Android-Package"])
        assertEquals("AB:CD", request.headers["X-Android-Cert"])
        val body = request.body!!.decodeToString()
        assertTrue("\"departureTime\":\"2026-10-28T11:55:00Z\"" in body, body)
        assertTrue("\"allowedTravelModes\":[\"TRAIN\",\"SUBWAY\",\"LIGHT_RAIL\",\"RAIL\"]" in body, body)
        assertFalse("arrivalTime" in body, "unset values are left out, as Swift's encoder does")
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
