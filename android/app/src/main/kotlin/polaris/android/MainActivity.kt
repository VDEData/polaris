package polaris.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import polaris.android.app.AppGraph
import polaris.engine.Appearance
import polaris.android.shell.PolarisApp
import polaris.android.ui.PolarisTheme
import polaris.android.ui.polaris

/**
 * The app: one map, one sheet (see `PolarisApp`). Edge to edge, light and dark following the
 * system. Storage is read off the main thread before the pages appear (a few milliseconds);
 * nothing is sent at launch beyond the map's tiles.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val demo = Variant.transport(this, intent)
        val secrets = Variant.secrets(intent)
        val startTrip = if (savedInstanceState == null) Variant.startTrip(intent) else null
        val fresh = savedInstanceState == null
        setContent {
            val context = LocalContext.current
            // Already built after a rotation or a fold: no blank first frame then.
            var graph by remember { mutableStateOf(AppGraph.cached()) }
            LaunchedEffect(Unit) { graph = AppGraph.load(context, demo, secrets) }
            // The appearance chosen in Settings, or the phone's own.
            val chosen = graph?.appearance?.collectAsState()?.value ?: Appearance.system
            val night = when (chosen) {
                Appearance.system -> isSystemInDarkTheme()
                Appearance.light -> false
                Appearance.dark -> true
            }
            // The bars' icons follow the appearance in use, not only the phone's.
            LaunchedEffect(night) {
                val clear = android.graphics.Color.TRANSPARENT
                val style = if (night) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            PolarisTheme(night) {
                Box(Modifier.fillMaxSize().background(polaris.paper)) {
                    graph?.let { g ->
                        val setup = remember(g) { fresh && g.takeSetup() }
                        PolarisApp(g, night, startTrip, showSetup = setup)
                    }
                }
            }
        }
    }
}
