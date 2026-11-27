package polaris.android.trip

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import polaris.engine.Coordinate
import polaris.engine.LiveTrip
import java.time.Instant

/** Fixes while a trip is live, as iOS's `LocationService.startTracking`. */
interface LocationTracking {
    /** The latest usable fix; null before the first, and after [stop]. */
    val latest: LiveTrip.Fix?
    /** Starts listening, if location is allowed (else does nothing; call again once it is). */
    fun start()
    fun stop()
}

/**
 * Which fixes Go believes, as iOS keeps only fixes better than 100 m: a coarse one (approximate
 * location, a cell fix underground) can sit a kilometre out and would end walks, call a ride
 * missed or see a boarding that never happened. A fix of 100 m or worse, or one more than two
 * minutes old when it arrives (a last-known fix from earlier), is dropped. A network fix doesn't
 * replace a fresh, better GPS one. Plain, so it is tested on the JVM.
 */
class FixKeeper(private val clock: () -> Instant) {
    @Volatile var latest: LiveTrip.Fix? = null
        private set

    /** [ageNanos]: how long before now the fix was taken (from Android's elapsed time, not its wall clock). */
    fun offer(latitude: Double, longitude: Double, accuracy: Double?, ageNanos: Long, speed: Double?, network: Boolean): Boolean {
        if (accuracy == null || accuracy >= MAX_ACCURACY_METRES) return false
        if (ageNanos > MAX_AGE_NANOS) return false
        val at = clock().minusNanos(ageNanos.coerceAtLeast(0L))
        val current = latest
        if (current != null && network && (current.accuracy ?: 0.0) < accuracy && java.time.Duration.between(current.at, at).seconds < 10) return false
        latest = LiveTrip.Fix(Coordinate(latitude, longitude), at, speed, accuracy)
        return true
    }

    fun clear() {
        latest = null
    }

    companion object {
        const val MAX_ACCURACY_METRES = 100.0
        const val MAX_AGE_NANOS = 120_000_000_000L
    }
}

/**
 * Android's own location provider (no Google Play services): GPS and the network provider every
 * two seconds while a trip is live, through [FixKeeper]. A fix is stamped on the trip's own clock
 * from its age (Android's elapsed time), not from `Location.time`, which can disagree with the
 * phone's clock. [onFix] runs on the main thread after each fix kept, so Go looks at once.
 */
class AndroidLocationTracker(
    private val context: Context,
    clock: () -> Instant = Instant::now,
    private val onFix: () -> Unit = {},
) : LocationTracking {
    private val keeper = FixKeeper(clock)
    override val latest: LiveTrip.Fix? get() = keeper.latest
    private var listening = false
    private val manager get() = context.getSystemService(LocationManager::class.java)

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (take(location)) onFix()
        }
        @Deprecated("Android 9 and 10 call these")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun take(location: Location): Boolean = keeper.offer(
        location.latitude, location.longitude,
        if (location.hasAccuracy()) location.accuracy.toDouble() else null,
        SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos,
        if (location.hasSpeed()) location.speed.toDouble() else null,
        location.provider == LocationManager.NETWORK_PROVIDER,
    )

    @SuppressLint("MissingPermission")
    override fun start() {
        if (listening) return
        val m = manager ?: return
        val allowed = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (!allowed) return
        var any = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val enabled = runCatching { m.isProviderEnabled(provider) }.getOrDefault(false)
            if (!enabled) continue
            runCatching {
                m.requestLocationUpdates(provider, 2_000L, 0f, listener, Looper.getMainLooper())
                any = true
            }
            // A last-known fix only if recent and good enough (the keeper's rules).
            runCatching { m.getLastKnownLocation(provider) }.getOrNull()?.let(::take)
        }
        listening = any
    }

    override fun stop() {
        keeper.clear()
        if (!listening) return
        listening = false
        runCatching { manager?.removeUpdates(listener) }
    }
}

/** No fixes (tests, and before a graph has a real tracker). */
object NoTracking : LocationTracking {
    override val latest: LiveTrip.Fix? get() = null
    override fun start() {}
    override fun stop() {}
}
