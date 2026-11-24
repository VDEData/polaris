package polaris.engine

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TfL's network status checked against routes from any source. Recorded on
 * Sunday 8 November 2026, when Elizabeth line trains weren't stopping at Canary
 * Wharf, the Windrush and Mildmay were part closed, and Burnt Oak was shut.
 * Mirrors DisruptionTests.swift.
 */
class DisruptionTest {
    private val afternoon = iso("2026-11-08T16:00:00Z")

    private fun status() = NetworkStatus.parse(Fixtures.bytes("tfl-line-status.json"), Fixtures.bytes("tfl-stop-disruptions.json"))

    private fun ride(mode: LegMode, line: String, from: String, to: String, via: List<String> = emptyList(), at: Instant = afternoon) =
        Leg(mode = mode, line = line, fromName = from, toName = to, departure = at, arrival = at.plusSeconds(600), intermediateStops = via)

    @Test fun readsTheNetwork() {
        val status = status()
        assertTrue(status.lines.any { it.name == "Jubilee" && it.severity == 9 })
        val wharf = status.stops.first { it.key == "canarywharf" }
        assertEquals(LegMode.elizabeth, wharf.mode)
        assertNull(wharf.line)
        assertFalse(wharf.isClosure)
        // Step-free notes and interchange messages aren't closures.
        assertTrue(status.stops.all { "No Step Free Access" !in it.description })
        assertTrue(status.stops.any { it.line == "windrush" && it.key == "surreyquays" })
        assertEquals(NetworkStatus.stationKey("Shepherd's Bush"), NetworkStatus.stationKey("Shepherds Bush Rail Station"))
        assertEquals(NetworkStatus.stationKey("Kensington (Olympia)"), NetworkStatus.stationKey("Kensington (Olympia) Rail Station"))
    }

    @Test fun flagsARouteThroughTheClosedStation() {
        val status = status()
        // The Elizabeth line to Canary Wharf, as a planner that didn't know would suggest.
        val notice = status.notices(ride(LegMode.elizabeth, "Elizabeth line", "Liverpool Street", "Canary Wharf", listOf("Whitechapel"))).first()
        assertEquals(ServiceNotice.Severity.closed, notice.severity)
        assertEquals("Canary Wharf part closed (Elizabeth line)", notice.title)
        assertTrue("not stopping" in notice.detail)
        assertEquals(ProviderID.tfl, notice.source)
        // Going through without stopping is fine.
        assertTrue(status.notices(ride(LegMode.elizabeth, "Elizabeth line", "Whitechapel", "Abbey Wood", listOf("Canary Wharf", "Custom House"))).isEmpty())
        // So is the DLR there; the Jubilee only has minor delays at its far end.
        assertTrue(status.notices(ride(LegMode.dlr, "DLR", "Bank", "Canary Wharf")).isEmpty())
        val jubilee = status.notices(ride(LegMode.tube, "Jubilee", "London Bridge", "Canary Wharf"))
        assertEquals(listOf(ServiceNotice.Severity.minor), jubilee.map { it.severity })
        assertEquals("Minor delays on the Jubilee", jubilee.first().title)
    }

    @Test fun tellsAStretchFromItsEnd() {
        val status = status()
        // No Windrush service between Surrey Quays and Clapham Junction: riding within it won't work…
        val within = status.notices(ride(LegMode.overground, "Windrush", "Surrey Quays", "Peckham Rye", listOf("Queens Road Peckham")))
        assertEquals(ServiceNotice.Severity.closed, within.first().severity)
        assertEquals("Part closure on the Windrush", within.first().title)
        // …but Surrey Quays still has trains northbound, so riding from it is only a warning.
        assertEquals(listOf(ServiceNotice.Severity.delays), status.notices(ride(LegMode.overground, "Windrush", "Surrey Quays", "Canada Water")).map { it.severity })
        // The Mildmay's closure is listed at the same stations, but isn't about the Windrush.
        assertTrue(within.none { it.detail.startsWith("MILDMAY") })
        assertEquals(ServiceNotice.Severity.closed, status.notices(ride(LegMode.overground, "Mildmay", "Willesden Junction", "Shepherd's Bush")).first().severity)
        // And it's only for today.
        val tuesday = ride(LegMode.overground, "Windrush", "Surrey Quays", "Peckham Rye", at = iso("2026-11-10T09:00:00Z"))
        assertTrue(status.notices(tuesday).all { it.severity != ServiceNotice.Severity.closed })
    }

    @Test fun aStationShutAndOneShutOneWay() {
        val status = status()
        val burntOak = status.notices(ride(LegMode.tube, "Northern", "Colindale", "Burnt Oak"))
        assertEquals("Burnt Oak station closed", burntOak.first().title)
        assertEquals(ServiceNotice.Severity.closed, burntOak.first().severity)
        // Westbound trains not calling at Barons Court leaves the eastbound ones: a warning, not a closure.
        assertEquals(listOf(ServiceNotice.Severity.delays), status.notices(ride(LegMode.tube, "Piccadilly", "Barons Court", "Green Park")).map { it.severity })
        // A line closed for the day.
        assertEquals(ServiceNotice.Severity.closed, status.notices(ride(LegMode.tube, "Waterloo & City", "Waterloo", "Bank")).first().severity)
    }

    @Test fun aRouteThroughAClosureNeverLeads() {
        val status = status()
        fun journey(leg: Leg, minutes: Long, from: ProviderID): Journey {
            val j = Journey(departure = afternoon, arrival = afternoon.plusSeconds(minutes * 60), legs = listOf(leg), sources = listOf(SourceTag(from, "Fastest")))
            return j.copy(notices = status.notices(j))
        }
        // Google's quicker route stops at Canary Wharf on the Elizabeth line; TfL's goes by DLR.
        val elizabeth = journey(ride(LegMode.elizabeth, "Elizabeth line", "Liverpool Street", "Canary Wharf"), 12, ProviderID.google)
        val dlr = journey(ride(LegMode.dlr, "DLR", "Bank", "Canary Wharf"), 19, ProviderID.tfl)
        assertTrue(elizabeth.isBlocked)
        assertFalse(dlr.isBlocked)
        val ranked = JourneyRanker.rank(listOf(elizabeth, dlr), arriveBy = false)
        assertEquals(listOf(dlr.id), ranked.top.map { it.journey.id })
        assertEquals(listOf(elizabeth.id), ranked.more.map { it.journey.id })
    }
}
