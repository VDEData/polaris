package polaris.engine

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * TfL Unified API Journey Planner: London's official planner, including National
 * Rail in and around London. Works without a key at 50 requests/minute.
 * Ported from Polaris/Providers/TfLProvider.swift.
 */
class TfLProvider(private val http: HTTP, val appKey: String? = null) : JourneyProvider {
    override val id = ProviderID.tfl

    data class Variant(val preference: String, val label: String)

    override suspend fun journeys(query: JourneyQuery, deep: Boolean): List<Journey> =
        fanOut(if (deep) variants else listOf(variants[0])) { variant ->
            parse(http.data(request(query, variant.preference)), variant.label)
        }

    fun request(query: JourneyQuery, preference: String): HttpRequest {
        val items = mutableListOf("journeyPreference" to preference)
        if (query.time != TimeMode.LeaveNow) {
            val c = Time.londonComponents(query.time.date)
            items += "date" to String.format(Locale.ROOT, "%04d%02d%02d", c.year, c.monthValue, c.dayOfMonth)
            items += "time" to String.format(Locale.ROOT, "%02d%02d", c.hour, c.minute)
            items += "timeIs" to if (query.time.isArriveBy) "Arriving" else "Departing"
        }
        if (!appKey.isNullOrEmpty()) items += "app_key" to appKey
        return HttpRequest(
            HTTP.url("https://api.tfl.gov.uk/Journey/JourneyResults/${query.origin.coordinate.pair}/to/${query.destination.coordinate.pair}", items),
        )
    }

    companion object {
        val variants = listOf(
            Variant("leasttime", "Fastest"),
            Variant("leastinterchange", "Fewer changes"),
            Variant("leastwalking", "Less walking"),
        )

        fun parse(data: ByteArray, variant: String): List<Journey> {
            val response = HTTP.decode<Response>(data)
            val journeys = response.journeys
            if (journeys.isNullOrEmpty()) throw ProviderError.NoRoutes
            return journeys.mapNotNull { j ->
                val start = Time.londonLocal(j.startDateTime) ?: return@mapNotNull null
                val end = Time.londonLocal(j.arrivalDateTime) ?: return@mapNotNull null
                Journey(
                    departure = start, arrival = end, legs = j.legs.mapNotNull(::leg),
                    sources = listOf(SourceTag(ProviderID.tfl, variant)),
                    fare = j.fare?.totalCost?.let { if (it > 0) String.format(Locale.ROOT, "£%.2f", it / 100.0) else null },
                    hasDisruption = j.legs.any { it.isDisrupted == true },
                )
            }
        }

        private fun leg(l: Response.Leg): Leg? {
            val dep = Time.londonLocal(l.departureTime) ?: return null
            val arr = Time.londonLocal(l.arrivalTime) ?: return null
            val option = l.routeOptions?.firstOrNull()
            val lineID = option?.lineIdentifier?.id
            val rawLine = option?.name?.takeIf { it.isNotEmpty() }
            val mode = mode(l.mode?.id).refined(rawLine, null)
            val line = if (mode.isTransit) rawLine else null
            val path = l.path?.lineString?.let { runCatching { HTTP.json.decodeFromString<List<List<Double>>>(it) }.getOrNull() } ?: emptyList()
            val realtime = l.scheduledDepartureTime != null && l.scheduledDepartureTime != l.departureTime
            return Leg(
                mode = mode, line = line,
                lineColor = if (mode.isTransit) LineColors.color(lineID ?: line, mode) else null,
                headsign = option?.directions?.firstOrNull(),
                fromName = cleanName(l.departurePoint?.commonName),
                toName = cleanName(l.arrivalPoint?.commonName),
                departure = dep, arrival = arr,
                stopCount = if (mode.isTransit) l.path?.stopPoints?.size else null,
                distanceMeters = l.distance,
                path = path.mapNotNull { if (it.size == 2) Coordinate(it[0], it[1]) else null },
                isRealtime = realtime,
                platform = l.departurePoint?.platformName?.takeIf { it.isNotEmpty() },
                // stopPoints ends with the alighting stop.
                intermediateStops = if (mode.isTransit) (l.path?.stopPoints ?: emptyList()).mapNotNull { p -> p.name?.let(::cleanName) }.dropLast(1) else emptyList(),
            )
        }

        fun mode(id: String?): LegMode = when (id) {
            "walking" -> LegMode.walk
            "bus", "replacement-bus" -> LegMode.bus
            "tube" -> LegMode.tube
            "overground" -> LegMode.overground
            "elizabeth-line" -> LegMode.elizabeth
            "dlr" -> LegMode.dlr
            "tram" -> LegMode.tram
            "national-rail", "international-rail" -> LegMode.rail
            "river-bus", "river-tour" -> LegMode.ferry
            "cable-car" -> LegMode.cableCar
            "coach" -> LegMode.coach
            "cycle", "cycle-hire" -> LegMode.cycle
            else -> LegMode.other
        }

        fun cleanName(name: String?): String {
            var result = name ?: ""
            // Each suffix is checked against the name as it stands, as Swift's `for … where` does.
            for (suffix in listOf(" Underground Station", " Rail Station", " DLR Station", " Station")) {
                if (result.endsWith(suffix)) result = result.dropLast(suffix.length)
            }
            return result
        }
    }

    @Serializable
    internal data class Response(val journeys: List<JourneyDTO>? = null) {
        @Serializable
        data class JourneyDTO(
            val startDateTime: String? = null,
            val arrivalDateTime: String? = null,
            val legs: List<Leg>,
            val fare: Fare? = null,
        )

        @Serializable
        data class Fare(val totalCost: Int? = null)

        @Serializable
        data class Leg(
            val departureTime: String? = null,
            val arrivalTime: String? = null,
            val scheduledDepartureTime: String? = null,
            val departurePoint: Point? = null,
            val arrivalPoint: Point? = null,
            val path: Path? = null,
            val routeOptions: List<RouteOption>? = null,
            val mode: Identifier? = null,
            val isDisrupted: Boolean? = null,
            val distance: Double? = null,
        )

        @Serializable
        data class Point(val commonName: String? = null, val platformName: String? = null)

        @Serializable
        data class Path(val lineString: String? = null, val stopPoints: List<Identifier>? = null)

        @Serializable
        data class RouteOption(val name: String? = null, val directions: List<String>? = null, val lineIdentifier: Identifier? = null)

        @Serializable
        data class Identifier(val id: String? = null, val name: String? = null)
    }
}
