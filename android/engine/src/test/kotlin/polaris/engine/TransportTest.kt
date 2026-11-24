package polaris.engine

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The real transport against a server on this machine's loopback address: offline, no fakes. */
class TransportTest {
    private val hits = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/moved") { exchange ->
            hits.incrementAndGet()
            exchange.responseHeaders.add("Location", "/here")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        createContext("/here") { exchange ->
            hits.incrementAndGet()
            val body = "ok:${exchange.requestHeaders.getFirst("User-Agent")}".encodeToByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        // Takes the request, then hangs up without answering: the case an HTTP client may
        // quietly resend. Polaris must not.
        createContext("/drop") { exchange ->
            hits.incrementAndGet()
            exchange.close()
        }
        start()
    }
    private val base = "http://${server.address.hostString}:${server.address.port}"
    private val http = HTTP(OkHttpTransport(), "0.1.0") { "someone@example.org" }

    @AfterTest fun stop() = server.stop(0)

    @Test fun followsRedirectsAsURLSessionDoes() = runTest {
        assertEquals("ok:Polaris/0.1.0 (Android transit app; someone@example.org)", http.data(HttpRequest("$base/moved")).decodeToString())
        assertEquals(2, hits.get())
    }

    @Test fun aDroppedRequestIsNeverSentTwice() = runTest {
        assertFailsWith<IOException> { http.data(HttpRequest("$base/drop")) }
        assertEquals(1, hits.get(), "GET sent once")
        assertFailsWith<IOException> { http.data(HttpRequest("$base/drop", "POST", mapOf("Content-Type" to "application/json"), "{}".encodeToByteArray())) }
        assertEquals(2, hits.get(), "POST sent once")
    }

    @Test fun errorsAreDescribedAsOnIOS() {
        assertEquals("Offline", SearchModel.describe(UnknownHostException("api.tfl.gov.uk")))
        assertEquals("Timed out", SearchModel.describe(SocketTimeoutException("timeout")))
        assertEquals("No routes", SearchModel.describe(IOException("unexpected end of stream")))
        assertEquals("Key rejected", SearchModel.describe(ProviderError.Http(403)))
        assertEquals("Rate limited", SearchModel.describe(ProviderError.Http(429)))
        assertEquals("No routes", SearchModel.describe(ProviderError.NoRoutes))
    }
}
