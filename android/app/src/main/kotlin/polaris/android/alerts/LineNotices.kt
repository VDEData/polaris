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
    /** The group summary's own tap; each notice has its own (by its tag), so one never takes another's alert. */
    private const val REQUEST_SUMMARY = 30
    private const val LEDGER = "polaris-notices"
    private const val KEEP_MILLIS = 24 * 3600_000L
    /** Beside each tag's send time: when this phone took that notice in. */
    private const val TAKEN = "@taken"

    /** Forgets when each tag's newest notice was sent (the alerts server was changed for another). */
    fun forgetOrder(context: Context) = context.getSharedPreferences(LEDGER, Context.MODE_PRIVATE).edit().clear().apply()

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
        val now = System.currentTimeMillis()
        // A record counts for a day from when this phone took it in, by this phone's clock either way round:
        // a server whose clock was once ahead and then put right can't keep a tag's later notices hidden for longer.
        fun live(tag: String) = kotlin.math.abs(now - ledger.getLong(tag + TAKEN, 0L)) < KEEP_MILLIS
        val last = ledger.getLong(notice.tag, 0L).takeIf { it > 0 && live(notice.tag) }
        if (!LineNotice.replaces(notice, last)) return false
        channels(context)
        val manager = NotificationManagerCompat.from(context)
        val posted = runCatching { manager.notify(notice.tag, ID, build(context, notice)) }.isSuccess
        if (!posted) return false
        // A group of their own with a summary, so Android doesn't bundle them with a trip's notification.
        runCatching { manager.notify(ID_SUMMARY, summary(context)) }
        ledger.edit().apply {
            putLong(notice.tag, notice.sent)
            putLong(notice.tag + TAKEN, now)
            ledger.all.keys.filterNot { it.endsWith(TAKEN) }.forEach { tag -> if (tag != notice.tag && !live(tag)) { remove(tag); remove(tag + TAKEN) } }
        }.apply()
        return true
    }

    /**
     * What happened comes first, as the title, so a collapsed notice or a group's line says it
     * ("Canary Wharf closed (Elizabeth line)"), with the title's line to itself (a header beside
     * it squeezes it short). TfL's own words are the text, and the alert it is for closes the
     * expanded notice. A test says it is one, before its words and under them: when TfL has
     * trouble on the alert, a test carries that trouble's own words.
     */
    fun build(context: Context, notice: LineNotice): android.app.Notification {
        val headline = if (notice.isTest) context.getString(R.string.alerts_notice_test_headline, notice.headline) else notice.headline
        val detail = notice.detail
        val expanded = listOfNotNull(
            // The whole of what happened when there is nothing under it (the title is one line).
            if (detail == null) headline else detail,
            context.getString(R.string.alerts_notice_for, notice.title),
            if (notice.isTest) context.getString(R.string.alerts_notice_test_note) else null,
        ).joinToString("\n\n")
        return NotificationCompat.Builder(context, channel(notice.level))
            .setSmallIcon(R.drawable.sym_notifications)
            .setContentTitle(headline)
            .setContentText(detail ?: context.getString(R.string.alerts_notice_for, notice.title))
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setWhen(notice.shownAt(System.currentTimeMillis()))
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
                    .setContentTitle(context.getString(if (notice.isTest) R.string.alerts_notice_public_test else R.string.alerts_notice_public))
                    .setWhen(notice.shownAt(System.currentTimeMillis()))
                    .build(),
            )
            .setContentIntent(open(context, notice.alert, notice.tag.hashCode() or 0x40000000))
            .build()
    }

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
            .setContentIntent(open(context, null, REQUEST_SUMMARY))
            .build()

    /** Opens Polaris on Alerts. */
    private fun open(context: Context, alert: String?, request: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_ALERTS, true)
        alert?.let { intent.putExtra(EXTRA_ALERT_ID, it) }
        return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * A message's data, as it arrives: shown if it is a notice for an alert this phone still has.
     * One for an alert since deleted (the server not yet told, or its last push already on its
     * way) is dropped, so nothing is shown about a line the person no longer asked to hear of.
     * Returns what became of it.
     */
    suspend fun receive(context: Context, data: Map<String, String>): Received {
        val notice = LineNotice.parse(data, System.currentTimeMillis()) ?: return Received.NotANotice
        // Saved alerts that can't be read (storage in trouble) don't lose the person a notice: only a list that was read and lacks the alert drops it.
        val saved = runCatching { savedAlerts(context) }.getOrNull()
        if (saved != null && notice.alert !in saved) return Received.AlertGone
        return if (show(context, notice)) Received.Shown else Received.NotShown
    }

    enum class Received { Shown, NotShown, AlertGone, NotANotice }

    /** The ids of the alerts saved on this phone: the running app's, else read from where they are kept. */
    private suspend fun savedAlerts(context: Context): Set<String>? {
        polaris.android.app.AppGraph.cached()?.let { graph -> return graph.alerts.state.value.alerts.map { it.id }.toSet() }
        val saved = polaris.android.storage.Storage.preferences(context).getString(polaris.engine.AlertStore.ALERTS)
        return polaris.engine.RouteAlert.decodeListOrNull(saved)?.map { it.id }?.toSet()
    }

    /** Whether a channel the person may have turned off still shows its notices. */
    fun channelOn(context: Context, id: String): Boolean {
        val channel = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(id) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }
}
