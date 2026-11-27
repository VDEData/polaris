package polaris.android.trip

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import polaris.android.R
import polaris.android.app.AppGraph
import polaris.engine.FileTripStore
import polaris.engine.TripStore
import java.io.File
import java.time.Instant

/**
 * Keeps a live trip going with Polaris in the background: a foreground service of the location
 * type, showing the trip's own ongoing notification. It does no work of its own; the trip runs in
 * the app's process ([TripSession]) and this keeps that process and its location updates alive.
 * Not restarted by Android if it is stopped: the saved trip brings Go back at the next launch.
 */
class TripService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires startForeground after startForegroundService, even when there is
        // nothing to show (the trip ended in between): a minimal notification, then stop.
        val notification = current ?: minimal(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        val started = runCatching { ServiceCompat.startForeground(this, TripNotifier.ID_TRIP, notification, type) }.isSuccess
        if (!started || current == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        /** The trip's notification as last built, for the service to show. */
        @Volatile var current: Notification? = null

        fun minimal(context: Context): Notification = NotificationCompat.Builder(context, TripNotifier.CHANNEL_TRIP)
            .setSmallIcon(R.drawable.go_arrow)
            .setContentTitle(context.getString(R.string.go_channel_trip))
            .setSilent(true)
            .build()
    }
}

/** One store for the receivers when the app's process had none: its writer thread is made once. */
internal object ReceiverStore {
    @Volatile private var store: FileTripStore? = null
    fun of(context: Context): TripStore =
        (AppGraph.cached()?.tripStore) ?: synchronized(this) {
            store ?: FileTripStore(File(context.applicationContext.noBackupFilesDir, "trip.json")).also { store = it }
        }
}

/**
 * Keep my route, End and a swipe-away, from the shade. With the trip running they go to it; with
 * the app's process gone (no service: location wasn't allowed) they act on the saved trip itself,
 * as iOS holds the tap until the trip is back: End deletes it (and a trip found at launch but not
 * yet resumed is dropped too, so End is final), Keep declines the offer's reason. Work on the file
 * runs off the main thread ([goAsync]).
 */
class TripActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = AppGraph.cached()
        val trips = graph?.trips
        val trip = trips?.trip?.value
        when (intent.action) {
            TripNotifier.ACTION_DISMISSED -> graph?.notifier?.repost()
            TripNotifier.ACTION_END -> {
                trips?.dropPending()
                if (trip != null) trips.end() else onFile(context) { store ->
                    store.clear()
                    TripNotifier.removeLeftovers(context)
                }
            }
            TripNotifier.ACTION_KEEP -> {
                NotificationManagerCompat.from(context).cancel(TripNotifier.ID_OFFER)
                if (trip != null) trip.keepRoute() else onFile(context) { store ->
                    store.load()?.let { saved ->
                        val key = saved.offerKey
                        if (key != null && saved.isResumable(Instant.now())) store.save(saved.copy(replanWatch = saved.replanWatch.declined(key), offerKey = null))
                    }
                }
            }
        }
    }

    private fun onFile(context: Context, work: (TripStore) -> Unit) {
        val done = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = ReceiverStore.of(context)
                work(store)
                (store as? FileTripStore)?.flush()
            } finally {
                done.finish()
            }
        }
    }
}

/**
 * A timed nudge's alarm: posts it while its trip runs. In this process it asks the trip's notifier
 * (which may have posted it already); with the process gone it reads the saved trip, so a trip
 * ended from the shade, or gone stale, gets nothing.
 */
class TripAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TripNotifier.ACTION_ALERT) return
        val id = intent.getStringExtra(TripNotifier.EXTRA_ALERT_ID) ?: return
        val title = intent.getStringExtra(TripNotifier.EXTRA_TITLE) ?: return
        val body = intent.getStringExtra(TripNotifier.EXTRA_BODY) ?: ""
        val due = intent.getLongExtra(TripNotifier.EXTRA_DUE, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli) ?: Instant.now()
        val graph = AppGraph.cached()
        val running = graph?.trips?.trip?.value != null
        val action = TripNoticePlan.onAlarm(
            Instant.now(), due,
            shownHere = running && graph?.notifier?.wasShown(id) == true,
            showing = TripNotifier.isShowing(context, id),
        )
        when (action) {
            TripNoticePlan.AlarmAction.Drop -> return
            // Come early (Android's window for it ends at the nudge): asked again for what's left.
            TripNoticePlan.AlarmAction.Rearm -> { TripNotifier.armNudge(context, id, title, body, due.toEpochMilli()); return }
            TripNoticePlan.AlarmAction.Post -> Unit
        }
        if (graph != null && (running || graph.trips.hasPending)) {
            if (!running || graph.notifier?.alarmFired(id) != false) TripNotifier.postAlert(context, id, title, body)
            return
        }
        // No trip in this process: only the saved file can say one is still on.
        val done = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val saved = ReceiverStore.of(context).load()
                if (saved != null && saved.isResumable(Instant.now())) TripNotifier.postAlert(context, id, title, body)
            } finally {
                done.finish()
            }
        }
    }
}

/**
 * An alarm at the time the trip ends by itself if nothing more is heard ([TripSession.cutoff]), so
 * it ends with the phone asleep and no fixes coming (the two-second ticks don't run then). Inexact,
 * so asked early, as the nudges are: Android's window for it ends at the cut-off, and it is asked
 * again for what's left when it comes early.
 */
object TripEndAlarm {
    private const val REQUEST = 900
    const val ACTION = "polaris.go.CUTOFF"
    private const val EXTRA_DUE = "polaris.go.cutoff"

    fun set(context: Context, at: Instant?, clock: () -> Instant) {
        if (at == null) {
            val alarms = context.getSystemService(android.app.AlarmManager::class.java) ?: return
            android.app.PendingIntent.getBroadcast(context, REQUEST, Intent(context, TripEndReceiver::class.java).setAction(ACTION), android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE)
                ?.let { alarms.cancel(it); it.cancel() }
            return
        }
        // The alarm clock runs on the phone's time; the trip's clock may run ahead in a debug check.
        arm(context, System.currentTimeMillis() + (at.toEpochMilli() - clock().toEpochMilli()))
    }

    /** Asked so its window ends at [dueMillis] (the phone's clock). */
    fun arm(context: Context, dueMillis: Long) {
        val alarms = context.getSystemService(android.app.AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val ask = TripNoticePlan.alarmAsk(Instant.ofEpochMilli(now), Instant.ofEpochMilli(dueMillis))?.toEpochMilli() ?: dueMillis
        val intent = Intent(context, TripEndReceiver::class.java).setAction(ACTION).putExtra(EXTRA_DUE, dueMillis)
        val pending = android.app.PendingIntent.getBroadcast(context, REQUEST, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        runCatching { alarms.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, maxOf(ask, now + 1_000), pending) }
    }

    fun due(intent: Intent): Long = intent.getLongExtra(EXTRA_DUE, 0L)
}

/**
 * The cut-off alarm. Early: asked again for what's left. In this process, the running trip checks
 * itself. With the process gone, a saved trip past its cut-off is cleared with what it left; one
 * still live (the file's cut-off can be later than the one the session last used) gets the alarm
 * again for its own cut-off.
 */
class TripEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TripEndAlarm.ACTION) return
        val due = TripEndAlarm.due(intent)
        if (due > 0 && TripNoticePlan.alarmAsk(Instant.now(), Instant.ofEpochMilli(due)) != null) {
            TripEndAlarm.arm(context, due)
            return
        }
        val trips = AppGraph.cached()?.trips
        if (trips?.trip?.value != null) {
            trips.check()
            return
        }
        val done = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = ReceiverStore.of(context)
                val saved = store.load()
                val now = Instant.now()
                if (saved != null && saved.isResumable(now)) {
                    TripEndAlarm.arm(context, polaris.engine.SavedTrip.cutoff(saved.journey, saved.arrival).toEpochMilli())
                } else {
                    if (saved != null) store.clear()
                    (store as? FileTripStore)?.flush()
                    TripNotifier.removeLeftovers(context)
                    // The session is main-thread only.
                    android.os.Handler(android.os.Looper.getMainLooper()).post { trips?.dropPending() }
                }
            } finally {
                done.finish()
            }
        }
    }
}
