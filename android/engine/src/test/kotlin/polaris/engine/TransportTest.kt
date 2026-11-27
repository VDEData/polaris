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
import kotlin.test.assertTrue

/** The real transport against a server on this machine's loopback address: offline, no fakes. */
class TransportTest {
    private val hits = AtomicInteger()
    private val seen = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
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
        // Busy, "try again now": OkHttp resends a 503 with Retry-After: 0 on its own.
        createContext("/busy") { exchange ->
            hits.incrementAndGet()
            exchange.responseHeaders.add("Retry-After", "0")
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        createContext("/timeout") { exchange ->
            hits.incrementAndGet()
            exchange.sendResponseHeaders(408, -1)
            exchange.close()
        }
        // Sends the caller to another host (localhost, where it came in on 127.0.0.1).
        createContext("/elsewhere") { exchange ->
            hits.incrementAndGet()
            exchange.responseHeaders.add("Location", "http://localhost:${address.port}/seen")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        createContext("/seen") { exchange ->
            hits.incrementAndGet()
            seen += exchange.requestHeaders.keys.map { it.lowercase() }
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        // A megabyte: with its length declared, and (chunked) without.
        createContext("/big") { exchange ->
            hits.incrementAndGet()
            val body = ByteArray(1024 * 1024) { 'x'.code.toByte() }
            exchange.sendResponseHeaders(200, if (exchange.requestURI.query == "chunked") 0 else body.size.toLong())
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
    private val base = "http://127.0.0.1:${server.address.port}"
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

    @Test fun aBusyAnswerIsNotResent() = runTest {
        // "One request" means one on the wire, whatever the server says back.
        assertEquals(ProviderError.Http(503), assertFailsWith<ProviderError> { http.data(HttpRequest("$base/busy")) })
        assertEquals(1, hits.get(), "503 with Retry-After: 0 sent once")
        assertEquals(ProviderError.Http(408), assertFailsWith<ProviderError> { http.data(HttpRequest("$base/timeout")) })
        assertEquals(2, hits.get(), "408 sent once")
    }

    @Test fun keysNeverFollowARedirectToAnotherHost() = runTest {
        val google = HttpRequest("$base/elsewhere", "POST",
            mapOf("Content-Type" to "application/json", "X-Goog-Api-Key" to "test-key", "X-Android-Package" to "com.example.polaris"),
            "{}".encodeToByteArray())
        assertEquals(ProviderError.Http(307), assertFailsWith<ProviderError> { http.data(google) })
        // Nor does a plain GET go to another host.
        assertEquals(ProviderError.Http(307), assertFailsWith<ProviderError> { http.data(HttpRequest("$base/elsewhere")) })
        assertEquals(2, hits.get(), "neither redirect was followed")
        assertTrue(seen.isEmpty(), "nothing reached the other host")
    }

    /** The alerts server is whatever address was entered: a reply over the request's limit is refused, declared or not. */
    @Test fun aReplyOverTheLimitIsRefused() = runTest {
        val transport = OkHttpTransport()
        val base = "http://127.0.0.1:${server.address.port}"
        for (path in listOf("/big", "/big?chunked")) {
            val error = runCatching { transport.send(HttpRequest("$base$path", maxBodyBytes = 512 * 1024)) }.exceptionOrNull()
            assertTrue(error is OkHttpTransport.TooLarge, "$path: $error")
        }
        // Within the limit, and with none, it is read whole.
        assertEquals(1024 * 1024, transport.send(HttpRequest("$base/big", maxBodyBytes = 2L * 1024 * 1024)).body.size)
        assertEquals(1024 * 1024, transport.send(HttpRequest("$base/big?chunked", maxBodyBytes = 1024L * 1024)).body.size)
        assertEquals(1024 * 1024, transport.send(HttpRequest("$base/big")).body.size)
    }

    @Test fun errorsAreDescribedAsOnIOS() {
        assertEquals("Offline", SearchModel.describe(UnknownHostException("api.tfl.gov.uk")))
        assertEquals("Timed out", SearchModel.describe(SocketTimeoutException("timeout")))
        // No network at all: Android fails the connect with ENETUNREACH (iOS: not connected).
        assertEquals("Offline", SearchModel.describe(java.net.ConnectException("Failed to connect to api.tfl.gov.uk/1.2.3.4:443").apply {
            initCause(java.net.ConnectException("connect failed: ENETUNREACH (Network is unreachable)"))
        }))
        assertEquals("Offline", SearchModel.describe(java.net.NoRouteToHostException("No route to host")))
        // A server that refuses the connection is not "offline" on iOS either.
        assertEquals("No routes", SearchModel.describe(java.net.ConnectException("Connection refused")))
        assertEquals("No routes", SearchModel.describe(IOException("unexpected end of stream")))
        assertEquals("Key rejected", SearchModel.describe(ProviderError.Http(403)))
        assertEquals("Rate limited", SearchModel.describe(ProviderError.Http(429)))
        assertEquals("No routes", SearchModel.describe(ProviderError.NoRoutes))
    }
}
