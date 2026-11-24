package polaris.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Place search as you type, over Transitous's own geocoder (MOTIS `/api/v1/geocode`): stations,
 * stops, named places and addresses from OpenStreetMap and the timetables, with no key. It stands
 * in for Apple's place search on iOS. A chain or a kind of place ("Apple Store", "pharmacy") is
 * not something this geocoder answers well; the app hands those to a maps app instead.
 *
 * Transitous is run by volunteers, so typing must not send a request per keystroke. The rule:
 * - nothing is sent until the trimmed text is at least [MIN_LENGTH] characters;
 * - a request goes only after [DEBOUNCE_MILLIS] with no further typing, so a burst of typing
 *   sends one request, for what was typed last;
 * - each keystroke cancels the wait and any request still in flight, and an answer for text
 *   that has since changed is dropped;
 * - text already asked about (the same words, case and spacing aside, with the same bias) is
 *   answered from memory: the last [CACHE_SIZE] answers are kept for as long as this object is.
 * Every request goes through [HTTP], so it carries Polaris's User-Agent.
 */
class PlaceSearch(
    private val http: HTTP,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
) {
    data class State(
        val text: String = "",
        val results: List<PlaceMatch> = emptyList(),
        /** Waiting out the pause or for the answer. */
        val isSearching: Boolean = false,
        /** Why the last request failed ("Offline", "Rate limited"), until the next answer. */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var bias: Coordinate? = null
    private var pending: Job? = null
    private var generation = 0L
    private val cache = object : LinkedHashMap<String, List<PlaceMatch>>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<PlaceMatch>>?) = size > CACHE_SIZE
    }

    /** Prefer places near here (the person's location, or the other end of the trip). */
    fun bias(to: Coordinate?) {
        bias = to
    }

    /** Called on every keystroke. Main-thread confined, as a text field's callback is. */
    fun type(text: String) {
        pending?.cancel()
        val ticket = ++generation
        val words = normalised(text)
        if (words.length < MIN_LENGTH) {
            _state.value = State(text = text)
            return
        }
        val key = cacheKey(words, bias)
        cache[key]?.let {
            _state.value = State(text = text, results = it)
            return
        }
        _state.value = _state.value.copy(text = text, isSearching = true)
        val near = bias
        pending = scope.launch {
            delay(debounceMillis)
            val outcome = try {
                Result.success(parse(http.data(request(words, near))))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (ticket != generation) return@launch
            outcome.fold(
                onSuccess = { matches ->
                    cache[key] = matches
                    _state.value = State(text = text, results = matches)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(isSearching = false, error = describe(e))
                },
            )
        }
    }

    /** Clears the box: no request, no results. */
    fun clear() = type("")

    /** Offline, timed out and rate limited in the search's own words; anything else is one line. */
    private fun describe(e: Throwable): String = when (e) {
        ProviderError.BadResponse -> "Couldn't read the answer"
        is ProviderError.Http -> e.errorDescription
        else -> SearchModel.describe(e).takeUnless { it == "No routes" } ?: "Search failed"
    }

    companion object {
        const val MIN_LENGTH = 3
        const val DEBOUNCE_MILLIS = 300L
        const val CACHE_SIZE = 20
        const val RESULTS = 8
        /** The fixture's station as a stop and as a place is 190 m apart. */
        const val SAME_PLACE_METRES = 250.0

        /** Lower case, single spaces, trimmed: "  Kings  Cross " and "kings cross" are one question. */
        fun normalised(text: String): String = text.trim().lowercase().replace(Regex("\\s+"), " ")

        private fun cacheKey(words: String, bias: Coordinate?) = words + "|" + (bias?.pair ?: "")

        fun request(words: String, near: Coordinate?): HttpRequest = HttpRequest(
            HTTP.url(
                "https://api.transitous.org/api/v1/geocode",
                listOfNotNull(
                    "text" to words,
                    near?.let { "place" to it.pair },
                    "numResults" to RESULTS.toString(),
                ),
            ),
        )

        /**
         * The geocoder can list one place twice (a station as a stop and as a named place, or the
         * same id twice): each id once, and each name in the same area once, keeping the first,
         * which is the stop when there is one (it carries the modes).
         */
        fun parse(data: ByteArray): List<PlaceMatch> = HTTP.decode<List<Match>>(data).mapNotNull { m ->
            val kind = when (m.type) {
                "STOP" -> PlaceMatch.Kind.stop
                "ADDRESS" -> PlaceMatch.Kind.address
                "PLACE" -> PlaceMatch.Kind.place
                else -> return@mapNotNull null
            }
            val name = if (kind == PlaceMatch.Kind.address) {
                listOfNotNull(m.houseNumber, m.street ?: m.name).joinToString(" ").ifBlank { m.name }
            } else m.name
            // The area people would name it by (a borough, a town), which MOTIS marks as the default.
            val area = m.areas.firstOrNull { it.default == true }?.name ?: m.areas.lastOrNull()?.name
            val subtitle = listOfNotNull(if (kind == PlaceMatch.Kind.address) m.zip else null, area)
                .filter { it.isNotBlank() && it != name }.joinToString(", ").ifEmpty { null }
            PlaceMatch(
                place = Place(id = m.id, name = name, subtitle = subtitle, coordinate = Coordinate(m.lat, m.lon)),
                kind = kind,
                modes = m.modes ?: emptyList(),
            )
        }.let(::dedupe)

        /**
         * Each id once; one place listed twice (the same name in the same area, within 250 m) once,
         * in the geocoder's order, the stop's copy preferred. Same-named places further apart (two
         * High Streets, a road and a stop named after it) are different places and both stay.
         */
        fun dedupe(all: List<PlaceMatch>): List<PlaceMatch> {
            val ids = mutableSetOf<String>()
            val kept = mutableListOf<PlaceMatch>()
            for (match in all) {
                if (!ids.add(match.place.id)) continue
                val same = kept.indexOfFirst {
                    normalised(it.place.name) == normalised(match.place.name) && it.place.subtitle == match.place.subtitle &&
                        JourneyMap.metres(it.place.coordinate, match.place.coordinate) < SAME_PLACE_METRES
                }
                when {
                    same < 0 -> kept += match
                    kept[same].kind != PlaceMatch.Kind.stop && match.kind == PlaceMatch.Kind.stop -> kept[same] = match
                }
            }
            return kept
        }

        @Serializable
        private data class Match(
            val type: String,
            val name: String,
            val id: String,
            val lat: Double,
            val lon: Double,
            val areas: List<Area> = emptyList(),
            val street: String? = null,
            val houseNumber: String? = null,
            val zip: String? = null,
            val modes: List<String>? = null,
        )

        @Serializable
        private data class Area(val name: String, val default: Boolean? = null)
    }
}

/** One suggestion: the place, and what kind of thing it is (a stop gets a transit icon). */
data class PlaceMatch(val place: Place, val kind: Kind, val modes: List<String> = emptyList()) {
    @Suppress("EnumEntryName")
    enum class Kind { stop, address, place }
}
