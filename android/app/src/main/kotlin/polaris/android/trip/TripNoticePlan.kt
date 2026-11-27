package polaris.android.trip

import polaris.engine.ReplanMessage
import polaris.engine.SavedTrip
import polaris.engine.TripCard
import java.time.Duration
import java.time.Instant

/** The notification's decisions, apart from its words, so they are tested on the JVM. */
object TripNoticePlan {
    data class Plan(val offerActions: Boolean, val endAction: Boolean, val timeoutMillis: Long)

    /**
     * Switch and Keep my route only with a faster way, and then no End beside them (End isn't one
     * tap from Switch). The notification times out when the trip would be stale (half an hour
     * past its arrival), so with the process gone it doesn't sit counting below zero.
     */
    fun of(card: TripCard, now: Instant): Plan {
        val offer = card.replan is ReplanMessage.Faster
        val stale = card.arrival.plusSeconds(SavedTrip.STALE_AFTER.toLong())
        return Plan(offer, !offer, Duration.between(now, stale).toMillis().coerceAtLeast(60_000))
    }

    /** Post again only when what's shown changes (the card is already to the minute and to the distance shown). */
    fun changed(shown: TripCard?, card: TripCard): Boolean = shown != card

    /**
     * When to ask for a nudge's alarm. Android delivers an inexact alarm anywhere in a window of
     * 75% of the time to it, after the time asked, so asking at the time itself could bring it
     * many minutes late. Asked this early the window ends at the nudge; delivered early, it is asked
     * again for what is left. Null within a minute of it: post it.
     */
    fun alarmAsk(now: Instant, due: Instant): Instant? {
        val left = Duration.between(now, due).toMillis()
        if (left <= 60_000) return null
        return now.plusMillis((left / 1.75).toLong())
    }

    /** Each nudge's own alarm, so a re-plan replaces exactly the ones it moves. */
    fun alarmRequest(alertId: String): Int = 1000 + (alertId.hashCode() and 0xFFFF)
}

/** What the nudge keeper needs from Android: alarms, the alerts channel, and whether it may post. */
interface NudgePort {
    val allowed: Boolean
    fun setAlarm(alert: polaris.engine.TripAlert)
    fun cancelAlarm(id: String)
    fun post(alert: polaris.engine.TripAlert)
    fun isShowing(id: String): Boolean
}

/**
 * The timed nudges' alarms. The trip hands over its plan each time it changes; a nudge leaves the
 * plan once its time has come (TripAlerts.plan keeps one only while it is ahead). So:
 * - one leaving the plan before its time is cancelled;
 * - one leaving it at or after its time is due: its alarm is left to fire, and if it hasn't shown
 *   it is posted here, once (the inexact alarm may come minutes later, or not while the app runs);
 * - the rest get their alarms (re-set in place when their time moves).
 * A plan handed over before notifications were allowed is kept, and [apply] sets it once they are.
 */
class NudgeKeeper(private val port: NudgePort, private val clock: () -> java.time.Instant) {
    private var planned: List<polaris.engine.TripAlert> = emptyList()
    private var set: Map<String, polaris.engine.TripAlert> = emptyMap()
    private val done = mutableSetOf<String>()

    fun schedule(alerts: List<polaris.engine.TripAlert>) {
        // A nudge that was set and has dropped out stays in view until it is settled here.
        val dropped = set.values.filter { old -> alerts.none { it.id == old.id } }
        planned = alerts
        apply(dropped)
    }

    fun apply(dropped: List<polaris.engine.TripAlert> = emptyList()) {
        if (!port.allowed) return
        val now = clock()
        for (old in dropped) {
            if (old.date > now) port.cancelAlarm(old.id) else postIfDue(old, now)
        }
        planned.filter { it.date <= now.plusSeconds(1) }.forEach { postIfDue(it, now) }
        val ahead = planned.filter { it.date > now.plusSeconds(1) }
        ahead.forEach(port::setAlarm)
        set = ahead.associateBy { it.id }
    }

    private fun postIfDue(alert: polaris.engine.TripAlert, now: java.time.Instant) {
        if (alert.id in done) return
        done += alert.id
        // Long past (the phone was off, the process stopped): saying it now would mislead.
        if (java.time.Duration.between(alert.date, now).seconds > LATE_SECONDS) return
        if (!port.isShowing(alert.id)) port.post(alert)
        // Shown here: its alarm, still pending or not, would only show it again.
        port.cancelAlarm(alert.id)
    }

    /** Its alarm showed it (in this process): not posted again. */
    fun shown(id: String) { done += id }
    fun wasShown(id: String) = id in done

    fun cancelAll() {
        (set.keys + planned.map { it.id }).forEach(port::cancelAlarm)
        set = emptyMap()
        planned = emptyList()
        done.clear()
    }

    companion object {
        /** A nudge more than this late isn't shown. */
        const val LATE_SECONDS = 5 * 60L
    }
}
