package polaris.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Recorded and hand-written journeys run through parsing, TfL's notices, merging and ranking,
 * checked against PolarisTests/Fixtures/journey-expectations.json, which the iOS tests
 * (SharedExpectationsTests.swift) assert against too.
 */
class SharedExpectationsTest {
    @Serializable data class Expectations(val about: String, val coordinatePairs: List<Pair>, val text: TextOrder, val cases: List<Case>)
    @Serializable data class TextOrder(val normalise: List<Normalised>, val unsorted: List<String>, val sorted: List<String>)
    @Serializable data class Normalised(val line: String, val normalised: String)
    @Serializable data class Pair(val latitude: Double, val longitude: Double, val pair: String)
    @Serializable data class Case(
        val name: String, val query: Query, val network: Network? = null, val sources: List<Source>,
        val merged: List<Merged>, val ranking: Map<String, Ranked>, val filters: List<Filtered>,
    )
    @Serializable data class Query(val origin: Point, val destination: Point, val time: String, val arriveBy: Boolean)
    @Serializable data class Point(val name: String, val latitude: Double, val longitude: Double)
    @Serializable data class Network(val lines: String, val stops: String)
    @Serializable data class Source(val fixture: String, val provider: String, val variant: String, val journeys: List<Found>)
    @Serializable data class Found(val signature: String, val departure: String, val arrival: String, val fare: String? = null, val legs: List<FoundLeg>)
    @Serializable data class FoundLeg(
        val mode: String, val line: String? = null, val from: String, val to: String, val departure: String, val arrival: String,
        val platform: String? = null, val stopCount: Int? = null,
    )
    @Serializable data class Merged(
        val signature: String, val firstBoarding: String, val departure: String, val arrival: String, val fare: String? = null,
        val sources: List<String>, val notices: List<Notice>,
        /** The leading copy's legs: which copy leads must come out the same on both sides. */
        val legs: List<FoundLeg>,
    )
    @Serializable data class Notice(val leg: Int, val severity: String, val title: String)
    @Serializable data class Ranked(val top: List<Card>, val more: List<Card>, val hidden: Int)
    @Serializable data class Card(val signature: String, val departure: String, val arrival: String, val otherDepartures: List<String>, val providers: List<String>)
    @Serializable data class Filtered(
        val sort: String, val maxChanges: Int? = null, val maxWalkMinutes: Int? = null, val avoided: List<String>, val ranked: Ranked,
    )

    private val expected = Json.decodeFromString<Expectations>(Fixtures.bytes("journey-expectations.json").decodeToString())

    private fun query(c: Case): JourneyQuery {
        val q = c.query
        val at = iso(q.time)
        return JourneyQuery(
            Place(name = q.origin.name, coordinate = Coordinate(q.origin.latitude, q.origin.longitude)),
            Place(name = q.destination.name, coordinate = Coordinate(q.destination.latitude, q.destination.longitude)),
            if (q.arriveBy) TimeMode.ArriveBy(at) else TimeMode.DepartAt(at),
        )
    }

    private fun parse(source: Source, query: JourneyQuery): List<Journey> {
        val data = Fixtures.bytes(source.fixture)
        return when (source.provider) {
            "tfl" -> TfLProvider.parse(data, source.variant)
            "transitous" -> TransitousProvider.parse(data, query.origin.name, query.destination.name)
            "google" -> GoogleRoutesProvider.parse(data, query, source.variant)
            else -> error("unknown provider ${source.provider}")
        }
    }

    private fun merged(c: Case): List<Journey> {
        val q = query(c)
        val merged = JourneyMerger.merge(c.sources.flatMap { parse(it, q) })
        val network = c.network ?: return merged
        val status = NetworkStatus.parse(Fixtures.bytes(network.lines), Fixtures.bytes(network.stops))
        return merged.map { it.copy(notices = status.notices(it)) }
    }

    private fun iso(i: java.time.Instant) = Time.isoString(i)
    private fun found(j: Journey) = Found(
        j.routeSignature, iso(j.departure), iso(j.arrival), j.fare,
        j.legs.map { FoundLeg(it.mode.name, it.line, it.fromName, it.toName, iso(it.departure), iso(it.arrival), it.platform, it.stopCount) },
    )
    private fun summary(j: Journey) = Merged(
        j.routeSignature, iso(j.firstBoarding), iso(j.departure), iso(j.arrival), j.fare,
        j.sources.map { "${it.provider.name}:${it.variant}" },
        j.notices.map { n -> Notice(j.legs.indexOfFirst { it.id == n.leg }, n.severity.name, n.title) },
        found(j).legs,
    )
    private fun ranked(r: JourneyRanker.Ranked) = Ranked(r.top.map(::card), r.more.map(::card), r.hidden)
    private fun card(o: RouteOption) = Card(
        o.journey.routeSignature, iso(o.journey.departure), iso(o.journey.arrival),
        o.otherDepartures.map(Time::isoString), o.providers.map { it.name },
    )

    @Test fun textIsNormalisedAndOrderedAsInSwift() {
        for (n in expected.text.normalise) assertEquals(n.normalised, Journey.normalise(n.line), n.line)
        assertEquals(expected.text.sorted, expected.text.unsorted.sortedWith(Text::compare))
    }

    @Test fun coordinatesAreWrittenTheSame() {
        for (p in expected.coordinatePairs) assertEquals(p.pair, Coordinate(p.latitude, p.longitude).pair)
    }

    @Test fun eachSourceParsesTheSame() {
        for (c in expected.cases) {
            val q = query(c)
            assertEquals(c.sources, c.sources.map { s -> s.copy(journeys = parse(s, q).map(::found)) }, c.name)
        }
    }

    @Test fun theSourcesMergeIntoTheSameGroupsWithTheSameNotices() {
        for (c in expected.cases) assertEquals(c.merged, merged(c).map(::summary), c.name)
    }

    @Test fun everySortRanksTheSame() {
        for (c in expected.cases) {
            assertEquals(SortOrder.entries.map { it.name }.toSet(), c.ranking.keys, c.name)
            val merged = merged(c)
            for (sort in SortOrder.entries) {
                assertEquals(c.ranking.getValue(sort.name), ranked(JourneyRanker.rank(merged, c.query.arriveBy, sort)), "${c.name}: ${sort.name}")
            }
        }
    }

    @Test fun everyFilterHidesTheSame() {
        for (c in expected.cases) {
            val merged = merged(c)
            for (f in c.filters) {
                // Names this side doesn't know are left out, on both platforms.
                val filter = RouteFilter(f.maxChanges, f.maxWalkMinutes, f.avoided.mapNotNull(ModeGroup::named).toSet())
                assertEquals(f.ranked, ranked(JourneyRanker.rank(merged, c.query.arriveBy, SortOrder.valueOf(f.sort), filter)), "${c.name}: $f")
            }
        }
    }

    /** Whichever order the sources' answers arrive in, the merge comes out the same. */
    @Test fun mergingDoesNotDependOnArrivalOrder() {
        for (c in expected.cases) {
            val q = query(c)
            val journeys = c.sources.flatMap { parse(it, q) }
            val forward = JourneyMerger.merge(journeys).map(::summary)
            assertEquals(forward, JourneyMerger.merge(journeys.reversed()).map(::summary), "${c.name}: reversed")
            val random = Random(7)
            repeat(5) { assertEquals(forward, JourneyMerger.merge(journeys.shuffled(random)).map(::summary), "${c.name}: shuffled") }
        }
    }
}
