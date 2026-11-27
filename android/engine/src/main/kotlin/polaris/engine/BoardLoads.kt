package polaris.engine

import java.time.Instant

/**
 * Go's own board loads, on top of [DepartureStore]'s minute and floor: during a trip the next
 * ride's board is asked for on every look, so a source that is down must not be asked every few
 * seconds by every phone on a trip. Nothing is sent without a network; after a failure the next
 * automatic try waits 15 s, then 30, 60 and 120 s at most, until one loads. A board's Refresh on
 * a page is not held by this.
 */
class BoardLoads(
    private val store: DepartureStore,
    private val clock: () -> Instant,
    private val isOnline: () -> Boolean,
) {
    private data class Held(val failures: Int, val until: Instant)
    private val held = java.util.concurrent.ConcurrentHashMap<String, Held>()

    private fun key(leg: Leg) = "${leg.start?.pair}|${leg.line}|${leg.departure.epochSecond}|${leg.toName}"

    suspend fun load(leg: Leg) {
        if (!isOnline()) return
        val key = key(leg)
        val now = clock()
        held[key]?.let { if (now < it.until && (it.until since now) <= MAX_SECONDS) return }
        store.load(leg)
        if (store.boardFailed(leg)) {
            val failures = (held[key]?.failures ?: 0) + 1
            val wait = minOf(MAX_SECONDS, FIRST_SECONDS * (1L shl minOf(failures - 1, 6)).toDouble())
            held[key] = Held(failures, clock().plusSeconds(wait))
        } else if (store.board(leg) is Loadable.Loaded) {
            held.remove(key)
        }
    }

    companion object {
        const val FIRST_SECONDS = 15.0
        const val MAX_SECONDS = 120.0
    }
}
