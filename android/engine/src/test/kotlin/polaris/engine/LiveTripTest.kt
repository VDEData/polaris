package polaris.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The journeys, boards and places the Go suites share (LiveTripTest, ReplanTest, TripRestoreTest),
 * as LiveTripTests.swift's statics are.
 */
internal object Go {
    val t0: Instant = iso("2026-10-31T10:00:00Z")
    // A, B and C about 280 m apart along a parallel; D south of C.
    val a = Coordinate(51.5300, -0.1300)
    val b = Coordinate(51.5300, -0.1260)
    val c = Coordinate(51.5300, -0.1000)
    val d = Coordinate(51.5280, -0.1000)

    /** Walk A→B for 5 min, Northern B→C for 10, walk C→D for 5. */
    fun journey(t0: Instant = this.t0): Journey {
        val walk = Leg(mode = LegMode.walk, fromName = "Start", toName = "B", departure = t0, arrival = t0.plusSeconds(300),
            distanceMeters = 280.0, path = listOf(a, b))
        val ride = Leg(mode = LegMode.tube, line = "Northern", headsign = "Morden", fromName = "B", toName = "C",
            departure = t0.plusSeconds(300), arrival = t0.plusSeconds(900), stopCount = 3, path = listOf(b, c), platform = "8")
        val last = Leg(mode = LegMode.walk, fromName = "C", toName = "D", departure = t0.plusSeconds(900),
            arrival = t0.plusSeconds(1200), path = listOf(c, d))
        return Journey(departure = t0, arrival = t0.plusSeconds(1200), legs = listOf(walk, ride, last),
            sources = listOf(SourceTag(ProviderID.tfl, "Fastest")))
    }

    // DepartureTests.swift's recorded Northern line from King's Cross.
    val kingsCross = Coordinate(51.5306, -0.1240)
    val bank = Coordinate(51.5133, -0.0886)

    fun northernLeg(departing: String = "2026-10-31T18:08:00Z"): Leg {
        val departure = iso(departing)
        return Leg(mode = LegMode.tube, line = "Northern", headsign = "Morden", fromName = "King's Cross St. Pancras", toName = "Bank",
            departure = departure, arrival = departure.plusSeconds(9 * 60), path = listOf(kingsCross, bank))
    }

    fun northernBoard(leg: Leg): DepartureBoard = DepartureProvider.parseBoard(Fixtures.bytes("transitous-departures.json"), leg)

    // Keeping time with what you do.

    // Paddington, then Baker Street on the Bakerloo and the Jubilee's Baker Street platform.
    // Norfolk Square, a public garden by Paddington; One Canada Square, the tower at Canary Wharf.
    val start = Coordinate(51.5154, -0.1739)
    val paddington = Coordinate(51.5182, -0.1757)
    val bakerStreet = Coordinate(51.5226, -0.1571)
    val jubileePlatform = Coordinate(51.5230, -0.1570)
    val canaryWharf = Coordinate(51.5035, -0.0187)
    val canadaSquare = Coordinate(51.5049, -0.0195)

    fun at(clock: String): Instant = iso("2026-11-16T${clock}Z")

    /**
     * The sample trip, as planned: walk 06:34–06:39 to Paddington, the 06:41 Bakerloo to Baker
     * Street (06:49), three minutes to the Jubilee, the 06:55 to Canary Wharf (07:15), and a
     * five-minute walk: there at 07:20.
     */
    val bakerloo: Journey
        get() {
            val walk = Leg(mode = LegMode.walk, fromName = "Norfolk Square", toName = "Paddington", departure = at("06:34:00"), arrival = at("06:39:00"),
                distanceMeters = 300.0, path = listOf(start, paddington))
            val bakerloo = Leg(mode = LegMode.tube, line = "Bakerloo", headsign = "Elephant & Castle", fromName = "Paddington", toName = "Baker Street",
                departure = at("06:41:00"), arrival = at("06:49:00"), stopCount = 3, path = listOf(paddington, bakerStreet), platform = "3")
            val change = Leg(mode = LegMode.walk, fromName = "Baker Street", toName = "Baker Street", departure = at("06:49:00"), arrival = at("06:52:00"),
                path = listOf(bakerStreet, jubileePlatform))
            val jubilee = Leg(mode = LegMode.tube, line = "Jubilee", headsign = "Stratford", fromName = "Baker Street", toName = "Canary Wharf",
                departure = at("06:55:00"), arrival = at("07:15:00"), stopCount = 9, path = listOf(jubileePlatform, canaryWharf))
            val last = Leg(mode = LegMode.walk, fromName = "Canary Wharf", toName = "One Canada Square", departure = at("07:15:00"), arrival = at("07:20:00"),
                path = listOf(canaryWharf, canadaSquare))
            return Journey(departure = at("06:34:00"), arrival = at("07:20:00"), legs = listOf(walk, bakerloo, change, jubilee, last),
                sources = listOf(SourceTag(ProviderID.tfl, "Fastest")))
        }

    /** A line's board: one train at each time, [run] minutes to where you get off, the planned one leaving at [planned]. */
    fun tubeBoard(leg: Leg, times: List<String>, run: Double, planned: String): DepartureBoard {
        val departures = times.map { time ->
            Departure(id = "${leg.line}-$time", line = leg.line!!, mode = LegMode.tube, lineColor = null, headsign = leg.headsign ?: "",
                stopName = leg.fromName, stopCoordinate = leg.start!!, scheduled = at(time), expected = at(time), isRealtime = true,
                isCancelled = false, arrivalThere = at(time).plusSeconds(run * 60))
        }
        return DepartureBoard(stopName = leg.fromName, departures = departures, plannedId = "${leg.line}-$planned")
    }

    fun bakerlooBoards(journey: Journey): MutableMap<String, DepartureBoard> = mutableMapOf(
        journey.legs[1].id to tubeBoard(journey.legs[1], listOf("06:22:00", "06:26:00", "06:30:00", "06:34:00", "06:37:00", "06:41:00",
            "06:44:00", "06:48:00", "06:52:00", "06:56:00", "07:00:00", "07:04:00"), run = 8.0, planned = "06:41:00"),
        journey.legs[3].id to tubeBoard(journey.legs[3], listOf("06:35:00", "06:39:00", "06:43:00", "06:47:00", "06:51:00", "06:55:00",
            "06:59:00", "07:03:00", "07:07:00", "07:11:00", "07:15:00"), run = 20.0, planned = "06:55:00"),
    )
}

/** Pending alerts as Go hands them over, kept in a list instead of the notification shade. */
internal class RecordedAlerts : TripAlertScheduling {
    var pending: List<TripAlert> = emptyList()
    var schedules = 0
    /** The faster way on show as a notification, if any. */
    var offered: ReplanMessage? = null
    override fun schedule(alerts: List<TripAlert>) { pending = alerts; schedules += 1 }
    override fun cancel() { pending = emptyList() }
    override fun offer(message: ReplanMessage?) { offered = message }
    fun time(id: String): Instant? = pending.firstOrNull { it.id == id }?.date
    fun kind(id: String): TripAlert.Kind? = pending.firstOrNull { it.id == id }?.kind
}

/** The ongoing notification as Go drives it, kept in a list. */
internal class RecordedPresenter : TripPresenter {
    val starts = mutableListOf<Boolean>()
    var updates = 0
    var ended = 0
    override fun start(destination: String, card: TripCard, adopt: Boolean) { starts += adopt }
    override fun update(card: TripCard) { updates += 1 }
    override fun end() { ended += 1 }
}

/**
 * A trip driven by hand: its clock, its fix and its boards are the test's. Started fresh from
 * [journey], or picked up from [saved] (TripRestoreTests' `Reopened`). Coroutines go on [scope],
 * the test's own, so none outlives the test.
 */
internal class Drive(
    scope: CoroutineScope,
    journey: Journey,
    start: Instant,
    boards: Map<String, DepartureBoard>,
    fix: Coordinate?,
    search: (suspend (JourneyQuery, Replan.Watch) -> ReplanAnswer)? = null,
    saved: SavedTrip? = null,
    savedFix: LiveTrip.Fix? = null,
    val store: TripStore? = null,
) {
    var clock: Instant = start
    var fix: LiveTrip.Fix? = savedFix ?: fix?.let { LiveTrip.Fix(it, start) }
    var boards: MutableMap<String, DepartureBoard> = boards.toMutableMap()
    val alerts = RecordedAlerts()
    val presenter = RecordedPresenter()
    val loaded = mutableListOf<String>()
    /** Whether the phone has a way to the internet, for re-plans. */
    var online = true
    /** When the trip last looked (a refresh or its start), to keep the gaps a test means. */
    var lastLook: Instant? = null
        private set
    val trip: LiveTrip

    init {
        val query = JourneyQuery(Place(name = "Norfolk Square", coordinate = journey.legs[0].start!!),
            Place(name = "One Canada Square", coordinate = journey.legs.last().end!!), TimeMode.LeaveNow)
        trip = LiveTrip(
            journey = saved?.journey ?: journey, query = saved?.query ?: query, scope = scope,
            clock = { clock }, fixSource = { this.fix }, timetable = { this.boards[it.id] }, loadBoard = { loaded += it.id },
            alerts = alerts, search = search, isOnline = { online }, presenter = presenter, store = store, saved = saved,
        )
    }

    /** The clock moves on to [time]; a fix there if given (else the last one, ageing). */
    fun tick(time: String, place: Coordinate? = null) {
        clock = Go.at(time)
        if (place != null) fix = LiveTrip.Fix(place, clock)
        refresh()
    }

    fun refresh() {
        trip.refresh()
        lastLook = clock
    }

    fun start() {
        trip.start()
        lastLook = clock
    }

    /**
     * The app's own ticks, a minute apart at most (Android stops them only in the background), from
     * the last look up to [until], the fix unchanged. Swift's tests jump minutes between looks,
     * which Android's [LiveTrip.GAP_SECONDS] rule takes for the app coming back from the background.
     */
    fun steady(until: String) {
        val end = Go.at(until)
        // Never back before the clock (a tap may have moved it on since the last look).
        var t = maxOf((lastLook ?: clock).plusSeconds(60), clock)
        while (t < end) {
            clock = t
            refresh()
            t = t.plusSeconds(60)
        }
        clock = end
        refresh()
    }
}

/** A trip driven on a test's own scope: the Swift tests' `LiveTrip(journey:..., location:..., departures:...)`. */
internal fun TestScope.plainTrip(journey: Journey, clock: () -> Instant, timetable: (Leg) -> DepartureBoard? = { null }): LiveTrip {
    val query = JourneyQuery(Place(name = "Start", coordinate = Go.a), Place(name = "D", coordinate = Go.d), TimeMode.LeaveNow)
    return LiveTrip(journey, query, this, clock, { null }, timetable, {})
}

/**
 * Following a journey: where you are on a walk, what to do next, whether you'll make the train, and
 * which leg you're on. Mirrors LiveTripTests.swift; where iOS words the Live Activity and the alerts,
 * these check the facts the app words ([TripCard], [TripAlert.Kind]).
 */
class LiveTripTest {
    private val a = Go.a
    private val b = Go.b
    private val c = Go.c
    private val d = Go.d
    private val t0 = Go.t0
    private fun at(clock: String) = Go.at(clock)

    @Test fun locatesYouAlongAPath() {
        val length = PathProgress.length(listOf(a, b))
        assertTrue(abs(length - 277) < 5)
        // Halfway along, 30 m north of the line.
        val point = Coordinate(51.53027, -0.1280)
        val progress = assertNotNull(PathProgress.locate(point, listOf(a, b)))
        assertTrue(abs(progress.distanceFromPath - 30) < 3)
        assertTrue(abs(progress.distanceAlong - length / 2) < 5)
        assertTrue(abs(progress.remaining - length / 2) < 5)
        // Past the end clamps to it.
        val beyond = assertNotNull(PathProgress.locate(Coordinate(51.53, -0.1200), listOf(a, b)))
        assertEquals(0.0, beyond.remaining)
    }

    @Test fun guidanceNamesTheNextTurnAndHowFar() {
        val route = WalkRoute(listOf(a, b), listOf(
            WalkRoute.Step("", 40.0),
            WalkRoute.Step("Turn right onto Argyle Street", 100.0),
            WalkRoute.Step("Turn left onto Euston Road", 60.0),
        ), distance = 200.0, expectedTime = 150.0)
        val start = route.guidance(10.0)
        assertEquals("Turn right onto Argyle Street", start.first)
        assertEquals(30.0, start.second)
        val middle = route.guidance(100.0)
        assertEquals("Turn left onto Euston Road", middle.first)
        assertEquals(40.0, middle.second)
        val end = route.guidance(150.0)
        assertNull(end.first)
        assertEquals(50.0, end.second)
    }

    @Test fun connectionVerdicts() {
        val now = t0
        // 270 m at 1.35 m/s is 200 s, plus 90 s to the platform.
        val tight = Connection.make(270.0, null, now, now.plusSeconds(400), LegMode.tube)
        assertEquals(now.plusSeconds(290), tight.ready)
        assertEquals(Connection.Verdict.tight, tight.verdict)
        assertEquals(Connection.Verdict.comfortable, Connection.make(270.0, null, now, now.plusSeconds(600), LegMode.tube).verdict)
        assertEquals(Connection.Verdict.missing, Connection.make(270.0, null, now, now.plusSeconds(200), LegMode.tube).verdict)
        // A brisk measured pace makes it.
        assertEquals(Connection.Verdict.tight, Connection.make(270.0, 2.2, now, now.plusSeconds(250), LegMode.tube).verdict)
        // Buses need less time from the pavement.
        assertTrue(Connection.platformTime(LegMode.bus) < Connection.platformTime(LegMode.tube))
    }

    @Test fun movesOnLegByLeg() {
        val journey = Go.journey()
        assertEquals(0, TripProgress.initialLeg(journey, t0))
        // Started late, mid-ride.
        assertEquals(1, TripProgress.initialLeg(journey, t0.plusSeconds(600)))
        // Still walking: stays put.
        assertEquals(0, TripProgress.advance(journey, 0, t0.plusSeconds(60), a))
        // At the station: on to the ride.
        assertEquals(1, TripProgress.advance(journey, 0, t0.plusSeconds(200), b))
        // Underground with no fix: time moves the ride on.
        assertEquals(2, TripProgress.advance(journey, 1, t0.plusSeconds(880), null))
        assertEquals(1, TripProgress.advance(journey, 1, t0.plusSeconds(600), null))
        // Still on the platform after it's due in: you didn't get on.
        assertEquals(1, TripProgress.advance(journey, 1, t0.plusSeconds(960), b))
        assertTrue(TripProgress.missed(journey.legs[1], t0.plusSeconds(420), b))
        assertFalse(TripProgress.missed(journey.legs[1], t0.plusSeconds(420), c))
    }

    /** iOS: `activityState()`. The card says what to do; its size limit is ActivityKit's, so not checked here. */
    @Test fun theCardSaysWhatToDo() = runTest {
        val journey = Go.journey()
        val trip = plainTrip(journey, clock = { t0.plusSeconds(-60) })
        val card = trip.card()
        // "Walk to B"
        val step = assertIs<TripCard.Step.WalkToRide>(card.step)
        assertEquals("B", step.stop)
        assertEquals("Northern", card.line)
        assertEquals(TripCard.Countdown.leaves, card.countdown)
        assertNull(card.lineColor)
    }

    // If you miss it

    /**
     * The recorded Northern from King's Cross (18:08, at Bank 18:17; the next is 18:11, at Bank
     * 18:20), a 5 min walk, then a train at [trainLeaves] for 26 min, then a 5 min walk to the end.
     */
    private fun connecting(trainLeaves: String): Journey {
        val northern = Go.northernLeg()
        val bank = Leg(mode = LegMode.walk, fromName = "Bank", toName = "Liverpool Street", departure = northern.arrival,
            arrival = northern.arrival.plusSeconds(300))
        val leaves = iso(trainLeaves)
        val train = Leg(mode = LegMode.rail, line = "Greater Anglia", headsign = "Southend Victoria", fromName = "Liverpool Street",
            toName = "Southend Victoria", departure = leaves, arrival = leaves.plusSeconds(26 * 60))
        val last = Leg(mode = LegMode.walk, fromName = "Southend Victoria", toName = "Southend Pier", departure = train.arrival,
            arrival = train.arrival.plusSeconds(300))
        return Journey(departure = northern.departure, arrival = last.arrival, legs = listOf(northern, bank, train, last),
            sources = listOf(SourceTag(ProviderID.tfl, "Fastest")))
    }

    /** Greater Anglia trains every quarter of an hour from the planned one; empty to have none. */
    private fun trainBoard(train: Leg, every: List<Int> = listOf(0, 15, 30)): DepartureBoard {
        val departures = every.map { minutes ->
            val leaves = train.departure.plusSeconds((minutes * 60).toLong())
            Departure(id = "ga-$minutes", line = "Greater Anglia", mode = LegMode.rail, lineColor = null, headsign = "Southend Victoria",
                stopName = "Liverpool Street", stopCoordinate = Go.bank, scheduled = leaves, expected = leaves, isRealtime = false,
                isCancelled = false, arrivalThere = leaves.plusSeconds(train.duration))
        }
        return DepartureBoard(stopName = "Liverpool Street", departures = departures, plannedId = if (departures.isEmpty()) null else "ga-0")
    }

    private fun ifMissed(journey: Journey, train: DepartureBoard?): IfMissed? {
        val northern = Go.northernBoard(journey.legs[0])
        return IfMissed.make(journey, 0) { if (it.mode == LegMode.tube) northern else if (it.mode == LegMode.rail) train else null }
    }

    @Test fun missingItCanCostTheTrainAfter() {
        // At Liverpool Street 18:22 for the 18:24: the 18:11 Northern gets you there at 18:25, too late for it.
        val journey = connecting("2026-10-31T18:24:00Z")
        val missed = assertNotNull(ifMissed(journey, trainBoard(journey.legs[2])))
        assertEquals(iso("2026-10-31T18:11:00Z"), missed.next.scheduled)
        assertTrue(missed.losesConnection)
        assertEquals("Greater Anglia", missed.connection?.line)
        // The 18:39 instead: there 15 min later, not 3.
        assertEquals(15, missed.cost)
        assertFalse(missed.costIsFloor)
    }

    @Test fun missingItCanCostNothing() {
        // The train isn't until 18:35: the 18:11 still makes it, so you arrive at the same time.
        val journey = connecting("2026-10-31T18:35:00Z")
        val missed = assertNotNull(ifMissed(journey, trainBoard(journey.legs[2])))
        assertFalse(missed.losesConnection)
        assertEquals("Greater Anglia", missed.connection?.line)
        assertEquals(0, missed.cost)
    }

    @Test fun missingItWithLittleToGoOn() {
        val journey = connecting("2026-10-31T18:24:00Z")
        // No board for the train: you'd miss it, but how late you'd be is unknown.
        val unknown = assertNotNull(ifMissed(journey, null))
        assertTrue(unknown.losesConnection)
        assertNull(unknown.cost)
        // A board with nothing after yours: at least as late as the board reaches (30 min after it).
        val floor = assertNotNull(ifMissed(journey, trainBoard(journey.legs[2], every = listOf(0))))
        assertTrue(floor.costIsFloor)
        assertEquals(30, floor.cost)
    }

    /**
     * iOS: `liveActivitySaysWhatMissingItCosts`. The card carries what missing it costs, from the
     * trip's own boards; "+15 min", "no later" and "30+ min" are the app's words for these facts.
     */
    @Test fun theCardSaysWhatMissingItCosts() = runTest {
        val journey = connecting("2026-10-31T18:24:00Z")
        val northern = Go.northernBoard(journey.legs[0])
        val train = trainBoard(journey.legs[2])
        val trip = plainTrip(journey, clock = { iso("2026-10-31T18:00:00Z") }) {
            if (it.mode == LegMode.tube) northern else if (it.mode == LegMode.rail) train else null
        }
        val card = trip.card()
        assertIs<TripCard.Step.Waiting>(card.step)
        val miss = assertNotNull(card.miss)
        // The next one is a Northern too, so no other line to name.
        assertNull(miss.nextLine)
        assertEquals(iso("2026-10-31T18:11:00Z"), northern.departures.first { it.expected == miss.nextDeparture }.scheduled)
        assertEquals(15, miss.cost)
        assertFalse(miss.costIsFloor)
        assertTrue(miss.isCostly)
        assertTrue(miss.losesConnection)
        assertEquals(journey.legs[2].departure to "Greater Anglia", miss.connection)
        // Still makes it.
        assertFalse(miss.copy(cost = 0, losesConnection = false).isCostly)
        // Past what the boards know.
        assertTrue(miss.copy(cost = 30, costIsFloor = true, losesConnection = false).isCostly)
    }

    // Already aboard

    /** Walk A→B, the 25 bus B→C (about 1.8 km east), walk C→D. */
    private fun busJourney(t0: Instant): Journey {
        val journey = Go.journey(t0)
        return journey.copy(legs = journey.legs.mapIndexed { i, leg -> if (i == 1) leg.copy(mode = LegMode.bus, line = "25", platform = null) else leg })
    }

    /** [metres] east of B, on the bus's road. */
    private fun alongRide(metres: Double) = Coordinate(b.latitude, b.longitude + metres / (111_320 * cos(b.latitude * Math.PI / 180)))

    @Test fun noticesYoureAlreadyOnTheBus() {
        val journey = busJourney(t0)
        val detector = BoardingDetector()
        // On an earlier bus: moving along its road at 6 m/s, past where you'd get on.
        val found = (0..14 step 2).map { second ->
            detector.ride(journey, 0, alongRide(150.0 + second * 6), t0.plusSeconds(second.toLong()))
        }
        assertTrue(found.take(3).all { it == null }, "not from one fix")
        assertEquals(1, found.last())
    }

    @Test fun walkingAlongTheBusRouteIsntRiding() {
        val journey = busJourney(t0)
        val walking = BoardingDetector()
        val towards = BoardingDetector()
        val waiting = BoardingDetector()
        for (second in 0..60 step 2) {
            val at = t0.plusSeconds(second.toLong())
            // Briskly, the way the bus goes.
            assertNull(walking.ride(journey, 0, alongRide(150 + second * 1.6), at))
            // Towards the stop from the far side, the way people come to it.
            assertNull(towards.ride(journey, 0, alongRide(400 - second * 1.4), at))
            // At the stop.
            assertNull(waiting.ride(journey, 1, alongRide(10.0), at))
        }
    }

    @Test fun getsOffWhereYouAreNotWhenThePlanSaid() {
        val journey = busJourney(t0)
        // An earlier bus: at C two minutes before the planned one gets in.
        assertEquals(2, TripProgress.advance(journey, 1, t0.plusSeconds(780), c))
        // A later bus: the plan's time has come, but your bus isn't due yet.
        val late = t0.plusSeconds(1100)
        assertEquals(1, TripProgress.advance(journey, 1, t0.plusSeconds(900), alongRide(900.0), rideArrival = late))
        assertEquals(2, TripProgress.advance(journey, 1, late, null, rideArrival = late))
    }

    /** iOS also checks the bus is watched live (`busWatch`): Android has no live bus positions yet. */
    @Test fun oneTapSaysYoureOnItThenOff() = runTest {
        val journey = busJourney(t0)
        val trip = plainTrip(journey, clock = { t0.plusSeconds(-60) })
        assertEquals(0, trip.legIndex)
        assertEquals(LiveTrip.Step.Boarded(journey.legs[1]), trip.manualStep)
        trip.take(LiveTrip.Step.Boarded(journey.legs[1]))
        // On it before it was due to leave: the step says when to get off, not a countdown.
        assertEquals(1, trip.legIndex)
        assertFalse(trip.isWaiting)
        assertEquals(LiveTrip.Step.GotOff(journey.legs[1]), trip.manualStep)
        assertEquals(TripCard.Countdown.getOff, trip.card().countdown)
        trip.take(LiveTrip.Step.GotOff(journey.legs[1]))
        assertEquals(2, trip.legIndex)
        assertNull(trip.manualStep)
    }

    // Keeping time with what you do

    /**
     * The sample Bakerloo trip: at Paddington ten minutes early, on the 06:30 instead of the 06:41
     * with a tap, no fix underground (the phone keeps Paddington's), then out at Baker Street.
     * Every time downstream follows: the train, the get-off time, the Jubilee now made, the
     * arrival and the pending alerts.
     */
    @Test fun keepsTimeWithAnEarlierTrain() = runTest {
        val journey = Go.bakerloo
        val drive = Drive(this, journey, at("06:25:00"), Go.bakerlooBoards(journey), Go.start)
        val trip = drive.trip
        drive.tick("06:25:00")
        // As planned before setting off.
        assertEquals(0, trip.legIndex)
        assertEquals(at("07:20:00"), trip.eta)
        assertEquals(at("06:33:00"), drive.alerts.time("leave"))
        assertEquals(at("06:47:30"), drive.alerts.time("getoff.1"))
        assertEquals(at("07:13:30"), drive.alerts.time("getoff.3"))

        // At Paddington by 06:28, then down to the platform: the fix stays there.
        drive.tick("06:28:00", Go.paddington)
        assertEquals(1, trip.legIndex)
        assertTrue(trip.isWaiting)
        assertEquals(at("06:41:00"), trip.rideDeparture)
        assertEquals(LiveTrip.Step.Boarded(journey.legs[1]), trip.manualStep)

        // On the 06:30, says the tap at 06:30:20 (the app ticking all the while, so not a catch-up).
        drive.steady("06:30:00")
        drive.clock = at("06:30:20")
        trip.take(LiveTrip.Step.Boarded(journey.legs[1]))
        assertTrue(trip.boarded)
        assertEquals(at("06:30:00"), trip.yourDeparture?.scheduled)
        assertEquals(at("06:30:00"), trip.rideDeparture)
        assertEquals(at("06:38:00"), trip.rideArrival)
        assertEquals(at("06:30:00"), trip.timeline.journey.legs[1].departure)
        // Out at 06:38, the change by 06:41: the 06:43 Jubilee, not the 06:55, so 07:08, not 07:20.
        assertEquals(at("06:43:00"), trip.timeline.steps[3].service?.scheduled)
        assertEquals(at("07:08:00"), trip.eta)
        val card = trip.card()
        assertEquals(TripCard.Countdown.getOff, card.countdown)
        assertEquals(at("06:38:00"), card.countdownTo)
        assertEquals(at("07:08:00"), card.arrival)
        // "On the 06:30, get off at Baker Street"
        assertEquals(at("06:30:00"), assertIs<TripCard.Step.Riding>(card.step).yours)
        assertNull(drive.alerts.time("leave"))
        assertEquals(at("06:36:30"), drive.alerts.time("getoff.1"))
        assertEquals(at("07:01:30"), drive.alerts.time("getoff.3"))
        // "Then the Jubilee at 06:43 from Baker Street."
        assertEquals(TripAlert.Kind.GetOff("Baker Street", TripAlert.Then.Ride("Jubilee", at("06:43:00"), "Baker Street")), drive.alerts.kind("getoff.1"))

        // A stop away, the phone still holding Paddington's fix: get off in about 3 min, not 11,
        // and the ride isn't held at Paddington by that old fix.
        drive.steady("06:35:00")
        assertEquals(1, trip.legIndex)
        assertEquals(3, ((trip.rideArrival since trip.now) / 60).toInt())
        assertFalse(trip.missedRide)
        val estimate = assertNotNull(trip.estimatedPosition)
        assertTrue(estimate.distance(Go.paddington) > 500 && estimate.distance(Go.bakerStreet) > 300)

        // Out at Baker Street at 06:38:10, a fix there: the ride is done and its alert goes. The
        // app's last tick before is 06:37:10, ahead of the clock's say-so (06:37:30): over a longer
        // gap Go would take 06:38:10 for a return from the background and end the ride at 06:38.
        drive.steady("06:37:10")
        drive.tick("06:38:10", Go.bakerStreet)
        assertEquals(2, trip.legIndex)
        assertEquals(at("06:38:10"), trip.log.ended[1])
        assertNull(drive.alerts.time("getoff.1"))
        assertEquals(at("06:43:00"), trip.nextRideDeparture)
        assertEquals(at("07:08:00"), trip.eta)
        assertEquals(at("07:01:30"), drive.alerts.time("getoff.3"))
        assertEquals(at("06:43:00"), trip.card().countdownTo)
    }

    /**
     * Off later than the train you're on said (held outside the station): the change and
     * everything after move to the next Jubilee you can make.
     */
    @Test fun gettingOffLateMovesTheRestOn() = runTest {
        val journey = Go.bakerloo
        val drive = Drive(this, journey, at("06:28:00"), Go.bakerlooBoards(journey), Go.paddington)
        val trip = drive.trip
        drive.tick("06:28:00", Go.paddington)
        drive.clock = at("06:30:20")
        trip.take(LiveTrip.Step.Boarded(journey.legs[1]))
        assertEquals(at("07:08:00"), trip.eta)
        // Held: off at 06:40:30 instead of 06:38, at the change by 06:43:30, so the 06:47.
        drive.clock = at("06:40:30")
        trip.take(LiveTrip.Step.GotOff(journey.legs[1]))
        assertEquals(at("06:47:00"), trip.timeline.steps[3].service?.scheduled)
        assertEquals(at("07:12:00"), trip.eta)
        assertEquals(at("07:05:30"), drive.alerts.time("getoff.3"))
        // Back to the ride by hand: what happened from there is forgotten.
        trip.select(1)
        assertTrue(trip.log.ended[1] == null && !trip.boarded)
        // Ending Go clears every alert.
        trip.stop()
        assertTrue(drive.alerts.pending.isEmpty())
    }

    /**
     * Still on the platform after the planned one left (a fresh fix says so): the trip moves to
     * the next one you could have caught since you were last seen there.
     */
    @Test fun missingThePlannedTrainMovesToTheNext() = runTest {
        val journey = Go.bakerloo
        val drive = Drive(this, journey, at("06:38:00"), Go.bakerlooBoards(journey), Go.paddington)
        val trip = drive.trip
        drive.tick("06:38:00", Go.paddington)
        assertEquals(at("06:41:00"), trip.rideDeparture)
        drive.tick("06:42:30", Go.paddington)
        assertTrue(trip.missedRide)
        assertEquals(at("06:44:00"), trip.rideDeparture)
        assertTrue(trip.isWaiting)
        assertEquals(at("06:52:00"), trip.rideArrival)
        // Jubilee from 06:56: the 06:59, there 07:19, at the tower 07:24.
        assertEquals(at("07:24:00"), trip.eta)
        assertEquals(at("06:50:30"), drive.alerts.time("getoff.1"))
    }

    /**
     * The recorded Northern board: a tap three minutes before the planned 18:08 puts you on the
     * 18:05 to Morden, and its own time at Bank is when you get off.
     */
    @Test fun boardingSnapsToTheRecordedBoard() = runTest {
        val northern = Go.northernLeg()
        val board = Go.northernBoard(northern)
        val walk = Leg(mode = LegMode.walk, fromName = "Bank", toName = "One Canada Square", departure = northern.arrival,
            arrival = northern.arrival.plusSeconds(300), path = listOf(Go.bank, Go.canadaSquare))
        val journey = Journey(departure = northern.departure, arrival = walk.arrival, legs = listOf(northern, walk),
            sources = listOf(SourceTag(ProviderID.tfl, "Fastest")))
        val drive = Drive(this, journey, iso("2026-10-31T18:04:00Z"), mapOf(northern.id to board), null)
        val trip = drive.trip
        assertTrue(trip.isWaiting)
        drive.clock = iso("2026-10-31T18:05:20Z")
        trip.take(LiveTrip.Step.Boarded(northern))
        val yours = assertNotNull(trip.yourDeparture)
        assertEquals(iso("2026-10-31T18:05:00Z"), yours.scheduled)
        assertEquals("Morden", yours.headsign)
        val there = assertNotNull(yours.arrivalThere)
        assertEquals(there, trip.rideArrival)
        assertTrue(there < iso("2026-10-31T18:17:00Z"))
        assertEquals(there.plusSeconds(300), trip.eta)
    }

    @Test fun snapsToTheTrainNearestTheTap() {
        val leg = Go.bakerloo.legs[1]
        val board = Go.tubeBoard(leg, listOf("06:30:00", "06:34:00"), run = 8.0, planned = "06:34:00")
        // Just after one left, it's that one.
        assertEquals(at("06:30:00"), TripLog.departure(at("06:31:30"), board)?.scheduled)
        // Just before one leaves, that one.
        assertEquals(at("06:34:00"), TripLog.departure(at("06:33:40"), board)?.scheduled)
        // Nothing near: the moment itself stands in.
        assertNull(TripLog.departure(at("06:50:00"), board))
        assertNull(TripLog.departure(at("06:31:00"), null))
    }

    /** No boards at all: the tap's moment and the ride's run time. */
    @Test fun boardingWithNoBoardTimesFromTheTap() = runTest {
        val journey = Go.bakerloo
        val drive = Drive(this, journey, at("06:28:00"), emptyMap(), Go.paddington)
        val trip = drive.trip
        drive.tick("06:28:00", Go.paddington)
        drive.clock = at("06:31:00")
        trip.take(LiveTrip.Step.Boarded(journey.legs[1]))
        assertEquals(at("06:39:00"), trip.rideArrival)
        // Early for the Jubilee with no board to say what's sooner: the planned one, 07:20.
        assertEquals(at("07:20:00"), trip.eta)
    }

    /**
     * Alerts stay pending right up to when they're due: Go re-plans every two seconds, and an
     * alert dropped from the plan just before its time would never fire.
     */
    @Test fun alertsSurviveUntilTheyFire() = runTest {
        val journey = Go.bakerloo
        val drive = Drive(this, journey, at("06:25:00"), Go.bakerlooBoards(journey), Go.start)
        drive.tick("06:25:00")
        assertEquals(at("06:33:00"), drive.alerts.time("leave"))
        // Still at the start two minutes before it: leave stays.
        drive.tick("06:31:30")
        drive.tick("06:32:45")
        assertEquals(at("06:33:00"), drive.alerts.time("leave"))
        // At Paddington, on the 06:41 as planned: get off at 06:47:30.
        drive.tick("06:39:00", Go.paddington)
        drive.clock = at("06:41:10")
        drive.trip.take(LiveTrip.Step.Boarded(journey.legs[1]))
        assertEquals(at("06:47:30"), drive.alerts.time("getoff.1"))
        drive.tick("06:47:10")
        assertEquals(at("06:47:30"), drive.alerts.time("getoff.1"))
        // Once its time has gone, it's gone from the plan.
        drive.tick("06:47:40")
        assertNull(drive.alerts.time("getoff.1"))
    }

    /**
     * No fix on a change between lines (deep underground): the walk keeps its planned time instead
     * of stretching with the clock, so the onward train and its alert hold.
     */
    @Test fun aChangeWithNoFixHoldsTheOnwardTrain() = runTest {
        val journey = Go.bakerloo
        val drive = Drive(this, journey, at("06:39:00"), Go.bakerlooBoards(journey), Go.paddington)
        drive.tick("06:39:00", Go.paddington)
        drive.clock = at("06:41:10")
        drive.trip.take(LiveTrip.Step.Boarded(journey.legs[1]))
        drive.clock = at("06:49:00")
        drive.trip.take(LiveTrip.Step.GotOff(journey.legs[1]))
        assertEquals(at("06:55:00"), drive.trip.timeline.steps[3].service?.scheduled)
        // Ten minutes on, the fix still Paddington's: still the 06:55, still off at 07:13:30.
        drive.tick("06:53:00")
        drive.tick("06:56:00")
        assertEquals(2, drive.trip.legIndex)
        assertEquals(at("06:55:00"), drive.trip.timeline.steps[3].service?.scheduled)
        assertEquals(at("07:13:30"), drive.alerts.time("getoff.3"))
    }

    /** [metres] along the Bakerloo from Paddington towards Baker Street. */
    private fun alongBakerloo(metres: Double) = PathProgress.point(metres, listOf(Go.paddington, Go.bakerStreet))!!

    /**
     * Go started (or the ride surfaced) five minutes into the 06:41, nobody tapped, and Go sees you
     * riding: you got on when the train left, not when it noticed.
     */
    @Test fun detectedBoardingCountsTheRideAlreadyDone() = runTest {
        val journey = Go.bakerloo
        val length = PathProgress.length(listOf(Go.paddington, Go.bakerStreet))
        val start = at("06:46:00")
        val drive = Drive(this, journey, start, Go.bakerlooBoards(journey), null)
        assertEquals(1, drive.trip.legIndex)
        // Moving along the line at 10 m/s from where five of eight minutes put you.
        for (second in 0..12 step 2) {
            drive.clock = start.plusSeconds(second.toLong())
            drive.fix = LiveTrip.Fix(alongBakerloo(length * 5 / 8 + second * 10), drive.clock)
            drive.refresh()
        }
        assertTrue(drive.trip.boarded)
        assertEquals(at("06:41:00"), drive.trip.yourDeparture?.scheduled)
        assertEquals(at("06:49:00"), drive.trip.rideArrival)
        assertEquals(at("07:20:00"), drive.trip.eta)
        assertEquals(at("07:13:30"), drive.alerts.time("getoff.3"))
    }

    /**
     * The platform's last fix came moments before the tap: it still says nothing about where you
     * are once aboard, so the clock moves the ride on.
     */
    @Test fun aFixJustBeforeTheTapDoesntHoldTheRide() = runTest {
        val journey = Go.bakerloo
        val drive = Drive(this, journey, at("06:28:00"), Go.bakerlooBoards(journey), Go.paddington)
        drive.tick("06:28:00", Go.paddington)
        drive.tick("06:30:00", Go.paddington)
        drive.clock = at("06:30:20")
        drive.trip.take(LiveTrip.Step.Boarded(journey.legs[1]))
        assertEquals(at("06:38:00"), drive.trip.rideArrival)
        assertNotNull(drive.trip.estimatedPosition)
        // Ticking all ride with nothing new: alerts aren't sent again and again (up to 06:36:30,
        // when the get-off alert is due and leaves the plan).
        val sent = drive.alerts.schedules
        var clock = at("06:30:22")
        while (clock < at("06:36:20")) {
            drive.clock = clock
            drive.refresh()
            clock = clock.plusSeconds(2)
        }
        assertEquals(sent, drive.alerts.schedules)
        assertEquals(at("07:08:00"), drive.trip.eta)
        drive.tick("06:37:40")
        assertEquals(2, drive.trip.legIndex)
    }

    /** An alert whose time has come by the time it's handed over isn't added again (the system has shown it, or is about to). */
    @Test fun alertsAlreadyDueAreNotAddedAgain() {
        val now = at("06:36:30")
        val kind = TripAlert.Kind.GetOff("", TripAlert.Then.Walk(""))
        val alerts = listOf(TripAlert("getoff.1", now.plusSeconds(0.5), kind), TripAlert("getoff.3", now.plusSeconds(600), kind))
        assertEquals(listOf("getoff.3"), TripAlerts.toAdd(alerts, now).map { it.id })
    }

    // Android's own

    /**
     * The planned train four minutes late on its board: the timeline re-times the ride to 06:45,
     * but the board is still found, by the plan's leg (Android's board store keys a board by the
     * leg's planned time, as this timetable does), for what missing it costs and for the strip.
     */
    @Test fun aLatePlannedTrainsBoardIsStillFound() = runTest {
        val journey = Go.bakerloo
        val boards = Go.bakerlooBoards(journey)
        val bakerloo = boards.getValue(journey.legs[1].id)
        val late = bakerloo.copy(departures = bakerloo.departures.map {
            if (it.id == bakerloo.plannedId) it.copy(expected = at("06:45:00"), arrivalThere = at("06:53:00")) else it
        })
        // Keyed as the store keys them: the stop, the line and the leg's planned time.
        fun key(leg: Leg) = "${leg.start?.pair}|${leg.line}|${leg.departure.epochSecond}"
        val byPlannedTime = mapOf(key(journey.legs[1]) to late, key(journey.legs[3]) to boards.getValue(journey.legs[3].id))
        var now = at("06:36:00")
        val query = JourneyQuery(Place(name = "Norfolk Square", coordinate = Go.start), Place(name = "One Canada Square", coordinate = Go.canadaSquare), TimeMode.LeaveNow)
        val trip = LiveTrip(journey, query, this, { now }, { LiveTrip.Fix(Go.paddington, now) }, { byPlannedTime[key(it)] }, {})
        trip.refresh()
        assertEquals(1, trip.legIndex)
        assertTrue(trip.isWaiting)
        val ride = trip.timeline.journey.legs[1]
        assertEquals(at("06:45:00"), ride.departure)
        // Through the re-timed leg, the board is the plan's.
        val board = assertNotNull(trip.board(ride))
        assertEquals("Bakerloo-06:41:00", board.plannedId)
        val missed = assertNotNull(trip.ifMissed)
        assertEquals(at("06:48:00"), missed.next.scheduled)
        assertNotNull(trip.card().miss)
    }

    /**
     * Go asks for boards on every tick and leaves the limits to the store: driven every two seconds
     * for ten minutes, at most one request per board per minute while they answer; while they fail,
     * no faster than the store's retries (the first at once, then 5 s apart, 15 s after a 503).
     */
    @Test fun aTripAsksForBoardsNoFasterThanTheStoreAllows() = runTest {
        class Recording(val answer: (HttpRequest) -> HttpResponse) : HttpTransport {
            val sent = mutableListOf<Pair<String, Instant>>()
            var now: () -> Instant = { Instant.EPOCH }
            override suspend fun send(request: HttpRequest): HttpResponse {
                sent += request.url to now()
                return answer(request)
            }
            fun gaps(): Map<String, List<Double>> = sent.groupBy({ it.first }, { it.second }).mapValues { (_, times) ->
                times.zipWithNext { x, y -> y since x }
            }
        }

        suspend fun drive(transport: Recording) {
            val journey = Go.bakerloo
            var now = at("06:30:00")
            transport.now = { now }
            val store = DepartureStore(DepartureProvider(HTTP(transport, "test")), clock = { now })
            val query = JourneyQuery(Place(name = "Norfolk Square", coordinate = Go.start), Place(name = "One Canada Square", coordinate = Go.canadaSquare), TimeMode.LeaveNow)
            // Waiting at Paddington for the 06:41, its board due every tick.
            val trip = LiveTrip(journey, query, this, { now }, { LiveTrip.Fix(Go.paddington, now) }, { store.board(it)?.value }, { store.load(it) })
            trip.start()
            testScheduler.runCurrent()
            val end = at("06:40:00")
            while (now < end) {
                now = now.plusSeconds(2)
                trip.refresh()
                testScheduler.runCurrent()
            }
            trip.stop()
        }

        val healthy = Recording { HttpResponse(200, Fixtures.bytes("transitous-departures.json")) }
        drive(healthy)
        val healthyGaps = healthy.gaps()
        assertEquals(2, healthyGaps.size, "the Bakerloo's board and the Jubilee's")
        assertTrue(healthyGaps.values.flatten().all { it >= 60 }, "${healthyGaps.values}")
        // The ride's own board kept fresh: once a minute, 06:30 to 06:40.
        assertEquals(10, healthyGaps.values.maxOf { it.size })

        val offline = Recording { throw java.io.IOException("no route") }
        drive(offline)
        val offlineGaps = offline.gaps().values.maxBy { it.size }
        assertTrue(offlineGaps.first() >= 0)
        assertTrue(offlineGaps.drop(1).all { it >= DepartureStore.RETRY_SECONDS }, "$offlineGaps")
        assertTrue(offlineGaps.size >= 50, "it keeps trying: ${offlineGaps.size}")

        val busy = Recording { HttpResponse(503, ByteArray(0)) }
        drive(busy)
        val busyGaps = busy.gaps().values.maxBy { it.size }
        assertTrue(busyGaps.all { it >= DepartureStore.MIN_SECONDS }, "$busyGaps")
        assertTrue(busyGaps.size >= 30, "it keeps trying: ${busyGaps.size}")
    }

    /**
     * Android stopped the app's ticks in the background, on the ride, until twenty minutes past
     * its arrival: one look on the way back ends the ride when the timeline had it in, not then.
     */
    @Test fun aLongGapEndsTheRideWhenItGotIn() = runTest {
        val journey = Go.bakerloo
        val drive = Drive(this, journey, at("06:39:00"), Go.bakerlooBoards(journey), Go.paddington)
        drive.tick("06:39:00", Go.paddington)
        drive.steady("06:41:00")
        drive.clock = at("06:41:10")
        drive.trip.take(LiveTrip.Step.Boarded(journey.legs[1]))
        drive.tick("06:41:12")
        assertEquals(at("06:49:00"), drive.trip.rideArrival)
        // Nothing until 07:09; the fix still Paddington's.
        drive.tick("07:09:00")
        assertEquals(2, drive.trip.legIndex)
        assertEquals(at("06:49:00"), drive.trip.log.ended[1])
    }

    /** On the last walk the card says the distance as shown: a few metres more walked is the same card (no new notification every two seconds). */
    @Test fun theLastWalksCardChangesOnlyWithTheDistanceShown() = runTest {
        val journey = Go.bakerloo
        val drive = Drive(this, journey, Go.at("07:15:30"), Go.bakerlooBoards(journey), Go.canaryWharf)
        drive.trip.select(4)
        fun along(f: Double) = Coordinate(Go.canaryWharf.latitude + (Go.canadaSquare.latitude - Go.canaryWharf.latitude) * f,
            Go.canaryWharf.longitude + (Go.canadaSquare.longitude - Go.canaryWharf.longitude) * f)
        drive.tick("07:15:40", along(0.20))
        val first = drive.trip.card()
        drive.tick("07:15:42", along(0.205))
        assertEquals(first, drive.trip.card())
        drive.tick("07:16:20", along(0.70))
        kotlin.test.assertNotEquals(first, drive.trip.card())
    }

    /** Still at the stop after the planned train left: you'll get on the next one, so the button says you're on it, not that you've got off. */
    @Test fun afterMissingItTheStepIsGettingOnNotGettingOff() = runTest {
        val journey = Go.bakerloo
        // No board (none loaded yet, or none for the line): nothing says the next one is coming.
        val drive = Drive(this, journey, Go.at("06:36:00"), emptyMap(), Go.paddington)
        drive.tick("06:36:00", Go.paddington)
        drive.steady("06:43:00")
        drive.tick("06:43:00", Go.paddington)
        assertTrue(drive.trip.missedRide)
        assertEquals(LiveTrip.Step.Boarded(journey.legs[1]), drive.trip.manualStep)
    }

    /**
     * Stepped on by hand to the last walk while the fix is still far away: the fix's nearest point on
     * the walk is its end, but it isn't on the walk, so the whole walk is still to do and the
     * banner and the walk left agree.
     */
    @Test fun aFixFarFromTheWalkDoesNotSayYouAreThere() = runTest {
        val journey = Go.bakerloo
        val drive = Drive(this, journey, Go.at("07:15:30"), Go.bakerlooBoards(journey), Go.paddington)
        drive.trip.select(4)
        drive.tick("07:15:40", Go.paddington)
        val full = drive.trip.walk!!.distance
        assertEquals(full, drive.trip.remainingWalk!!, 1.0)
        assertEquals(full, drive.trip.guidance!!.second, 1.0)
        // Back on the walk, it counts again.
        drive.tick("07:16:20", Go.canadaSquare)
        assertTrue(drive.trip.remainingWalk!! < full - 50)
    }

    /**
     * Stepped on by hand to a change while the fix is still far off: the timeline walks the same
     * metres at the same pace as the will-you-make-it check, so it never holds you to a train that
     * check says you'll miss.
     */
    @Test fun offTheWalkTheTimelineAndThePaceCheckAgree() = runTest {
        val plan = Go.bakerloo
        val journey = plan.copy(legs = plan.legs.mapIndexed { i, leg -> if (i == 2) leg.copy(distanceMeters = 400.0) else leg })
        val drive = Drive(this, journey, Go.at("06:49:30"), Go.bakerlooBoards(journey), Go.paddington)
        drive.trip.select(2)
        drive.tick("06:49:30", Go.paddington)
        val connection = drive.trip.connection!!
        kotlin.test.assertNotEquals(Connection.Verdict.missing, connection.verdict, "timeline holds to a train the pace check misses")
    }

    /**
     * Still minutes away on foot, the strip mustn't say "make the 06:37 and you reach Baker Street
     * sooner": the strip is given when you'd be on the platform, and leaves out what goes before.
     */
    @Test fun theStripOffersOnlyAnEarlierTrainYouCanReach() = runTest {
        val journey = Go.bakerloo
        val boards = Go.bakerlooBoards(journey)
        val drive = Drive(this, journey, Go.at("06:33:00"), boards, Go.start)
        drive.tick("06:33:00", Go.start)
        val reachable = assertNotNull(drive.trip.reachableBy)
        val board = boards.getValue(journey.legs[1].id)
        val verdict = Strip.verdict(board, drive.trip.now, reachable) as Strip.Verdict.Plain
        assertNull(verdict.earlier, "offered the ${verdict.earlier}, which leaves before you reach the platform at $reachable")
        // At the stop, it is offered.
        drive.tick("06:34:00", Go.paddington)
        val there = Strip.verdict(board, drive.trip.now, drive.trip.reachableBy) as Strip.Verdict.Plain
        assertEquals(Go.at("06:37:00"), there.earlier)
    }

    /**
     * Inside the half minute where the timeline's change margin (60 s) would still make a train the
     * pace check's platform time (90 s for the Tube) says you'll miss: the timeline doesn't hold you
     * to it, so "You're early: the 06:55" never sits above "you'll miss the 06:55".
     */
    @Test fun theTimelineAndThePaceCheckAgreeAtTheMargin() = runTest {
        val plan = Go.bakerloo
        val journey = plan.copy(legs = plan.legs.mapIndexed { i, leg -> if (i == 2) leg.copy(distanceMeters = 400.0) else leg })
        val drive = Drive(this, journey, Go.at("06:48:50"), Go.bakerlooBoards(journey), Go.paddington)
        drive.trip.select(2)
        drive.tick("06:48:50", Go.paddington)
        val connection = drive.trip.connection!!
        kotlin.test.assertNotEquals(Connection.Verdict.missing, connection.verdict, "timeline holds to the ${drive.trip.nextRideDeparture}, the pace check misses it")
    }

    /** Aboard a train the board says is held 30 minutes: that is news, and the arrival it gives is half an hour later. */
    @Test fun aHeldTrainIsNewsWithItsLaterArrival() = runTest {
        val journey = Go.bakerloo
        val boards = Go.bakerlooBoards(journey)
        val drive = Drive(this, journey, Go.at("06:40:30"), boards, Go.paddington)
        drive.tick("06:41:00", Go.paddington)
        drive.trip.take(LiveTrip.Step.Boarded(journey.legs[1]))
        drive.tick("06:42:00", Go.paddington)
        val before = drive.trip.serviceNews
        val eta = drive.trip.eta
        val ride = journey.legs[1]
        drive.boards[ride.id] = Go.tubeBoard(ride, listOf("06:37:00", "06:41:00", "06:44:00"), run = 38.0, planned = "06:41:00")
        drive.tick("06:43:00", Go.paddington)
        kotlin.test.assertNotEquals(before, drive.trip.serviceNews)
        val later = assertNotNull(drive.trip.liveEta)
        assertTrue(java.time.Duration.between(eta, later).toMinutes() >= 29, "live arrival $later against $eta")
    }

    /** Missed it, and the next is 35 minutes later: news, and the arrival moves with it. */
    @Test fun aMissedTrainWithALongWaitIsNews() = runTest {
        val journey = Go.bakerloo
        val ride = journey.legs[1]
        val boards = mutableMapOf(ride.id to Go.tubeBoard(ride, listOf("06:41:00", "07:16:00"), run = 8.0, planned = "06:41:00"))
        val drive = Drive(this, journey, Go.at("06:40:00"), boards, Go.paddington)
        drive.tick("06:40:00", Go.paddington)
        val before = drive.trip.serviceNews
        drive.steady("06:43:00")
        drive.tick("06:43:00", Go.paddington)
        kotlin.test.assertNotEquals(before, drive.trip.serviceNews)
        assertTrue(drive.trip.eta > journey.arrival.plusSeconds(30 * 60L))
    }

    /** Still at the start, the clock running on: no ride news (the estimate drifting isn't news). */
    @Test fun walkingThereIsNoServiceNews() = runTest {
        val journey = Go.bakerloo
        val drive = Drive(this, journey, Go.at("06:30:00"), Go.bakerlooBoards(journey), Go.start)
        drive.tick("06:30:00", Go.start)
        val first = drive.trip.serviceNews
        drive.tick("06:50:00", Go.start)
        assertEquals(first, drive.trip.serviceNews)
    }

    /** Walking with no fix yet (or approximate location only): you could be on the platform at the walk's end plus the platform time, never now. */
    @Test fun withNoFixTheReachableTimeIsTheWalksEndPlusThePlatform() = runTest {
        val journey = Go.bakerloo
        val drive = Drive(this, journey, Go.at("06:33:00"), Go.bakerlooBoards(journey), null)
        drive.tick("06:33:00", null)
        val reachable = assertNotNull(drive.trip.reachableBy)
        val walkEnd = drive.trip.timeline.steps[0].arrival
        assertEquals(walkEnd.plusSeconds(Connection.platformTime(LegMode.tube).toLong()), reachable)
    }

    /** "Time to leave" leaves the walk and the platform time before the train (and a minute's notice), not just the walk. */
    @Test fun timeToLeaveAllowsThePlatformTime() {
        val plan = Go.bakerloo
        // A four-minute walk ending a minute before the 06:41.
        val walk = plan.legs[0].copy(departure = Go.at("06:36:00"), arrival = Go.at("06:40:00"))
        val journey = plan.copy(departure = walk.departure, legs = listOf(walk) + plan.legs.drop(1))
        val alerts = TripAlerts.plan(TripTimeline.plan(journey), 0, TripLog(began = Go.at("06:20:00")), "One Canada Square", Go.at("06:20:00"))
        val leave = alerts.first { it.id == "leave" }
        // 06:41 less the 4-minute walk, 90 s to the platform and a minute's notice.
        assertEquals(Go.at("06:34:30"), leave.date)
    }
}
