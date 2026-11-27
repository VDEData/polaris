package polaris.android.go

import polaris.android.ui.keepLastTwo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import polaris.android.R
import polaris.android.app.AppGraph
import polaris.android.journey.DepartureStrip
import polaris.android.shell.GoPage
import polaris.android.shell.Rest
import polaris.android.shell.LocalScene
import polaris.android.shell.LocalSheet
import polaris.android.shell.LocalWindowLayout
import polaris.android.shell.MapScene
import polaris.android.shell.SceneEffect
import polaris.android.shell.SheetPage
import polaris.android.ui.FractalStar
import polaris.android.ui.LegStrip
import polaris.android.ui.LinePill
import polaris.android.ui.Symbol
import polaris.android.ui.modeIcon
import polaris.android.ui.onLineColor
import polaris.android.ui.polaris
import polaris.android.ui.surface
import polaris.android.ui.lineColor
import polaris.android.trip.approximateOnly
import polaris.engine.JourneyMap
import polaris.engine.Connection
import polaris.engine.ReplanMessage
import polaris.engine.Leg
import polaris.engine.LiveTrip
import polaris.engine.Loadable
import java.time.Instant

/**
 * Go, the live trip (iOS's `LiveGuide` and `GuidanceBanner`): across the top of the map, the
 * instruction for now; in the sheet, when you'll arrive with End, a faster way when there is one,
 * the step button, whether you'll make the next ride, its live departures, and the steps (tap one
 * to jump to it). The trip itself lives in [polaris.android.trip.TripSession], so leaving this page
 * (system back) doesn't stop it: the journey page and Home offer the way back, and End stops it.
 *
 * Its rest is the low bar (its own top row and the step button), so the map leads, as on iOS.
 */
@Composable
fun GoScreen(key: GoPage, graph: AppGraph, openSettings: () -> Unit, onLeave: () -> Unit) {
    val version by graph.trips.version.collectAsStateWithLifecycle()
    val live by graph.trips.trip.collectAsStateWithLifecycle()
    val trip = live
    val c = polaris
    val words = GoWords(LocalContext.current.resources)

    if (trip == null) {
        // Ended elsewhere, or past its time after Android stopped the app: nothing to follow.
        LaunchedEffect(Unit) { onLeave() }
        SheetPage(key, bar = {}) {
            Text(stringResource(R.string.go_ended), Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
        }
        return
    }
    // Read on every change of the trip (its own two-second looks, a tap, a board coming in).
    @Suppress("UNUSED_EXPRESSION") version
    val now = secondsWhenClose(trip, graph.clock)
    // Checked when the page comes back on screen (from the system settings, say).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val notificationsOff = remember(resumed, version) { android.os.Build.VERSION.SDK_INT >= 33 && graph.notifier?.allowed == false }
    val pageContext = LocalContext.current
    // Approximate location only: Go can't follow you, and says how to let it.
    val approximate = remember(resumed, version) { approximateOnly(pageContext) }
    LaunchedEffect(approximate) { if (!approximate) graph.notifier?.startService() }
    // Turned on in the settings during the trip: the trip appears in the shade at once. Only on
    // that change, not each time the page is shown (the service is already running then).
    var wasOff by rememberSaveable { mutableStateOf(notificationsOff) }
    LaunchedEffect(notificationsOff) {
        if (wasOff && !notificationsOff) graph.notifier?.let { n -> n.start(trip.query.destination.name, trip.card(), adopt = true) }
        wasOff = notificationsOff
    }
    val end: () -> Unit = {
        graph.trips.end()
        onLeave()
    }

    // The trip is one object that changes inside, so each part is handed what it shows (Compose
    // skips a part whose arguments haven't changed, and doesn't count an argument it never reads).
    GoMap(key, trip, trip.journey, trip.legIndex, trip.estimatedPosition)
    // A new faster way raises the sheet from its low rest so the card and its buttons are whole,
    // as iOS does (wide open the card already shows it).
    val sheet = LocalSheet.current
    val offered = trip.offer?.journey?.id
    // A faster way raises the sheet so its card, Switch and Keep my route together, is in view: to
    // half, or at large text (where the choices stack) to full.
    val largeText = LocalDensity.current.fontScale >= 1.5f
    // Never from under the person's finger (it waits for a drag to end), and back to their own rest
    // when the offer goes.
    var raisedForOffer by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(offered) {
        if (sheet.wide) return@LaunchedEffect
        if (offered == null) {
            if (raisedForOffer) sheet.restore()
            raisedForOffer = false
            return@LaunchedEffect
        }
        while (sheet.state?.beingDragged == true) kotlinx.coroutines.delay(150)
        val rest = sheet.state?.rest
        val to = if (largeText && rest != Rest.Full) Rest.Full else if (rest == Rest.Low) Rest.Half else null
        if (to != null) {
            sheet.request(to)
            raisedForOffer = true
        }
    }
    Banner(key, trip, words, now)
    StepAnnouncement(trip, words, now)

    SheetPage(key, bar = {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Header(trip, words, openSettings, end)
            StepButton(trip, words)
        }
    }) {
        val list = rememberLazyListState()
        KeepRestoredPlace(list)
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (LocalSheet.current.wide) 16.dp else 4.dp, bottom = 24.dp),
        ) {
            // Every slot is always there, empty when it has nothing to show, so a list restored
            // after the process was ended finds its place before the trip's later parts arrive.
            slot("header") { Header(trip, words, openSettings, end) }
            // A faster way leads, above the step: it's the decision to make now.
            val offer = trip.offerMessage
            val status = replanLine(trip)
            slot("lead", if (offer != null || status != null) ({
                if (offer != null) OfferCard(trip, words.said(offer), trip.offer) else if (status != null) StatusCard(status, words)
            }) else null)
            slot("step") { StepButton(trip, words) }
            slot("precise", if (approximate) ({ PreciseLocation() }) else null)
            slot("connection") { if (!approximate) WillYouMakeIt(trip, words, now) }
            slot("faster", if (trip.canFindFaster) ({
                TextButton(onClick = trip::findFaster, modifier = Modifier.heightIn(min = 48.dp)) {
                    Symbol(R.drawable.go_branch, null, Modifier.size(18.dp), tint = c.tint)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.go_find_faster), fontWeight = FontWeight.SemiBold, color = c.tint)
                }
            }) else null)
            val ride = trip.nextRide ?: trip.leg.takeIf { trip.isWaiting }
            slot("strip", if (ride != null) ({
                Column(Modifier.fillMaxWidth().surface(c).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinePill(ride)
                        Text(ride.fromName, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    val board = trip.board(ride)
                    val state = board?.let { Loadable.Loaded(it) } ?: graph.departures.board(ride)
                    DepartureStrip(ride, state, reachable = trip.reachableBy, onOpen = null, now = now)
                }
            }) else null)
            slot("steps") { Steps(trip, words) }
            if (notificationsOff) item(key = "notifications") {
                // Refused: Go carries on here; the way to change it, never asked again unprompted.
                val context = LocalContext.current
                Column(Modifier.fillMaxWidth().surface(c).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.go_notifications_off), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.go_notifications_settings)) }
                }
            }
        }
    }
}

/** The trip's clock, every second inside the last two minutes before a ride leaves (the seconds countdown), else at Go's own looks. */
@Composable
private fun secondsWhenClose(trip: LiveTrip, clock: () -> Instant): Instant {
    val close = trip.isWaiting && trip.rideDeparture.epochSecond - trip.now.epochSecond < 125
    val now by produceState(trip.now, close, trip.now) {
        value = clock()
        while (close) {
            delay(1_000)
            value = clock()
        }
    }
    return if (close) now else trip.now
}

/** What re-planning has to say when there's no offer: looking, a hand-asked answer, or what the timeline did. */
private sealed interface Line {
    data class Looking(val origin: String) : Line
    data class Message(val message: ReplanMessage) : Line
}

private fun replanLine(trip: LiveTrip): Line? = when (val state = trip.replan) {
    is LiveTrip.ReplanState.Looking -> Line.Looking(state.origin.name)
    is LiveTrip.ReplanState.Said -> Line.Message(state.message)
    else -> trip.replanStatus?.let(Line::Message)
}

/** The route on the map, framed on the leg you're on (and the stop you're walking to); the underground estimate as a ring. */
@Composable
private fun GoMap(key: GoPage, trip: LiveTrip, journey: polaris.engine.Journey, index: Int, estimate: polaris.engine.Coordinate?) {
    val night = polaris.isNight
    val drawing = remember(journey, night) {
        JourneyMap.drawing(journey, night, trip.query.origin.takeUnless { it.isCurrentLocation }?.coordinate, trip.query.destination.coordinate)
    }
    // Framed once per step, not on every fix: the dot moves inside the frame, and a pan isn't undone every two seconds.
    val frame = remember(journey, index) {
        val points = trip.leg.path + (trip.nextRide?.path?.take(1) ?: emptyList()) + listOfNotNull(trip.position?.takeIf { p -> trip.leg.path.any { JourneyMap.metres(it, p) < 3_000 } })
        if (points.isEmpty()) drawing.bounds else JourneyMap.Bounds(points.minOf { it.latitude }, points.minOf { it.longitude }, points.maxOf { it.latitude }, points.maxOf { it.longitude })
    }
    // Go frames the step again whenever the screen is made anew (a fold, a turn, a change of
    // language or text size), where other pages keep their camera: the person is following the trip.
    val instance = remember { java.util.UUID.randomUUID().toString() }
    val window = androidx.compose.ui.platform.LocalConfiguration.current.let { "${it.screenWidthDp}x${it.screenHeightDp}:$instance" }
    SceneEffect(key, MapScene.Content(drawing, frame = frame, estimate = estimate, fitKey = window))
}

/** The instruction across the top of the map, in the banner navy (iOS's `GuidanceBanner`). */
@Composable
private fun Banner(key: GoPage, trip: LiveTrip, words: GoWords, now: Instant) {
    val scene = LocalScene.current
    val primary = words.primary(trip, now)
    val secondary = words.secondary(trip)
    val offer = trip.offerMessage?.let { words.said(it) }
    val then = words.then(trip, now)
    val leg = trip.leg
    val night = polaris.isNight
    val content: @Composable () -> Unit = {
        val c = polaris
        Row(
            Modifier.fillMaxWidth()
                .background(c.banner, RoundedCornerShape(24.dp))
                .border(0.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (leg.mode.isTransit) {
                Box(Modifier.size(44.dp).background(lineColor(leg.lineColor, night), CircleShape).border(1.5.dp, Color.White.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                    Symbol(modeIcon(leg.mode), null, Modifier.size(24.dp), tint = onLineColor(leg.lineColor, night))
                }
            } else {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Symbol(if (trip.isLastLeg) R.drawable.sym_location_on else R.drawable.sym_directions_walk, null, Modifier.size(28.dp), tint = Color.White)
                }
            }
            // Every line wraps rather than being cut, so no time or stop is lost at large text; the
            // sheet's half rest sits below the whole banner.
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(primary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(secondary, style = MaterialTheme.typography.titleSmall, color = Color.White)
                if (offer != null) {
                    // A faster way takes the last line, in gold.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FractalStar(14.dp, color = c.gold)
                        Text(offer.short, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.gold)
                    }
                } else if (then != null) {
                    Text(then, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                }
            }
        }
    }
    DisposableEffect(key, primary, secondary, offer, then, leg.id, night) {
        scene.banner(key, content)
        onDispose { scene.banner(key, null) }
    }
}

/**
 * TalkBack hears the instruction when the step changes (and when a faster way is offered), not
 * every tick or second; a rotation or fold, which keeps the saved step, says nothing again.
 */
@Composable
private fun StepAnnouncement(trip: LiveTrip, words: GoWords, now: Instant) {
    val view = LocalView.current
    var spoken by rememberSaveable { mutableStateOf<String?>(null) }
    val stepKey = "${trip.journey.id}:${trip.legIndex}:${trip.offer != null}"
    LaunchedEffect(stepKey) {
        if (spoken != null && spoken != stepKey) {
            val text = trip.offerMessage?.let { words.said(it).title } ?: view.context.getString(R.string.go_announce_step, words.primary(trip, now), words.secondary(trip))
            @Suppress("DEPRECATION") view.announceForAccessibility(text)
        }
        spoken = stepKey
    }
}

/** Go's own top row: when you'll arrive, where to and how long is left, with Settings and End. */
@Composable
private fun Header(trip: LiveTrip, words: GoWords, openSettings: () -> Unit, end: () -> Unit) {
    val c = polaris
    val endHint = stringResource(R.string.go_end_hint)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(words.arrive(trip), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = c.ink, maxLines = 2)
            Text(words.headerDetail(trip), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        val gear = if (LocalDensity.current.fontScale >= 1.5f) 30.dp else 24.dp
        IconButton(onClick = openSettings, modifier = Modifier.size(48.dp)) { Symbol(R.drawable.sym_settings, stringResource(R.string.settings_title), Modifier.size(gear), tint = c.inkSoft) }
        OutlinedButton(
            onClick = end,
            modifier = Modifier.heightIn(min = 48.dp).semantics { onClick(label = endHint) { end(); true } },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.warning),
            border = androidx.compose.foundation.BorderStroke(1.dp, c.warning.copy(alpha = 0.6f)),
        ) { Text(stringResource(R.string.go_end), fontWeight = FontWeight.SemiBold, maxLines = 1) }
    }
}

/** Go's main action: tell it you're on the ride (an earlier one, say, or underground where it can't see), or that you're off. */
@Composable
private fun StepButton(trip: LiveTrip, words: GoWords) {
    val step = trip.manualStep ?: return
    val c = polaris
    val hint = stringResource(R.string.go_step_hint)
    FilledTonalButton(
        onClick = { trip.take(step) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { onClick(label = hint) { trip.take(step); true } },
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = c.tint.copy(alpha = 0.14f), contentColor = c.tint),
    ) {
        val icon = when (step) {
            is LiveTrip.Step.Boarded -> modeIcon(step.ride.mode)
            is LiveTrip.Step.GotOff -> R.drawable.sym_directions_walk
        }
        Symbol(icon, null, Modifier.size(18.dp), tint = c.tint)
        Spacer(Modifier.width(8.dp))
        Text(words.step(step, trip), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Will you make it? Your walk against the departure, and what to do if not; or missed; or no fix yet. */
@Composable
private fun WillYouMakeIt(trip: LiveTrip, words: GoWords, now: Instant) {
    val c = polaris
    val connection = trip.connection
    val ride = trip.nextRide
    when {
        connection != null && ride != null -> {
            val departure = trip.nextRideDeparture ?: ride.departure
            val (icon, tint) = when (connection.verdict) {
                Connection.Verdict.comfortable -> R.drawable.go_check to c.live
                Connection.Verdict.tight -> R.drawable.sym_directions_walk to c.warning
                Connection.Verdict.missing -> R.drawable.sym_warning to c.warning
            }
            Notice(icon, tint, words.connection(connection, ride, departure, trip.platform(ride)), words.advice(connection, ride, trip.board, trip.ifMissed, trip.query.destination.name, now))
        }
        trip.missedRide -> words.missedNext(trip.board(trip.leg), trip.leg, now)?.let { Notice(R.drawable.sym_refresh, c.warning, it, null) }
        trip.position == null -> Notice(R.drawable.sym_location_on, c.inkSoft, stringResource(R.string.go_no_fix), null)
        else -> Unit
    }
}

/** Only approximate location allowed: Go says it needs precise location, with the way to the app's settings. */
@Composable
private fun PreciseLocation() {
    val c = polaris
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().surface(c).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.go_precise_needed), style = MaterialTheme.typography.bodyMedium, color = c.ink)
        TextButton(onClick = {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.go_precise_settings)) }
    }
}

@Composable
private fun Notice(icon: Int, tint: Color, title: String, detail: String?) {
    val c = polaris
    Row(Modifier.fillMaxWidth().surface(c).padding(14.dp).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Symbol(icon, null, Modifier.size(20.dp), tint = tint)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink)
            detail?.let { Text(keepLastTwo(it), style = MaterialTheme.typography.bodySmall, color = c.inkSoft) }
        }
    }
}

@Composable
private fun StatusCard(line: Line, words: GoWords) {
    val c = polaris
    when (line) {
        is Line.Looking -> Row(Modifier.fillMaxWidth().surface(c).padding(14.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FractalStar(20.dp, lit = listOf(true, false, true, false))
            Text(stringResource(R.string.go_looking, line.origin), style = MaterialTheme.typography.titleSmall, color = c.ink)
        }
        is Line.Message -> {
            val said = words.said(line.message)
            val icon = when (val m = line.message) {
                is ReplanMessage.NoSignal -> R.drawable.sym_wifi_off
                is ReplanMessage.LongWait -> R.drawable.go_hourglass
                is ReplanMessage.OtherRide -> R.drawable.sym_schedule
                else -> R.drawable.go_check.also { m.hashCode() }
            }
            Notice(icon, c.goldInk, said.title, said.detail)
        }
    }
}

/** A faster way, with Switch and Keep my route (iOS's `ReplanCard`). */
@Composable
private fun OfferCard(trip: LiveTrip, said: GoWords.Said, offer: polaris.engine.Replan.Offer?) {
    val c = polaris
    offer ?: return
    val switchHint = stringResource(R.string.go_switch_hint, offer.journey.legs.firstOrNull()?.fromName ?: offer.origin.name)
    Column(Modifier.fillMaxWidth().surface(c, highlighted = true).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FractalStar(20.dp)
            Text(said.title, style = MaterialTheme.typography.titleMedium, color = c.ink)
        }
        LegStrip(offer.journey.legs)
        Text(said.detail, style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
        said.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.warning) }
        val large = LocalDensity.current.fontScale >= 1.5f
        val switch: @Composable (Modifier) -> Unit = { m ->
            Button(
                onClick = trip::acceptOffer,
                modifier = m.heightIn(min = 48.dp).semantics { onClick(label = switchHint) { trip.acceptOffer(); true } },
                colors = ButtonDefaults.buttonColors(containerColor = c.tint, contentColor = if (c.isNight) c.navy else Color.White),
            ) { Text(stringResource(R.string.go_switch), fontWeight = FontWeight.SemiBold) }
        }
        val keep: @Composable (Modifier) -> Unit = { m ->
            OutlinedButton(onClick = trip::keepRoute, modifier = m.heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink)) {
                Text(stringResource(R.string.go_keep), fontWeight = FontWeight.SemiBold, maxLines = 2)
            }
        }
        if (large) {
            switch(Modifier.fillMaxWidth())
            keep(Modifier.fillMaxWidth())
        } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            switch(Modifier.weight(1f))
            keep(Modifier.weight(1f))
        }
    }
}

/** The steps, done, now and to come, as the trip is going; tap one to jump to it. */
@Composable
private fun Steps(trip: LiveTrip, words: GoWords) {
    val c = polaris
    val timed = trip.timeline.journey
    Column(Modifier.fillMaxWidth().surface(c).padding(vertical = 6.dp)) {
        Text(stringResource(R.string.go_steps), Modifier.padding(horizontal = 14.dp, vertical = 6.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = c.inkSoft)
        trip.journey.legs.forEachIndexed { index, leg ->
            val state = when {
                index < trip.legIndex -> stringResource(R.string.go_step_done)
                index == trip.legIndex -> stringResource(R.string.go_step_now)
                else -> null
            }
            val current = index == trip.legIndex
            val done = index < trip.legIndex
            val before = trip.journey.legs.getOrNull(index - 1)
            val after = trip.journey.legs.getOrNull(index + 1)
            val title = when {
                leg.mode.isTransit -> stringResource(R.string.go_step_ride, words.lineOf(leg), leg.toName)
                index == trip.journey.legs.lastIndex -> stringResource(R.string.go_walk_to, trip.query.destination.name)
                before?.mode?.isTransit == true && after?.mode?.isTransit == true -> stringResource(R.string.go_change_to, words.lineOf(after))
                else -> stringResource(R.string.go_walk_to, leg.toName)
            }
            val jump = stringResource(R.string.go_jump_hint)
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(if (current) c.tint.copy(alpha = 0.08f) else Color.Transparent)
                    .clickable(role = Role.Button, onClickLabel = jump) { trip.select(index) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) { state?.let { stateDescription = it } },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // A step done shows its tick, not a time: jumping past steps ends them all at the same moment.
                Text(
                    if (done) "" else words.clock(timed.legs[index].departure),
                    Modifier.widthIn(min = 44.dp),
                    style = MaterialTheme.typography.labelLarge, color = if (done) c.inkFaint else c.ink,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                )
                if (done) Symbol(R.drawable.go_check, null, Modifier.size(18.dp), tint = c.live)
                else if (leg.mode.isTransit) LinePill(leg)
                else Symbol(R.drawable.sym_directions_walk, null, Modifier.size(18.dp), tint = if (current) c.tint else c.inkSoft)
                Text(
                    title, Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, color = if (done) c.inkFaint else c.ink,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One of Go's list slots: always present under its key, with the gap below it only when it shows something. */
private fun androidx.compose.foundation.lazy.LazyListScope.slot(key: String, content: (@Composable () -> Unit)?) {
    item(key = key) {
        if (content != null) Box(Modifier.padding(bottom = 12.dp)) { content() }
    }
}

/**
 * Brought back after the process was ended, the list's saved place can be further down than the
 * list first reaches: the departure board isn't loaded yet, so the list is shorter and stops
 * short. For a few seconds, as the late parts arrive, it goes back to the saved place, unless the
 * person scrolls first.
 */
@Composable
private fun KeepRestoredPlace(list: androidx.compose.foundation.lazy.LazyListState) {
    val saved = remember { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
    LaunchedEffect(list) {
        if (saved.first == 0 && saved.second == 0) return@LaunchedEffect
        var placed: Pair<Int, Int>? = null
        kotlinx.coroutines.withTimeoutOrNull(5_000) {
            androidx.compose.runtime.snapshotFlow { list.layoutInfo.visibleItemsInfo.map { it.key to it.size } }.collect {
                val at = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
                if (list.isScrollInProgress || (placed != null && at != placed)) throw kotlinx.coroutines.CancellationException("moved by the person")
                if (at != saved) list.scrollToItem(saved.first, saved.second)
                placed = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
            }
        }
    }
}
