import Foundation

/// Folds every source's copy of the same journey into one, keeping all their badges.
///
/// A merged journey is one departure. Copies are taken in `mergeOrder`, and each joins the first
/// merged journey whose leading copy boards within 4 minutes of its own first boarding; otherwise
/// it leads a new one. Copies are compared with the leader, never with each other, so departures
/// 3 minutes apart don't chain into one: 12:01, 12:04 and 12:07 on the same lines become 12:01
/// (with 12:04 folded in) and 12:07, and the ranker's card then offers 12:07 as "Or leave at".
/// A merged journey's times, legs and platforms are its leader's (the highest-priority source's
/// earliest copy); its fare is the first one in merge order; its sources are all of them.
/// The Android engine's `JourneyMerger` follows the same rule.
enum JourneyMerger {
    /// Same lines boarding within this window of the leading copy are the same journey.
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
    /// arrival, then lines, variant, fare, number of legs and, last, every leg's details, so two
    /// copies differing only in a platform still come out the same way. The Android engine uses the
    /// same order, comparing text as Swift's `<` does.
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
        if a.legs.count != b.legs.count { return a.legs.count < b.legs.count }
        return legKey(a) < legKey(b)
    }

    /// Every leg's details in one string, the last tie-break.
    static func legKey(_ journey: Journey) -> String {
        journey.legs.map { l in
            [l.mode.rawValue, l.line ?? "", l.fromName, l.toName, l.platform ?? "", Time.isoString(l.departure),
             Time.isoString(l.arrival), l.stopCount.map(String.init) ?? ""].joined(separator: "|")
        }.joined(separator: ";")
    }

    static func isSame(_ a: Journey, _ b: Journey) -> Bool {
        a.routeSignature == b.routeSignature && abs(a.firstBoarding.timeIntervalSince(b.firstBoarding)) <= sameDepartureWindow
    }
}
