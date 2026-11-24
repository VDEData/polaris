package polaris.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Google Routes API, TRANSIT mode. Ported from Polaris/Providers/GoogleRoutesProvider.swift.
 *
 * A key restricted to an Android app is checked against the `X-Android-Package` and
 * `X-Android-Cert` headers (the app's package name and its signing certificate's SHA-1),
 * where iOS sends `X-Ios-Bundle-Identifier`.
 */
class GoogleRoutesProvider(
    private val http: HTTP,
    val apiKey: String? = null,
    val packageName: String = "",
    val certificateSha1: String? = null,
    /** How many requests this search may send (`GoogleAllowance.searchRequests`): its deep-search variants are cut to this many, in order. */
    val maxRequests: Int = variants.size,
) : JourneyProvider {
    override val id = ProviderID.google

    data class Variant(val label: String, val routingPreference: String? = null, val modes: List<String>? = null)

    override suspend fun journeys(query: JourneyQuery, deep: Boolean): List<Journey> {
        val key = apiKey?.takeIf { it.isNotEmpty() } ?: throw ProviderError.MissingKey
        if (maxRequests <= 0) throw ProviderError.MissingKey
        return fanOut(if (deep) variants.take(maxRequests) else listOf(variants[0])) { variant ->
            parse(http.data(request(query, variant, key)), query, variant.label)
        }
    }

    fun request(query: JourneyQuery, variant: Variant, key: String): HttpRequest {
        val headers = buildMap {
            put("Content-Type", "application/json")
            put("X-Goog-Api-Key", key)
            put("X-Goog-FieldMask", fieldMask)
            if (packageName.isNotEmpty()) put("X-Android-Package", packageName)
            certificateSha1?.takeIf { it.isNotEmpty() }?.let { put("X-Android-Cert", it) }
        }
        val time = query.time
        val body = Body(
            origin = Body.Waypoint(query.origin.coordinate), destination = Body.Waypoint(query.destination.coordinate),
            transitPreferences = Body.TransitPreferences(variant.modes, variant.routingPreference),
            departureTime = (time as? TimeMode.DepartAt)?.let { Time.isoString(it.at) },
            arrivalTime = (time as? TimeMode.ArriveBy)?.let { Time.isoString(it.at) },
        )
        return HttpRequest(
            url = "https://routes.googleapis.com/directions/v2:computeRoutes", method = "POST", headers = headers,
            body = HTTP.json.encodeToString(body).encodeToByteArray(),
            // It carries the key: an answer that points elsewhere is an error, not somewhere to send it.
            followRedirects = false,
        )
    }

    companion object {
        val variants = listOf(
            Variant("Fastest"),
            Variant("Fewer changes", routingPreference = "FEWER_TRANSFERS"),
            Variant("Less walking", routingPreference = "LESS_WALKING"),
            Variant("Rail only", modes = listOf("TRAIN", "SUBWAY", "LIGHT_RAIL", "RAIL")),
            Variant("Bus only", modes = listOf("BUS")),
        )

        val fieldMask = listOf(
            "routes.duration", "routes.legs.steps.travelMode", "routes.legs.steps.staticDuration",
            "routes.legs.steps.distanceMeters", "routes.legs.steps.polyline", "routes.legs.steps.transitDetails",
            "routes.localizedValues.transitFare",
        ).joinToString(",")

        fun parse(data: ByteArray, query: JourneyQuery, variant: String): List<Journey> {
            val response = HTTP.decode<Response>(data)
            val journeys = (response.routes ?: emptyList()).mapNotNull { journey(it, query, variant) }
            if (journeys.isEmpty()) throw ProviderError.NoRoutes
            return journeys
        }

        private class PendingWalk(var seconds: Double = 0.0, var meters: Double = 0.0, val path: MutableList<Coordinate> = mutableListOf())

        /**
         * Google times only its transit steps. Walks are placed around them: the
         * first ends as the first vehicle leaves, later ones start when you alight.
         */
        private fun journey(route: Response.Route, query: JourneyQuery, variant: String): Journey? {
            val steps = route.legs?.flatMap { it.steps ?: emptyList() } ?: emptyList()
            val legs = mutableListOf<Leg>()
            var pendingWalk: PendingWalk? = null

            fun flushWalk(endingAt: java.time.Instant?, startingAt: java.time.Instant?) {
                val walk = pendingWalk ?: return
                pendingWalk = null
                val departure = startingAt ?: endingAt?.plusSeconds(-walk.seconds) ?: query.time.date
                legs += Leg(
                    mode = LegMode.walk, fromName = legs.lastOrNull()?.toName ?: query.origin.name, toName = "",
                    departure = departure, arrival = departure.plusSeconds(walk.seconds),
                    distanceMeters = walk.meters, path = walk.path.toList(),
                )
            }

            for (step in steps) {
                val points = step.polyline?.encodedPolyline?.let { Polyline.decode(it) } ?: emptyList()
                val t = step.transitDetails
                val dep = Time.iso(t?.stopDetails?.departureTime)
                val arr = Time.iso(t?.stopDetails?.arrivalTime)
                if (step.travelMode != "TRANSIT" || t == null || dep == null || arr == null) {
                    val walk = pendingWalk ?: PendingWalk()
                    walk.seconds += seconds(step.staticDuration)
                    walk.meters += (step.distanceMeters ?: 0).toDouble()
                    walk.path += points
                    pendingWalk = walk
                    continue
                }
                val previousArrival = legs.lastOrNull()?.arrival
                flushWalk(endingAt = dep, startingAt = previousArrival)
                val fromName = t.stopDetails?.departureStop?.name ?: ""
                val last = legs.lastIndex
                if (last >= 0 && legs[last].mode == LegMode.walk && legs[last].toName.isEmpty()) legs[last] = legs[last].copy(toName = fromName)

                val line = t.transitLine
                val agency = line?.agencies?.firstOrNull()?.name
                val base = mode(line?.vehicle?.type)
                // The short name is the bus number, or TfL's own line name ("Northern", not "Northern Line").
                val shortName = line?.nameShort?.takeIf { it.isNotEmpty() }
                val rawLine = shortName ?: line?.name ?: agency
                val refined = base.refined(rawLine, agency)
                legs += Leg(
                    mode = refined, line = rawLine,
                    lineColor = LineColors.color(rawLine, refined, line?.color),
                    headsign = t.headsign, fromName = fromName, toName = t.stopDetails?.arrivalStop?.name ?: "",
                    departure = dep, arrival = arr, stopCount = t.stopCount,
                    distanceMeters = step.distanceMeters?.toDouble(), path = points,
                )
            }
            val lastArrival = legs.lastOrNull()?.arrival
            flushWalk(endingAt = null, startingAt = lastArrival ?: walkOnlyStart(route, query))
            val last = legs.lastIndex
            if (last >= 0 && legs[last].mode == LegMode.walk) legs[last] = legs[last].copy(toName = query.destination.name)

            val first = legs.firstOrNull() ?: return null
            return Journey(
                departure = first.departure, arrival = legs.last().arrival, legs = legs.toList(),
                sources = listOf(SourceTag(ProviderID.google, variant)),
                fare = route.localizedValues?.transitFare?.text,
            )
        }

        private fun walkOnlyStart(route: Response.Route, query: JourneyQuery): java.time.Instant =
            if (query.time.isArriveBy) query.time.date.plusSeconds(-seconds(route.duration)) else query.time.date

        fun seconds(duration: String?): Double = duration?.trim('s')?.toDoubleOrNull() ?: 0.0

        fun mode(vehicle: String?): LegMode = when (vehicle) {
            "BUS", "TROLLEYBUS", "SHARE_TAXI" -> LegMode.bus
            "INTERCITY_BUS" -> LegMode.coach
            "SUBWAY", "METRO_RAIL" -> LegMode.tube
            "TRAM", "MONORAIL" -> LegMode.tram
            "RAIL", "HEAVY_RAIL", "COMMUTER_TRAIN", "HIGH_SPEED_TRAIN", "LONG_DISTANCE_TRAIN" -> LegMode.rail
            "FERRY" -> LegMode.ferry
            "CABLE_CAR", "GONDOLA_LIFT", "FUNICULAR" -> LegMode.cableCar
            else -> LegMode.other
        }
    }

    @Serializable
    internal data class Body(
        val origin: Waypoint,
        val destination: Waypoint,
        val travelMode: String = "TRANSIT",
        val computeAlternativeRoutes: Boolean = true,
        val transitPreferences: TransitPreferences,
        val departureTime: String? = null,
        val arrivalTime: String? = null,
    ) {
        @Serializable
        data class Waypoint(val location: Location) {
            constructor(c: Coordinate) : this(Location(LatLng(c.latitude, c.longitude)))
        }

        @Serializable
        data class Location(val latLng: LatLng)

        @Serializable
        data class LatLng(val latitude: Double, val longitude: Double)

        @Serializable
        data class TransitPreferences(val allowedTravelModes: List<String>? = null, val routingPreference: String? = null)
    }

    @Serializable
    internal data class Response(val routes: List<Route>? = null) {
        @Serializable
        data class Route(val duration: String? = null, val legs: List<RouteLeg>? = null, val localizedValues: LocalizedValues? = null)

        @Serializable
        data class LocalizedValues(val transitFare: Text? = null)

        @Serializable
        data class Text(val text: String? = null)

        @Serializable
        data class RouteLeg(val steps: List<Step>? = null)

        @Serializable
        data class Step(
            val travelMode: String? = null,
            val staticDuration: String? = null,
            val distanceMeters: Int? = null,
            val polyline: EncodedPolyline? = null,
            val transitDetails: TransitDetails? = null,
        )

        @Serializable
        data class EncodedPolyline(val encodedPolyline: String? = null)

        @Serializable
        data class TransitDetails(
            val stopDetails: StopDetails? = null,
            val headsign: String? = null,
            val stopCount: Int? = null,
            val transitLine: TransitLine? = null,
        )

        @Serializable
        data class StopDetails(
            val departureStop: Stop? = null,
            val arrivalStop: Stop? = null,
            val departureTime: String? = null,
            val arrivalTime: String? = null,
        )

        @Serializable
        data class Stop(val name: String? = null)

        @Serializable
        data class TransitLine(
            val name: String? = null,
            val nameShort: String? = null,
            val color: String? = null,
            val vehicle: Vehicle? = null,
            val agencies: List<Agency>? = null,
        )

        @Serializable
        data class Vehicle(val type: String? = null)

        @Serializable
        data class Agency(val name: String? = null)
    }
}
