package polaris.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Polaris's own map style (a MapLibre style document), in the star-atlas palette: chart paper and
 * navy ink by day, night-sky paper and pale ink by night, streets drawn quietly so the journey's
 * line colours and the one gold star carry the map. Written here from scratch over the
 * OpenMapTiles vector schema, so any OpenMapTiles-compatible tile source works.
 *
 * The default source is OpenFreeMap's public instance: no key, no account, free to use with
 * attribution ([ATTRIBUTION], shown on the map). Builders can point [tiles] and [glyphs] at their
 * own server. There is no sprite (nothing here needs icons) and no offline download: tiles are
 * fetched as the map shows them and kept only in MapLibre's default cache.
 */
object MapStyle {
    const val OPENFREEMAP_TILES = "https://tiles.openfreemap.org/planet"
    const val OPENFREEMAP_GLYPHS = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"

    /** The credit OpenFreeMap asks every map using it to show, in its words, each part linked as on its site. */
    val CREDITS: List<Pair<String, String>> = listOf(
        "OpenFreeMap" to "https://openfreemap.org",
        "© OpenMapTiles" to "https://www.openmaptiles.org/",
        "Data from OpenStreetMap" to "https://www.openstreetmap.org/copyright",
    )
    val ATTRIBUTION: String = CREDITS.joinToString(" ") { it.first }

    data class Palette(
        val paper: String,
        val water: String,
        val green: String,
        val building: String,
        val roadCasing: String,
        val road: String,
        val majorRoad: String,
        val rail: String,
        val label: String,
        val minorLabel: String,
        val waterLabel: String,
        val halo: String,
    )

    /** Chart paper (`Theme.paper`), navy ink (`Theme.ink`), water in the chart-blue's palest wash. */
    val day = Palette(
        paper = "#F5F6FA", water = "#D5DFEF", green = "#E3EAE4", building = "#E9EBF1",
        roadCasing = "#D8DCE6", road = "#FFFFFF", majorRoad = "#FFFFFF", rail = "#B9C0CF",
        label = "#0D1530", minorLabel = "#5A6380", waterLabel = "#4A6496", halo = "#F5F6FA",
    )

    /** Night-sky paper, with streets a step lighter and water a step darker, so lines stay the brightest thing. */
    val night = Palette(
        paper = "#0B0F1C", water = "#060912", green = "#0E1820", building = "#131A2B",
        roadCasing = "#0B0F1C", road = "#1C2438", majorRoad = "#26304A", rail = "#2F3A55",
        label = "#E9EDF6", minorLabel = "#8F9AB5", waterLabel = "#6F86B8", halo = "#0B0F1C",
    )

    fun json(night: Boolean, tiles: String = OPENFREEMAP_TILES, glyphs: String = OPENFREEMAP_GLYPHS): String {
        val p = if (night) this.night else day
        return buildJsonObject {
            put("version", 8)
            put("name", if (night) "Polaris night" else "Polaris day")
            put("glyphs", glyphs)
            put("sources", obj("openmaptiles" to obj("type" to "vector", "url" to tiles, "attribution" to ATTRIBUTION)))
            put("layers", JsonArray(layers(p)))
        }.toString()
    }

    private fun layers(p: Palette): List<JsonElement> = listOf(
        obj("id" to "background", "type" to "background", "paint" to obj("background-color" to p.paper)),
        fill("park", "park", p.green),
        fill("green", "landcover", p.green, filter = arr("match", arr("get", "class"), arr("wood", "grass"), true, false)),
        fill("water", "water", p.water),
        obj(
            "id" to "waterway", "type" to "line", "source" to "openmaptiles", "source-layer" to "waterway",
            "paint" to obj("line-color" to p.water, "line-width" to zoomed(10, 0.5, 16, 3)),
        ),
        fill("building", "building", p.building, minzoom = 14),
        road("road-minor-casing", arr("minor", "service"), p.roadCasing, zoomed(13, 1.5, 18, 14), minzoom = 13),
        road("road-major-casing", arr("primary", "secondary", "tertiary", "trunk", "motorway"), p.roadCasing, zoomed(8, 1.5, 18, 22), minzoom = 8),
        road("road-minor", arr("minor", "service"), p.road, zoomed(13, 0.75, 18, 11), minzoom = 13),
        road("road-major", arr("primary", "secondary", "tertiary", "trunk", "motorway"), p.majorRoad, zoomed(8, 0.75, 18, 18), minzoom = 8),
        obj(
            "id" to "path", "type" to "line", "source" to "openmaptiles", "source-layer" to "transportation", "minzoom" to 15,
            "filter" to arr("==", arr("get", "class"), "path"),
            "paint" to obj("line-color" to p.roadCasing, "line-width" to 1, "line-dasharray" to arr(2, 2)),
        ),
        road("rail", arr("rail", "transit"), p.rail, zoomed(10, 0.5, 16, 1.5), minzoom = 10),
        label(
            "water-name", "water_name", p.waterLabel, p.halo, font = "Noto Sans Italic", size = zoomed(10, 11, 16, 14),
            filter = arr("match", arr("geometry-type"), arr("LineString", "MultiLineString"), true, false), along = true,
        ),
        label(
            "road-name", "transportation_name", p.minorLabel, p.halo, font = "Noto Sans Regular", size = zoomed(14, 10, 18, 13),
            minzoom = 14, along = true,
        ),
        // Districts in spaced capitals, as an atlas letters them.
        label(
            "district", "place", p.minorLabel, p.halo, font = "Noto Sans Regular", size = zoomed(11, 10, 15, 12),
            filter = arr("match", arr("get", "class"), arr("suburb", "neighbourhood", "quarter"), true, false),
            minzoom = 11, capitals = true,
        ),
        label(
            "town", "place", p.label, p.halo, font = "Noto Sans Regular", size = zoomed(6, 11, 12, 15),
            filter = arr("match", arr("get", "class"), arr("town", "village"), true, false), minzoom = 6,
        ),
        label(
            "city", "place", p.label, p.halo, font = "Noto Sans Bold", size = zoomed(4, 12, 10, 18),
            filter = arr("==", arr("get", "class"), "city"), maxzoom = 13,
        ),
    )

    private fun fill(id: String, layer: String, color: String, filter: JsonElement? = null, minzoom: Int? = null) = obj(
        "id" to id, "type" to "fill", "source" to "openmaptiles", "source-layer" to layer,
        "minzoom" to minzoom, "filter" to filter, "paint" to obj("fill-color" to color),
    )

    private fun road(id: String, classes: JsonElement, color: String, width: JsonElement, minzoom: Int) = obj(
        "id" to id, "type" to "line", "source" to "openmaptiles", "source-layer" to "transportation", "minzoom" to minzoom,
        "filter" to arr("match", arr("get", "class"), classes, true, false),
        "layout" to obj("line-cap" to "round", "line-join" to "round"),
        "paint" to obj("line-color" to color, "line-width" to width),
    )

    private fun label(
        id: String, layer: String, color: String, halo: String, font: String, size: JsonElement,
        filter: JsonElement? = null, minzoom: Int? = null, maxzoom: Int? = null, along: Boolean = false, capitals: Boolean = false,
    ) = obj(
        "id" to id, "type" to "symbol", "source" to "openmaptiles", "source-layer" to layer,
        "minzoom" to minzoom, "maxzoom" to maxzoom, "filter" to filter,
        "layout" to obj(
            "text-field" to arr("coalesce", arr("get", "name:en"), arr("get", "name:latin"), arr("get", "name")),
            "text-font" to arr(font),
            "text-size" to size,
            "symbol-placement" to if (along) "line" else null,
            "text-transform" to if (capitals) "uppercase" else null,
            "text-letter-spacing" to if (capitals) 0.12 else null,
            "text-max-width" to 8,
        ),
        "paint" to obj("text-color" to color, "text-halo-color" to halo, "text-halo-width" to 1.2),
    )

    private fun zoomed(z1: Int, v1: Number, z2: Int, v2: Number) = arr("interpolate", arr("exponential", 1.4), arr("zoom"), z1, v1, z2, v2)

    private fun value(v: Any?): JsonElement? = when (v) {
        null -> null
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> error("unsupported $v")
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject =
        JsonObject(pairs.mapNotNull { (k, v) -> value(v)?.let { k to it } }.toMap())

    private fun arr(vararg items: Any?): JsonArray = JsonArray(items.map { value(it) ?: error("null in array") })
}
