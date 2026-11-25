package polaris.engine

import java.time.Instant
import kotlin.math.abs
import kotlin.math.max

// Ported from Polaris/Engine/Replan.swift. Everything here is pure: the trip's timeline and log
// in, a decision out. Asking the planners and showing the offer are Go's job, and only on the
// events named here, never on a timer. iOS words its messages here; on Android the words live in
// the app's resources, so a message is the facts it says ([ReplanMessage]).

object Replan {
    // Thresholds (named so a change of mind changes a number, not the code).

    /** A wait at a change this long is worth a look... */
    const val longWait = 10 * 60.0
    /** ...when it's grown by at least this much over the plan's. */
    const val waitGrowth = 7 * 60.0
    /** Arriving this much later than planned is worth a look. */
    const val lateBy = 8 * 60.0
    /** An earlier or later ride on the same line that moves the arrival by this much is worth saying. */
    const val worthSaying = 2 * 60.0
    /** A new way must get there at least this much sooner... */
    const val minimumGain = 5 * 60.0
    /** ...or this share of the time still to go from where it's asked, if that's more. */
    const val gainShare = 0.15
    /** Each change more than the current route costs this much of the gain. */
    const val changePenalty = 3 * 60.0
    /** Notices at or above this rule a new way out. */
    val ruledOutBy = ServiceNotice.Severity.delays

    /** The trip's re-plans: searches sent, answered or not. */
    const val searchesPerTrip = 3
    /** Searches that got no answer (no signal underground), so a dead spot can't loop. */
    const val failuresPerTrip = 2
    /** Google at most this often a trip, whatever else. */
    const val googlePerTrip = 3
    /** Never two searches closer together than this, whatever the reason. */
    const val spacing = 4 * 60.0

    /** Why a re-plan is worth asking for. [key] stops the same reason asking twice. */
    sealed interface Reason {
        val key: String

        /** Early: the ride at this change now means a long wait. */
        data class LongWait(val leg: Int, val wait: Double) : Reason { override val key get() = "wait.$leg" }
        /** Late at this change: the ride the plan had is gone, and the next costs a lot. */
        data class LostConnection(val leg: Int, val late: Double) : Reason { override val key get() = "late.$leg" }
        /** Still at the stop after the ride you were at left. */
        data class Missed(val leg: Int, val late: Double) : Reason { override val key get() = "late.$leg" }
        /** The ride the plan had at this change is cancelled on its board. */
        data class Cancelled(val leg: Int) : Reason { override val key get() = "cancelled.$leg" }
        /** "Find a faster way", tapped. */
        data object Asked : Reason { override val key get() = "asked" }
    }

    /** The rides still ahead as the timeline has them, and whether that's a guess. */
    fun fingerprint(timeline: TripTimeline, current: Int): List<String> =
        timeline.steps.indices.drop(current).mapNotNull { index ->
            if (!timeline.journey.legs[index].mode.isTransit) return@mapNotNull null
            val step = timeline.steps[index]
            "$index:${step.service?.id ?: "-"}:${step.isEstimate}"
        }

    /** The first reason worth a re-plan, if any. [plan] is the journey as found; [board] gives the boards as loaded. */
    fun reason(plan: Journey, timeline: TripTimeline, log: TripLog, current: Int, missedRide: Boolean, board: (Leg) -> DepartureBoard?): Reason? {
        val late = timeline.arrival since plan.arrival
        if (missedRide && current in plan.legs.indices && plan.legs[current].mode.isTransit && late >= lateBy) return Reason.Missed(current, late)
        for (index in plan.legs.indices.drop(current)) {
            val leg = plan.legs[index]
            if (!leg.mode.isTransit || log.boardings[index] != null) continue
            val step = timeline.steps[index]
            // Cancelled, and the next one costs enough to matter (not a tube every three minutes).
            if (board(leg)?.planned?.isCancelled == true) {
                val wait = knownWait(plan, timeline, index, board(leg)) ?: 0.0
                if (late >= lateBy || wait >= longWait) return Reason.Cancelled(index)
            }
            if (index <= current) continue
            val wait = knownWait(plan, timeline, index, board(leg))
            if (wait != null && wait >= longWait && wait - plannedWait(plan, index) >= waitGrowth) return Reason.LongWait(index, wait)
            if (late >= lateBy && step.departure > leg.departure.plusSeconds(TripTimeline.onPlan)) return Reason.LostConnection(index, late)
        }
        return null
    }

    /**
     * How long you'd stand at this ride's stop, as the timeline has it. Null when that isn't known
     * well enough to act on: a guess past the board, or you'd get there before the board's window starts.
     */
    fun knownWait(plan: Journey, timeline: TripTimeline, index: Int, board: DepartureBoard?): Double? {
        if (index <= 0 || board == null || board.departures.isEmpty()) return null
        val step = timeline.steps[index]
        if (step.isEstimate || step.service == null) return null
        val ready = timeline.steps[index - 1].arrival
        val windowStarts = plan.legs[index].departure.plusSeconds(-DepartureProvider.BOARD_LEAD_SECONDS.toDouble())
        if (ready < windowStarts) return null
        return max(0.0, step.departure since ready)
    }

    fun plannedWait(plan: Journey, index: Int): Double =
        if (index <= 0) 0.0 else max(0.0, plan.legs[index].departure since plan.legs[index - 1].arrival)

    /** What changed about the rest of the trip, worth a line in Go. Null while you're on plan. */
    sealed interface Status {
        /** Early or late, now on another departure of the same line at this change. */
        data class OtherRide(val leg: Int, val service: Departure, val by: Double) : Status
        /** Early, and the ride at this change now means this long a wait. */
        data class LongWait(val leg: Int, val wait: Double) : Status
    }

    fun status(plan: Journey, timeline: TripTimeline, log: TripLog, current: Int, board: (Leg) -> DepartureBoard?): Status? {
        var other: Status? = null
        for (index in plan.legs.indices.drop(current + 1)) {
            val leg = plan.legs[index]
            if (!leg.mode.isTransit || log.boardings[index] != null) continue
            val wait = knownWait(plan, timeline, index, board(leg))
            if (wait != null && wait >= longWait && wait - plannedWait(plan, index) >= waitGrowth) return Status.LongWait(index, wait)
            val service = timeline.steps[index].service
            if (service != null && service.id != board(leg)?.plannedId) {
                val by = timeline.arrival since plan.arrival
                // The last ride that moved is the one that sets the arrival.
                if (abs(by) >= worthSaying) other = Status.OtherRide(index, service, by)
            }
        }
        return other
    }

    /** Where a re-plan asks from: the next stop you'll be at, at the timeline's time there. */
    data class Origin(
        /** The first leg a new way would replace; the legs before it stay, with their log. */
        val resumeAt: Int,
        val name: String,
        val coordinate: Coordinate,
        val time: Instant,
    ) {
        fun query(destination: Place) = JourneyQuery(Place(name = name, coordinate = coordinate), destination, TimeMode.DepartAt(time))
    }

    fun origin(timeline: TripTimeline, current: Int, boarded: Boolean, now: Instant): Origin? {
        val legs = timeline.journey.legs
        if (current !in legs.indices) return null
        val leg = legs[current]
        val next = legs.getOrNull(current + 1)
        val origin: Origin? = when {
            leg.mode.isTransit && boarded -> leg.end?.let { Origin(current + 1, leg.toName, it, timeline.steps[current].arrival) }
            leg.mode.isTransit -> leg.start?.let { Origin(current, leg.fromName, it, now) }
            next != null && next.mode.isTransit -> next.start?.let { Origin(current + 1, next.fromName, it, maxOf(now, timeline.steps[current].arrival)) }
            else -> null
        }
        // Nothing but a walk left from there: nothing to re-plan.
        if (origin == null || legs.drop(origin.resumeAt).none { it.mode.isTransit }) return null
        return origin
    }

    data class Offer(
        /** The new way from [origin], as the planner gave it. */
        val journey: Journey,
        val origin: Origin,
        /** Seconds sooner than the trip as it's going. */
        val gain: Double,
        /** TfL's network status was had and checked against it. */
        val statusChecked: Boolean = true,
    )

    /** The best of [candidates] if it beats the trip as it's going by a clear margin; null otherwise. */
    fun offer(timeline: TripTimeline, origin: Origin, candidates: List<Journey>): Offer? {
        val rest = timeline.journey.legs.drop(origin.resumeAt)
        val restChanges = max(0, rest.count { it.mode.isTransit } - 1)
        val restSignature = Journey(departure = origin.time, arrival = timeline.arrival, legs = rest, sources = emptyList()).routeSignature
        val toGo = timeline.arrival since origin.time
        val needed = max(minimumGain, gainShare * toGo)
        data class Scored(val journey: Journey, val sooner: Double, val score: Double)
        val scored = candidates.mapNotNull { candidate ->
            // One you can catch from there, then.
            if (candidate.departure < origin.time.plusSeconds(-60.0)) return@mapNotNull null
            // The same lines arriving sooner is the timeline's job, not an offer.
            if (candidate.routeSignature == restSignature) return@mapNotNull null
            if (candidate.notices.any { it.severity >= ruledOutBy } || candidate.hasDisruption) return@mapNotNull null
            val sooner = timeline.arrival since candidate.arrival
            val score = sooner - changePenalty * max(0, candidate.changes - restChanges)
            if (score >= needed) Scored(candidate, sooner, score) else null
        }
        val best = scored.maxWithOrNull(compareBy<Scored>({ it.score }, { -it.journey.changes })) ?: return null
        return Offer(best.journey, origin, best.sooner)
    }

    /** The trip with the offer taken: the legs before its stop as they went (same ids), then the new way. Keeps the journey's id. */
    fun splice(timeline: TripTimeline, plan: Journey, offer: Offer): Journey {
        val legs = timeline.journey.legs.take(offer.origin.resumeAt) + offer.journey.legs
        val sources = plan.sources.toMutableList()
        for (tag in offer.journey.sources) if (tag !in sources) sources += tag
        return plan.copy(
            legs = legs,
            departure = legs.firstOrNull()?.departure ?: offer.journey.departure,
            arrival = offer.journey.arrival,
            sources = sources,
            notices = offer.journey.notices,
            fare = null,
            hasDisruption = offer.journey.hasDisruption,
        )
    }

    /** What this trip has asked, so nothing asks twice for the same reason and a dead spot can't loop. */
    data class Watch(
        val searches: Int = 0,
        val failures: Int = 0,
        val googleRequests: Int = 0,
        val settled: Set<String> = emptySet(),
        val lastAsked: Instant? = null,
    ) {
        val sends: Int get() = searches + failures
        val mayUseGoogle: Boolean get() = googleRequests < googlePerTrip

        /**
         * Whether to ask now for this reason. "Find a faster way" skips the same-reason rule and
         * the failure cap but not the trip's cap or the spacing. A clock stepped backwards counts
         * as past the spacing, never as hours of waiting.
         */
        fun mayAsk(reason: Reason, now: Instant): Boolean {
            if (sends >= searchesPerTrip || (reason != Reason.Asked && failures >= failuresPerTrip)) return false
            lastAsked?.let { val gone = now since it; if (gone >= 0 && gone < spacing) return false }
            return reason == Reason.Asked || reason.key !in settled
        }

        /** A search went out. With no answer the reason stays open, to try once more at the next event. */
        fun asked(reason: Reason, now: Instant, usedGoogle: Boolean, answered: Boolean) = copy(
            lastAsked = now,
            googleRequests = googleRequests + if (usedGoogle) 1 else 0,
            searches = searches + if (answered) 1 else 0,
            failures = failures + if (answered) 0 else 1,
            settled = if (answered) settled + reason.key else settled,
        )

        /** After a Switch the legs from [index] are new ones: what was settled about the old legs there no longer means anything. */
        fun forget(index: Int) = copy(settled = settled.filter { key -> key.split(".").last().toIntOrNull()?.let { it < index } ?: true }.toSet())

        /** "Keep my route": don't ask again for this. */
        fun declined(reason: Reason) = declined(reason.key)
        fun declined(key: String) = copy(settled = settled + key)
    }

    /** Which sources a re-plan asks: TfL and Transitous once each, and Google once only while the month's allowance has room. */
    fun sources(enabled: Set<ProviderID>, hasGoogleKey: Boolean, allowance: GoogleAllowance, watch: Watch = Watch(), now: Instant): List<ProviderID> {
        val sources = listOf(ProviderID.tfl, ProviderID.transitous).filter { it in enabled }.toMutableList()
        if (ProviderID.google in enabled && hasGoogleKey && watch.mayUseGoogle && allowance.canSpend(1, now)) sources.add(0, ProviderID.google)
        return sources
    }

    /** A ride's line as Go names it (the app shortens it as its pills do). */
    fun ride(leg: Leg): String = leg.line ?: leg.mode.label

    /** The timeline's own change, as facts for the app to word. */
    fun message(status: Status, timeline: TripTimeline, checked: Boolean): ReplanMessage {
        val legs = timeline.journey.legs
        return when (status) {
            is Status.OtherRide -> ReplanMessage.OtherRide(
                early = status.by < 0, departure = status.service.expected, line = ride(legs[status.leg]),
                arrival = timeline.arrival, minutes = Strip.roundAway(abs(status.by) / 60),
            )
            is Status.LongWait -> ReplanMessage.LongWait(
                minutes = Strip.roundAway(status.wait / 60), line = ride(legs[status.leg]), stop = legs[status.leg].fromName,
                leaves = timeline.steps[status.leg].departure, checked = checked,
            )
        }
    }

    /** An offer, with why it came up. */
    fun message(offer: Offer, reason: Reason, timeline: TripTimeline): ReplanMessage.Faster {
        val than = when (reason) {
            is Reason.LongWait -> ReplanMessage.Than.Waiting(Strip.roundAway(reason.wait / 60), ride(timeline.journey.legs[reason.leg]))
            is Reason.LostConnection, is Reason.Missed -> ReplanMessage.Than.ThisWay(timeline.arrival)
            is Reason.Cancelled -> ReplanMessage.Than.Cancelled(ride(timeline.journey.legs[reason.leg]))
            Reason.Asked -> ReplanMessage.Than.Nothing
        }
        val rides = offer.journey.transitLegs
        return ReplanMessage.Faster(
            origin = offer.origin.name, rides = rides.map(::ride), to = rides.lastOrNull()?.toName,
            arrival = offer.journey.arrival, minutes = Strip.roundAway(offer.gain / 60), than = than, statusChecked = offer.statusChecked,
        )
    }
}

/**
 * What Go says about re-planning, as the facts the words need. One source for the sheet, the
 * banner and the notification, so they never disagree; the app words each in its resources.
 */
sealed interface ReplanMessage {
    /** "You're early: the 06:43 Jubilee" / "Running late: …", arriving [minutes] sooner or later. */
    data class OtherRide(val early: Boolean, val departure: Instant, val line: String, val arrival: Instant, val minutes: Int) : ReplanMessage
    /** "You're early: 19 min wait for the 277". [checked]: a search found nothing quicker from there. */
    data class LongWait(val minutes: Int, val line: String, val stop: String, val leaves: Instant, val checked: Boolean) : ReplanMessage
    /** "Faster way from Baker Street": take [rides] to [to], arrive [arrival], [minutes] sooner. */
    data class Faster(
        val origin: String,
        val rides: List<String>,
        val to: String?,
        val arrival: Instant,
        val minutes: Int,
        val than: Than,
        val statusChecked: Boolean,
    ) : ReplanMessage
    /** Asked by hand with no signal: nothing is sent; Go asks again at [origin]. */
    data class NoSignal(val origin: String) : ReplanMessage
    /** Asked by hand and nothing beat the trip as it's going. */
    data class NothingBetter(val origin: String, val arrival: Instant) : ReplanMessage
    /** A faster way tapped once it can't be taken any more. */
    data object OfferGone : ReplanMessage

    /** What the faster way is faster than. */
    sealed interface Than {
        data class Waiting(val minutes: Int, val line: String) : Than
        data class ThisWay(val arrival: Instant) : Than
        data class Cancelled(val line: String) : Than
        data object Nothing : Than
    }
}

/** What a re-plan's search came back with. */
data class ReplanAnswer(
    val journeys: List<Journey>,
    /** At least one source replied. */
    val answered: Boolean,
    val usedGoogle: Boolean,
    /** TfL's network status was had, so the journeys carry its notices. */
    val statusChecked: Boolean = true,
)
