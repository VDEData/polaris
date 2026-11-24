package polaris.android.results

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import polaris.android.app.AppGraph
import polaris.engine.Journey
import polaris.engine.JourneyQuery
import polaris.engine.JourneyRanker
import polaris.engine.Place
import polaris.engine.ProviderID
import polaris.engine.ProviderStatus
import polaris.engine.RouteFilter
import polaris.engine.RouteOption
import polaris.engine.SearchModel
import polaris.engine.ServiceNotice
import polaris.engine.Snapshot
import polaris.engine.SortOrder
import polaris.engine.TimeMode
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One results page: the trip, every source's routes as they arrive, the order and filters, and
 * which route is picked. Ported from ResultsSheet.swift's logic.
 *
 * Request budget, the README's rules: one search per person's action, and only these actions
 * search: the page being opened for a trip, a swap, a new start, end or time, and "Search
 * again". Sorting, filtering, picking a route, folding "more routes", the sheet moving, a
 * rotation, and this view model being recreated (from [SavedStateHandle] and the archive after
 * Android stopped the app) send nothing.
 */
class ResultsViewModel(
    private val handle: SavedStateHandle,
    private val graph: AppGraph,
    /** This page's id: its search is archived under it. */
    private val id: String,
    initial: JourneyQuery,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Instant = Instant::now,
) : ViewModel() {
    data class Ui(
        val query: JourneyQuery,
        val statuses: Map<ProviderID, ProviderStatus> = emptyMap(),
        val journeys: List<Journey> = emptyList(),
        val networkChecked: Boolean = false,
        val sort: SortOrder = SortOrder.best,
        val filter: RouteFilter = RouteFilter(),
        val selectedId: String? = null,
        val showMore: Boolean = false,
        /** Finding where the person is before the search goes. */
        val locating: Boolean = false,
        /** The trip starts or ends at Current Location and no fix came. */
        val noFix: Boolean = false,
        /** Android recreated the page and its copy of the routes is gone: offer "Search again" rather than asking unprompted. */
        val stale: Boolean = false,
        /** Reading the page's kept routes back (off the main thread) after Android recreated it. */
        val restoring: Boolean = false,
        /** When the routes shown were found, if they came back from the kept copy. */
        val restoredFrom: Instant? = null,
        /** The trip as searched, with Current Location given its fix; null until a search has resolved it. */
        val resolved: JourneyQuery? = null,
    ) {
        val isSearching: Boolean get() = locating || statuses.values.any { it == ProviderStatus.Loading }
        val ranked: JourneyRanker.Ranked by lazy { JourneyRanker.rank(journeys, query.time.isArriveBy, sort, filter) }
        /** The gold star: the first of the top routes (the ranker never puts one through a closure there). */
        val best: RouteOption? get() = ranked.top.firstOrNull()
        /** What the map draws in colour: the picked route if it is still listed, else the best. */
        val shown: RouteOption? get() = (ranked.top + ranked.more).firstOrNull { it.id == selectedId } ?: best
        /** Everything TfL reports about these routes, worst first, each once. */
        val notices: List<ServiceNotice> get() = notices(ranked)
    }

    private val _ui = MutableStateFlow(restore(initial))
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private var model: SearchModel? = null
    private var searching: Job? = null

    // Declared before init, which may archive straight away on the main thread.
    /** The copy waiting to be written; each write takes the latest, so an older copy never lands last. */
    private val unwritten = AtomicReference<Snapshot.Search?>(null)
    private val writing = Mutex()

    init {
        // A page that has never searched is a trip the person just asked for: search it once.
        // One that has (Android recreated it) shows its kept copy, or offers Search again.
        if (handle.get<Boolean>(STARTED) != true) search() else readBack()
    }

    private fun restore(initial: JourneyQuery): Ui {
        val settings = graph.settings
        val query = handle.get<String>(QUERY)?.let(Snapshot::decodeQuery) ?: initial
        return Ui(
            query = query, sort = settings.sortOrder, filter = settings.routeFilter,
            selectedId = handle.get<String>(SELECTED), showMore = handle.get<Boolean>(MORE) ?: false,
            restoring = handle.get<Boolean>(STARTED) == true,
        )
    }

    /**
     * The kept copy, read off the main thread, and used only if it is this trip: a copy of the
     * trip before an edit (a new start whose fix never came, say) is not shown under the new one.
     */
    private fun readBack() {
        viewModelScope.launch {
            val copy = withContext(io) { graph.archive.load(id) }
            val ui = _ui.value
            if (!ui.restoring) return@launch
            _ui.value = if (copy == null || Snapshot.encode(copy.query) != Snapshot.encode(ui.query)) {
                ui.copy(restoring = false, stale = true)
            } else {
                ui.copy(restoring = false, statuses = copy.statuses, journeys = copy.journeys, networkChecked = copy.networkChecked, restoredFrom = copy.savedAt)
            }
        }
    }

    /**
     * Routes brought back from the kept copy that are old enough to have changed: past 10 minutes
     * for Leave now, an hour for a set time. iOS keeps no copy, so these figures are Android's own.
     */
    fun isOld(ui: Ui, at: Instant = now()): Boolean {
        val from = ui.restoredFrom ?: return false
        val age = java.time.Duration.between(from, at)
        return age > (if (ui.query.time == TimeMode.LeaveNow) OLD_LEAVE_NOW else OLD_SET_TIME)
    }

    // The person's actions.

    fun swap() {
        val q = _ui.value.query
        if (q.origin.isCurrentLocation && q.destination.isCurrentLocation) return
        setQuery(q.copy(origin = q.destination, destination = q.origin))
    }

    fun setOrigin(place: Place) = setQuery(_ui.value.query.copy(origin = place))
    fun setDestination(place: Place) = setQuery(_ui.value.query.copy(destination = place))
    fun setTime(time: TimeMode) = setQuery(_ui.value.query.copy(time = time))
    fun searchAgain() = search()

    /** Remembered between searches, as on iOS. No request. */
    fun setSort(sort: SortOrder) {
        graph.settings.sortOrder = sort
        _ui.value = _ui.value.copy(sort = sort)
    }

    /** Remembered between searches, as on iOS. No request. */
    fun setFilter(filter: RouteFilter) {
        graph.settings.routeFilter = filter
        _ui.value = _ui.value.copy(filter = filter)
    }

    fun select(journeyId: String) {
        handle[SELECTED] = journeyId
        _ui.value = _ui.value.copy(selectedId = journeyId)
    }

    fun toggleMore() {
        val more = !_ui.value.showMore
        handle[MORE] = more
        _ui.value = _ui.value.copy(showMore = more)
    }

    private fun setQuery(query: JourneyQuery) {
        if (query == _ui.value.query) return
        handle[QUERY] = Snapshot.encode(query)
        _ui.value = _ui.value.copy(query = query)
        search()
    }

    /**
     * Searches the trip once. A trip from (or to) Current Location gets a fresh fix first, as on
     * iOS: the one saved with the trip may be from before the person moved.
     */
    private fun search() {
        handle[STARTED] = true
        handle[SELECTED] = null
        searching?.cancel()
        val query = _ui.value.query
        _ui.value = _ui.value.copy(stale = false, noFix = false, selectedId = null, locating = query.usesHere,
            statuses = emptyMap(), journeys = emptyList(), networkChecked = false, restoring = false, restoredFrom = null, resolved = null)
        val model = model ?: graph.searchModel().also { model = it }
        searching = viewModelScope.launch {
            var resolved = query
            if (query.usesHere) {
                val here = graph.locator.current()
                if (here == null) {
                    _ui.value = _ui.value.copy(locating = false, noFix = true)
                    return@launch
                }
                resolved = query.copy(
                    origin = if (query.origin.isCurrentLocation) query.origin.copy(coordinate = here) else query.origin,
                    destination = if (query.destination.isCurrentLocation) query.destination.copy(coordinate = here) else query.destination,
                )
            }
            _ui.value = _ui.value.copy(locating = false, resolved = resolved)
            val follow = launch {
                model.state.collect { s ->
                    if (s.query != resolved) return@collect
                    _ui.value = _ui.value.copy(statuses = s.statuses, journeys = s.journeys, networkChecked = s.network != null)
                    archive()
                }
            }
            model.search(resolved, graph.settings)
            follow.cancel()
        }
    }

    private fun archive() {
        val ui = _ui.value
        val copy = Snapshot.Search(ui.query, ui.statuses, ui.journeys, ui.networkChecked, now())
        graph.archive.hold(id, copy)
        unwritten.set(copy)
        viewModelScope.launch(io) {
            writing.withLock { unwritten.getAndSet(null)?.let { graph.archive.save(id, it) } }
        }
    }

    companion object {
        const val QUERY = "query"
        const val STARTED = "started"
        const val SELECTED = "selected"
        const val MORE = "more"
        val OLD_LEAVE_NOW: java.time.Duration = java.time.Duration.ofMinutes(10)
        val OLD_SET_TIME: java.time.Duration = java.time.Duration.ofHours(1)

        private val JourneyQuery.usesHere get() = origin.isCurrentLocation || destination.isCurrentLocation

        fun notices(ranked: JourneyRanker.Ranked): List<ServiceNotice> {
            val seen = mutableSetOf<String>()
            return (ranked.top + ranked.more).flatMap { it.journey.notices }
                .filter { seen.add(it.title + it.detail) }
                .sortedWith(compareByDescending<ServiceNotice> { it.severity }.thenBy { it.title })
        }
    }
}
