package polaris.engine

import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The engine parts the first screens added: saved places, snapshots, and unreadable keys. */
class ScreensEngineTest {
    private val kingsCross = Place(id = "kx", name = "King's Cross", subtitle = "Camden", coordinate = Coordinate(51.5308, -0.1238))
    private val wharf = Place(id = "cw", name = "Canary Wharf", subtitle = "Tower Hamlets", coordinate = Coordinate(51.5054, -0.0235))

    @Test fun recentsKeepEightNewestFirstOnceEachAndNeverCurrentLocation() {
        val store = PlaceStore(InMemoryKeyValueStore())
        repeat(10) { store.remember(Place(id = "p$it", name = "Place $it", coordinate = Coordinate(51.5, -0.1))) }
        store.remember(Place(id = "again", name = "Place 3", coordinate = Coordinate(51.5, -0.1)))
        store.remember(Place(name = "Current Location", coordinate = Coordinate(51.5, -0.1), isCurrentLocation = true))
        assertEquals(PlaceStore.MAX_RECENTS, store.recents.size)
        assertEquals("again", store.recents.first().id)
        assertEquals(1, store.recents.count { it.name == "Place 3" })
        assertTrue(store.recents.none { it.isCurrentLocation })
    }

    @Test fun homeWorkAndRecentsAreReadBackUnderIOSNames() {
        val kv = InMemoryKeyValueStore()
        PlaceStore(kv).apply {
            home = kingsCross
            work = wharf
            remember(wharf)
        }
        assertTrue(kv.getString("places.home")!!.contains("\"coordinate\":{\"latitude\":51.5308"))
        val again = PlaceStore(kv)
        assertEquals(kingsCross, again.home)
        assertEquals(wharf, again.work)
        assertEquals(listOf(wharf), again.recents)
        again.home = null
        assertNull(PlaceStore(kv).home)
        again.forget(wharf)
        assertEquals(emptyList(), PlaceStore(kv).recents)
    }

    @Test fun anUnreadableSavedPlaceIsNoPlace() {
        val kv = InMemoryKeyValueStore().apply { putString("places.home", "{not json") }
        assertNull(PlaceStore(kv).home)
    }

    @Test fun aSearchSnapshotReadsBackTheSameRoutesWithoutTheNetwork() {
        val journeys = TransitousProvider.parse(Fixtures.bytes("transitous-plan.json"))
        assertTrue(journeys.isNotEmpty())
        val noticed = journeys.mapIndexed { i, j ->
            if (i == 0) j.copy(notices = listOf(ServiceNotice("Minor delays on the Jubilee", "Signal failure", ServiceNotice.Severity.minor, ProviderID.tfl, j.legs[0].id))) else j
        }
        val query = JourneyQuery(kingsCross, wharf, TimeMode.ArriveBy(Instant.parse("2026-11-24T09:00:00Z")))
        val search = Snapshot.Search(
            query, mapOf(ProviderID.transitous to ProviderStatus.Done(noticed.size), ProviderID.google to ProviderStatus.Off("Add a key in Settings"),
                ProviderID.tfl to ProviderStatus.Loading),
            noticed, networkChecked = true, savedAt = Instant.parse("2026-11-24T08:30:00Z"),
        )
        val back = Snapshot.decodeSearch(Snapshot.encode(search))!!
        assertEquals(query, back.query)
        assertEquals(noticed.map { it.id }, back.journeys.map { it.id })
        assertEquals(noticed.map { it.routeSignature }, back.journeys.map { it.routeSignature })
        assertEquals(noticed.map { it.departure.toEpochMilli() }, back.journeys.map { it.departure.toEpochMilli() })
        assertEquals(noticed[0].legs.map { it.path.size }, back.journeys[0].legs.map { it.path.size })
        assertEquals(noticed[0].notices, back.journeys[0].notices)
        // A source still asking when the copy was made is shown as interrupted, not spinning for ever.
        assertEquals(ProviderStatus.Failed("Interrupted"), back.statuses[ProviderID.tfl])
        assertEquals(ProviderStatus.Off("Add a key in Settings"), back.statuses[ProviderID.google])
        // Ranking the copy gives the same cards.
        val a = JourneyRanker.rank(noticed, arriveBy = true)
        val b = JourneyRanker.rank(back.journeys, arriveBy = true)
        assertEquals(a.top.map { it.id }, b.top.map { it.id })
        assertNull(Snapshot.decodeSearch("{\"version\":2}"))
    }

    /** Reads TfL's key as saved-but-unreadable; everything else absent. */
    private class LockedTfL : SecretStore {
        override fun read(account: String) = if (account == AppSettings.TFL) SecretRead.Unreadable else SecretRead.Absent
        override fun write(value: String, account: String) = false
    }

    @Test fun aSourceWhoseKeyCantBeReadIsSkippedAndSaysSo() = runTest {
        val transport = RecordingTransport()
        val settings = AppSettings(LockedTfL(), BuildDefaults(tflKey = "built-in-tfl"))
        val model = SearchModel.create(settings, GoogleUsage(InMemoryKeyValueStore()), "0.1.0", AppIdentity(), transport, NetworkStatusCache())
        model.search(JourneyQuery(kingsCross, wharf, TimeMode.LeaveNow), settings)
        // Not with the build's key, not without one: not at all, status included.
        assertEquals(0, transport.to("api.tfl.gov.uk").size)
        assertEquals(ProviderStatus.Off(SearchModel.UNREADABLE_KEY), model.state.value.statuses[ProviderID.tfl])
        assertEquals(1, transport.to("api.transitous.org").size)
    }

    /** Counts reads per account, so a test can see each key is read once a search. */
    private class CountingStore(private val values: Map<String, String>) : SecretStore {
        val reads = mutableMapOf<String, Int>()
        override fun read(account: String): SecretRead {
            reads[account] = (reads[account] ?: 0) + 1
            return values[account]?.let { SecretRead.Value(it) } ?: SecretRead.Absent
        }
        override fun write(value: String, account: String) = false
    }

    @Test fun eachKeyIsReadOnceASearch() = runTest {
        val store = CountingStore(mapOf(AppSettings.GOOGLE to "g-key", AppSettings.TFL to "t-key"))
        val settings = AppSettings(store, BuildDefaults())
        val model = SearchModel.create(settings, GoogleUsage(InMemoryKeyValueStore()), "0.1.0", AppIdentity(), RecordingTransport(), NetworkStatusCache())
        model.search(JourneyQuery(kingsCross, wharf, TimeMode.LeaveNow), settings)
        // A value changing between reads could otherwise send TfL without its key, or count Google requests never sent.
        assertEquals(1, store.reads[AppSettings.GOOGLE])
        assertEquals(1, store.reads[AppSettings.TFL])
    }

    @Test fun theSamePlaceListedTwiceShowsOnce() {
        val matches = PlaceSearch.parse(Fixtures.bytes("transitous-geocode-kings-cross.json"))
        assertEquals(4, matches.size)
        assertEquals(matches.size, matches.map { it.place.id }.toSet().size)
        assertEquals(matches.size, matches.map { it.place.name.lowercase() to it.place.subtitle }.toSet().size)
        assertEquals(1, matches.count { it.place.name == "King's Cross St. Pancras Underground Station" })
        // The stop is kept over the bare place, so it carries its modes.
        assertEquals(PlaceMatch.Kind.stop, matches.first { it.place.name == "King's Cross St. Pancras Underground Station" }.kind)
    }

    @Test fun aSnapshotKeepsEveryDetailACardShows() {
        val ride = Leg(
            id = "l1", mode = LegMode.tube, line = "Northern", lineColor = RGB.hex("2B2B2B"), headsign = "Edgware",
            fromName = "King's Cross", toName = "Bank", departure = Instant.parse("2026-11-24T09:01:00Z"), arrival = Instant.parse("2026-11-24T09:09:00Z"),
            stopCount = 4, distanceMeters = 3200.0, path = listOf(Coordinate(51.53, -0.12), Coordinate(51.51, -0.09)),
            isRealtime = true, platform = "2", intermediateStops = listOf("Angel", "Old Street"),
        )
        val journey = Journey(
            id = "j1", departure = ride.departure, arrival = ride.arrival, legs = listOf(ride),
            sources = listOf(SourceTag(ProviderID.tfl, "Fewer changes"), SourceTag(ProviderID.transitous, "")), fare = "£2.80", hasDisruption = true,
        )
        for (time in listOf(TimeMode.LeaveNow, TimeMode.DepartAt(Instant.parse("2026-11-24T09:00:00Z")), TimeMode.ArriveBy(Instant.parse("2026-11-24T10:00:00Z")))) {
            val query = JourneyQuery(kingsCross, wharf, time)
            val back = Snapshot.decodeSearch(Snapshot.encode(Snapshot.Search(query, emptyMap(), listOf(journey), false, Instant.parse("2026-11-24T09:00:00Z"))))!!
            assertEquals(time, back.query.time)
            val j = back.journeys.single()
            assertEquals("£2.80", j.fare)
            assertEquals(true, j.hasDisruption)
            assertEquals(journey.sources, j.sources)
            val l = j.legs.single()
            assertEquals(ride.lineColor, l.lineColor)
            assertEquals("2", l.platform)
            assertEquals(4, l.stopCount)
            assertEquals(true, l.isRealtime)
            assertEquals("Edgware", l.headsign)
            assertEquals(ride.intermediateStops, l.intermediateStops)
            assertEquals(ride.path, l.path)
        }
    }

    @Test fun sameNamedPlacesFarApartAreBothKept() {
        fun m(id: String, name: String, area: String?, lat: Double, lon: Double, kind: PlaceMatch.Kind) =
            PlaceMatch(Place(id = id, name = name, subtitle = area, coordinate = Coordinate(lat, lon)), kind)
        // No area on either: two different High Streets.
        val noArea = PlaceSearch.dedupe(listOf(m("a", "High Street", null, 51.50, -0.10, PlaceMatch.Kind.address), m("b", "High Street", null, 51.60, -0.20, PlaceMatch.Kind.address)))
        assertEquals(2, noArea.size)
        // A street and a stop of the same name in one borough, a kilometre apart.
        val streetAndStop = PlaceSearch.dedupe(listOf(m("c", "Camden Road", "Camden", 51.5410, -0.1390, PlaceMatch.Kind.address), m("d", "Camden Road", "Camden", 51.5480, -0.1270, PlaceMatch.Kind.stop)))
        assertEquals(2, streetAndStop.size)
        // The same station twice, close together: once.
        val same = PlaceSearch.dedupe(listOf(m("e", "Bank", "City", 51.5133, -0.0890, PlaceMatch.Kind.place), m("f", "Bank", "City", 51.5135, -0.0886, PlaceMatch.Kind.stop)))
        assertEquals(listOf("f"), same.map { it.place.id })
    }
}
