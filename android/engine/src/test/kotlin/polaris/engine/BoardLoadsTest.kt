package polaris.engine

import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Go asks for the next ride's board on every look (every two seconds). With the source down, or
 * no network, that must not turn into a request every few seconds from every phone on a trip.
 */
class BoardLoadsTest {
    private val t0 = Instant.parse("2026-11-16T06:30:00Z")
    private val leg = Leg(
        mode = LegMode.tube, line = "Northern", fromName = "King's Cross", toName = "Bank",
        departure = t0.plusSeconds(40 * 60), arrival = t0.plusSeconds(52 * 60),
        path = listOf(Coordinate(51.5308, -0.1238), Coordinate(51.5133, -0.0890)),
    )

    private class Counting(val fail: Boolean) : HttpTransport {
        var count = 0
        override suspend fun send(request: HttpRequest): HttpResponse {
            count += 1
            if (fail) throw java.net.ConnectException("refused")
            return HttpResponse(200, """{"stopTimes":[]}""".encodeToByteArray())
        }
    }

    /** Ten minutes of looks every two seconds; returns how many requests went out. */
    private fun tenMinutes(fail: Boolean, online: Boolean = true): Int {
        val transport = Counting(fail)
        var now = t0
        val store = DepartureStore(DepartureProvider(HTTP(transport, "test")), { now })
        val loads = BoardLoads(store, { now }, { online })
        runTest {
            repeat(300) {
                loads.load(leg)
                now = now.plusSeconds(2)
            }
        }
        return transport.count
    }

    @Test fun aSourceThatIsDownIsAskedLessAndLessOften() {
        // Tries at 0, 15, 45, 105, 225, 345, 465 and 585 s: eight, where the store's own rule
        // (first retry at once, then every 5 s) would send over a hundred.
        val sent = tenMinutes(fail = true)
        assertTrue(sent <= 8, "sent $sent requests in ten minutes")
        assertTrue(sent >= 6, "still tries again ($sent)")
    }

    @Test fun nothingIsSentWithoutANetwork() {
        assertEquals(0, tenMinutes(fail = true, online = false))
    }

    @Test fun aHealthyBoardIsAskedOnceAMinute() {
        val sent = tenMinutes(fail = false)
        assertTrue(sent in 9..11, "sent $sent")
    }
}
