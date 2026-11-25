package polaris.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.Instant

/**
 * The models as JSON, for keeping what a screen shows across the app being stopped in the
 * background (Android may end the process and recreate it later). Leg paths make a set of
 * results too big for Android's saved-state bundle, so the app writes one of these to a file
 * and keeps only its name in the bundle. Reading one back sends nothing: the person sees the
 * routes they were looking at, not a new search.
 *
 * The form is this app's own, not a wire format: a file this version can't read is ignored.
 */
object Snapshot {
    /** One search as the results page last showed it. */
    data class Search(
        val query: JourneyQuery,
        val statuses: Map<ProviderID, ProviderStatus>,
        val journeys: List<Journey>,
        /** TfL's network status was checked (its notices are already on the journeys). */
        val networkChecked: Boolean,
        val savedAt: Instant,
    )

    fun encode(search: Search): String = HTTP.json.encodeToString(SearchDTO.of(search))

    /** Null for anything this version can't read. */
    fun decodeSearch(text: String): Search? = runCatching { HTTP.json.decodeFromString<SearchDTO>(text).model() }.getOrNull()

    fun encode(place: Place): String = HTTP.json.encodeToString(PlaceDTO.of(place))
    fun decodePlace(text: String): Place? = runCatching { HTTP.json.decodeFromString<PlaceDTO>(text).model() }.getOrNull()

    fun encode(places: List<Place>): String = HTTP.json.encodeToString(places.map(PlaceDTO::of))
    fun decodePlaces(text: String): List<Place>? = runCatching { HTTP.json.decodeFromString<List<PlaceDTO>>(text).map { it.model() } }.getOrNull()

    fun encode(query: JourneyQuery): String = HTTP.json.encodeToString(QueryDTO.of(query))
    fun decodeQuery(text: String): JourneyQuery? = runCatching { HTTP.json.decodeFromString<QueryDTO>(text).model() }.getOrNull()

    @Serializable
    internal data class CoordinateDTO(val latitude: Double, val longitude: Double)

    /** The shape Swift's `Place` encodes to, so the two platforms' saved places read alike. */
    @Serializable
    internal data class PlaceDTO(
        val id: String,
        val name: String,
        val subtitle: String? = null,
        val coordinate: CoordinateDTO,
        val isCurrentLocation: Boolean = false,
    ) {
        fun model() = Place(id, name, subtitle, Coordinate(coordinate.latitude, coordinate.longitude), isCurrentLocation)

        companion object {
            fun of(p: Place) = PlaceDTO(p.id, p.name, p.subtitle, CoordinateDTO(p.coordinate.latitude, p.coordinate.longitude), p.isCurrentLocation)
        }
    }

    @Serializable
    internal data class QueryDTO(
        val origin: PlaceDTO,
        val destination: PlaceDTO,
        /** "now", "depart" or "arrive". */
        val time: String,
        val at: String? = null,
    ) {
        fun model(): JourneyQuery {
            val date = at?.let(Time::iso)
            val mode = when (time) {
                "depart" -> date?.let(TimeMode::DepartAt)
                "arrive" -> date?.let(TimeMode::ArriveBy)
                else -> TimeMode.LeaveNow
            } ?: TimeMode.LeaveNow
            return JourneyQuery(origin.model(), destination.model(), mode)
        }

        companion object {
            fun of(q: JourneyQuery) = QueryDTO(
                PlaceDTO.of(q.origin), PlaceDTO.of(q.destination),
                when (q.time) {
                    TimeMode.LeaveNow -> "now"
                    is TimeMode.DepartAt -> "depart"
                    is TimeMode.ArriveBy -> "arrive"
                },
                (q.time as? TimeMode.DepartAt)?.at?.let(Time::isoString) ?: (q.time as? TimeMode.ArriveBy)?.at?.let(Time::isoString),
            )
        }
    }

    @Serializable
    internal data class LegDTO(
        val id: String,
        val mode: String,
        val line: String? = null,
        val color: String? = null,
        val headsign: String? = null,
        val from: String,
        val to: String,
        val departure: Long,
        val arrival: Long,
        val stops: Int? = null,
        val metres: Double? = null,
        /** Latitude, longitude, latitude, longitude, ... : half the size of a list of objects. */
        val path: List<Double> = emptyList(),
        val realtime: Boolean = false,
        val platform: String? = null,
        val calls: List<String> = emptyList(),
    ) {
        fun model() = Leg(
            id = id, mode = LegMode.entries.firstOrNull { it.name == mode } ?: LegMode.other, line = line,
            lineColor = RGB.hex(color), headsign = headsign, fromName = from, toName = to,
            departure = Instant.ofEpochMilli(departure), arrival = Instant.ofEpochMilli(arrival),
            stopCount = stops, distanceMeters = metres,
            path = path.chunked(2).filter { it.size == 2 }.map { Coordinate(it[0], it[1]) },
            isRealtime = realtime, platform = platform, intermediateStops = calls,
        )

        companion object {
            fun of(l: Leg) = LegDTO(
                l.id, l.mode.name, l.line, l.lineColor?.let(JourneyMap::hex), l.headsign, l.fromName, l.toName,
                l.departure.toEpochMilli(), l.arrival.toEpochMilli(), l.stopCount, l.distanceMeters,
                l.path.flatMap { listOf(it.latitude, it.longitude) }, l.isRealtime, l.platform, l.intermediateStops,
            )
        }
    }

    @Serializable
    internal data class NoticeDTO(val title: String, val detail: String, val severity: String, val source: String, val leg: String)

    @Serializable
    internal data class JourneyDTO(
        val id: String,
        val departure: Long,
        val arrival: Long,
        val legs: List<LegDTO>,
        val sources: List<List<String>>,
        val fare: String? = null,
        val disrupted: Boolean = false,
        val notices: List<NoticeDTO> = emptyList(),
    ) {
        fun model() = Journey(
            id = id, departure = Instant.ofEpochMilli(departure), arrival = Instant.ofEpochMilli(arrival),
            legs = legs.map { it.model() },
            sources = sources.mapNotNull { s -> provider(s.getOrNull(0))?.let { SourceTag(it, s.getOrElse(1) { "" }) } },
            fare = fare, hasDisruption = disrupted,
            notices = notices.mapNotNull { n ->
                val severity = ServiceNotice.Severity.entries.firstOrNull { it.name == n.severity } ?: return@mapNotNull null
                ServiceNotice(n.title, n.detail, severity, provider(n.source) ?: ProviderID.tfl, n.leg)
            },
        )

        companion object {
            fun of(j: Journey) = JourneyDTO(
                j.id, j.departure.toEpochMilli(), j.arrival.toEpochMilli(), j.legs.map(LegDTO::of),
                j.sources.map { listOf(it.provider.name, it.variant) }, j.fare, j.hasDisruption,
                j.notices.map { NoticeDTO(it.title, it.detail, it.severity.name, it.source.name, it.leg) },
            )
        }
    }

    @Serializable
    internal data class StatusDTO(val kind: String, val count: Int = 0, val reason: String = "")

    @Serializable
    internal data class SearchDTO(
        val version: Int = 1,
        val query: QueryDTO,
        val statuses: Map<String, StatusDTO>,
        val journeys: List<JourneyDTO>,
        val networkChecked: Boolean,
        val savedAt: Long,
    ) {
        fun model(): Search? {
            if (version != 1) return null
            return Search(
                query = query.model(),
                statuses = statuses.mapNotNull { (name, s) ->
                    val id = provider(name) ?: return@mapNotNull null
                    id to when (s.kind) {
                        "done" -> ProviderStatus.Done(s.count)
                        "failed" -> ProviderStatus.Failed(s.reason)
                        "off" -> ProviderStatus.Off(s.reason)
                        // A source still asking when the app was stopped never answered this copy.
                        else -> ProviderStatus.Failed("Interrupted")
                    }
                }.toMap(),
                journeys = journeys.map { it.model() },
                networkChecked = networkChecked,
                savedAt = Instant.ofEpochMilli(savedAt),
            )
        }

        companion object {
            fun of(s: Search) = SearchDTO(
                query = QueryDTO.of(s.query),
                statuses = s.statuses.map { (id, status) ->
                    id.name to when (status) {
                        is ProviderStatus.Done -> StatusDTO("done", count = status.count)
                        is ProviderStatus.Failed -> StatusDTO("failed", reason = status.reason)
                        is ProviderStatus.Off -> StatusDTO("off", reason = status.reason)
                        ProviderStatus.Loading -> StatusDTO("loading")
                    }
                }.toMap(),
                journeys = s.journeys.map(JourneyDTO::of),
                networkChecked = s.networkChecked,
                savedAt = s.savedAt.toEpochMilli(),
            )
        }
    }

    private fun provider(name: String?) = ProviderID.entries.firstOrNull { it.name == name }
}
