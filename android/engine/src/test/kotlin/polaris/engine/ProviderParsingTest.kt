package polaris.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The iOS suite's recorded responses, read in place from PolarisTests/Fixtures. */
object Fixtures {
    fun bytes(name: String): ByteArray {
        val stream = Fixtures::class.java.classLoader.getResourceAsStream(name)
        assertNotNull(stream, "fixture $name (PolarisTests/Fixtures)")
        return stream.use { it.readBytes() }
    }
}

fun iso(text: String) = Time.iso(text)!!

/** Parsers against real responses recorded for King's Cross → Canary Wharf. Mirrors ProviderParsingTests.swift. */
class ProviderParsingTest {
    @Test fun transitous() {
        val journeys = TransitousProvider.parse(Fixtures.bytes("transitous-plan.json"), "King's Cross", "Canary Wharf")
        val first = journeys.first()
        assertEquals("King's Cross", first.legs.first().fromName)
        assertEquals("Canary Wharf", first.legs.last().toName)
        assertEquals(listOf(LegMode.tube, LegMode.elizabeth), first.transitLegs.map { it.mode })
        assertEquals(listOf("Hammersmith & City", "Elizabeth line"), first.transitLegs.map { it.line })
        assertEquals(30 * 60.0, first.duration)
        assertTrue(first.legs.all { it.path.isNotEmpty() })
        assertEquals("2", first.transitLegs.first().platform)
        assertEquals(listOf("Liverpool Street", "Whitechapel"), first.transitLegs.last().intermediateStops)
        // Decoded at precision 6, the path stays in London.
        val point = first.transitLegs.first().path.first()
        assertTrue(point.latitude in 51.4..51.6 && point.longitude in -0.3..0.1)
    }

    @Test fun tfl() {
        val journeys = TfLProvider.parse(Fixtures.bytes("tfl-journeys.json"), "Fastest")
        val first = journeys.first()
        assertEquals(listOf(LegMode.tube, LegMode.dlr), first.transitLegs.map { it.mode })
        assertEquals("Northern", first.transitLegs.first().line)
        assertEquals("King's Cross St. Pancras", first.transitLegs.first().fromName)
        // TfL lists the alighting stop last; only the ones in between are kept.
        assertEquals(listOf("Angel", "Old Street", "Moorgate"), first.transitLegs.first().intermediateStops)
        assertEquals(39 * 60.0, first.duration)
        // London wall-clock "11:58" in GMT season.
        assertEquals(iso("2026-10-28T11:58:00Z"), first.departure)
        assertTrue(journeys.any { j -> j.transitLegs.map { it.mode } == listOf(LegMode.rail, LegMode.elizabeth) })
    }

    @Test fun google() {
        val query = JourneyQuery(
            Place(name = "King's Cross", coordinate = Coordinate(51.5308, -0.1238)),
            Place(name = "Canary Wharf", coordinate = Coordinate(51.5035, -0.0187)), TimeMode.LeaveNow,
        )
        val journeys = GoogleRoutesProvider.parse(Fixtures.bytes("google-routes.json"), query, "Fastest")
        assertEquals(6, journeys.size)
        val first = journeys.first()
        assertEquals(listOf(LegMode.tube, LegMode.dlr), first.transitLegs.map { it.mode })
        assertEquals(listOf("Northern", "DLR"), first.transitLegs.map { it.line })
        assertEquals("£3.10", first.fare)
        // Matches the route Transitous and TfL also find, so the cards can merge.
        val hc = journeys.first { j -> j.transitLegs.map { it.mode } == listOf(LegMode.tube, LegMode.elizabeth) }
        assertEquals(listOf("Hammersmith & City", "Elizabeth line"), hc.transitLegs.map { it.line })
        assertEquals(listOf(LegMode.walk, LegMode.tube, LegMode.walk, LegMode.elizabeth, LegMode.walk), hc.legs.map { it.mode })
        assertEquals("King's Cross St. Pancras", hc.legs[0].toName)
        assertEquals("Canary Wharf", hc.legs.last().toName)
        assertTrue(hc.legs.all { it.path.isNotEmpty() })
        assertEquals(hc.legs[1].departure.minusSeconds(180), hc.departure)
    }

    @Test fun googleSynthetic() {
        val json = """
        {"routes":[{"duration":"1500s","localizedValues":{"transitFare":{"text":"£2.80"}},"legs":[{"steps":[
          {"travelMode":"WALK","staticDuration":"120s","distanceMeters":150},
          {"travelMode":"WALK","staticDuration":"60s","distanceMeters":50},
          {"travelMode":"TRANSIT","staticDuration":"600s","transitDetails":{"stopDetails":{
            "departureStop":{"name":"King's Cross St. Pancras"},"arrivalStop":{"name":"Farringdon"},
            "departureTime":"2026-10-28T12:05:00Z","arrivalTime":"2026-10-28T12:08:00Z"},
            "headsign":"Barking","stopCount":2,
            "transitLine":{"name":"Hammersmith & City","color":"#f3a9bb","vehicle":{"type":"SUBWAY"}}}},
          {"travelMode":"TRANSIT","transitDetails":{"stopDetails":{
            "departureStop":{"name":"Farringdon"},"arrivalStop":{"name":"Canary Wharf"},
            "departureTime":"2026-10-28T12:12:00Z","arrivalTime":"2026-10-28T12:22:00Z"},
            "transitLine":{"name":"Elizabeth line","vehicle":{"type":"HEAVY_RAIL"}}}},
          {"travelMode":"WALK","staticDuration":"300s","distanceMeters":400}
        ]}]}]}
        """
        val query = JourneyQuery(
            Place(name = "A", coordinate = Coordinate(51.53, -0.12)),
            Place(name = "B", coordinate = Coordinate(51.50, -0.02)), TimeMode.LeaveNow,
        )
        val journey = GoogleRoutesProvider.parse(json.encodeToByteArray(), query, "Fastest").first()
        assertEquals(listOf(LegMode.walk, LegMode.tube, LegMode.elizabeth, LegMode.walk), journey.legs.map { it.mode })
        assertEquals(iso("2026-10-28T12:02:00Z"), journey.departure)
        assertEquals(iso("2026-10-28T12:27:00Z"), journey.arrival)
        assertEquals("King's Cross St. Pancras", journey.legs[0].toName)
        assertEquals("B", journey.legs[3].toName)
        assertEquals("£2.80", journey.fare)
    }

    @Test fun polyline() {
        // Google's documented example.
        val points = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, points.size)
        assertTrue(abs(points[0].latitude - 38.5) < 1e-6 && abs(points[0].longitude + 120.2) < 1e-6)
        assertTrue(abs(points[2].latitude - 43.252) < 1e-6 && abs(points[2].longitude + 126.453) < 1e-6)
    }

    @Test fun lineNamesStayAsTheOperatorSpellsThem() {
        assertEquals("hammersmithandcity", Journey.normalise("Hammersmith & City"))
        assertEquals("elizabeth", Journey.normalise("Elizabeth line"))
        assertEquals("northern", Journey.normalise("Northern line"))
        assertEquals(RGB.hex("F3A9BB"), LineColors.color("Hammersmith & City", LegMode.tube))
        assertEquals(LegMode.overground, LegMode.rail.refined("Windrush", null))
        assertEquals(LegMode.tube, LegMode.rail.refined("Victoria", "London Underground"))
    }

    /** Same name and cases as in ProviderParsingTests.swift; journey-expectations.json has more. */
    @Test fun coordinatePairsAreWrittenInPlainDecimals() {
        assertEquals("51.5308,-0.1238", Coordinate(51.5308, -0.1238).pair)
        assertEquals("51.4779,0.0005", Coordinate(51.4779, 0.0005).pair)
        assertEquals("51.4779,-0.00002", Coordinate(51.4779, -0.00002).pair)
        assertEquals("51.0,0.0", Coordinate(51.0, 0.0).pair)
        assertEquals("1.0E-6", (0.000001).toString(), "what Kotlin would have written")
        assertEquals("51.5,0.000001", Coordinate(51.5, 0.000001).pair)
    }

    @Test fun timesAreWrittenAndReadAsSwiftDoes() {
        assertEquals("2026-10-28T12:00:00Z", Time.isoString(iso("2026-10-28T12:00:00.734Z")))
        assertEquals(iso("2026-10-28T12:07:00Z"), Time.londonLocal("2026-10-28T12:07:00"))
        // British Summer Time: London wall-clock is an hour ahead of UTC.
        assertEquals(iso("2026-07-01T11:07:00Z"), Time.londonLocal("2026-07-01T12:07:00"))
        assertEquals(iso("2026-10-28T12:00:00Z"), Time.iso("2026-10-28T13:00:00+01:00"))
    }
}
