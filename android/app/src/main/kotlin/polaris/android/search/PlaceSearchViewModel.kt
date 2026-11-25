package polaris.android.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import polaris.android.app.AppGraph
import polaris.engine.Coordinate
import polaris.engine.PlaceMatch
import polaris.engine.PlaceSearch

/**
 * The place search view: Transitous's geocoder as the person types, through the engine's
 * [PlaceSearch] and so its rule (nothing under 3 characters, one request per pause in typing,
 * answers for replaced text dropped). Created when the view opens and cleared when it closes,
 * so its answers are remembered only while it is open.
 *
 * After Android stops and recreates the app, the typed text comes back but is not searched
 * again unasked: the view offers to.
 */
class PlaceSearchViewModel(
    private val handle: SavedStateHandle,
    graph: AppGraph,
    /** Places near here first: the person's last fix, or the trip's other end. */
    bias: Coordinate?,
) : ViewModel() {
    private val search = graph.placeSearch(viewModelScope).also { it.bias(bias) }

    data class Ui(
        val text: String = "",
        val results: List<PlaceMatch> = emptyList(),
        val isSearching: Boolean = false,
        val error: String? = null,
        /** Text brought back after the app was recreated, not yet searched. */
        val restored: Boolean = false,
    ) {
        /** Long enough to search: below this the view shows Home, Work and recent places. */
        val isQuery: Boolean get() = PlaceSearch.normalised(text).length >= PlaceSearch.MIN_LENGTH
        val noResults: Boolean get() = isQuery && !isSearching && error == null && !restored && results.isEmpty()
    }

    private val restoredText = MutableStateFlow(handle.get<String>(TEXT)?.takeIf { it.isNotEmpty() })

    val ui: StateFlow<Ui> = combine(search.state, restoredText) { s, restored ->
        if (restored != null && s.text.isEmpty()) Ui(text = restored, restored = true)
        else Ui(s.text, s.results, s.isSearching, s.error)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Ui(text = restoredText.value ?: "", restored = restoredText.value != null))

    fun type(text: String) {
        restoredText.value = null
        handle[TEXT] = text
        search.type(text)
    }

    /** "Search again" for text brought back after a restart: one request, as typing it would be. */
    fun searchRestored() {
        val text = restoredText.value ?: return
        type(text)
    }

    companion object {
        const val TEXT = "text"

        /**
         * The maps-app hand-off for a chain or kind of place ("pharmacy"), which Transitous's
         * geocoder doesn't answer well: a `geo:` link to whichever maps app the person uses,
         * near [near] (their position or the trip's other end) when known.
         */
        fun mapsLink(text: String, near: Coordinate?): String {
            val at = near?.let { "${Coordinate.plain(it.latitude)},${Coordinate.plain(it.longitude)}" } ?: "0,0"
            return "geo:$at?q=" + java.net.URLEncoder.encode(text.trim(), "UTF-8").replace("+", "%20")
        }
    }
}
