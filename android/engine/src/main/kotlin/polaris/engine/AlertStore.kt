package polaris.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

/** Whether this build and phone can be sent a push at all. */
@Suppress("EnumEntryName")
enum class PushAvailability {
    ready,
    /** The app was built without a push service's settings (no Firebase file): no notices can arrive. */
    notSetUp,
    /** Built with them, but this phone can't use the service (no Google Play services). */
    unavailable,
}

/**
 * What gets this install an address a push can be sent to: Firebase on a phone, a fake in tests.
 * Nothing here is asked until there is an alert and a server to tell.
 */
interface PushRegistrar {
    val availability: PushAvailability

    /** This install's push address (a Firebase installation ID), asked for now. Throws when it can't be had. */
    suspend fun register(): String

    /**
     * Gives the address up and registers under a new one (Firebase: the installation is deleted
     * and made again). For a phone the server holds under a secret this install has lost: the
     * old address can no longer be changed or removed from here, and stops being reachable.
     */
    suspend fun reset(): String = register()

    /** A build with no push service. */
    object None : PushRegistrar {
        override val availability = PushAvailability.notSetUp
        override suspend fun register(): String = throw IllegalStateException("no push service in this build")
    }
}

/**
 * Your alerts, kept on the phone and mirrored to an alerts server (`worker/`) with this phone's
 * push address, so the server can tell you about trouble while the app is closed. With no server
 * (none built in, none entered in Settings) alerts are off: no requests, no push registration.
 * With a server but no push service in the build, alerts still show what TfL says now; the page
 * says no notices can arrive. Ported from Polaris/Services/AlertStore.swift.
 *
 * Confined to [scope]'s thread (the main thread in the app): call it from there. Requests and
 * the secret store are used on [io].
 */
class AlertStore(
    private val settings: AppSettings,
    private val secrets: SecretStore,
    private val values: KeyValueStore,
    private val transport: HttpTransport,
    private val userAgent: () -> String,
    private val push: PushRegistrar,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> String = { ZoneId.systemDefault().id },
    /** How long sync waits for more edits before sending. */
    private val syncDelayMillis: Long = 400,
    private val random: (ByteArray) -> Unit = { SecureRandom().nextBytes(it) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** How long the push service is given to answer before the page is told it hasn't (offline, it waits without a word). */
    private val registerTimeoutMillis: Long = 30_000,
) {
    /** Why the server's copy may be out of date. */
    sealed interface Trouble {
        /** No answer (offline, the server down). */
        data object Unreachable : Trouble
        /** The server's own words (no room for another phone, registered by another install). */
        data class Said(val message: String) : Trouble
        /** The phone's secure store can't be read just now. */
        data object Locked : Trouble
    }

    data class State(
        val alerts: List<RouteAlert> = emptyList(),
        /** What TfL says about each alert, by id, from the server. */
        val status: Map<String, AlertStatus.Entry> = emptyMap(),
        val checkedAt: Instant? = null,
        /** The last check of TfL didn't come back. */
        val statusFailed: Boolean = false,
        val syncTrouble: Trouble? = null,
        /** A server is set (built in, or entered in Settings): alerts can be added. */
        val configured: Boolean = false,
        /** The server's host, for Settings. */
        val serverLabel: String? = null,
        val serverIsBuiltIn: Boolean = false,
        val push: PushAvailability = PushAvailability.notSetUp,
        /** The push service wouldn't give this phone an address just now. */
        val pushFailed: Boolean = false,
        /** The server has this phone and its alerts. */
        val registered: Boolean = false,
        /** What the server said when it took the registration: whether it can push to this phone. Null until it has. */
        val serverCanPush: Boolean? = null,
        /**
         * The server held this phone under a secret this install no longer has (the secure
         * store's key was lost), so the phone was registered afresh under a new address. Said
         * once, until [acknowledgeReRegistered].
         */
        val reRegistered: Boolean = false,
    ) {
        /** No more alerts can be added: the server takes [RouteAlert.MAX_ALERTS] a phone. */
        val isFull: Boolean get() = alerts.size >= RouteAlert.MAX_ALERTS
    }

    private val _state = MutableStateFlow(
        State(alerts = RouteAlert.decodeList(values.getString(ALERTS)), push = push.availability),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val alerts: List<RouteAlert> get() = _state.value.alerts
    private var pushId: String? = values.getString(PUSH_ID)
        set(value) { field = value; values.putString(PUSH_ID, value) }
    /** The address the server last heard, to forget it when the phone gets a new one. */
    private var registered: String? = values.getString(REGISTERED)
        set(value) {
            field = value
            values.putString(REGISTERED, value)
            _state.update { it.copy(registered = value != null && value == pushId) }
        }
    private var syncing: Job? = null
    private var statusJob: Job? = null

    init {
        _state.update { it.copy(registered = registered != null && registered == pushId) }
        scope.launch { readServer() }
    }

    /** The server in use, read again each time (the entered one is sealed; it can come back after an unlock). */
    private suspend fun server(): AlertServer? = withContext(io) { settings.alertServer }

    private suspend fun readServer(): AlertServer? = server().also { server ->
        _state.update { it.copy(configured = server != null, serverLabel = server?.let(::label), serverIsBuiltIn = server?.isBuiltIn == true) }
    }

    private fun setAlerts(alerts: List<RouteAlert>) {
        values.putString(ALERTS, RouteAlert.encode(alerts))
        _state.update { it.copy(alerts = alerts) }
    }

    /** For tests: until the pending sync has gone out. */
    suspend fun awaitSync() { syncing?.join() }

    // Editing

    /**
     * Saves an alert, cut to what the server takes (its lines, times and name), so one alert too
     * big can't make the server refuse them all. False, and nothing saved, for one alert more
     * than the server keeps for a phone.
     */
    fun save(alert: RouteAlert): Boolean {
        val list = alerts.toMutableList()
        val index = list.indexOfFirst { it.id == alert.id }
        if (index < 0 && list.size >= RouteAlert.MAX_ALERTS) return false
        val kept = alert.withinLimits
        if (index >= 0) list[index] = kept else list += kept
        setAlerts(list)
        _state.update { it.copy(status = it.status - alert.id) }
        // Each by itself: a push service slow to answer doesn't hold back what TfL says.
        scope.launch { enablePush() }
        scope.launch { refreshStatus(force = true) }
        sync()
        return true
    }

    fun delete(id: String) {
        setAlerts(alerts.filterNot { it.id == id })
        _state.update { it.copy(status = it.status - id) }
        sync()
    }

    // Push

    /**
     * Asks the push service for this phone's address, once there is an alert and a server to
     * give it to, and on every launch after (it can change).
     */
    suspend fun enablePush() {
        if (alerts.isEmpty() || readServer() == null) return
        _state.update { it.copy(push = push.availability) }
        if (push.availability != PushAvailability.ready) return
        try {
            // No answer in time is said as a failure; if the service answers later, it tells the
            // app itself (the address then comes through didRegister).
            val id = kotlinx.coroutines.withTimeoutOrNull(registerTimeoutMillis) { push.register() }
            if (id == null) {
                _state.update { it.copy(pushFailed = true) }
                // The address from last time still stands until the service says otherwise: the alerts go to the server under it.
                if (pushId != null) sync()
                return
            }
            _state.update { it.copy(pushFailed = false) }
            didRegister(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(pushFailed = true) }
            if (pushId != null) sync()
        }
    }

    /** The push service gave (or changed) this phone's address. */
    fun didRegister(id: String) {
        // With no alerts there is nothing to register, unless the server still has the phone from before.
        if (alerts.isEmpty() && registered == null) return
        if (id != pushId) {
            pushId = id
            _state.update { it.copy(registered = registered != null && registered == id) }
        }
        _state.update { it.copy(pushFailed = false) }
        sync()
    }

    /** The server's copy follows the phone's; the last change wins. */
    private fun sync() {
        syncing?.cancel()
        syncing = scope.launch {
            delay(syncDelayMillis)
            val id = pushId ?: return@launch
            val server = readServer() ?: return@launch
            try {
                registered?.takeIf { it != id }?.let { send("DELETE", "devices/$it", server = server) }
                if (alerts.isEmpty()) {
                    send("DELETE", "devices/$id", server = server)
                    registered = null
                    _state.update { it.copy(serverCanPush = null) }
                } else {
                    val body = HTTP.json.encodeToString(RouteAlert.Registration.serializer(), RouteAlert.Registration(ENVIRONMENT, zone(), alerts.map { it.wire }))
                    val reply = send("PUT", "devices/$id", body.encodeToByteArray(), server)
                    registered = id
                    // A server from before Android phones says nothing: then it isn't known.
                    val canPush = runCatching { HTTP.json.parseToJsonElement(reply.decodeToString()).jsonObject["pushes"]?.jsonPrimitive?.booleanOrNull }.getOrNull()
                    _state.update { it.copy(serverCanPush = canPush) }
                }
                _state.update { it.copy(syncTrouble = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(syncTrouble = trouble(e)) }
                if (e is AlertError.Server && e.status == 403) registerAfresh()
            }
        }
    }

    private var registeredAfresh = false

    /**
     * The server refuses this phone as another install's: it holds the phone's address under a
     * secret this install no longer has (the secure store's key was lost; the address itself
     * outlives that). Nothing sent from here can change or remove that registration, so the
     * address is given up and the phone registered under a new one. The old one stops being
     * reachable, and the server forgets it when the push service says so. Once a process: a
     * server that refuses the new address too is not asked for ever.
     */
    private suspend fun registerAfresh() {
        if (registeredAfresh || push.availability != PushAvailability.ready) return
        registeredAfresh = true
        val id = try {
            kotlinx.coroutines.withTimeoutOrNull(registerTimeoutMillis) { push.reset() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        if (id == pushId) return
        pushId = id
        registered = null
        _state.update { it.copy(reRegistered = true, serverCanPush = null) }
        if (alerts.isNotEmpty()) sync()
    }

    /** The person has read that the phone was registered afresh. */
    fun acknowledgeReRegistered() = _state.update { it.copy(reRegistered = false) }

    private fun trouble(e: Exception): Trouble = when (e) {
        is AlertError.Server -> Trouble.Said(e.said)
        is AlertError.Locked -> Trouble.Locked
        else -> Trouble.Unreachable
    }

    private var resumed = false

    /**
     * On launch: pick up a new address, and resend in case the last change didn't reach the
     * server. Once a process: the screen being made anew (a rotation, a fold) asks nothing more.
     */
    fun resume() {
        if (resumed) return
        resumed = true
        if (alerts.isEmpty()) {
            // The last alert was deleted while the server couldn't be told: it is told now, so it stops watching.
            if (registered != null) sync()
            return
        }
        scope.launch { enablePush() }
    }

    private var lastRetry: Instant? = null

    /**
     * Polaris came back to the front, or the network came back: whatever the server is still
     * owed (a change it never got, a phone it should forget, an address the push service hadn't
     * given) is tried again. At most once every [RETRY_SECONDS], and nothing when nothing is owed.
     */
    fun retryIfOwed() {
        val now = _state.value
        val owed = now.syncTrouble != null || now.pushFailed || (alerts.isNotEmpty() && !now.registered && now.push == PushAvailability.ready) || (alerts.isEmpty() && registered != null)
        if (!owed) return
        val at = clock()
        lastRetry?.let { if (Duration.between(it, at).seconds in 0 until RETRY_SECONDS) return }
        lastRetry = at
        if (alerts.isNotEmpty() && (pushId == null || now.pushFailed)) scope.launch { enablePush() } else sync()
    }

    sealed interface TestResult {
        data object Sent : TestResult
        data object NoServer : TestResult
        data object NotRegistered : TestResult
        data class Said(val message: String) : TestResult
        data object Unreachable : TestResult
    }

    /** A real notification, sent the way trouble will be. */
    suspend fun sendTest(): TestResult {
        val server = readServer() ?: return TestResult.NoServer
        val id = pushId
        if (id == null || registered != id) return TestResult.NotRegistered
        return try {
            send("POST", "devices/$id/test", server = server)
            TestResult.Sent
        } catch (e: CancellationException) {
            throw e
        } catch (e: AlertError.Server) {
            TestResult.Said(e.said)
        } catch (e: Exception) {
            TestResult.Unreachable
        }
    }

    // Status

    /** What TfL says now, at most once a minute. */
    suspend fun refreshStatus(force: Boolean = false) {
        if (alerts.isEmpty()) return
        val server = readServer() ?: return
        val now = _state.value
        if (!force && now.checkedAt != null && Duration.between(now.checkedAt, clock()).seconds < 60 && now.status.keys == alerts.map { it.id }.toSet()) return
        statusJob?.cancel()
        val job = scope.launch {
            try {
                val body = HTTP.json.encodeToString(RouteAlert.StatusRequest.serializer(), RouteAlert.StatusRequest(alerts.map { it.wire }, zone()))
                val result = AlertStatus.decode(send("POST", "status", body.encodeToByteArray(), server))
                _state.update { it.copy(status = result.alerts.reversed().associateBy { e -> e.id }, checkedAt = result.checkedAt, statusFailed = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(statusFailed = true) }
            }
        }
        statusJob = job
        job.join()
    }

    // Choosing the server

    sealed interface UseResult {
        /** [couldNotLeave]: the server left behind couldn't be told to forget this phone, so it may keep sending. */
        data class Used(val couldNotLeave: String? = null) : UseResult
        data class Refused(val problem: AlertServer.Problem) : UseResult
        data object NoToken : UseResult
        /** The phone's secure store wouldn't keep it (or what is saved can't be read): nothing changed. */
        data object NotSaved : UseResult
    }

    /**
     * Switches to a server entered in Settings, or with an empty address back to the built-in
     * one (or none). This phone is first removed from the server it leaves, with that server's
     * own token and secret, so it stops pushing; nothing of the old server's goes to the new one.
     * Alerts stay on the phone and are registered with the new server.
     */
    suspend fun useServer(url: String, token: String): UseResult =
        // In the store's own scope: once begun it finishes (the old server told, the new one given the alerts), whatever becomes of the screen that asked.
        scope.async { switchServer(url, token) }.await()

    private suspend fun switchServer(url: String, token: String): UseResult {
        val address = url.trim()
        val appToken = token.trim()
        val next: String? = if (address.isEmpty()) null else try {
            AlertServer.address(address)
        } catch (e: AlertServer.InvalidAddress) {
            return UseResult.Refused(e.problem)
        }
        if (address.isNotEmpty() && appToken.isEmpty()) return UseResult.NoToken
        val old = server()
        syncing?.cancel()
        statusJob?.cancel()
        // Saved first: a store that won't keep it changes nothing, here or on either server.
        val saved = withContext(io) { settings.useAlertsServer(address, if (address.isEmpty()) "" else appToken) }
        if (saved != AppSettings.Save.saved) {
            sync()
            return UseResult.NotSaved
        }
        val now = readServer()
        var note: String? = null
        // The same address (however it was typed) is the same server: no removal, just the new token.
        val left = registered
        if (old != null && old.url != (now?.url ?: next) && left != null) {
            try {
                send("DELETE", "devices/$left", server = old)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                note = label(old)
            }
        }
        registered = null
        _state.update { it.copy(status = emptyMap(), checkedAt = null, statusFailed = false, syncTrouble = null, serverCanPush = null) }
        if (now != null && alerts.isNotEmpty()) {
            // Not waited for: a push service slow to answer mustn't hold Settings; its address registers when it comes.
            scope.launch { enablePush() }
            refreshStatus(force = true)
        }
        return UseResult.Used(note)
    }

    // Server

    sealed class AlertError(message: String) : Exception(message) {
        /** The server's own words, kept short: it is whatever address was entered. */
        class Server(val said: String, val status: Int = 0) : AlertError(said)
        class Locked : AlertError("The secure store can't be read just now")
        class TooLarge : AlertError("The reply was larger than allowed")
    }

    /**
     * This install's own secret for a server: it lets only the install that registered a phone
     * change, test or remove it. Random, sealed in the secure store, never shown; one per
     * address, so a server can't use the secret it was sent against another. A store that can't
     * be read now means no request, never a new secret.
     */
    fun deviceSecret(server: AlertServer): String {
        val account = secretAccount(server)
        when (val read = secrets.read(account)) {
            is SecretRead.Value -> if (read.text.length >= 32) return read.text
            SecretRead.Unreadable -> throw AlertError.Locked()
            // Lost for good, or never made. A lost one's notice is this store's to clear: what
            // follows from the loss (the server refusing the phone, and registering afresh) is handled in sync.
            else -> secrets.forgetLost(account)
        }
        val bytes = ByteArray(32).also(random)
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        // A secret that couldn't be kept would lock this phone out of its own registration later.
        if (!secrets.write(secret, account)) throw AlertError.Locked()
        return secret
    }

    /**
     * One request to [server], with its token and, for a phone's own routes, this install's
     * secret for it. Redirects are refused: the token and secret go to that address only. A
     * reply over [MAX_RESPONSE] is refused.
     */
    private suspend fun send(method: String, path: String, body: ByteArray? = null, server: AlertServer): ByteArray = withContext(io) {
        val headers = buildMap {
            put("Authorization", "Bearer ${server.token}")
            if (path.startsWith("devices/")) put("X-Device-Secret", deviceSecret(server))
            put("Content-Type", "application/json")
            put("User-Agent", userAgent())
        }
        val response = try {
            transport.send(HttpRequest("${server.url}/v1/$path", method, headers, body, followRedirects = false, maxBodyBytes = MAX_RESPONSE.toLong()))
        } catch (e: OkHttpTransport.TooLarge) {
            throw AlertError.TooLarge()
        }
        if (response.body.size > MAX_RESPONSE) throw AlertError.TooLarge() // for any transport
        if (response.status !in 200..299) {
            val problem = runCatching { HTTP.json.parseToJsonElement(response.body.decodeToString()).jsonObject }.getOrNull()
            val said = problem?.get("error")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                ?: problem?.get("reason")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.let { "The push service refused it: $it" }
                ?: "The alert server said ${response.status}."
            throw AlertError.Server(said.take(200), response.status)
        }
        response.body
    }

    companion object {
        const val ALERTS = "alerts"
        const val PUSH_ID = "alerts.pushId"
        const val REGISTERED = "alerts.registered"
        /** The server's column is APNs' (sandbox or production); FCM has one environment. */
        const val ENVIRONMENT = "production"
        /** The most a reply may be: a status for 30 alerts is a few KB. */
        const val MAX_RESPONSE = 512 * 1024
        /** The least time between two retries of what the server is owed. */
        const val RETRY_SECONDS = 30L

        /** The secret-store account of this install's device secret for a server: by its address alone. */
        fun secretAccount(server: AlertServer): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(server.url.lowercase().encodeToByteArray())
            return "alerts.secret." + digest.take(8).joinToString("") { "%02x".format(it) }
        }

        /** For Settings: the host, with a port or path when it has one. */
        fun label(server: AlertServer): String = server.url.substringAfter("://").trim('/')
    }
}
