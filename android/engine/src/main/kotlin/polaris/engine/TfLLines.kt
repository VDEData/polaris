package polaris.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/**
 * TfL's lines and the stations along each, for choosing what to be alerted about. One request
 * for the list of lines and one per line opened, kept for the session. Ported from
 * Polaris/Providers/TfLLines.swift.
 */
class TfLLines(private val http: HTTP) {
    data class Line(
        val id: String,
        /** TfL's own: "District", "Elizabeth line", "Southeastern". */
        val tflName: String,
        val mode: String,
    ) {
        val legMode: LegMode get() = NetworkStatus.mode(mode)?.refined(tflName, null) ?: LegMode.rail
        /** As people say it: "District line", "Mildmay line", "DLR". */
        val name: String get() = if (mode in setOf("tube", "overground") && !tflName.lowercase().endsWith("line")) "$tflName line" else tflName
        val alertLine: RouteAlert.Line get() = RouteAlert.Line(id, name)
    }

    /**
     * One station on a line, with every id TfL uses for it there (the Elizabeth line's Liverpool
     * Street is both its own platforms and the main-line station).
     */
    data class Station(
        val ids: List<String>,
        val name: String,
        /** Every spelling seen, for matching other sources' names. */
        val names: List<String>,
    ) {
        val id: String get() = ids.joinToString("|")
        val alertStation: RouteAlert.Station get() = RouteAlert.Station(id, name)
    }

    data class Route(
        val stations: List<Station>,
        /** Each of the line's routes as station ids, in order. */
        val sequences: List<List<String>>,
    ) {
        fun station(named: String): Station? {
            val key = NetworkStatus.stationKey(named)
            return stations.firstOrNull { station -> station.names.any { NetworkStatus.stationKey(it) == key } }
        }

        fun stationWithId(id: String): Station? = stations.firstOrNull { it.id == id }

        /** The stations between two, along the shortest of the line's routes that has both. */
        fun stretch(from: Station, to: Station): List<Station>? {
            val byId = HashMap<String, Station>()
            stations.forEach { station -> station.ids.forEach { byId.putIfAbsent(it, station) } }
            var best: List<Station>? = null
            for (sequence in sequences) {
                val a = sequence.indexOfFirst { it in from.ids }
                val b = sequence.indexOfFirst { it in to.ids }
                if (a < 0 || b < 0 || a == b) continue
                val ids = if (a < b) sequence.subList(a, b + 1) else sequence.subList(b, a + 1).reversed()
                val run = mutableListOf<Station>()
                for (id in ids) byId[id]?.let { if (run.lastOrNull() != it) run += it }
                if (best == null || run.size < best.size) best = run
            }
            return best
        }

        /** Whether one of the line's routes runs between the two (else an alert for them can only be for the whole line). */
        fun joins(from: Station, to: Station): Boolean = stretch(from, to) != null

        fun segment(line: Line, from: Station?, to: Station?): RouteAlert.Segment {
            val run = if (from != null && to != null) stretch(from, to) else null
            if (from == null || to == null || run == null) return RouteAlert.Segment(line.alertLine, line.mode)
            return RouteAlert.Segment(line.alertLine, line.mode, from.alertStation, to.alertStation, run.map { it.id })
        }
    }

    private val mutex = Mutex()
    private var cachedLines: List<Line>? = null
    private val cachedRoutes = HashMap<String, Route>()

    suspend fun lines(): List<Line> {
        mutex.withLock { cachedLines }?.let { return it }
        val lines = parseLines(http.data(HttpRequest("https://api.tfl.gov.uk/Line/Mode/${NetworkStatus.modes}")))
        mutex.withLock { cachedLines = lines }
        return lines
    }

    suspend fun route(lineId: String): Route {
        // Line ids are TfL's own, but the id goes into a path: anything else is refused.
        if (!lineId.matches(Regex("[A-Za-z0-9-]+"))) throw ProviderError.BadResponse
        mutex.withLock { cachedRoutes[lineId] }?.let { return it }
        val route = parseRoute(http.data(HttpRequest("https://api.tfl.gov.uk/Line/$lineId/Route/Sequence/all")))
        mutex.withLock { cachedRoutes[lineId] = route }
        return route
    }

    /** An alert for the TfL lines in a journey, each from where you get on to where you get off. */
    suspend fun draft(journey: Journey, zone: ZoneId = ZoneId.systemDefault()): RouteAlert? {
        val lines = lines()
        val segments = mutableListOf<RouteAlert.Segment>()
        for (leg in journey.transitLegs) {
            val line = line(leg, lines) ?: continue
            val route = route(line.id)
            val segment = route.segment(line, route.station(leg.fromName), route.station(leg.toName))
            if (segment !in segments) segments += segment
        }
        if (segments.isEmpty()) return null
        val (days, window) = whenFor(journey.departure, journey.arrival, zone)
        return RouteAlert(segments = segments, days = days, windows = listOf(window))
    }

    companion object {
        /** The mode groups a line is picked from, in the order shown. */
        val GROUPS: List<Pair<String, String>> = listOf(
            "tube" to "Underground", "elizabeth-line" to "Elizabeth line", "overground" to "Overground",
            "dlr" to "DLR", "tram" to "Tram", "national-rail" to "National Rail",
        )

        @Serializable private data class LineDTO(val id: String, val name: String, val modeName: String)

        fun parseLines(data: ByteArray): List<Line> = HTTP.decode<List<LineDTO>>(data).map { Line(it.id, it.name, it.modeName) }

        @Serializable private data class Stop(val id: String, val name: String, val topMostParentId: String? = null)
        @Serializable private data class Sequence(val stopPoint: List<Stop> = emptyList())
        @Serializable private data class Ordered(val naptanIds: List<String> = emptyList())
        @Serializable private data class RouteDTO(
            val stations: List<Stop>? = null,
            val stopPointSequences: List<Sequence>? = null,
            val orderedLineRoutes: List<Ordered>? = null,
        )

        fun parseRoute(data: ByteArray): Route {
            val dto = HTTP.decode<RouteDTO>(data)
            val sequences = (dto.orderedLineRoutes ?: emptyList()).map { it.naptanIds }.filter { it.isNotEmpty() }
            val onRoutes = sequences.flatten().toSet()
            val stops = (dto.stopPointSequences ?: emptyList()).flatMap { it.stopPoint } + (dto.stations ?: emptyList())

            // Group by TfL's hub, so one station's several ids are one choice. A stop's hub is
            // taken from wherever TfL gives it: the same stop can come with its parent in one
            // list and without in another, and must still be one station, not two with one id.
            val parentOf = HashMap<String, String>()
            for (stop in stops) stop.topMostParentId?.let { parentOf.putIfAbsent(stop.id, it) }
            val groups = LinkedHashMap<String, MutableList<Stop>>()
            // The station list includes the hubs themselves ("HUBLST"), which no disruption names.
            for (stop in stops) {
                if (stop.id.startsWith("HUB")) continue
                val group = groups.getOrPut(parentOf[stop.id] ?: stop.id) { mutableListOf() }
                if (group.none { it.id == stop.id }) group += stop
            }
            val stations = groups.entries.mapNotNull { (key, group) ->
                // A hub's ids not on any of this line's routes only matter as aliases of one that is.
                if (group.none { it.id in onRoutes }) return@mapNotNull null
                val ids = group.map { it.id }.filter { it in onRoutes } + group.map { it.id }.filter { it !in onRoutes }
                val names = group.map { TfLProvider.cleanName(it.name) }.distinct()
                // The shortest spelling: "Liverpool Street" over "London Liverpool Street".
                Station(ids, names.minByOrNull { it.length } ?: key, names)
            }
            // One row a station: ids are what the picker's list is keyed by.
            return Route(stations.distinctBy { it.id }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }), sequences)
        }

        /** TfL's line for a ride another source found, by name ("Elizabeth line", "Central", "Mildmay"). */
        fun line(leg: Leg, lines: List<Line>): Line? = when (leg.mode.refined(leg.line, null)) {
            LegMode.dlr -> lines.firstOrNull { it.id == "dlr" }
            LegMode.tram -> lines.firstOrNull { it.id == "tram" }
            LegMode.tube, LegMode.elizabeth, LegMode.overground, LegMode.rail -> leg.line?.let { name ->
                val key = Journey.normalise(name)
                lines.firstOrNull { Journey.normalise(it.tflName) == key }
            }
            else -> null
        }

        /** Whether any ride in [journey] could be on a TfL line (so the journey page offers an alert). */
        fun mayHaveLines(journey: Journey): Boolean = journey.transitLegs.any {
            it.mode.refined(it.line, null) in setOf(LegMode.dlr, LegMode.tram, LegMode.tube, LegMode.elizabeth, LegMode.overground, LegMode.rail)
        }

        /**
         * The days and time a journey suggests: weekdays or the weekend, as it falls, from an hour
         * before you'd leave to when you'd arrive, in quarter hours.
         */
        fun whenFor(departure: Instant, arrival: Instant, zone: ZoneId): Pair<List<Int>, RouteAlert.TimeWindow> {
            val leaves = departure.atZone(zone)
            val arrives = arrival.atZone(zone)
            val days = if (leaves.dayOfWeek.value <= 5) (1..5).toList() else listOf(6, 7)
            val start = maxOf(0, (leaves.hour * 60 + leaves.minute - 60) / 15 * 15)
            val end = minOf(23 * 60 + 45, (arrives.hour * 60 + arrives.minute + 14) / 15 * 15)
            return days to RouteAlert.TimeWindow(start, if (end > start) end else minOf(start + 120, 23 * 60 + 45))
        }
    }
}
