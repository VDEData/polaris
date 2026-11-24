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
}
