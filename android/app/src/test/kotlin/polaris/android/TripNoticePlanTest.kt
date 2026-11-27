package polaris.android

import polaris.android.trip.TripNoticePlan
import polaris.engine.LegMode
import polaris.engine.ReplanMessage
import polaris.engine.TripAlerts
import polaris.engine.TripCard
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The trip notification's decisions: which buttons, when it times out, when it's posted again, and one alarm per nudge. */
class TripNoticePlanTest {
    private val now = Instant.parse("2026-11-16T06:30:00Z")
    private fun card(replan: ReplanMessage? = null, metres: Double? = 120.0) = TripCard(
        step = TripCard.Step.WalkToEnd("One Canada Square", metres, 300.0), mode = LegMode.walk, line = null, lineColor = null,
        countdown = TripCard.Countdown.arrive, countdownTo = now.plusSeconds(600), arrival = now.plusSeconds(600),
        stepNumber = 5, stepCount = 5, replan = replan,
    )

    @Test fun aFasterWayOffersSwitchAndKeepWithNoEndBesideThem() {
        val offer = ReplanMessage.Faster("Baker Street", listOf("DLR"), "Heron Quays", now.plusSeconds(900), 11, ReplanMessage.Than.Nothing, true)
        val plan = TripNoticePlan.of(card(offer), now)
        assertTrue(plan.offerActions)
        assertFalse(plan.endAction)
        val plain = TripNoticePlan.of(card(), now)
        assertFalse(plain.offerActions)
        assertTrue(plain.endAction)
    }

    @Test fun itTimesOutHalfAnHourPastTheArrival() {
        // Arrival in 10 minutes, so 40 minutes from now.
        assertEquals(40 * 60_000L, TripNoticePlan.of(card(), now).timeoutMillis)
        // Already past: a minute, not zero or below.
        assertEquals(60_000L, TripNoticePlan.of(card(), now.plusSeconds(3 * 3600)).timeoutMillis)
    }

    @Test fun itIsPostedAgainOnlyWhenWhatsShownChanges() {
        assertFalse(TripNoticePlan.changed(card(metres = 120.0), card(metres = 120.0)))
        assertTrue(TripNoticePlan.changed(card(metres = 120.0), card(metres = 110.0)))
        assertTrue(TripNoticePlan.changed(null, card()))
    }

    @Test fun everyNudgeHasItsOwnAlarm() {
        val requests = TripAlerts.allIds.map(TripNoticePlan::alarmRequest)
        assertEquals(requests.size, requests.toSet().size)
    }
}
