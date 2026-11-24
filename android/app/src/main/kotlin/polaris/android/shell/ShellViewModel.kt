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
 * never at launch. Nothing here sends a request: trips are searched by the results page.
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
        graph.places.remember(destination)
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

    /** Android's answer to the permission request the screen made for [need]. */
    fun permissionResult(granted: Boolean) {
        val waiting = _need.value ?: return
        _need.value = null
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
        viewModelScope.launch {
            val here = graph.locator.current()
            if (here == null) _need.value = Need.Stranded(destination, locationOff = !graph.locator.hasPermission)
            else push(JourneyQuery(currentLocation(here), destination, TimeMode.LeaveNow))
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
        pushes.trySend(ResultsPage(query))
    }

    companion object {
        fun currentLocation(at: Coordinate) = Place(name = "Current Location", coordinate = at, isCurrentLocation = true)
    }
}
