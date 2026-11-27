package polaris.android.go

import android.content.res.Resources
import polaris.android.R
import polaris.android.ui.Clock
import polaris.engine.Connection
import polaris.engine.DepartureBoard
import polaris.engine.IfMissed
import polaris.engine.Leg
import polaris.engine.LegMode
import polaris.engine.LiveTrip
import polaris.engine.ReplanMessage
import polaris.engine.Strip
import polaris.engine.TripAlert
import polaris.android.ui.shortLineName
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max

/**
 * Go's words, from the app's resources, for the sheet, the banner and the notification alike, so
 * they never disagree (iOS keeps one source in `Replan.Message` for the same reason). Plain
 * functions of [Resources], so the notification uses them without a screen.
 */
class GoWords(private val r: Resources) {
    /** One message in each place it is said. [note] is a second sentence shown on its own line. */
    data class Said(val title: String, val detail: String, val short: String, val compact: String, val note: String? = null)

    fun clock(at: Instant) = Clock.of(at)
    fun line(raw: String) = shortLineName(raw)
    /** A number and its unit never split across lines ("7 min", "1 h 05", "230 m"). */
    private fun keep(text: String) = text.replace(' ', '\u00A0')

    fun minutes(seconds: Double): String {
        val m = Math.round(seconds / 60).toInt()
        return keep(if (m < 60) r.getString(R.string.duration_minutes, m) else r.getString(R.string.duration_hours_minutes, m / 60, m % 60))
    }

    /** "platform 2", or for a bus "stop G". */
    fun standing(leg: Leg, platform: String): String =
        r.getString(if (leg.mode == LegMode.bus || leg.mode == LegMode.coach) R.string.go_stop else R.string.go_platform, platform)

    fun distance(metres: Double): String = keep(
        if (metres < 1000) r.getString(R.string.journey_metres, ((metres / 10).let { Math.round(it).toInt() } * 10))
        else r.getString(R.string.journey_kilometres, "%.1f".format(java.util.Locale.UK, metres / 1000)),
    )

    fun lineOf(leg: Leg) = line(leg.line ?: leg.mode.label)

    // The top row

    fun arrive(trip: LiveTrip) = r.getString(R.string.go_arrive, clock(trip.eta))

    fun headerDetail(trip: LiveTrip): String {
        val left = trip.eta.epochSecond - trip.now.epochSecond
        val time = if (left > 0) r.getString(R.string.go_to_go, minutes(left.toDouble())) else r.getString(R.string.go_due_now)
        val walk = trip.remainingWalk?.takeIf { !trip.leg.mode.isTransit }
        return if (walk != null) r.getString(R.string.go_header_walk, trip.query.destination.name, time, r.getString(R.string.go_walk_left, distance(walk)))
        else r.getString(R.string.go_header, trip.query.destination.name, time)
    }

    // The banner

    /** The banner's big line: the countdown, "Get off at X", the distance, or "Missed it?". Null for a seconds countdown (shown live). */
    fun primary(trip: LiveTrip, now: Instant): String {
        val leg = trip.leg
        return when {
            leg.mode.isTransit && trip.missedRide -> r.getString(R.string.go_missed_it)
            leg.mode.isTransit && trip.isWaiting -> {
                val left = trip.rideDeparture.epochSecond - now.epochSecond
                // In minutes, as the boards say it; the last two minutes count down in seconds.
                if (left >= 120) r.getString(R.string.go_minutes, (left / 60).toString())
                else "%d:%02d".format(java.util.Locale.ROOT, max(0, left) / 60, max(0, left) % 60)
            }
            leg.mode.isTransit -> r.getString(R.string.go_get_off_at, leg.toName)
            trip.guidance != null -> distance(trip.guidance!!.second)
            else -> minutes(leg.duration)
        }
    }

    fun secondary(trip: LiveTrip): String {
        val leg = trip.leg
        val name = lineOf(leg)
        if (leg.mode.isTransit) {
            if (trip.missedRide) return r.getString(R.string.go_still_at, leg.fromName)
            if (trip.isWaiting) {
                val headsign = leg.headsign
                val via = headsign?.lowercase()?.startsWith("via") == true
                val platform = trip.platform(leg)?.let { standing(leg, it) }
                return when {
                    headsign == null && platform == null -> r.getString(R.string.go_leaves, name)
                    headsign == null -> r.getString(R.string.go_leaves_platform, name, platform)
                    platform == null -> r.getString(if (via) R.string.go_leaves_via else R.string.go_leaves_towards, name, headsign)
                    else -> r.getString(if (via) R.string.go_leaves_via_platform else R.string.go_leaves_towards_platform, name, headsign, platform)
                }
            }
            val arrival = trip.rideArrival
            val minutes = max(0L, (arrival.epochSecond - trip.now.epochSecond) / 60)
            val yours = trip.yourDeparture?.expected
            return when {
                yours != null && minutes == 0L -> r.getString(R.string.go_on_the_next_stop, clock(yours))
                yours != null -> r.getString(R.string.go_on_the_in, clock(yours), minutes.toString(), clock(arrival))
                minutes == 0L -> r.getString(R.string.go_next_stop)
                else -> r.getString(R.string.go_in_minutes_at, minutes.toString(), clock(arrival))
            }
        }
        trip.guidance?.first?.let { return it }
        trip.nextRide?.let { ride -> return change(trip, ride) ?: r.getString(R.string.go_walk_to, ride.fromName) }
        return r.getString(R.string.go_walk_to, trip.query.destination.name)
    }

    /** A walk between two rides at one station is a change: "Walk to the Elizabeth line, platform B", not "Walk to Farringdon" while at Farringdon. */
    fun change(trip: LiveTrip, ride: Leg): String? {
        val before = trip.journey.legs.getOrNull(trip.legIndex - 1) ?: return null
        if (!before.mode.isTransit) return null
        val platform = trip.platform(ride)?.let { standing(ride, it) }
        return if (platform != null) r.getString(R.string.go_change_to_platform, lineOf(ride), platform) else r.getString(R.string.go_change_to, lineOf(ride))
    }

    /** What comes after this step; when the ride was missed, the next one instead. */
    fun then(trip: LiveTrip, now: Instant = trip.now): String? {
        val leg = trip.leg
        if (leg.mode.isTransit && trip.missedRide) return missedNext(trip.board(leg), leg, now)
        val ride = trip.nextRide
        val departure = trip.nextRideDeparture
        if (ride != null && departure != null) {
            val platform = trip.platform(ride)?.let { standing(ride, it) }
            return if (platform != null) r.getString(R.string.go_then_ride_at_platform, lineOf(ride), clock(departure), platform)
            else r.getString(R.string.go_then_ride_at, lineOf(ride), clock(departure))
        }
        if (leg.mode.isTransit && !trip.isWaiting && trip.legIndex + 1 < trip.journey.legs.size) {
            val index = trip.legIndex + 1
            val next = trip.journey.legs[index]
            if (!next.mode.isTransit) return r.getString(R.string.go_then_walk, minutes(next.duration))
            val step = trip.timeline.steps[index]
            val platform = trip.platform(next)?.let { standing(next, it) }
            return when {
                step.isEstimate && platform == null -> r.getString(R.string.go_then_ride, lineOf(next))
                step.isEstimate -> r.getString(R.string.go_then_ride_platform, lineOf(next), platform)
                platform == null -> r.getString(R.string.go_then_ride_at, lineOf(next), clock(step.departure))
                else -> r.getString(R.string.go_then_ride_at_platform, lineOf(next), clock(step.departure), platform)
            }
        }
        return null
    }

    // The sheet

    /** "I'm on the H&C": the line of the train the timeline has you catching (another line can be the soonest there). */
    fun step(step: LiveTrip.Step, trip: LiveTrip? = null): String = when (step) {
        is LiveTrip.Step.Boarded -> {
            val index = trip?.journey?.legs?.indexOfFirst { it.id == step.ride.id } ?: -1
            val catching = trip?.timeline?.steps?.getOrNull(index)?.service?.line
            (catching ?: step.ride.line)?.let { r.getString(R.string.go_step_on, line(it)) } ?: r.getString(R.string.go_step_on_it)
        }
        is LiveTrip.Step.GotOff -> r.getString(R.string.go_step_off)
    }

    fun connection(connection: Connection, ride: Leg, departure: Instant, platform: String?): String {
        val train = r.getString(R.string.go_time_line, clock(departure), lineOf(ride))
        val spare = Strip.roundAway(abs(connection.spare) / 60)
        val where = platform?.let { standing(ride, it) }
        return when (connection.verdict) {
            Connection.Verdict.comfortable -> if (where != null) r.getString(R.string.go_spare_platform, spare.toString(), train, where) else r.getString(R.string.go_spare, spare.toString(), train)
            Connection.Verdict.tight -> if (where != null) r.getString(R.string.go_tight_platform, train, where) else r.getString(R.string.go_tight, train)
            Connection.Verdict.missing -> if (where != null) r.getString(R.string.go_missing_platform, train, where) else r.getString(R.string.go_missing, train)
        }
    }

    /** What to do about it: what missing it costs the whole trip, or a sooner one worth running for. */
    fun advice(connection: Connection, ride: Leg, board: DepartureBoard?, ifMissed: IfMissed?, destination: String, now: Instant): String? {
        board ?: return null
        return when (connection.verdict) {
            Connection.Verdict.missing, Connection.Verdict.tight -> {
                if (ifMissed != null) return cost(ifMissed, destination)
                val next = board.nextAfterPlanned ?: return null
                val later = board.arrivalMinutes(next)
                if (later != null) r.getString(R.string.go_next_is_later, clock(next.expected), later.toString(), ride.toName)
                else r.getString(R.string.go_next_is, clock(next.expected))
            }
            Connection.Verdict.comfortable -> {
                val earlier = board.earlierThanPlanned(now) ?: return null
                if (earlier.expected <= connection.ready) return null
                val gain = board.arrivalMinutes(earlier) ?: return null
                if (gain >= 0) return null
                r.getString(R.string.go_could_make, clock(earlier.expected), (-gain).toString(), ride.toName)
            }
        }
    }

    /** What missing it does to the whole trip, not just this ride. */
    fun cost(missed: IfMissed, destination: String): String {
        val next = clock(missed.next.expected)
        val change = missed.connection?.let { r.getString(R.string.go_time_line, clock(it.departure), lineOf(it)) }
        val later = missed.cost?.let { minutes ->
            when {
                missed.costIsFloor -> r.getString(R.string.go_later_at_least, max(minutes, 1).toString(), destination)
                minutes == 0 -> r.getString(R.string.go_later_none, destination)
                minutes > 0 -> r.getString(R.string.go_later, minutes.toString(), destination)
                else -> r.getString(R.string.go_sooner, (-minutes).toString(), destination)
            }
        }
        return when {
            change != null && missed.losesConnection && later != null -> r.getString(R.string.go_cost_loses_later, next, change, later)
            change != null && missed.losesConnection -> r.getString(R.string.go_cost_loses, next, change)
            change != null && (missed.cost ?: 0) == 0 -> r.getString(R.string.go_cost_still_makes, next, change)
            later != null -> r.getString(R.string.go_cost_later, next, later)
            else -> r.getString(R.string.go_next_is, next)
        }
    }

    fun missedNext(board: DepartureBoard?, leg: Leg, now: Instant): String? {
        val next = board?.departures?.firstOrNull { it.expected > now && !it.isCancelled } ?: return null
        val there = next.arrivalThere
        return if (there != null) r.getString(R.string.go_missed_next_reaching, line(next.line), clock(next.expected), leg.toName, clock(there))
        else r.getString(R.string.go_missed_next, line(next.line), clock(next.expected))
    }

    // Re-planning

    fun summary(rides: List<String>, to: String?): String {
        if (rides.isEmpty() || to == null) return r.getString(R.string.go_summary_walk)
        var text = r.getString(R.string.go_summary_first, line(rides.first()))
        for (ride in rides.drop(1)) text = r.getString(R.string.go_summary_then, text, line(ride))
        return r.getString(R.string.go_summary_to, text, to)
    }

    fun said(message: ReplanMessage): Said = when (message) {
        is ReplanMessage.OtherRide -> {
            val time = clock(message.departure)
            val name = line(message.line)
            val arrive = clock(message.arrival)
            if (message.early) Said(
                r.getString(R.string.go_early_title, time, name), r.getString(R.string.go_early_detail, arrive, message.minutes.toString()),
                r.getString(R.string.go_early_short, time, name, arrive), r.getString(R.string.go_early_compact, arrive),
            ) else Said(
                r.getString(R.string.go_late_title, time, name), r.getString(R.string.go_late_detail, arrive, message.minutes.toString()),
                r.getString(R.string.go_late_short, time, name, arrive), r.getString(R.string.go_late_compact, arrive),
            )
        }
        is ReplanMessage.LongWait -> {
            val at = clock(message.leaves)
            val name = line(message.line)
            Said(
                r.getString(R.string.go_wait_title, message.minutes.toString(), name),
                r.getString(if (message.checked) R.string.go_wait_detail_checked else R.string.go_wait_detail, message.stop, at),
                r.getString(R.string.go_wait_short, message.minutes.toString(), at, name),
                r.getString(R.string.go_wait_compact, message.minutes.toString(), at),
            )
        }
        is ReplanMessage.Faster -> {
            val take = summary(message.rides, message.to)
            val arrive = clock(message.arrival)
            val minutes = message.minutes.toString()
            val detail = when (val than = message.than) {
                is ReplanMessage.Than.Waiting -> r.getString(R.string.go_faster_detail_waiting, take, arrive, minutes, than.minutes.toString(), line(than.line))
                is ReplanMessage.Than.ThisWay -> r.getString(R.string.go_faster_detail_this_way, take, arrive, minutes, clock(than.arrival))
                is ReplanMessage.Than.Cancelled -> r.getString(R.string.go_faster_detail_cancelled, take, arrive, minutes, line(than.line))
                ReplanMessage.Than.Nothing -> r.getString(R.string.go_faster_detail, take, arrive, minutes)
            }
            Said(
                r.getString(R.string.go_faster_title, message.origin), detail,
                r.getString(R.string.go_faster_short, minutes, arrive), r.getString(R.string.go_faster_compact, minutes, arrive),
                note = if (message.statusChecked) null else r.getString(R.string.go_faster_unchecked),
            )
        }
        is ReplanMessage.NoSignal -> {
            val title = r.getString(R.string.go_no_signal_title)
            Said(title, r.getString(R.string.go_no_signal_detail, message.origin), title, title)
        }
        is ReplanMessage.NothingBetter -> {
            val arrive = clock(message.arrival)
            Said(
                r.getString(R.string.go_quickest_title), r.getString(R.string.go_quickest_detail, message.origin, arrive),
                r.getString(R.string.go_quickest_short, arrive), r.getString(R.string.go_quickest_compact),
            )
        }
        ReplanMessage.OfferGone -> {
            val title = r.getString(R.string.go_offer_gone_title)
            Said(title, r.getString(R.string.go_offer_gone_detail), title, r.getString(R.string.go_offer_gone_compact))
        }
    }

    /** A timed nudge's title and body (the notification, in the last commit). */
    fun alert(alert: TripAlert): Pair<String, String> = when (val kind = alert.kind) {
        is TripAlert.Kind.GetOff -> r.getString(R.string.go_alert_get_off, kind.stop) to when (val then = kind.then) {
            is TripAlert.Then.Ride -> then.at?.let { r.getString(R.string.go_alert_then_ride_at, line(then.line), clock(it), then.from) }
                ?: r.getString(R.string.go_alert_then_ride, line(then.line), then.from)
            is TripAlert.Then.Walk -> r.getString(R.string.go_alert_then_walk, then.destination)
        }
        is TripAlert.Kind.Leave -> r.getString(R.string.go_alert_leave) to
            r.getString(R.string.go_alert_leave_body, minutes(kind.walkSeconds), kind.from, clock(kind.departure), line(kind.line))
    }
}

/** "platform 2", or for a bus "stop G", from the app's resources (the engine leaves wording to the app). */
@androidx.compose.runtime.Composable
fun standingText(leg: Leg, platform: String): String =
    androidx.compose.ui.res.stringResource(if (leg.mode == LegMode.bus || leg.mode == LegMode.coach) R.string.go_stop else R.string.go_platform, platform)
