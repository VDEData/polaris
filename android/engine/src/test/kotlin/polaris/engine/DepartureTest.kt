package polaris.engine

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
        store.load(leg, force = true)
        assertEquals(3, transport.boards)
        // A walk asks for nothing.
        store.load(leg.copy(mode = LegMode.walk, toName = "elsewhere"))
        assertEquals(3, transport.boards)
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
        // A failed load can be tried again at once (no minute's wait).
        val counting = CountingTransport(fail = true)
        val store = DepartureStore(DepartureProvider(HTTP(counting, "test")))
        store.load(northernLeg()); store.load(northernLeg())
        assertEquals(2, counting.boards)
    }
}
