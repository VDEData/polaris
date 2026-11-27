package polaris.android

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay
import polaris.engine.Coordinate
import polaris.engine.HttpRequest
import polaris.engine.HttpResponse
import polaris.engine.HttpTransport
import polaris.engine.JourneyQuery
import polaris.engine.Place
import polaris.engine.TimeMode

/**
 * Debug builds only: recorded answers instead of the network, for checking each screen's states
 * without asking TfL or Transitous anything. Started with an extra, never by default:
 *
 *     adb shell am start -n <app id>/polaris.android.MainActivity --es demo routes [--ez trip true]
 *
 * `demo` is one of: routes (no disruption), disruption, closure (the Elizabeth line closed), failing (Transitous answers 503), slow
 * (every answer after 4 s), empty (no routes), searchError (the geocoder answers 503).
 * `trip` opens King's Cross to Canary Wharf (the recordings' trip) at once.
 * Departure boards and "Leaving near you" answer from the recorded stop times at King's Cross.
 * Every recorded time is moved by the same amount, so the trip leaves a few minutes from now and
 * the boards line up with it (`stale` keeps the recordings' own dates).
 * Place search answers "king…" and "trafalgar…" from recordings, anything else with nothing.
 */
object Variant {
    fun transport(context: Context, intent: Intent?): HttpTransport? {
        // An alerts check alone runs on the recorded answers too (the routes scenario).
        val scenario = intent?.getStringExtra("demo") ?: if (intent?.getStringExtra("alerts") != null) "routes" else return null
        return DemoTransport(context.applicationContext, scenario, intent?.getStringExtra("alerts"))
    }

    /**
     * `--es alerts <state>`: the alerts pages on made-up answers, for checking each of their
     * states with no alerts server, no Firebase and nothing sent. A made-up server
     * (alerts.example.com, answered by the demo transport) stands in, in a store kept in memory,
     * so the real settings and saved alerts are not touched. States:
     * - `lines`: two saved alerts, one with a station closed and one with planned works; a push
     *   address made up; "Send a test notification" shows a notice drawn locally (simulated);
     * - `empty`: the server, no alerts yet;
     * - `noPush`: as `lines` in a build with no push service;
     * - `unavailable`: as `lines` on a phone without Google Play services;
     * - `pushFailed`: as `lines`, the push service giving no address;
     * - `cantPush`: as `lines`, the server saying it can't send to Android phones;
     * - `noServer`: no alerts server at all.
     * Never in release builds; shots of these are labelled simulated.
     */
    fun push(intent: Intent?): polaris.engine.PushRegistrar? {
        val state = intent?.getStringExtra("alerts") ?: return null
        return object : polaris.engine.PushRegistrar {
            override val availability = when (state) {
                "noPush", "noServer" -> polaris.engine.PushAvailability.notSetUp
                "unavailable" -> polaris.engine.PushAvailability.unavailable
                else -> polaris.engine.PushAvailability.ready
            }
            override suspend fun register(): String {
                delay(300)
                if (state == "pushFailed") throw IllegalStateException("simulated: no address")
                return "c2ltdWxhdGVkLWFkZHJlc3M"
            }
        }
    }

    /** With `--es alerts`: the saved alerts in memory, two of them unless the state has none. */
    fun values(context: Context, intent: Intent?): polaris.engine.KeyValueStore? {
        val state = intent?.getStringExtra("alerts") ?: return null
        return polaris.engine.InMemoryKeyValueStore().apply {
            if (state !in setOf("empty", "noServer")) putString(polaris.engine.AlertStore.ALERTS, polaris.engine.RouteAlert.encode(DEMO_ALERTS))
        }
    }

    /** Liverpool Street to Canary Wharf on the Elizabeth line, both ways at their own times; and the whole District line at weekends. */
    val DEMO_ALERTS = listOf(
        polaris.engine.RouteAlert(
            id = "DEMO-COMMUTE", direction = polaris.engine.RouteAlert.Direction.out,
            windows = listOf(polaris.engine.RouteAlert.TimeWindow(7 * 60, 10 * 60), polaris.engine.RouteAlert.TimeWindow(16 * 60 + 30, 19 * 60 + 30, polaris.engine.RouteAlert.Direction.back)),
            segments = listOf(
                polaris.engine.RouteAlert.Segment(
                    polaris.engine.RouteAlert.Line("elizabeth", "Elizabeth line"), "elizabeth-line",
                    polaris.engine.RouteAlert.Station("910GLIVSTLL|910GLIVST", "Liverpool Street"), polaris.engine.RouteAlert.Station("910GCANWHRF", "Canary Wharf"),
                    listOf("910GLIVSTLL|910GLIVST", "910GWCHAPXR", "910GCANWHRF"),
                ),
            ),
        ),
        polaris.engine.RouteAlert(
            id = "DEMO-WEEKEND", days = listOf(6, 7), windows = listOf(polaris.engine.RouteAlert.TimeWindow(10 * 60, 14 * 60)), minorDelays = false,
            segments = listOf(polaris.engine.RouteAlert.Segment(polaris.engine.RouteAlert.Line("district", "District line"), "tube")),
        ),
    )

    /**
     * `--ez unreadableKey true`: a simulated store whose TfL key reads as saved but unreadable
     * (as the Keystore does when it is locked), for checking the draft and Replace states. A key
     * written to it is kept in memory. Never in release builds; the real store is not touched.
     */
    fun secrets(intent: Intent?): polaris.engine.SecretStore? {
        // An alerts check: the made-up server, entered in a store kept in memory (none for `noServer`).
        intent?.getStringExtra("alerts")?.let { state ->
            return polaris.engine.InMemorySecretStore().apply {
                if (state != "noServer") write(polaris.engine.AlertServer.encodeRecord("https://alerts.example.com", "simulated"), polaris.engine.AlertServer.ACCOUNT)
            }
        }
        if (intent?.getBooleanExtra("unreadableKey", false) != true) return null
        return object : polaris.engine.SecretStore {
            private val memory = polaris.engine.InMemorySecretStore()
            private var replaced = false
            override fun read(account: String) =
                if (account == polaris.engine.AppSettings.TFL && !replaced) polaris.engine.SecretRead.Unreadable else memory.read(account)
            override fun write(value: String, account: String): Boolean {
                if (account == polaris.engine.AppSettings.TFL) replaced = true
                return memory.write(value, account)
            }
        }
    }

    /**
     * `--ei ahead N`: the app's clock runs N minutes ahead of the phone's (the recordings stay
     * moved to the real time), so a trip shows its later states without waiting: at the stop
     * after the train left, mid-ride, near the end. Debug only.
     */
    fun clock(intent: Intent?): (() -> java.time.Instant)? {
        val minutes = intent?.getIntExtra("ahead", 0) ?: 0
        if (minutes == 0) return null
        return { java.time.Instant.now().plusSeconds(minutes * 60L) }
    }

    /**
     * `--ez replanDemo true`: a re-plan's search answers with one hand-written way from wherever
     * Go asks, 12 minutes sooner than the trip, so the offer card, Switch and Keep can be seen. No
     * request is sent for it. Debug only, and shots of it are labelled simulated.
     */
    fun replanSearch(intent: Intent?): (suspend (JourneyQuery, polaris.engine.Replan.Watch) -> polaris.engine.ReplanAnswer)? {
        if (intent?.getBooleanExtra("replanDemo", false) != true) return null
        return { query, _ ->
            delay(900)
            val leaves = (query.time as? TimeMode.DepartAt)?.at ?: java.time.Instant.now()
            val from = query.origin.coordinate
            val to = query.destination.coordinate
            val board = leaves.plusSeconds(180)
            val dlr = polaris.engine.Leg(
                mode = polaris.engine.LegMode.dlr, line = "DLR", lineColor = polaris.engine.RGB.hex("00AFAD"), headsign = "Lewisham",
                fromName = query.origin.name, toName = "Heron Quays", departure = board, arrival = board.plusSeconds(9 * 60), stopCount = 5,
                path = listOf(from, Coordinate((from.latitude + to.latitude) / 2, (from.longitude + to.longitude) / 2), Coordinate(51.5027, -0.0215)),
                platform = "3",
            )
            val walk = polaris.engine.Leg(
                mode = polaris.engine.LegMode.walk, fromName = "Heron Quays", toName = query.destination.name,
                departure = dlr.arrival, arrival = dlr.arrival.plusSeconds(240), distanceMeters = 300.0, path = listOf(Coordinate(51.5027, -0.0215), to),
            )
            val journey = polaris.engine.Journey(
                departure = board, arrival = walk.arrival, legs = listOf(dlr, walk),
                sources = listOf(polaris.engine.SourceTag(polaris.engine.ProviderID.transitous, "Fastest")),
            )
            // Arrives well before anything the recordings can give from there.
            polaris.engine.ReplanAnswer(listOf(journey), answered = true, usedGoogle = false)
        }
    }

    fun startTrip(intent: Intent?): JourneyQuery? {
        if (intent?.getBooleanExtra("trip", false) != true) return null
        return JourneyQuery(
            Place(id = "demo-kx", name = "King's Cross", subtitle = "Camden", coordinate = Coordinate(51.5308, -0.1238)),
            Place(id = "demo-cw", name = "Canary Wharf", subtitle = "Tower Hamlets", coordinate = Coordinate(51.5054, -0.0235)),
            TimeMode.LeaveNow,
        )
    }
}

/**
 * Hand-written, not recorded: TfL's status shape with no validity period, so it applies to the
 * recorded routes' day. Severe delays on the Elizabeth line and minor delays on the Jubilee.
 */
private const val DISRUPTED_LINES = """[
  {"name":"Elizabeth line","modeName":"elizabeth-line","lineStatuses":[{"statusSeverity":6,"statusSeverityDescription":"Severe Delays",
   "reason":"Elizabeth line: severe delays between Paddington and Abbey Wood while a signal fault is fixed. Tickets are accepted on London Underground."}]},
  {"name":"Jubilee","modeName":"tube","lineStatuses":[{"statusSeverity":9,"statusSeverityDescription":"Minor Delays",
   "reason":"Jubilee line: minor delays due to an earlier faulty train."}]}
]"""

/** Hand-written too: the Elizabeth line closed, so routes on it can't be best and go under the others. */
private const val CLOSED_LINE = """[
  {"name":"Elizabeth line","modeName":"elizabeth-line","lineStatuses":[{"statusSeverity":20,"statusSeverityDescription":"Service Closed",
   "reason":"Elizabeth line: no service while engineering work takes place."}]}
]"""

private class DemoTransport(private val context: Context, private val scenario: String, private val alerts: String? = null) : HttpTransport {
    /**
     * The made-up alerts server: registration taken, what "TfL says" (a station closed on the
     * first demo alert, planned works on the second, good service on any other), and a test that
     * is drawn here as its notice would be, since nothing can be sent.
     */
    private fun alertsServer(request: HttpRequest): HttpResponse {
        fun json(text: String, status: Int = 200) = HttpResponse(status, text.encodeToByteArray())
        val path = java.net.URI(request.url).path
        return when {
            path == "/v1/status" -> {
                val ids = Regex(""""id":"([^"]+)"""").findAll(request.body?.decodeToString() ?: "").map { it.groupValues[1] }.filter { '|' !in it && it != "elizabeth" && it != "district" }.distinct().toList()
                val planned = java.time.LocalDate.now().plusDays(2).atTime(4, 30).toInstant(java.time.ZoneOffset.UTC)
                val entries = ids.map { id ->
                    when (id) {
                        "DEMO-COMMUTE" -> """{"id":"$id","issues":[{"severity":"closed","title":"Canary Wharf closed (Elizabeth line)","detail":"Canary Wharf: This station is closed while a faulty escalator is repaired. Use Heron Quays or Canary Wharf Jubilee line station instead."},{"severity":"minor","title":"Elizabeth line: minor delays","detail":"Minor delays between Paddington and Abbey Wood due to an earlier signal failure at Whitechapel."}],"upcoming":[]}"""
                        "DEMO-WEEKEND" -> """{"id":"$id","issues":[],"upcoming":[{"severity":"closed","title":"District line: part closure","detail":"No service between Earl's Court and Wimbledon while we carry out track work. Replacement buses run.","from":"$planned"}]}"""
                        else -> """{"id":"$id","issues":[],"upcoming":[]}"""
                    }
                }
                json("""{"checkedAt":"${java.time.Instant.now()}","alerts":[${entries.joinToString(",")}]}""")
            }
            path.endsWith("/test") -> {
                polaris.android.alerts.LineNotice.parse(
                    mapOf(
                        "alert" to "DEMO-COMMUTE", "tag" to "DEMO-COMMUTE-test", "title" to "Liverpool Street → Canary Wharf", "subtitle" to "Canary Wharf closed (Elizabeth line)",
                        "body" to "Canary Wharf: This station is closed while a faulty escalator is repaired.", "level" to "urgent",
                    ),
                    System.currentTimeMillis(),
                )?.let { polaris.android.alerts.LineNotices.show(context, it) }
                json("""{"status":200,"gone":false}""")
            }
            request.method == "PUT" -> json("""{"ok":true,"alerts":2,"pushes":${alerts != "cantPush"}}""")
            request.method == "DELETE" -> json("""{"ok":true}""")
            else -> json("""{"error":"Not found"}""", 404)
        }
    }

    /** How far the recordings move: their first journey (11:58 on 28 Oct 2026, GMT) to four minutes from now. */
    private val shift: java.time.Duration = if (scenario == "stale") java.time.Duration.ZERO else
        java.time.Duration.between(java.time.Instant.parse("2026-10-28T11:58:00Z"), java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).plusSeconds(240))

    /** Moves every time in a recording by [by]: ISO times with a Z, and TfL's London times without one (GMT on those dates). */
    private fun moved(bytes: ByteArray, by: java.time.Duration): ByteArray {
        if (by.isZero) return bytes
        val pattern = Regex("""(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(Z?)""")
        return pattern.replace(bytes.decodeToString()) { m ->
            val at = java.time.LocalDateTime.parse(m.groupValues[1]).toInstant(java.time.ZoneOffset.UTC).plus(by)
            java.time.LocalDateTime.ofInstant(at, java.time.ZoneOffset.UTC).toString().let { if (it.length == 16) "$it:00" else it } + m.groupValues[2]
        }.encodeToByteArray()
    }

    override suspend fun send(request: HttpRequest): HttpResponse {
        // Each request on one log line (host and path only), so a check can count them.
        android.util.Log.i("PolarisDemo", "request ${request.host}${java.net.URI(request.url).path}")
        if (scenario == "slow") delay(4_000) else delay(350)
        val url = request.url
        fun asset(name: String) = HttpResponse(200, moved(context.assets.open("demo/$name").use { it.readBytes() }, shift))
        /** Stop times recorded for a request at [recordedAt], moved to the time this request asks about. */
        fun stopTimes(name: String, recordedAt: String): HttpResponse {
            val asked = java.net.URI(url).rawQuery.split("&").firstOrNull { it.startsWith("time=") }?.substringAfter("=")
                ?.let { runCatching { java.time.Instant.parse(java.net.URLDecoder.decode(it, "UTF-8")) }.getOrNull() }
            val by = if (scenario == "stale" || asked == null) java.time.Duration.ZERO else java.time.Duration.between(java.time.Instant.parse(recordedAt), asked)
            return HttpResponse(200, moved(context.assets.open("demo/$name").use { it.readBytes() }, by))
        }
        fun json(text: String) = HttpResponse(200, text.encodeToByteArray())
        return when {
            request.host == "alerts.example.com" -> alertsServer(request)
            // TfL's lines, and one line's stations: the Elizabeth line's stand in for whichever line is opened.
            "/Line/Mode/" in url && "/Status" !in url -> HttpResponse(200, context.assets.open("demo/tfl-lines.json").use { it.readBytes() })
            "/Route/Sequence/" in url -> HttpResponse(200, context.assets.open("demo/tfl-route-elizabeth.json").use { it.readBytes() })
            "/api/v1/geocode" in url -> when {
                scenario == "searchError" -> HttpResponse(503, ByteArray(0))
                "text=king" in url -> asset("transitous-geocode-kings-cross.json")
                "text=trafalgar" in url -> asset("transitous-geocode-trafalgar-square.json")
                else -> json("[]")
            }
            "/api/v6/stoptimes" in url -> when {
                scenario == "failing" -> HttpResponse(503, ByteArray(0))
                // The H&C at King's Cross: hand-written, so the planned train is on it (the recording has none at the plan's time).
                "fetchStops=true" in url && "mode=SUBWAY" in url && isHammersmithAndCity(url) -> handWrittenBoard(url)
                // A board: recorded for the 18:08 Northern line train, asked 20 minutes before it.
                "fetchStops=true" in url && "mode=SUBWAY" in url -> stopTimes("transitous-departures.json", "2026-10-31T17:48:00Z")
                // Rail, the Elizabeth line and the DLR: hand-written (no recording covers them).
                "fetchStops=true" in url -> handWrittenBoard(url)
                else -> stopTimes("transitous-nearby.json", "2026-10-31T18:19:00Z")
            }
            "api.transitous.org" in url -> when (scenario) {
                "failing" -> HttpResponse(503, ByteArray(0))
                "empty" -> json("""{"itineraries":[]}""")
                else -> asset("transitous-plan.json")
            }
            "/Journey/JourneyResults/" in url -> if (scenario == "empty") json("""{"journeys":[]}""") else asset("tfl-journeys.json")
            "/Line/Mode/" in url -> json(
                when (scenario) {
                    "disruption" -> DISRUPTED_LINES
                    "closure" -> CLOSED_LINE
                    else -> "[]"
                },
            )
            "/StopPoint/Mode/" in url -> json("[]")
            else -> HttpResponse(404, ByteArray(0))
        }
    }
}

/** A board asked from the H&C's stop at King's Cross St. Pancras (the recorded best route's first ride). */
private fun isHammersmithAndCity(url: String): Boolean {
    val center = java.net.URI(url).rawQuery.split("&").firstOrNull { it.startsWith("center=") }?.substringAfter("=")?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: return false
    val (lat, lon) = center.split(",").map { it.toDouble() }
    return kotlin.math.abs(lat - 51.52971) < 0.0006 && kotlin.math.abs(lon + 0.12470) < 0.0008
}

/**
 * Hand-written, not recorded: a board for the recorded trip's rail rides, so the strip and board
 * show their states (yours, gone, late, live, cancelled, a sooner one worth running for). Every
 * few minutes from the asked time; yours leaves 20 minutes after it, as the request asks.
 */
private fun handWrittenBoard(url: String): HttpResponse {
    val query = java.net.URI(url).rawQuery.split("&").associate { it.substringBefore("=") to java.net.URLDecoder.decode(it.substringAfter("="), "UTF-8") }
    val asked = java.time.Instant.parse(query["time"])
    val (lat, lon) = query["center"]!!.split(",").map { it.toDouble() }
    data class Ride(val stop: String, val line: String, val headsign: String, val to: String, val toLat: Double, val toLon: Double, val minutes: Long, val track: String)
    val subway = "mode=SUBWAY" in url
    val ride = when {
        subway -> Ride("King's Cross St. Pancras", "Hammersmith & City", "Barking", "Farringdon", 51.52076, -0.10508, 3, "2")
        lat > 51.525 -> Ride("London St Pancras International", "Thameslink", "Brighton", "Farringdon", 51.5203, -0.1053, 4, "A")
        lon < -0.1 -> Ride("Farringdon", "Elizabeth line", "Abbey Wood", "Canary Wharf", 51.5036, -0.0189, 10, "B")
        else -> Ride("Bank", "DLR", "Lewisham", "Canary Wharf", 51.5051, -0.0209, 12, "10")
    }
    // Minutes after the asked time, minutes late, live, cancelled.
    // Fourteen rows from 8 minutes after the asked time, so a whole board can be scrolled to its end.
    val rows = listOf(
        Triple(8L, 0L, false), Triple(12L, 0L, false), Triple(16L, 2L, true), Triple(20L, 0L, true), Triple(23L, 0L, true), Triple(27L, 0L, false),
        Triple(31L, 4L, true), Triple(36L, 0L, false), Triple(39L, 0L, true), Triple(42L, 1L, true), Triple(45L, 0L, false), Triple(48L, 0L, true),
        Triple(51L, 0L, false), Triple(54L, 0L, true),
    )
    val times = rows.mapIndexed { i, (after, late, live) ->
        val scheduled = asked.plusSeconds(after * 60)
        val expected = scheduled.plusSeconds(late * 60)
        val cancelled = i == 5
        """{"place":{"name":"${ride.stop}","lat":$lat,"lon":$lon,"departure":"$expected","scheduledDeparture":"$scheduled","track":"${ride.track}","cancelled":$cancelled},""" +
            """"mode":"${if (subway) "SUBWAY" else "RAIL"}","realTime":$live,"headsign":"${ride.headsign}","tripId":"demo-$i","routeShortName":"${ride.line}","cancelled":$cancelled,""" +
            """"nextStops":[{"name":"${ride.to}","lat":${ride.toLat},"lon":${ride.toLon},"arrival":"${expected.plusSeconds(ride.minutes * 60)}","scheduledArrival":"${scheduled.plusSeconds(ride.minutes * 60)}"}]}"""
    }
    return HttpResponse(200, """{"stopTimes":[${times.joinToString(",")}]}""".encodeToByteArray())
}
