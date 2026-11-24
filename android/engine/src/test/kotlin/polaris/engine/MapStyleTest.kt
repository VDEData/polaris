package polaris.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The map style: keyless OpenFreeMap by default, credited, nothing fetched beyond what is shown. */
class MapStyleTest {
    private fun style(night: Boolean, tiles: String = MapStyle.OPENFREEMAP_TILES): JsonObject =
        HTTP.json.parseToJsonElement(MapStyle.json(night, tiles)).jsonObject

    @Test fun usesOpenFreeMapByDefaultAndCreditsIt() {
        for (night in listOf(false, true)) {
            val s = style(night)
            assertEquals("8", s["version"]!!.jsonPrimitive.content)
            val source = s["sources"]!!.jsonObject["openmaptiles"]!!.jsonObject
            // The TileJSON address, not a dated tile URL: OpenFreeMap moves those on with each build.
            assertEquals("https://tiles.openfreemap.org/planet", source["url"]!!.jsonPrimitive.content)
            assertEquals(MapStyle.ATTRIBUTION, source["attribution"]!!.jsonPrimitive.content)
            assertTrue("OpenFreeMap" in MapStyle.ATTRIBUTION && "OpenMapTiles" in MapStyle.ATTRIBUTION && "OpenStreetMap" in MapStyle.ATTRIBUTION)
            // No sprite to fetch, and every font is one OpenFreeMap serves.
            assertNull(s["sprite"])
            val fonts = s["layers"]!!.jsonArray.mapNotNull { it.jsonObject["layout"]?.jsonObject?.get("text-font") }
                .flatMap { it.jsonArray.map { f -> f.jsonPrimitive.content } }.toSet()
            assertTrue(fonts.isNotEmpty() && fonts.all { it in setOf("Noto Sans Regular", "Noto Sans Bold", "Noto Sans Italic") }, "$fonts")
        }
    }

    @Test fun aBuilderCanPointAtTheirOwnTiles() {
        val s = style(false, tiles = "https://tiles.example.org/planet.json")
        assertEquals("https://tiles.example.org/planet.json", s["sources"]!!.jsonObject["openmaptiles"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test fun everyLayerReadsTheOpenMapTilesSchema() {
        val schema = setOf("water", "waterway", "landcover", "landuse", "park", "building", "transportation", "transportation_name", "place", "water_name", "boundary")
        val layers = style(false)["layers"]!!.jsonArray.map { it.jsonObject }
        assertEquals(layers.map { it["id"] }.distinct().size, layers.size)
        for (layer in layers.drop(1)) assertTrue(layer["source-layer"]!!.jsonPrimitive.content in schema, "$layer")
        // Day and night draw the same layers, in different ink.
        assertEquals(layers.map { it["id"] }, style(true)["layers"]!!.jsonArray.map { it.jsonObject["id"] })
        assertTrue(MapStyle.json(false) != MapStyle.json(true))
    }

    @Test fun labelsAreLegibleOnThePaperInBothAppearances() {
        for (p in listOf(MapStyle.day, MapStyle.night)) {
            assertTrue(contrast(p.label, p.paper) >= 7.0, "label on ${p.paper}")
            assertTrue(contrast(p.minorLabel, p.paper) >= 4.5, "minor label on ${p.paper}")
            assertTrue(contrast(p.waterLabel, p.water) >= 3.0, "water label on ${p.water}")
        }
    }

    private fun contrast(a: String, b: String): Double {
        fun lum(hex: String): Double {
            val c = RGB.hex(hex)!!
            fun lin(v: Double) = if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            return 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b)
        }
        val (x, y) = lum(a) to lum(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
}
