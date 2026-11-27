package polaris.android

import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import polaris.android.app.AppGraph

/**
 * Go on a device, with the debug build's recorded answers: Go on a route starts the trip and
 * shows its page; the activity made anew (as a rotation or a fold makes it) comes back to the same
 * trip; End stops it, deletes what a relaunch would pick up, and the route's Go is back. Location
 * and notifications are granted first so no system prompt covers the page. Run it only with
 * ANDROID_SERIAL set to an emulator.
 */
@RunWith(AndroidJUnit4::class)
class GoDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun grant(permission: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} $permission").close()
    }

    @Test fun goSurvivesTheActivityBeingMadeAnewAndEndStopsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        grant(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 33) grant(android.Manifest.permission.POST_NOTIFICATIONS)
        java.io.File(context.noBackupFilesDir, "trip.json").delete()
        val intent = Intent(context, MainActivity::class.java).putExtra("demo", "routes").putExtra("trip", true)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Best route", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithContentDescription("Best route", substring = true).onFirst().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Go")).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasText("Go")).onFirst().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("End")).fetchSemanticsNodes().isNotEmpty() }
            val trip = AppGraph.cached()?.trips?.trip?.value
            check(trip != null && trip.isRunning) { "Go didn't start a trip" }
            compose.waitUntil(10_000) { java.io.File(context.noBackupFilesDir, "trip.json").exists() }

            scenario.recreate()
            compose.waitUntil(15_000) { compose.onAllNodes(hasText("End")).fetchSemanticsNodes().isNotEmpty() }
            check(AppGraph.cached()?.trips?.trip?.value === trip) { "a new activity started another trip" }

            compose.onAllNodes(hasText("End")).onFirst().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Go")).fetchSemanticsNodes().isNotEmpty() }
            check(AppGraph.cached()?.trips?.trip?.value == null) { "End left the trip running" }
            compose.waitUntil(10_000) { !java.io.File(context.noBackupFilesDir, "trip.json").exists() }
        }
    }

    /** The trip's notification is up while Go runs, and End tapped on it ends the trip: the service, the notification and the saved file go. */
    @Test fun endOnTheNotificationEndsTheTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        grant(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 33) grant(android.Manifest.permission.POST_NOTIFICATIONS)
        java.io.File(context.noBackupFilesDir, "trip.json").delete()
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val activities = context.getSystemService(android.app.ActivityManager::class.java)
        @Suppress("DEPRECATION")
        fun serviceRunning() = activities.getRunningServices(50).any { it.service.className == polaris.android.trip.TripService::class.java.name }
        fun tripNotification() = manager.activeNotifications.firstOrNull { it.id == polaris.android.trip.TripNotifier.ID_TRIP }
        val intent = Intent(context, MainActivity::class.java).putExtra("demo", "routes").putExtra("trip", true)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Best route", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithContentDescription("Best route", substring = true).onFirst().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Go")).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasText("Go")).onFirst().performClick()
            compose.waitUntil(15_000) { tripNotification() != null && serviceRunning() }
            val end = tripNotification()!!.notification.actions.first { it.title.toString() == "End" }
            end.actionIntent.send()
            compose.waitUntil(15_000) { tripNotification() == null && !serviceRunning() && AppGraph.cached()?.trips?.trip?.value == null }
            compose.waitUntil(10_000) { !java.io.File(context.noBackupFilesDir, "trip.json").exists() }
        }
    }
}
