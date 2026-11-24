package polaris.android.map

import androidx.lifecycle.Lifecycle.Event
import kotlin.test.Test
import kotlin.test.assertEquals

/** The map view gets each lifecycle call once, in order, however the owner and composition behave. */
class MapViewLifecycleTest {
    private class Recorder : MapViewLifecycle.Target {
        val calls = mutableListOf<String>()
        override fun onCreate() { calls += "create" }
        override fun onStart() { calls += "start" }
        override fun onResume() { calls += "resume" }
        override fun onPause() { calls += "pause" }
        override fun onStop() { calls += "stop" }
        override fun onSaveInstanceState() { calls += "save" }
        override fun onLowMemory() { calls += "lowMemory" }
        override fun onDestroy() { calls += "destroy" }
    }

    private fun run(vararg events: Event, create: Boolean = true, dispose: Boolean = false, block: MapViewLifecycle.() -> Unit = {}): List<String> {
        val view = Recorder()
        val lifecycle = MapViewLifecycle(view)
        if (create) lifecycle.create()
        events.forEach(lifecycle::on)
        lifecycle.block()
        if (dispose) lifecycle.dispose()
        return view.calls
    }

    @Test fun observingAResumedOwnerReplaysCreateStartResumeOnce() {
        // What a LifecycleEventObserver receives when added to an owner already resumed.
        assertEquals(listOf("create", "start", "resume"), run(Event.ON_CREATE, Event.ON_START, Event.ON_RESUME))
    }

    @Test fun theActivityStoppingThenTheCompositionLeavingStopsOnce() {
        assertEquals(listOf("create", "start", "resume", "pause", "stop", "save", "save", "destroy"),
            run(Event.ON_CREATE, Event.ON_START, Event.ON_RESUME, Event.ON_PAUSE, Event.ON_STOP, dispose = true))
    }

    @Test fun leavingTheCompositionWhileResumedPausesStopsSavesThenDestroys() {
        assertEquals(listOf("create", "start", "resume", "pause", "stop", "save", "save", "destroy"),
            run(Event.ON_CREATE, Event.ON_START, Event.ON_RESUME, dispose = true))
    }

    @Test fun stopThenStartAgainGoesRoundAgain() {
        // The app goes to the background and comes back.
        assertEquals(listOf("create", "start", "resume", "pause", "stop", "save", "start", "resume"),
            run(Event.ON_CREATE, Event.ON_START, Event.ON_RESUME, Event.ON_PAUSE, Event.ON_STOP, Event.ON_START, Event.ON_RESUME))
    }

    @Test fun nothingRunsBeforeCreateOrAfterDestroy() {
        // An abandoned composition: never created, so nothing to destroy.
        assertEquals(emptyList(), run(Event.ON_START, Event.ON_RESUME, create = false, dispose = true) { lowMemory() })
        assertEquals(listOf("create", "start", "resume", "pause", "stop", "save", "save", "destroy"),
            run(Event.ON_START, Event.ON_RESUME, Event.ON_DESTROY, Event.ON_START, dispose = true) { lowMemory() })
    }

    @Test fun lowMemoryReachesALiveView() {
        assertEquals(listOf("create", "start", "lowMemory"), run(Event.ON_START) { lowMemory() })
    }
}
