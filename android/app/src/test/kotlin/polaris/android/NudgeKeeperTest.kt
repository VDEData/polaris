package polaris.android

import polaris.android.trip.NudgeKeeper
import polaris.android.trip.NudgePort
import polaris.engine.TripAlert
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The timed nudges' alarms: one due is never cancelled before it fires, one due and never shown is
 * posted in the process (once), one taken out of the plan before its time is cancelled, and a trip
 * started before notifications were allowed gets its alarms when they are.
 */
class NudgeKeeperTest {
    private var now = Instant.parse("2026-11-16T06:30:00Z")
    private fun at(seconds: Long) = Instant.parse("2026-11-16T06:30:00Z").plusSeconds(seconds)
    private fun getOff(id: String, seconds: Long) = TripAlert(id, at(seconds), TripAlert.Kind.GetOff("Baker Street", TripAlert.Then.Walk("Canary Wharf")))

    private class Port : NudgePort {
        override var allowed = true
        val alarms = mutableMapOf<String, Instant>()
        val cancelled = mutableListOf<String>()
        val posted = mutableListOf<String>()
        val showing = mutableSetOf<String>()
        override fun setAlarm(alert: TripAlert) { alarms[alert.id] = alert.date }
        override fun cancelAlarm(id: String) { cancelled += id; alarms.remove(id) }
        override fun post(alert: TripAlert) { posted += alert.id; showing += alert.id }
        override fun isShowing(id: String) = id in showing
    }

    private val port = Port()
    private val keeper = NudgeKeeper(port) { now }

    @Test fun aNudgeAtItsTimeIsNotCancelledButPostedOnce() {
        keeper.schedule(listOf(getOff("getoff.1", 60), getOff("getoff.3", 900)))
        assertEquals(setOf("getoff.1", "getoff.3"), port.alarms.keys)
        // Its time passes; the plan drops it (as TripAlerts.plan does) before the inexact alarm came.
        now = at(62)
        keeper.schedule(listOf(getOff("getoff.3", 900)))
        assertEquals(listOf("getoff.1"), port.posted)
        assertTrue("getoff.3" in port.alarms, "the later nudge's alarm was cancelled")
        // Looking again posts nothing more.
        now = at(64)
        keeper.schedule(listOf(getOff("getoff.3", 900)))
        assertEquals(listOf("getoff.1"), port.posted)
    }

    @Test fun aNudgeTakenOutBeforeItsTimeIsCancelledNotPosted() {
        keeper.schedule(listOf(getOff("getoff.1", 300)))
        now = at(100)
        keeper.schedule(emptyList())
        assertEquals(listOf("getoff.1"), port.cancelled)
        assertTrue(port.posted.isEmpty())
    }

    @Test fun aNudgeItsAlarmAlreadyShowedIsNotPostedAgain() {
        keeper.schedule(listOf(getOff("getoff.1", 60)))
        now = at(61)
        keeper.shown("getoff.1")
        keeper.schedule(emptyList())
        assertTrue(port.posted.isEmpty())
    }

    @Test fun aNudgeLongPastIsNotPosted() {
        keeper.schedule(listOf(getOff("getoff.1", 60)))
        now = at(60 + 20 * 60)
        keeper.schedule(emptyList())
        assertTrue(port.posted.isEmpty())
    }

    @Test fun startedRefusedThenAllowedTheAlarmsAreSet() {
        port.allowed = false
        keeper.schedule(listOf(getOff("getoff.1", 600), getOff("leave", 120)))
        assertTrue(port.alarms.isEmpty())
        port.allowed = true
        keeper.apply()
        assertEquals(setOf("getoff.1", "leave"), port.alarms.keys)
    }

    @Test fun endingCancelsEveryAlarmSet() {
        keeper.schedule(listOf(getOff("getoff.1", 600), getOff("leave", 120)))
        keeper.cancelAll()
        assertTrue(port.alarms.isEmpty())
    }

    /**
     * Android delivers an inexact alarm anywhere in a window of 75% of the time to it, after the
     * time asked. Asked early enough, the window ends at the nudge's time; each early delivery asks
     * again for what is left, so it closes in on the time and never comes late.
     */
    @Test fun alarmsAreAskedEarlySoTheirWindowEndsAtTheNudge() {
        val due = at(25 * 60)
        var t = at(0)
        var steps = 0
        while (true) {
            val ask = polaris.android.trip.TripNoticePlan.alarmAsk(t, due) ?: break
            // The latest Android may deliver it is never after the nudge.
            val latest = ask.plusMillis((java.time.Duration.between(t, ask).toMillis() * 0.75).toLong())
            assertTrue(!latest.isAfter(due.plusSeconds(1)), "could come at $latest, after $due")
            // The earliest it may come: asked again from there.
            t = ask
            steps += 1
            assertTrue(steps < 12, "never closes in")
        }
        // Within a minute of it: posted, not asked again.
        assertTrue(java.time.Duration.between(t, due).seconds <= 60)
    }
}
