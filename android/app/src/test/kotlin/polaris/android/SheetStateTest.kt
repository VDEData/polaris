package polaris.android

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import polaris.android.shell.Rest
import polaris.android.shell.SheetAnimator
import polaris.android.shell.SheetState
import kotlin.test.Test
import kotlin.test.assertEquals

/** The sheet's rules, on the JVM: rests, memory, dragging, flicks and window changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class SheetStateTest {
    private val instant = SheetAnimator { _, to, onValue -> onValue(to) }
    private val anchors = mapOf(Rest.Low to 1800f, Rest.Half to 1000f, Rest.Full to 0f)

    private fun kotlinx.coroutines.test.TestScope.sheet(rest: Rest = Rest.Half) =
        SheetState(rest, rest, instant).also { it.updateAnchors(this, anchors) }

    @Test fun aDragMovesTheSheetAtOnceWithinItsRests() = runTest {
        val s = sheet()
        assertEquals(-300f, s.dragBy(-300f))
        assertEquals(700f, s.top, "moved in the same call, not later")
        assertEquals(-700f, s.dragBy(-5000f))
        assertEquals(0f, s.top)
    }

    @Test fun theRestPersonChoosesIsRememberedOneAPageAsksForIsNot() = runTest {
        val s = sheet()
        s.personChose(this, Rest.Low); advanceUntilIdle()
        s.request(this, Rest.Half); advanceUntilIdle()
        assertEquals(Rest.Half, s.rest)
        s.restore(this); advanceUntilIdle()
        assertEquals(Rest.Low, s.rest)
        assertEquals(1800f, s.top)
    }

    @Test fun aSlowReleaseGoesToTheNearestRestAndAFlickToTheNext() = runTest {
        val s = sheet()
        s.dragBy(-300f)
        assertEquals(Rest.Half, s.targetFor(0f))
        assertEquals(Rest.Full, s.targetFor(-2000f))
        // Down from between full and half, the next rest along is half; from half, the bar.
        assertEquals(Rest.Half, s.targetFor(2000f))
        s.dragBy(300f)
        assertEquals(Rest.Low, s.targetFor(2000f))
    }

    @Test fun theBarFadesInByWhereTheSheetIsNotWhereItIsGoing() = runTest {
        val s = sheet()
        assertEquals(0f, s.lowness)
        s.dragBy(400f)
        assertEquals(0.5f, s.lowness)
        s.dragBy(400f)
        assertEquals(1f, s.lowness)
    }

    @Test fun aNewWindowShapeKeepsTheRestAndAShortWindowHasNoFullRest() = runTest {
        val s = sheet(Rest.Full)
        s.updateAnchors(this, mapOf(Rest.Low to 700f, Rest.Half to 300f))
        assertEquals(Rest.Half, s.rest)
        assertEquals(300f, s.top)
        s.updateAnchors(this, mapOf(Rest.Low to 900f, Rest.Half to 400f))
        assertEquals(400f, s.top)
    }
}
