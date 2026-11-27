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
import polaris.android.alerts.LineNotices
import polaris.android.app.AppGraph
import polaris.android.trip.TripNotifier
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
    /** A tap on Go's notification (open Go, or Switch to a faster way), once per tap. */
    private val goRequest = mutableStateOf<GoRequest?>(null)

    /** A tap on a line alert's notification, once per tap. */
    private val alertsRequest = mutableStateOf<Long?>(null)

    private fun take(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(LineNotices.EXTRA_OPEN_ALERTS, false) == true) {
            alertsRequest.value = System.nanoTime()
            intent.removeExtra(LineNotices.EXTRA_OPEN_ALERTS)
        }
        if (intent?.getBooleanExtra(TripNotifier.EXTRA_OPEN_GO, false) != true) return
        val switch = intent.getStringExtra(TripNotifier.EXTRA_ACTION) == TripNotifier.ACTION_SWITCH
        goRequest.value = GoRequest(switch, verified = switch && intent.getStringExtra(TripNotifier.EXTRA_TOKEN) == TripNotifier.TOKEN)
        intent.removeExtra(TripNotifier.EXTRA_OPEN_GO)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val demo = Variant.transport(this, intent)
        val secrets = Variant.secrets(intent)
        val clock = Variant.clock(intent)
        val replan = Variant.replanSearch(intent)
        val push = Variant.push(intent)
        val values = Variant.values(this, intent)
        val startTrip = if (savedInstanceState == null) Variant.startTrip(intent) else null
        val fresh = savedInstanceState == null
        if (fresh) take(intent)
        setContent {
            val context = LocalContext.current
            // Already built after a rotation or a fold: no blank first frame then.
            var graph by remember { mutableStateOf(AppGraph.cached()) }
            LaunchedEffect(Unit) { graph = AppGraph.load(context, demo, secrets, clock, replan, push, values) }
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
                        PolarisApp(g, night, startTrip, showSetup = setup, goRequest = goRequest.value, goRequestTaken = { goRequest.value = null },
                            alertsRequest = alertsRequest.value, alertsRequestTaken = { alertsRequest.value = null },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Go's notification tapped: open Go, and [switch] to the faster way it offered. [verified]: the tap
 * carried this process's own token. Without it (a cold launch, or another app's intent) a live
 * offer is never taken; with no live offer the trip says the faster way has gone.
 */
data class GoRequest(val switch: Boolean, val verified: Boolean = false, val token: Long = System.nanoTime())
