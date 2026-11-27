package polaris.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A planner by hand: every query it's asked, and the answer it gives. No network. */
internal class Planner(journeys: List<Journey>, answered: Boolean = true) {
    val queries = mutableListOf<JourneyQuery>()
    var answer = ReplanAnswer(journeys, answered = answered, usedGoogle = answered)
    val search: suspend (JourneyQuery, Replan.Watch) -> ReplanAnswer = { query, _ ->
        queries += query
        answer
    }
}

/** ReplanTests.swift's trip to the island, its boards and the planners' answers, shared with TripRestoreTest. */
internal object Island {
    private fun at(clock: String) = Go.at(clock)

    val busStop = Coordinate(51.5040, -0.0175)
    val crossharbour = Coordinate(51.4957, -0.0144)
    val island = Coordinate(51.4950, -0.0150)
    val heronQuays = Coordinate(51.5027, -0.0215)

    /**
     * The Bakerloo trip, but off the Jubilee it's two minutes to the stop and the 07:20 277 (a
     * three-minute wait) to Crossharbour, then a walk: there at 07:34.
     */
    val toIsland: Journey
        get() {
            val journey = Go.bakerloo
            val walk = Leg(mode = LegMode.walk, fromName = "Canary Wharf", toName = "Canary Wharf Station", departure = at("07:15:00"), arrival = at("07:17:00"),
                path = listOf(Go.canaryWharf, busStop))
            val bus = Leg(mode = LegMode.bus, line = "277", headsign = "Crossharbour", fromName = "Canary Wharf Station", toName = "Crossharbour",
                departure = at("07:20:00"), arrival = at("07:30:00"), stopCount = 6, path = listOf(busStop, crossharbour), platform = "C")
            val last = Leg(mode = LegMode.walk, fromName = "Crossharbour", toName = "Island", departure = at("07:30:00"), arrival = at("07:34:00"),
                path = listOf(crossharbour, island))
            return journey.copy(legs = journey.legs.take(4) + listOf(walk, bus, last), arrival = at("07:34:00"))
        }

    /** The 277 every 20 minutes, ten minutes to Crossharbour. */
    fun busBoard(leg: Leg, planned: String = "07:20:00", cancelled: Boolean = false): DepartureBoard {
        val departures = listOf("07:00:00", "07:20:00", "07:40:00", "08:00:00").map { time ->
            Departure(id = "277-$time", line = "277", mode = LegMode.bus, lineColor = null, headsign = "Crossharbour", stopName = leg.fromName,
                stopCoordinate = leg.start!!, scheduled = at(time), expected = at(time), isRealtime = true,
                isCancelled = cancelled && time == planned, arrivalThere = at(time).plusSeconds(600))
        }
        return DepartureBoard(stopName = leg.fromName, departures = departures, plannedId = "277-$planned")
    }

    fun boards(journey: Journey, cancelled: Boolean = false): MutableMap<String, DepartureBoard> =
        Go.bakerlooBoards(journey).also { it[journey.legs[5].id] = busBoard(journey.legs[5], cancelled = cancelled) }

    data class Ride(val mode: LegMode, val line: String, val from: String, val to: String, val leaves: String, val arrives: String)

    /** A planner's journey from Baker Street at the given times, one ride per line given. */
    fun candidate(rides: List<Ride>, start: String, end: String, notices: List<ServiceNotice> = emptyList()): Journey {
        val legs = rides.map { ride ->
            Leg(mode = ride.mode, line = ride.line, fromName = ride.from, toName = ride.to, departure = at(ride.leaves), arrival = at(ride.arrives),
                path = listOf(Go.bakerStreet, heronQuays))
        }
        return Journey(departure = at(start), arrival = at(end), legs = legs, sources = listOf(SourceTag(ProviderID.tfl, "Fastest")), notices = notices)
    }

    /** From Baker Street: the Jubilee, four minutes' walk to Heron Quays, the DLR, three minutes' walk. */
    fun dlrWay(leaving: String = "06:34:00"): Journey {
        val legs = listOf(
            Leg(mode = LegMode.tube, line = "Jubilee", fromName = "Baker Street", toName = "Canary Wharf", departure = at("06:39:00"), arrival = at("06:59:00"),
                path = listOf(Go.jubileePlatform, Go.canaryWharf)),
            Leg(mode = LegMode.walk, fromName = "Canary Wharf", toName = "Heron Quays", departure = at("06:59:00"), arrival = at("07:03:00"),
                path = listOf(Go.canaryWharf, heronQuays)),
            Leg(mode = LegMode.dlr, line = "DLR", fromName = "Heron Quays", toName = "Crossharbour", departure = at("07:05:00"), arrival = at("07:10:00"),
                path = listOf(heronQuays, crossharbour)),
            Leg(mode = LegMode.walk, fromName = "Crossharbour", toName = "Island", departure = at("07:10:00"), arrival = at("07:13:00"),
                path = listOf(crossharbour, island)),
        )
        return Journey(departure = at(leaving), arrival = at("07:13:00"), legs = legs, sources = listOf(SourceTag(ProviderID.transitous, "Fastest")))
    }

    val dlr: Journey get() = dlrWay()

    /**
     * On the 06:26 Bakerloo instead of the 06:41 (a tap at 06:26:20): out at Baker Street 06:34,
     * the 06:39 Jubilee, at the 277's stop by 07:01, a minute after the 07:00 left.
     */
    fun earlyDrive(scope: CoroutineScope, search: (suspend (JourneyQuery, Replan.Watch) -> ReplanAnswer)? = null): Drive {
        val journey = toIsland
        val drive = Drive(scope, journey, at("06:22:00"), boards(journey), Go.paddington, search)
        drive.tick("06:22:00", Go.paddington)
        drive.clock = at("06:26:20")
        drive.trip.take(LiveTrip.Step.Boarded(journey.legs[1]))
        return drive
    }

    /**
     * Early onto the Bakerloo with a planner that knows the DLR way; its search done. The app ticks
     * a minute apart at most until the tap (Swift's doesn't look between 06:22 and the tap), so the
     * tests' later ticks are ticks, not returns from the background ([Drive.steady]).
     */
    fun TestScope.early(planner: Planner): Drive {
        val journey = toIsland
        val drive = Drive(this, journey, at("06:22:00"), boards(journey), Go.paddington, planner.search)
        drive.tick("06:22:00", Go.paddington)
        drive.steady("06:26:00")
        drive.clock = at("06:26:20")
        drive.trip.take(LiveTrip.Step.Boarded(journey.legs[1]))
        testScheduler.advanceUntilIdle()
        return drive
    }

    /** From Paddington while waiting there: the Elizabeth line to Canary Wharf, 17 minutes sooner. */
    val elizabethWay: Journey
        get() {
            val legs = listOf(
                Leg(mode = LegMode.elizabeth, line = "Elizabeth line", fromName = "Paddington", toName = "Canary Wharf",
                    departure = at("06:40:00"), arrival = at("06:58:00"), path = listOf(Go.paddington, Go.canaryWharf)),
                Leg(mode = LegMode.walk, fromName = "Canary Wharf", toName = "One Canada Square", departure = at("06:58:00"), arrival = at("07:03:00"),
                    path = listOf(Go.canaryWharf, Go.canadaSquare)),
            )
            return Journey(departure = at("06:36:00"), arrival = at("07:03:00"), legs = legs, sources = listOf(SourceTag(ProviderID.tfl, "Fastest")))
        }
}

/**
 * Re-planning during Go: when to look, where from, what counts as better, and how often. The
 * sample Bakerloo trip with a 277 at the end; no network, boards and candidates by hand. Mirrors
 * ReplanTests.swift; where iOS words its messages, these check [ReplanMessage]'s facts.
 */
class ReplanTest {
    private fun at(clock: String) = Go.at(clock)
    private val toIsland get() = Island.toIsland
    private fun boards(journey: Journey, cancelled: Boolean = false) = Island.boards(journey, cancelled)
    private fun ride(mode: LegMode, line: String, from: String, to: String, leaves: String, arrives: String) = Island.Ride(mode, line, from, to, leaves, arrives)

    // When to look

    /**
     * Early by two trains: the Jubilee moves up on its own (the timeline's job, said plainly), but
     * the 277 doesn't, so the three-minute wait becomes nineteen. That's worth a look.
     */
    @Test fun earlyIntoALongWaitIsAReason() = runTest {
        val drive = Island.earlyDrive(this)
        val trip = drive.trip
        val plan = trip.journey
        assertEquals(at("06:39:00"), trip.timeline.steps[3].service?.scheduled)
        assertEquals(at("07:20:00"), trip.timeline.steps[5].departure)
        assertEquals(at("07:34:00"), trip.eta)
        val raw = { leg: Leg -> drive.boards[leg.id] }

        val reason = Replan.reason(plan, trip.timeline, trip.log, trip.legIndex, trip.missedRide, raw)
        assertEquals(Replan.Reason.LongWait(5, 19 * 60.0), reason)
        assertEquals("wait.5", reason?.key)

        val status = assertNotNull(Replan.status(plan, trip.timeline, trip.log, trip.legIndex, raw))
        assertEquals(Replan.Status.LongWait(5, 19 * 60.0), status)
        // "You're early: 19 min wait for the 277", "At Canary Wharf Station, it leaves 07:20. Nothing quicker from there."
        assertEquals(ReplanMessage.LongWait(19, "277", "Canary Wharf Station", at("07:20:00"), checked = true),
            Replan.message(status, trip.timeline, checked = true))
    }

    /** Early onto the plain Bakerloo trip: the earlier Jubilee gets you in 12 minutes sooner. Nothing to search for, but worth saying. */
    @Test fun anEarlierRideOnTheSameLineIsSaidNotSearched() = runTest {
        val journey = Go.bakerloo
        val drive = Drive(this, journey, at("06:28:00"), Go.bakerlooBoards(journey), Go.paddington)
        val trip = drive.trip
        drive.tick("06:28:00", Go.paddington)
        drive.clock = at("06:30:20")
        trip.take(LiveTrip.Step.Boarded(journey.legs[1]))
        val raw = { leg: Leg -> drive.boards[leg.id] }
        assertNull(Replan.reason(journey, trip.timeline, trip.log, 1, false, raw))
        val status = assertIs<Replan.Status.OtherRide>(Replan.status(journey, trip.timeline, trip.log, 1, raw))
        assertTrue(status.leg == 3 && status.service.scheduled == at("06:43:00") && status.by == -12 * 60.0)
        // "You're early: the 06:43 Jubilee", "Arrive 07:08, 12 min sooner."
        assertEquals(ReplanMessage.OtherRide(early = true, departure = at("06:43:00"), line = "Jubilee", arrival = at("07:08:00"), minutes = 12),
            Replan.message(status, trip.timeline, checked = false))
    }

    /** Missing a tube that runs every few minutes costs four: say nothing. */
    @Test fun aSmallSlipStaysSilent() = runTest {
        val journey = Go.bakerloo
        val drive = Drive(this, journey, at("06:38:00"), Go.bakerlooBoards(journey), Go.paddington)
        drive.tick("06:38:00", Go.paddington)
        drive.tick("06:42:30", Go.paddington)
        val trip = drive.trip
        assertTrue(trip.missedRide && trip.eta == at("07:24:00"))
        assertNull(Replan.reason(journey, trip.timeline, trip.log, trip.legIndex, trip.missedRide) { drive.boards[it.id] })
        // But it's said: the Jubilee you'll now make, and what it does to the arrival.
        val status = assertNotNull(Replan.status(journey, trip.timeline, trip.log, trip.legIndex) { drive.boards[it.id] })
        // "Running late: the 06:59 Jubilee", "Arrive 07:24, 4 min later."
        assertEquals(ReplanMessage.OtherRide(early = false, departure = at("06:59:00"), line = "Jubilee", arrival = at("07:24:00"), minutes = 4),
            Replan.message(status, trip.timeline, checked = false))
    }

    /**
     * The other case: early enough for the bus before the planned one. The timeline takes it on its
     * own, and the line names the bus, which sets the arrival, not the Jubilee before it.
     */
    @Test fun earlyEnoughForTheEarlierBusIsSaid() = runTest {
        val journey = toIsland
        val drive = Drive(this, journey, at("06:20:00"), boards(journey), Go.paddington)
        val trip = drive.trip
        drive.tick("06:20:00", Go.paddington)
        drive.clock = at("06:22:20")
        trip.take(LiveTrip.Step.Boarded(journey.legs[1]))
        // Off 06:30, the 06:35 Jubilee, Canary Wharf 06:55, the stop 06:57: the 07:00 277, there 07:14.
        assertEquals(at("07:00:00"), trip.timeline.steps[5].service?.scheduled)
        assertEquals(at("07:14:00"), trip.eta)
        val raw = { leg: Leg -> drive.boards[leg.id] }
        assertNull(Replan.reason(journey, trip.timeline, trip.log, 1, false, raw))
        val status = assertNotNull(Replan.status(journey, trip.timeline, trip.log, 1, raw))
        // "You're early: the 07:00 277", "Arrive 07:14, 20 min sooner."
        assertEquals(ReplanMessage.OtherRide(early = true, departure = at("07:00:00"), line = "277", arrival = at("07:14:00"), minutes = 20),
            Replan.message(status, trip.timeline, checked = false))
    }

    /** A cancelled tube on a line every four minutes costs four: not worth a search. */
    @Test fun aCancelledTrainOnAFrequentLineStaysSilent() = runTest {
        val journey = Go.bakerloo
        val boards = Go.bakerlooBoards(journey)
        val jubilee = journey.legs[3].id
        boards[jubilee] = boards.getValue(jubilee).let { board -> board.copy(departures = board.departures.map { it.copy(isCancelled = it.id == "Jubilee-06:55:00") }) }
        val drive = Drive(this, journey, at("06:30:00"), boards, Go.start)
        drive.tick("06:30:00", Go.start)
        val trip = drive.trip
        assertEquals(at("07:24:00"), trip.eta)
        assertNull(Replan.reason(journey, trip.timeline, trip.log, trip.legIndex, false) { drive.boards[it.id] })
    }

    /** Missing the Bakerloo with a 277 at the end loses the bus too: twenty minutes late. */
    @Test fun aMissThatLosesTheBusIsAReason() = runTest {
        val journey = toIsland
        val drive = Drive(this, journey, at("06:38:00"), boards(journey), Go.paddington)
        drive.tick("06:38:00", Go.paddington)
        drive.tick("06:42:30", Go.paddington)
        val trip = drive.trip
        assertEquals(at("07:54:00"), trip.eta)
        assertEquals(Replan.Reason.Missed(1, 20 * 60.0),
            Replan.reason(journey, trip.timeline, trip.log, trip.legIndex, trip.missedRide) { drive.boards[it.id] })
    }

    /** Held after getting off, not missed at the stop: the lost bus is the reason. */
    @Test fun aLostConnectionLaterOnIsAReason() = runTest {
        val journey = toIsland
        val drive = Drive(this, journey, at("06:38:00"), boards(journey), Go.paddington)
        val trip = drive.trip
        drive.tick("06:38:00", Go.paddington)
        drive.clock = at("06:41:00")
        trip.take(LiveTrip.Step.Boarded(journey.legs[1]))
        drive.clock = at("06:53:30")
        trip.take(LiveTrip.Step.GotOff(journey.legs[1]))
        // At the Jubilee by 06:56:30: the 06:59, Canary Wharf 07:19, the stop 07:21, the 07:40.
        assertEquals(at("07:54:00"), trip.eta)
        val reason = Replan.reason(journey, trip.timeline, trip.log, trip.legIndex, false) { drive.boards[it.id] }
        assertEquals(Replan.Reason.LostConnection(3, 20 * 60.0), reason)
    }

    /** The planned 277 cancelled on its board. */
    @Test fun aCancelledRideAheadIsAReason() = runTest {
        val journey = toIsland
        val drive = Drive(this, journey, at("06:30:00"), boards(journey, cancelled = true), Go.start)
        drive.tick("06:30:00", Go.start)
        val trip = drive.trip
        assertEquals(Replan.Reason.Cancelled(5), Replan.reason(journey, trip.timeline, trip.log, trip.legIndex, false) { drive.boards[it.id] })
    }

    /**
     * Reaching a change before its board's window starts, the board can't show the earlier buses,
     * so the long wait it seems to show is no evidence: silent.
     */
    @Test fun aWaitBeforeTheBoardStartsIsNoEvidence() {
        val plan = toIsland
        val board = Island.busBoard(plan.legs[5])
        fun timeline(arrival: String, estimate: Boolean = false): TripTimeline {
            val timeline = TripTimeline.plan(plan)
            val steps = timeline.steps.toMutableList()
            steps[4] = steps[4].copy(arrival = at(arrival))
            steps[5] = steps[5].copy(service = board.planned, isEstimate = estimate)
            return timeline.copy(steps = steps)
        }
        // At the stop by 06:58, before the window (07:00) opens; the timeline still has the 07:20.
        assertNull(Replan.knownWait(plan, timeline("06:58:00"), 5, board))
        assertEquals(19 * 60.0, Replan.knownWait(plan, timeline("07:01:00"), 5, board))
        // And past what the board knows, a guess: no evidence either.
        assertNull(Replan.knownWait(plan, timeline("07:01:00", estimate = true), 5, board))
    }

    /** Go looks again only when the rides ahead change, not as a walk's estimate drifts. */
    @Test fun theFingerprintIgnoresDriftButNotANewRide() {
        val plan = toIsland
        val timeline = TripTimeline.plan(plan)
        val before = Replan.fingerprint(timeline, 0)
        val drifted = timeline.steps.toMutableList().also { it[0] = it[0].copy(arrival = it[0].arrival.plusSeconds(20)) }
        assertEquals(before, Replan.fingerprint(timeline.copy(steps = drifted), 0))
        val later = drifted.also { it[5] = it[5].copy(service = Island.busBoard(plan.legs[5], planned = "07:40:00").planned) }
        assertNotEquals(before, Replan.fingerprint(timeline.copy(steps = later), 0))
    }

    // Where from

    @Test fun asksFromTheNextStopAtTheTimelinesTime() = runTest {
        val drive = Island.earlyDrive(this)
        val trip = drive.trip
        // On the Bakerloo (no fix underground): from Baker Street when it gets in, not Paddington.
        val riding = assertNotNull(Replan.origin(trip.timeline, 1, true, trip.now))
        assertTrue(riding.resumeAt == 2 && riding.name == "Baker Street" && riding.time == at("06:34:00"))
        assertEquals(Go.bakerStreet, riding.coordinate)
        val query = riding.query(Place(name = "Island", coordinate = Island.island))
        assertEquals(TimeMode.DepartAt(at("06:34:00")), query.time)
        // Walking the change: from the Jubilee's platform when the walk ends.
        val walking = assertNotNull(Replan.origin(trip.timeline, 2, false, at("06:35:00")))
        assertTrue(walking.resumeAt == 3 && walking.name == "Baker Street" && walking.time == at("06:37:00"))
        // Waiting at a stop: from there, now.
        val waiting = assertNotNull(Replan.origin(trip.timeline, 5, false, at("07:02:00")))
        assertTrue(waiting.resumeAt == 5 && waiting.name == "Canary Wharf Station" && waiting.time == at("07:02:00"))
        // On the last ride, only a walk is left from its stop: nothing to re-plan.
        assertNull(Replan.origin(trip.timeline, 5, true, at("07:21:00")))
    }

    // Is there a better way

    /**
     * From Baker Street at 06:34 with 07:34 to beat, a new way needs 9 minutes (15% of the hour to
     * go, more than the 5-minute floor). The DLR way gives 21; the rest don't qualify. iOS also
     * words "Looking for a faster way…", "No signal here…" and "Your route is still the quickest":
     * on Android those are the app's words for [LiveTrip.ReplanState.Looking], [ReplanMessage.NoSignal]
     * and [ReplanMessage.NothingBetter], which the trip tests below check.
     */
    @Test fun offersOnlyAClearlyBetterUnflaggedWay() = runTest {
        val drive = Island.earlyDrive(this)
        val trip = drive.trip
        val origin = assertNotNull(Replan.origin(trip.timeline, 1, true, trip.now))
        val dlr = Island.candidate(listOf(ride(LegMode.tube, "Jubilee", "Baker Street", "Canary Wharf", "06:39:00", "06:59:00"),
            ride(LegMode.dlr, "DLR", "Heron Quays", "Crossharbour", "07:05:00", "07:10:00")), start = "06:34:00", end = "07:13:00")
        val sameLines = Island.candidate(listOf(ride(LegMode.tube, "Jubilee", "Baker Street", "Canary Wharf", "06:39:00", "06:59:00"),
            ride(LegMode.bus, "277", "Canary Wharf Station", "Crossharbour", "07:00:00", "07:10:00")), start = "06:34:00", end = "07:05:00")
        val closed = Island.candidate(listOf(ride(LegMode.elizabeth, "Elizabeth line", "Bond Street", "Canary Wharf", "06:40:00", "06:55:00")),
            start = "06:34:00", end = "07:00:00",
            notices = listOf(ServiceNotice("Canary Wharf part closed", "", ServiceNotice.Severity.closed, ProviderID.tfl, UUID.randomUUID().toString())))
        val gone = Island.candidate(listOf(ride(LegMode.tube, "Jubilee", "Baker Street", "Canary Wharf", "06:31:00", "06:51:00")),
            start = "06:30:00", end = "06:58:00")
        val busy = Island.candidate(listOf(ride(LegMode.tube, "Jubilee", "Baker Street", "Waterloo", "06:39:00", "06:46:00"),
            ride(LegMode.rail, "Southeastern", "Waterloo East", "Greenwich", "06:52:00", "07:02:00"),
            ride(LegMode.dlr, "DLR", "Cutty Sark", "Crossharbour", "07:10:00", "07:14:00")), start = "06:34:00", end = "07:27:00")
        val slight = Island.candidate(listOf(ride(LegMode.tube, "Metropolitan", "Baker Street", "Aldgate", "06:38:00", "06:52:00"),
            ride(LegMode.dlr, "DLR", "Tower Gateway", "Crossharbour", "07:00:00", "07:17:00")), start = "06:34:00", end = "07:27:00")

        val offer = assertNotNull(Replan.offer(trip.timeline, origin, listOf(sameLines, closed, gone, busy, slight, dlr)))
        assertEquals(dlr.id, offer.journey.id)
        assertEquals(21 * 60.0, offer.gain)
        assertNull(Replan.offer(trip.timeline, origin, listOf(sameLines, closed, gone, busy, slight)))

        // "Faster way from Baker Street", "Take the Jubilee, then the DLR to Crossharbour: arrive 07:13, 21 min sooner than waiting 19 min for the 277."
        val faster = { than: ReplanMessage.Than ->
            ReplanMessage.Faster("Baker Street", listOf("Jubilee", "DLR"), "Crossharbour", at("07:13:00"), 21, than, statusChecked = true)
        }
        assertEquals(faster(ReplanMessage.Than.Waiting(19, "277")), Replan.message(offer, Replan.Reason.LongWait(5, 19 * 60.0), trip.timeline))
        // The same offer for the other reasons: "… sooner than 07:34 this way", "… with the planned 277 cancelled", "… 21 min sooner."
        assertEquals(faster(ReplanMessage.Than.ThisWay(at("07:34:00"))), Replan.message(offer, Replan.Reason.LostConnection(5, 600.0), trip.timeline))
        assertEquals(faster(ReplanMessage.Than.Cancelled("277")), Replan.message(offer, Replan.Reason.Cancelled(5), trip.timeline))
        assertEquals(faster(ReplanMessage.Than.Nothing), Replan.message(offer, Replan.Reason.Asked, trip.timeline))
    }

    /**
     * Taking it keeps the legs already done, with their ids (the log points at them), and the
     * journey's id (the sheet follows it); the rest is the new way.
     */
    @Test fun takingTheOfferKeepsWhatsDone() = runTest {
        val drive = Island.earlyDrive(this)
        val trip = drive.trip
        val origin = assertNotNull(Replan.origin(trip.timeline, 1, true, trip.now))
        val dlr = Island.candidate(listOf(ride(LegMode.tube, "Jubilee", "Baker Street", "Canary Wharf", "06:39:00", "06:59:00"),
            ride(LegMode.dlr, "DLR", "Heron Quays", "Crossharbour", "07:05:00", "07:10:00")), start = "06:34:00", end = "07:13:00")
        val offer = assertNotNull(Replan.offer(trip.timeline, origin, listOf(dlr)))
        val spliced = Replan.splice(trip.timeline, trip.journey, offer)
        assertEquals(trip.journey.id, spliced.id)
        assertEquals(trip.journey.legs.take(2).map { it.id }, spliced.legs.take(2).map { it.id })
        assertEquals(at("06:26:00"), spliced.legs[1].departure)
        assertEquals(dlr.legs.map { it.id }, spliced.legs.drop(2).map { it.id })
        assertEquals(at("07:13:00"), spliced.arrival)
        assertEquals(trip.timeline.journey.departure, spliced.departure)
    }

    // How often

    @Test fun asksOncePerReasonAndAFewTimesPerTrip() {
        var watch = Replan.Watch()
        val wait = Replan.Reason.LongWait(5, 19 * 60.0)
        val t0 = at("06:30:00")
        assertTrue(watch.mayAsk(wait, t0))
        watch = watch.asked(wait, t0, usedGoogle = true, answered = true)
        // The same reason, even with a new wait, doesn't ask again; nor anything within 4 minutes.
        assertFalse(watch.mayAsk(Replan.Reason.LongWait(5, 22 * 60.0), t0.plusSeconds(600)))
        assertFalse(watch.mayAsk(Replan.Reason.Missed(1, 600.0), t0.plusSeconds(120)))
        assertTrue(watch.mayAsk(Replan.Reason.Missed(1, 600.0), t0.plusSeconds(300)))
        // Tapped: allowed again for the same leg's reason, but not inside the spacing.
        assertTrue(watch.mayAsk(Replan.Reason.Asked, t0.plusSeconds(300)))
        watch = watch.asked(Replan.Reason.Asked, t0.plusSeconds(300), usedGoogle = false, answered = true)
        watch = watch.asked(Replan.Reason.Cancelled(3), t0.plusSeconds(600), usedGoogle = true, answered = true)
        // Three answered: that's the trip's lot, tapped or not.
        assertFalse(watch.mayAsk(Replan.Reason.Asked, t0.plusSeconds(1200)))
        assertEquals(2, watch.googleRequests)
    }

    @Test fun noSignalLeavesTheReasonOpenButCannotLoop() {
        var watch = Replan.Watch()
        val late = Replan.Reason.LostConnection(3, 900.0)
        val t0 = at("06:50:00")
        watch = watch.asked(late, t0, usedGoogle = false, answered = false)
        assertTrue(watch.mayAsk(late, t0.plusSeconds(300)))
        watch = watch.asked(late, t0.plusSeconds(300), usedGoogle = false, answered = false)
        assertFalse(watch.mayAsk(late, t0.plusSeconds(900)))
        assertEquals(0, watch.searches)
        // Back in signal, a tap still asks.
        assertTrue(watch.mayAsk(Replan.Reason.Asked, t0.plusSeconds(900)))
    }

    /** Google three times a trip at most, failed sends included. */
    @Test fun googleAtMostThreeTimesATrip() {
        val now = at("06:30:00")
        val watch = Replan.Watch()
            .asked(Replan.Reason.Missed(1, 600.0), now, usedGoogle = true, answered = false)
            .asked(Replan.Reason.LongWait(5, 900.0), now, usedGoogle = true, answered = true)
            .asked(Replan.Reason.Asked, now, usedGoogle = true, answered = true)
        assertFalse(watch.mayUseGoogle)
        assertEquals(listOf(ProviderID.tfl, ProviderID.transitous),
            Replan.sources(setOf(ProviderID.google, ProviderID.tfl, ProviderID.transitous), true, GoogleAllowance(), watch, now))
    }

    @Test fun keepingMyRouteIsntAskedAgain() {
        val wait = Replan.Reason.LongWait(5, 19 * 60.0)
        val watch = Replan.Watch().declined(wait)
        assertFalse(watch.mayAsk(wait, at("07:00:00")))
        assertTrue(watch.mayAsk(Replan.Reason.Asked, at("07:00:00")))
    }

    /** Google only while the month has room, counted in Google's own month (Pacific time). */
    @Test fun googleOnlyInsideTheFreeAllowance() {
        val now = at("06:30:00")
        var allowance = GoogleAllowance().spending(899, now)
        val enabled = setOf(ProviderID.google, ProviderID.tfl, ProviderID.transitous)
        assertEquals(listOf(ProviderID.google, ProviderID.tfl, ProviderID.transitous), Replan.sources(enabled, true, allowance, now = now))
        allowance = allowance.spending(1, now)
        assertEquals(900, allowance.usedThisMonth(now))
        assertEquals(listOf(ProviderID.tfl, ProviderID.transitous), Replan.sources(enabled, true, allowance, now = now))
        assertEquals(listOf(ProviderID.transitous), Replan.sources(setOf(ProviderID.google, ProviderID.transitous), false, GoogleAllowance(), now = now))
        // 05:00 on 1 December in London is still 30 November in California: same month.
        assertEquals(900, allowance.usedThisMonth(iso("2026-12-01T05:00:00Z")))
        val december = iso("2026-12-01T09:00:00Z")
        assertEquals(0, allowance.usedThisMonth(december))
        assertEquals(ProviderID.google, Replan.sources(enabled, true, allowance, now = december).first())
    }

    /** Android's own: a clock stepped backwards (a wrong time corrected) counts as past the spacing, never as hours of waiting. */
    @Test fun aClockSteppedBackDoesNotHoldTheNextSearch() {
        val t0 = at("06:30:00")
        val watch = Replan.Watch().asked(Replan.Reason.Missed(1, 600.0), t0, usedGoogle = false, answered = true)
        assertFalse(watch.mayAsk(Replan.Reason.Asked, t0.plusSeconds(60)))
        assertTrue(watch.mayAsk(Replan.Reason.Asked, t0.minusSeconds(3600)))
        assertTrue(watch.mayAsk(Replan.Reason.LongWait(5, 900.0), t0.minusSeconds(1)))
        // The same reason is still settled, whatever the clock says.
        assertFalse(watch.mayAsk(Replan.Reason.Missed(1, 600.0), t0.minusSeconds(3600)))
    }

    // In Go

    /**
     * The tap that says you're on the early Bakerloo is the event: Go asks once, from Baker Street
     * when it gets in, and offers the DLR way in the sheet, the notification and the card. Ticks
     * that change nothing don't ask again.
     */
    /**
     * Android only: the app's ticks stopped in the background, and meanwhile the 277 the plan has
     * was cancelled. Coming back is caught up as a relaunch is (rides end when they got in), but
     * unlike a relaunch the trip ran all along, so what changed is still an event worth a re-plan.
     */
    @Test fun aChangeWhileTheTicksStoppedIsStillAnEvent() = runTest {
        val journey = toIsland
        val planner = Planner(listOf(Island.dlr))
        val drive = Drive(this, journey, at("06:30:00"), boards(journey), Go.paddington, planner.search)
        drive.tick("06:30:00", Go.paddington)
        drive.steady("06:32:00")
        testScheduler.advanceUntilIdle()
        assertEquals(0, planner.queries.size)
        drive.boards[journey.legs[5].id] = Island.busBoard(journey.legs[5], cancelled = true)
        // Thirteen minutes without a look.
        drive.tick("06:45:00")
        testScheduler.advanceUntilIdle()
        assertEquals(1, planner.queries.size)
    }

    @Test fun goAsksOnceAndOffersTheFasterWay() = runTest {
        val planner = Planner(listOf(Island.dlr))
        val drive = with(Island) { early(planner) }
        val trip = drive.trip
        assertEquals(1, planner.queries.size)
        val query = planner.queries.first()
        assertTrue(query.origin.name == "Baker Street" && query.time == TimeMode.DepartAt(at("06:34:00")))
        assertEquals("One Canada Square", query.destination.name)
        val offer = assertNotNull(trip.offer)
        assertEquals(21 * 60.0, offer.gain)
        val offered = assertIs<ReplanMessage.Faster>(drive.alerts.offered)
        assertEquals("Baker Street", offered.origin)
        // "21 min sooner · 07:13"
        val words = assertNotNull(trip.offerMessage)
        assertEquals(21, words.minutes)
        assertEquals(at("07:13:00"), words.arrival)
        // "Faster way: 21 min sooner, arrive 07:13" in place of the card's detail.
        assertEquals(words, trip.card().replan)
        drive.tick("06:27:00")
        drive.tick("06:28:00")
        // Swift ticks 06:29:30 next; a tick at 06:29 keeps the gap a tick's, not a return from the background.
        drive.steady("06:29:30")
        testScheduler.advanceUntilIdle()
        assertEquals(1, planner.queries.size)
        assertNotNull(trip.offer)
    }

    /**
     * Switch: the legs done stay (with the log), the rest is the new way, and the timeline, alerts
     * and card follow it. The journey keeps its id, which the sheet follows.
     */
    @Test fun switchCarriesOnByTheNewWay() = runTest {
        val planner = Planner(listOf(Island.dlr))
        val drive = with(Island) { early(planner) }
        val trip = drive.trip
        val before = trip.journey
        trip.acceptOffer()
        assertTrue(trip.offer == null && drive.alerts.offered == null)
        assertEquals(before.id, trip.journey.id)
        assertEquals(before.legs.take(2).map { it.id }, trip.journey.legs.take(2).map { it.id })
        assertTrue(trip.journey.legs.size == 6 && trip.journey.legs[4].line == "DLR")
        assertEquals(at("06:26:00"), trip.log.boardings[1]?.departure?.scheduled)
        assertTrue(trip.legIndex == 1 && trip.boarded)
        assertEquals(at("07:13:00"), trip.eta)
        assertEquals(at("07:08:30"), drive.alerts.time("getoff.4"))
        assertEquals(at("07:13:00"), trip.card().arrival)
        // The new way's rides get their boards, once.
        testScheduler.runCurrent()
        assertTrue(trip.journey.legs[2].id in drive.loaded)
        // Nothing to re-plan on the new way (ticking up to 06:30, so each look is looked at).
        drive.steady("06:30:00")
        assertEquals(1, planner.queries.size)
    }

    /** Keep my route: the offer goes and that wait isn't asked about again, even at the next stop. */
    @Test fun keepMyRouteSilencesTheReason() = runTest {
        val planner = Planner(listOf(Island.dlr))
        val drive = with(Island) { early(planner) }
        val trip = drive.trip
        trip.keepRoute()
        assertTrue(trip.offer == null && drive.alerts.offered == null)
        // Ticking all the while (Swift jumps from the tap to 06:34:10, which Android would take for a
        // return from the background and not look at): the last tick before Baker Street is ahead of
        // the clock's say-so for the ride (06:33:30).
        drive.steady("06:33:10")
        drive.tick("06:34:10", Go.bakerStreet)
        drive.steady("06:39:30")
        testScheduler.advanceUntilIdle()
        assertEquals(1, planner.queries.size)
        assertNull(trip.offer)
    }

    /** No signal underground: nothing is sent. Out at Baker Street (the next event), it asks. */
    @Test fun noSignalSendsNothingUntilTheNextStop() = runTest {
        val planner = Planner(listOf(Island.dlrWay(leaving = "06:37:00")))
        val journey = toIsland
        val drive = Drive(this, journey, at("06:22:00"), boards(journey), Go.paddington, planner.search)
        drive.online = false
        drive.tick("06:22:00", Go.paddington)
        // The app ticks a minute apart at most. Swift doesn't look between 06:22 and 06:34:10; on
        // Android that gap would be a return from the background, whose catch-up never re-plans, so
        // Baker Street wouldn't be looked at. The last tick before is ahead of the clock's say-so
        // for the ride (06:33:30), so the ride still ends on the Baker Street fix.
        drive.steady("06:26:00")
        drive.clock = at("06:26:20")
        drive.trip.take(LiveTrip.Step.Boarded(journey.legs[1]))
        assertTrue(!drive.trip.isSearching && planner.queries.isEmpty())
        drive.steady("06:33:10")
        assertTrue(planner.queries.isEmpty())
        drive.online = true
        drive.tick("06:34:10", Go.bakerStreet)
        testScheduler.advanceUntilIdle()
        assertEquals(1, planner.queries.size)
        // Now walking the change: asked from the Jubilee's platform, when the walk gets there.
        assertEquals("Baker Street", planner.queries.first().origin.name)
        assertEquals(TimeMode.DepartAt(at("06:37:10")), planner.queries.first().time)
        assertNotNull(drive.trip.offer)
    }

    /** The planner had nothing better: no offer, and the wait's line says it was checked. */
    @Test fun nothingBetterSaysTheWaitWasChecked() = runTest {
        val slow = Island.candidate(listOf(ride(LegMode.tube, "Jubilee", "Baker Street", "Canary Wharf", "06:39:00", "06:59:00"),
            ride(LegMode.dlr, "DLR", "Heron Quays", "Crossharbour", "07:23:00", "07:28:00")), start = "06:34:00", end = "07:31:00")
        val planner = Planner(listOf(slow))
        val drive = with(Island) { early(planner) }
        assertTrue(drive.trip.offer == null && drive.alerts.offered == null)
        // "You're early: 19 min wait for the 277", "At Canary Wharf Station, it leaves 07:20. Nothing quicker from there."
        assertEquals(ReplanMessage.LongWait(19, "277", "Canary Wharf Station", at("07:20:00"), checked = true), drive.trip.replanStatus)
    }

    /** "Find a faster way" on a trip going to plan: looks, and says the route is still the quickest. */
    @Test fun findAFasterWayByHand() = runTest {
        val planner = Planner(emptyList())
        val journey = Go.bakerloo
        val drive = Drive(this, journey, at("06:36:00"), Go.bakerlooBoards(journey), Go.paddington, planner.search)
        drive.tick("06:36:00", Go.paddington)
        val trip = drive.trip
        assertTrue(planner.queries.isEmpty())
        assertTrue(trip.canFindFaster)
        trip.findFaster()
        val looking = assertIs<LiveTrip.ReplanState.Looking>(trip.replan)
        assertEquals("Paddington", looking.origin.name)
        testScheduler.advanceUntilIdle()
        // "Your route is still the quickest", "Nothing gets there sooner from Paddington than 07:20."
        assertEquals(LiveTrip.ReplanState.Said(ReplanMessage.NothingBetter("Paddington", at("07:20:00"))), trip.replan)
        // Inside the 4 minutes since, the button waits.
        assertFalse(trip.canFindFaster)
    }

    /** A planner that never answers (a dead spot) can't make Go loop: two tries, then only a tap. */
    @Test fun aDeadSpotCannotLoop() = runTest {
        val planner = Planner(listOf(Island.dlr), answered = false)
        val drive = with(Island) { early(planner) }
        val trip = drive.trip
        assertEquals(1, planner.queries.size)
        // Out at Baker Street, eight minutes on: one more try, which also gets nothing. The app ticks
        // a minute apart at most throughout (as noSignalSendsNothingUntilTheNextStop says why), so
        // every event after is looked at and only the failure cap holds the planner off.
        drive.steady("06:33:10")
        drive.tick("06:34:10", Go.bakerStreet)
        testScheduler.advanceUntilIdle()
        assertEquals(2, planner.queries.size)
        drive.steady("06:39:30")
        testScheduler.advanceUntilIdle()
        drive.steady("06:45:00")
        drive.steady("06:58:30")
        drive.tick("06:59:30", Go.canaryWharf)
        testScheduler.advanceUntilIdle()
        drive.steady("07:00:30")
        drive.tick("07:01:30", Island.busStop)
        testScheduler.advanceUntilIdle()
        assertEquals(2, planner.queries.size)
        assertEquals(2, trip.replanWatch.failures)
    }

    // Review fixes

    /** Waiting at Paddington, "Find a faster way" offers the Elizabeth line from there. */
    private fun TestScope.offeredAtTheStop(): Pair<Drive, Planner> {
        val planner = Planner(listOf(Island.elizabethWay))
        val journey = Go.bakerloo
        val drive = Drive(this, journey, at("06:36:00"), Go.bakerlooBoards(journey), Go.paddington, planner.search)
        drive.tick("06:36:00", Go.paddington)
        drive.trip.findFaster()
        testScheduler.advanceUntilIdle()
        return drive to planner
    }

    /** An offer made at a stop replaces the ride there. Getting on that ride takes it back: Switch must never splice out the ride you're on. */
    @Test fun boardingTheRideAtTheStopWithdrawsTheOffer() = runTest {
        val (drive, _) = offeredAtTheStop()
        val trip = drive.trip
        val offer = assertNotNull(trip.offer)
        assertEquals(1, offer.origin.resumeAt)
        drive.clock = at("06:37:20")
        trip.take(LiveTrip.Step.Boarded(trip.journey.legs[1]))
        assertTrue(trip.offer == null && drive.alerts.offered == null)
        val before = trip.journey
        trip.acceptOffer()
        assertEquals(before, trip.journey)
        assertTrue(trip.journey.legs[1].line == "Bakerloo" && trip.boarded)
    }

    /**
     * Switch tapped (from the notification, the app just woken) after the offer's first ride has
     * gone: nothing changes, and Go says the offer has passed.
     */
    @Test fun switchAfterTheOfferHasGoneDoesNothing() = runTest {
        val (drive, _) = offeredAtTheStop()
        val trip = drive.trip
        assertNotNull(trip.offer)
        val before = trip.journey
        drive.clock = at("06:41:30") // no tick: the app was suspended
        trip.acceptOffer()
        assertEquals(before, trip.journey)
        assertTrue(trip.offer == null && drive.alerts.offered == null)
        // "That faster way has gone"
        assertEquals(LiveTrip.ReplanState.Said(ReplanMessage.OfferGone), trip.replan)
    }

    /** On the tick, an offer whose first ride has gone is taken back, notification included. */
    @Test fun aStaleOfferIsWithdrawnOnTheTick() = runTest {
        val (drive, _) = offeredAtTheStop()
        assertNotNull(drive.trip.offer)
        drive.tick("06:40:20", Go.paddington)
        assertNotNull(drive.trip.offer)
        drive.tick("06:40:40", Go.paddington)
        assertTrue(drive.trip.offer == null && drive.alerts.offered == null)
    }

    /** Three searches a trip, answered or not, taps included. */
    @Test fun threeSendsATripWhateverHappens() {
        val t0 = at("06:30:00")
        var watch = Replan.Watch()
            .asked(Replan.Reason.Missed(1, 600.0), t0, usedGoogle = false, answered = false)
            .asked(Replan.Reason.LostConnection(3, 600.0), t0.plusSeconds(300), usedGoogle = false, answered = false)
        // Two automatic tries got nothing: only a tap now, and it's the trip's third and last.
        assertFalse(watch.mayAsk(Replan.Reason.Cancelled(5), t0.plusSeconds(600)))
        assertTrue(watch.mayAsk(Replan.Reason.Asked, t0.plusSeconds(600)))
        watch = watch.asked(Replan.Reason.Asked, t0.plusSeconds(600), usedGoogle = false, answered = false)
        assertFalse(watch.mayAsk(Replan.Reason.Asked, t0.plusSeconds(1200)))
        assertEquals(3, watch.sends)
    }

    /** After a Switch the legs are new: what was settled about the old ones from the stop on is forgotten. */
    @Test fun aSwitchForgetsReasonsAboutTheOldLegs() = runTest {
        val planner = Planner(listOf(Island.dlr))
        val drive = with(Island) { early(planner) }
        assertTrue("wait.5" in drive.trip.replanWatch.settled)
        drive.trip.acceptOffer()
        assertFalse("wait.5" in drive.trip.replanWatch.settled)
    }

    /** When TfL's status couldn't be had, the offer still goes, and says it wasn't checked. */
    @Test fun anOfferWithoutTfLStatusSaysSo() = runTest {
        val planner = Planner(listOf(Island.dlr))
        planner.answer = planner.answer.copy(statusChecked = false)
        val drive = with(Island) { early(planner) }
        // "… TfL's line status couldn't be checked."
        val words = assertNotNull(drive.trip.offerMessage)
        assertFalse(words.statusChecked)
        assertEquals(words, drive.alerts.offered)
    }
}
