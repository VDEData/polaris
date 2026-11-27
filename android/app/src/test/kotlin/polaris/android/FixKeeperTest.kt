package polaris.android

import polaris.android.trip.FixKeeper
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Which location fixes Go believes: only ones better than 100 m, and fresh (iOS keeps the same). */
class FixKeeperTest {
    private val now = Instant.parse("2026-11-16T06:30:00Z")
    private val keeper = FixKeeper { now }

    @Test fun aFixAKilometreAndAHalfOutIsDropped() {
        keeper.offer(51.53, -0.12, accuracy = 1_500.0, ageNanos = 0, speed = null, network = true)
        assertNull(keeper.latest)
    }

    @Test fun aFixWithoutAnAccuracyOrOf100mIsDropped() {
        keeper.offer(51.53, -0.12, accuracy = null, ageNanos = 0, speed = null, network = false)
        keeper.offer(51.53, -0.12, accuracy = 100.0, ageNanos = 0, speed = null, network = false)
        assertNull(keeper.latest)
    }

    @Test fun anOldLastKnownFixIsDropped() {
        keeper.offer(51.53, -0.12, accuracy = 10.0, ageNanos = 10L * 60 * 1_000_000_000, speed = null, network = false)
        assertNull(keeper.latest)
    }

    @Test fun aGoodFixIsKeptStampedByItsAgeAndClearedOnStop() {
        keeper.offer(51.53, -0.12, accuracy = 12.0, ageNanos = 3_000_000_000, speed = 1.2, network = false)
        val fix = assertNotNull(keeper.latest)
        assertEquals(now.minusSeconds(3), fix.at)
        assertEquals(12.0, fix.accuracy)
        keeper.clear()
        assertNull(keeper.latest)
    }

    @Test fun aWorseNetworkFixDoesNotReplaceAFreshGpsOne() {
        keeper.offer(51.53, -0.12, accuracy = 8.0, ageNanos = 0, speed = null, network = false)
        keeper.offer(51.54, -0.13, accuracy = 60.0, ageNanos = 0, speed = null, network = true)
        assertEquals(51.53, keeper.latest?.coordinate?.latitude)
    }
}
