package polaris.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import java.io.IOException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.pow

// Ported from Polaris/Providers/JourneyProvider.swift.

interface JourneyProvider {
    val id: ProviderID

    /**
     * `deep` fans the query out over the provider's preference variants
     * (fewer changes, less walking, mode filters) to surface routes its default ranking hides.
     */
    suspend fun journeys(query: JourneyQuery, deep: Boolean): List<Journey>
}

/**
 * Runs every query variant concurrently. One failing variant doesn't sink the
 * rest; only if all fail is the first error thrown.
 */
suspend fun <V> fanOut(variants: List<V>, fetch: suspend (V) -> List<Journey>): List<Journey> = coroutineScope {
    val results = variants.map { variant ->
        async {
            try {
                Result.success(fetch(variant))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }.awaitAll()
    val journeys = mutableListOf<Journey>()
    var firstError: Throwable? = null
    var succeeded = false
    for (result in results) {
        result.fold(
            onSuccess = { journeys += it; succeeded = true },
            onFailure = { if (firstError == null) firstError = it },
        )
    }
    if (!succeeded) firstError?.let { throw it }
    journeys
}

sealed class ProviderError(message: String) : Exception(message) {
    data object MissingKey : ProviderError("No API key")
    data class Http(val code: Int) : ProviderError(
        when (code) {
            429 -> "Rate limited"
            401, 403 -> "Key rejected"
            else -> "HTTP $code"
        },
    )
    data object NoRoutes : ProviderError("No routes")
    data object BadResponse : ProviderError("Unreadable response")

    val errorDescription: String get() = message ?: "Failed"
}

/** One outgoing request, as plain data, so tests can see exactly what would be sent. */
data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    /** Whether a redirect to the same host may be followed. Never to another host. */
    val followRedirects: Boolean = true,
) {
    val host: String get() = URI(url).host ?: ""

    /**
     * Method, host and path only: the query (TfL's `app_key`), the headers (Google's key, the
     * certificate hash) and the body never reach a log through `toString`.
     */
    override fun toString(): String = runCatching { URI(url) }.getOrNull()
        ?.let { "HttpRequest($method ${it.scheme}://${it.host}${it.rawPath ?: ""})" } ?: "HttpRequest($method redacted)"
}

data class HttpResponse(val status: Int, val body: ByteArray, val location: String? = null)

/** What sends requests: the network on a phone, a recording fake in tests. */
fun interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}

/**
 * OkHttp with every automatic follow-up switched off, so "one request per search" means one
 * request on the wire. `HttpURLConnection` may resend on a stale or failed connection (the JDK's,
 * and the copy built into Android) and neither can be told not to; OkHttp can, but on its own it
 * still resends a 503 that says `Retry-After: 0` and follows redirects to other hosts with every
 * header but `Authorization`. So here:
 * - no connection retries, and a guard that fails any second attempt at the same request;
 * - a 503's `Retry-After` is dropped before OkHttp's follow-up logic sees it;
 * - redirects are followed by Polaris itself, only to the same scheme, host and port, and only
 *   for requests that allow it (`HttpRequest.followRedirects`; Google's carry a key and don't).
 * Like URLSession it gives up after 20 seconds with nothing received, and cancelling the
 * coroutine cancels the call.
 */
class OkHttpTransport(client: OkHttpClient? = null) : HttpTransport {
    private class Attempts {
        val count = java.util.concurrent.atomic.AtomicInteger()
    }

    private val client = client ?: OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val attempts = chain.request().tag(Attempts::class.java)
            if (attempts != null && attempts.count.incrementAndGet() > 1) {
                throw IOException("Polaris sends each request once; a second attempt was refused")
            }
            val response = chain.proceed(chain.request())
            if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
        }
        .connectTimeout(HTTP.TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(HTTP.TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(HTTP.TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
        .build()

    override suspend fun send(request: HttpRequest): HttpResponse {
        var current = request
        repeat(MAX_REDIRECTS + 1) {
            val response = sendOnce(current)
            val next = redirect(current, response) ?: return response
            current = next
        }
        throw IOException("Too many redirects")
    }

    /** Where a redirect may take this request: the same scheme, host and port, or nowhere. */
    private fun redirect(request: HttpRequest, response: HttpResponse): HttpRequest? {
        if (!request.followRedirects || response.status !in setOf(301, 302, 303, 307, 308)) return null
        val location = response.location ?: return null
        val from = URI(request.url)
        val to = runCatching { from.resolve(location) }.getOrNull() ?: return null
        val samePlace = to.scheme.equals(from.scheme, ignoreCase = true) && to.host.equals(from.host, ignoreCase = true) && to.port == from.port
        if (!samePlace) return null
        // 303, and 301/302 after a POST, become a GET, as browsers and URLSession do.
        val toGet = response.status == 303 || (response.status in setOf(301, 302) && request.method == "POST")
        return if (toGet) request.copy(url = to.toString(), method = "GET", body = null, headers = request.headers - "Content-Type")
        else request.copy(url = to.toString())
    }

    private suspend fun sendOnce(request: HttpRequest): HttpResponse {
        val builder = Request.Builder().url(request.url).tag(Attempts::class.java, Attempts())
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val body = request.body?.toRequestBody(request.headers["Content-Type"]?.toMediaTypeOrNull())
        builder.method(request.method, body ?: if (request.method == "POST") ByteArray(0).toRequestBody() else null)
        val call = client.newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use { HttpResponse(it.code, it.body.bytes(), it.header("Location")) } }
                    result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}

/** A contact for the User-Agent: an email address or a web address, short and on one line. */
object Contact {
    /** Sent when neither settings nor the build gives a valid contact. A distributed build should set its own. */
    const val PROJECT = "https://github.com/VDEData/polaris"

    private val email = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

    fun valid(text: String?): String? {
        val trimmed = text?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed.length > 200 || trimmed.any { it == '\n' || it == '\r' || it == '(' || it == ')' }) return null
        if (email.matches(trimmed)) return trimmed
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        return if (scheme in setOf("https", "http") && "." in host) trimmed else null
    }
}

/**
 * Sends requests with Polaris's User-Agent. The open-data services ask to be able to reach
 * whoever runs a client, so the contact is the one entered in settings, else the build's, else
 * the project's page: there is always one.
 */
class HTTP(
    private val transport: HttpTransport,
    private val appVersion: String,
    private val contact: () -> String? = { null },
) {
    /** "Polaris/0.1.0 (Android transit app; <contact>)". */
    val userAgent: String get() = userAgent(appVersion, contact())

    suspend fun data(request: HttpRequest): ByteArray {
        val response = transport.send(request.copy(headers = request.headers + ("User-Agent" to userAgent)))
        if (response.status !in 200..299) throw ProviderError.Http(response.status)
        return response.body
    }

    companion object {
        const val TIMEOUT_MILLIS = 20_000

        fun userAgent(version: String, contact: String?): String {
            val valid = Contact.valid(contact) ?: Contact.PROJECT
            return "Polaris/$version (Android transit app; $valid)"
        }

        @PublishedApi internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        inline fun <reified T> decode(data: ByteArray): T = try {
            json.decodeFromString<T>(data.decodeToString())
        } catch (e: Exception) {
            throw ProviderError.BadResponse
        }

        /** Builds a URL with query items, encoded as Swift's URLComponents would send them. */
        fun url(base: String, items: List<Pair<String, String>>): String =
            if (items.isEmpty()) base else base + "?" + items.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }

        private fun encode(text: String): String =
            URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20").replace("%2C", ",").replace("%3A", ":")
    }
}

/** Seconds-based helpers, ported from Swift's `Time`. */
object Time {
    val london: ZoneId = ZoneId.of("Europe/London")

    /** RFC 3339 with or without fractional seconds. */
    fun iso(string: String?): Instant? {
        string ?: return null
        return runCatching { OffsetDateTime.parse(string).toInstant() }.getOrNull()
    }

    /** "2026-10-28T12:07:00Z": whole seconds, as Swift's `.iso8601` writes it. */
    fun isoString(date: Instant): String = date.truncatedTo(ChronoUnit.SECONDS).toString()

    /** TfL's zone-less "2026-10-28T12:07:00", which is London wall-clock time. */
    fun londonLocal(string: String?): Instant? {
        string ?: return null
        val parts = string.split('-', 'T', ':').filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
        if (parts.size < 5) return null
        return runCatching {
            ZonedDateTime.of(parts[0], parts[1], parts[2], parts[3], parts[4], if (parts.size > 5) parts[5] else 0, 0, london).toInstant()
        }.getOrNull()
    }

    fun londonComponents(date: Instant): ZonedDateTime = date.atZone(london).truncatedTo(ChronoUnit.MINUTES)
}

object Polyline {
    /** Google's encoded polyline format; Google uses precision 5, MOTIS 6 (or 7). */
    fun decode(encoded: String, precision: Int = 5): List<Coordinate> {
        val factor = 10.0.pow(precision)
        val coordinates = mutableListOf<Coordinate>()
        // Swift walks unicode scalars; outside the BMP a scalar is two chars here, both far past the range.
        val scalars = encoded.codePoints().toArray()
        var index = 0
        var lat = 0L
        var lon = 0L

        fun next(): Long? {
            var result = 0L
            var shift = 0
            while (index < scalars.size) {
                val byte = scalars[index].toLong() - 63
                index++
                result = result or ((byte and 0x1F) shl shift)
                shift += 5
                if (byte < 0x20) return if ((result and 1L) != 0L) (result shr 1).inv() else (result shr 1)
            }
            return null
        }

        while (index < scalars.size) {
            val dLat = next() ?: break
            val dLon = next() ?: break
            lat += dLat
            lon += dLon
            coordinates += Coordinate(lat / factor, lon / factor)
        }
        return coordinates
    }
}
