package polaris.android

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import polaris.android.trip.LocationTracking
import polaris.android.trip.TripSession
import polaris.engine.Coordinate
import polaris.engine.DepartureProvider
import polaris.engine.DepartureStore
import polaris.engine.HTTP
import polaris.engine.HttpRequest
import polaris.engine.HttpResponse
import polaris.engine.HttpTransport
import polaris.engine.Journey
import polaris.engine.JourneyQuery
import polaris.engine.Leg
import polaris.engine.LegMode
import polaris.engine.LiveTrip
import polaris.engine.Place
import polaris.engine.SourceTag
import polaris.engine.ProviderID
import polaris.engine.TimeMode
import polaris.engine.TripStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The live trip for the whole app: one at a time, kept off the page, End deletes what a relaunch
 * would pick up, and a trip found at launch comes back once and opens Go. Every coroutine runs on
 * the test's own scope ([kotlinx.coroutines.test.TestScope.backgroundScope] for the ticks), so none
 * outlives a test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripSessionTest {
    private val t0 = Instant.parse("2026-11-16T06:30:00Z")
    private val a = Coordinate(51.5300, -0.1240)
    private val b = Coordinate(51.5310, -0.1200)
    private val c = Coordinate(51.5200, -0.1050)

    private fun journey(id: String = "j1") = Journey(
        id = id, departure = t0, arrival = t0.plusSeconds(20 * 60),
        legs = listOf(
            Leg(id = "$id-w", mode = LegMode.walk, fromName = "Start", toName = "Station", departure = t0, arrival = t0.plusSeconds(300), path = listOf(a, b)),
            Leg(id = "$id-r", mode = LegMode.tube, line = "Northern", fromName = "Station", toName = "End", departure = t0.plusSeconds(420), arrival = t0.plusSeconds(1200), path = listOf(b, c)),
        ),
        sources = listOf(SourceTag(ProviderID.tfl, "Fastest")),
    )

    private val query = JourneyQuery(Place(name = "Start", coordinate = a), Place(name = "End", coordinate = c), TimeMode.LeaveNow)

    private class Requests : HttpTransport {
        var count = 0
        override suspend fun send(request: HttpRequest): HttpResponse {
            count += 1
            return HttpResponse(200, """{"stopTimes":[]}""".encodeToByteArray())
        }
    }

    private class Tracker : LocationTracking {
        var started = 0
        var stopped = 0
        override var latest: LiveTrip.Fix? = null
        override fun start() { started += 1 }
        override fun stop() { stopped += 1 }
    }

    private fun kotlinx.coroutines.test.TestScope.session(store: TripStore, clock: () -> Instant, pending: polaris.engine.SavedTrip? = null, tracker: Tracker = Tracker(), requests: Requests = Requests(), cutoffs: MutableList<Instant?> = mutableListOf()) =
        TripSession(
            scope = backgroundScope,
            departures = DepartureStore(DepartureProvider(HTTP(requests, "test")), clock),
            tracker = tracker, store = store, clock = clock, search = null, isOnline = { true }, pending = pending,
            cutoffAlarm = { cutoffs += it },
        )

    @Test fun goStartsOneTripAndEndDeletesWhatARelaunchWouldPickUp() = runTest {
        val store = TripStore.Memory()
        val tracker = Tracker()
        var now = t0
        val trips = session(store, { now }, tracker = tracker)
        val trip = trips.start(journey(), query)
        runCurrent()
        assertSame(trip, trips.trip.value)
        assertTrue(trip.isRunning)
        assertNotNull(store.trip, "saved for a relaunch as soon as it starts")
        assertEquals(1, tracker.started)
        // Go tapped again on the same journey: the same trip, not a second.
        assertSame(trip, trips.start(journey(), query))
        assertSame(trip, trips.following("j1"))
        assertNull(trips.following("other"))

        trips.end()
        assertNull(trips.trip.value)
        assertFalse(trip.isRunning)
        assertNull(store.trip, "End leaves nothing to resume")
        assertEquals(1, tracker.stopped)
        // No more looks after End: the clock moving on changes nothing.
        val looks = trip.version.value
        now = t0.plusSeconds(60)
        advanceTimeBy(10_000)
        assertEquals(looks, trip.version.value)
    }

    @Test fun goOnAnotherJourneyReplacesTheTripRunning() = runTest {
        val store = TripStore.Memory()
        val trips = session(store, { t0 })
        val first = trips.start(journey("j1"), query)
        val second = trips.start(journey("j2"), query)
        assertFalse(first.isRunning)
        assertTrue(second.isRunning)
        assertEquals("j2", store.trip?.journey?.id)
    }

    @Test fun theTripLooksEveryTwoSecondsAndAsksForItsBoardsWithinTheStoresFloor() = runTest {
        val requests = Requests()
        var now = t0
        val trips = session(TripStore.Memory(), { now }, requests = requests)
        val trip = trips.start(journey(), query)
        runCurrent()
        val first = trip.version.value
        // Ten simulated minutes of looks, every two seconds.
        repeat(300) {
            now = now.plusSeconds(2)
            advanceTimeBy(2_000)
            runCurrent()
        }
        assertTrue(trip.version.value >= first + 300, "a look every two seconds")
        // The one ride's board: once when Go starts, then at most once a minute while it matters.
        assertTrue(requests.count <= 11, "at most one board request a minute, was ${requests.count}")
        trips.end()
    }

    @Test fun aTripFoundAtLaunchComesBackOnceAndOpensGo() = runTest {
        val store = TripStore.Memory()
        val now = t0.plusSeconds(120)
        val saved = session(store, { t0 }).start(journey(), query).saved
        val trips = session(store, { now }, pending = saved)
        val trip = assertNotNull(trips.resumePending())
        assertTrue(trip.isResumed)
        assertTrue(trips.opens.value)
        trips.opened()
        assertFalse(trips.opens.value)
        assertNull(trips.resumePending(), "taken once")
        trips.end()
    }

    @Test fun aTripPastItsTimeWhenTheScreenComesUpIsDropped() = runTest {
        val store = TripStore.Memory()
        val saved = session(store, { t0 }).start(journey(), query).saved
        // The screen comes up long after the launch decided (past the arrival by over half an hour).
        val trips = session(store, { t0.plusSeconds(60 * 60) }, pending = saved)
        assertNull(trips.resumePending())
        assertNull(trips.trip.value)
        assertNull(store.trip)
        assertFalse(trips.opens.value)
    }

    /** Arrive, pocket the phone, forget End: half an hour past the arrival the trip ends by itself. */
    @Test fun halfAnHourPastTheArrivalTheTripEndsByItself() = runTest {
        val store = TripStore.Memory()
        val tracker = Tracker()
        var now = t0
        val trips = session(store, { now }, tracker = tracker)
        trips.start(journey(), query)
        runCurrent()
        // Ticks a minute apart, no fix at all: the walk never ends, so the estimate keeps slipping
        // and the trip ends half an hour past an hour late (06:50 + 60 + 31 min).
        while (now < t0.plusSeconds((20 + 60 + 31) * 60L)) {
            now = now.plusSeconds(60)
            advanceTimeBy(2_000)
            runCurrent()
        }
        assertNull(trips.trip.value)
        assertEquals(1, tracker.stopped)
        assertNull(store.trip)
    }

    /** Seen at the destination on the last leg: two minutes later the trip ends, GPS and all. */
    @Test fun seenAtTheDestinationTheTripEndsTwoMinutesLater() = runTest {
        val store = TripStore.Memory()
        val tracker = Tracker()
        var now = t0
        val trips = session(store, { now }, tracker = tracker)
        val trip = trips.start(journey(), query)
        runCurrent()
        trip.select(1)
        tracker.latest = LiveTrip.Fix(c, now)
        now = now.plusSeconds(10); advanceTimeBy(2_000); runCurrent()
        assertTrue(trip.arrived)
        assertSame(trip, trips.trip.value, "not straight away")
        repeat(70) {
            now = now.plusSeconds(2)
            tracker.latest = LiveTrip.Fix(c, now)
            advanceTimeBy(2_000); runCurrent()
        }
        assertNull(trips.trip.value)
        assertEquals(1, tracker.stopped)
        assertNull(store.trip)
    }

    /** Seen at the destination once, then walking away from it for two minutes: the trip goes on. */
    @Test fun oneFixAtTheEndThenAwayKeepsTheTripRunning() = runTest {
        val store = TripStore.Memory()
        val tracker = Tracker()
        var now = t0
        val trips = session(store, { now }, tracker = tracker)
        val trip = trips.start(journey(), query)
        runCurrent()
        trip.select(1)
        tracker.latest = LiveTrip.Fix(c, now)
        now = now.plusSeconds(10); advanceTimeBy(2_000); runCurrent()
        assertTrue(trip.arrived)
        // 200 m north of the end.
        val away = Coordinate(c.latitude + 0.0018, c.longitude)
        repeat(70) {
            now = now.plusSeconds(2)
            tracker.latest = LiveTrip.Fix(away, now)
            advanceTimeBy(2_000); runCurrent()
        }
        assertSame(trip, trips.trip.value, "ended though the person walked away from the end")
    }

    /** A long delay saved before Android ended the process: the relaunch resumes it and it doesn't end at once. */
    @Test fun aLateTripResumedIsNotEndedAtOnce() = runTest {
        val j = journey()
        // Running 100 minutes late when saved; reopened 95 minutes past the planned arrival.
        val saved = polaris.engine.SavedTrip(
            journey = j, query = query, legIndex = 1, log = polaris.engine.TripLog(began = t0, boardings = mapOf(1 to polaris.engine.TripLog.Boarding(t0.plusSeconds(420)))),
            replanWatch = polaris.engine.Replan.Watch(), checkedWaits = emptySet(), arrival = j.arrival.plusSeconds(100 * 60L),
        )
        var now = j.arrival.plusSeconds(95 * 60L)
        assertTrue(saved.isResumable(now))
        val store = TripStore.Memory()
        val trips = session(store, { now }, pending = saved)
        val trip = assertNotNull(trips.resumePending())
        repeat(5) { now = now.plusSeconds(2); advanceTimeBy(2_000); runCurrent() }
        assertSame(trip, trips.trip.value, "resumed, then ended at once")
    }

    /** The trip sets an alarm at the time it would end by itself, so it ends even asleep with no fixes; End takes it back. */
    @Test fun anAlarmIsSetAtTheCutOffAndTakenBackOnEnd() = runTest {
        val cutoffs = mutableListOf<Instant?>()
        var now = t0
        val trips = session(TripStore.Memory(), { now }, cutoffs = cutoffs)
        trips.start(journey(), query)
        runCurrent()
        now = now.plusSeconds(2); advanceTimeBy(2_000); runCurrent()
        val at = assertNotNull(cutoffs.lastOrNull())
        assertEquals(journey().arrival.plusSeconds(30 * 60L), at)
        trips.end()
        assertNull(cutoffs.last())
    }
}
