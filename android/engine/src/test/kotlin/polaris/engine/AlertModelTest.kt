package polaris.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Alerts for your lines: the stations along a line, picking the stretch you ride, and the JSON
 * the alerts server takes (the same contract file its tests and the iOS tests read). Mirrors
 * PolarisTests/AlertTests.swift.
 */
class AlertModelTest {
    private fun elizabeth() = TfLLines.parseRoute(Fixtures.bytes("tfl-route-elizabeth.json"))
    private fun lines() = TfLLines.parseLines(Fixtures.bytes("tfl-lines.json"))
    private val elizabethLine = RouteAlert.Line("elizabeth", "Elizabeth line")
    private fun stretch(from: String, to: String) = RouteAlert.Segment(elizabethLine, "elizabeth-line", RouteAlert.Station("a", from), RouteAlert.Station("b", to))
    private val wholeDistrict = RouteAlert.Segment(RouteAlert.Line("district", "District line"), "tube")

    @Test fun oneStationHasEveryIdTfLUsesForIt() {
        val route = elizabeth()
        val liverpool = assertNotNull(route.station("Liverpool Street"))
        assertEquals("Liverpool Street", liverpool.name)
        assertEquals("910GLIVSTLL", liverpool.ids.first())
        assertEquals(setOf("910GLIVSTLL", "910GLIVST"), liverpool.ids.toSet())
        // Other sources' spellings find it too.
        assertEquals(liverpool.id, route.station("London Liverpool Street")?.id)
        assertEquals(liverpool.id, route.station("Liverpool Street Rail Station")?.id)
        assertEquals(1, route.stations.count { it.name == "Paddington" })
        assertTrue(route.stations.size > 35)
    }

    /** TfL can give a stop its hub in one list and not in another: still one station, and no two with the same id (the picker's rows are keyed by it). */
    @Test fun aStopWithItsParentInOneListOnlyIsOneStation() {
        val json = """{"orderedLineRoutes":[{"naptanIds":["A1","B1","C1"]}],
          "stopPointSequences":[{"stopPoint":[{"id":"A1","name":"Alpha Underground Station"},{"id":"B1","name":"Beta Underground Station","topMostParentId":"HUBBET"},{"id":"C1","name":"Gamma"}]}],
          "stations":[{"id":"A1","name":"Alpha Underground Station","topMostParentId":"HUBALP"},{"id":"A2","name":"Alpha Rail Station","topMostParentId":"HUBALP"},{"id":"B1","name":"Beta Underground Station"},{"id":"C1","name":"Gamma"}]}"""
        val route = TfLLines.parseRoute(json.encodeToByteArray())
        assertEquals(listOf("Alpha", "Beta", "Gamma"), route.stations.map { it.name })
        assertEquals(route.stations.size, route.stations.map { it.id }.toSet().size, "ids are unique")
        assertEquals(listOf("A1", "A2"), route.stations[0].ids, "the hub's other id is kept as an alias")
        // And every recorded line: no id twice.
        for (fixture in listOf("tfl-route-elizabeth.json", "tfl-sequence-circle.json", "tfl-sequence-district.json", "tfl-sequence-northern.json", "tfl-sequence-elizabeth.json")) {
            val stations = TfLLines.parseRoute(Fixtures.bytes(fixture)).stations
            assertEquals(stations.size, stations.map { it.id }.toSet().size, fixture)
        }
    }

    /** Two stations on different branches (the Northern line's Edgware and High Barnet): no one stretch joins them, so the alert is for the whole line, as on iOS. The app says so when it happens. */
    @Test fun twoBranchesHaveNoStretchBetweenThem() {
        val route = TfLLines.parseRoute(Fixtures.bytes("tfl-sequence-northern.json"))
        val edgware = assertNotNull(route.station("Edgware"))
        val barnet = assertNotNull(route.station("High Barnet"))
        val line = TfLLines.Line("northern", "Northern", "tube")
        val segment = route.segment(line, edgware, barnet)
        assertTrue(segment.isWholeLine)
        assertFalse(route.joins(edgware, barnet))
        assertTrue(route.joins(edgware, assertNotNull(route.station("Camden Town"))))
    }

    @Test fun theStretchYouRideAlongTheLine() {
        val route = elizabeth()
        fun station(name: String) = assertNotNull(route.station(name), name)
        val run = assertNotNull(route.stretch(station("Liverpool Street"), station("Canary Wharf")))
        assertEquals(listOf("Liverpool Street", "Whitechapel", "Canary Wharf"), run.map { it.name })
        // Either direction, and across the central section onto a branch.
        val west = assertNotNull(route.stretch(station("Canary Wharf"), station("Paddington")))
        assertEquals(listOf("Canary Wharf", "Whitechapel", "Liverpool Street", "Farringdon", "Tottenham Court Road", "Bond Street", "Paddington"), west.map { it.name })
        val segment = route.segment(TfLLines.Line("elizabeth", "Elizabeth line", "elizabeth-line"), station("Liverpool Street"), station("Canary Wharf"))
        assertEquals(listOf("910GLIVSTLL|910GLIVST", "910GWCHAPXR", "910GCANWHRF"), segment.stations)
        assertEquals("Liverpool Street to Canary Wharf", segment.stretch)
        // No stations chosen: the whole line.
        assertTrue(route.segment(TfLLines.Line("elizabeth", "Elizabeth line", "elizabeth-line"), null, null).isWholeLine)
    }

    @Test fun linesAsPeopleSayThem() {
        val lines = lines()
        fun name(id: String) = lines.firstOrNull { it.id == id }?.name
        assertEquals("District line", name("district"))
        assertEquals("Elizabeth line", name("elizabeth"))
        assertEquals("Mildmay line", name("mildmay"))
        assertEquals("DLR", name("dlr"))
        assertEquals("Southeastern", name("southeastern"))
        assertEquals(LegMode.overground, lines.first { it.id == "mildmay" }.legMode)
        // Every line falls under one of the picker's groups.
        assertTrue(lines.all { line -> TfLLines.GROUPS.any { it.first == line.mode } }, lines.map { it.mode }.distinct().toString())
    }

    @Test fun otherSourcesRidesFindTheirTfLLine() {
        val lines = lines()
        val now = iso("2026-11-09T08:00:00Z")
        fun ride(mode: LegMode, line: String?) = TfLLines.line(Leg(mode = mode, line = line, fromName = "A", toName = "B", departure = now, arrival = now), lines)?.id
        assertEquals("elizabeth", ride(LegMode.rail, "Elizabeth line"))
        assertEquals("central", ride(LegMode.tube, "Central"))
        assertEquals("hammersmith-city", ride(LegMode.tube, "Hammersmith & City"))
        assertEquals("mildmay", ride(LegMode.rail, "Mildmay"))
        assertEquals("dlr", ride(LegMode.dlr, null))
        assertNull(ride(LegMode.bus, "25"))
    }

    @Test fun sendsWhatTheServerExpects() {
        val text = Fixtures.bytes("alert-contract.json").decodeToString()
        val original = HTTP.json.parseToJsonElement(text).jsonObject
        val contract = HTTP.json.decodeFromString(RouteAlert.Registration.serializer(), text)
        assertEquals("Liverpool Street", contract.alerts[0].segments[0].from?.name)
        assertNull(contract.alerts[1].segments[0].from)
        assertEquals(RouteAlert.Direction.out, contract.alerts[0].direction)
        // As older builds sent it: no direction, and none is added on the way back out.
        assertNull(contract.alerts[1].direction)
        // A time with its own way, and one following the alert's.
        assertEquals(listOf(null, RouteAlert.Direction.back), contract.alerts[0].windows.map { it.direction })
        assertEquals(listOf(RouteAlert.Direction.out, RouteAlert.Direction.back), contract.alerts[0].windows.map(contract.alerts[0]::way))

        val sent = HTTP.json.encodeToString(RouteAlert.Registration.serializer(), contract.copy(alerts = contract.alerts.map { it.wire }))
        assertEquals(original, HTTP.json.parseToJsonElement(sent).jsonObject)
    }

    /** Alerts are kept as JSON and a failed decode would lose them all, so alerts saved before directions (and a direction a newer build wrote) must still load. */
    @Test fun alertsSavedBeforeDirectionsStillLoad() {
        val stored = """[{"id":"A","name":"","days":[1,2,3,4,5],"windows":[{"start":420,"end":600}],"minorDelays":true,"segments":[{"line":{"id":"elizabeth","name":"Elizabeth line"},"mode":"elizabeth-line","from":{"id":"910GLIVSTLL|910GLIVST","name":"Liverpool Street"},"to":{"id":"910GCANWHRF","name":"Canary Wharf"},"stations":["910GLIVSTLL|910GLIVST","910GWCHAPXR","910GCANWHRF"]}]},""" +
            """{"id":"B","name":"","days":[6],"windows":[{"start":600,"end":840}],"minorDelays":false,"segments":[],"direction":"sideways"}]"""
        val alerts = RouteAlert.decodeList(stored)
        assertEquals(2, alerts.size)
        assertNull(alerts[0].direction)
        assertEquals(RouteAlert.Direction.both, alerts[0].way)
        assertEquals(RouteAlert.Direction.both, alerts[1].direction)
        // Saved again, the old one still has no direction.
        val resaved = HTTP.json.parseToJsonElement(RouteAlert.encode(listOf(alerts[0]))).jsonArray[0].jsonObject
        assertFalse("direction" in resaved)
        val back = alerts[0].copy(direction = RouteAlert.Direction.back)
        assertEquals(RouteAlert.Direction.back, RouteAlert.decodeOne(RouteAlert.encodeOne(back))?.direction)
        // Something that isn't alerts at all loads as none, not a crash.
        assertEquals(emptyList(), RouteAlert.decodeList("{not json"))
        assertEquals(emptyList(), RouteAlert.decodeList(null))
    }

    @Test fun whichWayInTheEditor() {
        var alert = RouteAlert(segments = listOf(stretch("Liverpool Street", "Canary Wharf")))
        // A new alert is both ways, as the editor shows it.
        assertNull(alert.direction)
        assertEquals(RouteAlert.Direction.both, alert.way)
        assertEquals(listOf("To Canary Wharf", "To Liverpool Street", "Both ways"), RouteAlert.Direction.entries.map(alert::label))
        alert = alert.copy(direction = RouteAlert.Direction.back)
        assertEquals("Canary Wharf → Liverpool Street", alert.displayName)
        alert = alert.copy(direction = RouteAlert.Direction.out)
        assertEquals("Liverpool Street → Canary Wharf", alert.displayName)
        // Whole lines have no ends, so no choice.
        assertNull(RouteAlert(segments = listOf(wholeDistrict)).ends)
    }

    /** Alerts saved before times had ways, and a way a newer build wrote, still load. */
    @Test fun timesWithTheirOwnWay() {
        val stored = """{"id":"A","name":"","days":[1,2,3,4,5],"windows":[{"start":420,"end":600},{"start":990,"end":1170,"direction":"back"},{"start":1200,"end":1260,"direction":"sideways"}],"minorDelays":true,"direction":"out","segments":[{"line":{"id":"elizabeth","name":"Elizabeth line"},"mode":"elizabeth-line","from":{"id":"a","name":"Liverpool Street"},"to":{"id":"b","name":"Canary Wharf"},"stations":["a","b"]}]}"""
        var alert = assertNotNull(RouteAlert.decodeOne(stored))
        assertEquals(listOf(null, RouteAlert.Direction.back, RouteAlert.Direction.both), alert.windows.map { it.direction })
        assertTrue(alert.waysDiffer)
        assertEquals("Liverpool Street ⇄ Canary Wharf", alert.displayName)
        assertEquals("Weekdays, 07:00–10:00 to Canary Wharf, 16:30–19:30 to Liverpool Street and 20:00–21:00 both ways", alert.whenText)
        // Saved again, a time following the alert writes no way.
        val windows = HTTP.json.parseToJsonElement(RouteAlert.encodeOne(alert)).jsonObject.getValue("windows").jsonArray.map { it as JsonObject }
        assertFalse("direction" in windows[0])
        assertEquals("\"back\"", windows[1]["direction"].toString())

        // Times all going the same way: names and times as before.
        alert = alert.copy(windows = listOf(RouteAlert.TimeWindow(420, 600), RouteAlert.TimeWindow(990, 1170, RouteAlert.Direction.out)))
        assertFalse(alert.waysDiffer)
        assertEquals("Liverpool Street → Canary Wharf", alert.displayName)
        assertEquals("Weekdays, 07:00–10:00 and 16:30–19:30", alert.whenText)

        // Whole lines have no ends: no ways to tell apart.
        val whole = RouteAlert(segments = listOf(wholeDistrict)).let { it.copy(windows = listOf(it.windows[0], it.windows[1].copy(direction = RouteAlert.Direction.back))) }
        assertFalse(whole.waysDiffer)
        assertEquals("Weekdays, 07:00–10:00 and 16:30–19:30", whole.whenText)
    }

    /** A time's way set on purpose stays as set, even when it matches Which way then. */
    @Test fun aTimesOwnWayStaysWhenWhichWayChanges() {
        val alert = RouteAlert(segments = listOf(stretch("Reading", "Paddington"))).let {
            it.copy(windows = listOf(it.windows[0], it.windows[1].copy(direction = RouteAlert.Direction.both)))
        }
        // Saved as set (the editor stores the alert as it is), then Which way changes.
        var saved = assertNotNull(RouteAlert.decodeOne(RouteAlert.encodeOne(alert)))
        assertEquals(RouteAlert.Direction.both, saved.windows[1].direction)
        saved = saved.copy(direction = RouteAlert.Direction.out)
        assertEquals(RouteAlert.Direction.both, saved.way(saved.windows[1]))
        // A time's own way that matches Which way reads as no difference.
        saved = saved.copy(windows = listOf(saved.windows[0].copy(direction = RouteAlert.Direction.out), saved.windows[1]))
        assertEquals("Weekdays, 07:00–10:00 to Paddington and 16:30–19:30 both ways", saved.whenText)
        saved = saved.copy(windows = saved.windows.map { it.copy(direction = RouteAlert.Direction.out) })
        assertFalse(saved.waysDiffer)
        assertEquals("Reading → Paddington", saved.displayName)
        assertEquals("Weekdays, 07:00–10:00 and 16:30–19:30", saved.whenText)
    }

    @Test fun namesAndTimesForTheList() {
        var alert = RouteAlert(segments = listOf(stretch("Liverpool Street", "Canary Wharf")))
        assertEquals("Liverpool Street → Canary Wharf", alert.displayName)
        assertEquals("Weekdays, 07:00–10:00 and 16:30–19:30", alert.whenText)
        alert = alert.copy(name = "Work")
        assertEquals("Work", alert.displayName)
        assertEquals("Work", alert.wire.name)
        alert = alert.copy(days = listOf(6, 7), windows = listOf(RouteAlert.TimeWindow(600, 840)))
        assertEquals("Weekends, 10:00–14:00", alert.whenText)
        assertEquals("Mon, Wed and Sun", alert.copy(days = listOf(7, 1, 3)).daysText)
        assertEquals("Every day", alert.copy(days = (1..7).toList()).daysText)
        val whole = RouteAlert(segments = listOf(wholeDistrict))
        assertEquals("District line", whole.displayName)
        assertEquals("District line", whole.wire.name, "the name sent is always filled in")
        assertEquals("Whole line", whole.segments[0].stretch)
        assertEquals(LegMode.tube, whole.segments[0].legMode)
        assertEquals("District line and Elizabeth line", whole.copy(segments = listOf(wholeDistrict, RouteAlert.Segment(elizabethLine, "elizabeth-line"))).displayName)
    }

    @Test fun whatCanBeSaved() {
        val alert = RouteAlert(segments = listOf(wholeDistrict))
        assertTrue(alert.isValid)
        assertFalse(RouteAlert().isValid, "no line")
        assertFalse(alert.copy(days = emptyList()).isValid)
        assertFalse(alert.copy(windows = emptyList()).isValid)
        assertFalse(alert.copy(windows = listOf(RouteAlert.TimeWindow(600, 600))).isValid, "the server refuses a time that starts and ends together")
    }

    @Test fun readsTheServersStatus() {
        val json = """{"checkedAt":"2026-11-09T11:29:56.589Z","alerts":[{"id":"x","issues":[{"severity":"closed","title":"Canary Wharf closed (Elizabeth line)","detail":"Closed"}],"upcoming":[{"severity":"closed","title":"Elizabeth line: part closure","detail":"Sunday","from":"2026-11-15T04:30:00.000Z"}]}]}"""
        val status = AlertStatus.decode(json.encodeToByteArray())
        assertEquals(ServiceNotice.Severity.closed, status.alerts[0].issues[0].level)
        assertEquals(iso("2026-11-15T04:30:00Z"), status.alerts[0].upcoming[0].from)
        assertEquals(iso("2026-11-09T11:29:56.589Z"), status.checkedAt)
    }

    /** The server is whatever address was entered: what it says is cut to what a screen can show. */
    @Test fun serverTextIsKeptShort() {
        val issue = """{"severity":"${"s".repeat(100)}","title":"${"t".repeat(500)}","detail":"${"d".repeat(5000)}"}"""
        val json = """{"checkedAt":"2026-11-09T11:29:56Z","alerts":[${(1..100).joinToString(",") { """{"id":"${"i".repeat(200)}","issues":[${(1..30).joinToString(",") { issue }}],"upcoming":[]}""" }}]}"""
        val status = AlertStatus.decode(json.encodeToByteArray())
        assertEquals(AlertStatus.MAX_ALERTS, status.alerts.size)
        assertEquals(64, status.alerts[0].id.length)
        assertEquals(AlertStatus.MAX_ISSUES, status.alerts[0].issues.size)
        assertEquals(AlertStatus.MAX_TITLE, status.alerts[0].issues[0].title.length)
        assertEquals(AlertStatus.MAX_DETAIL, status.alerts[0].issues[0].detail.length)
        assertEquals(16, status.alerts[0].issues[0].severity.length)
        // Not the server's status at all.
        for (bad in listOf("", "[]", "{}", """{"checkedAt":"yesterday","alerts":[]}""", "<html>")) {
            assertTrue(runCatching { AlertStatus.decode(bad.encodeToByteArray()) }.exceptionOrNull() is ProviderError.BadResponse, bad)
        }
    }

    /** From a journey: weekdays or the weekend as it falls, from an hour before you'd leave to when you'd arrive, in quarter hours. */
    @Test fun aJourneySuggestsItsDaysAndTime() {
        val london = ZoneId.of("Europe/London")
        // Monday 9 November 2026, 08:07 to 08:52.
        val (days, window) = TfLLines.whenFor(iso("2026-11-09T08:07:00Z"), iso("2026-11-09T08:52:00Z"), london)
        assertEquals((1..5).toList(), days)
        assertEquals(RouteAlert.TimeWindow(7 * 60, 9 * 60), window)
        // A Saturday in summer: the phone's own time (BST), and the weekend.
        val (weekend, summer) = TfLLines.whenFor(iso("2026-07-04T09:30:00Z"), iso("2026-07-04T10:01:00Z"), london)
        assertEquals(listOf(6, 7), weekend)
        assertEquals(RouteAlert.TimeWindow(9 * 60 + 30, 11 * 60 + 15), summer)
        // Just after midnight, and a late one that would run past the day.
        assertEquals(0, TfLLines.whenFor(iso("2026-11-09T00:20:00Z"), iso("2026-11-09T00:50:00Z"), london).second.start)
        val late = TfLLines.whenFor(iso("2026-11-09T23:40:00Z"), iso("2026-11-10T00:10:00Z"), london).second
        assertEquals(22 * 60 + 30, late.start)
        assertEquals(23 * 60 + 45, late.end)
    }

    /** What is saved and can't be read is told apart from nothing saved: a notice isn't dropped for it. */
    @Test fun aSavedListThatCannotBeReadIsNotAnEmptyOne() {
        assertEquals(emptyList(), RouteAlert.decodeListOrNull(null))
        assertEquals(null, RouteAlert.decodeListOrNull("{not json"))
        assertEquals(listOf("A"), RouteAlert.decodeListOrNull(RouteAlert.encode(listOf(RouteAlert(id = "A"))))?.map { it.id })
    }
}
