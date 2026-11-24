import Foundation
import Observation

enum Loadable<Value: Sendable>: Sendable {
    case loading
    case loaded(Value)
    case failed(String)

    var value: Value? { if case .loaded(let v) = self { v } else { nil } }
}

/// Departure boards for the journey on screen, and what's leaving near you.
/// Boards are kept for a minute, so moving between screens doesn't ask again.
@MainActor @Observable
final class DepartureStore {
    private let provider = DepartureProvider()
    private(set) var boards: [UUID: Loadable<DepartureBoard>] = [:]
    private var fetched: [UUID: Date] = [:]
    private(set) var nearby: Loadable<[NearbyLine]>?
    private var nearbyFetched: (at: Date, around: Coordinate)?

    static let freshFor: TimeInterval = 60

    func board(_ leg: Leg) -> Loadable<DepartureBoard>? { boards[leg.id] }

    func load(_ leg: Leg, force: Bool = false) async {
        guard leg.mode.isTransit, leg.start != nil else { return }
        #if DEBUG
        if Demo.offline { return }
        #endif
        if !force, let at = fetched[leg.id], Date.now.timeIntervalSince(at) < Self.freshFor { return }
        fetched[leg.id] = .now
        if boards[leg.id]?.value == nil { boards[leg.id] = .loading }
        do {
            boards[leg.id] = .loaded(try await provider.board(for: leg))
        } catch {
            // Keep a board we already have rather than blanking it on a blip.
            if boards[leg.id]?.value == nil { boards[leg.id] = .failed(Self.describe(error)) }
            fetched[leg.id] = nil
        }
    }

    func loadNearby(_ coordinate: Coordinate, force: Bool = false) async {
        #if DEBUG
        if Demo.offline { return }
        #endif
        if !force, let last = nearbyFetched, Date.now.timeIntervalSince(last.at) < Self.freshFor,
           last.around.distance(to: coordinate) < 150 { return }
        nearbyFetched = (.now, coordinate)
        if nearby?.value == nil { nearby = .loading }
        do {
            nearby = .loaded(try await provider.nearby(coordinate))
        } catch {
            if nearby?.value == nil { nearby = .failed(Self.describe(error)) }
            nearbyFetched = nil
        }
    }

    private static func describe(_ error: any Error) -> String {
        if (error as? URLError)?.code == .notConnectedToInternet { return "You're offline" }
        if let error = error as? ProviderError { return error.errorDescription ?? "Couldn't load departures" }
        return "Couldn't load departures"
    }
}
