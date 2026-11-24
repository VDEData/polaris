package polaris.android.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import polaris.engine.Coordinate
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Where the person is, when they have allowed it. The screens ask for permission only on an action that needs it. */
interface Locator {
    val hasPermission: Boolean
    /** A fresh fix, or null (no permission, location off, no fix in time). */
    suspend fun current(): Coordinate?
    /** The last fix any app had, without waiting; null without permission. */
    fun last(): Coordinate?
}

/**
 * Android's own location provider (no Google Play services). As iOS's `LocationService`: a fix
 * under 20 seconds old is used as it is; otherwise one fresh fix from the best enabled provider,
 * waited for up to 10 seconds, and never an older fix in its place.
 */
class AndroidLocator(private val context: Context) : Locator {
    private val manager get() = context.getSystemService(LocationManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    override val hasPermission: Boolean
        get() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    private fun providers(): List<String> {
        val m = manager ?: return emptyList()
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) }
    }

    @SuppressLint("MissingPermission")
    override fun last(): Coordinate? {
        if (!hasPermission) return null
        val m = manager ?: return null
        return providers().mapNotNull { runCatching { m.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }?.let { Coordinate(it.latitude, it.longitude) }
    }

    /** The newest last-known fix if it is under [FRESH_FOR_NANOS] old. */
    @SuppressLint("MissingPermission")
    private fun recent(): Location? {
        val m = manager ?: return null
        val now = SystemClock.elapsedRealtimeNanos()
        return providers().mapNotNull { runCatching { m.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.elapsedRealtimeNanos in 0 until FRESH_FOR_NANOS }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }

    @SuppressLint("MissingPermission")
    override suspend fun current(): Coordinate? {
        if (!hasPermission) return null
        val m = manager ?: return null
        recent()?.let { return Coordinate(it.latitude, it.longitude) }
        val provider = providers().firstOrNull { it != LocationManager.PASSIVE_PROVIDER } ?: return null
        val fresh = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<Location?> { done ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    done.invokeOnCancellation { signal.cancel() }
                    m.getCurrentLocation(provider, signal, executor) { done.resume(it) }
                } else {
                    // Android 9 and 10 have no single-fix call that takes a cancellation signal: the
                    // listener is removed by hand when the wait is cancelled or times out.
                    val listener = android.location.LocationListener { done.resume(it) }
                    done.invokeOnCancellation { m.removeUpdates(listener) }
                    @Suppress("DEPRECATION")
                    m.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
                }
            }
        }
        return fresh?.let { Coordinate(it.latitude, it.longitude) }
    }

    companion object {
        /** iOS reuses a fix under 20 seconds old (LocationService.swift). */
        const val FRESH_FOR_NANOS = 20_000_000_000L
    }
}
