package polaris.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Place search over Transitous's geocoder: what is asked, how the answer is read, and above all
 * how few requests typing sends. Requests are counted on a fake transport, in virtual time.
 */
class PlaceSearchTest {
    /** Answers geocode requests from the recordings, optionally held until released. */
    private class GeocodeTransport : HttpTransport {
        val sent = mutableListOf<HttpRequest>()
        var hold: CompletableDeferred<Unit>? = null

        override suspend fun send(request: HttpRequest): HttpResponse {
            sent += request
            hold?.await()
            val text = query(request)["text"] ?: ""
            val fixture = if ("trafalgar" in text) "transitous-geocode-trafalgar-square.json" else "transitous-geocode-kings-cross.json"
            return HttpResponse(200, Fixtures.bytes(fixture))
        }
    }

    private fun TestScope.rig(): Pair<PlaceSearch, GeocodeTransport> {
        val transport = GeocodeTransport()
        val http = HTTP(transport, "0.1.0") { "https://example.org/polaris" }
        return PlaceSearch(http, backgroundScope) to transport
    }

    /** Lets the pause and any answer play out. (`advanceUntilIdle` leaves `backgroundScope` work alone.) */
    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    /** Types one character at a time, `gap` milliseconds apart, as a person would. */
    private suspend fun TestScope.typeSlowly(search: PlaceSearch, text: String, gap: Long) {
        for (i in 1..text.length) {
            search.type(text.take(i))
            advanceTimeBy(gap)
        }
    }

    @Test fun aBurstOfTypingSendsOneRequestForWhatWasTypedLast() = runTest {
        val (search, transport) = rig()
        typeSlowly(search, "kings cross st pancras", gap = 120)
        settle()
        assertEquals(1, transport.sent.size)
        assertEquals("kings cross st pancras", query(transport.sent.single())["text"])
        assertEquals("King's Cross and St Pancras", search.state.value.results.first().place.name)
        assertEquals(false, search.state.value.isSearching)
    }

    @Test fun aPauseLongerThanTheDebounceSendsAgain() = runTest {
        val (search, transport) = rig()
        typeSlowly(search, "kings", gap = 100)
        advanceTimeBy(PlaceSearch.DEBOUNCE_MILLIS + 50)
        typeSlowly(search, "kings cross", gap = 100)
        settle()
        // "kings" after the pause, then "kings cross"; retyping "kings" on the way is answered from memory.
        assertEquals(listOf("kings", "kings cross"), transport.sent.map { query(it)["text"] })
    }

    @Test fun shortTextSendsNothingAndClearsTheResults() = runTest {
        val (search, transport) = rig()
        search.type("ki")
        settle()
        search.type("  k  ")
        settle()
        assertEquals(0, transport.sent.size)
        assertEquals(emptyList(), search.state.value.results)
        assertEquals(false, search.state.value.isSearching)
    }

    @Test fun clearingSendsNothingAndEmptiesTheList() = runTest {
        val (search, transport) = rig()
        search.type("trafalgar square")
        settle()
        assertEquals(5, search.state.value.results.size)
        search.clear()
        settle()
        assertEquals(1, transport.sent.size)
        assertEquals("", search.state.value.text)
        assertEquals(emptyList(), search.state.value.results)
    }

    @Test fun theSameWordsAreNotAskedTwice() = runTest {
        val (search, transport) = rig()
        search.type("Trafalgar Square")
        settle()
        search.type("trafalgar")
        settle()
        // Back to the same words, spelt with other case and spaces: answered from memory, at once.
        search.type("  trafalgar   SQUARE ")
        assertEquals(5, search.state.value.results.size)
        settle()
        assertEquals(listOf("trafalgar square", "trafalgar"), transport.sent.map { query(it)["text"] })
    }

    @Test fun aKeystrokeCancelsTheRequestInFlightAndItsAnswerIsDropped() = runTest {
        val (search, transport) = rig()
        val hold = CompletableDeferred<Unit>()
        transport.hold = hold
        search.type("trafalgar square")
        advanceTimeBy(PlaceSearch.DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertEquals(1, transport.sent.size)
        // Still waiting for that answer when the person types on.
        search.type("kings cross st pancras")
        hold.complete(Unit)
        settle()
        assertEquals(2, transport.sent.size)
        assertEquals("King's Cross and St Pancras", search.state.value.results.first().place.name)
        assertTrue(search.state.value.results.none { it.place.name == "Trafalgar Square" })
    }

    @Test fun eachRequestCarriesTheUserAgentAndTheBias() = runTest {
        val (search, transport) = rig()
        search.bias(Coordinate(51.508, -0.128))
        search.type("trafalgar square")
        settle()
        val request = transport.sent.single()
        assertEquals("Polaris/0.1.0 (Android transit app; https://example.org/polaris)", request.headers["User-Agent"])
        assertEquals("api.transitous.org", request.host)
        assertEquals("/api/v1/geocode", URI(request.url).path)
        assertEquals(mapOf("text" to "trafalgar square", "place" to "51.508,-0.128", "numResults" to "8"), query(request))
    }

    @Test fun withNoBiasNoPlaceIsSent() {
        val request = PlaceSearch.request("trafalgar square", null)
        assertNull(query(request)["place"])
    }

    @Test fun anErrorIsSaidAndTheNextAnswerClearsIt() = runTest {
        var fail = true
        val transport = HttpTransport { request ->
            if (fail) HttpResponse(429, ByteArray(0)) else HttpResponse(200, Fixtures.bytes("transitous-geocode-trafalgar-square.json"))
        }
        val search = PlaceSearch(HTTP(transport, "0.1.0"), backgroundScope)
        search.type("trafalgar square")
        settle()
        assertEquals("Rate limited", search.state.value.error)
        fail = false
        search.type("trafalgar squar")
        settle()
        assertNull(search.state.value.error)
        assertEquals(5, search.state.value.results.size)
    }

    @Test fun readsTheRecordedAnswer() {
        val matches = PlaceSearch.parse(Fixtures.bytes("transitous-geocode-kings-cross.json"))
        assertEquals(5, matches.size)
        val station = matches.first()
        assertEquals(PlaceMatch.Kind.stop, station.kind)
        assertEquals("King's Cross and St Pancras", station.place.name)
        assertEquals("London Borough of Camden", station.place.subtitle)
        assertTrue(station.place.coordinate.latitude in 51.52..51.54 && station.place.coordinate.longitude in -0.13..-0.12)
        assertEquals(PlaceMatch.Kind.place, matches[2].kind)
        // Each keeps the geocoder's id, so the same place picked twice is the same place.
        assertEquals(matches.map { it.place.id }.distinct().size, matches.size)
    }

    @Test fun anAddressIsNamedByItsNumberAndStreet() {
        // Hand-written in the geocoder's shape: a public address, not a recording.
        val json = """[{"type":"ADDRESS","name":"Downing Street","id":"a1","lat":51.5034,"lon":-0.1276,
            "street":"Downing Street","houseNumber":"10","zip":"SW1A 2AA","tokens":[],"score":1,
            "areas":[{"name":"City of Westminster","adminLevel":8,"matched":false,"default":true}]},
            {"type":"SOMETHING_NEW","name":"x","id":"b","lat":0,"lon":0,"areas":[],"tokens":[],"score":0}]"""
        val match = PlaceSearch.parse(json.encodeToByteArray()).single()
        assertEquals(PlaceMatch.Kind.address, match.kind)
        assertEquals("10 Downing Street", match.place.name)
        assertEquals("SW1A 2AA, City of Westminster", match.place.subtitle)
    }

    companion object {
        fun query(request: HttpRequest): Map<String, String> =
            (URI(request.url).rawQuery ?: "").split("&").filter { it.isNotEmpty() }.associate {
                val (name, value) = it.split("=", limit = 2)
                URLDecoder.decode(name, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
            }
    }
}
