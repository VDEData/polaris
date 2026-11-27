package polaris.engine

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.util.UUID

// Ported from Polaris/Models/Departure.swift and Polaris/Providers/DepartureProvider.swift.
// Names and rules follow the Swift; the wording the screens show lives in the app's resources.

/** One vehicle leaving a stop, as a departure board shows it. */
data class Departure(
    val id: String,
    val line: String,
    val mode: LegMode,
    val lineColor: RGB?,
    val headsign: String,
    val stopName: String,
    val stopCoordinate: Coordinate,
    val scheduled: Instant,
    /** The live estimate where the feed has one, otherwise the timetable. */
    val expected: Instant,
    val isRealtime: Boolean,
    val isCancelled: Boolean,
    val platform: String? = null,
    /** When it reaches the stop the journey gets off at, if it calls there. */
    val arrivalThere: Instant? = null,
) {
    /** Seconds late (negative when early). */
    val delay: Double get() = expected.seconds - scheduled.seconds
    val isLate: Boolean get() = delay >= 60
}

/** A line's own status message, from the operator's feed ("Minor delays"). */
data class ServiceAlert(val header: String, val detail: String? = null)

/**
 * A stop's departures around when a journey boards there, cut down to the ones that also reach
 * that journey's next stop off.
 */
data class DepartureBoard(
    val stopName: String,
    /** In order of when they leave. */
    val departures: List<Departure>,
    /** The one the journey plans to catch, when the timetable has it. */
    val plannedId: String? = null,
    val alerts: List<ServiceAlert> = emptyList(),
) {
    val planned: Departure? get() = departures.firstOrNull { it.id == plannedId }

    private val running: List<Departure> get() = departures.filter { !it.isCancelled }

    /**
     * What missing the planned one costs: whatever leaves after it and gets there first (not just
     * the next to leave, which may go the long way round).
     */
    val nextAfterPlanned: Departure?
        get() {
            val planned = planned ?: return null
            return soonestThere(running.filter { it.expected > planned.expected })
        }

    /** Something leaving before the planned one that gets there sooner, worth running for if it hasn't gone. */
    fun earlierThanPlanned(after: Instant): Departure? {
        val planned = planned ?: return null
        val best = soonestThere(running.filter { it.expected < planned.expected && it.expected > after }) ?: return null
        val gain = arrivalDifference(best) ?: return null
        return if (gain < 0) best else null
    }

    /** Seconds later you'd reach the stop off on [other] than on the planned one. */
    fun arrivalDifference(other: Departure): Double? {
        val planned = planned ?: return null
        val mine = planned.arrivalThere ?: planned.expected
        return (other.arrivalThere ?: other.expected).seconds - mine.seconds
    }

    /** Minutes later you'd reach the stop off on [other], as the two arrival times are shown (to the minute). */
    fun arrivalMinutes(other: Departure): Int? {
        val planned = planned ?: return null
        return Strip.shownMinutes(planned.arrivalThere ?: planned.expected, other.arrivalThere ?: other.expected)
    }

    private fun soonestThere(departures: List<Departure>): Departure? =
        departures.minWithOrNull(compareBy<Departure>({ it.arrivalThere ?: it.expected }, { it.expected }))
}

/** Departures within a short walk: one row per line and direction, from its nearest stop. */
data class NearbyLine(
    val line: String,
    val mode: LegMode,
    val lineColor: RGB?,
    val headsign: String,
    val stopName: String,
    val stopCoordinate: Coordinate,
    val distance: Double,
    /** The next few, soonest first. */
    val departures: List<Departure>,
) {
    val id: String get() = "$line|$headsign"
}

/**
 * Departure boards from Transitous's stop times: what leaves a stop, live where the feed has it,
 * and where each one calls next. One request per board, made when a board is shown, so it stays
 * within Transitous's light-use policy.
 */
class DepartureProvider(private val http: HTTP) {
    suspend fun board(leg: Leg): DepartureBoard {
        val request = boardRequest(leg) ?: throw ProviderError.NoRoutes
        return parseBoard(http.data(request), leg)
    }

    suspend fun nearby(coordinate: Coordinate, now: Instant = Instant.now()): List<NearbyLine> =
        parseNearby(http.data(nearbyRequest(coordinate, now)), coordinate, now)

    companion object {
        /** A board starts this long before the planned departure, so the ones just missed (or still makeable) show above yours. */
        const val BOARD_LEAD_SECONDS = 20 * 60L
        const val BOARD_SPAN_SECONDS = 50 * 60L
        /** A stop off this close to where the journey gets off counts as the same stop. Tight on purpose: Cannon Street is 250 m from Bank. */
        const val SAME_STOP_METRES = 200.0
        const val NEARBY_RADIUS_METRES = 400

        private const val STOPTIMES = "https://api.transitous.org/api/v6/stoptimes"

        fun boardRequest(leg: Leg): HttpRequest? {
            val start = leg.start
            if (!leg.mode.isTransit || start == null) return null
            val items = mutableListOf(
                "center" to start.pair,
                // Stations sprawl (and sources place them differently); bus stops don't.
                "radius" to if (leg.mode in setOf(LegMode.bus, LegMode.coach, LegMode.tram)) "80" else "200",
                "time" to Time.isoString(leg.departure.minusSeconds(BOARD_LEAD_SECONDS)),
                "window" to BOARD_SPAN_SECONDS.toString(),
                "n" to "1",
                "fetchStops" to "true",
            )
            queryMode(leg.mode)?.let { items += "mode" to it }
            return HttpRequest(HTTP.url(STOPTIMES, items))
        }

        fun nearbyRequest(coordinate: Coordinate, now: Instant): HttpRequest = HttpRequest(
            HTTP.url(
                STOPTIMES,
                listOf(
                    "center" to coordinate.pair,
                    "radius" to NEARBY_RADIUS_METRES.toString(),
                    "time" to Time.isoString(now),
                    "n" to "60",
                    "withAlerts" to "false",
                ),
            ),
        )

        /** Narrows a busy station to the right kind of vehicle. Rail, DLR and the Elizabeth line are left open: feeds disagree on what to call them. */
        private fun queryMode(mode: LegMode): String? = when (mode) {
            LegMode.tube -> "SUBWAY"
            LegMode.bus -> "BUS"
            LegMode.coach -> "COACH,BUS"
            LegMode.tram -> "TRAM"
            LegMode.ferry -> "FERRY"
            else -> null
        }

        fun parseBoard(data: ByteArray, leg: Leg): DepartureBoard {
            val response = HTTP.decode<Response>(data)
            val wantedLine = leg.line?.let(Journey::normalise)
            val seen = mutableSetOf<String>()
            val departures = mutableListOf<Departure>()
            val alerts = mutableListOf<ServiceAlert>()

            for (time in response.stopTimes) {
                if (!seen.add(time.tripId ?: UUID.randomUUID().toString())) continue
                var departure = departure(time) ?: continue
                val sameLine = wantedLine != null && Journey.normalise(departure.line) == wantedLine
                // Anything that calls where the journey gets off is a way there; without the
                // trip's stops to check, fall back to the same line.
                val stops = time.nextStops
                if (!stops.isNullOrEmpty()) {
                    val there = stops.firstOrNull { isAlighting(it, leg) } ?: continue
                    departure = departure.copy(arrivalThere = Time.iso(there.arrival) ?: Time.iso(there.scheduledArrival))
                    if (!(sameLine || departure.mode == leg.mode)) continue
                } else if (!sameLine) {
                    continue
                }
                departures += departure
                val line = leg.line
                if (sameLine && line != null) {
                    for (alert in time.place.alerts ?: emptyList()) {
                        val header = alert.headerText
                        if (header.isNullOrEmpty() || !concerns(alert, line)) continue
                        val item = ServiceAlert(header, alert.descriptionText)
                        if (item !in alerts) alerts += item
                    }
                }
            }
            // Stable, as Swift's sort is for equal times in practice (the feed lists them in order).
            departures.sortBy { it.expected }

            // The planned one: same line, leaving when the journey said it would.
            val candidates = departures.filter { wantedLine == null || Journey.normalise(it.line) == wantedLine }
            val planned = candidates.ifEmpty { departures }
                .map { it to minOf(kotlin.math.abs(it.scheduled.seconds - leg.departure.seconds), kotlin.math.abs(it.expected.seconds - leg.departure.seconds)) }
                .filter { it.second <= 150 }
                .minByOrNull { it.second }?.first

            return DepartureBoard(
                stopName = planned?.stopName ?: departures.firstOrNull()?.stopName ?: leg.fromName,
                departures = departures,
                plannedId = planned?.id,
                // Line news before station notices, which run long.
                alerts = alerts.sortedBy { it.detail?.length ?: 0 },
            )
        }

        fun parseNearby(data: ByteArray, around: Coordinate, now: Instant): List<NearbyLine> {
            val response = HTTP.decode<Response>(data)
            val groups = linkedMapOf<String, NearbyLine>()
            for (time in response.stopTimes) {
                val departure = departure(time) ?: continue
                if (departure.isCancelled || departure.expected <= now.minusSeconds(30)) continue
                val distance = JourneyMap.metres(departure.stopCoordinate, around)
                val key = "${departure.line}|${departure.headsign}"
                val fresh = NearbyLine(
                    departure.line, departure.mode, departure.lineColor, departure.headsign,
                    departure.stopName, departure.stopCoordinate, distance, listOf(departure),
                )
                val group = groups[key]
                groups[key] = when {
                    group == null -> fresh
                    // Each line and direction from its nearest stop only.
                    distance < group.distance - 1 -> fresh
                    departure.stopName == group.stopName -> group.copy(departures = group.departures + departure)
                    else -> group
                }
            }
            return groups.values
                .map { it.copy(departures = it.departures.sortedBy { d -> d.expected }.take(3)) }
                // Nearest first, in 100 m bands so a stop next door doesn't bury a sooner bus.
                .sortedWith(compareBy<NearbyLine>({ (it.distance / 100).toInt() }, { it.departures.firstOrNull()?.expected ?: Instant.MAX }))
        }

        private fun departure(time: Response.StopTime): Departure? {
            val place = time.place
            val scheduled = Time.iso(place.scheduledDeparture) ?: Time.iso(place.departure) ?: return null
            if (time.pickupDropoffType == "NOT_ALLOWED" && time.cancelled != true) return null
            val base = TransitousProvider.mode(time.mode)
            val line = TransitousProvider.lineName(base, time.routeShortName, time.displayName, time.tripShortName, time.agencyName) ?: return null
            val mode = base.refined(line, time.agencyName)
            return Departure(
                id = "${time.tripId ?: line}|${place.scheduledDeparture ?: ""}",
                line = line,
                mode = mode,
                lineColor = LineColors.color(line, mode, time.routeColor),
                headsign = time.headsign ?: "",
                stopName = place.name,
                stopCoordinate = Coordinate(place.lat, place.lon),
                scheduled = scheduled,
                expected = Time.iso(place.departure) ?: scheduled,
                isRealtime = time.realTime ?: false,
                isCancelled = time.cancelled == true || place.cancelled == true,
                platform = place.track?.takeIf { it.isNotEmpty() && it != "-" },
            )
        }

        /** A stop's alerts cover every line through it: keep the ones about this line, and station notices that name no line at all. */
        internal fun concerns(alert: Response.Alert, line: String): Boolean {
            val text = Journey.normalise("${alert.headerText ?: ""} ${alert.descriptionText ?: ""}")
            if (Journey.normalise(line) in text) return true
            return LineColors.namedLines.none { it in text }
        }

        private fun isAlighting(stop: Response.Place, leg: Leg): Boolean {
            if (stop.dropoffType == "NOT_ALLOWED") return false
            val wanted = Journey.normalise(TfLProvider.cleanName(leg.toName))
            if (wanted.isNotEmpty() && Journey.normalise(TfLProvider.cleanName(stop.name)) == wanted) return true
            val end = leg.end ?: return false
            return JourneyMap.metres(Coordinate(stop.lat, stop.lon), end) <= SAME_STOP_METRES
        }
    }

    @Serializable
    internal data class Response(val stopTimes: List<StopTime> = emptyList()) {
        @Serializable
        data class StopTime(
            val place: Place,
            val mode: String,
            val realTime: Boolean? = null,
            val headsign: String? = null,
            val tripId: String? = null,
            val agencyName: String? = null,
            val routeShortName: String? = null,
            val displayName: String? = null,
            val tripShortName: String? = null,
            val routeColor: String? = null,
            val cancelled: Boolean? = null,
            val pickupDropoffType: String? = null,
            val nextStops: List<Place>? = null,
        )

        @Serializable
        data class Place(
            val name: String,
            val lat: Double,
            val lon: Double,
            val arrival: String? = null,
            val departure: String? = null,
            val scheduledArrival: String? = null,
            val scheduledDeparture: String? = null,
            val track: String? = null,
            val cancelled: Boolean? = null,
            val dropoffType: String? = null,
            val alerts: List<Alert>? = null,
        )

        @Serializable
        data class Alert(val headerText: String? = null, val descriptionText: String? = null)
    }
}

/** Something loading, loaded, or that failed with a reason the screen can say. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Loaded<T>(override val value: T) : Loadable<T>
    data class Failed(val reason: Reason) : Loadable<Nothing>

    val value: T? get() = (this as? Loaded<T>)?.value

    /** Why it failed, as a kind; the app puts it into words. */
    enum class Reason { offline, notAnswering, failed }
}

/**
 * Departure boards for the journey on screen, and what's leaving near you, as iOS's
 * `DepartureStore`. Each is kept for a minute, so moving between screens (or a rotation) doesn't
 * ask again: one request per board per minute at most, and nearby only when the person has moved
 * 150 m or a minute has passed. A failed refresh keeps what was there.
 *
 * Android adds two limits iOS doesn't have: a request already on its way is never sent again
 * (a Refresh during the first load waits for it), and nothing that loaded is asked again within
 * [MIN_SECONDS] of the last time it was asked. After a failure the first Try again goes at once,
 * further ones wait [RETRY_SECONDS]; a source that said it was busy (429, 503) gets the full floor.
 */
class DepartureStore(
    private val provider: DepartureProvider,
    private val clock: () -> Instant = Instant::now,
) {
    private val boards = java.util.concurrent.ConcurrentHashMap<String, Loadable<DepartureBoard>>()
    /** When each board was last fetched successfully (kept a minute) and last asked for at all (the floor). */
    private val fetched = java.util.concurrent.ConcurrentHashMap<String, Instant>()
    private val asked = java.util.concurrent.ConcurrentHashMap<String, Instant>()
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    /** Failures in a row, and whether the source said it was busy: they decide the floor before the next try. */
    private data class Failures(val count: Int, val busy: Boolean)
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Failures>()
    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Bumped whenever a board or the nearby list changes, for screens to read again. */
    val version: kotlinx.coroutines.flow.StateFlow<Int> = _version

    @Volatile var nearby: Loadable<List<NearbyLine>>? = null
        private set
    @Volatile private var nearbyFetched: Pair<Instant, Coordinate>? = null
    @Volatile private var nearbyAsked: Instant? = null
    @Volatile private var nearbyInFlight = false
    @Volatile private var nearbyFailures: Failures? = null

    fun board(leg: Leg): Loadable<DepartureBoard>? = boards[key(leg)]

    /** A board's key: the stop, line and planned time, so the same ride on a restored page finds its board. */
    private fun key(leg: Leg) = "${leg.start?.pair}|${leg.line}|${leg.departure.epochSecond}|${leg.toName}"

    /** The wait before the next request: the full floor after a load or a busy answer; after other failures, none for the first retry, then a short one. */
    private fun floor(f: Failures?): Long = when {
        f == null || f.busy -> MIN_SECONDS
        f.count <= 1 -> 0
        else -> RETRY_SECONDS
    }

    /** A clock stepped back (elapsed below zero) counts as past the floor, never as hours of waiting. */
    private fun tooSoon(last: Instant?, now: Instant, floor: Long) = last != null && Duration.between(last, now).seconds.let { it in 0 until floor }

    private fun fresh(at: Instant?, now: Instant) = at != null && Duration.between(at, now).seconds in 0 until FRESH_SECONDS

    private fun failed(previous: Failures?, e: Exception) =
        Failures((previous?.count ?: 0) + 1, e is ProviderError.Http && (e.code == 429 || e.code == 503))

    suspend fun load(leg: Leg, force: Boolean = false) {
        if (!leg.mode.isTransit || leg.start == null) return
        val key = key(leg)
        val now = clock()
        synchronized(fetched) {
            if (key in inFlight) return
            val at = fetched[key]
            if (!force && fresh(at, now)) return
            if (tooSoon(asked[key], now, floor(failures[key]))) return
            asked[key] = now
            inFlight += key
        }
        try {
            if (boards[key]?.value == null) set { boards[key] = Loadable.Loading }
            val board = provider.board(leg)
            fetched[key] = now
            failures.remove(key)
            set { boards[key] = Loadable.Loaded(board) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            asked.remove(key)
            if (boards[key] == Loadable.Loading) boards.remove(key)
            throw e
        } catch (e: Exception) {
            // Keep a board we already have rather than blanking it on a blip.
            failures[key] = failed(failures[key], e)
            set { if (boards[key]?.value == null) boards[key] = Loadable.Failed(describe(e)) }
        } finally {
            inFlight -= key
        }
    }

    /** The last request for [leg]'s board failed (a board already held is kept, so its state alone doesn't say). */
    fun boardFailed(leg: Leg): Boolean = failures.containsKey(key(leg))

    /** Whether nearby was fetched within the minute: checked before asking for a fix at all. */
    fun nearbyIsFresh(): Boolean = fresh(nearbyFetched?.first, clock())

    /** Whether a nearby request is on its way or was asked for too recently (a Refresh would send nothing). */
    fun nearbyIsBusy(): Boolean = nearbyInFlight || nearbyHeldFor() > 0

    /** Seconds until a Refresh of nearby would go (0 when it would go now, or after a failure). */
    fun nearbyHeldFor(): Long = heldFor(nearbyAsked, floor(nearbyFailures))

    /** Seconds until a Refresh of [leg]'s board would go (0 when it would go now, or after a failure). */
    fun boardHeldFor(leg: Leg): Long { val key = key(leg); return heldFor(asked[key], floor(failures[key])) }

    /** Never more than the floor itself, so a clock moved backwards can't hold a spinner for long. */
    private fun heldFor(last: Instant?, floor: Long): Long = last?.let {
        val elapsed = Duration.between(it, clock()).seconds
        if (elapsed < 0) 0L else (floor - elapsed).coerceIn(0L, floor)
    } ?: 0L

    suspend fun loadNearby(coordinate: Coordinate, force: Boolean = false) {
        val now = clock()
        synchronized(this) {
            if (nearbyInFlight) return
            val last = nearbyFetched
            if (!force && last != null && fresh(last.first, now) &&
                JourneyMap.metres(last.second, coordinate) < 150
            ) return
            if (tooSoon(nearbyAsked, now, floor(nearbyFailures))) return
            nearbyAsked = now
            nearbyInFlight = true
        }
        try {
            // A list from somewhere else or from long ago (a trip has just ended across town)
            // isn't shown as if it were here and now while the new one loads.
            val shown = nearbyFetched
            val elsewhere = shown != null && (JourneyMap.metres(shown.second, coordinate) >= ELSEWHERE_METRES || Duration.between(shown.first, now).seconds >= OLD_SECONDS)
            if (nearby?.value == null || elsewhere) set { nearby = Loadable.Loading }
            val lines = provider.nearby(coordinate, now)
            nearbyFetched = now to coordinate
            nearbyFailures = null
            set { nearby = Loadable.Loaded(lines) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            nearbyAsked = null
            if (nearby == Loadable.Loading) nearby = null
            throw e
        } catch (e: Exception) {
            nearbyFailures = failed(nearbyFailures, e)
            set { if (nearby?.value == null) nearby = Loadable.Failed(describe(e)) }
        } finally {
            nearbyInFlight = false
        }
    }

    private inline fun set(change: () -> Unit) {
        change()
        _version.value += 1
    }

    companion object {
        const val FRESH_SECONDS = 60L
        /** The least time between two requests for the same board, or for nearby, once it has loaded (or the source said it was busy). */
        const val MIN_SECONDS = 15L
        /** Between retries after the first, when a request failed for another reason (offline, a timeout, an error). */
        const val RETRY_SECONDS = 5L
        /** How far from where the nearby list was asked, or how long after, it no longer stands in while a new one loads. */
        const val ELSEWHERE_METRES = 500.0
        const val OLD_SECONDS = 600L

        fun describe(error: Throwable): Loadable.Reason = when (error) {
            is java.net.UnknownHostException, is java.net.ConnectException -> Loadable.Reason.offline
            is ProviderError.Http -> Loadable.Reason.notAnswering
            else -> Loadable.Reason.failed
        }
    }
}

/**
 * The departure strip under a ride (iOS's `DepartureStrip`): which departures to show, what each
 * says, and the line under them. Plain, so it is tested on the JVM; the app words it.
 */
object Strip {
    /** What one tile says under its time. */
    sealed interface Caption {
        data object Cancelled : Caption
        data object Gone : Caption
        data object Yours : Caption
        /** Gets you there within half a minute of yours. */
        data object Same : Caption
        /** Minutes later (positive) or sooner (negative) than yours. */
        data class Minutes(val minutes: Int) : Caption
    }

    /** The line under the tiles: what missing yours costs, or what running gains. */
    sealed interface Verdict {
        data class Cancelled(val scheduled: Instant, val next: Instant?) : Verdict
        /** [late] minutes late when over a minute; [earlier] and [gain] for a sooner one worth running for; [next] and [cost] for missing it. */
        data class Plain(val late: Int?, val earlier: Instant?, val gain: Int?, val next: Instant?, val cost: Int?) : Verdict {
            val warns: Boolean get() = late != null
        }
    }

    /** The departures around yours: [before] earlier and [after] later, as the room allows. */
    fun window(board: DepartureBoard, legDeparture: Instant, before: Int, after: Int): List<Departure> {
        if (board.departures.isEmpty()) return emptyList()
        val anchor = board.planned?.let { p -> board.departures.indexOfFirst { it.id == p.id } }?.takeIf { it >= 0 }
            ?: board.departures.indexOfFirst { it.expected >= legDeparture.minusSeconds(60) }.takeIf { it >= 0 }
            ?: 0
        return board.departures.subList(maxOf(0, anchor - before), minOf(board.departures.size, anchor + after + 1))
    }

    fun caption(departure: Departure, board: DepartureBoard, legDeparture: Instant, now: Instant): Caption {
        if (departure.isCancelled) return Caption.Cancelled
        if (departure.id == board.plannedId) return if (departure.expected < now) Caption.Gone else Caption.Yours
        if (departure.expected < now) return Caption.Gone
        val minutes = board.arrivalMinutes(departure) ?: Strip.shownMinutes(legDeparture, departure.expected)
        return if (minutes == 0) Caption.Same else Caption.Minutes(minutes)
    }

    fun verdict(board: DepartureBoard, now: Instant, reachable: Instant? = null): Verdict? {
        val planned = board.planned ?: return null
        if (planned.isCancelled) return Verdict.Cancelled(planned.scheduled, board.nextAfterPlanned?.expected)
        val late = if (planned.isLate) (planned.delay / 60).toInt() else null
        val after = if (reachable != null && reachable > now) reachable else now
        var earlier: Instant? = null
        var gain: Int? = null
        board.earlierThanPlanned(after)?.let { e ->
            val g = board.arrivalMinutes(e)
            if (g != null && g < 0) {
                earlier = e.expected
                gain = -g
            }
        }
        var next: Instant? = null
        var cost: Int? = null
        board.nextAfterPlanned?.let { n ->
            board.arrivalMinutes(n)?.let { c ->
                next = n.expected
                cost = c
            }
        }
        if (late == null && earlier == null && next == null) return null
        return Verdict.Plain(late, earlier, gain, next, cost)
    }

    /** iOS's `signedMinutesText` count: rounded to the nearest minute. */
    fun signedMinutes(seconds: Double): Int = roundAway(seconds / 60)

    /** Swift's `rounded()`: halves away from zero (Kotlin's `Math.round` takes -2.5 to -2). */
    fun roundAway(value: Double): Int = (kotlin.math.sign(value) * kotlin.math.floor(kotlin.math.abs(value) + 0.5)).toInt()

    /**
     * Minutes from [earlier] to [later] as their clock times show them (each to the minute, as the
     * app's clock cuts the seconds), so "N min sooner" always matches the two times beside it.
     */
    fun shownMinutes(earlier: java.time.Instant, later: java.time.Instant): Int =
        (Math.floorDiv(later.epochSecond, 60L) - Math.floorDiv(earlier.epochSecond, 60L)).toInt()
}
