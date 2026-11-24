import Foundation
import Testing
@testable import Polaris

/// The recorded journeys run through parsing, merging and ranking, checked against
/// `Fixtures/journey-expectations.json`. The Android engine's tests assert against the same file,
/// so the two platforms find, merge and order the same routes from the same answers.
///
/// On a mismatch the test prints what this implementation produces, between the markers
/// below, so a deliberate change can be written back to the file (and checked by hand).
struct SharedExpectationsTests {
    struct Expectations: Codable, Equatable {
        var about: String
        var query: Query
        var sources: [Source]
        var merged: [Merged]
        var ranking: [String: Ranked]
        var filtered: Filtered
    }

    struct Query: Codable, Equatable {
        var origin: Point
        var destination: Point
        var departAt: String
        var arriveBy: Bool
    }

    struct Point: Codable, Equatable {
        var name: String
        var latitude: Double
        var longitude: Double
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
    }

    struct Merged: Codable, Equatable {
        var signature: String
        var firstBoarding: String
        var departure: String
        var arrival: String
        var sources: [String]
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

    @Test func matchesTheSharedExpectations() throws {
        let expected = try JSONDecoder().decode(Expectations.self, from: ProviderParsingTests.fixture("journey-expectations"))
        let actual = try Self.actual(for: expected)
        if actual != expected {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
            print("---- journey-expectations actual ----")
            print(String(decoding: try encoder.encode(actual), as: UTF8.self))
            print("---- end journey-expectations actual ----")
        }
        #expect(actual.sources == expected.sources)
        #expect(actual.merged == expected.merged)
        #expect(actual.ranking == expected.ranking)
        #expect(actual.filtered == expected.filtered)
    }

    /// Everything but the inputs is worked out here from the fixtures.
    static func actual(for expected: Expectations) throws -> Expectations {
        let q = expected.query
        let departAt = try #require(Time.iso(q.departAt))
        let query = JourneyQuery(
            origin: Place(name: q.origin.name, coordinate: .init(latitude: q.origin.latitude, longitude: q.origin.longitude)),
            destination: Place(name: q.destination.name, coordinate: .init(latitude: q.destination.latitude, longitude: q.destination.longitude)),
            time: q.arriveBy ? .arriveBy(departAt) : .departAt(departAt))

        var all: [Journey] = []
        var sources: [Source] = []
        for source in expected.sources {
            let data = try ProviderParsingTests.fixture(String(source.fixture.dropLast(".json".count)))
            let journeys: [Journey] = switch source.provider {
            case "tfl": try TfLProvider.parse(data, variant: source.variant)
            case "transitous": try TransitousProvider.parse(data, origin: query.origin.name, destination: query.destination.name)
            case "google": try GoogleRoutesProvider.parse(data, query: query, variant: source.variant)
            default: []
            }
            all += journeys
            sources.append(Source(fixture: source.fixture, provider: source.provider, variant: source.variant,
                                  journeys: journeys.map { Found(signature: $0.routeSignature, departure: iso($0.departure), arrival: iso($0.arrival)) }))
        }

        let merged = JourneyMerger.merge(all)
        var ranking: [String: Ranked] = [:]
        for sort in SortOrder.allCases {
            ranking[sort.rawValue] = ranked(JourneyRanker.rank(merged, arriveBy: q.arriveBy, sort: sort))
        }
        let f = expected.filtered
        let filter = RouteFilter(maxChanges: f.maxChanges, maxWalkMinutes: f.maxWalkMinutes,
                                 avoided: Set(f.avoided.compactMap(ModeGroup.init(rawValue:))))
        let sort = try #require(SortOrder(rawValue: f.sort))

        var result = expected
        result.sources = sources
        result.merged = merged.map {
            Merged(signature: $0.routeSignature, firstBoarding: iso($0.firstBoarding), departure: iso($0.departure),
                   arrival: iso($0.arrival), sources: $0.sources.map { "\($0.provider.rawValue):\($0.variant)" })
        }
        result.ranking = ranking
        result.filtered.ranked = ranked(JourneyRanker.rank(merged, arriveBy: q.arriveBy, sort: sort, filter: filter))
        return result
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
