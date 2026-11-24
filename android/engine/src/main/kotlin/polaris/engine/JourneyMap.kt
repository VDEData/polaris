package polaris.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * What a map draws for one journey, worked out without any map library so it is tested on the
 * JVM: each leg as a line in its line's colour (walks dotted), the stops where you get on, change
 * and get off, and the box the camera should fit. The app turns this into map layers.
 */
object JourneyMap {
    data class Line(
        val legIndex: Int,
        val points: List<Coordinate>,
        /** The line's colour as drawn, null for a walk (drawn dotted in ink). */
        val color: RGB?,
        val isWalk: Boolean,
    )

    data class Stop(val coordinate: Coordinate, val name: String, val kind: Kind) {
        @Suppress("EnumEntryName")
        enum class Kind { origin, change, destination }
    }

    data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double) {
        /** A box no bigger than a street corner (a single point): centre on it instead of fitting. */
        val isPoint: Boolean get() = abs(north - south) < 1e-4 && abs(east - west) < 1e-4
        val centre: Coordinate get() = Coordinate((south + north) / 2, (west + east) / 2)
    }

    data class Drawing(val lines: List<Line>, val stops: List<Stop>, val bounds: Bounds?)

    /** Near-black lines (the Northern) vanish on night paper, so at night they lift to grey, as `Theme.line` does on iOS. */
    val nightLift = RGB(0.64, 0.67, 0.74)
    val unknownLine = RGB(0.541, 0.580, 0.651)

    fun color(leg: Leg, night: Boolean): RGB? {
        if (!leg.mode.isTransit) return null
        val own = leg.lineColor ?: unknownLine
        return if (night && own.luminance < 0.2) nightLift else own
    }

    /**
     * `origin` and `destination` are the query's ends, used where a leg has no path (some sources
     * send none for a short walk) so the gap is drawn straight rather than left out.
     */
    fun drawing(journey: Journey, night: Boolean, origin: Coordinate? = null, destination: Coordinate? = null): Drawing {
        val legs = journey.legs
        val lines = legs.mapIndexedNotNull { index, leg ->
            val points = leg.path.ifEmpty {
                val from = legs.getOrNull(index - 1)?.end ?: if (index == 0) origin else null
                val to = legs.getOrNull(index + 1)?.start ?: if (index == legs.lastIndex) destination else null
                if (from != null && to != null) listOf(from, to) else emptyList()
            }
            if (points.size < 2) null else Line(index, points, color(leg, night), !leg.mode.isTransit)
        }

        val stops = mutableListOf<Stop>()
        fun add(coordinate: Coordinate?, name: String, kind: Stop.Kind) {
            coordinate ?: return
            // A change drawn twice (off one line, on the next a few metres away) is one stop.
            if (stops.any { metres(it.coordinate, coordinate) < 40 }) return
            stops += Stop(coordinate, name, kind)
        }
        add(lines.firstOrNull()?.points?.first() ?: origin, legs.firstOrNull()?.fromName ?: "", Stop.Kind.origin)
        add(lines.lastOrNull()?.points?.last() ?: destination, legs.lastOrNull()?.toName ?: "", Stop.Kind.destination)
        legs.forEachIndexed { index, leg ->
            if (!leg.mode.isTransit) return@forEachIndexed
            val line = lines.firstOrNull { it.legIndex == index } ?: return@forEachIndexed
            add(line.points.first(), leg.fromName, Stop.Kind.change)
            add(line.points.last(), leg.toName, Stop.Kind.change)
        }

        val all = lines.flatMap { it.points } + stops.map { it.coordinate }
        val bounds = if (all.isEmpty()) null else Bounds(
            south = all.minOf { it.latitude }, west = all.minOf { it.longitude },
            north = all.maxOf { it.latitude }, east = all.maxOf { it.longitude },
        )
        return Drawing(lines, stops.sortedBy { it.kind != Stop.Kind.change }, bounds)
    }

    /** The lines as GeoJSON, with `color` ("#RRGGBB") and `walk` on each feature for the style to read. */
    fun linesGeoJson(drawing: Drawing): String = featureCollection(drawing.lines.map { line ->
        feature(
            geometry = buildJsonObject {
                put("type", "LineString")
                put("coordinates", JsonArray(line.points.map(::position)))
            },
            properties = buildJsonObject {
                put("leg", line.legIndex)
                put("walk", line.isWalk)
                line.color?.let { put("color", hex(it)) }
            },
        )
    })

    /** The stops as GeoJSON points, with `kind` and `name`. */
    fun stopsGeoJson(drawing: Drawing): String = featureCollection(drawing.stops.map { stop ->
        feature(
            geometry = buildJsonObject {
                put("type", "Point")
                put("coordinates", position(stop.coordinate))
            },
            properties = buildJsonObject {
                put("kind", stop.kind.name)
                put("name", stop.name)
            },
        )
    })

    fun hex(color: RGB): String {
        fun byte(v: Double) = (v.coerceIn(0.0, 1.0) * 255).let { Math.round(it).toInt() }
        return "#%02X%02X%02X".format(byte(color.r), byte(color.g), byte(color.b))
    }

    /** Ground distance, good enough for telling whether two stops are the same place. */
    fun metres(a: Coordinate, b: Coordinate): Double {
        val dLat = (a.latitude - b.latitude) * 111_320.0
        val dLon = (a.longitude - b.longitude) * 111_320.0 * cos(Math.toRadians((a.latitude + b.latitude) / 2))
        return sqrt(dLat * dLat + dLon * dLon)
    }

    // GeoJSON positions are longitude first.
    private fun position(c: Coordinate) = JsonArray(listOf(JsonPrimitive(c.longitude), JsonPrimitive(c.latitude)))

    private fun feature(geometry: JsonObject, properties: JsonObject) = buildJsonObject {
        put("type", "Feature")
        put("geometry", geometry)
        put("properties", properties)
    }

    private fun featureCollection(features: List<JsonObject>): String = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") { features.forEach { add(it) } }
    }.toString()
}
