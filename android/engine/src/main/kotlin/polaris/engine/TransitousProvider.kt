package polaris.engine

import kotlinx.serialization.Serializable

/**
 * Transitous: community-run MOTIS router over open timetables (GB rail, BODS
 * buses, Underground). One request per search, never fanned out, even for a deep search:
 * their policy asks for light use and a descriptive User-Agent (`HTTP.userAgent`).
 * Ported from Polaris/Providers/TransitousProvider.swift.
 */
class TransitousProvider(private val http: HTTP) : JourneyProvider {
    override val id = ProviderID.transitous

    override suspend fun journeys(query: JourneyQuery, deep: Boolean): List<Journey> =
        parse(http.data(request(query, deep)), query.origin.name, query.destination.name)

    fun request(query: JourneyQuery, deep: Boolean): HttpRequest = HttpRequest(
        HTTP.url(
            "https://api.transitous.org/api/v5/plan",
            listOf(
                "fromPlace" to query.origin.coordinate.pair,
                "toPlace" to query.destination.coordinate.pair,
                "time" to Time.isoString(query.time.date),
                "arriveBy" to if (query.time.isArriveBy) "true" else "false",
                // A deep search asks for more answers in the same one request.
                "numItineraries" to if (deep) "8" else "5",
            ),
        ),
    )

    companion object {
        fun parse(data: ByteArray, origin: String = "", destination: String = ""): List<Journey> {
            val response = HTTP.decode<Response>(data)
            val itineraries = (response.itineraries ?: emptyList()) + (response.direct ?: emptyList())
            val journeys = itineraries.mapNotNull { it ->
                val start = Time.iso(it.startTime) ?: return@mapNotNull null
                val end = Time.iso(it.endTime) ?: return@mapNotNull null
                // MOTIS names the request's own endpoints "START" and "END".
                val legs = it.legs.mapNotNull(::leg).map { leg ->
                    leg.copy(
                        fromName = if (leg.fromName == "START") origin else leg.fromName,
                        toName = if (leg.toName == "END") destination else leg.toName,
                    )
                }
                Journey(departure = start, arrival = end, legs = legs, sources = listOf(SourceTag(ProviderID.transitous, "Fastest")))
            }
            if (journeys.isEmpty()) throw ProviderError.NoRoutes
            return journeys
        }

        private fun leg(l: Response.Leg): Leg? {
            val dep = Time.iso(l.startTime) ?: return null
            val arr = Time.iso(l.endTime) ?: return null
            val base = mode(l.mode)
            val rawLine = lineName(base, l.routeShortName, l.displayName, l.tripShortName, l.agencyName)
            val mode = base.refined(rawLine, l.agencyName)
            val path = l.legGeometry?.let { Polyline.decode(it.points, it.precision ?: 6) } ?: emptyList()
            return Leg(
                mode = mode, line = if (mode.isTransit) rawLine else null,
                lineColor = if (mode.isTransit) LineColors.color(rawLine, mode, l.routeColor) else null,
                headsign = l.headsign,
                fromName = l.from.name, toName = l.to.name,
                departure = dep, arrival = arr,
                stopCount = if (mode.isTransit) (l.intermediateStops?.size ?: 0) + 1 else null,
                distanceMeters = l.distance,
                path = path,
                isRealtime = l.realTime ?: false,
                platform = if (mode.isTransit) l.from.track?.takeIf { it.isNotEmpty() && it != "-" } else null,
                intermediateStops = (l.intermediateStops ?: emptyList()).map { it.name },
            )
        }

        /** Rail legs often carry only a trip number as their name; the operator reads better. */
        fun lineName(mode: LegMode, routeShortName: String?, displayName: String?, tripShortName: String?, agencyName: String?): String? {
            val named = listOfNotNull(routeShortName, displayName).firstOrNull { it.isNotEmpty() }
            return if (mode == LegMode.rail && (named == null || named == tripShortName)) agencyName else (named ?: agencyName)
        }

        fun mode(mode: String): LegMode = when (mode) {
            "WALK" -> LegMode.walk
            "BIKE", "RENTAL", "SCOOTER" -> LegMode.cycle
            "BUS", "TROLLEYBUS" -> LegMode.bus
            "COACH" -> LegMode.coach
            "SUBWAY", "METRO" -> LegMode.tube
            "TRAM", "CABLE_CAR" -> LegMode.tram
            "RAIL", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "SUBURBAN" -> LegMode.rail
            "FERRY" -> LegMode.ferry
            "AERIAL_LIFT", "FUNICULAR" -> LegMode.cableCar
            else -> LegMode.other
        }
    }

    @Serializable
    internal data class Response(val itineraries: List<Itinerary>? = null, val direct: List<Itinerary>? = null) {
        @Serializable
        data class Itinerary(val startTime: String, val endTime: String, val legs: List<Leg>)

        @Serializable
        data class Leg(
            val mode: String,
            val from: Stop,
            val to: Stop,
            val startTime: String,
            val endTime: String,
            val realTime: Boolean? = null,
            val headsign: String? = null,
            val routeShortName: String? = null,
            val displayName: String? = null,
            val tripShortName: String? = null,
            val agencyName: String? = null,
            val routeColor: String? = null,
            val distance: Double? = null,
            val intermediateStops: List<Stop>? = null,
            val legGeometry: Geometry? = null,
        )

        @Serializable
        data class Stop(val name: String, val track: String? = null)

        @Serializable
        data class Geometry(val points: String, val precision: Int? = null)
    }
}
