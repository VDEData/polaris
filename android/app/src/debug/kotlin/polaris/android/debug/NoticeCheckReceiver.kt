package polaris.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import polaris.android.alerts.LineNotice
import polaris.android.alerts.LineNotices

/**
 * Debug builds only: shows a line alert's notice as a push would, from extras, through the same
 * reading and drawing a real message goes through ([LineNotice.parse], [LineNotices.show]).
 * Nothing is sent or received, so it proves how a notice looks and replaces, not that one arrives.
 *
 *     adb shell am broadcast -n <app id>/polaris.android.debug.NoticeCheckReceiver \
 *         -e alert A -e title "Home to work" -e subtitle "Jubilee line: severe delays" -e body "…" -e level urgent
 *
 * Extras are the message's data keys: alert, title, subtitle, body, level, tag, sent.
 */
class NoticeCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val data = listOf("alert", "title", "subtitle", "body", "level", "tag", "sent").mapNotNull { key -> intent.getStringExtra(key)?.let { key to it } }.toMap()
        val notice = LineNotice.parse(data, System.currentTimeMillis())
        val shown = notice != null && LineNotices.show(context.applicationContext, notice)
        Log.i("PolarisDemo", "notice check: read=${notice != null} shown=$shown level=${notice?.level} tag=${notice?.tag}")
    }
}
