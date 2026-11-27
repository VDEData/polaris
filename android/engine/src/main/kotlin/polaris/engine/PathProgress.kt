package polaris.engine

import java.time.Instant
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Ported from Polaris/Engine/PathProgress.swift: where you are along a path, how a walk lines up
// with the ride at its end, which leg you're on, what missing a ride costs, and the trip re-timed
// from what you've done. Names and rules follow the Swift.

/** Seconds from [other] to this instant (negative when this is earlier), as Swift's `timeIntervalSince`. */
internal infix fun Instant.since(other: Instant): Double = seconds - other.seconds

/** Metres between two points on the Earth (haversine), standing in for Core Location's distance. */
fun Coordinate.distance(to: Coordinate): Double {
    val r = 6_371_008.8
    val p1 = Math.toRadians(latitude)
    val p2 = Math.toRadians(to.latitude)
    val dp = p2 - p1
    val dl = Math.toRadians(to.longitude - longitude)
    val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * r * asin(sqrt(min(1.0, h)))
}

/**
 * Where someone is along a path: how far they've come, how far is left, and how far they've
 * strayed from it. Flat-earth maths, fine over a walk.
 */
data class PathProgress(val distanceFromPath: Double, val distanceAlong: Double, val remaining: Double) {
    companion object {
        fun locate(point: Coordinate, path: List<Coordinate>): PathProgress? {
            val first = path.firstOrNull() ?: return null
            if (path.size < 2) return PathProgress(point.distance(first), 0.0, 0.0)
            val xy = path.map { metres(point, it) }
            var bestDistance = Double.POSITIVE_INFINITY
            var bestAlong = 0.0
            var travelled = 0.0
            for (i in 1 until xy.size) {
                val (ax, ay) = xy[i - 1]
                val (bx, by) = xy[i]
                val dx = bx - ax
                val dy = by - ay
                val length = sqrt(dx * dx + dy * dy)
                // The point itself sits at the origin.
                val t = if (length > 0) max(0.0, min(1.0, -(ax * dx + ay * dy) / (length * length))) else 0.0
                val px = ax + t * dx
                val py = ay + t * dy
                val distance = sqrt(px * px + py * py)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestAlong = travelled + t * length
                }
                travelled += length
            }
            return PathProgress(bestDistance, bestAlong, max(0.0, travelled - bestAlong))
        }

        /** The point [metres] along a path, clamped to its ends. */
        fun point(metres: Double, path: List<Coordinate>): Coordinate? {
            val first = path.firstOrNull() ?: return null
            var left = max(0.0, metres)
            for (i in 1 until path.size) {
                val a = path[i - 1]
                val b = path[i]
                val piece = length(listOf(a, b))
                if (piece > 0 && left <= piece) {
                    val t = left / piece
                    return Coordinate(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
                }
                left -= piece
            }
            return path.lastOrNull() ?: first
        }

        fun length(path: List<Coordinate>): Double {
            val first = path.firstOrNull() ?: return 0.0
            var total = 0.0
            for (i in 1 until path.size) {
                val (ax, ay) = metres(first, path[i - 1])
                val (bx, by) = metres(first, path[i])
                total += sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
            }
            return total
        }

        /** Equirectangular metres around an origin. */
        private fun metres(origin: Coordinate, c: Coordinate): Pair<Double, Double> {
            val k = cos(origin.latitude * Math.PI / 180)
            return Pair((c.longitude - origin.longitude) * 111_320 * k, (c.latitude - origin.latitude) * 110_540)
        }
    }
}

/**
 * A walking route. iOS gets turn-by-turn steps from MapKit; Android has no walking router, so a
 * walk is the source's own path with no steps (iOS's fallback when MapKit can't help).
 */
data class WalkRoute(val path: List<Coordinate>, val steps: List<Step>, val distance: Double, val expectedTime: Double) {
    data class Step(val instruction: String, val distance: Double)

    /** What to do next from a point along the route, and how far ahead it is. */
    fun guidance(along: Double): Pair<String?, Double> {
        val total = steps.sumOf { it.distance }
        if (total <= 0 || steps.isEmpty()) return Pair(null, max(0.0, distance - along))
        val scale = distance / total
        var end = 0.0
        for ((index, step) in steps.withIndex()) {
            end += step.distance * scale
            if (along < end - 5) {
                val next = steps.drop(index + 1).firstOrNull { it.instruction.isNotEmpty() }
                return Pair(next?.instruction, max(0.0, end - along))
            }
        }
        return Pair(null, max(0.0, distance - along))
    }

    companion object {
        /** A source's own walking path (iOS's `WalkRouter.fallback`). */
        fun fallback(leg: Leg): WalkRoute? {
            if (leg.path.size < 2) return null
            val distance = leg.distanceMeters ?: PathProgress.length(leg.path)
            return WalkRoute(leg.path, emptyList(), distance, leg.duration)
        }
    }
}

/** How a walk lines up with the vehicle at the end of it. */
data class Connection(
    /** When you'd be on the platform (or at the stop). */
    val ready: Instant,
    /** Seconds before it leaves; negative if you'd miss it. */
    val spare: Double,
) {
    @Suppress("EnumEntryName")
    enum class Verdict { comfortable, tight, missing }

    val verdict: Verdict get() = if (spare >= 120) Verdict.comfortable else if (spare >= 0) Verdict.tight else Verdict.missing

    companion object {
        /** Ordinary walking pace; a measured one replaces it while moving. */
        const val walkingSpeed = 1.35

        /** Time from the station door to the platform, which walking routes stop short of. */
        fun platformTime(mode: LegMode): Double = when (mode) {
            LegMode.bus, LegMode.coach, LegMode.tram -> 30.0
            LegMode.tube, LegMode.elizabeth, LegMode.dlr, LegMode.overground, LegMode.rail -> 90.0
            else -> 60.0
        }

        fun make(remaining: Double, speed: Double?, now: Instant, departure: Instant, mode: LegMode): Connection {
            val pace = speed?.let { min(max(it, 0.8), 2.5) } ?: walkingSpeed
            val ready = now.plusSeconds(remaining / pace + platformTime(mode))
            return Connection(ready, departure since ready)
        }
    }
}

/** Which leg of a journey you're on, worked out from the clock and where you are. */
object TripProgress {
    /** Within this of a walk's end, you've arrived. */
    const val arrivedDistance = 35.0
    /** Within this of where a ride gets off, you're there, whatever the clock says. */
    const val alightedDistance = 60.0

    /** Where to start when a trip begins: the first leg not yet over. */
    fun initialLeg(journey: Journey, now: Instant): Int =
        journey.legs.indexOfFirst { it.arrival > now }.takeIf { it >= 0 } ?: max(0, journey.legs.size - 1)

    /**
     * Moves on past legs that are done. A walk is done when you reach its end; a ride when you're
     * at its stop off, or when it's due in unless you're still standing where it left from
     * (underground there's no fix, so time decides).
     */
    fun advance(
        journey: Journey,
        start: Int,
        now: Instant,
        location: Coordinate?,
        rideArrival: Instant? = null,
        arrivals: List<Instant>? = null,
    ): Int {
        var index = start
        while (index < journey.legs.size - 1) {
            val leg = journey.legs[index]
            if (leg.mode.isTransit) {
                val atStart = location?.let { here -> leg.start?.let { here.distance(it) < 150 } } ?: false
                val end = leg.end
                if (!atStart && location != null && end != null && location.distance(end) < alightedDistance) {
                    index += 1
                    continue
                }
                val due = (if (index == start) rideArrival else null) ?: arrivals?.getOrNull(index) ?: leg.arrival
                if (now < due.plusSeconds(-30.0) || atStart) break
            } else {
                val end = leg.end
                if (location == null || end == null || location.distance(end) >= arrivedDistance) break
            }
            index += 1
        }
        return index
    }

    /** Still at the stop after the ride should have left ([departure], where it's known live): you missed it. */
    fun missed(leg: Leg, now: Instant, location: Coordinate?, departure: Instant? = null): Boolean {
        if (!leg.mode.isTransit || now <= (departure ?: leg.departure).plusSeconds(60.0)) return false
        val start = leg.start ?: return false
        location ?: return false
        return location.distance(start) < 150
    }
}

/**
 * Notices you're already on a ride, say an earlier bus than planned, or Go started once aboard:
 * on its line, past where you'd get on, and moving along it faster than anyone walks.
 */
class BoardingDetector {
    private data class Sample(val at: Instant, val leg: Int, val along: Double)
    private val samples = mutableListOf<Sample>()

    fun ride(journey: Journey, first: Int, location: Coordinate?, now: Instant): Int? {
        samples.removeAll { now since it.at > 120 }
        if (location == null || first >= journey.legs.size) return null
        for (index in first until journey.legs.size) {
            val leg = journey.legs[index]
            if (!leg.mode.isTransit || leg.path.size < 2) continue
            val progress = PathProgress.locate(location, leg.path) ?: continue
            if (progress.distanceFromPath > onLineDistance || progress.distanceAlong < pastStart || progress.remaining <= 30) continue
            // Going its way, at a ride's pace, over the last little while.
            val riding = samples.any { sample ->
                val seconds = now since sample.at
                val gained = progress.distanceAlong - sample.along
                sample.leg == index && seconds >= 6 && gained >= minimumProgress && gained / seconds >= ridingSpeed
            }
            samples += Sample(now, index, progress.distanceAlong)
            if (riding) return index
        }
        return null
    }

    fun reset() = samples.clear()

    companion object {
        const val onLineDistance = 50.0
        const val pastStart = 100.0
        const val ridingSpeed = 3.5
        const val minimumProgress = 50.0
    }
}

/**
 * What missing a ride does to the whole journey: the next one instead, then each later change,
 * made or missed in turn.
 */
data class IfMissed(
    /** What you'd catch instead. */
    val next: Departure,
    /** Minutes later at the destination than if you make it; null when a missed change has no board to say what comes after it. */
    val cost: Int? = null,
    /** Nothing on a missed change's board leaves late enough: at least this late. */
    val costIsFloor: Boolean = false,
    /** The first later change you'd lose, or else the next one you'd still make. */
    val connection: Leg? = null,
    val losesConnection: Boolean = false,
) {
    private class Path {
        val changes = mutableListOf<Pair<Leg, Boolean>>()
        var arrival: Instant? = null
        var isFloor = false
    }

    companion object {
        /** Extra time to change when the next ride is late, on top of any walk between. */
        const val changeMargin = 60.0

        fun make(journey: Journey, ride: Int, board: (Leg) -> DepartureBoard?): IfMissed? {
            val leg = journey.legs[ride]
            val rideBoard = board(leg) ?: return null
            val planned = rideBoard.planned ?: return null
            val next = rideBoard.nextAfterPlanned ?: return null
            val lateBy = rideBoard.arrivalDifference(next) ?: (next.expected since planned.expected)
            val offset = planned.expected since leg.departure
            val made = follow(journey, ride, leg.arrival.plusSeconds(offset), board, 0.0)
            val missed = follow(journey, ride, leg.arrival.plusSeconds(offset + lateBy), board, changeMargin)

            var connection: Leg? = null
            var loses = false
            val lost = made.changes.zip(missed.changes).firstOrNull { (a, b) -> a.second && !b.second }
            if (lost != null) {
                connection = lost.first.first
                loses = true
            } else {
                missed.changes.firstOrNull()?.takeIf { it.second }?.let { connection = it.first }
            }
            var cost: Int? = null
            var floor = false
            val late = missed.arrival
            val onTime = made.arrival
            if (late != null && onTime != null) {
                cost = Strip.roundAway((late since onTime) / 60)
                floor = missed.isFloor
            }
            return IfMissed(next, cost, floor, connection, loses)
        }

        /** Travels the legs after [index], reaching the end of that ride at [start]. */
        private fun follow(journey: Journey, index: Int, start: Instant, board: (Leg) -> DepartureBoard?, margin: Double): Path {
            val path = Path()
            var at = start
            for (leg in journey.legs.drop(index + 1)) {
                if (!leg.mode.isTransit) {
                    at = at.plusSeconds(leg.duration)
                    continue
                }
                val known = board(leg)
                val delay = known?.planned?.let { it.expected since leg.departure } ?: 0.0
                if (leg.departure.plusSeconds(delay) >= at.plusSeconds(margin)) {
                    path.changes += leg to true
                    at = leg.arrival.plusSeconds(delay)
                    continue
                }
                path.changes += leg to false
                known ?: return path
                val running = known.departures.filter { !it.isCancelled }
                val ready = at.plusSeconds(margin)
                val there = { d: Departure -> d.arrivalThere ?: d.expected.plusSeconds(leg.duration) }
                val catchable = running.filter { it.expected >= ready }.minByOrNull { there(it) }
                if (catchable != null) {
                    at = there(catchable)
                } else {
                    // Nothing on the board leaves late enough, so the one that does leaves after the board ends.
                    val boardEnds = leg.departure.plusSeconds((DepartureProvider.BOARD_SPAN_SECONDS - DepartureProvider.BOARD_LEAD_SECONDS).toDouble())
                    at = maxOf(ready, boardEnds).plusSeconds(leg.duration)
                    path.isFloor = true
                }
            }
            path.arrival = at
            return path
        }
    }
}

/**
 * What has really happened on a trip so far: which departure you got on, and when you finished
 * each leg. Go writes it as you go; [TripTimeline] re-times the rest from it.
 */
data class TripLog(
    /** When Go started. */
    val began: Instant,
    /** By leg index. Stamped once, when you say so or Go sees you riding. */
    val boardings: Map<Int, Boarding> = emptyMap(),
    /** When each leg was finished: off a ride, or at a walk's end. */
    val ended: Map<Int, Instant> = emptyMap(),
) {
    data class Boarding(val at: Instant, val departure: Departure? = null)

    /** Jumped back to a leg by hand: forget what happened from there on. */
    fun rewound(index: Int) = copy(boardings = boardings.filterKeys { it < index }, ended = ended.filterKeys { it < index })

    companion object {
        /** Further than this from any departure on the board, the moment itself stands in. */
        const val snapWindow = 5 * 60.0

        /** The departure you got on at [at]: the one nearest that moment, one just left counting as nearer than one to come. */
        fun departure(at: Instant, board: DepartureBoard?): Departure? {
            board ?: return null
            val distance = { d: Departure -> val gap = d.expected since at; if (gap > 0) gap * 1.5 else -gap }
            val nearest = board.departures.filter { !it.isCancelled }.minByOrNull(distance) ?: return null
            return if (distance(nearest) <= snapWindow) nearest else null
        }
    }
}

/**
 * The journey as it's really going: every leg re-timed from what you've done ([TripLog]) and what
 * the departure boards already held say, through to the arrival.
 */
data class TripTimeline(
    /** The plan with each leg's times replaced by the re-timed ones (same legs, same ids). */
    val journey: Journey,
    /** One per leg. */
    val steps: List<Step>,
) {
    data class Step(
        val departure: Instant,
        val arrival: Instant,
        /** For a ride: the departure off its board you're on, or the one you'll now catch. */
        val service: Departure? = null,
        /** Past what the boards know (or with none): a guess at the soonest it could be. */
        val isEstimate: Boolean = false,
    )

    /** When you'll get there now. */
    val arrival: Instant get() = journey.arrival

    companion object {
        /** Time to change trains, where the plan leaves at least that much. */
        const val changeMargin = 60.0
        /** Within this of the plan, you're on it: keep the planned departure. */
        const val onPlan = 60.0

        /** The timeline before anything has happened. */
        fun plan(journey: Journey) = TripTimeline(journey, journey.legs.map { Step(it.departure, it.arrival) })

        fun make(
            plan: Journey,
            log: TripLog,
            current: Int,
            now: Instant,
            seenAtStop: Instant? = null,
            liveArrival: Instant? = null,
            walkLeft: Double? = null,
            board: (Leg) -> DepartureBoard?,
        ): TripTimeline {
            val steps = mutableListOf<Step>()
            var ready: Instant? = null
            for ((index, leg) in plan.legs.withIndex()) {
                val step: Step
                if (leg.mode.isTransit) {
                    // Walking to it with a fix: counted from the walk's end plus the platform time, as
                    // the will-you-make-it check counts it, so the two never disagree about a train.
                    val platform = if (walkLeft != null && index == current + 1 && !plan.legs[current].mode.isTransit) Connection.platformTime(leg.mode) else null
                    step = ride(plan, index, log, current, now, ready, seenAtStop, if (index == current) liveArrival else null, board, platform)
                    // Still aboard after it was due: the rest waits on it.
                    ready = if (index == current && log.boardings[index] != null && log.ended[index] == null) maxOf(step.arrival, now) else step.arrival
                } else {
                    val start = ready ?: if (index == current) maxOf(leg.departure, log.began) else leg.departure
                    var end = log.ended[index] ?: start.plusSeconds(leg.duration)
                    // A fix showing more walking left than the plan allows moves it on. With no fix it keeps its planned time.
                    if (log.ended[index] == null && index == current && walkLeft != null) end = maxOf(end, now.plusSeconds(walkLeft))
                    step = Step(minOf(start, end), end)
                    ready = end
                }
                steps += step
            }
            // A leg already done can't have ended after the ride that followed it left: it ended by then.
            for (index in 1 until steps.size) {
                if (index - 1 < current && steps[index - 1].arrival > steps[index].departure) {
                    val before = steps[index - 1]
                    steps[index - 1] = before.copy(arrival = steps[index].departure, departure = minOf(before.departure, steps[index].departure))
                }
            }
            val legs = plan.legs.mapIndexed { i, leg -> leg.copy(departure = steps[i].departure, arrival = steps[i].arrival) }
            val journey = if (steps.isEmpty()) plan.copy(legs = legs) else plan.copy(legs = legs, departure = steps.first().departure, arrival = steps.last().arrival)
            return TripTimeline(journey, steps)
        }

        private fun ride(
            plan: Journey,
            index: Int,
            log: TripLog,
            current: Int,
            now: Instant,
            ready: Instant?,
            seenAtStop: Instant?,
            liveArrival: Instant?,
            board: (Leg) -> DepartureBoard?,
            platform: Double? = null,
        ): Step {
            val leg = plan.legs[index]
            val ended = log.ended[index]
            // On it: from the departure you got on, or the moment you did.
            log.boardings[index]?.let { boarding ->
                val departure = boarding.departure?.expected ?: boarding.at
                val arrival = ended ?: liveArrival ?: boarding.departure?.arrivalThere ?: departure.plusSeconds(leg.duration)
                return Step(departure, arrival, boarding.departure)
            }

            val known = board(leg)
            val running = known?.departures?.filter { !it.isCancelled } ?: emptyList()
            val planned = known?.planned?.takeUnless { it.isCancelled }
            val timed = { d: Departure -> Step(d.expected, d.arrivalThere ?: d.expected.plusSeconds(leg.duration), d) }
            val asPlanned = planned?.let(timed) ?: Step(leg.departure, leg.arrival)
            // Whatever leaves from `from` and gets there first.
            val soonest = { from: Instant ->
                running.filter { it.expected >= from }
                    .minWithOrNull(compareBy<Departure>({ it.arrivalThere ?: it.expected }, { it.expected }))
                    ?.let(timed)
            }
            // Nothing known leaves late enough: at least as late as the board reaches.
            val beyond = { from: Instant ->
                val boardEnds = if (known == null) from else leg.departure.plusSeconds((DepartureProvider.BOARD_SPAN_SECONDS - DepartureProvider.BOARD_LEAD_SECONDS).toDouble())
                val departure = maxOf(from, boardEnds)
                Step(departure, departure.plusSeconds(leg.duration), isEstimate = true)
            }
            val plannedCancelled = known?.plannedId != null && planned == null

            var step: Step
            if (index < current) {
                // Passed without a word: as planned.
                step = asPlanned
            } else if (index == current || (index == current + 1 && current == 0 && !plan.legs[current].mode.isTransit)) {
                val reach = maxOf(now, ready?.plusSeconds(platform ?: 0.0) ?: now)
                step = when {
                    index == current && seenAtStop != null && seenAtStop > asPlanned.departure.plusSeconds(60.0) ->
                        soonest(seenAtStop.plusSeconds(-30.0)) ?: beyond(seenAtStop)
                    index > current && now > asPlanned.departure.plusSeconds(60.0) -> soonest(reach) ?: beyond(reach)
                    plannedCancelled -> soonest(reach) ?: beyond(reach)
                    else -> asPlanned
                }
            } else {
                // A later ride: when you'd reach it against when the plan did.
                val at = ready ?: leg.departure
                val plannedAt = if (index > 0) plan.legs[index - 1].arrival else leg.departure
                val margin = platform ?: min(changeMargin, max(0.0, leg.departure since plannedAt))
                val from = at.plusSeconds(margin)
                val shift = at since plannedAt
                step = when {
                    abs(shift) < onPlan && !plannedCancelled -> asPlanned
                    // Early: whatever you can now make that gets there first.
                    shift < 0 -> soonest(from) ?: asPlanned
                    asPlanned.departure >= from && !plannedCancelled -> asPlanned
                    else -> soonest(from) ?: beyond(from)
                }
            }
            if (ended != null) step = step.copy(arrival = ended, departure = minOf(step.departure, ended))
            return step
        }
    }
}
