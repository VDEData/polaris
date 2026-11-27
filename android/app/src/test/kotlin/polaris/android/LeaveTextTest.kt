package polaris.android

import polaris.android.journey.LeaveKind
import polaris.android.journey.leaveState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** "Leave in N min" on the journey page agrees with the clock times shown beside it. */
class LeaveTextTest {
    private fun at(time: String) = Instant.parse("2026-11-09T${time}Z")

    @Test fun minutesAreTheDifferenceOfTheTwoClocks() {
        // 12:00:50 on the clock is "12:00"; the train is "12:04": four minutes, not three.
        assertEquals(4, leaveState(at("12:00:50"), at("12:04:00")).minutes)
        assertEquals(LeaveKind.inMinutes, leaveState(at("12:00:50"), at("12:04:00")).kind)
        // And never a minute long: 12:00:10 to 12:04:50 is still "12:00" to "12:04".
        assertEquals(4, leaveState(at("12:00:10"), at("12:04:50")).minutes)
    }

    @Test fun theSameMinuteIsNowAndAnHourIsAClockTime() {
        assertEquals(LeaveKind.now, leaveState(at("12:04:05"), at("12:04:55")).kind)
        assertEquals(LeaveKind.now, leaveState(at("12:05:30"), at("12:04:55")).kind)
        assertEquals(LeaveKind.wasDue, leaveState(at("12:07:00"), at("12:04:55")).kind)
        assertEquals(LeaveKind.at, leaveState(at("12:00:00"), at("13:00:00")).kind)
        assertEquals(LeaveKind.inMinutes, leaveState(at("12:00:59"), at("12:59:00")).kind)
    }

    @Test fun theWarningColourStillGoesBySeconds() {
        assertEquals(true, leaveState(at("12:02:30"), at("12:04:00")).urgent)
        assertEquals(false, leaveState(at("12:01:30"), at("12:04:00")).urgent)
    }
}
