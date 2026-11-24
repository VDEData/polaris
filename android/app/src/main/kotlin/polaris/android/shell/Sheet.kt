package polaris.android.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import polaris.android.ui.polaris
import kotlin.math.abs
import kotlin.math.roundToInt

/** The sheet's rests, as on iOS: a one-row bar, half the window, and full height. */
enum class Rest(val spoken: String) { Low("Collapsed"), Half("Half open"), Full("Expanded") }

/**
 * Where the sheet rests and is. A rest the person drags to is remembered ([remembered]); one a
 * page asks for is not (iOS's rule), so going back to Home returns to the person's own rest.
 * Offsets are the sheet's top edge in pixels from the window's top.
 */
@Stable
class SheetState(initial: Rest, remembered: Rest) {
    /** Where it is going or rests. Saved. */
    var rest by mutableStateOf(initial)
        private set
    var remembered by mutableStateOf(remembered)
        private set

    internal val offset = Animatable(Float.NaN)
    internal var anchors by mutableStateOf<Map<Rest, Float>>(emptyMap())

    /** The sheet's top edge, for the map's padding and its credit. */
    val top: Float get() = offset.value

    val rests: List<Rest> get() = Rest.entries.filter { it in anchors }

    /** A page's own wish: not remembered. */
    fun request(scope: CoroutineScope, target: Rest) {
        val to = nearestAvailable(target)
        rest = to
        scope.launch { animateTo(to) }
    }

    /** Back to the rest the person last chose. */
    fun restore(scope: CoroutineScope) = request(scope, remembered)

    internal fun personChose(scope: CoroutineScope, target: Rest) {
        val to = nearestAvailable(target)
        rest = to
        remembered = to
        scope.launch { animateTo(to) }
    }

    private fun nearestAvailable(target: Rest): Rest = when {
        target in anchors || anchors.isEmpty() -> target
        target == Rest.Full -> rests.lastOrNull() ?: target
        else -> rests.firstOrNull() ?: target
    }

    internal suspend fun animateTo(target: Rest) {
        val y = anchors[target] ?: return
        offset.animateTo(y, spring(dampingRatio = 0.9f, stiffness = 500f))
    }

    internal suspend fun updateAnchors(new: Map<Rest, Float>) {
        anchors = new
        val target = nearestAvailable(rest)
        rest = target
        // Snap on a change of shape (a rotation, a fold): the sheet stays at its rest.
        new[target]?.let { offset.snapTo(it) }
    }

    internal fun dragBy(scope: CoroutineScope, delta: Float): Float {
        if (anchors.isEmpty()) return 0f
        val min = anchors.values.min()
        val max = anchors.values.max()
        val now = offset.value
        val to = (now + delta).coerceIn(min, max)
        scope.launch { offset.snapTo(to) }
        return to - now
    }

    /** Where a drag ends up: the nearest rest, or the next one along on a quick flick. */
    internal fun settle(scope: CoroutineScope, velocity: Float) {
        val now = offset.value
        val ordered = anchors.entries.sortedBy { it.value }
        if (ordered.isEmpty()) return
        val target = if (abs(velocity) > 1200f) {
            if (velocity < 0) ordered.lastOrNull { it.value < now - 1 }?.key ?: ordered.first().key
            else ordered.firstOrNull { it.value > now + 1 }?.key ?: ordered.last().key
        } else ordered.minBy { abs(it.value - now) }.key
        personChose(scope, target)
    }

    companion object {
        val Saver: Saver<SheetState, *> = listSaver(
            save = { listOf(it.rest.name, it.remembered.name) },
            restore = { SheetState(Rest.valueOf(it[0]), Rest.valueOf(it[1])) },
        )
    }
}

/**
 * Lets a list inside the sheet drag the sheet first: scrolling up grows the sheet to full height
 * before the list moves, and pulling down at the list's top shrinks it, as Material's bottom
 * sheet does.
 */
fun sheetNestedScroll(state: SheetState, scope: CoroutineScope) = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val dy = available.y
        // Up: grow the sheet before scrolling the list.
        return if (dy < 0 && source == NestedScrollSource.UserInput) Offset(0f, state.dragBy(scope, dy)) else Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val dy = available.y
        // Down, with the list at its top: shrink the sheet.
        return if (dy > 0 && source == NestedScrollSource.UserInput) Offset(0f, state.dragBy(scope, dy)) else Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val anchors = state.anchors.values
        if (anchors.isEmpty()) return Velocity.Zero
        val top = anchors.min()
        // Mid-way between rests: the fling settles the sheet rather than the list.
        return if (state.top > top + 1 && available.y < 0) {
            state.settle(scope, available.y)
            available
        } else Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (state.anchors.values.none { abs(it - state.top) < 1 }) state.settle(scope, available.y)
        return available
    }
}

/**
 * The drag handle: Material's look, and the actions TalkBack offers on Material's own sheet
 * (expand, collapse), with the rest spoken as its state.
 */
@Composable
fun DragHandle(state: SheetState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val rests = state.rests
    val index = rests.indexOf(state.rest)
    Box(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics {
                role = Role.Button
                contentDescription = "Drag handle"
                stateDescription = state.rest.spoken
                customActions = buildList {
                    if (index in 0 until rests.lastIndex) add(CustomAccessibilityAction("Expand") { state.personChose(scope, rests[index + 1]); true })
                    if (index > 0) add(CustomAccessibilityAction("Collapse") { state.personChose(scope, rests[index - 1]); true })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 36.dp, height = 4.dp).background(polaris.inkFaint.copy(alpha = 0.5f), RoundedCornerShape(2.dp)))
    }
}

/**
 * The sheet itself, over the map. [anchorsFor] gives each rest's top edge for the window's
 * height; [bar] is the page's one-row bar shown at the low rest instead of a slice of its list.
 */
@Composable
fun BottomSheet(
    state: SheetState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val c = polaris
    val drag = rememberDraggableState { delta -> state.dragBy(scope, delta) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val statusTop = androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(density)
    val corner = (28f * ((if (state.top.isNaN()) 1f else state.top / statusTop.coerceAtLeast(1)).coerceIn(0f, 1f))).dp
    Column(
        modifier
            .offset { IntOffset(0, if (state.top.isNaN()) 0 else state.top.roundToInt()) }
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            // Square at full height, where the top edge meets the screen's.
            .background(c.paper, RoundedCornerShape(topStart = corner, topEnd = corner))
            .draggable(drag, Orientation.Vertical, onDragStopped = { v -> state.settle(scope, v) })
            .nestedScroll(sheetNestedScroll(state, scope))
            // Under the status bar at full height, the content starts below it.
            .padding(top = with(density) { (statusTop - (if (state.top.isNaN()) statusTop.toFloat() else state.top)).coerceAtLeast(0f).toDp() }),
    ) {
        // Not a Material Surface, so icons and text get the ink colour here (black at night otherwise).
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides c.ink) {
            DragHandle(state)
            content()
        }
    }
}
