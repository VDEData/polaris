package polaris.android.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
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
 * Android's own location provider (no Google Play services): a single fresh fix from the best
 * enabled provider, falling back to the last known one, within 10 seconds.
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

    @SuppressLint("MissingPermission")
    override suspend fun current(): Coordinate? {
        if (!hasPermission) return null
        val m = manager ?: return null
        val provider = providers().firstOrNull { it != LocationManager.PASSIVE_PROVIDER } ?: return last()
        val fresh = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<Location?> { done ->
                val signal = CancellationSignal()
                done.invokeOnCancellation { signal.cancel() }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    m.getCurrentLocation(provider, signal, executor) { done.resume(it) }
                } else {
                    // Android 9 and 10 have no single-fix call that takes a cancellation signal.
                    @Suppress("DEPRECATION")
                    m.requestSingleUpdate(provider, { done.resume(it) }, android.os.Looper.getMainLooper())
                }
            }
        }
        return fresh?.let { Coordinate(it.latitude, it.longitude) } ?: last()
    }
}
