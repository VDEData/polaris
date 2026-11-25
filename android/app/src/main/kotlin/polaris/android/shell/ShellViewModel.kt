package polaris.android.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import polaris.android.app.AppGraph
import polaris.android.map.Recentre
import polaris.engine.Coordinate
import polaris.engine.JourneyQuery
import polaris.engine.Place
import polaris.engine.PlaceStore
import polaris.engine.Snapshot
import polaris.engine.TimeMode

/**
 * What the whole window shares: the place search over everything, where a trip from Home
 * starts, and the location button. Location is asked for only when the person does something
 * that needs it (plans from where they are, taps the location button, picks Current Location),
 * never at launch. Trips are searched by the results page; the one request sent from here is
 * "Leaving near you" ([refreshNearby]), and only once location is allowed.
 */
class ShellViewModel(private val graph: AppGraph) : ViewModel() {
    val places: StateFlow<PlaceStore.Places> get() = graph.places.places

    /** Something that waits on location. The screen asks Android when [ask] is true, then calls [permissionResult]. */
    sealed interface Need {
        val ask: Boolean
        /** Plan to [destination] from here. */
        data class Trip(val destination: Place, override val ask: Boolean = true) : Need
        /** Centre the map on the person. */
        data class Map(override val ask: Boolean = true) : Need
        /** Pick Current Location in a search for [kind]. */
        data class Pick(val kind: SearchFor, override val ask: Boolean = true) : Need
        /** No start could be found for a trip: "Can't find where you are". */
        data class Stranded(val destination: Place, val locationOff: Boolean) : Need { override val ask get() = false }
        /** Refused (Android no longer asks after two refusals): say so, with the way to Settings. */
        data object LocationOff : Need { override val ask get() = false }
    }

    private val _need = MutableStateFlow<Need?>(null)
    val need: StateFlow<Need?> = _need.asStateFlow()

    /** Finding where the person is for a trip; further taps wait for it rather than starting another. */
    private var locating: kotlinx.coroutines.Job? = null
    /** Where the trip being located goes: the latest place tapped while waiting replaces the one before. */
    private var pending: Place? = null
    /** A page was sent and isn't on top yet: further taps on Home are the same wish, until [pageShown]. */
    private var awaitingPage = false
    private val _finding = MutableStateFlow(false)
    val finding: StateFlow<Boolean> = _finding.asStateFlow()

    /** A place picked for a results page's start or end, until that page takes it. */
    data class EndPick(val page: String, val isOrigin: Boolean, val place: Place)
    private val _endPick = MutableStateFlow<EndPick?>(null)
    val endPick: StateFlow<EndPick?> = _endPick.asStateFlow()

    private val _recentre = MutableStateFlow<Recentre?>(null)
    val recentre: StateFlow<Recentre?> = _recentre.asStateFlow()

    /** Whether the map shows the person's dot: once they've allowed location. */
    private val _showsLocation = MutableStateFlow(graph.locator.hasPermission)
    val showsLocation: StateFlow<Boolean> = _showsLocation.asStateFlow()

    private val pushes = Channel<NavKey>(Channel.BUFFERED)
    /** Pages to push on the sheet's stack, each once. */
    val navigation: Flow<NavKey> = pushes.receiveAsFlow()

    /** A place search's pick, sent where its kind says. The screen closes the search. */
    fun picked(kind: SearchFor, place: Place) {
        when (kind) {
            SearchFor.Destination -> planTo(place)
            is SearchFor.Origin -> Snapshot.decodePlace(kind.destination)?.let { planTo(it, origin = place) }
            SearchFor.Home -> graph.places.home = place
            SearchFor.Work -> graph.places.work = place
            is SearchFor.ResultsEnd -> _endPick.value = EndPick(kind.page, kind.isOrigin, place)
        }
    }

    fun endPickTaken() {
        _endPick.value = null
    }

    /** Plan to a place, from [origin] or from where the person is now (as iOS's `go(to:)`). */
    fun planTo(destination: Place, origin: Place? = null) {
        // Once a page is on its way, a tap on Home before it appears is the same wish (a double tap).
        if (awaitingPage) return
        graph.places.remember(destination)
        // Waiting for a fix or for the person's answer: this place replaces the one waiting.
        if (origin == null && locating?.isActive == true) {
            pending = destination
            return
        }
        (_need.value as? Need.Trip)?.let { waiting ->
            if (origin == null) {
                _need.value = waiting.copy(destination = destination)
                return
            }
        }
        if (origin != null) {
            push(JourneyQuery(origin, destination, TimeMode.LeaveNow))
        } else if (graph.locator.hasPermission) {
            locate(destination)
        } else {
            _need.value = Need.Trip(destination)
        }
    }

    fun locateMe() {
        if (graph.locator.hasPermission) recentreOnFix() else _need.value = Need.Map()
    }

    /** Current Location picked in a search: allowed already, or asked for first. */
    fun pickCurrentLocation(kind: SearchFor) {
        if (graph.locator.hasPermission) pickHere(kind) else _need.value = Need.Pick(kind)
    }

    /**
     * The screen is about to show Android's prompt for [need]: marked first, so an activity
     * recreated while the prompt is up (a rotation) doesn't ask a second time.
     */
    fun asking() {
        _need.value = when (val n = _need.value) {
            is Need.Trip -> n.copy(ask = false)
            is Need.Map -> n.copy(ask = false)
            is Need.Pick -> n.copy(ask = false)
            else -> n
        }
    }

    /**
     * Android's answer to the prompt shown for [need]. An empty answer means the prompt was
     * cancelled rather than refused (Android does this when the screen is recreated under it):
     * then nothing is concluded, and the person can tap again.
     */
    fun permissionResult(granted: Boolean, answered: Boolean = true) {
        val waiting = _need.value ?: return
        _need.value = null
        if (!answered && !granted) return
        if (granted) _showsLocation.value = true
        when (waiting) {
            is Need.Trip -> if (granted) locate(waiting.destination) else _need.value = Need.Stranded(waiting.destination, locationOff = true)
            is Need.Map -> if (granted) recentreOnFix() else _need.value = Need.LocationOff
            is Need.Pick -> if (granted) pickHere(waiting.kind) else _need.value = Need.LocationOff
            is Need.Stranded, Need.LocationOff -> Unit
        }
    }

    /** "Choose a start": the trip's start from a search instead, which the screen opens with this. */
    fun chooseStart(destination: Place): SearchFor {
        _need.value = null
        return SearchFor.Origin(Snapshot.encode(destination))
    }

    private val closes = Channel<SearchFor>(Channel.BUFFERED)
    /** Searches to close because their pick has been made (Current Location after a permission prompt). */
    val closedSearches: Flow<SearchFor> = closes.receiveAsFlow()

    fun dismissNeed() {
        _need.value = null
    }

    fun setHome(place: Place?) {
        graph.places.home = place
    }

    fun setWork(place: Place?) {
        graph.places.work = place
    }

    fun forget(place: Place) = graph.places.forget(place)

    private fun locate(destination: Place) {
        pending = destination
        if (locating?.isActive == true) return
        _finding.value = true
        locating = viewModelScope.launch {
            try {
                val here = graph.locator.current()
                val to = pending ?: destination
                pending = null
                if (here == null) _need.value = Need.Stranded(to, locationOff = !graph.locator.hasPermission)
                else push(JourneyQuery(currentLocation(here), to, TimeMode.LeaveNow))
            } finally {
                _finding.value = false
            }
        }
    }

    private fun recentreOnFix() {
        _showsLocation.value = true
        viewModelScope.launch {
            val here = graph.locator.current() ?: return@launch
            _recentre.value = Recentre(here, System.nanoTime())
        }
    }

    private fun pickHere(kind: SearchFor) {
        _showsLocation.value = true
        // The fix saved with the trip is a placeholder: the results page asks for a fresh one when it searches.
        picked(kind, currentLocation(graph.locator.last() ?: Coordinate(0.0, 0.0)))
        closes.trySend(kind)
    }

    private fun push(query: JourneyQuery) {
        awaitingPage = true
        pushes.trySend(ResultsPage(query))
    }

    private val _nearbyLoading = MutableStateFlow(false)
    /** Asking what's leaving near the person (Home's "Leaving near you" and a line's page). */
    val nearbyLoading: StateFlow<Boolean> = _nearbyLoading.asStateFlow()
    private val _nearbyNoFix = MutableStateFlow(false)
    /** Location is allowed but no fix came, so nothing could be asked: Home says so, with Try again. */
    val nearbyNoFix: StateFlow<Boolean> = _nearbyNoFix.asStateFlow()
    private var nearbyJob: kotlinx.coroutines.Job? = null

    /**
     * What's leaving near the person, once they've allowed location: one request, kept a minute
     * and for 150 m by the store, so Home coming back on top or a rotation asks nothing more, and
     * within that minute no fix is asked for either. Refresh and Try again ([force]) ask again,
     * but never while a request is on its way or within the store's floor. Without permission it
     * does nothing (Home offers the way to allow it).
     */
    fun refreshNearby(force: Boolean = false) {
        if (!graph.locator.hasPermission || nearbyJob?.isActive == true) return
        if (!force && graph.departures.nearbyIsFresh()) return
        if (force && graph.departures.nearbyIsBusy()) {
            // Held by the floor (or already on its way): the spinner stays for what's left of it,
            // so the tap is seen to be taken, and nothing more is sent.
            val held = graph.departures.nearbyHeldFor()
            if (held > 0 && !_nearbyLoading.value) {
                nearbyJob = viewModelScope.launch {
                    _nearbyLoading.value = true
                    try { kotlinx.coroutines.delay(held * 1000) } finally { _nearbyLoading.value = false }
                }
            }
            return
        }
        _showsLocation.value = true
        nearbyJob = viewModelScope.launch {
            _nearbyLoading.value = true
            try {
                val here = graph.locator.current()
                _nearbyNoFix.value = here == null
                if (here != null) graph.departures.loadNearby(here, force)
            } finally {
                _nearbyLoading.value = false
            }
        }
    }

    /** The page on top changed: taps on Home count again once it is back on top. */
    fun topChanged(isHome: Boolean) {
        if (isHome) awaitingPage = false
    }

    companion object {
        fun currentLocation(at: Coordinate) = Place(name = "Current Location", coordinate = at, isCurrentLocation = true)
    }
}
