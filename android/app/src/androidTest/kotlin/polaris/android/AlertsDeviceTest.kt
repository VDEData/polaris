package polaris.android

import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import polaris.android.alerts.LineNotice
import polaris.android.alerts.LineNotices

/**
 * Line alerts on a device: what a notice does in the shade (its channel, a later one replacing
 * the earlier under the alert's tag, an older one refused), and that a notice tapped opens Alerts.
 * The notice is handed to the app's own drawing as a message's data would be; nothing is sent or
 * received, so this doesn't show that a push arrives. Run it only with ANDROID_SERIAL set to an emulator.
 */
@RunWith(AndroidJUnit4::class)
class AlertsDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    /** These tests change the app's permissions and post to its shade: never on a real phone. */
    @Before fun onlyOnAnEmulator() {
        val emulator = android.os.Build.HARDWARE in setOf("ranchu", "goldfish") ||
            shell("getprop ro.kernel.qemu").trim() == "1" || shell("getprop ro.boot.qemu").trim() == "1"
        check(emulator) { "AlertsDeviceTest runs only on an emulator; this device is not one" }
        if (android.os.Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        manager.cancelAll()
        context.getSharedPreferences("polaris-notices", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
    }

    private fun notice(tag: String, level: String, sent: Long, subtitle: String? = "Jubilee line: severe delays", body: String = "Severe delays.") = LineNotice.parse(
        buildMap {
            put("alert", "TEST-ALERT"); put("tag", tag); put("title", "Home to work"); put("body", body); put("level", level); put("sent", sent.toString())
            subtitle?.let { put("subtitle", it) }
        },
        System.currentTimeMillis(),
    )!!

    private fun shown(tag: String) = manager.activeNotifications.filter { it.id == LineNotices.ID && it.tag == tag }

    @Test fun aLaterNoticeReplacesTheEarlierOneOnItsOwnChannelAndAnOlderOneIsRefused() {
        val now = System.currentTimeMillis()
        check(LineNotices.show(context, notice("TEST-ALERT", "urgent", now - 60_000)))
        compose.waitUntil(5_000) { shown("TEST-ALERT").size == 1 }
        check(shown("TEST-ALERT").single().notification.channelId == LineNotices.CHANNEL_URGENT)
        check(manager.getNotificationChannel(LineNotices.CHANNEL_URGENT).importance == NotificationManager.IMPORTANCE_HIGH)
        check(manager.getNotificationChannel(LineNotices.CHANNEL_NORMAL).importance == NotificationManager.IMPORTANCE_DEFAULT)
        check(manager.getNotificationChannel(LineNotices.CHANNEL_QUIET).importance == NotificationManager.IMPORTANCE_LOW) { "the quiet channel must not sound or pop up" }

        // "It has ended", quietly: the same tag, so it takes the closure's place, on the silent channel.
        check(LineNotices.show(context, notice("TEST-ALERT", "quiet", now - 30_000, subtitle = null, body = "Severe delays on the Jubilee line have cleared.")))
        compose.waitUntil(5_000) { shown("TEST-ALERT").singleOrNull()?.notification?.channelId == LineNotices.CHANNEL_QUIET }
        check(shown("TEST-ALERT").size == 1) { "one notice per alert, not two" }
        val text = shown("TEST-ALERT").single().notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        check(text == "Severe delays on the Jubilee line have cleared.") { text }

        // One sent before it, delivered late: refused, and what is showing stays.
        check(!LineNotices.show(context, notice("TEST-ALERT", "urgent", now - 45_000, subtitle = "OLD NEWS")))
        check(shown("TEST-ALERT").single().notification.channelId == LineNotices.CHANNEL_QUIET)

        // Planned works have their own tag: beside it, not over it.
        check(LineNotices.show(context, notice("TEST-ALERT-planned", "normal", now, subtitle = "Planned: part closure tomorrow")))
        compose.waitUntil(5_000) { shown("TEST-ALERT-planned").size == 1 }
        check(shown("TEST-ALERT").size == 1)
        check(shown("TEST-ALERT-planned").single().notification.channelId == LineNotices.CHANNEL_NORMAL)
        manager.cancelAll()
    }

    /** What a tap on a notice sends: Polaris opens on Alerts. */
    @Test fun aNoticeTappedOpensAlerts() {
        val intent = Intent(context, MainActivity::class.java).putExtra("alerts", "lines").putExtra(LineNotices.EXTRA_OPEN_ALERTS, true)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(20_000) { compose.onAllNodes(hasText("Your lines")).fetchSemanticsNodes().isNotEmpty() && compose.onAllNodes(hasText("Send a test notification")).fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
