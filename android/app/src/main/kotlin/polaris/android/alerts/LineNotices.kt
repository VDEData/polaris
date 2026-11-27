package polaris.android.alerts

import android.Manifest
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

/**
 * Line alerts in the shade: what the alerts server sent, on one of three channels, with a later
 * notice for an alert replacing the earlier one (the same tag), as APNs' collapse ID does on iOS.
 * - "Closures and delays" (high importance: it pops up, and can be let through Do Not Disturb in
 *   the phone's settings), for what iOS sends Time Sensitive;
 * - "Minor delays and planned works" (default importance, with a sound), which also carries "it
 *   has ended";
 * - "Quiet updates" (low importance: no sound, no pop-up), for what iOS sends as passive.
 * Tapping one opens Alerts. Without the notification permission nothing is shown.
 */
object LineNotices {
    const val CHANNEL_URGENT = "line-alerts-urgent"
    const val CHANNEL_NORMAL = "line-alerts"
    const val CHANNEL_QUIET = "line-alerts-quiet"
    const val GROUP = "polaris.lines"
    /** One id for all of them: the tag tells them apart, and replaces. */
    const val ID = 20
    const val ID_SUMMARY = 21
    const val EXTRA_OPEN_ALERTS = "polaris.alerts"
    const val EXTRA_ALERT_ID = "polaris.alerts.id"
    private const val REQUEST_OPEN = 30
    private const val LEDGER = "polaris-notices"
    private const val KEEP_MILLIS = 24 * 3600_000L

    fun allowed(context: Context): Boolean = (
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun channel(level: LineNotice.Level): String = when (level) {
        LineNotice.Level.urgent -> CHANNEL_URGENT
        LineNotice.Level.normal -> CHANNEL_NORMAL
        LineNotice.Level.quiet -> CHANNEL_QUIET
    }

    /** Made when alerts are first used, and before any notice is posted: they show in the phone's settings from then. */
    fun channels(context: Context) {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        fun make(id: String, name: Int, about: Int, importance: Int) =
            system.createNotificationChannel(NotificationChannel(id, context.getString(name), importance).apply { description = context.getString(about) })
        make(CHANNEL_URGENT, R.string.alerts_channel_urgent, R.string.alerts_channel_urgent_about, NotificationManager.IMPORTANCE_HIGH)
        make(CHANNEL_NORMAL, R.string.alerts_channel_normal, R.string.alerts_channel_normal_about, NotificationManager.IMPORTANCE_DEFAULT)
        make(CHANNEL_QUIET, R.string.alerts_channel_quiet, R.string.alerts_channel_quiet_about, NotificationManager.IMPORTANCE_LOW)
    }

    /**
     * Shows [notice], replacing what its tag showed, unless it was sent before that one. True if
     * shown. When each tag's newest was sent is kept (a day) in a small private file: times and
     * the server's own tags, nothing of what a notice said.
     */
    fun show(context: Context, notice: LineNotice): Boolean {
        if (!allowed(context)) return false
        val ledger = context.getSharedPreferences(LEDGER, Context.MODE_PRIVATE)
        val last = ledger.getLong(notice.tag, 0L).takeIf { it > 0 }
        if (!LineNotice.replaces(notice, last)) return false
        channels(context)
        val manager = NotificationManagerCompat.from(context)
        val posted = runCatching { manager.notify(notice.tag, ID, build(context, notice)) }.isSuccess
        if (!posted) return false
        // A group of their own with a summary, so Android doesn't bundle them with a trip's notification.
        runCatching { manager.notify(ID_SUMMARY, summary(context)) }
        ledger.edit().apply {
            putLong(notice.tag, notice.sent)
            ledger.all.forEach { (tag, at) -> if (tag != notice.tag && (at as? Long ?: 0L) < notice.sent - KEEP_MILLIS) remove(tag) }
        }.apply()
        return true
    }

    fun build(context: Context, notice: LineNotice): android.app.Notification =
        NotificationCompat.Builder(context, channel(notice.level))
            .setSmallIcon(R.drawable.sym_notifications)
            .setContentTitle(notice.title)
            .setContentText(notice.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.full))
            .setWhen(notice.sent)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setGroup(GROUP)
            // A quiet one says so itself too, for phones before channels decided it.
            .setSilent(notice.level == LineNotice.Level.quiet)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // A locked phone that hides content shows that a line alert came, not which lines you take.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channel(notice.level))
                    .setSmallIcon(R.drawable.sym_notifications)
                    .setContentTitle(context.getString(R.string.alerts_notice_public))
                    .setWhen(notice.sent)
                    .build(),
            )
            .setContentIntent(open(context, notice.alert))
            .build()

    private fun summary(context: Context): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_QUIET)
            .setSmallIcon(R.drawable.sym_notifications)
            .setContentTitle(context.getString(R.string.alerts_title))
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSilent(true)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open(context, null))
            .build()

    /** Opens Polaris on Alerts. */
    private fun open(context: Context, alert: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_ALERTS, true)
        alert?.let { intent.putExtra(EXTRA_ALERT_ID, it) }
        return PendingIntent.getActivity(context, REQUEST_OPEN, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Whether a channel the person may have turned off still shows its notices. */
    fun channelOn(context: Context, id: String): Boolean {
        val channel = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(id) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }
}
