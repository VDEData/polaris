package polaris.android.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.navigation3.runtime.NavKey
import polaris.engine.JourneyMap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * What the map shows, set by each page for itself (iOS's `MapScene`). Every page on the stack
 * may write its own entry, but only the entry of the page on top reaches the map, so a page lower
 * in the stack (alive during a transition) never drives it.
 */
@Stable
class MapScene {
    data class Content(
        val drawing: JourneyMap.Drawing? = null,
        val others: List<JourneyMap.Drawing> = emptyList(),
        val frame: JourneyMap.Bounds? = drawing?.bounds,
    )

    private val contents = mutableStateMapOf<NavKey, Content>()
    private val docks = mutableStateMapOf<NavKey, @Composable () -> Unit>()

    fun set(key: NavKey, content: Content) {
        contents[key] = content
    }

    fun clear(key: NavKey) {
        contents.remove(key)
        docks.remove(key)
    }

    fun of(key: NavKey?): Content = key?.let { contents[it] } ?: Content()

    /** A card the page docks at the foot of the map half when the window is wide (sort and filters). */
    fun dock(key: NavKey, card: (@Composable () -> Unit)?) {
        if (card == null) docks.remove(key) else docks[key] = card
    }

    fun dockOf(key: NavKey?): (@Composable () -> Unit)? = key?.let { docks[it] }
}

/** The sheet as a page sees it: its rest, whether the window is wide (no sheet: a card), and how to ask for a rest. */
@Stable
class SheetHost(
    val state: SheetState?,
    val wide: Boolean,
    private val requestRest: (Rest) -> Unit,
    private val restoreRest: () -> Unit,
    private val onBar: (NavKey, Int) -> Unit,
) {
    val atLowRest: Boolean get() = !wide && state?.rest == Rest.Low
    fun request(rest: Rest) = requestRest(rest)
    fun restore() = restoreRest()
    fun reportBar(key: NavKey, height: Int) = onBar(key, height)
}

val LocalScene = compositionLocalOf { MapScene() }
val LocalSheet = compositionLocalOf<SheetHost> { error("No sheet") }

/** Sets what the map shows while this page is on the stack. */
@Composable
fun SceneEffect(key: NavKey, content: MapScene.Content) {
    val scene = LocalScene.current
    LaunchedEffect(key, content) { scene.set(key, content) }
    DisposableEffect(key) { onDispose { scene.clear(key) } }
}

/** Measures a page's low-rest bar for the sheet without drawing it or showing it to TalkBack. */
@Composable
fun MeasuredBar(onHeight: (Int) -> Unit, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier
            .clearAndSetSemantics {}
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints.copy(minHeight = 0))
                onHeight(p.height)
                layout(0, 0) {}
            },
    ) { content() }
}

/**
 * The window's shape, decided once for every page and the search over them: wide (a card on the
 * leading half, stopping 48 dp short of a fold, else 400 dp), short (under 480 dp tall: the sheet
 * has its low and half rests only), or the phone layout.
 */
data class WindowLayout(val wide: Boolean = false, val short: Boolean = false, val cardWidth: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified)

val LocalWindowLayout = compositionLocalOf { WindowLayout() }
