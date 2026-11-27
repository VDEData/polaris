package polaris.engine

import java.time.Instant
import kotlin.math.abs

// Ported from Polaris/Services/TripAlerts.swift (the plan, not the delivery) and the Live
// Activity's state in Polaris/Engine/LiveTrip.swift. iOS words both in Swift; on Android each is
// the facts it says, worded by the app.

/** One timed nudge during a trip. */
data class TripAlert(val id: String, val date: Instant, val kind: Kind) {
    sealed interface Kind {
        /** "Get off at [stop] next", then [then]. */
        data class GetOff(val stop: String, val then: Then) : Kind
        /** "Time to leave": walk [walkSeconds] to [from] for the [departure] [line]. */
        data class Leave(val walkSeconds: Double, val from: String, val departure: Instant, val line: String) : Kind
    }

    sealed interface Then {
        /** "Then the [line] at [at] from [from]." ([at] is null past what the boards know.) */
        data class Ride(val line: String, val at: Instant?, val from: String) : Then
        /** "Then it's a short walk to [destination]." */
        data class Walk(val destination: String) : Then
    }
}

/** Where a trip's alerts go: the system's notifications, or a test's list. */
interface TripAlertScheduling {
    /** Makes these the trip's pending alerts, in place of any before. */
    fun schedule(alerts: List<TripAlert>)
    /** Removes every pending trip alert. */
    fun cancel()
    /** Shows a faster way now, with Switch and Keep my route; null takes it back. */
    fun offer(message: ReplanMessage?)

    /** Nowhere: the trip keeps its alerts to itself. */
    object None : TripAlertScheduling {
        override fun schedule(alerts: List<TripAlert>) {}
        override fun cancel() {}
        override fun offer(message: ReplanMessage?) {}
    }
}

/**
 * Timed nudges while a trip is live: when to leave, and when to get off. Timed from the trip's
 * re-timed timeline rather than GPS, because there is no fix underground.
 */
object TripAlerts {
    /**
     * The alerts still to come, from the leg you're on. A new one needs a little notice; one
     * already pending ([pending]) stays until its time, since dropping it moments early would mean
     * it never fires.
     */
    fun plan(timeline: TripTimeline, index: Int, log: TripLog, destination: String, now: Instant, pending: Set<String> = emptySet()): List<TripAlert> {
        val due = { id: String, date: Instant, lead: Double -> date > now.plusSeconds(if (id in pending) 0.0 else lead) }
        val journey = timeline.journey
        val alerts = mutableListOf<TripAlert>()
        for ((offset, leg) in journey.legs.withIndex()) {
            if (offset < index || !leg.mode.isTransit || log.ended[offset] != null) continue
            val nextIndex = journey.legs.indices.drop(offset + 1).firstOrNull { journey.legs[it].mode.isTransit }
            val then: TripAlert.Then = nextIndex?.let { next ->
                val ride = journey.legs[next]
                TripAlert.Then.Ride(Replan.ride(ride), if (timeline.steps[next].isEstimate) null else ride.departure, ride.fromName)
            } ?: TripAlert.Then.Walk(destination)
            // Long enough to get up and to the doors.
            val getOff = leg.arrival.plusSeconds(-90.0)
            if (due("getoff.$offset", getOff, 30.0) && leg.duration > 4 * 60) {
                alerts += TripAlert("getoff.$offset", getOff, TripAlert.Kind.GetOff(leg.toName, then))
            }
        }
        // Started early: say when to set off for the first ride.
        val walk = journey.legs.firstOrNull()
        val ride = journey.legs.firstOrNull { it.mode.isTransit }
        // A minute's notice before you must set off: the walk and the platform time before the train,
        // or the plan's own start if that's sooner.
        val mustLeave = ride?.let { r -> walk?.let { w -> r.departure.plusSeconds(-(w.duration + Connection.platformTime(r.mode))) } }
        val leaveAt = minOf(journey.departure, mustLeave ?: journey.departure).plusSeconds(-60.0)
        if (index == 0 && log.ended[0] == null && walk != null && walk.mode == LegMode.walk && ride != null && due("leave", leaveAt, 2 * 60.0)) {
            alerts += TripAlert("leave", leaveAt, TripAlert.Kind.Leave(walk.duration, ride.fromName, ride.departure, Replan.ride(ride)))
        }
        return alerts
    }

    /** Worth sending again: a different set, or one moved by a minute or more. */
    fun differ(a: List<TripAlert>, b: List<TripAlert>): Boolean {
        if (a.size != b.size) return true
        return a.zip(b).any { (x, y) -> x.id != y.id || x.kind != y.kind || abs(x.date since y.date) >= 60 }
    }

    /** What to hand over: alerts still more than a second away. One due by then has been shown, or is about to be. */
    fun toAdd(alerts: List<TripAlert>, now: Instant): List<TripAlert> = alerts.filter { (it.date since now) > 1 }

    /** Every id a trip's alerts can have, for taking them all back. */
    val allIds: List<String> get() = listOf("leave") + (0 until 40).map { "getoff.$it" }
}

/**
 * The trip at a glance, where iOS shows its Live Activity (on Android, the ongoing notification):
 * what you're doing, the countdown that matters, and what missing the next ride costs. Kept to
 * what changes by the minute, so a walk's moving estimate doesn't update it every tick.
 */
data class TripCard(
    val step: Step,
    val mode: LegMode,
    /** The line on show (the ride you're on, at, or walking to), raw: the app shortens it. */
    val line: String?,
    val lineColor: RGB?,
    val countdown: Countdown,
    val countdownTo: Instant,
    /** To the minute. */
    val arrival: Instant,
    /** 1-based. */
    val stepNumber: Int,
    val stepCount: Int,
    /** In place of the step's own detail: a faster way, or a long wait for the next ride. */
    val replan: ReplanMessage? = null,
    val miss: Miss? = null,
) {
    sealed interface Step {
        /** "[line] from [from]", "towards [headsign] · platform 2". [platform] is as the source gives it ("2", "C"); [bus] says it's a stop. */
        data class Waiting(val from: String, val headsign: String?, val platform: String?, val bus: Boolean = false) : Step
        /** "[line] to [to]", "On the 06:30, get off at [to]" or "[stops] stops, get off at [to]". */
        data class Riding(val to: String, val yours: Instant?, val stops: Int?) : Step
        /** "Walk to [stop]", "[line] from platform 2" or "[line] towards [towards]". */
        data class WalkToRide(val stop: String, val platform: String?, val towards: String, val bus: Boolean = false) : Step
        /** "Walk to [destination]", with the distance left (or the walk's minutes when there's no fix). */
        data class WalkToEnd(val destination: String, val metres: Double?, val minutes: Double) : Step
    }

    @Suppress("EnumEntryName")
    enum class Countdown { leaves, getOff, arrive }

    /** If you miss the ride you're heading for. */
    data class Miss(
        val nextDeparture: Instant,
        /** Its line when it isn't yours. */
        val nextLine: String?,
        val cost: Int?,
        val costIsFloor: Boolean,
        /** "11:52 GWR": the later change it costs you, or the next one you'd still make. */
        val connection: Pair<Instant, String>?,
        val losesConnection: Boolean,
    ) {
        /** Worth running for: it costs you a change, or a lot of time. */
        val isCostly: Boolean get() = losesConnection || costIsFloor || (cost ?: 0) >= 10
    }
}

/** Where the trip at a glance goes: the ongoing notification, or a test's record. */
interface TripPresenter {
    /** Shows the trip. [adopt]: a trip picked up after the app was stopped, whose card may still be up. */
    fun start(destination: String, card: TripCard, adopt: Boolean)
    fun update(card: TripCard)
    fun end()

    object None : TripPresenter {
        override fun start(destination: String, card: TripCard, adopt: Boolean) {}
        override fun update(card: TripCard) {}
        override fun end() {}
    }
}
