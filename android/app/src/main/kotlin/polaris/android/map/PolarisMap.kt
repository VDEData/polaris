package polaris.android.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import polaris.android.BuildConfig
import polaris.android.ui.PolarisColors
import polaris.engine.Contact
import polaris.engine.HTTP
import polaris.engine.JourneyMap
import polaris.engine.MapStyle

/**
 * The one map every screen sits on: MapLibre Native in an `AndroidView`, over a keyless open tile
 * source (OpenFreeMap unless the builder set their own), in Polaris's day or night style.
 *
 * Why `AndroidView` and not the MapLibre Compose library: that library is pre-1.0 (its minor
 * releases still break its API) and its current release builds against a newer Android SDK than
 * this project; the classic `MapView` is MapLibre's stable Android API, and the part Compose
 * needs from it (lifecycle, a few setters) is small.
 *
 * Courtesy to the tile server: tiles are fetched only for what is on screen (no offline packs, no
 * lower-zoom prefetch) and kept in MapLibre's default cache, and every request names the app in
 * its User-Agent. No Google Play services: the location dot uses Android's own location provider.
 */
@Composable
fun PolarisMap(
    drawing: JourneyMap.Drawing?,
    night: Boolean,
    modifier: Modifier = Modifier,
    /** Space other content covers (a search box, a sheet, the system bars): the camera fits inside the rest. */
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** Show the person's dot. Only pass true once location permission is granted. */
    showLocation: Boolean = false,
    /** Where to look when there is no journey. */
    home: LatLng = LONDON,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val colors = PolarisColors.of(night)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val mapView = remember {
        MapSetup.ensure(context)
        MapView(context).apply { onCreate(null) }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        mapView.getMapAsync { m ->
            m.uiSettings.isLogoEnabled = false
            // The credit is drawn as visible text below instead of behind an (i) button.
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            // No tiles beyond what is on screen.
            m.prefetchZoomDelta = 0
            m.cameraPosition = CameraPosition.Builder().target(home).zoom(12.0).build()
            map = m
        }
        onDispose {
            lifecycle.removeObserver(observer)
            style = null
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // A new style for day or night; the journey layers are added on top once it loads.
    LaunchedEffect(map, night) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromJson(MapStyle.json(night, BuildConfig.MAP_TILES, BuildConfig.MAP_GLYPHS))) { loaded ->
            JourneyLayers.install(context, loaded, colors)
            style = loaded
        }
    }

    LaunchedEffect(style, drawing) {
        val s = style ?: return@LaunchedEffect
        JourneyLayers.show(s, drawing)
    }

    // Fit the camera to the journey, inside whatever covers the map.
    val padding = with(density) {
        intArrayOf(
            contentPadding.calculateStartPadding(direction).roundToPx(), contentPadding.calculateTopPadding().roundToPx(),
            contentPadding.calculateEndPadding(direction).roundToPx(), contentPadding.calculateBottomPadding().roundToPx(),
        )
    }
    // Fitted when the journey changes, inside the padding at that moment; content growing over
    // the map later (a results list, the keyboard) doesn't move the camera.
    val currentPadding by rememberUpdatedState(padding)
    LaunchedEffect(map, drawing?.bounds) {
        val m = map ?: return@LaunchedEffect
        val padding = currentPadding
        val bounds = drawing?.bounds ?: return@LaunchedEffect
        val margin = with(density) { 32.dp.roundToPx() }
        val update = if (bounds.isPoint) {
            CameraUpdateFactory.newLatLngZoom(LatLng(bounds.centre.latitude, bounds.centre.longitude), 15.0)
        } else {
            CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.from(bounds.north, bounds.east, bounds.south, bounds.west),
                padding[0] + margin, padding[1] + margin, padding[2] + margin, padding[3] + margin,
            )
        }
        m.easeCamera(update, 400)
    }

    LaunchedEffect(style, showLocation) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        LocationDot.update(context, m, s, showLocation, colors)
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize())
        Attribution(colors, Modifier.align(Alignment.BottomStart).padding(contentPadding).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** OpenFreeMap's required credit, always visible, each part linked as on OpenFreeMap's site. */
@Composable
private fun BoxScope.Attribution(colors: PolarisColors, modifier: Modifier) {
    val text = buildAnnotatedString {
        MapStyle.CREDITS.forEachIndexed { index, (words, url) ->
            if (index > 0) append(" ")
            withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = colors.ink.copy(alpha = 0.8f))))) { append(words) }
        }
    }
    Text(
        text,
        modifier = modifier.background(colors.paper.copy(alpha = 0.85f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = colors.ink.copy(alpha = 0.8f)),
    )
}

val LONDON = LatLng(51.5074, -0.1278)

/** MapLibre's one-time set-up: its instance, and the app's User-Agent on every tile request. */
object MapSetup {
    private var done = false

    @Synchronized fun ensure(context: Context) {
        if (done) return
        MapLibre.getInstance(context.applicationContext)
        val agent = HTTP.userAgent(BuildConfig.VERSION_NAME, Contact.valid(BuildConfig.POLARIS_CONTACT))
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", agent).build()) }
                .build(),
        )
        done = true
    }
}

/** The journey's sources and layers: lines in their colours on a casing, dotted walks, stops, the gold star. */
private object JourneyLayers {
    const val LINES = "polaris-journey-lines"
    const val STOPS = "polaris-journey-stops"
    const val STAR = "polaris-star"

    fun install(context: Context, style: Style, colors: PolarisColors) {
        style.addImage(STAR, starBitmap(context))
        style.addSource(GeoJsonSource(LINES))
        style.addSource(GeoJsonSource(STOPS))
        val transit = Expression.eq(Expression.get("walk"), Expression.literal(false))
        val walk = Expression.eq(Expression.get("walk"), Expression.literal(true))
        style.addLayer(
            LineLayer("polaris-casing", LINES).withFilter(transit).withProperties(
                PropertyFactory.lineColor(colors.casing.toArgb()),
                PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(10, 5f), Expression.stop(16, 11f))),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            LineLayer("polaris-walk", LINES).withFilter(walk).withProperties(
                PropertyFactory.lineColor(colors.ink.copy(alpha = 0.7f).toArgb()),
                PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(10, 2f), Expression.stop(16, 4.5f))),
                PropertyFactory.lineDasharray(arrayOf(0f, 2f)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            ),
        )
        style.addLayer(
            LineLayer("polaris-line", LINES).withFilter(transit).withProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(10, 3f), Expression.stop(16, 7f))),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        fun kind(name: String) = Expression.eq(Expression.get("kind"), Expression.literal(name))
        style.addLayer(
            // Changes only once zoomed in: across a whole city they sit on top of the ends.
            CircleLayer("polaris-stop", STOPS).withFilter(kind("change")).withProperties(
                PropertyFactory.circleRadius(5f), PropertyFactory.circleColor(colors.surface.toArgb()),
                PropertyFactory.circleStrokeColor(colors.banner.toArgb()), PropertyFactory.circleStrokeWidth(2.5f),
            ).apply { minZoom = 12.5f },
        )
        style.addLayer(
            CircleLayer("polaris-origin", STOPS).withFilter(kind("origin")).withProperties(
                PropertyFactory.circleRadius(6.5f), PropertyFactory.circleColor(colors.here.toArgb()),
                PropertyFactory.circleStrokeColor(colors.casing.toArgb()), PropertyFactory.circleStrokeWidth(2.5f),
            ),
        )
        style.addLayer(
            SymbolLayer("polaris-destination", STOPS).withFilter(kind("destination")).withProperties(
                PropertyFactory.iconImage(STAR), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            ),
        )
    }

    fun show(style: Style, drawing: JourneyMap.Drawing?) {
        val empty = """{"type":"FeatureCollection","features":[]}"""
        style.getSourceAs<GeoJsonSource>(LINES)?.setGeoJson(drawing?.let(JourneyMap::linesGeoJson) ?: empty)
        style.getSourceAs<GeoJsonSource>(STOPS)?.setGeoJson(drawing?.let(JourneyMap::stopsGeoJson) ?: empty)
    }

    /** The north star (Theme.swift's `NorthStar`), gold with a navy edge, for where the journey ends. */
    private fun starBitmap(context: Context): Bitmap {
        val size = (28 * context.resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = size / 2f
        val v = size / 2f * 0.94f
        val h = v * 0.72f
        val waist = size * 0.09f
        val path = Path().apply {
            moveTo(c, c - v)
            quadTo(c + waist, c - waist, c + h, c)
            quadTo(c + waist, c + waist, c, c + v)
            quadTo(c - waist, c + waist, c - h, c)
            quadTo(c - waist, c - waist, c, c - v)
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PolarisColors.Gold.toArgb() })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PolarisColors.Navy.toArgb(); style = Paint.Style.STROKE; strokeWidth = size * 0.05f; strokeJoin = Paint.Join.ROUND
        })
        return bitmap
    }
}

/** The person's dot, from Android's own location provider; never started without permission. */
private object LocationDot {
    @SuppressLint("MissingPermission")
    fun update(context: Context, map: MapLibreMap, style: Style, show: Boolean, colors: PolarisColors) {
        val component = map.locationComponent
        if (!show) {
            if (component.isLocationComponentActivated) component.isLocationComponentEnabled = false
            return
        }
        val options = LocationComponentOptions.builder(context)
            .foregroundTintColor(colors.here.toArgb())
            .backgroundTintColor(Color.White.toArgb())
            .accuracyColor(colors.here.toArgb())
            .accuracyAlpha(0.12f)
            .pulseEnabled(false)
            .build()
        component.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style)
                .locationComponentOptions(options)
                .useDefaultLocationEngine(true)
                .build(),
        )
        component.isLocationComponentEnabled = true
        component.cameraMode = CameraMode.NONE
        component.renderMode = RenderMode.COMPASS
    }
}
