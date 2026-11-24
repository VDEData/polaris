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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.pointer.pointerInput
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
    /** 0 at half or above, 1 at the low rest; always 0 when wide (no sheet). */
    val lowness: Float get() = if (wide) 0f else state?.lowness ?: 0f
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
data class WindowLayout(
    val wide: Boolean = false,
    val short: Boolean = false,
    val cardWidth: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
    /** Wide: where the visible map half starts (past the fold when there is one), from the leading edge. */
    val mapStart: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
)

val LocalWindowLayout = compositionLocalOf { WindowLayout() }

/**
 * A page in the sheet: its content, and its one-row bar for the low rest. The content stays
 * composed at every rest (its scroll position and open notices kept); the bar fades in over it as
 * the sheet nears the low rest, by where the sheet is, and takes the taps there.
 */
@Composable
fun SheetPage(key: NavKey, bar: @Composable () -> Unit, content: @Composable () -> Unit) {
    val sheet = LocalSheet.current
    val paper = polaris.android.ui.LocalPolaris.current.paper
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
        MeasuredBar({ sheet.reportBar(key, it) }) { bar() }
        // Read through derived state: the page recomposes when the sheet crosses half way or starts
        // to leave half, not on every frame of a drag (the fades themselves are read while drawing).
        val low by androidx.compose.runtime.remember(sheet) { androidx.compose.runtime.derivedStateOf { sheet.lowness >= 0.5f } }
        val leaving by androidx.compose.runtime.remember(sheet) { androidx.compose.runtime.derivedStateOf { sheet.lowness > 0f } }
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize()
                // The list fades out over the first half of the way down, the bar in over the second: never both.
                .graphicsLayer { alpha = (1f - sheet.lowness * 2f).coerceIn(0f, 1f) }
                // Hidden at the low rest: not read by TalkBack, not reachable by keyboard or D-pad.
                .then(if (low) androidx.compose.ui.Modifier.clearAndSetSemantics {}.focusProperties { canFocus = false; onEnter = { cancelFocusChange() } } else androidx.compose.ui.Modifier),
        ) { content() }
        if (leaving) {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxWidth()
                    .graphicsLayer { alpha = (sheet.lowness * 2f - 1f).coerceIn(0f, 1f) }
                    .background(paper)
                    // The bar takes taps only once it is the one showing; until then they reach the list.
                    .then(
                        if (low) androidx.compose.ui.Modifier.pointerInput(Unit) { detectTapGestures { } }
                        else androidx.compose.ui.Modifier.clearAndSetSemantics {}.focusProperties { canFocus = false; onEnter = { cancelFocusChange() } },
                    ),
            ) { if (low) bar() }
        }
    }
}
