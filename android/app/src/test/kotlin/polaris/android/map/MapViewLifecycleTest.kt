package polaris.android.map

import androidx.lifecycle.Lifecycle.Event
import kotlin.test.Test
import kotlin.test.assertEquals

/** The map view gets each lifecycle call once, in order, however the owner and composition behave. */
class MapViewLifecycleTest {
    private class Recorder : MapViewLifecycle.Target {
        val calls = mutableListOf<String>()
        override fun onStart() { calls += "start" }
        override fun onResume() { calls += "resume" }
        override fun onPause() { calls += "pause" }
        override fun onStop() { calls += "stop" }
        override fun onDestroy() { calls += "destroy" }
    }

    private fun run(vararg events: Event, dispose: Boolean = false): List<String> {
        val view = Recorder()
        val lifecycle = MapViewLifecycle(view)
        events.forEach(lifecycle::on)
        if (dispose) lifecycle.dispose()
        return view.calls
    }

    @Test fun theActivityStoppingThenTheCompositionLeavingStopsOnce() {
        // The order an activity's destruction gives: the owner stops, then the composition is disposed.
        assertEquals(listOf("start", "resume", "pause", "stop", "destroy"),
            run(Event.ON_CREATE, Event.ON_START, Event.ON_RESUME, Event.ON_PAUSE, Event.ON_STOP, dispose = true))
    }

    @Test fun leavingTheCompositionWhileResumedPausesStopsThenDestroys() {
        assertEquals(listOf("start", "resume", "pause", "stop", "destroy"),
            run(Event.ON_CREATE, Event.ON_START, Event.ON_RESUME, dispose = true))
    }

    @Test fun anOwnerThatIsAlreadyResumedReplaysAndNothingRepeats() {
        assertEquals(listOf("start", "resume"), run(Event.ON_START, Event.ON_RESUME, Event.ON_START, Event.ON_RESUME))
    }

    @Test fun destroyingTheOwnerThenDisposingDestroysOnceAndNothingAfter() {
        assertEquals(listOf("start", "resume", "pause", "stop", "destroy"),
            run(Event.ON_START, Event.ON_RESUME, Event.ON_PAUSE, Event.ON_STOP, Event.ON_DESTROY, Event.ON_START, dispose = true))
    }

    @Test fun aStopWithoutAPauseStillPausesFirst() {
        assertEquals(listOf("start", "resume", "pause", "stop"), run(Event.ON_START, Event.ON_RESUME, Event.ON_STOP))
    }
}
