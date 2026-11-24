package polaris.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The recorded journeys run through parsing, merging and ranking, checked against
 * PolarisTests/Fixtures/journey-expectations.json, which the iOS tests
 * (SharedExpectationsTests.swift) assert against too.
 */
class SharedExpectationsTest {
    @Serializable data class Expectations(
        val about: String, val query: Query, val sources: List<Source>, val merged: List<Merged>,
        val ranking: Map<String, Ranked>, val filtered: Filtered,
    )
    @Serializable data class Query(val origin: Point, val destination: Point, val departAt: String, val arriveBy: Boolean)
    @Serializable data class Point(val name: String, val latitude: Double, val longitude: Double)
    @Serializable data class Source(val fixture: String, val provider: String, val variant: String, val journeys: List<Found>)
    @Serializable data class Found(val signature: String, val departure: String, val arrival: String)
    @Serializable data class Merged(val signature: String, val firstBoarding: String, val departure: String, val arrival: String, val sources: List<String>)
    @Serializable data class Ranked(val top: List<Card>, val more: List<Card>, val hidden: Int)
    @Serializable data class Card(val signature: String, val departure: String, val arrival: String, val otherDepartures: List<String>, val providers: List<String>)
    @Serializable data class Filtered(
        val sort: String, val maxChanges: Int? = null, val maxWalkMinutes: Int? = null, val avoided: List<String>, val ranked: Ranked,
    )

    private val expected = Json.decodeFromString<Expectations>(Fixtures.bytes("journey-expectations.json").decodeToString())

    private val query = expected.query.let { q ->
        val at = iso(q.departAt)
        JourneyQuery(
            Place(name = q.origin.name, coordinate = Coordinate(q.origin.latitude, q.origin.longitude)),
            Place(name = q.destination.name, coordinate = Coordinate(q.destination.latitude, q.destination.longitude)),
            if (q.arriveBy) TimeMode.ArriveBy(at) else TimeMode.DepartAt(at),
        )
    }

    private val parsed: List<List<Journey>> = expected.sources.map { source ->
        val data = Fixtures.bytes(source.fixture)
        when (source.provider) {
            "tfl" -> TfLProvider.parse(data, source.variant)
            "transitous" -> TransitousProvider.parse(data, query.origin.name, query.destination.name)
            "google" -> GoogleRoutesProvider.parse(data, query, source.variant)
            else -> error("unknown provider ${source.provider}")
        }
    }
    private val merged = JourneyMerger.merge(parsed.flatten())

    private fun ranked(r: JourneyRanker.Ranked) = Ranked(r.top.map(::card), r.more.map(::card), r.hidden)
    private fun card(o: RouteOption) = Card(
        o.journey.routeSignature, Time.isoString(o.journey.departure), Time.isoString(o.journey.arrival),
        o.otherDepartures.map(Time::isoString), o.providers.map { it.name },
    )

    @Test fun eachSourceFindsTheSameRoutes() {
        val actual = expected.sources.zip(parsed).map { (source, journeys) ->
            source.copy(journeys = journeys.map { Found(it.routeSignature, Time.isoString(it.departure), Time.isoString(it.arrival)) })
        }
        assertEquals(expected.sources, actual)
    }

    @Test fun theSourcesMergeIntoTheSameGroups() {
        val actual = merged.map { j ->
            Merged(j.routeSignature, Time.isoString(j.firstBoarding), Time.isoString(j.departure), Time.isoString(j.arrival),
                j.sources.map { "${it.provider.name}:${it.variant}" })
        }
        assertEquals(expected.merged, actual)
    }

    @Test fun everySortRanksTheSame() {
        assertEquals(SortOrder.entries.map { it.name }.toSet(), expected.ranking.keys)
        for (sort in SortOrder.entries) {
            assertEquals(expected.ranking.getValue(sort.name), ranked(JourneyRanker.rank(merged, expected.query.arriveBy, sort)), "sort ${sort.name}")
        }
    }

    @Test fun theFilterHidesTheSame() {
        val f = expected.filtered
        val filter = RouteFilter(f.maxChanges, f.maxWalkMinutes, f.avoided.map { ModeGroup.valueOf(it) }.toSet())
        assertEquals(f.ranked, ranked(JourneyRanker.rank(merged, expected.query.arriveBy, SortOrder.valueOf(f.sort), filter)))
    }
}
