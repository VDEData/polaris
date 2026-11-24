package polaris.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Clock
import java.time.Instant

// Ported from Polaris/Engine/SearchModel.swift.

sealed interface ProviderStatus {
    data object Loading : ProviderStatus
    data class Done(val count: Int) : ProviderStatus
    data class Failed(val reason: String) : ProviderStatus
    data class Off(val reason: String) : ProviderStatus
}

data class SearchState(
    val query: JourneyQuery? = null,
    val statuses: Map<ProviderID, ProviderStatus> = emptyMap(),
    /** Every source's journeys, merged; the screen ranks them by the chosen order. */
    val journeys: List<Journey> = emptyList(),
    /** TfL's reports on the network, checked against every source's journeys. */
    val network: NetworkStatus? = null,
) {
    val isSearching: Boolean get() = statuses.values.any { it == ProviderStatus.Loading }
}

/** Which Android app is asking, for a Google key restricted to it. Neither value is a secret. */
data class AppIdentity(val packageName: String = "", val certificateSha1: String? = null) {
    companion object {
        /**
         * The form Google's `X-Android-Cert` header takes: the SHA-1 of the signing certificate
         * (its DER bytes) as hexadecimal, upper case, with no colons.
         */
        fun fingerprint(certificate: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-1").digest(certificate).joinToString("") { "%02X".format(it) }
    }
}

/**
 * One search across every enabled source. Results merge in as each source
 * answers, so the fast ones show while the slow ones are still working.
 *
 * Request budget per search: Google 5, 1 or 0 by `GoogleAllowance` (counted before sending),
 * TfL 1 (3 when deep), Transitous exactly 1, and TfL's network status 2 unless any search fetched
 * it in the last 3 minutes (`NetworkStatusCache.shared`), skipped when TfL is off.
 *
 * Starting a search cancels the one before, as on iOS, and nothing the earlier one gets back
 * afterwards reaches the state.
 */
class SearchModel(
    private val http: HTTP,
    private val usage: GoogleUsage,
    private val networkCache: NetworkStatusCache = NetworkStatusCache.shared,
    private val clock: Clock = Clock.systemUTC(),
    private val app: AppIdentity = AppIdentity(),
) {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    // Guarded by `this`: the search that may still write, and what it has found.
    private var generation = 0
    private var running: Job? = null
    private var found: List<Journey> = emptyList()

    /** Runs to completion, or returns early when another search starts; cancel the caller to drop it. */
    suspend fun search(query: JourneyQuery, settings: AppSettings) {
        val caller = currentCoroutineContext()[Job]
        try {
            // The search runs in a job of its own, so a newer search cancels just this one and
            // whoever called it carries on.
            coroutineScope { run(query, settings, coroutineContext[Job]!!) }
        } catch (e: CancellationException) {
            // Replaced by a newer search: return quietly. A cancelled caller is passed on.
            if (caller?.isActive == false) throw e
        }
    }

    private suspend fun run(query: JourneyQuery, settings: AppSettings, own: Job) {
        val mine = synchronized(this) {
            running?.cancel()
            running = own
            found = emptyList()
            ++generation
        }

        // Google within its free monthly allowance: every deep-search way under 700 this month,
        // one under 900, none past that. Planned and counted in one step.
        val now = clock.instant()
        // Read once: the plan and the provider must agree on whether there is a key.
        val googleKey = settings.googleKey
        val google = googlePlan(settings.isEnabled(ProviderID.google), googleKey != null, settings.deepSearch, usage.allowance, now)
        val googleRequests = if (google is GooglePlan.Ask) usage.reserve(settings.deepSearch, GoogleRoutesProvider.variants.size, now) else 0
        val providers: List<JourneyProvider> = listOf(
            GoogleRoutesProvider(http, googleKey, app.packageName, app.certificateSha1, googleRequests),
            TfLProvider(http, settings.tflKey),
            TransitousProvider(http),
        )
        val statuses = mutableMapOf<ProviderID, ProviderStatus>()
        for (provider in providers) {
            statuses[provider.id] = when {
                provider.id == ProviderID.google && (google is GooglePlan.Off || googleRequests == 0) ->
                    ProviderStatus.Off((google as? GooglePlan.Off)?.reason ?: "Free allowance used this month")
                !settings.isEnabled(provider.id) -> ProviderStatus.Off("Turned off")
                else -> ProviderStatus.Loading
            }
        }
        // MapKit's transit estimate has no Android counterpart yet.
        statuses[ProviderID.apple] = ProviderStatus.Off("Not on Android")
        write(mine) { SearchState(query = query, statuses = statuses.toMap()) }

        val deep = settings.deepSearch
        val checksNetwork = settings.isEnabled(ProviderID.tfl)
        val tflKey = settings.tflKey
        val active = providers.filter { statuses[it.id] == ProviderStatus.Loading }
        coroutineScope {
            for (provider in active) {
                launch {
                    val result = try {
                        Result.success(provider.journeys(query, deep))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                    receive(mine, result, provider.id)
                }
            }
            if (checksNetwork) {
                launch {
                    // Without it the routes still show, just unchecked.
                    val status = networkCache.status(http, tflKey) ?: return@launch
                    write(mine) { publish(it.copy(network = status)) }
                }
            }
        }
    }

    private fun receive(mine: Int, result: Result<List<Journey>>, provider: ProviderID) {
        result.fold(
            onSuccess = { new ->
                write(mine) { state ->
                    found = found + new
                    publish(state).let { it.copy(statuses = it.statuses + (provider to ProviderStatus.Done(new.size))) }
                }
            },
            onFailure = { error ->
                write(mine) { it.copy(statuses = it.statuses + (provider to ProviderStatus.Failed(describe(error)))) }
            },
        )
    }

    /** Changes the state only while `mine` is still the current search: a replaced one's answers are dropped. */
    private inline fun write(mine: Int, change: (SearchState) -> SearchState) = synchronized(this) {
        if (mine == generation) _state.value = change(_state.value)
    }

    private fun publish(state: SearchState): SearchState {
        val merged = JourneyMerger.merge(found)
        val network = state.network
        return state.copy(journeys = if (network == null) merged else merged.map { it.copy(notices = network.notices(it)) })
    }

    sealed interface GooglePlan {
        data class Ask(val requests: Int) : GooglePlan
        data class Off(val reason: String) : GooglePlan
    }

    companion object {
        /**
         * The engine as the app uses it: the real network, the build's contact, the app's identity
         * for Google. Tests pass their own transport to the same factory.
         */
        fun create(
            settings: AppSettings,
            usage: GoogleUsage,
            appVersion: String,
            app: AppIdentity,
            transport: HttpTransport = OkHttpTransport(),
            networkCache: NetworkStatusCache = NetworkStatusCache.shared,
            clock: Clock = Clock.systemUTC(),
        ) = SearchModel(HTTP(transport, appVersion) { settings.contact }, usage, networkCache, clock, app)

        /** What a search asks Google: every deep-search way under 700 this month, one under 900, none past. */
        fun googlePlan(enabled: Boolean, hasKey: Boolean, deep: Boolean, allowance: GoogleAllowance, now: Instant): GooglePlan {
            if (!enabled) return GooglePlan.Off("Turned off")
            if (!hasKey) return GooglePlan.Off("Add a key in Settings")
            val requests = allowance.searchRequests(deep, GoogleRoutesProvider.variants.size, now)
            return if (requests > 0) GooglePlan.Ask(requests) else GooglePlan.Off("Free allowance used this month")
        }

        /**
         * As Swift's `describe`: offline and timed out have their own words; anything else a
         * source couldn't answer is "No routes". With no connection Android fails the name lookup.
         */
        fun describe(error: Throwable): String = when {
            error is ProviderError -> error.errorDescription
            isOffline(error) -> "Offline"
            error is SocketTimeoutException -> "Timed out"
            error is java.io.InterruptedIOException && error.message == "timeout" -> "Timed out"
            else -> "No routes"
        }

        /**
         * iOS's "not connected": the name lookup fails, or the connect finds no network
         * (ENETUNREACH, no route to host). A server refusing the connection is not offline.
         */
        private fun isOffline(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(5).any { e ->
            e is UnknownHostException || e is java.net.NoRouteToHostException ||
                (e is java.net.ConnectException && (e.message ?: "").let { "ENETUNREACH" in it || "Network is unreachable" in it })
        }
    }
}
