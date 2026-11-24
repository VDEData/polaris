package polaris.engine

import java.time.Instant
import kotlin.math.abs

// Ported from Polaris/Engine/JourneyMerger.swift and Polaris/Engine/JourneyRanker.swift.

/** Folds every source's copy of the same journey into one, keeping all their badges. */
object JourneyMerger {
    /** Same lines boarding within this window are the same journey. */
    const val sameDepartureWindow: Double = 4 * 60.0

    fun merge(journeys: List<Journey>): List<Journey> {
        val merged = mutableListOf<Journey>()
        // A stable sort, as Swift's is: within one source, the order it answered in.
        val ordered = journeys.sortedBy { it.sources.firstOrNull()?.provider?.mergePriority ?: 9 }
        for (journey in ordered) {
            val i = merged.indexOfFirst { isSame(it, journey) }
            if (i >= 0) {
                val kept = merged[i]
                merged[i] = kept.copy(
                    sources = kept.sources + journey.sources.filter { it !in kept.sources }.distinct(),
                    fare = kept.fare ?: journey.fare,
                    hasDisruption = kept.hasDisruption || journey.hasDisruption,
                )
            } else {
                merged += journey
            }
        }
        return merged
    }

    fun isSame(a: Journey, b: Journey): Boolean =
        a.routeSignature == b.routeSignature && abs(a.firstBoarding.seconds - b.firstBoarding.seconds) <= sameDepartureWindow
}

/**
 * How the list is ordered. Each order weighs time against changes and walking
 * rather than sorting on one key, so a couple of minutes less walking never
 * lifts a route above one that's an hour quicker.
 */
@Suppress("EnumEntryName")
enum class SortOrder(val label: String, val changeWeight: Double, val walkMinuteWeight: Double) {
    // Minutes of travel a change is worth, and what each minute on foot costs on top of the time it takes.
    best("Best", 5.0, 1.0),
    fastest("Fastest", 1.0, 0.25),
    fewestChanges("Fewest changes", 25.0, 0.5),
    leastWalking("Least walking", 4.0, 3.0),
}

/** Modes grouped the way people think about avoiding them. */
@Suppress("EnumEntryName")
enum class ModeGroup(val label: String) {
    tube("Underground"), rail("Trains"), light("DLR and trams"), bus("Buses and coaches"), water("Boats and cable car");

    val detail: String? get() = if (this == rail) "National Rail, Overground, Elizabeth line" else null

    companion object {
        fun of(mode: LegMode): ModeGroup? = when (mode) {
            LegMode.tube -> tube
            LegMode.rail, LegMode.overground, LegMode.elizabeth -> rail
            LegMode.dlr, LegMode.tram -> light
            LegMode.bus, LegMode.coach -> bus
            LegMode.ferry, LegMode.cableCar -> water
            LegMode.walk, LegMode.cycle, LegMode.other -> null
        }
    }
}

data class RouteFilter(
    val maxChanges: Int? = null,
    val maxWalkMinutes: Int? = null,
    val avoided: Set<ModeGroup> = emptySet(),
) {
    val activeCount: Int get() = listOf(maxChanges != null, maxWalkMinutes != null, avoided.isNotEmpty()).count { it }

    fun allows(journey: Journey): Boolean {
        if (maxChanges != null && journey.changes > maxChanges) return false
        // Half a minute's grace: sources round walks differently.
        if (maxWalkMinutes != null && journey.walkingTime > (maxWalkMinutes * 60 + 30).toDouble()) return false
        return journey.transitLegs.none { leg -> ModeGroup.of(leg.mode)?.let { it in avoided } ?: false }
    }
}

/**
 * One card on the list: the best-placed departure of a sequence of lines,
 * with the other departures on the same lines folded into it.
 */
data class RouteOption(
    val journey: Journey,
    /** When else you could leave and take the same lines. */
    val otherDepartures: List<Instant> = emptyList(),
    /** Every source that found these lines, at any departure. */
    val providers: List<ProviderID> = emptyList(),
) {
    val id: String get() = journey.id
}

object JourneyRanker {
    data class Ranked(
        /** Worth a look, in order. */
        val top: List<RouteOption> = emptyList(),
        /** Beaten outright by another route, far behind the best, or through a reported closure. */
        val more: List<RouteOption> = emptyList(),
        /** Routes the filters took out. */
        val hidden: Int = 0,
    )

    /** Sources disagree by a minute or so; differences this small don't count. */
    const val timeTolerance: Double = 60.0
    const val walkTolerance: Double = 120.0

    fun rank(journeys: List<Journey>, arriveBy: Boolean, sort: SortOrder = SortOrder.best, filter: RouteFilter = RouteFilter()): Ranked {
        val allowed = journeys.filter(filter::allows)
        val hidden = (journeys.map { it.routeSignature }.toSet() - allowed.map { it.routeSignature }.toSet()).size

        val cost = { journey: Journey -> score(journey, arriveBy, sort) }
        val precedes = { a: Journey, b: Journey ->
            when {
                // A route through a reported closure goes after every one that isn't.
                a.isBlocked != b.isBlocked -> !a.isBlocked
                cost(a) != cost(b) -> cost(a) < cost(b)
                a.departure != b.departure -> a.departure > b.departure
                else -> a.routeSignature < b.routeSignature
            }
        }
        val order = Comparator<Journey> { a, b -> if (precedes(a, b)) -1 else if (precedes(b, a)) 1 else 0 }

        val options = allowed.groupBy { it.routeSignature }.values
            .map { group ->
                val sorted = group.sortedWith(order)
                val lead = sorted[0]
                // Leave times a minute or so apart are the same train, timed by different sources.
                val others = mutableListOf<Instant>()
                for (departure in sorted.drop(1).map { it.departure }.sorted()) {
                    if ((listOf(lead.departure) + others).all { abs(it.seconds - departure.seconds) >= 120 }) others += departure
                }
                RouteOption(lead, others, ProviderID.entries.filter { p -> group.any { p in it.providers } })
            }
            .sortedWith { a, b -> order.compare(a.journey, b.journey) }

        // A route earns a card if it's close to the best on the balanced score,
        // or on the chosen order's own terms. Switching order can bring routes
        // up (a direct one for "Fewest changes") but never hides the balanced picks.
        // Anything beaten outright on time, changes and walking stays under "more".
        val orders = if (sort == SortOrder.best) listOf(SortOrder.best) else listOf(SortOrder.best, sort)
        val thresholds = orders.mapNotNull { o ->
            val lead = options.filter { !it.journey.isBlocked }.minByOrNull { score(it.journey, arriveBy, o) } ?: return@mapNotNull null
            o to score(lead.journey, arriveBy, o) + maxOf(10.0, lead.journey.duration / 60 * 0.35)
        }
        val top = mutableListOf<RouteOption>()
        val more = mutableListOf<RouteOption>()
        for (option in options) {
            // Only a route that should work can beat another; one through a closure never leads.
            val beaten = options.any { !it.journey.isBlocked && dominates(it.journey, option.journey, arriveBy) }
            val close = thresholds.any { (o, limit) -> score(option.journey, arriveBy, o) <= limit }
            if (option.journey.isBlocked || beaten || !close) more += option else top += option
        }
        return Ranked(top, more, hidden)
    }

    /**
     * Generalised cost in minutes: when you arrive (or, arriving by a deadline,
     * how early you have to leave) plus what the changes and walking are worth.
     * Times are seconds since 2001, as in Swift, so near-ties break the same way on both.
     */
    fun score(journey: Journey, arriveBy: Boolean, sort: SortOrder): Double {
        val time = if (arriveBy) -journey.departure.seconds else journey.arrival.seconds
        return time / 60 + sort.changeWeight * journey.changes.toDouble() + sort.walkMinuteWeight * journey.walkingTime / 60
    }

    /**
     * `a` is no worse than `b` on time, changes and walking, allowing for noise
     * between sources, and clearly better on at least one.
     */
    fun dominates(a: Journey, b: Journey, arriveBy: Boolean): Boolean {
        if (a.id == b.id) return false
        val timeGain = if (arriveBy) a.departure.seconds - b.departure.seconds else b.arrival.seconds - a.arrival.seconds
        val walkGain = b.walkingTime - a.walkingTime
        val noWorse = timeGain >= -timeTolerance && a.changes <= b.changes && walkGain >= -walkTolerance
        val better = timeGain > timeTolerance || a.changes < b.changes || walkGain > walkTolerance
        return noWorse && better
    }
}
