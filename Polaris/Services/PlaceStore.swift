import Foundation
import Observation

/// Home, Work and recent destinations, kept in UserDefaults.
@MainActor @Observable
final class PlaceStore {
    var home: Place? { didSet { save(home, "home") } }
    var work: Place? { didSet { save(work, "work") } }
    private(set) var recents: [Place] { didSet { save(recents, "recents") } }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        home = Self.load("home", defaults)
        work = Self.load("work", defaults)
        recents = Self.load("recents", defaults) ?? []
    }

    func remember(_ place: Place) {
        guard !place.isCurrentLocation else { return }
        recents.removeAll { $0.name == place.name && $0.subtitle == place.subtitle }
        recents.insert(place, at: 0)
        recents = Array(recents.prefix(8))
    }

    func forget(_ place: Place) { recents.removeAll { $0.id == place.id } }

    private func save<T: Encodable>(_ value: T, _ key: String) {
        defaults.set(try? JSONEncoder().encode(value), forKey: "places.\(key)")
    }

    private static func load<T: Decodable>(_ key: String, _ defaults: UserDefaults) -> T? {
        defaults.data(forKey: "places.\(key)").flatMap { try? JSONDecoder().decode(T.self, from: $0) }
    }
}
