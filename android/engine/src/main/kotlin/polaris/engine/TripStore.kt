package polaris.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// Ported from Polaris/Services/TripStore.swift: what Go needs to pick a trip up again after the app
// was stopped (swiped away, or ended by Android in the background). The boards, the fix and the
// walk come back by themselves. No keys or settings go in here.

data class SavedTrip(
    val version: Int = CURRENT_VERSION,
    val journey: Journey,
    val query: JourneyQuery,
    val legIndex: Int,
    val log: TripLog,
    val replanWatch: Replan.Watch,
    /** Long waits a search found nothing better for. */
    val checkedWaits: Set<String>,
    /** When you'd get there, as the trip was going when this was saved. */
    val arrival: Instant,
    /** The re-plan reason of a faster way on show when this was saved (the offer itself isn't kept). */
    val offerKey: String? = null,
) {
    /** Still worth putting you back in Go: no more than half an hour past the arrival it had. */
    fun isResumable(now: Instant): Boolean =
        version == CURRENT_VERSION && journey.legs.isNotEmpty() && legIndex in journey.legs.indices && now <= endsAt(arrival)

    /** The same trip as far as saving goes: the arrival counts only once it moves by five minutes or more. */
    fun sameAs(other: SavedTrip): Boolean =
        journey == other.journey && query == other.query && legIndex == other.legIndex && log == other.log &&
            replanWatch == other.replanWatch && checkedWaits == other.checkedWaits && offerKey == other.offerKey &&
            abs(arrival since other.arrival) < 5 * 60

    companion object {
        /** Bumped when the shape changes; a file of another version is dropped. */
        const val CURRENT_VERSION = 1
        /** Past the arrival by more than this, the trip is over: closed and never ended. */
        const val STALE_AFTER = 30 * 60.0

        /** When a trip arriving at [arrival] ends by itself if nothing more is heard: the running trip and a resumed one use this. */
        fun endsAt(arrival: Instant): Instant = arrival.plusSeconds(STALE_AFTER)

        fun encode(trip: SavedTrip): String = HTTP.json.encodeToString(TripDTO.of(trip))

        /** Null for a file this version can't read (corrupt, or another shape). */
        fun decode(text: String): SavedTrip? = runCatching { HTTP.json.decodeFromString<TripDTO>(text).model() }.getOrNull()
    }

    @Serializable
    internal data class DepartureDTO(
        val id: String,
        val line: String,
        val mode: String,
        val color: String? = null,
        val headsign: String,
        val stop: String,
        val lat: Double,
        val lon: Double,
        val scheduled: Long,
        val expected: Long,
        val realtime: Boolean,
        val cancelled: Boolean,
        val platform: String? = null,
        val there: Long? = null,
    ) {
        fun model() = Departure(
            id, line, LegMode.entries.firstOrNull { it.name == mode } ?: LegMode.other, RGB.hex(color), headsign, stop, Coordinate(lat, lon),
            Instant.ofEpochMilli(scheduled), Instant.ofEpochMilli(expected), realtime, cancelled, platform, there?.let(Instant::ofEpochMilli),
        )

        companion object {
            fun of(d: Departure) = DepartureDTO(
                d.id, d.line, d.mode.name, d.lineColor?.let(JourneyMap::hex), d.headsign, d.stopName, d.stopCoordinate.latitude, d.stopCoordinate.longitude,
                d.scheduled.toEpochMilli(), d.expected.toEpochMilli(), d.isRealtime, d.isCancelled, d.platform, d.arrivalThere?.toEpochMilli(),
            )
        }
    }

    @Serializable
    internal data class BoardingDTO(val leg: Int, val at: Long, val departure: DepartureDTO? = null)

    @Serializable
    internal data class EndedDTO(val leg: Int, val at: Long)

    @Serializable
    internal data class WatchDTO(
        val searches: Int = 0,
        val failures: Int = 0,
        val google: Int = 0,
        val settled: List<String> = emptyList(),
        val lastAsked: Long? = null,
    )

    @Serializable
    internal data class TripDTO(
        val version: Int,
        val journey: Snapshot.JourneyDTO,
        val query: Snapshot.QueryDTO,
        val legIndex: Int,
        val began: Long,
        val boardings: List<BoardingDTO> = emptyList(),
        val ended: List<EndedDTO> = emptyList(),
        val watch: WatchDTO = WatchDTO(),
        val checkedWaits: List<String> = emptyList(),
        val arrival: Long,
        val offerKey: String? = null,
    ) {
        fun model(): SavedTrip? {
            if (version != CURRENT_VERSION) return null
            return SavedTrip(
                version = version,
                journey = journey.model(),
                query = query.model(),
                legIndex = legIndex,
                log = TripLog(
                    began = Instant.ofEpochMilli(began),
                    boardings = boardings.associate { it.leg to TripLog.Boarding(Instant.ofEpochMilli(it.at), it.departure?.model()) },
                    ended = ended.associate { it.leg to Instant.ofEpochMilli(it.at) },
                ),
                replanWatch = Replan.Watch(watch.searches, watch.failures, watch.google, watch.settled.toSet(), watch.lastAsked?.let(Instant::ofEpochMilli)),
                checkedWaits = checkedWaits.toSet(),
                arrival = Instant.ofEpochMilli(arrival),
                offerKey = offerKey,
            )
        }

        companion object {
            fun of(t: SavedTrip) = TripDTO(
                version = t.version,
                journey = Snapshot.JourneyDTO.of(t.journey),
                query = Snapshot.QueryDTO.of(t.query),
                legIndex = t.legIndex,
                began = t.log.began.toEpochMilli(),
                boardings = t.log.boardings.entries.sortedBy { it.key }.map { (leg, b) -> BoardingDTO(leg, b.at.toEpochMilli(), b.departure?.let(DepartureDTO::of)) },
                ended = t.log.ended.entries.sortedBy { it.key }.map { (leg, at) -> EndedDTO(leg, at.toEpochMilli()) },
                watch = t.replanWatch.let { WatchDTO(it.searches, it.failures, it.googleRequests, it.settled.sorted(), it.lastAsked?.toEpochMilli()) },
                checkedWaits = t.checkedWaits.sorted(),
                arrival = t.arrival.toEpochMilli(),
                offerKey = t.offerKey,
            )
        }
    }
}

/** Where the live trip is kept between launches. */
interface TripStore {
    fun save(trip: SavedTrip)
    /** The saved trip, or null when there's none or it can't be read. */
    fun load(): SavedTrip?
    fun clear()

    class Memory : TripStore {
        @Volatile var trip: SavedTrip? = null
        var saves = 0
        override fun save(trip: SavedTrip) { this.trip = trip; saves += 1 }
        override fun load() = trip
        override fun clear() { trip = null }
    }
}

/**
 * One small JSON file. Saves and End's delete go one after another on one thread, in the order
 * they were asked for, so a save queued just before End can never bring the trip back after it.
 */
class FileTripStore(private val file: File, private val writer: ExecutorService = Executors.newSingleThreadExecutor()) : TripStore {
    override fun save(trip: SavedTrip) {
        val text = SavedTrip.encode(trip)
        writer.execute {
            runCatching {
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(text)
                // Losing the save loses only the resume, never the trip in front of you.
                if (!temp.renameTo(file)) temp.delete()
            }
        }
    }

    override fun load(): SavedTrip? {
        flush()
        return runCatching { file.readText() }.getOrNull()?.let(SavedTrip::decode)
    }

    /** End is final: after any saves queued before it, the file is deleted before this returns. */
    override fun clear() {
        writer.execute { file.delete() }
        flush()
    }

    /** Waits for every save and delete asked for so far. */
    fun flush() {
        runCatching { writer.submit {}.get(5, TimeUnit.SECONDS) }
    }
}

/** What happens to a trip the app was stopped during, decided once as it launches. */
object TripResume {
    /**
     * The trip to put the person back in, if it's still worth resuming. Anything else (none saved,
     * past its time, unreadable) is cleared, with [dropped] called to take back what it left (its
     * notifications).
     */
    fun decide(store: TripStore, now: Instant, dropped: () -> Unit = {}): SavedTrip? {
        val saved = store.load()
        if (saved != null && saved.isResumable(now)) return saved
        store.clear()
        dropped()
        return null
    }
}
