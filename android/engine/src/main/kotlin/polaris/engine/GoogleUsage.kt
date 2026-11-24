package polaris.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

// Ported from `GoogleAllowance` in Polaris/Engine/Replan.swift and Polaris/Services/GoogleUsage.swift.

/**
 * The month's Google Routes requests. Google is used only inside its free monthly allowance
 * (planned to the lowest cap, 1,000 requests), counted in Google's own month (Pacific time).
 */
@Serializable
data class GoogleAllowance(val month: String = "", val used: Int = 0) {
    fun usedThisMonth(now: Instant): Int = if (month == month(now)) used else 0

    /**
     * How many Google requests a search may send now: every deep-search variant while under
     * 700 this month, one while under 900, none past that.
     */
    fun searchRequests(deep: Boolean, variants: Int, now: Instant): Int {
        val used = usedThisMonth(now)
        if (!canSpend(1, now)) return 0
        if (!deep || used >= deepUntil) return 1
        return variants
    }

    fun canSpend(requests: Int, now: Instant): Boolean = usedThisMonth(now) + requests <= monthlyCap - reserve

    fun spending(requests: Int, now: Instant): GoogleAllowance {
        val current = month(now)
        val base = if (month != current) GoogleAllowance(current, 0) else this
        return base.copy(used = base.used + requests)
    }

    companion object {
        /** The lowest free monthly cap among the Routes SKUs Polaris uses. */
        const val monthlyCap = 1_000
        /** Kept unused, for counts that drift from Google's. */
        const val reserve = 100
        /** Below this many used, a deep search may ask Google all its ways. */
        const val deepUntil = 700
        val googleTime: ZoneId = ZoneId.of("America/Los_Angeles")

        fun month(date: Instant): String {
            val d = date.atZone(googleTime)
            return String.format(Locale.ROOT, "%04d-%02d", d.year, d.monthValue)
        }
    }
}

/** Small values kept on the phone (SharedPreferences on Android, a map in tests). */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = synchronized(values) { values[key] }
    override fun putString(key: String, value: String?) {
        synchronized(values) { if (value == null) values.remove(key) else values[key] = value }
    }
}

/**
 * The month's Google Routes requests, kept on this phone. Every search spends
 * from it before it sends, and stops asking Google as the free allowance runs out.
 */
class GoogleUsage(private val store: KeyValueStore) {
    var allowance: GoogleAllowance = store.getString(KEY)?.let { runCatching { HTTP.json.decodeFromString<GoogleAllowance>(it) }.getOrNull() } ?: GoogleAllowance()
        private set

    /**
     * Plans and spends in one step, so two searches started together can't both read the same
     * count and spend past a threshold: how many requests this search may send to Google, already
     * counted. On iOS the main actor makes the plan and the spend one step.
     */
    @Synchronized
    fun reserve(deep: Boolean, variants: Int, now: Instant = Instant.now()): Int {
        val requests = allowance.searchRequests(deep, variants, now)
        spend(requests, now)
        return requests
    }

    @Synchronized
    fun spend(requests: Int, now: Instant = Instant.now()) {
        if (requests <= 0) return
        allowance = allowance.spending(requests, now)
        store.putString(KEY, HTTP.json.encodeToString(allowance))
    }

    companion object {
        const val KEY = "googleAllowance"
    }
}
