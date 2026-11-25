package polaris.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Clock

/**
 * A re-plan's one search (iOS's `ReplanSearch.live`): Google once (while the allowance and the
 * trip's cap allow), TfL once, Transitous once, never their deep-search variants, plus TfL's
 * network status (shared with searches, kept 3 minutes) to flag what comes back. At most 5
 * requests, and only when Go asks, which is on an event and within [Replan.Watch]'s limits.
 */
object ReplanSearch {
    fun live(
        settings: AppSettings,
        usage: GoogleUsage,
        http: HTTP,
        app: AppIdentity = AppIdentity(),
        networkCache: NetworkStatusCache = NetworkStatusCache.shared,
        clock: Clock = Clock.systemUTC(),
    ): suspend (JourneyQuery, Replan.Watch) -> ReplanAnswer = { query, watch ->
        val now = clock.instant()
        val keys = settings.readKeys()
        // A key saved but unreadable right now: that source sits this search out, as a search does.
        val googleKey = (keys.google as? AppSettings.Key.Use)?.value
        val tflUsable = keys.tfl is AppSettings.Key.Use
        val tflKey = (keys.tfl as? AppSettings.Key.Use)?.value
        val enabled = ProviderID.entries.filter { settings.isEnabled(it) && (it != ProviderID.tfl || tflUsable) }.toSet()
        val sources = Replan.sources(enabled, googleKey != null, usage.allowance, watch, now)
        val providers = buildList<JourneyProvider> {
            if (ProviderID.google in sources) {
                usage.spend(1, now)
                add(GoogleRoutesProvider(http, googleKey, app.packageName, app.certificateSha1, 1))
            }
            if (ProviderID.tfl in sources) add(TfLProvider(http, tflKey))
            if (ProviderID.transitous in sources) add(TransitousProvider(http))
        }
        val checksNetwork = ProviderID.tfl in enabled
        coroutineScope {
            val network = async { if (checksNetwork) networkCache.status(http, tflKey) else null }
            val results = providers.map { provider ->
                async {
                    try {
                        provider.journeys(query, false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll()
            val status = network.await()
            val journeys = JourneyMerger.merge(results.filterNotNull().flatten()).map { journey ->
                if (status != null) journey.copy(notices = status.notices(journey)) else journey
            }
            // Without TfL's status the offer still goes, saying it wasn't checked (TfL off counts too).
            ReplanAnswer(journeys, answered = results.any { it != null }, usedGoogle = ProviderID.google in sources, statusChecked = status != null)
        }
    }
}
