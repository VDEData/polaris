package polaris.android.trip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import polaris.engine.DepartureBoard
import polaris.engine.DepartureStore
import polaris.engine.Journey
import polaris.engine.JourneyQuery
import polaris.engine.Leg
import polaris.engine.LiveTrip
import polaris.engine.Replan
import polaris.engine.ReplanAnswer
import polaris.engine.SavedTrip
import polaris.engine.TripAlertScheduling
import polaris.engine.TripPresenter
import polaris.engine.TripStore
import java.time.Instant

/**
 * The live trip for the whole app (iOS's `router.trip`): one at a time, kept by the process rather
 * than by a page, so a rotation, a fold, leaving the Go page or the app going to the background
 * never stops it. The saved file ([TripStore]) is what brings it back after Android ended the
 * process; End deletes it.
 *
 * Everything here runs on [scope], which is the main thread in the app (the trip is confined to
 * one thread). Go looks every two seconds, as iOS does; the departure store's own minute and floor
 * keep the boards' requests within iOS's pace.
 */
class TripSession(
    private val scope: CoroutineScope,
    private val departures: DepartureStore,
    private val tracker: LocationTracking,
    private val store: TripStore,
    private val clock: () -> Instant,
    private val search: (suspend (JourneyQuery, Replan.Watch) -> ReplanAnswer)?,
    private val isOnline: () -> Boolean,
    private val alerts: TripAlertScheduling = TripAlertScheduling.None,
    private val presenter: TripPresenter = TripPresenter.None,
    /** A trip found at launch still worth resuming, until [resumePending] takes it. */
    private var pending: SavedTrip? = null,
    private val tickMillis: Long = 2_000,
) {
    private val _trip = MutableStateFlow<LiveTrip?>(null)
    val trip: StateFlow<LiveTrip?> = _trip.asStateFlow()
    private var ticking: Job? = null
    private var watching: Job? = null
    private val _version = MutableStateFlow(0)
    /** Bumped when the trip on screen may have changed (its own version, or a new or ended trip). */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** A resumed trip the window should open Go for, once. */
    private val _opens = MutableStateFlow(false)
    val opens: StateFlow<Boolean> = _opens.asStateFlow()
    fun opened() { _opens.value = false }

    private fun timetable(leg: Leg): DepartureBoard? = departures.board(leg)?.value

    private fun make(journey: Journey, query: JourneyQuery, saved: SavedTrip?) = LiveTrip(
        journey = journey, query = query, scope = scope, clock = clock, fixSource = { tracker.latest },
        timetable = ::timetable, loadBoard = { departures.load(it) }, alerts = alerts, search = search,
        isOnline = isOnline, presenter = presenter, store = store, saved = saved,
    )

    /** Go, tapped on a journey page: this trip replaces any other. */
    fun start(journey: Journey, query: JourneyQuery): LiveTrip {
        _trip.value?.let { if (it.journey.id == journey.id && it.isRunning) return it }
        end()
        return run(make(journey, query, null))
    }

    /** At launch, after Android ended the process during a trip: back in Go, caught up to now. */
    fun resumePending(): LiveTrip? {
        val saved = pending ?: return null
        pending = null
        if (_trip.value != null) return null
        // Its age checked again: the screen may come up long after the launch decided.
        if (!saved.isResumable(clock())) {
            store.clear()
            return null
        }
        val trip = run(make(saved.journey, saved.query, saved))
        _opens.value = true
        return trip
    }

    private fun run(trip: LiveTrip): LiveTrip {
        _trip.value = trip
        tracker.start()
        trip.start()
        watching = scope.launch { trip.version.collect { _version.value += 1 } }
        ticking = scope.launch {
            while (isActive) {
                delay(tickMillis)
                // Location allowed since Go started (asked on the Go tap): start listening now.
                tracker.start()
                trip.refresh()
            }
        }
        return trip
    }

    /** End: the trip stops, its file and notifications go. */
    fun end() {
        val trip = _trip.value ?: return
        ticking?.cancel()
        watching?.cancel()
        ticking = null
        watching = null
        trip.stop()
        tracker.stop()
        _trip.value = null
        _opens.value = false
        _version.value += 1
    }

    /** The trip, if it is following [journeyId]'s journey (a Switch keeps the journey's id). */
    fun following(journeyId: String): LiveTrip? = _trip.value?.takeIf { it.journey.id == journeyId }
}
