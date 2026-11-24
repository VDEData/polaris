package polaris.android.map

import androidx.lifecycle.Lifecycle

/**
 * Drives a `MapView` through its lifecycle exactly: create once (only when the composition is
 * applied, so an abandoned composition never leaves a created view behind), then start, resume,
 * pause and stop as often as the owner goes round, each once per round and in order, and destroy
 * once. The view's state (its camera) is saved at every stop and before destroy, so a rotation or
 * fold brings it back; low-memory warnings reach it only while it is alive.
 */
class MapViewLifecycle(private val view: Target) {
    /** The parts of `MapView` this calls, so it can be tested without a device. */
    interface Target {
        fun onCreate()
        fun onStart()
        fun onResume()
        fun onPause()
        fun onStop()
        fun onSaveInstanceState()
        fun onLowMemory()
        fun onDestroy()
    }

    private var created = false
    private var started = false
    private var resumed = false
    private var destroyed = false

    /** The composition was applied: create the view. */
    fun create() {
        if (created || destroyed) return
        created = true
        view.onCreate()
    }

    fun on(event: Lifecycle.Event) {
        if (!created || destroyed) return
        when (event) {
            Lifecycle.Event.ON_START -> start()
            Lifecycle.Event.ON_RESUME -> {
                start()
                if (!resumed) { resumed = true; view.onResume() }
            }
            Lifecycle.Event.ON_PAUSE -> pause()
            Lifecycle.Event.ON_STOP -> stop()
            Lifecycle.Event.ON_DESTROY -> dispose()
            else -> {}
        }
    }

    fun lowMemory() {
        if (created && !destroyed) view.onLowMemory()
    }

    /** The view leaves the composition (or its owner is destroyed). */
    fun dispose() {
        if (!created || destroyed) return
        stop()
        view.onSaveInstanceState()
        destroyed = true
        view.onDestroy()
    }

    private fun start() {
        if (!started) { started = true; view.onStart() }
    }

    private fun pause() {
        if (resumed) { resumed = false; view.onPause() }
    }

    private fun stop() {
        pause()
        if (started) {
            started = false
            view.onStop()
            view.onSaveInstanceState()
        }
    }
}
