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

    /** Each nudge's own alarm, so a re-plan replaces exactly the ones it moves. */
    fun alarmRequest(alertId: String): Int = 1000 + (alertId.hashCode() and 0xFFFF)
}
