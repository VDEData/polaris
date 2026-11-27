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
    /** Sets (or with null takes back) an alarm at the time the trip would end by itself. */
    private val cutoffAlarm: (Instant?) -> Unit = {},
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

    /** Go's automatic board loads: nothing offline, and a growing wait after failures. */
    private val boards = polaris.engine.BoardLoads(departures, clock, isOnline)

    private fun make(journey: Journey, query: JourneyQuery, saved: SavedTrip?) = LiveTrip(
        journey = journey, query = query, scope = scope, clock = clock, fixSource = { tracker.latest },
        timetable = ::timetable, loadBoard = { boards.load(it) }, alerts = alerts, search = search,
        isOnline = isOnline, presenter = presenter, store = store, saved = saved,
    )

    /** Go, tapped on a journey page: this trip replaces any other. */
    fun start(journey: Journey, query: JourneyQuery): LiveTrip {
        _trip.value?.let { if (it.journey.id == journey.id && it.isRunning) return it }
        end()
        return run(make(journey, query, null))
    }

    /** A trip found at launch and not yet taken. */
    val hasPending: Boolean get() = pending != null

    /** End tapped in the shade before the screen took the trip found at launch: it isn't brought back. */
    fun dropPending() {
        if (pending != null) {
            pending = null
            store.clear()
        }
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
        // From the arrival it was saved with, as isResumable judged it.
        val trip = run(make(saved.journey, saved.query, saved), saved.arrival)
        _opens.value = true
        return trip
    }

    /** When the last leg was first reached (seen at the destination), for ending the trip by itself. */
    private var arrivedAt: Instant? = null

    /** The arrival as last known from something that happened (a fresh fix, a change of leg), and the leg then. */
    private var known: Instant? = null
    private var knownLeg = -1
    private var armed: Instant? = null

    /** When the trip ends by itself if nothing more is heard, the same rule a saved trip is resumed by. */
    val cutoff: Instant? get() = known?.let(SavedTrip::endsAt)

    /**
     * A trip ends by itself:
     * - [ARRIVED_SECONDS] after you're seen at the destination and still there (long enough to glance
     *   at Go saying so, short enough that GPS, the service and the notification don't run on in a pocket);
     * - or at [cutoff], half an hour past the arrival as last known. Only a fresh fix or a change of
     *   leg updates that arrival: with nothing heard (no fix underground, the phone left behind) the
     *   timeline keeps moving the estimate on, and that alone mustn't keep the trip alive. A long
     *   delay that is seen keeps it going.
     * An alarm at the cut-off ends it with the phone asleep and no fixes coming.
     */
    private fun endIfOver(trip: LiveTrip) {
        val now = clock()
        if (!trip.arrived) arrivedAt = null else if (arrivedAt == null) arrivedAt = now
        val there = arrivedAt?.let { java.time.Duration.between(it, now).seconds >= ARRIVED_SECONDS } ?: false
        val fresh = tracker.latest?.let { java.time.Duration.between(it.at, now).seconds < FRESH_SECONDS } == true
        if (known == null || fresh || trip.legIndex != knownLeg) {
            known = trip.eta
            knownLeg = trip.legIndex
        }
        val at = cutoff
        if (at != null && (armed == null || kotlin.math.abs(java.time.Duration.between(armed, at).seconds) >= 60)) {
            armed = at
            cutoffAlarm(at)
        }
        if (there || (at != null && now > at)) end()
    }

    /** The cut-off alarm fired: the trip ends if it is over, otherwise the alarm moves on. */
    fun check() {
        val trip = _trip.value ?: return
        if (!trip.isRunning) return
        trip.refresh()
        armed = null
        endIfOver(trip)
    }

    /** A new fix: Go looks at once (the two-second ticks don't run while the phone sleeps; fixes wake it). */
    fun onFix() {
        val trip = _trip.value ?: return
        if (!trip.isRunning) return
        trip.refresh()
        endIfOver(trip)
    }

    private fun run(trip: LiveTrip, arrival: Instant? = null): LiveTrip {
        arrivedAt = null
        known = arrival
        knownLeg = if (arrival != null) trip.legIndex else -1
        armed = null
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
                endIfOver(trip)
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
        known = null
        armed = null
        cutoffAlarm(null)
        _trip.value = null
        _opens.value = false
        _version.value += 1
    }

    companion object {
        /** After being seen at the destination, the trip carries on this long, then ends. */
        const val ARRIVED_SECONDS = 120L
        /** A fix younger than this counts as hearing from the trip. */
        const val FRESH_SECONDS = 120L
    }

    /** The trip, if it is following [journeyId]'s journey (a Switch keeps the journey's id). */
    fun following(journeyId: String): LiveTrip? = _trip.value?.takeIf { it.journey.id == journeyId }
}
