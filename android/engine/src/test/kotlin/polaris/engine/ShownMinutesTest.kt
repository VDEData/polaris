package polaris.engine

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** "N min sooner" is the difference between the two times shown beside it (each to the minute, as the clock shows them), never a rounding of the seconds between. */
class ShownMinutesTest {
    private fun iso(s: String) = Instant.parse("2026-11-16T${s}Z")

    private fun journeyArriving(at: Instant): Journey {
        val leg = Leg(mode = LegMode.tube, line = "Jubilee", fromName = "A", toName = "B", departure = at.plusSeconds(-1200), arrival = at,
            path = listOf(Coordinate(51.5, -0.1), Coordinate(51.51, -0.09)))
        return Journey(departure = leg.departure, arrival = at, legs = listOf(leg), sources = emptyList())
    }

    @Test fun shownMinutesCountWholeClockMinutes() {
        assertEquals(12, Strip.shownMinutes(iso("11:17:10"), iso("11:29:50")))
        assertEquals(13, Strip.shownMinutes(iso("11:17:50"), iso("11:30:10")))
    }

    @Test fun aFasterWaysMinutesMatchTheTwoArrivalsShown() {
        // Arrive 11:29 shown in the header, 11:17 on the offer: 12 min sooner (the seconds give 12.7).
        val timeline = TripTimeline.plan(journeyArriving(iso("11:29:50")))
        val offer = Replan.Offer(journeyArriving(iso("11:17:10")), Replan.Origin(0, "A", Coordinate(51.5, -0.1), iso("11:00:00")), gain = 760.0)
        assertEquals(12, Replan.message(offer, Replan.Reason.Asked, timeline).minutes)
    }

    @Test fun earlyMinutesMatchThePlannedAndNewArrivalsShown() {
        // Planned 11:18 (seconds 11:18:46), now 11:11: 7 min sooner (the seconds give 7.6).
        val timeline = TripTimeline.plan(journeyArriving(iso("11:11:10")))
        val service = Departure(id = "j", line = "Jubilee", mode = LegMode.tube, lineColor = null, headsign = "Stratford", stopName = "A",
            stopCoordinate = Coordinate(51.5, -0.1), scheduled = iso("10:51:10"), expected = iso("10:51:10"), isRealtime = true, isCancelled = false)
        val message = Replan.message(Replan.Status.OtherRide(0, service, by = -456.0), timeline, false) as ReplanMessage.OtherRide
        assertEquals(7, message.minutes)
    }
}
