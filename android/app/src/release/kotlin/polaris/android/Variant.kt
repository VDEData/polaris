package polaris.android

import android.content.Context
import android.content.Intent
import polaris.engine.HttpTransport
import polaris.engine.JourneyQuery

/** Release builds: the real network, and the app opens on Home. (Debug builds have recorded answers for checks.) */
object Variant {
    fun transport(context: Context, intent: Intent?): HttpTransport? = null
    fun startTrip(intent: Intent?): JourneyQuery? = null
}
