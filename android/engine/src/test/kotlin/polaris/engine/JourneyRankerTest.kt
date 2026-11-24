package polaris.engine

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mirrors JourneyMergerTests.swift and JourneyRankerTests.swift. */
class JourneyMergerTest {
    private val t0: Instant = Instant.ofEpochSecond(1_800_000_000)

    private fun journey(
        lines: List<Pair<LegMode, String>>, boards: Long, arrives: Long, by: ProviderID,
        variant: String = "Fastest", fare: String? = null,
    ): Journey {
        val legs = lines.mapIndexed { i, (mode, line) ->
            Leg(mode = mode, line = line, fromName = "", toName = "",
                departure = t0.plusSeconds(boards + i * 300L), arrival = t0.plusSeconds(boards + (i + 1) * 300L))
        }
        return Journey(departure = t0.plusSeconds(boards - 120), arrival = t0.plusSeconds(arrives), legs = legs,
            sources = listOf(SourceTag(by, variant)), fare = fare)
    }

    @Test fun sameRouteFromDifferentSourcesMerges() {
        val tfl = journey(listOf(LegMode.tube to "Northern", LegMode.dlr to "DLR"), 300, 2400, ProviderID.tfl)
        // Google calls the same lines slightly differently and is a minute out.
        val google = journey(listOf(LegMode.tube to "Northern line", LegMode.dlr to "DLR"), 360, 2460, ProviderID.google, fare = "£2.80")
        val merged = JourneyMerger.merge(listOf(google, tfl))
        assertEquals(1, merged.size)
        assertEquals(listOf(ProviderID.tfl, ProviderID.google), merged[0].providers)
        assertEquals("£2.80", merged[0].fare)
    }

    @Test fun elizabethLineMatchesWhetherRailOrSubway() {
        val a = journey(listOf(LegMode.elizabeth to "Elizabeth line"), 300, 1500, ProviderID.transitous)
        val b = journey(listOf(LegMode.rail to "Elizabeth line"), 300, 1500, ProviderID.google)
        assertEquals(1, JourneyMerger.merge(listOf(a, b)).size)
    }

    @Test fun laterDepartureOfSameRouteStaysSeparate() {
        val a = journey(listOf(LegMode.bus to "25"), 300, 1500, ProviderID.tfl)
        val b = journey(listOf(LegMode.bus to "25"), 900, 2100, ProviderID.tfl)
        assertEquals(2, JourneyMerger.merge(listOf(a, b)).size)
    }
}

class JourneyRankerTest {
    private val t0: Instant = Instant.ofEpochSecond(1_800_000_000)
    private fun at(minutes: Double): Instant = t0.plusSeconds(minutes * 60)

    /** Leaves `leaves` minutes after t0 and arrives at `arrives`, walking `walk` minutes first, then riding these lines in equal parts. */
    private fun journey(lines: List<String>, leaves: Double = 0.0, arrives: Double, walk: Double = 0.0, mode: LegMode = LegMode.tube): Journey {
        val start = at(leaves)
        val legs = mutableListOf(Leg(mode = LegMode.walk, fromName = "", toName = "", departure = start, arrival = start.plusSeconds(walk * 60)))
        val ride = (arrives - leaves - walk) * 60 / lines.size
        lines.forEachIndexed { i, line ->
            val departure = start.plusSeconds(walk * 60 + i * ride)
            legs += Leg(mode = mode, line = line, fromName = "", toName = "", departure = departure, arrival = departure.plusSeconds(ride))
        }
        return Journey(departure = start, arrival = at(arrives), legs = legs, sources = listOf(SourceTag(ProviderID.tfl, "Fastest")))
    }

    private fun order(ranked: JourneyRanker.Ranked) = ranked.top.map { it.journey.routeSignature }
    private fun rank(journeys: List<Journey>, sort: SortOrder = SortOrder.best, filter: RouteFilter = RouteFilter(), arriveBy: Boolean = false) =
        JourneyRanker.rank(journeys, arriveBy, sort, filter)

    @Test fun shelvesRoutesBeatenOnEverything() {
        val fast = journey(listOf("Hammersmith & City", "Elizabeth"), arrives = 30.0)
        val slow = journey(listOf("Northern", "DLR"), arrives = 39.0)
        val direct = journey(listOf("135"), arrives = 45.0, mode = LegMode.bus)
        val ranked = rank(listOf(slow, direct, fast))
        assertEquals(listOf(fast.routeSignature, direct.routeSignature), order(ranked))
        // Same changes as `fast`, arrives later.
        assertEquals(listOf(slow.routeSignature), ranked.more.map { it.journey.routeSignature })
    }

    @Test fun aMinuteLessWalkingNeverBeatsAnHour() {
        val tube = journey(listOf("Victoria"), arrives = 30.0, walk = 8.0)
        val bus = journey(listOf("N73"), arrives = 90.0, walk = 6.0, mode = LegMode.bus)
        val ranked = rank(listOf(bus, tube), SortOrder.leastWalking)
        assertEquals(listOf(tube.routeSignature), order(ranked))
        assertEquals(listOf(bus.routeSignature), ranked.more.map { it.journey.routeSignature })
    }

    @Test fun muchLessWalkingWinsWhenAskedFor() {
        val walky = journey(listOf("Central"), arrives = 30.0, walk = 16.0)
        val easy = journey(listOf("Jubilee"), arrives = 36.0, walk = 3.0)
        assertEquals(listOf(walky.routeSignature, easy.routeSignature), order(rank(listOf(walky, easy), SortOrder.fastest)))
        assertEquals(listOf(easy.routeSignature, walky.routeSignature), order(rank(listOf(walky, easy), SortOrder.leastWalking)))
    }

    @Test fun changingTheOrderNeverHidesTheBalancedPicks() {
        val routes = listOf(
            journey(listOf("Central"), arrives = 30.0, walk = 16.0), journey(listOf("Jubilee"), arrives = 36.0, walk = 3.0),
            journey(listOf("Northern", "DLR"), arrives = 29.0), journey(listOf("135"), arrives = 41.0, mode = LegMode.bus),
        )
        val balanced = order(rank(routes, SortOrder.best)).toSet()
        for (sort in SortOrder.entries) assertTrue(order(rank(routes, sort)).toSet().containsAll(balanced), "$sort")
    }

    @Test fun fewestChangesBringsUpADirectRoute() {
        val quick = journey(listOf("Northern", "DLR"), arrives = 29.0)
        val direct = journey(listOf("135"), arrives = 50.0, mode = LegMode.bus)
        assertEquals(listOf(quick.routeSignature), order(rank(listOf(quick, direct), SortOrder.best)))
        assertEquals(listOf(direct.routeSignature, quick.routeSignature), order(rank(listOf(quick, direct), SortOrder.fewestChanges)))
    }

    @Test fun otherDeparturesSkipTheSameMinute() {
        val runs = listOf(0.0, 0.5, 7.0).map { journey(listOf("Northern", "DLR"), leaves = it, arrives = it + 29) }
        assertEquals(listOf(at(7.0)), rank(runs).top.first().otherDepartures)
    }

    @Test fun directRouteWinsWhenChangesMatter() {
        val change = journey(listOf("Northern", "DLR"), arrives = 29.0)
        val direct = journey(listOf("Jubilee"), arrives = 40.0)
        assertEquals(change.routeSignature, order(rank(listOf(change, direct), SortOrder.best)).first())
        assertEquals(direct.routeSignature, order(rank(listOf(change, direct), SortOrder.fewestChanges)).first())
    }

    @Test fun laterDeparturesFoldIntoOneCard() {
        val runs = listOf(0.0, 5.0, 10.0).map { journey(listOf("Northern", "DLR"), leaves = it, arrives = it + 29) }
        val ranked = rank(runs.reversed())
        assertTrue(ranked.top.size == 1 && ranked.more.isEmpty())
        assertEquals(t0, ranked.top[0].journey.departure)
        assertEquals(listOf(at(5.0), at(10.0)), ranked.top[0].otherDepartures)
    }

    @Test fun cardCreditsEverySourceThatFoundTheLines() {
        val google = journey(listOf("Northern", "DLR"), arrives = 29.0).copy(sources = listOf(SourceTag(ProviderID.google, "Fastest")))
        val transitous = journey(listOf("Northern", "DLR"), leaves = 8.0, arrives = 37.0).copy(sources = listOf(SourceTag(ProviderID.transitous, "Fastest")))
        assertEquals(listOf(ProviderID.google, ProviderID.transitous), rank(listOf(google, transitous)).top.first().providers)
    }

    @Test fun farSlowerRoutesFoldAway() {
        val quick = journey(listOf("Metropolitan", "Elizabeth"), arrives = 29.0, walk = 13.0)
        val slow = journey(listOf("Hammersmith & City", "135"), arrives = 49.0, walk = 7.0)
        val ranked = rank(listOf(quick, slow))
        assertEquals(listOf(quick.routeSignature), order(ranked))
        assertEquals(1, ranked.more.size)
    }

    @Test fun filtersHideRoutesAndCountThem() {
        val tube = journey(listOf("Northern", "DLR"), arrives = 29.0)
        val bus = journey(listOf("135"), arrives = 45.0, mode = LegMode.bus)
        var ranked = rank(listOf(tube, bus), filter = RouteFilter(avoided = setOf(ModeGroup.bus)))
        assertTrue(order(ranked) == listOf(tube.routeSignature) && ranked.hidden == 1)
        ranked = rank(listOf(tube, bus), filter = RouteFilter(maxChanges = 0))
        assertTrue(order(ranked) == listOf(bus.routeSignature) && ranked.hidden == 1)
    }

    @Test fun arriveByPrefersLatestDeparture() {
        val early = journey(listOf("25"), leaves = 0.0, arrives = 25.0, mode = LegMode.bus)
        val late = journey(listOf("Central"), leaves = 10.0, arrives = 25.0)
        assertEquals(late.routeSignature, order(rank(listOf(early, late), arriveBy = true)).first())
    }
}
