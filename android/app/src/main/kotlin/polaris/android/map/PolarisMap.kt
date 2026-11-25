package polaris.android.map

import androidx.compose.ui.layout.onSizeChanged
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import polaris.android.R
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
import polaris.android.storage.Storage
import polaris.android.ui.PolarisColors
import polaris.engine.Contact
import polaris.engine.HTTP
import java.io.IOException
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
    /** Other routes, drawn faintly under [drawing] (the results page's alternatives). */
    others: List<JourneyMap.Drawing> = emptyList(),
    /**
     * The box the camera fits when it changes, inside [contentPadding]: by default the journey's
     * own; the results page passes every route's, so selecting one doesn't move the camera.
     */
    frame: JourneyMap.Bounds? = drawing?.bounds,
    /** What the camera fits routes inside, when more covers the map than its credit must clear (a docked card). */
    fitPadding: PaddingValues = contentPadding,
    /**
     * A change here fits [frame] again inside the padding of that moment (a docked card measured
     * after the routes came). Not the padding itself: a sheet being dragged changes it every frame.
     */
    refitOn: Any? = null,
    /** A request to centre on the person; a new [Recentre.token] is a new request. */
    recentre: Recentre? = null,
    /**
     * What the screen lays over the map (panels, cards, controls). Drawn under the map's credit,
     * which therefore can't be covered; see `MapCredit` for where it sits.
     */
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val colors = PolarisColors.of(night)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    // The view's own saved state (its camera), so a rotation or fold keeps where the person was.
    var savedState by rememberSaveable { mutableStateOf<Bundle?>(null) }
    // One MapView per lifecycle owner. Only constructed here; onCreate runs when the composition
    // is applied (below), so a composition that is abandoned leaves nothing created behind.
    val mapView = remember(owner) {
        MapSetup.ensure(context)
        // Paper while the style loads, so a night map doesn't flash light first.
        MapView(context, org.maplibre.android.maps.MapLibreMapOptions.createFromAttributes(context).foregroundLoadColor(colors.paper.toArgb()))
    }
    var map by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    var style by remember(mapView) { mutableStateOf<Style?>(null) }

    DisposableEffect(owner, mapView) {
        val lifecycle = MapViewLifecycle(object : MapViewLifecycle.Target {
            override fun onCreate() = mapView.onCreate(savedState)
            override fun onStart() = mapView.onStart()
            override fun onResume() = mapView.onResume()
            override fun onPause() = mapView.onPause()
            override fun onStop() = mapView.onStop()
            override fun onSaveInstanceState() {
                savedState = Bundle().also(mapView::onSaveInstanceState)
            }
            override fun onLowMemory() = mapView.onLowMemory()
            override fun onDestroy() = mapView.onDestroy()
        })
        lifecycle.create()
        val observer = LifecycleEventObserver { _, event -> lifecycle.on(event) }
        owner.lifecycle.addObserver(observer)
        val memory = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) lifecycle.lowMemory()
            }
            @Deprecated("Deprecated in Android") override fun onLowMemory() = lifecycle.lowMemory()
            override fun onConfigurationChanged(newConfig: Configuration) {}
        }
        context.registerComponentCallbacks(memory)
        mapView.getMapAsync { m ->
            m.uiSettings.isLogoEnabled = false
            // The credit is drawn as visible text instead of behind an (i) button.
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            // No tiles beyond what is on screen.
            m.prefetchZoomDelta = 0
            if (savedState == null) m.cameraPosition = CameraPosition.Builder().target(home).zoom(12.0).build()
            map = m
        }
        onDispose {
            context.unregisterComponentCallbacks(memory)
            owner.lifecycle.removeObserver(observer)
            style = null
            map = null
            lifecycle.dispose()
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

    LaunchedEffect(style, drawing, others) {
        val s = style ?: return@LaunchedEffect
        JourneyLayers.show(s, drawing, others)
    }

    // Fit the camera to the journey, inside whatever covers the map.
    val padding = with(density) {
        intArrayOf(
            // MapLibre takes left, top, right, bottom: in a right-to-left layout start is the right.
            fitPadding.calculateLeftPadding(direction).roundToPx(), fitPadding.calculateTopPadding().roundToPx(),
            fitPadding.calculateRightPadding(direction).roundToPx(), fitPadding.calculateBottomPadding().roundToPx(),
        )
    }
    // Fitted when the journey changes, inside the padding at that moment; content growing over
    // the map later (a results list, the keyboard) doesn't move the camera.
    // Only a journey not fitted before: after a rotation the saved camera stays where it was.
    val currentPadding by rememberUpdatedState(padding)
    // Whether any of the map shows inside the padding (a sheet at full height covers it all): a
    // fit waits until it does, then happens once, so a page opened from a full sheet frames its
    // place when the sheet comes down rather than fitting into no room at all.
    var mapHeight by remember { mutableStateOf(0) }
    val room = mapHeight > 0 && mapHeight - padding[1] - padding[3] >= with(density) { 120.dp.roundToPx() }
    var fitted by rememberSaveable { mutableStateOf<List<Double>?>(null) }
    // Also refitted when [refitOn] changes within this composition, but not after a rotation,
    // where the saved camera stands.
    var refitSeen by remember { mutableStateOf<Any?>(Unit) }
    LaunchedEffect(map, frame, refitOn, room) {
        val m = map ?: return@LaunchedEffect
        val padding = currentPadding
        val bounds = frame ?: return@LaunchedEffect
        if (!room) return@LaunchedEffect
        val key = listOf(bounds.south, bounds.west, bounds.north, bounds.east)
        val refit = refitSeen != Unit && refitSeen != refitOn
        refitSeen = refitOn
        if (key == fitted && !refit) return@LaunchedEffect
        fitted = key
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

    // Each request once, and not again after a rotation (the token is saved).
    var recentred by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(map, recentre) {
        val m = map ?: return@LaunchedEffect
        val r = recentre ?: return@LaunchedEffect
        if (r.token == recentred) return@LaunchedEffect
        recentred = r.token
        val p = currentPadding
        // Centred in the part of the map left showing: MapLibre's padding moves the centre.
        m.easeCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(r.coordinate.latitude, r.coordinate.longitude))
                    .zoom(maxOf(m.cameraPosition.zoom, 15.0)).padding(p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble()).build(),
            ),
            400,
        )
    }

    LaunchedEffect(style, showLocation) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        LocationDot.update(context, m, s, showLocation, colors)
    }

    Box(modifier.onSizeChanged { mapHeight = it.height }) {
        key(mapView) { AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize()) }
        overlay()
        MapCredit(colors, contentPadding)
    }
}

/**
 * OpenFreeMap's required credit. The rule, for every screen:
 * - it is the last thing drawn over the map, so nothing laid over the map covers it;
 * - it sits at the bottom-start corner (right in a right-to-left language), inset by the real
 *   window insets on every side (system bars, a cutout, the keyboard) and by the bottom the
 *   screen covers (a sheet's top edge), whichever is larger;
 * - it is always fully readable: it wraps as text grows and is never cut or squeezed (no top
 *   padding, no line limit);
 * - it is one 48 dp target that opens the three links, each a full-size menu item, rather than
 *   three links too small to tap.
 * Screens keep that corner clear.
 */
@Composable
private fun BoxScope.MapCredit(colors: PolarisColors, contentPadding: PaddingValues) {
    val direction = LocalLayoutDirection.current
    val uri = LocalUriHandler.current
    var open by remember { mutableStateOf(false) }
    val covered = WindowInsets(
        left = contentPadding.calculateLeftPadding(direction),
        right = contentPadding.calculateRightPadding(direction),
        bottom = contentPadding.calculateBottomPadding(),
    )
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .zIndex(1f)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom).union(covered))
            .padding(horizontal = 4.dp),
    ) {
        Box(
            Modifier
                .sizeIn(minHeight = 48.dp)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.map_show_sources)) { open = true },
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                MapStyle.ATTRIBUTION,
                modifier = Modifier
                    .background(colors.paper.copy(alpha = 0.88f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                // Grows with the text size up to 1.3x, then stops: past that it wrapped over the map's own labels.
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = (10f * minOf(1f, 1.3f / androidx.compose.ui.platform.LocalDensity.current.fontScale)).sp,
                    color = colors.ink.copy(alpha = 0.85f),
                ),
                // Whole, always (the terms ask for all of it): it wraps rather than being cut.
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MapStyle.CREDITS.forEach { (words, url) ->
                DropdownMenuItem(text = { Text(words) }, onClick = {
                    open = false
                    runCatching { uri.openUri(url) }
                })
            }
        }
    }
}

val LONDON = LatLng(51.5074, -0.1278)

/** Centre the map on the person. */
data class Recentre(val coordinate: polaris.engine.Coordinate, val token: Long)

/** MapLibre's one-time set-up: its instance, and the app's User-Agent on every tile, style and font request. */
object MapSetup {
    private var done = false

    @Synchronized fun ensure(context: Context) {
        if (done) return
        val app = context.applicationContext
        MapLibre.getInstance(app)
        // The same User-Agent as every other request: `AppSettings.contact`, read from Storage's
        // in-memory copy so the tile path never waits on storage or the Keystore. The interceptor
        // can't throw anything but an IOException, which OkHttp reports as a failed request
        // rather than crashing the app.
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val agent = runCatching { HTTP.userAgent(BuildConfig.VERSION_NAME, Storage.contact) }
                        .getOrDefault(HTTP.userAgent(BuildConfig.VERSION_NAME, Contact.PROJECT))
                    try {
                        chain.proceed(chain.request().newBuilder().header("User-Agent", agent).build())
                    } catch (e: IOException) {
                        throw e
                    } catch (e: Throwable) {
                        throw IOException("map request failed", e)
                    }
                }
                .build(),
        )
        done = true
    }
}

/** The journey's sources and layers: lines in their colours on a casing, dotted walks, stops, the gold star. */
private object JourneyLayers {
    const val LINES = "polaris-journey-lines"
    const val OTHERS = "polaris-other-lines"
    const val STOPS = "polaris-journey-stops"
    const val STAR = "polaris-star"

    fun install(context: Context, style: Style, colors: PolarisColors) {
        style.addImage(STAR, starBitmap(context))
        style.addSource(GeoJsonSource(OTHERS))
        style.addSource(GeoJsonSource(LINES))
        style.addSource(GeoJsonSource(STOPS))
        val transit = Expression.eq(Expression.get("walk"), Expression.literal(false))
        val walk = Expression.eq(Expression.get("walk"), Expression.literal(true))
        // The alternatives: their rides only, thin and faint, under the chosen route.
        style.addLayer(
            LineLayer("polaris-other", OTHERS).withFilter(transit).withProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.lineOpacity(0.4f),
                PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(10, 2f), Expression.stop(16, 5f))),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
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

    fun show(style: Style, drawing: JourneyMap.Drawing?, others: List<JourneyMap.Drawing>) {
        val empty = """{"type":"FeatureCollection","features":[]}"""
        style.getSourceAs<GeoJsonSource>(OTHERS)?.setGeoJson(
            if (others.isEmpty()) empty else JourneyMap.linesGeoJson(JourneyMap.Drawing(others.flatMap { it.lines }, emptyList(), null)),
        )
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
