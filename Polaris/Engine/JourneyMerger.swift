import Foundation

/// Folds every source's copy of the same journey into one, keeping all their badges.
enum JourneyMerger {
    /// Same lines boarding within this window are the same journey.
    static let sameDepartureWindow: TimeInterval = 4 * 60

    static func merge(_ journeys: [Journey]) -> [Journey] {
        var merged: [Journey] = []
        let ordered = journeys.sorted(by: mergeOrder)
        for journey in ordered {
            if let i = merged.firstIndex(where: { isSame($0, journey) }) {
                for tag in journey.sources where !merged[i].sources.contains(tag) { merged[i].sources.append(tag) }
                merged[i].fare = merged[i].fare ?? journey.fare
                merged[i].hasDisruption = merged[i].hasDisruption || journey.hasDisruption
            } else {
                merged.append(journey)
            }
        }
        return merged
    }

    /// The order copies are taken in, so the result doesn't depend on which source or deep-search
    /// variant answered first: source by `mergePriority`, then the earliest boarding, departure and
    /// arrival, then lines, variant, fare and number of legs. The Android engine uses the same order.
    static func mergeOrder(_ a: Journey, _ b: Journey) -> Bool {
        let pa = a.sources.first?.provider.mergePriority ?? 9, pb = b.sources.first?.provider.mergePriority ?? 9
        if pa != pb { return pa < pb }
        if a.firstBoarding != b.firstBoarding { return a.firstBoarding < b.firstBoarding }
        if a.departure != b.departure { return a.departure < b.departure }
        if a.arrival != b.arrival { return a.arrival < b.arrival }
        if a.routeSignature != b.routeSignature { return a.routeSignature < b.routeSignature }
        let va = a.sources.first?.variant ?? "", vb = b.sources.first?.variant ?? ""
        if va != vb { return va < vb }
        if (a.fare ?? "") != (b.fare ?? "") { return (a.fare ?? "") < (b.fare ?? "") }
        return a.legs.count < b.legs.count
    }

    static func isSame(_ a: Journey, _ b: Journey) -> Bool {
        a.routeSignature == b.routeSignature && abs(a.firstBoarding.timeIntervalSince(b.firstBoarding)) <= sameDepartureWindow
    }
}
