package polaris.engine

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Departure boards against stop times recorded at King's Cross St. Pancras (Underground, 18:04 to
 * 18:39 on 31 Oct 2026), the iOS suite's fixtures. Mirrors DepartureTests.swift, plus the strip's
 * rules and the store's request budget.
 */
class DepartureTest {
    private val kingsCross = Coordinate(51.5306, -0.1240)
    private val bank = Coordinate(51.5133, -0.0886)

    private fun northernLeg(departing: String = "2026-10-31T18:08:00Z"): Leg {
        val departure = iso(departing)
        return Leg(
            mode = LegMode.tube, line = "Northern", headsign = "Morden", fromName = "King's Cross St. Pancras", toName = "Bank",
            departure = departure, arrival = departure.plusSeconds(9 * 60), path = listOf(kingsCross, bank),
        )
    }

    private fun board(leg: Leg = northernLeg()) = DepartureProvider.parseBoard(Fixtures.bytes("transitous-departures.json"), leg)

    @Test fun keepsOnlyWhatStopsWhereYouGetOff() {
        val board = board()
        assertTrue(board.departures.isNotEmpty())
        // Edgware, High Barnet and Mill Hill East trains go north; Victoria trains never reach Bank.
        assertTrue(board.departures.all { it.arrivalThere != null })
        assertFalse(board.departures.any { it.headsign == "Edgware" || it.line == "Victoria" })
        assertEquals(board.departures.map { it.expected }.sorted(), board.departures.map { it.expected })
        assertEquals("King's Cross St. Pancras", board.stopName)
    }

    @Test fun findsThePlannedTrainAndWhatMissingItCosts() {
        val board = board()
        val planned = assertNotNull(board.planned)
        assertEquals(iso("2026-10-31T18:08:00Z"), planned.scheduled)
        assertEquals(iso("2026-10-31T18:17:00Z"), planned.arrivalThere)
        assertNotNull(planned.platform)

        val next = assertNotNull(board.nextAfterPlanned)
        assertEquals(iso("2026-10-31T18:11:00Z"), next.scheduled)
        assertTrue(abs((board.arrivalDifference(next) ?: 0.0) - 180) < 1)

        val earlier = assertNotNull(board.earlierThanPlanned(iso("2026-10-31T18:00:00Z")))
        assertEquals(iso("2026-10-31T18:05:00Z"), earlier.scheduled)
        assertTrue(abs((board.arrivalDifference(earlier) ?: 0.0) + 180) < 1)
        // Once the 18:05 has gone, there's nothing earlier to run for.
        assertNull(board.earlierThanPlanned(iso("2026-10-31T18:06:00Z")))
    }

    @Test fun carriesTheLinesStatus() {
        assertTrue(board().alerts.any { it.header == "Minor Delays" })
        val northern = DepartureProvider.Response.Alert("Minor Delays", "Northern Line: Minor delays due to train cancellations.")
        val station = DepartureProvider.Response.Alert("Lifts", "No step-free access at this station until Sunday.")
        assertTrue(DepartureProvider.concerns(northern, "Northern"))
        assertFalse(DepartureProvider.concerns(northern, "Metropolitan"))
        assertTrue(DepartureProvider.concerns(station, "Metropolitan"))
    }

    @Test fun plansNothingWhenNoTrainLeavesThen() {
        val near = board(northernLeg("2026-10-31T18:09:30Z")).planned?.scheduled
        assertTrue(near == iso("2026-10-31T18:08:00Z") || near == iso("2026-10-31T18:11:00Z"))
        assertNull(board(northernLeg("2026-10-31T19:30:00Z")).planned)
    }

    @Test fun boardRequestNarrowsToTheRightKindOfVehicle() {
        val url = assertNotNull(DepartureProvider.boardRequest(northernLeg())).url
        val items = java.net.URI(url).rawQuery.split("&").associate { it.substringBefore("=") to java.net.URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        assertEquals("SUBWAY", items["mode"])
        assertEquals("true", items["fetchStops"])
        assertEquals(kingsCross.pair, items["center"])
        // Twenty minutes before the planned departure, so the ones just missed show too.
        assertEquals(Time.isoString(iso("2026-10-31T17:48:00Z")), items["time"])
        // A walk has no board.
        assertNull(DepartureProvider.boardRequest(northernLeg().copy(mode = LegMode.walk)))
    }

    @Test fun nearbyGroupsByLineAndDirectionFromTheNearestStop() {
        val here = Coordinate(51.5308, -0.1238)
        val lines = DepartureProvider.parseNearby(Fixtures.bytes("transitous-nearby.json"), here, iso("2026-10-31T18:19:00Z"))
        assertTrue(lines.isNotEmpty())
        assertEquals(lines.size, lines.map { it.id }.toSet().size)
        assertTrue(lines.all { it.departures.isNotEmpty() && it.departures.size <= 3 && it.distance < 450 })
        assertTrue(lines.all { line -> line.departures.all { it.stopName == line.stopName } })
        assertTrue(lines.any { it.mode == LegMode.tube && it.line == "Northern" })
        assertTrue(lines.any { it.mode == LegMode.bus })
        val bands = lines.map { (it.distance / 100).toInt() }
        assertEquals(bands.sorted(), bands)
    }

    // The strip under a ride.

    @Test fun theStripCentresOnYoursAndNamesEachTile() {
        val board = board()
        val leg = northernLeg()
        val now = iso("2026-10-31T18:00:00Z")
        val shown = Strip.window(board, leg.departure, before = 1, after = 2)
        assertEquals(4, shown.size)
        assertEquals(board.plannedId, shown[1].id)
        assertEquals(Strip.Caption.Yours, Strip.caption(shown[1], board, leg.departure, now))
        assertEquals(Strip.Caption.Minutes(-3), Strip.caption(shown[0], board, leg.departure, now))
        assertEquals(Strip.Caption.Minutes(3), Strip.caption(shown[2], board, leg.departure, now))
        // Yours, once it has left, is gone.
        assertEquals(Strip.Caption.Gone, Strip.caption(shown[1], board, leg.departure, iso("2026-10-31T18:09:00Z")))
        // No room: yours alone and the next.
        assertEquals(listOf(board.plannedId), Strip.window(board, leg.departure, 0, 1).take(1).map { it.id })
    }

    @Test fun theVerdictSaysWhatRunningGainsAndMissingCosts() {
        val board = board()
        val verdict = assertIs<Strip.Verdict.Plain>(Strip.verdict(board, iso("2026-10-31T18:00:00Z")))
        assertEquals(iso("2026-10-31T18:05:00Z"), verdict.earlier)
        assertEquals(3, verdict.gain)
        assertEquals(3, verdict.cost)
        assertNull(verdict.late)
        // Can't be at the stop before 18:06 (a change): nothing earlier to run for.
        val later = assertIs<Strip.Verdict.Plain>(Strip.verdict(board, iso("2026-10-31T18:00:00Z"), reachable = iso("2026-10-31T18:06:00Z")))
        assertNull(later.earlier)
    }

    @Test fun aCancelledTrainIsSaidFirst() {
        val board = board()
        val cancelled = board.copy(departures = board.departures.map { if (it.id == board.plannedId) it.copy(isCancelled = true) else it })
        val verdict = assertIs<Strip.Verdict.Cancelled>(Strip.verdict(cancelled, iso("2026-10-31T18:00:00Z")))
        assertEquals(iso("2026-10-31T18:08:00Z"), verdict.scheduled)
        assertNotNull(verdict.next)
    }

    @Test fun minutesRoundAsSwiftDoes() {
        assertEquals(-3, Strip.signedMinutes(-150.0))
        assertEquals(3, Strip.signedMinutes(150.0))
        assertEquals(0, Strip.signedMinutes(29.0))
    }

    // What it costs.

    private class CountingTransport(private val fail: Boolean = false) : HttpTransport {
        var boards = 0
        var nearby = 0
        override suspend fun send(request: HttpRequest): HttpResponse {
            if ("fetchStops=true" in request.url) boards++ else nearby++
            if (fail) return HttpResponse(503, ByteArray(0))
            return HttpResponse(200, Fixtures.bytes(if ("fetchStops=true" in request.url) "transitous-departures.json" else "transitous-nearby.json"))
        }
    }

    @Test fun aBoardIsAskedForOnceAMinute() = runTest {
        val transport = CountingTransport()
        var now = iso("2026-10-31T18:00:00Z")
        val store = DepartureStore(DepartureProvider(HTTP(transport, "test")), clock = { now })
        val leg = northernLeg()
        store.load(leg); store.load(leg); store.load(leg)
        assertEquals(1, transport.boards)
        assertNotNull(store.board(leg)?.value)
        // The same ride rebuilt (a restored page: new ids) finds the same board.
        assertNotNull(store.board(leg.copy(id = "another")))
        now = now.plusSeconds(61)
        store.load(leg)
        assertEquals(2, transport.boards)
        // Refresh straight after: the floor holds it (and says for how long); 15 s later it goes.
        store.load(leg, force = true); store.load(leg, force = true)
        assertEquals(2, transport.boards)
        assertEquals(15, store.boardHeldFor(leg))
        now = now.plusSeconds(15)
        store.load(leg, force = true)
        assertEquals(3, transport.boards)
        // A walk asks for nothing.
        store.load(leg.copy(mode = LegMode.walk, toName = "elsewhere"))
        assertEquals(3, transport.boards)
    }

    @Test fun aNearbyListFromElsewhereOrLongAgoDoesNotStandInWhileTheNewOneLoads() = runTest {
        var now: Instant = iso("2026-10-31T18:19:00Z")
        var seenWhileLoading: Loadable<List<NearbyLine>>? = null
        lateinit var store: DepartureStore
        val transport = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                seenWhileLoading = store.nearby
                return HttpResponse(200, Fixtures.bytes("transitous-nearby.json"))
            }
        }
        store = DepartureStore(DepartureProvider(HTTP(transport, "test")), clock = { now })
        val kingsCross = Coordinate(51.5308, -0.1238)
        store.loadNearby(kingsCross)
        assertEquals(Loadable.Loading, seenWhileLoading)
        // A short walk on, a minute later: the list stays up while it is asked again.
        now = now.plusSeconds(61)
        store.loadNearby(Coordinate(51.5330, -0.1238)) // 245 m on
        assertTrue(seenWhileLoading?.value?.isNotEmpty() == true)
        // A trip later, across town: the old stops are not shown as here.
        now = now.plusSeconds(61)
        store.loadNearby(Coordinate(51.5054, -0.0235))
        assertEquals(Loadable.Loading, seenWhileLoading)
        // The same place, but ten minutes on: those departures have gone.
        now = now.plusSeconds(601)
        store.loadNearby(Coordinate(51.5054, -0.0235))
        assertEquals(Loadable.Loading, seenWhileLoading)
        assertNotNull(store.nearby?.value, "and the new list replaces the wait")
    }

    @Test fun nearbyIsAskedAgainOnlyAfterAMinuteOrAMove() = runTest {
        val transport = CountingTransport()
        var now: Instant = iso("2026-10-31T18:19:00Z")
        val store = DepartureStore(DepartureProvider(HTTP(transport, "test")), clock = { now })
        val here = Coordinate(51.5308, -0.1238)
        store.loadNearby(here); store.loadNearby(here)
        assertEquals(1, transport.nearby)
        store.loadNearby(Coordinate(51.5309, -0.1238)) // 11 m on
        assertEquals(1, transport.nearby)
        now = now.plusSeconds(15) // past the floor
        store.loadNearby(Coordinate(51.5340, -0.1238)) // 360 m on
        assertEquals(2, transport.nearby)
        now = now.plusSeconds(61)
        store.loadNearby(Coordinate(51.5340, -0.1238))
        assertEquals(3, transport.nearby)
        assertTrue(store.nearby?.value?.isNotEmpty() == true)
    }

    @Test fun aFailureSaysWhyAndKeepsWhatWasThere() = runTest {
        val failing = DepartureStore(DepartureProvider(HTTP(CountingTransport(fail = true), "test")))
        failing.load(northernLeg())
        assertEquals(Loadable.Failed(Loadable.Reason.notAnswering), failing.board(northernLeg()))
        // A source that says it's busy (503) gets the full floor before Try again.
        val counting = CountingTransport(fail = true)
        val now = iso("2026-10-31T18:00:00Z")
        val store = DepartureStore(DepartureProvider(HTTP(counting, "test")), clock = { now })
        repeat(5) { store.load(northernLeg(), force = true) }
        assertEquals(1, counting.boards)
        assertEquals(15, store.boardHeldFor(northernLeg()))
        repeat(5) { store.loadNearby(Coordinate(51.5308, -0.1238), force = true) }
        assertEquals(1, counting.nearby)
    }

    /** Other failures (offline, an error): the first Try again goes at once, then 5 s between tries. */
    @Test fun aFailedLoadIsRetriedAtOnceThenWaitsAShortWhile() = runTest {
        var sent = 0
        val offline = HttpTransport { sent++; throw java.io.IOException("no route") }
        var now = iso("2026-10-31T18:00:00Z")
        val store = DepartureStore(DepartureProvider(HTTP(offline, "test")), clock = { now })
        repeat(5) { store.load(northernLeg(), force = true) }
        assertEquals(2, sent, "the first and one immediate retry")
        assertEquals(5, store.boardHeldFor(northernLeg()))
        now = now.plusSeconds(5)
        store.load(northernLeg(), force = true)
        assertEquals(3, sent)
        // A clock moved backwards never holds for longer than the floor.
        now = now.minusSeconds(3600)
        assertTrue(store.boardHeldFor(northernLeg()) <= 5)
    }

    /** A Refresh while the first load is on its way never sends a second request. */
    @Test fun aRequestOnItsWayIsNeverSentTwice() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var sent = 0
        val slow = HttpTransport { request ->
            sent++
            gate.await()
            HttpResponse(200, Fixtures.bytes(if ("fetchStops=true" in request.url) "transitous-departures.json" else "transitous-nearby.json"))
        }
        var now = iso("2026-10-31T18:00:00Z")
        val store = DepartureStore(DepartureProvider(HTTP(slow, "test")), clock = { now })
        val first = launch { store.load(northernLeg()) }
        testScheduler.runCurrent()
        now = now.plusSeconds(30)
        store.load(northernLeg(), force = true)
        val nearbyFirst = launch { store.loadNearby(Coordinate(51.5308, -0.1238)) }
        testScheduler.runCurrent()
        assertTrue(store.nearbyIsBusy())
        store.loadNearby(Coordinate(51.5308, -0.1238), force = true)
        gate.complete(Unit)
        first.join(); nearbyFirst.join()
        assertEquals(2, sent, "one board, one nearby")
        assertNotNull(store.board(northernLeg())?.value)
    }

    @Test fun nearbyIsFreshForAMinuteSoHomeNeedsNoFix() = runTest {
        var now = iso("2026-10-31T18:19:00Z")
        val store = DepartureStore(DepartureProvider(HTTP(CountingTransport(), "test")), clock = { now })
        assertFalse(store.nearbyIsFresh())
        store.loadNearby(Coordinate(51.5308, -0.1238))
        assertTrue(store.nearbyIsFresh())
        now = now.plusSeconds(61)
        assertFalse(store.nearbyIsFresh())
    }

    /** The recorded trip's H&C ride from King's Cross: no H&C train in the recording, so no planned one (iOS's no-match case). */
    @Test fun aBoardWithoutTheRidesLineHasNoYoursAndNoVerdict() {
        // Leaving 18:01: the recording's H&C trains go at 18:04 and 18:14, more than 150 s away.
        val leg = northernLeg("2026-10-31T18:01:00Z").copy(line = "Hammersmith & City", headsign = "Barking", toName = "Farringdon", path = listOf(kingsCross, Coordinate(51.5203, -0.1053)))
        val board = board(leg)
        assertNull(board.planned)
        assertNull(Strip.verdict(board, iso("2026-10-31T18:00:00Z")))
        // The strip still shows what calls at Farringdon (H&C, Circle and Metropolitan trains), timed against the ride.
        assertEquals(setOf("Hammersmith & City", "Metropolitan", "Circle"), board.departures.map { it.line }.toSet())
        // Leaving 18:04 instead, the 18:04 H&C is the planned one.
        assertNotNull(board(leg.copy(departure = iso("2026-10-31T18:04:00Z"))).planned)
    }

    /** The clock stepped back an hour: a forced load still goes, and nothing counts as fresh. */
    @Test fun aClockSteppedBackNeverHoldsRequests() = runTest {
        val transport = CountingTransport()
        var now = iso("2026-10-31T18:00:00Z")
        val store = DepartureStore(DepartureProvider(HTTP(transport, "test")), clock = { now })
        store.load(northernLeg())
        store.loadNearby(Coordinate(51.5308, -0.1238))
        now = now.minusSeconds(3600)
        assertFalse(store.nearbyIsFresh(), "a fetch \"in the future\" isn't fresh")
        assertEquals(0, store.nearbyHeldFor())
        store.load(northernLeg(), force = true)
        store.loadNearby(Coordinate(51.5308, -0.1238), force = true)
        assertEquals(2, transport.boards)
        assertEquals(2, transport.nearby)
    }
}
