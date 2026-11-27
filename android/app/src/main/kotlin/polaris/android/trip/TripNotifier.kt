package polaris.android.trip

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import polaris.android.MainActivity
import polaris.android.R
import polaris.android.go.GoWords
import polaris.android.ui.toColor
import polaris.engine.JourneyMap
import polaris.engine.LiveTrip
import polaris.engine.ReplanMessage
import polaris.engine.TripAlert
import polaris.engine.TripAlertScheduling
import polaris.engine.TripCard
import polaris.engine.TripPresenter
import java.time.Instant

/**
 * What replaces iOS's Live Activity and its trip notifications on Android:
 * - the trip as one ongoing notification ("Your trip" channel: default importance, so it shows on
 *   the lock screen, never with a sound). The title is the next action and its time, the header a
 *   labelled countdown that ticks by itself, and the expanded text the arrival, what missing the
 *   next ride costs and a faster way. A faster way offers Switch and Keep my route; otherwise End.
 *   On a phone with a secure lock it shows only "Your trip", the arrival and the countdown.
 *   Android 16's Live Update (promotion, progress style) is not used: it isn't proven on a phone.
 * - a foreground service of the location type while a trip runs and location is allowed, so Go
 *   keeps time and fixes with the app in the background (it shows the same notification);
 * - the timed nudges (get off next, time to leave) as inexact alarms, and a faster way found while
 *   Polaris isn't in front, on the "Trip alerts" channel.
 * Without the notification permission nothing here is shown and Go carries on in the app.
 */
class TripNotifier(
    private val context: Context,
    private val trip: () -> LiveTrip?,
    private val clock: () -> Instant,
) : TripPresenter, TripAlertScheduling {
    private val words get() = GoWords(context.resources)
    private val manager get() = NotificationManagerCompat.from(context)
    private var shown: TripCard? = null
    private var destination = ""
    private var running = false

    val allowed: Boolean
        get() = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channels() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        system.createNotificationChannel(
            // Default importance so it shows on the lock screen (Android hides low-importance ones
            // there), with no sound or vibration of its own: every post is silent and alerts once.
            NotificationChannel(CHANNEL_TRIP, context.getString(R.string.go_channel_trip), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.go_channel_trip_about)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.go_channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.go_channel_alerts_about)
            },
        )
    }

    override fun start(destination: String, card: TripCard, adopt: Boolean) {
        this.destination = destination
        running = true
        shown = null
        update(card)
        // Allowed only now (asked on this Go tap): the nudges planned before are set.
        nudges.apply()
        startService()
    }

    /** The location-type service, only once precise location is allowed (Android refuses it without location), started while the app is in front. */
    fun startService() {
        if (!running || !allowed) return
        // Approximate location alone can't follow the trip, so no location service runs for it.
        if (!hasPrecise(context) || !inFront()) return
        runCatching { ContextCompat.startForegroundService(context, Intent(context, TripService::class.java)) }
    }

    override fun update(card: TripCard) {
        if (!running || !TripNoticePlan.changed(shown, card)) return
        shown = card
        post(card)
    }

    /** Swiped away (Android 14 and later let a foreground service's notification go): it comes back while the trip runs. */
    fun repost() {
        val card = shown ?: return
        if (running) post(card)
    }

    private fun post(card: TripCard) {
        if (!allowed) return
        channels()
        val notification = build(card)
        TripService.current = notification
        runCatching { manager.notify(ID_TRIP, notification) }
    }

    override fun end() {
        running = false
        shown = null
        TripService.current = null
        manager.cancel(ID_TRIP)
        manager.cancel(ID_OFFER)
        context.stopService(Intent(context, TripService::class.java))
    }

    /**
     * The timed nudges as inexact alarms that may fire while the phone sleeps
     * (`setAndAllowWhileIdle`, no exact-alarm permission); Android may hold one by a few minutes in
     * deep sleep (Doze). [NudgeKeeper] decides which to set, cancel or post now.
     */
    private val nudges = NudgeKeeper(object : NudgePort {
        override val allowed get() = this@TripNotifier.allowed
        override fun setAlarm(alert: TripAlert) {
            val (title, body) = words.alert(alert)
            // The alarm clock runs on the phone's time; the trip's clock may run ahead in a debug check.
            val due = System.currentTimeMillis() + (alert.date.toEpochMilli() - clock().toEpochMilli())
            armNudge(context, alert.id, title, body, due)
        }
        override fun cancelAlarm(id: String) = cancelAlarm(context, id)
        override fun post(alert: TripAlert) {
            channels()
            val (title, body) = words.alert(alert)
            postAlert(context, alert.id, title, body)
        }
        override fun isShowing(id: String) = runCatching {
            context.getSystemService(NotificationManager::class.java)?.activeNotifications?.any { it.tag == id && it.id == ID_ALERT } == true
        }.getOrDefault(false)
    }, clock)

    override fun schedule(alerts: List<TripAlert>) = nudges.schedule(alerts)

    /** A nudge's alarm fired in this process: shown unless it already was. */
    fun alarmFired(id: String): Boolean {
        if (nudges.wasShown(id)) return false
        nudges.shown(id)
        return true
    }

    override fun cancel() {
        nudges.cancelAll()
        polaris.engine.TripAlerts.allIds.forEach { manager.cancel(it, ID_ALERT) }
    }

    override fun offer(message: ReplanMessage?) {
        manager.cancel(ID_OFFER)
        // With Polaris in front the offer is already the card on Go's page: no banner over it.
        if (message == null || !allowed || inFront()) return
        channels()
        val said = words.said(message)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.go_arrow)
            .setContentTitle(said.title)
            .setContentText(said.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(said.detail, said.note).joinToString("\n")))
            .setContentIntent(open(REQUEST_OPEN, null))
            .addAction(0, context.getString(R.string.go_switch), open(REQUEST_SWITCH, ACTION_SWITCH))
            .addAction(0, context.getString(R.string.go_keep), broadcast(ACTION_KEEP))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(null))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        runCatching { manager.notify(ID_OFFER, notification) }
    }

    /** What a locked phone shows: "Your trip", and the arrival with its countdown when there is a card. Never a stop or the destination. */
    private fun publicVersion(card: TripCard?): android.app.Notification {
        val b = NotificationCompat.Builder(context, CHANNEL_TRIP)
            .setSmallIcon(R.drawable.go_arrow)
            .setContentTitle(context.getString(R.string.go_channel_trip))
            .setSilent(true)
        if (card != null) {
            b.setContentText(context.getString(R.string.go_arrive, words.clock(card.arrival)))
                .setWhen(card.arrival.toEpochMilli()).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                // The countdown is to the arrival, so it says so, as the full one labels its own.
                .setSubText(context.getString(R.string.go_card_arrives_in))
                .setOngoing(true)
        }
        return b.build()
    }

    /**
     * The ongoing notification for [card]: the next action and its time as the title (short, so
     * it isn't cut), where from or which train under it, a countdown labelled in the header
     * ("Leaves in 06:12"), and when expanded the arrival, what missing it costs and a faster way
     * on lines of their own. A faster way offers Switch and Keep my route; otherwise End.
     */
    fun build(card: TripCard): android.app.Notification {
        val w = words
        val (title, detail) = cardText(card, w)
        val plan = TripNoticePlan.of(card, clock())
        val miss = card.miss?.let { missText(it, w) }
        val offer = card.replan?.let { w.said(it).short }
        val arrive = if (card.countdown != TripCard.Countdown.arrive) context.getString(R.string.go_arrive, w.clock(card.arrival)) else null
        val label = context.getString(
            when (card.countdown) {
                TripCard.Countdown.leaves -> R.string.go_card_leaves_in
                TripCard.Countdown.getOff -> R.string.go_card_get_off_in
                TripCard.Countdown.arrive -> R.string.go_card_arrives_in
            },
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_TRIP)
            .setSmallIcon(R.drawable.go_arrow)
            .setContentTitle(title)
            .setContentText(detail)
            .setSubText(label)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(detail, arrive, miss, offer).joinToString("\n")))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(card))
            .setContentIntent(open(REQUEST_OPEN, null))
            .setDeleteIntent(broadcast(ACTION_DISMISSED))
            // The countdown to what matters now, ticking by itself.
            .setWhen(card.countdownTo.toEpochMilli())
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            // With no service to clear it (the process gone), it goes by itself when the trip would be stale.
            .setTimeoutAfter(plan.timeoutMillis)
        card.lineColor?.let { builder.setColor(it.toColor().let { c -> android.graphics.Color.argb(c.alpha, c.red, c.green, c.blue) }).setColorized(false) }
        if (plan.offerActions) {
            builder.addAction(0, context.getString(R.string.go_switch), open(REQUEST_SWITCH, ACTION_SWITCH))
            builder.addAction(0, context.getString(R.string.go_keep), broadcast(ACTION_KEEP))
        }
        if (plan.endAction) builder.addAction(0, context.getString(R.string.go_end), broadcast(ACTION_END))
        return builder.build()
    }

    private fun cardText(card: TripCard, w: GoWords): Pair<String, String> {
        val line = card.line?.let(w::line) ?: ""
        fun platform(raw: String?, bus: Boolean) = raw?.let { context.getString(if (bus) R.string.go_stop else R.string.go_platform, it) }
        return when (val step = card.step) {
            is TripCard.Step.Waiting -> {
                val where = platform(step.platform, step.bus)
                val title = if (where != null) context.getString(R.string.go_card_leaves_platform, line, w.clock(card.countdownTo), where)
                else context.getString(R.string.go_card_leaves, line, w.clock(card.countdownTo))
                title to (step.headsign?.let { context.getString(R.string.go_card_towards, it) } ?: context.getString(R.string.go_card_from, step.from))
            }
            is TripCard.Step.Riding -> context.getString(R.string.go_card_get_off, step.to, w.clock(card.countdownTo)) to when {
                step.yours != null -> context.getString(R.string.go_card_on_the, w.clock(step.yours!!), line)
                step.stops != null -> context.resources.getQuantityString(R.plurals.go_card_stops, step.stops!!, step.stops.toString())
                else -> context.getString(R.string.go_get_off_at, step.to)
            }
            is TripCard.Step.WalkToRide -> {
                val where = platform(step.platform, step.bus)
                val title = if (where != null) context.getString(R.string.go_card_catch_platform, line, w.clock(card.countdownTo), where)
                else context.getString(R.string.go_card_catch, line, w.clock(card.countdownTo))
                title to context.getString(R.string.go_walk_to, step.stop)
            }
            is TripCard.Step.WalkToEnd -> context.getString(R.string.go_walk_to, step.destination) to
                context.getString(R.string.go_card_walk_left, step.metres?.let(w::distance) ?: w.minutes(step.minutes), w.clock(card.arrival))
        }
    }

    private fun missText(miss: TripCard.Miss, w: GoWords): String {
        val next = miss.nextLine?.let { context.getString(R.string.go_time_line, w.line(it), w.clock(miss.nextDeparture)) } ?: w.clock(miss.nextDeparture)
        val cost = miss.cost?.let { m ->
            when {
                miss.costIsFloor -> context.getString(R.string.go_card_cost_floor, maxOf(m, 1).toString())
                m == 0 -> context.getString(R.string.go_card_cost_none)
                m > 0 -> context.getString(R.string.go_card_cost_later, m.toString())
                else -> context.getString(R.string.go_card_cost_sooner, (-m).toString())
            }
        }
        val connection = miss.connection?.let { (at, line) -> context.getString(R.string.go_time_line, w.clock(at), w.line(line)) }
        return when {
            connection != null && miss.losesConnection -> context.getString(R.string.go_card_miss_loses, next, connection)
            connection != null -> context.getString(R.string.go_card_miss_makes, next, connection)
            cost != null -> context.getString(R.string.go_card_miss_cost, next, cost)
            else -> context.getString(R.string.go_card_miss, next)
        }
    }

    private fun inFront(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun open(request: Int, action: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_GO, true)
        // Switch changes the trip, and the activity is exported: only a tap carrying this process's
        // own token counts (another app's intent, or a notification from before a relaunch, just opens Go).
        action?.let { intent.putExtra(EXTRA_ACTION, it).putExtra(EXTRA_TOKEN, TOKEN) }
        return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun broadcast(action: String): PendingIntent =
        PendingIntent.getBroadcast(context, action.hashCode(), Intent(context, TripActionReceiver::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val CHANNEL_TRIP = "go-trip"
        const val CHANNEL_ALERTS = "trip-alerts"
        const val ID_TRIP = 1
        const val ID_OFFER = 2
        const val ID_ALERT = 3
        private const val REQUEST_OPEN = 10
        private const val REQUEST_SWITCH = 11
        const val EXTRA_OPEN_GO = "polaris.go"
        const val EXTRA_ACTION = "polaris.go.action"
        const val ACTION_SWITCH = "polaris.go.SWITCH"
        const val ACTION_KEEP = "polaris.go.KEEP"
        const val ACTION_END = "polaris.go.END"
        const val ACTION_DISMISSED = "polaris.go.DISMISSED"
        const val ACTION_ALERT = "polaris.go.ALERT"
        const val EXTRA_ALERT_ID = "polaris.go.alert"
        const val EXTRA_TITLE = "polaris.go.title"
        const val EXTRA_BODY = "polaris.go.body"
        const val EXTRA_TOKEN = "polaris.go.token"
        /** Made once per process, never saved or logged. */
        val TOKEN: String = java.util.UUID.randomUUID().toString()

        /** A timed nudge, posted by its alarm on the "Trip alerts" channel. */
        fun postAlert(context: Context, id: String, title: String, body: String) {
            val allowed = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!allowed) return
            val open = PendingIntent.getActivity(
                context, REQUEST_OPEN,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_OPEN_GO, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.go_arrow)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(id, ID_ALERT, notification) }
        }

        const val EXTRA_DUE = "polaris.go.due"

        /**
         * A nudge's alarm, asked early enough that Android's window for an inexact alarm ends at
         * [dueMillis] (the phone's clock); delivered early, the receiver asks again for what's left.
         */
        fun armNudge(context: Context, id: String, title: String, body: String, dueMillis: Long) {
            val alarms = context.getSystemService(android.app.AlarmManager::class.java) ?: return
            val now = System.currentTimeMillis()
            val ask = TripNoticePlan.alarmAsk(Instant.ofEpochMilli(now), Instant.ofEpochMilli(dueMillis))?.toEpochMilli() ?: dueMillis
            val intent = Intent(context, TripAlarmReceiver::class.java).setAction(ACTION_ALERT)
                .putExtra(EXTRA_ALERT_ID, id).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_BODY, body).putExtra(EXTRA_DUE, dueMillis)
            val pending = PendingIntent.getBroadcast(context, TripNoticePlan.alarmRequest(id), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            runCatching { alarms.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, maxOf(ask, now + 1_000), pending) }
        }

        fun cancelAlarm(context: Context, id: String) {
            val alarms = context.getSystemService(android.app.AlarmManager::class.java) ?: return
            val intent = Intent(context, TripAlarmReceiver::class.java).setAction(ACTION_ALERT)
            PendingIntent.getBroadcast(context, TripNoticePlan.alarmRequest(id), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                ?.let { alarms.cancel(it); it.cancel() }
        }

        /** A launch with no trip to pick up, or End with the process gone: whatever the last trip left in the shade, and its alarms, go. */
        fun removeLeftovers(context: Context) {
            polaris.engine.TripAlerts.allIds.forEach { cancelAlarm(context, it) }
            TripEndAlarm.set(context, null, Instant::now)
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(ID_TRIP)
            manager.cancel(ID_OFFER)
            polaris.engine.TripAlerts.allIds.forEach { manager.cancel(it, ID_ALERT) }
        }
    }
}
