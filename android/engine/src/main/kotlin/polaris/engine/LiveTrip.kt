package polaris.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * A journey being travelled: which leg you're on, whether you'll make the next departure, and the
 * whole trip re-timed from what you actually do ([timeline]). Ported from
 * Polaris/Engine/LiveTrip.swift.
 *
 * Confined to one thread: every call, and every coroutine it starts on [scope], runs on the same
 * one (the app's main thread; a test's own dispatcher). The clock, fixes, boards, search and alerts
 * are passed in, so tests drive it by hand.
 *
 * Differences from iOS, on purpose:
 * - No walking router on Android, so a walk is the source's own path, without turn-by-turn.
 * - No live bus positions yet (TfL's bus feed is not ported), so a bus ride is timed from the
 *   timetable board, as iOS does when TfL has nothing.
 * - Boards are found by the plan's own leg (by id), because Android's board store keys a board by
 *   the leg's planned time and the timeline's legs are re-timed.
 * - A gap of more than [GAP_SECONDS] between looks (Android stopped the app's ticks in the
 *   background) is caught up as a relaunch is, so a ride the clock finished ends when it got in.
 */
class LiveTrip(
    journey: Journey,
    query: JourneyQuery,
    private val scope: CoroutineScope,
    private val clock: () -> Instant,
    private val fixSource: () -> Fix?,
    private val timetable: (Leg) -> DepartureBoard?,
    private val loadBoard: suspend (Leg) -> Unit,
    private val alerts: TripAlertScheduling = TripAlertScheduling.None,
    private val search: (suspend (JourneyQuery, Replan.Watch) -> ReplanAnswer)? = null,
    private val isOnline: () -> Boolean = { true },
    private val presenter: TripPresenter = TripPresenter.None,
    /** Where the trip is kept for a relaunch; null keeps it in memory only (tests). */
    private val store: TripStore? = null,
    saved: SavedTrip? = null,
) {
    /** A location fix, when it was taken (on the trip's own clock), the speed then in m/s and its accuracy in metres where known. */
    data class Fix(val coordinate: Coordinate, val at: Instant, val speed: Double? = null, val accuracy: Double? = null)

    /** Re-planning, as a satnav does: looking for a faster way, offering one, or saying why not. */
    sealed interface ReplanState {
        data object Idle : ReplanState
        data class Looking(val origin: Replan.Origin) : ReplanState
        data class Offered(val offer: Replan.Offer, val reason: Replan.Reason) : ReplanState
        data class Said(val message: ReplanMessage) : ReplanState
    }

    /** What the one-tap step button does now. */
    sealed interface Step {
        data class Boarded(val ride: Leg) : Step
        data class GotOff(val ride: Leg) : Step
    }

    /** The plan, as found, or as switched to mid-trip. */
    var journey: Journey = saved?.journey ?: journey
        private set
    val query: JourneyQuery = saved?.query ?: query
    var legIndex: Int
        private set
    /** The walk you're on: the source's path. */
    var walk: WalkRoute? = null
        private set
    var walkProgress: PathProgress? = null
        private set
    var now: Instant
        private set
    var log: TripLog
        private set
    var timeline: TripTimeline
        private set
    var replan: ReplanState = ReplanState.Idle
        private set
    var replanWatch = Replan.Watch()
        private set
    /** Picked up after the app was stopped, not started afresh. */
    val isResumed: Boolean
    /** How many times the trip has been written; tests check it isn't every tick. */
    var saveCount = 0
        private set

    private val _version = MutableStateFlow(0)
    /** Bumped whenever something on screen may have changed, for the app to read again. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private var checkedWaits: Set<String> = emptySet()
    private var lastReplanEvent: List<String>? = null
    private var replanJob: Job? = null
    private var sentAlerts: List<TripAlert>? = null
    private val detector = BoardingDetector()
    private var lastSaved: SavedTrip? = null
    private var catchingUp = false
    private var awaitingFix: Instant? = null
    private var lookedForFix = false
    private var quietSince: Instant? = null
    private var alertsMayBePending = false
    private var quietLegs: Set<String> = emptySet()
    private var savedOfferKey: String? = null
    private var started = false
    private var stopped = false
    /** When Go last looked, to tell a tick from a long gap. */
    private var lastLook: Instant? = null

    init {
        val start = clock()
        now = start
        if (saved != null) {
            isResumed = true
            log = saved.log
            legIndex = min(max(0, saved.legIndex), saved.journey.legs.size - 1)
            replanWatch = saved.replanWatch
            checkedWaits = saved.checkedWaits
            lastSaved = saved
            quietSince = start
            alertsMayBePending = true
            quietLegs = saved.journey.legs.drop(saved.legIndex).filter { it.mode.isTransit }.map { it.id }.toSet()
            savedOfferKey = saved.offerKey
        } else {
            isResumed = false
            log = TripLog(began = start)
            legIndex = TripProgress.initialLeg(journey, start)
        }
        timeline = TripTimeline.plan(this.journey)
        if (!leg.mode.isTransit) walk = WalkRoute.fallback(leg)
        retime()
    }

    val leg: Leg get() = journey.legs[legIndex]
    val isLastLeg: Boolean get() = legIndex == journey.legs.size - 1
    /** Your latest fix, however old: the map's dot. */
    val position: Coordinate? get() = fixSource()?.coordinate
    /** You're on the ride you're at: seen moving along it, or you said so. */
    val boarded: Boolean get() = log.boardings[legIndex] != null
    /** When you'll get there, as the trip is going. */
    val eta: Instant get() = timeline.arrival
    val isRunning: Boolean get() = started && !stopped

    /** On the last leg and seen within [TripProgress.arrivedDistance] of its end: there. */
    val arrived: Boolean
        get() {
            if (!isLastLeg) return false
            val end = leg.end ?: return false
            val here = trustedPosition ?: return false
            return here.distance(end) < TripProgress.arrivedDistance
        }

    /** Where you are as far as the leg you're on goes: a fix from before you got on or off says where you were. */
    val trustedPosition: Coordinate?
        get() {
            val fix = fixSource() ?: return null
            if (leg.mode.isTransit) {
                val since = log.boardings[legIndex]?.at ?: (if (isWaiting) null else rideDeparture) ?: return fix.coordinate
                return if (fix.at >= since) fix.coordinate else null
            }
            if (legIndex > 0) {
                val since = log.ended[legIndex - 1]
                if (since != null && fix.at < since.plusSeconds(-30.0)) return null
            }
            return fix.coordinate
        }

    /** On a ride with no fix to go on (underground): where it has got to by the clock, drawn as an estimate. */
    val estimatedPosition: Coordinate?
        get() {
            if (!leg.mode.isTransit || isWaiting || leg.path.size < 2 || trustedPosition != null) return null
            val step = timeline.steps.getOrNull(legIndex) ?: return null
            val span = step.arrival since step.departure
            if (span <= 0) return null
            val fraction = min(1.0, max(0.0, (now since step.departure) / span))
            return PathProgress.point(fraction * PathProgress.length(leg.path), leg.path)
        }

    /** The ride this walk leads to, if any. */
    val nextRide: Leg?
        get() {
            if (leg.mode.isTransit || legIndex + 1 >= journey.legs.size) return null
            return journey.legs[legIndex + 1].takeIf { it.mode.isTransit }
        }

    val board: DepartureBoard? get() = nextRide?.let(::board)

    /** A ride's departures, with the one the timeline has you on (or catching) as its planned one. */
    fun board(ride: Leg): DepartureBoard? {
        val board = rawBoard(ride) ?: return null
        val index = journey.legs.indexOfFirst { it.id == ride.id }
        if (index >= legIndex && index >= 0) timeline.steps.getOrNull(index)?.let { return board.copy(plannedId = it.service?.id) }
        return board
    }

    /** A ride's board as loaded, found by the plan's own leg (the store keys boards by planned time). */
    private fun rawBoard(ride: Leg): DepartureBoard? = timetable(journey.legs.firstOrNull { it.id == ride.id } ?: ride)

    /** When the ride you're walking to leaves: the departure the timeline has you catching. */
    val nextRideDeparture: Instant? get() = if (nextRide == null) null else timeline.steps.getOrNull(legIndex + 1)?.departure

    /** When the ride you're at leaves: the one you got on, or the one you'll catch. */
    val rideDeparture: Instant get() = timeline.steps.getOrNull(legIndex)?.departure ?: leg.departure

    private val plannedRideDeparture: Instant get() = rawBoard(leg)?.planned?.expected ?: leg.departure

    /** At the stop, waiting for the ride: not on it yet, and it hasn't gone. */
    val isWaiting: Boolean get() = leg.mode.isTransit && !boarded && now < rideDeparture

    private val upcomingRide: Int?
        get() = if (nextRide != null) legIndex + 1 else if (isWaiting) legIndex else null

    /** When the ride you're on gets you there. */
    val rideArrival: Instant get() = timeline.steps.getOrNull(legIndex)?.arrival ?: leg.arrival

    /** The departure you're on, once Go knows which. */
    val yourDeparture: Departure? get() = log.boardings[legIndex]?.departure

    /** What missing that ride does to your arrival, through every later change. */
    val ifMissed: IfMissed? get() = upcomingRide?.let { IfMissed.make(timeline.journey, it) { leg -> board(leg) } }

    /** What to do next on the walk, and how far ahead it is. */
    val guidance: Pair<String?, Double>?
        get() {
            val walk = walk ?: return null
            if (leg.mode.isTransit) return null
            return walk.guidance(walkProgress?.distanceAlong ?: 0.0)
        }

    val remainingWalk: Double?
        get() = if (leg.mode.isTransit) null else walkProgress?.remaining ?: walk?.distance ?: leg.distanceMeters

    /** Your walking pace while moving. */
    private val speed: Double? get() = fixSource()?.speed?.takeIf { it > 0.6 && it < 3 }
    private val pace: Double get() = speed ?: Connection.walkingSpeed

    val connection: Connection?
        get() {
            val ride = nextRide ?: return null
            val departure = nextRideDeparture ?: return null
            val remaining = remainingWalk ?: return null
            if (trustedPosition == null) return null
            return Connection.make(remaining, speed, now, departure, ride.mode)
        }

    /**
     * When you could be on the platform for the next ride: now, at its stop; the pace check's time
     * while walking to it; null on a ride. Nothing leaving before it can be offered as one to catch.
     */
    val reachableBy: Instant?
        get() = when {
            isWaiting -> now
            !leg.mode.isTransit && nextRide != null -> connection?.ready
            else -> null
        }

    /** Still at the stop after the planned one left (a fix taken since says so). */
    val missedRide: Boolean
        get() {
            if (boarded) return false
            val fix = fixSource() ?: return false
            if (fix.at <= plannedRideDeparture) return false
            return TripProgress.missed(leg, now, fix.coordinate, plannedRideDeparture)
        }

    val manualStep: Step?
        get() {
            nextRide?.let { return Step.Boarded(it) }
            if (!leg.mode.isTransit || isLastLeg) return null
            // Still at the stop after it left (missed it): the next one is the one you'll get on.
            return if (isWaiting || missedRide) Step.Boarded(leg) else Step.GotOff(leg)
        }

    /** The platform of the train Go has you catching, once a board has it, otherwise the plan's. */
    fun platform(ride: Leg): String? {
        val index = journey.legs.indexOfFirst { it.id == ride.id }
        if (index < 0) return ride.platform
        return timeline.steps.getOrNull(index)?.service?.platform ?: ride.platform
    }

    fun take(step: Step) {
        if (stopped) return
        now = clock()
        lastLook = now
        when (step) {
            is Step.Boarded -> {
                val index = journey.legs.indexOfFirst { it.id == step.ride.id }
                if (index < 0) return
                if (index != legIndex) moveTo(index)
                recordBoarding(index)
            }
            is Step.GotOff -> {
                if (legIndex + 1 >= journey.legs.size) return
                moveTo(legIndex + 1)
            }
        }
        retime()
        publish()
    }

    fun start() {
        if (started || stopped) return
        started = true
        now = clock()
        if (isResumed) catchUp()
        retime()
        presenter.start(query.destination.name, card(), adopt = isResumed)
        // A faster way on show when the app was stopped wasn't kept: its notification's Switch would do nothing.
        if (isResumed) alerts.offer(null)
        lastLook = now
        publish()
        // Later changes' boards say what missing a ride costs and re-time the trip. Fetch any not
        // held, once (only the next ride's refreshes after).
        val later = journey.legs.drop(legIndex).filter { it.mode.isTransit && timetable(it) == null }
        if (later.isNotEmpty()) scope.launch { for (ride in later) loadBoard(ride) }
    }

    /** End: nothing more is shown, scheduled or kept. */
    fun stop() {
        if (stopped) return
        stopped = true
        presenter.end()
        alerts.cancel()
        alerts.offer(null)
        sentAlerts = null
        replanJob?.cancel()
        replanJob = null
        // Ended: nothing to pick up next launch.
        store?.clear()
        lastSaved = null
        bump()
    }

    /**
     * Picked up after the app was stopped: one look at the clock and where you are, as a tick takes,
     * for the time it was stopped. A ride the clock finished ends when it got in, not now; nothing
     * that happened meanwhile sets off a re-plan.
     */
    fun catchUp() {
        catchingUp = true
        // By the clock first: a ride due in has ended, when it was due.
        look()
        val hadFix = jumpToFix()
        catchingUp = false
        lastReplanEvent = replanEvent
        if (!hadFix && awaitingFix == null && !lookedForFix) awaitingFix = now
        lookedForFix = true
    }

    private fun jumpToFix(): Boolean {
        val fix = fixSource() ?: return false
        if ((now since fix.at) >= 120) return false
        val reached = journey.legs.indices.drop(legIndex).lastOrNull { index ->
            val leg = journey.legs[index]
            val near = if (leg.mode.isTransit) TripProgress.alightedDistance else TripProgress.arrivedDistance
            leg.end?.let { fix.coordinate.distance(it) < near } ?: false
        }
        if (reached != null && min(reached + 1, journey.legs.size - 1) > legIndex) {
            walkProgress = null
            retime()
            moveTo(min(reached + 1, journey.legs.size - 1))
            look()
        }
        return true
    }

    /** Jump to a leg by hand; automatic progress carries on from there. */
    fun select(index: Int) {
        if (stopped || index !in journey.legs.indices || index == legIndex) return
        now = clock()
        lastLook = now
        moveTo(index)
    }

    /** One look at the clock and where you are. Go does this every two seconds. */
    fun refresh() {
        if (stopped) return
        val at = clock()
        val gap = lastLook?.let { at since it }
        lastLook = at
        // Back after a long gap (the app's ticks stopped in the background): caught up as a relaunch is.
        if (gap != null && gap > GAP_SECONDS && !catchingUp) {
            now = at
            // Unlike a relaunch, the trip was running all along: what changed meanwhile is an
            // event (arriving at the change to a long wait, say), so it is looked at as one.
            val before = lastReplanEvent
            catchUp()
            lastReplanEvent = before
            publish()
            return
        }
        look()
    }

    private fun look() {
        now = clock()
        // Resumed before any fix: the first fresh one catches the trip up as the launch would have.
        val since = awaitingFix
        if (since != null && !catchingUp) {
            if ((now since since) > 120) {
                awaitingFix = null
            } else {
                val fix = fixSource()
                if (fix != null && (now since fix.at) < 120) {
                    awaitingFix = null
                    catchUp()
                    publish()
                    return
                }
            }
        }
        // Already aboard (an earlier bus, or Go started on it): catch up to that ride.
        detector.ride(journey, if (boarded) legIndex + 1 else legIndex, position, now)?.let { ride ->
            if (ride != legIndex) moveTo(ride)
            recordBoarding(ride, boardedBefore(ride))
        }
        retime()
        var advanced = TripProgress.advance(
            journey, legIndex, now, trustedPosition,
            rideArrival = if (leg.mode.isTransit) rideArrival else null,
            arrivals = timeline.steps.map { it.arrival },
        )
        // Picked up on a ride you weren't seen getting on, before its board is in: the timetable
        // can't say it's over, as it may be running late. It waits for the board, for the quiet minute at most.
        if (advanced > legIndex && holdsUnseenRide) advanced = legIndex
        if (advanced != legIndex) moveTo(advanced)

        if (!leg.mode.isTransit) {
            val walk = walk
            val here = trustedPosition
            // A fix far from the walk isn't on it (a step made by hand before the fix caught up): its
            // nearest point could be the end. iOS walks a fresh route from there; Android has no
            // walking router, so the walk counts as not begun.
            if (walk != null && here != null) walkProgress = PathProgress.locate(here, walk.path)?.takeIf { it.distanceFromPath <= OFF_WALK_METRES }
        }
        // Keep the next ride's board live until it has gone (the store asks at most once a minute).
        val ride = nextRide ?: if (isWaiting) leg else null
        if (ride != null) {
            val index = journey.legs.indexOfFirst { it.id == ride.id }
            if (index >= 0) {
                val leaves = timeline.steps[index].departure
                if ((leaves since now) < 45 * 60 && now < leaves.plusSeconds(5 * 60.0)) scope.launch { loadBoard(ride) }
            }
        }
        retime()
        publish()
    }

    private val holdsUnseenRide: Boolean get() = quietSince != null && leg.mode.isTransit && !boarded && rawBoard(leg) == null

    /** Works the timeline out again from what's happened, where you are and the boards. */
    private fun retime() {
        val fix = fixSource()
        var seenAtStop: Instant? = null
        val start = leg.start
        if (leg.mode.isTransit && !boarded && fix != null && start != null && fix.coordinate.distance(start) < 150) seenAtStop = fix.at
        // With a fix, the walk left at your pace, as the will-you-make-it check counts it: off the walk
        // (a step made by hand before the fix caught up), all of it. Catching up, a fix off the walk may
        // be past it, and jumping to the fix comes next, so it keeps its planned time, as with no fix.
        val offWalk = !catchingUp && walkProgress == null && trustedPosition != null
        val metresLeft = walkProgress?.remaining ?: if (offWalk) remainingWalk else null
        val walkLeft = if (!leg.mode.isTransit) metresLeft?.let { it / pace } else null
        timeline = TripTimeline.make(journey, log, legIndex, now, seenAtStop, null, walkLeft) { rawBoard(it) }
    }

    /** The card and the alerts follow the timeline, and Go looks for a reason to re-plan. */
    private fun publish() {
        if (catchingUp || stopped) return
        considerReplan()
        if (started) presenter.update(card())
        var pending = (sentAlerts ?: emptyList()).map { it.id }.toSet()
        if (alertsMayBePending) {
            alertsMayBePending = false
            pending = (listOf("leave") + journey.legs.indices.map { "getoff.$it" }).toSet()
        }
        val planned = TripAlerts.plan(timeline, legIndex, log, query.destination.name, now, pending)
        if (sentAlerts?.let { TripAlerts.differ(it, planned) } != false) {
            sentAlerts = planned
            alerts.schedule(planned)
        }
        persist()
        bump()
    }

    private fun bump() {
        _version.value += 1
    }

    /** The trip as it would be saved now. */
    val saved: SavedTrip
        get() {
            val key = (replan as? ReplanState.Offered)?.reason?.key
            return SavedTrip(
                journey = journey, query = query, legIndex = legIndex, log = log, replanWatch = replanWatch,
                checkedWaits = checkedWaits, arrival = eta, offerKey = key ?: savedOfferKey,
            )
        }

    /** Keeps the trip for a relaunch when something worth keeping has changed (not a walk's seconds). */
    private fun persist() {
        val store = store ?: return
        if (catchingUp || stopped) return
        val snapshot = saved
        if (lastSaved?.sameAs(snapshot) == true) return
        lastSaved = snapshot
        saveCount += 1
        store.save(snapshot)
    }

    /** Stamps which departure you got on, once: at [moment], or now (a tap). */
    private fun recordBoarding(index: Int, moment: Instant? = null) {
        if (log.boardings[index] != null || index !in journey.legs.indices) return
        val at = moment ?: now
        val departure = TripLog.departure(at, rawBoard(journey.legs[index]))
        log = log.copy(boardings = log.boardings + (index to TripLog.Boarding(at, departure)))
        retime()
    }

    /** Go sees you riding some way along: you got on about that much of the ride ago. */
    private fun boardedBefore(index: Int): Instant {
        val ride = journey.legs[index]
        val length = PathProgress.length(ride.path)
        val here = position ?: return now
        val progress = PathProgress.locate(here, ride.path) ?: return now
        if (length <= 0) return now
        return now.plusSeconds(-min(1.0, progress.distanceAlong / length) * ride.duration)
    }

    private fun moveTo(index: Int) {
        if (index > legIndex) {
            // Moving on finishes the legs between, now; catching up, when the timeline had them end, if that's passed.
            val ended = log.ended.toMutableMap()
            for (passed in legIndex until index) {
                if (ended[passed] == null) {
                    ended[passed] = if (catchingUp) minOf(now, timeline.steps.getOrNull(passed)?.arrival ?: now) else now
                }
            }
            log = log.copy(ended = ended)
        } else {
            log = log.rewound(index)
        }
        legIndex = index
        if (replan is ReplanState.Said) replan = ReplanState.Idle
        detector.reset()
        walkProgress = null
        walk = if (!leg.mode.isTransit) WalkRoute.fallback(leg) else null
        retime()
        publish()
    }

    // Re-planning

    /** The boards as loaded, whose planned departure is the plan's (not the timeline's pick). */
    private fun loadedBoard(leg: Leg): DepartureBoard? = rawBoard(leg)

    /** What the timeline has already done that's worth a line: an earlier or later ride, or a long wait. */
    val replanStatus: ReplanMessage?
        get() {
            val status = Replan.status(journey, timeline, log, legIndex, ::loadedBoard) ?: return null
            val checked = status is Replan.Status.LongWait && "wait.${status.leg}" in checkedWaits
            return Replan.message(status, timeline, checked)
        }

    /** The offer on screen, as facts. */
    val offerMessage: ReplanMessage.Faster?
        get() = (replan as? ReplanState.Offered)?.let { Replan.message(it.offer, it.reason, timeline) }

    val offer: Replan.Offer? get() = (replan as? ReplanState.Offered)?.offer

    /** A search is on its way. */
    val isSearching: Boolean get() = replanJob?.isActive == true

    /** "Find a faster way" can go now: a stop ahead to ask from, nothing running or offered, and the trip's cap and spacing allow it. */
    val canFindFaster: Boolean
        get() {
            if (search == null || isSearching || offer != null || stopped) return false
            return Replan.origin(timeline, legIndex, boarded, now) != null && replanWatch.mayAsk(Replan.Reason.Asked, now)
        }

    /** "Find a faster way", tapped. */
    fun findFaster() {
        if (stopped) return
        now = clock()
        lastLook = now
        ask(Replan.Reason.Asked)
        bump()
    }

    /** Only on an event, never on the clock: the rides ahead changed, you boarded, or a leg ended. */
    private fun considerReplan() {
        withdrawStaleOffer()
        val event = replanEvent
        quietSince?.let { since ->
            // What the restored boards change is the restore's doing, not an event.
            val boardsIn = journey.legs.filter { it.id in quietLegs }.all { timetable(it) != null }
            val gone = now since since
            if (boardsIn || (gone >= 0 && gone <= 60)) {
                if (boardsIn) quietSince = null
                lastReplanEvent = event
                return
            }
            // A board never came: the quiet is over, and this event is looked at as any other.
            quietSince = null
        }
        if (event == lastReplanEvent) return
        lastReplanEvent = event
        if (search == null || isSearching || offer != null) return
        val reason = Replan.reason(journey, timeline, log, legIndex, missedRide, ::loadedBoard) ?: return
        ask(reason)
    }

    private val replanEvent: List<String>
        get() = Replan.fingerprint(timeline, legIndex) + listOf("leg $legIndex", "on ${log.boardings.size}", "off ${log.ended.size}")

    private fun ask(reason: Replan.Reason) {
        val search = search ?: return
        if (isSearching) return
        val origin = Replan.origin(timeline, legIndex, boarded, now) ?: return
        if (!replanWatch.mayAsk(reason, now)) return
        // No way to the internet (underground): send nothing; the next stop is the next event.
        if (!isOnline()) {
            if (reason == Replan.Reason.Asked) replan = ReplanState.Said(ReplanMessage.NoSignal(origin.name))
            return
        }
        if (reason == Replan.Reason.Asked) replan = ReplanState.Looking(origin)
        val query = origin.query(this.query.destination)
        val watch = replanWatch
        replanJob = scope.launch {
            val answer = try {
                search(query, watch)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ReplanAnswer(emptyList(), answered = false, usedGoogle = false)
            }
            finish(reason, origin, answer)
        }
    }

    private fun finish(reason: Replan.Reason, origin: Replan.Origin, answer: ReplanAnswer) {
        replanJob = null
        if (stopped) return
        now = clock()
        replanWatch = replanWatch.asked(reason, now, answer.usedGoogle, answer.answered)
        if (!answer.answered) {
            replan = if (reason == Replan.Reason.Asked) ReplanState.Said(ReplanMessage.NoSignal(origin.name)) else ReplanState.Idle
        } else {
            // Judged against the trip as it is now, from that stop, if it's still ahead and you haven't got on the ride there since.
            val found = if (legIndex <= origin.resumeAt && log.boardings[origin.resumeAt] == null) Replan.offer(timeline, origin, answer.journeys) else null
            if (found != null) {
                val offer = found.copy(statusChecked = answer.statusChecked)
                replan = ReplanState.Offered(offer, reason)
                savedOfferKey = null
                alerts.offer(Replan.message(offer, reason, timeline))
            } else {
                if (reason is Replan.Reason.LongWait) checkedWaits = checkedWaits + reason.key
                replan = if (reason == Replan.Reason.Asked) ReplanState.Said(ReplanMessage.NothingBetter(origin.name, timeline.arrival)) else ReplanState.Idle
            }
        }
        presenter.update(card())
        // What was asked counts against the trip's cap after a relaunch too.
        persist()
        bump()
    }

    /** Still takeable: you haven't passed its stop or got on the ride it would replace, and its first ride hasn't left. */
    private fun stillGood(offer: Replan.Offer): Boolean {
        val first = offer.journey.transitLegs.firstOrNull()?.departure ?: offer.journey.departure
        return legIndex <= offer.origin.resumeAt && log.boardings[offer.origin.resumeAt] == null && now <= first.plusSeconds(30.0)
    }

    private fun withdrawStaleOffer() {
        val offer = offer ?: return
        if (stillGood(offer)) return
        replan = ReplanState.Idle
        alerts.offer(null)
    }

    /** Switch: carry on by the new way from its stop. The legs done stay, with the log. */
    fun acceptOffer() {
        if (stopped) return
        val offer = offer
        if (offer == null) {
            // Tapped on the notification of an offer from before the app was stopped: it wasn't kept.
            if (savedOfferKey != null) {
                savedOfferKey = null
                replan = ReplanState.Said(ReplanMessage.OfferGone)
                presenter.update(card())
                persist()
                bump()
            }
            return
        }
        now = clock()
        if (!stillGood(offer)) {
            alerts.offer(null)
            replan = ReplanState.Said(ReplanMessage.OfferGone)
            presenter.update(card())
            bump()
            return
        }
        journey = Replan.splice(timeline, journey, offer)
        replanWatch = replanWatch.forget(offer.origin.resumeAt)
        replan = ReplanState.Idle
        alerts.offer(null)
        checkedWaits = emptySet()
        detector.reset()
        legIndex = min(legIndex, journey.legs.size - 1)
        if (!leg.mode.isTransit) {
            walk = WalkRoute.fallback(leg)
            walkProgress = null
        }
        // The new rides' boards, once each (only the next ride's refreshes after).
        val later = journey.legs.drop(offer.origin.resumeAt).filter { it.mode.isTransit && timetable(it) == null }
        if (later.isNotEmpty()) scope.launch { for (ride in later) loadBoard(ride) }
        retime()
        publish()
    }

    /** Keep my route: the offer goes, and that reason isn't asked again this trip. */
    fun keepRoute() {
        if (stopped) return
        val offered = replan as? ReplanState.Offered
        if (offered == null) {
            // Tapped on the notification of an offer from before the app was stopped: decline its reason.
            savedOfferKey?.let { key ->
                savedOfferKey = null
                replanWatch = replanWatch.declined(key)
                persist()
                bump()
            }
            return
        }
        replanWatch = replanWatch.declined(offered.reason)
        replan = ReplanState.Idle
        alerts.offer(null)
        presenter.update(card())
        persist()
        bump()
    }

    // The trip at a glance

    fun card(): TripCard {
        val leg = leg
        // To the minute, so a walk's moving estimate doesn't push an update every tick.
        val arrival = Instant.ofEpochSecond(Math.floorDiv(eta.epochSecond, 60L) * 60)
        var card = TripCard(
            step = TripCard.Step.WalkToEnd(query.destination.name, null, leg.duration), mode = leg.mode, line = null, lineColor = null,
            countdown = TripCard.Countdown.arrive, countdownTo = arrival, arrival = arrival,
            stepNumber = legIndex + 1, stepCount = journey.legs.size,
        )
        val ride = nextRide
        if (leg.mode.isTransit) {
            card = card.copy(line = Replan.ride(leg), lineColor = leg.lineColor)
            card = if (isWaiting) {
                card.copy(
                    step = TripCard.Step.Waiting(leg.fromName, leg.headsign, platform(leg), leg.mode == LegMode.bus || leg.mode == LegMode.coach),
                    countdown = TripCard.Countdown.leaves, countdownTo = rideDeparture, miss = miss(ifMissed, leg),
                )
            } else {
                card.copy(
                    step = TripCard.Step.Riding(leg.toName, yourDeparture?.expected, leg.stopCount),
                    countdown = TripCard.Countdown.getOff, countdownTo = rideArrival,
                )
            }
        } else if (ride != null) {
            card = card.copy(
                step = TripCard.Step.WalkToRide(ride.fromName, platform(ride), ride.headsign ?: ride.toName, ride.mode == LegMode.bus || ride.mode == LegMode.coach),
                line = Replan.ride(ride), lineColor = ride.lineColor,
                countdown = TripCard.Countdown.leaves, countdownTo = nextRideDeparture ?: ride.departure, miss = miss(ifMissed, ride),
            )
        } else {
            // To the distance as shown (10 m under a kilometre, 100 m above), so a walk's every step doesn't post a new card.
            val shown = remainingWalk?.let { m -> if (m < 1000) Math.round(m / 10) * 10.0 else Math.round(m / 100) * 100.0 }
            card = card.copy(step = TripCard.Step.WalkToEnd(query.destination.name, shown, leg.duration))
        }
        // A faster way, or a long wait for the next ride, says so in place of the detail.
        val offerWords = offerMessage
        if (offerWords != null) {
            card = card.copy(replan = offerWords)
        } else {
            val status = Replan.status(journey, timeline, log, legIndex, ::loadedBoard)
            if (status is Replan.Status.LongWait && status.leg == upcomingRide) card = card.copy(replan = replanStatus)
        }
        return card
    }

    private fun miss(ifMissed: IfMissed?, ride: Leg): TripCard.Miss? {
        ifMissed ?: return null
        val line = ifMissed.next.line
        return TripCard.Miss(
            nextDeparture = ifMissed.next.expected,
            nextLine = if (Journey.normalise(line) == Journey.normalise(ride.line ?: "")) null else line,
            cost = ifMissed.cost,
            costIsFloor = ifMissed.costIsFloor,
            connection = ifMissed.connection?.let { it.departure to Replan.ride(it) },
            losesConnection = ifMissed.losesConnection,
        )
    }

    companion object {
        /** Longer than this between two looks, the trip is caught up as after a relaunch. */
        const val GAP_SECONDS = 60.0
        /** Further than this from a walk's path, a fix isn't placed on the walk. */
        const val OFF_WALK_METRES = 150.0
    }
}
