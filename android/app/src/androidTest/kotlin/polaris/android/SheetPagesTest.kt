package polaris.android

import android.content.Intent
import androidx.compose.ui.test.hasContentDescription
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

/**
 * The sheet's pages on a device, with the debug build's recorded answers (nothing is asked of TfL
 * or Transitous): a trip opens its routes, a route opens its page, and system back returns
 * through them to Home. Run it only with ANDROID_SERIAL set to an emulator.
 */
@RunWith(AndroidJUnit4::class)
class SheetPagesTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun backReturnsThroughTheStack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra("demo", "routes").putExtra("trip", true)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            // System back, as the gesture or the button sends it.
            val back = { scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() } }
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Best route", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithContentDescription("Best route", substring = true).onFirst().performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Journey details", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            back()
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Swap start and destination")).fetchSemanticsNodes().isNotEmpty() }
            back()
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Search for where to go")).fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
