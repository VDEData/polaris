package polaris.android

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import polaris.android.storage.Storage
import polaris.android.ui.PolarisColors
import polaris.engine.Coordinate
import polaris.engine.AppIdentity
import polaris.engine.JourneyQuery
import polaris.engine.JourneyRanker
import polaris.engine.Place
import polaris.engine.ProviderID
import polaris.engine.ProviderStatus
import polaris.engine.RouteOption
import polaris.engine.SearchModel
import polaris.engine.SearchState
import polaris.engine.TimeMode
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Phase 1's only screen: proof that the engine works on a device. One search between two
 * public landmarks, the routes listed as plain text. No design, no map, no navigation yet.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = PolarisColors.scheme(isSystemInDarkTheme())) {
                Surface(Modifier.fillMaxSize()) { SearchScreen() }
            }
        }
    }
}

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    val query = JourneyQuery(
        Place(name = "King's Cross", coordinate = Coordinate(51.5308, -0.1238)),
        Place(name = "Canary Wharf", coordinate = Coordinate(51.5054, -0.0235)),
        TimeMode.LeaveNow,
    )

    init {
        // Started here, not from composition, so a rotation or recomposition never repeats it:
        // one search per launch. Settings are read from storage first, off the main thread.
        viewModelScope.launch {
            val (settings, usage) = Storage.settings(application)
            val model = SearchModel.create(settings, usage, BuildConfig.VERSION_NAME, appIdentity(application))
            launch { model.state.collect { _state.value = it } }
            model.search(query, settings)
        }
    }
}

/**
 * This app's package name and signing certificate, which Google checks a key restricted to an
 * Android app against. Read from the running app, so it is right for whoever built and signed it.
 */
private fun appIdentity(context: Context): AppIdentity {
    val signers = runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners
    }.getOrNull()
    val certificate = signers?.firstOrNull()?.toByteArray()
    return AppIdentity(context.packageName, certificate?.let(AppIdentity::fingerprint)).also {
        Log.i("Polaris", "Google key restriction: package ${it.packageName}, certificate SHA-1 ${it.certificateSha1}")
    }
}

private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/London"))

@Composable
private fun SearchScreen(viewModel: SearchViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // A reading width on wide or unfolded screens; one column on a phone.
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            item {
                Text("Polaris engine check", style = MaterialTheme.typography.titleLarge)
                Text("${viewModel.query.origin.name} to ${viewModel.query.destination.name}, leaving now", style = MaterialTheme.typography.bodyMedium)
                Text(statusLine(state), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            }
            val ranked = JourneyRanker.rank(state.journeys, arriveBy = false)
            if (ranked.top.isNotEmpty()) item { Heading("Best routes") }
            items(ranked.top, key = { it.id }) { RouteText(it) }
            if (ranked.more.isNotEmpty()) item { Heading("More routes") }
            items(ranked.more, key = { it.id }) { RouteText(it) }
            if (!state.isSearching && state.journeys.isEmpty() && state.query != null) item { Text("No routes found.") }
            item {
                // What TfL's and Transitous's terms ask apps using their data to show.
                Text(
                    "Powered by TfL Open Data. Contains OS data © Crown copyright and database rights 2016 and " +
                        "Geomni UK Map data © and database rights [2019]. Timetables from Transitous " +
                        "(transitous.org/sources) with © OpenStreetMap contributors.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

private fun statusLine(state: SearchState): String =
    listOf(ProviderID.tfl, ProviderID.transitous, ProviderID.google).joinToString(" · ") { id ->
        val status = when (val s = state.statuses[id]) {
            null, ProviderStatus.Loading -> "asking"
            is ProviderStatus.Done -> "${s.count} found"
            is ProviderStatus.Failed -> s.reason
            is ProviderStatus.Off -> s.reason
        }
        "${id.displayName}: $status"
    } + if (state.network != null) " · TfL status checked" else ""

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun RouteText(option: RouteOption) {
    val j = option.journey
    val lines = j.transitLegs.joinToString(" > ") { it.line ?: it.mode.label }
    val minutes = (j.duration / 60).toInt()
    val also = if (option.otherDepartures.isEmpty()) "" else "\nOr leave at " + option.otherDepartures.joinToString(" or ") { clock.format(it) }
    val notice = j.worstNotice?.let { "\n${it.title}" } ?: ""
    Text(
        "${clock.format(j.departure)} to ${clock.format(j.arrival)} ($minutes min, ${j.changes} changes)\n$lines\n" +
            "Found by ${option.providers.joinToString(", ") { it.displayName }}$also$notice",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}
