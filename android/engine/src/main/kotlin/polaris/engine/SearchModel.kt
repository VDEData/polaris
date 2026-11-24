package polaris.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Clock

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

/**
 * One search across every enabled source. Results merge in as each source
 * answers, so the fast ones show while the slow ones are still working.
 *
 * Request budget per search: Google 5, 1 or 0 by `GoogleAllowance` (spent before sending),
 * TfL 1 (3 when deep), Transitous exactly 1, and TfL's network status 2 unless it was fetched
 * in the last 3 minutes (`NetworkStatusCache`), skipped when TfL is off.
 */
class SearchModel(
    private val http: HTTP,
    private val usage: GoogleUsage,
    private val networkCache: NetworkStatusCache = NetworkStatusCache(),
    private val clock: Clock = Clock.systemUTC(),
    /** For a Google key restricted to this Android app. */
    private val packageName: String = "",
    private val certificateSha1: String? = null,
) {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private val lock = Mutex()
    private var found: List<Journey> = emptyList()

    /** Runs to completion; cancel the calling coroutine to drop a search. */
    suspend fun search(query: JourneyQuery, settings: AppSettings) {
        lock.withLock { found = emptyList() }
        // Google within its free monthly allowance: every deep-search way under 700 this month,
        // one under 900, none past that.
        val google = googlePlan(settings.isEnabled(ProviderID.google), settings.googleKey != null, settings.deepSearch, usage.allowance, clock.instant())
        val googleRequests = (google as? GooglePlan.Ask)?.requests ?: 0
        val providers: List<JourneyProvider> = listOf(
            GoogleRoutesProvider(http, settings.googleKey, packageName, certificateSha1, googleRequests),
            TfLProvider(http, settings.tflKey),
            TransitousProvider(http),
        )
        val statuses = mutableMapOf<ProviderID, ProviderStatus>()
        for (provider in providers) {
            statuses[provider.id] = when {
                provider.id == ProviderID.google && google is GooglePlan.Off -> ProviderStatus.Off(google.reason)
                !settings.isEnabled(provider.id) -> ProviderStatus.Off("Turned off")
                else -> ProviderStatus.Loading
            }
        }
        if (statuses[ProviderID.google] == ProviderStatus.Loading) usage.spend(googleRequests, clock.instant())
        // MapKit's transit estimate has no Android counterpart yet.
        statuses[ProviderID.apple] = ProviderStatus.Off("Not on Android")
        _state.value = SearchState(query = query, statuses = statuses.toMap())

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
                    receive(result, provider.id)
                }
            }
            if (checksNetwork) {
                launch {
                    // Without it the routes still show, just unchecked.
                    val status = networkCache.status(http, tflKey) ?: return@launch
                    lock.withLock {
                        _state.update { it.copy(network = status) }
                        publish()
                    }
                }
            }
        }
    }

    private suspend fun receive(result: Result<List<Journey>>, provider: ProviderID) = lock.withLock {
        result.fold(
            onSuccess = { new ->
                found = found + new
                publish()
                _state.update { it.copy(statuses = it.statuses + (provider to ProviderStatus.Done(new.size))) }
            },
            onFailure = { error ->
                _state.update { it.copy(statuses = it.statuses + (provider to ProviderStatus.Failed(describe(error)))) }
            },
        )
    }

    private fun publish() {
        val merged = JourneyMerger.merge(found)
        _state.update { state ->
            val network = state.network
            state.copy(journeys = if (network == null) merged else merged.map { it.copy(notices = network.notices(it)) })
        }
    }

    sealed interface GooglePlan {
        data class Ask(val requests: Int) : GooglePlan
        data class Off(val reason: String) : GooglePlan
    }

    companion object {
        /** What a search asks Google: every deep-search way under 700 this month, one under 900, none past. */
        fun googlePlan(enabled: Boolean, hasKey: Boolean, deep: Boolean, allowance: GoogleAllowance, now: java.time.Instant): GooglePlan {
            if (!enabled) return GooglePlan.Off("Turned off")
            if (!hasKey) return GooglePlan.Off("Add a key in Settings")
            val requests = allowance.searchRequests(deep, GoogleRoutesProvider.variants.size, now)
            return if (requests > 0) GooglePlan.Ask(requests) else GooglePlan.Off("Free allowance used this month")
        }

        fun describe(error: Throwable): String = when (error) {
            is ProviderError -> error.errorDescription
            is UnknownHostException -> "Offline"
            is SocketTimeoutException -> "Timed out"
            is IOException -> "Offline"
            else -> "No routes"
        }
    }
}
