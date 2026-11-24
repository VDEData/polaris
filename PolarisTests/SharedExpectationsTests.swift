import Foundation
import Testing
@testable import Polaris

/// Recorded and hand-written journeys run through parsing, TfL's notices, merging and ranking,
/// checked against `Fixtures/journey-expectations.json`. The Android engine's tests assert against
/// the same file, so the two platforms parse each leg, merge and order routes the same way.
///
/// On a mismatch the test prints what this implementation produces, between the markers
/// below, so a deliberate change can be written back to the file (and checked by hand).
struct SharedExpectationsTests {
    struct Expectations: Codable, Equatable {
        var about: String
        var coordinatePairs: [Pair]
        var text: TextOrder
        var cases: [Case]
    }

    /// Line names normalised for matching, and text in the order both platforms sort it.
    struct TextOrder: Codable, Equatable {
        var normalise: [Normalised]
        var unsorted: [String]
        var sorted: [String]
    }

    struct Normalised: Codable, Equatable {
        var line: String
        var normalised: String
    }

    struct Pair: Codable, Equatable {
        var latitude: Double
        var longitude: Double
        var pair: String
    }

    struct Case: Codable, Equatable {
        var name: String
        var query: Query
        var network: Network?
        var sources: [Source]
        var merged: [Merged]
        var ranking: [String: Ranked]
        var filters: [Filtered]
    }

    struct Query: Codable, Equatable {
        var origin: Point
        var destination: Point
        var time: String
        var arriveBy: Bool
    }

    struct Point: Codable, Equatable {
        var name: String
        var latitude: Double
        var longitude: Double
    }

    struct Network: Codable, Equatable {
        var lines: String
        var stops: String
    }

    struct Source: Codable, Equatable {
        var fixture: String
        var provider: String
        var variant: String
        var journeys: [Found]
    }

    struct Found: Codable, Equatable {
        var signature: String
        var departure: String
        var arrival: String
        var fare: String?
        var legs: [FoundLeg]
    }

    struct FoundLeg: Codable, Equatable {
        var mode: String
        var line: String?
        var from: String
        var to: String
        var departure: String
        var arrival: String
        var platform: String?
        var stopCount: Int?
    }

    struct Merged: Codable, Equatable {
        var signature: String
        var firstBoarding: String
        var departure: String
        var arrival: String
        var fare: String?
        var sources: [String]
        var notices: [Notice]
        /// The leading copy's legs: which copy leads must come out the same on both sides.
        var legs: [FoundLeg]
    }

    struct Notice: Codable, Equatable {
        var leg: Int
        var severity: String
        var title: String
    }

    struct Ranked: Codable, Equatable {
        var top: [Card]
        var more: [Card]
        var hidden: Int
    }

    struct Card: Codable, Equatable {
        var signature: String
        var departure: String
        var arrival: String
        var otherDepartures: [String]
        var providers: [String]
    }

    struct Filtered: Codable, Equatable {
        var sort: String
        var maxChanges: Int?
        var maxWalkMinutes: Int?
        var avoided: [String]
        var ranked: Ranked
    }

    static func expectations() throws -> Expectations {
        try JSONDecoder().decode(Expectations.self, from: ProviderParsingTests.fixture("journey-expectations"))
    }

    /// JSONDecoder skips keys it doesn't know, where the Android test rejects them: a field added to
    /// the file must be read here too, or this fails.
    @Test func readsEveryKeyInTheFile() throws {
        let data = try ProviderParsingTests.fixture("journey-expectations")
        let file = try JSONSerialization.jsonObject(with: data) as? NSObject
        let read = try JSONSerialization.jsonObject(with: JSONEncoder().encode(Self.expectations())) as? NSObject
        #expect(file != nil && file == read, "journey-expectations.json has keys or values this test doesn't read")
    }

    @Test func matchesTheSharedExpectations() throws {
        let expected = try Self.expectations()
        var actual = expected
        actual.text.normalise = expected.text.normalise.map { Normalised(line: $0.line, normalised: Journey.normalise($0.line)) }
        actual.text.sorted = expected.text.unsorted.sorted()
        actual.coordinatePairs = expected.coordinatePairs.map {
            Pair(latitude: $0.latitude, longitude: $0.longitude, pair: Coordinate(latitude: $0.latitude, longitude: $0.longitude).pair)
        }
        actual.cases = try expected.cases.map(Self.actual)
        if actual != expected {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
            print("---- journey-expectations actual ----")
            print(String(decoding: try encoder.encode(actual), as: UTF8.self))
            print("---- end journey-expectations actual ----")
        }
        #expect(actual.coordinatePairs == expected.coordinatePairs)
        #expect(actual.text == expected.text)
        for (a, e) in zip(actual.cases, expected.cases) {
            #expect(a.sources == e.sources, "\(e.name): sources")
            #expect(a.merged == e.merged, "\(e.name): merged")
            #expect(a.ranking == e.ranking, "\(e.name): ranking")
            #expect(a.filters == e.filters, "\(e.name): filters")
        }
    }

    /// Whichever order the sources' answers arrive in, the merge comes out the same.
    @Test func mergingDoesNotDependOnArrivalOrder() throws {
        for c in try Self.expectations().cases {
            let (query, _) = try Self.query(c)
            let journeys = try c.sources.flatMap { try Self.parse($0, query: query) }
            let forward = JourneyMerger.merge(journeys).map(Self.summary)
            #expect(JourneyMerger.merge(journeys.reversed()).map(Self.summary) == forward, "\(c.name): reversed")
            var rng = SeededGenerator(seed: 7)
            for _ in 0..<5 {
                #expect(JourneyMerger.merge(journeys.shuffled(using: &rng)).map(Self.summary) == forward, "\(c.name): shuffled")
            }
        }
    }

    static func query(_ c: Case) throws -> (JourneyQuery, Date) {
        let q = c.query
        let at = try #require(Time.iso(q.time))
        return (JourneyQuery(
            origin: Place(name: q.origin.name, coordinate: .init(latitude: q.origin.latitude, longitude: q.origin.longitude)),
            destination: Place(name: q.destination.name, coordinate: .init(latitude: q.destination.latitude, longitude: q.destination.longitude)),
            time: q.arriveBy ? .arriveBy(at) : .departAt(at)), at)
    }

    static func parse(_ source: Source, query: JourneyQuery) throws -> [Journey] {
        let data = try ProviderParsingTests.fixture(String(source.fixture.dropLast(".json".count)))
        switch source.provider {
        case "tfl": return try TfLProvider.parse(data, variant: source.variant)
        case "transitous": return try TransitousProvider.parse(data, origin: query.origin.name, destination: query.destination.name)
        case "google": return try GoogleRoutesProvider.parse(data, query: query, variant: source.variant)
        default: throw ProviderError.badResponse
        }
    }

    /// Everything but the inputs is worked out here from the fixtures.
    static func actual(_ expected: Case) throws -> Case {
        let (query, _) = try query(expected)
        var all: [Journey] = []
        var result = expected
        result.sources = try expected.sources.map { source in
            let journeys = try parse(source, query: query)
            all += journeys
            var s = source
            s.journeys = journeys.map(found)
            return s
        }
        var merged = JourneyMerger.merge(all)
        if let network = expected.network {
            let status = try NetworkStatus.parse(lines: ProviderParsingTests.fixture(String(network.lines.dropLast(5))),
                                                 stops: ProviderParsingTests.fixture(String(network.stops.dropLast(5))))
            merged = merged.map { journey in
                var journey = journey
                journey.notices = status.notices(for: journey)
                return journey
            }
        }
        result.merged = merged.map(summary)
        result.ranking = [:]
        for sort in SortOrder.allCases {
            result.ranking[sort.rawValue] = ranked(JourneyRanker.rank(merged, arriveBy: expected.query.arriveBy, sort: sort))
        }
        result.filters = try expected.filters.map { f in
            // Names this side doesn't know are left out, on both platforms.
            let filter = RouteFilter(maxChanges: f.maxChanges, maxWalkMinutes: f.maxWalkMinutes,
                                     avoided: Set(f.avoided.compactMap(ModeGroup.init(rawValue:))))
            var out = f
            out.ranked = ranked(JourneyRanker.rank(merged, arriveBy: expected.query.arriveBy, sort: try #require(SortOrder(rawValue: f.sort)), filter: filter))
            return out
        }
        return result
    }

    static func found(_ j: Journey) -> Found {
        Found(signature: j.routeSignature, departure: iso(j.departure), arrival: iso(j.arrival), fare: j.fare,
              legs: j.legs.map { l in
                  FoundLeg(mode: l.mode.rawValue, line: l.line, from: l.fromName, to: l.toName, departure: iso(l.departure),
                           arrival: iso(l.arrival), platform: l.platform, stopCount: l.stopCount)
              })
    }

    static func summary(_ j: Journey) -> Merged {
        Merged(signature: j.routeSignature, firstBoarding: iso(j.firstBoarding), departure: iso(j.departure), arrival: iso(j.arrival),
               fare: j.fare, sources: j.sources.map { "\($0.provider.rawValue):\($0.variant)" },
               notices: j.notices.map { n in
                   Notice(leg: j.legs.firstIndex { $0.id == n.leg } ?? -1, severity: "\(n.severity)", title: n.title)
               },
               legs: found(j).legs)
    }

    static func ranked(_ r: JourneyRanker.Ranked) -> Ranked {
        Ranked(top: r.top.map(card), more: r.more.map(card), hidden: r.hidden)
    }

    static func card(_ o: RouteOption) -> Card {
        Card(signature: o.journey.routeSignature, departure: iso(o.journey.departure), arrival: iso(o.journey.arrival),
             otherDepartures: o.otherDepartures.map(iso), providers: o.providers.map(\.rawValue))
    }

    static func iso(_ date: Date) -> String { Time.isoString(date) }
}

/// A small fixed-seed generator, so a shuffle that fails fails the same way again.
struct SeededGenerator: RandomNumberGenerator {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return state
    }
}
