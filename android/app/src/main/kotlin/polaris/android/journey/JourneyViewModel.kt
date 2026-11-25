package polaris.android.journey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import polaris.android.app.AppGraph
import polaris.engine.DepartureBoard
import polaris.engine.Journey
import polaris.engine.JourneyQuery
import polaris.engine.Leg
import polaris.engine.Loadable
import java.time.Instant

/**
 * One route's page (iOS's `JourneySheet`): the journey from its results page's kept search, and a
 * departure board for each ride. Nothing is searched again: the journey comes from the archive
 * (memory, or the file after Android stopped the app). Each ride's board is asked for once when
 * the page opens, and at most once a minute after that (the store keeps them), so opening,
 * scrolling, rotating and coming back cost nothing more; a board's Refresh asks again.
 */
class JourneyViewModel(
    private val graph: AppGraph,
    private val searchId: String,
    private val journeyId: String,
    /** A board page: only this ride's board is asked for (the journey page asks for every ride's). */
    private val onlyLeg: String? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    data class Ui(
        val loading: Boolean = true,
        val journey: Journey? = null,
        val query: JourneyQuery? = null,
        /** Each ride's board by leg id; absent until asked for. */
        val boards: Map<String, Loadable<DepartureBoard>> = emptyMap(),
        /** Legs whose board is being refreshed (or held by the floor): each board its own spinner. */
        val refreshing: Set<String> = emptySet(),
    ) {
        /** For a change, you can't be at the stop before the ride before gets in (and the walk after it). */
        fun reachable(index: Int): Instant? {
            val legs = journey?.legs ?: return null
            val previous = (0 until index).lastOrNull { legs[it].mode.isTransit } ?: return null
            val walk = (previous + 1 until index).sumOf { legs[it].duration }
            return legs[previous].arrival.plusMillis((walk * 1000).toLong())
        }
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val search = withContext(io) { graph.archive.load(searchId) }
            val journey = search?.journeys?.firstOrNull { it.id == journeyId }
            _ui.value = _ui.value.copy(loading = false, journey = journey, query = search?.query)
            readBoards()
            if (journey != null) loadBoards(force = false)
        }
        viewModelScope.launch { graph.departures.version.collect { readBoards() } }
    }

    private fun readBoards() {
        val journey = _ui.value.journey ?: return
        _ui.value = _ui.value.copy(
            boards = journey.transitLegs.mapNotNull { leg -> graph.departures.board(leg)?.let { leg.id to it } }.toMap(),
        )
    }

    private suspend fun loadBoards(force: Boolean) {
        val legs = _ui.value.journey?.transitLegs?.filter { onlyLeg == null || it.id == onlyLeg } ?: return
        // One at a time, in order: the first ride's board matters most and comes first.
        for (leg in legs) graph.departures.load(leg, force)
    }

    /** One ride's board again (the board's Refresh). */
    fun refresh(leg: Leg) {
        if (leg.id in _ui.value.refreshing) return
        _ui.update { it.copy(refreshing = it.refreshing + leg.id) }
        viewModelScope.launch {
            // Within the floor nothing is sent, and the spinner stays for what's left of it.
            val held = graph.departures.boardHeldFor(leg)
            try {
                if (held > 0) kotlinx.coroutines.delay(held * 1000) else graph.departures.load(leg, force = true)
            } finally {
                _ui.update { it.copy(refreshing = it.refreshing - leg.id) }
            }
        }
    }

}
