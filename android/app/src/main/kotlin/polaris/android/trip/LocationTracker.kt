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
    /** The latest fix, however old; null before the first. */
    val latest: LiveTrip.Fix?
    /** Starts listening, if location is allowed (else does nothing; call again once it is). */
    fun start()
    fun stop()
}

/**
 * Android's own location provider (no Google Play services): GPS and the network provider every
 * two seconds while a trip is live. A fix is stamped on the trip's own clock from its age (Android's
 * elapsed time), not from `Location.time`, which can disagree with the phone's clock.
 */
class AndroidLocationTracker(private val context: Context, private val clock: () -> Instant = Instant::now) : LocationTracking {
    @Volatile override var latest: LiveTrip.Fix? = null
        private set
    private var listening = false
    private val manager get() = context.getSystemService(LocationManager::class.java)

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = take(location)
        @Deprecated("Android 9 and 10 call these")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun take(location: Location) {
        val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos).coerceAtLeast(0L)
        val at = clock().minusNanos(age)
        // A network fix doesn't replace a better, fresh GPS one.
        val current = latest
        if (current != null && location.provider == LocationManager.NETWORK_PROVIDER && location.accuracy > 60 &&
            java.time.Duration.between(current.at, at).seconds < 10
        ) return
        latest = LiveTrip.Fix(Coordinate(location.latitude, location.longitude), at, if (location.hasSpeed()) location.speed.toDouble() else null)
    }

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
            runCatching { m.getLastKnownLocation(provider) }.getOrNull()?.let(::take)
        }
        listening = any
    }

    override fun stop() {
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
