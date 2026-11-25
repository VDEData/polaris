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
 * Place search answers "king…" and "trafalgar…" from recordings, anything else with nothing.
 */
object Variant {
    fun transport(context: Context, intent: Intent?): HttpTransport? {
        val scenario = intent?.getStringExtra("demo") ?: return null
        return DemoTransport(context.applicationContext, scenario)
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

private class DemoTransport(private val context: Context, private val scenario: String) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse {
        // Each request on one log line (host and path only), so a check can count them.
        android.util.Log.i("PolarisDemo", "request ${request.host}${java.net.URI(request.url).path}")
        if (scenario == "slow") delay(4_000) else delay(350)
        val url = request.url
        fun asset(name: String) = HttpResponse(200, context.assets.open("demo/$name").use { it.readBytes() })
        fun json(text: String) = HttpResponse(200, text.encodeToByteArray())
        return when {
            "/api/v1/geocode" in url -> when {
                scenario == "searchError" -> HttpResponse(503, ByteArray(0))
                "text=king" in url -> asset("transitous-geocode-kings-cross.json")
                "text=trafalgar" in url -> asset("transitous-geocode-trafalgar-square.json")
                else -> json("[]")
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
