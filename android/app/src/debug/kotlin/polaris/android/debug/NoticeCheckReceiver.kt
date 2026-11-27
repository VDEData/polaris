package polaris.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import polaris.android.alerts.LineNotices

/**
 * Debug builds only: shows a line alert's notice as a push would, from extras, through the same
 * taking-in a real message goes through ([LineNotices.receive]).
 * Nothing is sent or received, so it proves how a notice looks and replaces, not that one arrives.
 *
 *     adb shell am broadcast -n <app id>/polaris.android.debug.NoticeCheckReceiver \
 *         -e alert A -e title "Home to work" -e subtitle "Jubilee line: severe delays" -e body "…" -e level urgent
 *
 * Extras are the message's data keys: alert, title, subtitle, body, level, tag, sent, test. The alert
 * must be one saved on the phone (in an alerts check, `DEMO-COMMUTE` or `DEMO-WEEKEND`, with the app running).
 */
class NoticeCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val data = listOf("alert", "title", "subtitle", "body", "level", "tag", "sent", "test").mapNotNull { key -> intent.getStringExtra(key)?.let { key to it } }.toMap()
        // As a message is taken in: read, dropped if its alert isn't saved on this phone, then drawn.
        val done = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            try {
                val result = LineNotices.receive(context.applicationContext, data)
                Log.i("PolarisDemo", "notice check: $result tag=${data["tag"] ?: data["alert"]} level=${data["level"]}")
            } finally {
                done.finish()
            }
        }
    }
}
