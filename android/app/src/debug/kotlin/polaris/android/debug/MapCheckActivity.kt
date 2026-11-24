package polaris.android.debug

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import polaris.android.BuildConfig
import polaris.android.map.PolarisMap
import polaris.android.storage.Storage
import polaris.android.ui.PolarisColors
import polaris.engine.Coordinate
import polaris.engine.HTTP
import polaris.engine.HttpTransport
import polaris.engine.Journey
import polaris.engine.JourneyMap
import polaris.engine.OkHttpTransport
import polaris.engine.PlaceMatch
import polaris.engine.PlaceSearch
import polaris.engine.TransitousProvider
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug builds only, and not the app's UI: a check that the plumbing works on a device. The map
 * with one journey drawn from a recorded Transitous answer (no request for it), a search box over
 * Transitous's geocoder (a request only after typing pauses, counted on screen), the location dot
 * behind a tap, and what settings storage reads back.
 */
class MapCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val night = isSystemInDarkTheme()
            MaterialTheme(colorScheme = PolarisColors.scheme(night)) { MapCheck(night) }
        }
    }
}

class MapCheckModel(application: Application) : AndroidViewModel(application) {
    /** The recorded King's Cross to Canary Wharf answer, read from the debug build's assets. */
    val journey: Journey = application.assets.open("transitous-plan.json").use { stream ->
        TransitousProvider.parse(stream.readBytes(), "King's Cross", "Canary Wharf").first()
    }

    private val sent = AtomicInteger()
    private val _requests = MutableStateFlow(0)
    val requests: StateFlow<Int> = _requests.asStateFlow()
    private val _storage = MutableStateFlow("Reading settings…")
    val storage: StateFlow<String> = _storage.asStateFlow()

    private val _search = MutableStateFlow<PlaceSearch?>(null)
    val search: StateFlow<PlaceSearch?> = _search.asStateFlow()

    init {
        viewModelScope.launch {
            val (settings, _) = Storage.settings(application)
            val counting = HttpTransport { request ->
                _requests.value = sent.incrementAndGet()
                OkHttpTransport().send(request)
            }
            val http = HTTP(counting, BuildConfig.VERSION_NAME) { settings.contact }
            _search.value = PlaceSearch(http, viewModelScope).apply { bias(CENTRAL_LONDON) }
            _storage.value = listOf(
                "Contact in User-Agent: ${settings.contact}",
                "Google key: ${if (settings.googleKey != null) "set" else "none"} · TfL key: ${if (settings.tflKey != null) "set" else "none"}" +
                    if (settings.keysUnreadable) " · an entered key can't be read yet" else "",
                "Sort ${settings.sortOrder.label} · deep search ${if (settings.deepSearch) "on" else "off"} · alerts ${if (settings.alertServer != null) "on" else "off"}",
            ).joinToString("\n")
        }
    }

    companion object {
        val CENTRAL_LONDON = Coordinate(51.508, -0.128)
    }
}

@Composable
private fun MapCheck(night: Boolean, model: MapCheckModel = viewModel()) {
    val context = LocalContext.current
    val colors = PolarisColors.of(night)
    val search by model.search.collectAsStateWithLifecycle()
    val requests by model.requests.collectAsStateWithLifecycle()
    val storage by model.storage.collectAsStateWithLifecycle()
    var picked by remember { mutableStateOf<PlaceMatch?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    var showLocation by remember { mutableStateOf(granted()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { showLocation = granted() }

    val drawing = remember(picked, night) {
        val place = picked?.place
        if (place != null) {
            JourneyMap.Drawing(emptyList(), listOf(JourneyMap.Stop(place.coordinate, place.name, JourneyMap.Stop.Kind.destination)),
                JourneyMap.Bounds(place.coordinate.latitude, place.coordinate.longitude, place.coordinate.latitude, place.coordinate.longitude))
        } else {
            JourneyMap.drawing(model.journey, night)
        }
    }
    var panelHeight by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val bars = WindowInsets.systemBars.asPaddingValues()
    val top = with(density) { panelHeight.toDp() }

    Box(Modifier.fillMaxSize().background(colors.paper)) {
        PolarisMap(
            drawing = drawing, night = night, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = top + 8.dp, bottom = bars.calculateBottomPadding(), start = 8.dp, end = 8.dp),
            showLocation = showLocation,
        ) {
            Column(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    // The map's credit sits in the bottom-start corner; the panel never reaches it.
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 36.dp)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(18.dp))
                    .border(0.75.dp, colors.hairline, RoundedCornerShape(18.dp))
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
                    .onSizeChanged { panelHeight = it.height + with(density) { (12.dp + bars.calculateTopPadding()).roundToPx() } },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Map check (debug build only)", style = MaterialTheme.typography.titleMedium, color = colors.ink,
                    modifier = Modifier.semantics { heading() })
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; picked = null; search?.type(it) },
                    label = { Text("Search places (Transitous)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val state = search?.state?.collectAsStateWithLifecycle()?.value
                val results = state?.results.orEmpty()
                if (results.isNotEmpty() && picked == null) {
                    Column {
                        results.forEach { match ->
                            Column(Modifier.fillMaxWidth().clickable { picked = match; text = match.place.name }.padding(vertical = 8.dp)) {
                                Text(match.place.name, color = colors.ink, style = MaterialTheme.typography.bodyLarge)
                                Text(listOfNotNull(kind(match), match.place.subtitle).joinToString(" · "), color = colors.ink.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider(color = colors.hairline)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        buildString {
                            append("Geocoder requests: $requests")
                            when {
                                state?.isSearching == true -> append(" · searching")
                                state?.error != null -> append(" · ${state.error}")
                            }
                            append("\n").append(if (picked != null) "Showing ${picked!!.place.name}" else "Recorded journey: King's Cross to Canary Wharf")
                        },
                        color = colors.ink.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    if (!showLocation) {
                        FilledTonalButton(onClick = {
                            ask.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                        }) { Text("Show me") }
                    }
                }
                Text(storage, color = colors.ink.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                Text("Journey and places from Transitous (transitous.org/sources) with © OpenStreetMap contributors.",
                    color = colors.ink.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun kind(match: PlaceMatch): String = when (match.kind) {
    PlaceMatch.Kind.stop -> "Stop"
    PlaceMatch.Kind.address -> "Address"
    PlaceMatch.Kind.place -> "Place"
}
