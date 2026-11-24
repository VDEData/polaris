package polaris.engine

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the map draws for a journey, against the recorded Transitous search. */
class JourneyMapTest {
    private val journey = TransitousProvider.parse(Fixtures.bytes("transitous-plan.json"), "King's Cross", "Canary Wharf").first()

    @Test fun eachLegIsALineInItsLinesColourAndWalksAreDotted() {
        val drawing = JourneyMap.drawing(journey, night = false)
        assertEquals(journey.legs.size, drawing.lines.size)
        assertEquals(listOf(true, false, true, false, true), drawing.lines.map { it.isWalk })
        // TfL's colours, not the feed's own (Transitous says ec9bad for the Hammersmith & City).
        assertEquals(listOf("#F3A9BB", "#6950A1"), drawing.lines.mapNotNull { it.color }.map(JourneyMap::hex))
        assertTrue(drawing.lines.all { it.points.size >= 2 })
    }

    @Test fun nearBlackLinesLiftAtNight() {
        val northern = Leg(mode = LegMode.tube, line = "Northern", lineColor = LineColors.color("Northern", LegMode.tube),
            fromName = "Euston", toName = "Bank", departure = Instant.EPOCH, arrival = Instant.EPOCH)
        assertEquals("#2B2B2B", JourneyMap.hex(JourneyMap.color(northern, night = false)!!))
        assertEquals(JourneyMap.nightLift, JourneyMap.color(northern, night = true))
        val elizabeth = northern.copy(line = "Elizabeth line", lineColor = LineColors.color("Elizabeth line", LegMode.elizabeth))
        assertEquals("#6950A1", JourneyMap.hex(JourneyMap.color(elizabeth, night = true)!!))
        assertNull(JourneyMap.color(northern.copy(mode = LegMode.walk), night = true))
    }

    @Test fun stopsAreWhereYouStartChangeAndArrive() {
        val drawing = JourneyMap.drawing(journey, night = false)
        val kinds = drawing.stops.map { it.kind }
        assertEquals(1, kinds.count { it == JourneyMap.Stop.Kind.origin })
        assertEquals(1, kinds.count { it == JourneyMap.Stop.Kind.destination })
        val changes = drawing.stops.filter { it.kind == JourneyMap.Stop.Kind.change }.map { it.name }
        assertTrue("King's Cross St. Pancras" in changes && "Canary Wharf" in changes, "$changes")
        assertTrue(changes.any { it.startsWith("Farringdon") })
        // No two stops drawn on top of each other.
        for (a in drawing.stops) for (b in drawing.stops) if (a !== b) assertTrue(JourneyMap.metres(a.coordinate, b.coordinate) >= 40)
    }

    @Test fun theCameraFitsEveryPointOfTheJourney() {
        val drawing = JourneyMap.drawing(journey, night = false)
        val bounds = assertNotNull(drawing.bounds)
        assertFalse(bounds.isPoint)
        for (point in drawing.lines.flatMap { it.points }) {
            assertTrue(point.latitude in bounds.south..bounds.north && point.longitude in bounds.west..bounds.east)
        }
        // King's Cross is north-west of Canary Wharf; the box spans both.
        assertTrue(bounds.north > 51.53 && bounds.south < 51.51 && bounds.west < -0.12 && bounds.east > -0.03, "$bounds")
    }

    @Test fun aLegWithNoPathIsDrawnStraightBetweenItsNeighbours() {
        val noWalkPath = journey.copy(legs = journey.legs.mapIndexed { i, leg -> if (i == 2) leg.copy(path = emptyList()) else leg })
        val drawing = JourneyMap.drawing(noWalkPath, night = false)
        val walk = drawing.lines.single { it.legIndex == 2 }
        assertEquals(listOf(journey.legs[1].end, journey.legs[3].start), walk.points)
        // The first and last legs fall back to the query's ends.
        val origin = Coordinate(51.5308, -0.1238)
        val bare = journey.copy(legs = journey.legs.mapIndexed { i, leg -> if (i == 0) leg.copy(path = emptyList()) else leg })
        assertEquals(origin, JourneyMap.drawing(bare, night = false, origin = origin).lines.first().points.first())
        assertEquals(journey.legs.size - 1, JourneyMap.drawing(bare, night = false).lines.size)
    }

    @Test fun nothingToDrawHasNoBounds() {
        val empty = journey.copy(legs = journey.legs.map { it.copy(path = emptyList()) })
        val drawing = JourneyMap.drawing(empty, night = false)
        assertEquals(emptyList(), drawing.lines)
        assertNull(drawing.bounds)
    }

    @Test fun theGeoJsonIsLongitudeFirstWithTheStylesProperties() {
        val drawing = JourneyMap.drawing(journey, night = true)
        val lines = HTTP.json.parseToJsonElement(JourneyMap.linesGeoJson(drawing)).jsonObject
        val features = lines["features"]!!.jsonArray
        assertEquals(drawing.lines.size, features.size)
        val tube = features[1].jsonObject
        assertEquals("#F3A9BB", tube["properties"]!!.jsonObject["color"]!!.jsonPrimitive.content)
        assertEquals("false", tube["properties"]!!.jsonObject["walk"]!!.jsonPrimitive.content)
        val first = tube["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.first().jsonArray
        assertEquals(drawing.lines[1].points.first().longitude, first[0].jsonPrimitive.content.toDouble())
        val stops = HTTP.json.parseToJsonElement(JourneyMap.stopsGeoJson(drawing)).jsonObject["features"]!!.jsonArray
        assertEquals(drawing.stops.map { it.kind.name }, stops.map { it.jsonObject["properties"]!!.jsonObject["kind"]!!.jsonPrimitive.content })
    }
}
