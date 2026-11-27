package polaris.android

import android.content.Context
import android.content.Intent
import polaris.engine.HttpTransport
import polaris.engine.JourneyQuery

/** Release builds: the real network, and the app opens on Home. (Debug builds have recorded answers for checks.) */
object Variant {
    fun transport(context: Context, intent: Intent?): HttpTransport? = null
    fun startTrip(intent: Intent?): JourneyQuery? = null
    fun secrets(intent: Intent?): polaris.engine.SecretStore? = null
    fun clock(intent: Intent?): (() -> java.time.Instant)? = null
    fun replanSearch(intent: Intent?): (suspend (JourneyQuery, polaris.engine.Replan.Watch) -> polaris.engine.ReplanAnswer)? = null
}
