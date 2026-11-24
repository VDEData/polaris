package polaris.android.map

import androidx.lifecycle.Lifecycle

/**
 * Drives a `MapView` through its lifecycle exactly: each of start, resume, pause, stop and
 * destroy once and in order, whatever the owner's events or the composition do. Leaving the
 * composition while resumed pauses, then stops, then destroys; an owner's ON_STOP followed by
 * the composition leaving doesn't stop twice; nothing runs after destroy.
 */
class MapViewLifecycle(private val view: Target) {
    /** The parts of `MapView` this calls, so it can be tested without a device. */
    interface Target {
        fun onStart()
        fun onResume()
        fun onPause()
        fun onStop()
        fun onDestroy()
    }

    private var started = false
    private var resumed = false
    private var destroyed = false

    fun on(event: Lifecycle.Event) {
        if (destroyed) return
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

    /** The view leaves the composition (or its owner is destroyed). */
    fun dispose() {
        if (destroyed) return
        stop()
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
        if (started) { started = false; view.onStop() }
    }
}
