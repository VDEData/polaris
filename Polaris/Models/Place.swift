import CoreLocation
import Foundation

struct Coordinate: Codable, Hashable, Sendable {
    var latitude: Double
    var longitude: Double

    var clLocation: CLLocationCoordinate2D { .init(latitude: latitude, longitude: longitude) }
    /// "lat,lon", the form every provider and deep link takes, always as plain decimals.
    var pair: String { "\(Self.plain(latitude)),\(Self.plain(longitude))" }

    /// Swift writes a longitude just off the Greenwich meridian as "-2e-05"; providers want
    /// "-0.00002". Same digits, decimal point moved. The Android engine does the same.
    static func plain(_ value: Double) -> String {
        let text = "\(value)"
        guard let e = text.firstIndex(where: { $0 == "e" || $0 == "E" }) else { return text }
        let exponent = Int(text[text.index(after: e)...]) ?? 0
        var mantissa = String(text[..<e])
        let negative = mantissa.hasPrefix("-")
        if negative { mantissa.removeFirst() }
        let parts = mantissa.split(separator: ".", omittingEmptySubsequences: false)
        let whole = String(parts[0]), fraction = parts.count > 1 ? String(parts[1]) : ""
        var digits = whole + fraction
        var point = whole.count + exponent
        if point <= 0 {
            digits = String(repeating: "0", count: 1 - point) + digits
            point = 1
        } else if point > digits.count {
            digits += String(repeating: "0", count: point - digits.count)
        }
        var after = String(digits.dropFirst(point))
        while after.hasSuffix("0") { after.removeLast() }
        return (negative ? "-" : "") + digits.prefix(point) + "." + (after.isEmpty ? "0" : after)
    }

    /// Metres to another point, along the ground.
    func distance(to other: Coordinate) -> Double {
        CLLocation(latitude: latitude, longitude: longitude)
            .distance(from: CLLocation(latitude: other.latitude, longitude: other.longitude))
    }
}

extension Coordinate {
    init(_ c: CLLocationCoordinate2D) { self.init(latitude: c.latitude, longitude: c.longitude) }
}

struct Place: Codable, Hashable, Sendable, Identifiable {
    var id = UUID()
    var name: String
    var subtitle: String?
    var coordinate: Coordinate
    /// Resolved from GPS at search time rather than picked from search.
    var isCurrentLocation = false

    static func currentLocation(_ coordinate: Coordinate) -> Place {
        Place(name: "Current Location", coordinate: coordinate, isCurrentLocation: true)
    }
}

enum TimeMode: Codable, Hashable, Sendable {
    case leaveNow
    case departAt(Date)
    case arriveBy(Date)

    var date: Date {
        switch self {
        case .leaveNow: .now
        case .departAt(let d), .arriveBy(let d): d
        }
    }

    var isArriveBy: Bool {
        if case .arriveBy = self { true } else { false }
    }
}

struct JourneyQuery: Codable, Hashable, Sendable {
    var origin: Place
    var destination: Place
    var time: TimeMode
}
