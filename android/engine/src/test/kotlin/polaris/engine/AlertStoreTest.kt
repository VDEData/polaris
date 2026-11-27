package polaris.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The alert store against a recorded transport, an in-memory secret store and a made-up push
 * service: which server gets which token and secret, what a locked store does, what the server's
 * reply may be, and that nothing is asked of anyone until there is an alert and a server.
 * Mirrors PolarisTests/AlertStoreTests.swift, with what Android adds (a build with no push service).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertStoreTest {
    class Secrets : SecretStore {
        val items = mutableMapOf<String, String>()
        val locked = mutableSetOf<String>()
        val failingWrites = mutableSetOf<String>()
        var lockEverything = false
        override fun read(account: String): SecretRead {
            if (lockEverything || account in locked) return SecretRead.Unreadable
            return items[account]?.let { SecretRead.Value(it) } ?: SecretRead.Absent
        }
        override fun write(value: String, account: String): Boolean {
            if (account in failingWrites || failingWrites.any { it.endsWith("*") && account.startsWith(it.dropLast(1)) }) return false
            if (value.isEmpty()) items.remove(account) else items[account] = value
            return true
        }
    }

    class Wire : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        val failing = mutableSetOf<String>()
        var status = 200
        /** What each request is answered with, when a test says: the status for it (else [status]). */
        var statusFor: (HttpRequest) -> Int? = { null }
        var reply = """{"checkedAt":"2026-11-24T12:00:00Z","alerts":[]}"""
        var putReply = """{"ok":true,"alerts":1,"pushes":true}"""
        /** What a 403 says: the server's own, unless a test makes it something in front of the server. */
        var refusal = """{"error":"This phone is registered by another install of Polaris","code":"not_owner"}"""
        /** Held until completed, for a request that is slow to be answered. */
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request
            if (request.host in failing) throw java.net.ConnectException("refused")
            gate?.await()
            val code = statusFor(request) ?: status
            val said = if (code == 403) refusal else if (request.method == "PUT") putReply else reply
            return HttpResponse(code, said.encodeToByteArray())
        }
        fun to(host: String) = requests.filter { it.host == host }
        fun said(host: String) = to(host).map { "${it.method} ${URI(it.url).path}" }
    }

    class Push(override var availability: PushAvailability = PushAvailability.ready, var id: String? = ID) : PushRegistrar {
        var asked = 0
        var resets = 0
        /** Held until completed, for a push service slow to answer. */
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun register(): String {
            asked++
            gate?.await()
            return id ?: throw IllegalStateException("no address")
        }
        var released = 0
        /** What happens while the installation is being made again (the service telling the app its new address, the app coming to the front). */
        var duringReset: suspend () -> Unit = {}
        override suspend fun reset(): String {
            resets++
            // As Firebase does it: the old installation goes, time passes, the new address is announced, and only then is it returned.
            kotlinx.coroutines.delay(1_000)
            id = AFRESH
            duringReset()
            kotlinx.coroutines.delay(1_000)
            return AFRESH
        }
        override fun release() { released++ }
    }

    private val district = RouteAlert(id = "DISTRICT", segments = listOf(RouteAlert.Segment(RouteAlert.Line("district", "District line"), "tube")))

    private class Rig(
        scope: TestScope,
        val secrets: Secrets = Secrets(),
        val wire: Wire = Wire(),
        val push: Push = Push(),
        val values: InMemoryKeyValueStore = InMemoryKeyValueStore(),
        builtIn: Pair<String, String>? = "https://built.example.com" to "built-token",
        alerts: List<RouteAlert>,
        registered: Boolean = true,
    ) {
        val settings = AppSettings(secrets, BuildDefaults(alertsUrl = builtIn?.first, alertsToken = builtIn?.second), values)
        val store: AlertStore

        init {
            if (alerts.isNotEmpty()) values.putString(AlertStore.ALERTS, RouteAlert.encode(alerts))
            if (registered) {
                values.putString(AlertStore.PUSH_ID, ID)
                values.putString(AlertStore.REGISTERED, ID)
            }
            val dispatcher = StandardTestDispatcher(scope.testScheduler)
            store = AlertStore(
                settings, secrets, values, wire, { "Polaris/test" }, push, scope, clock = { iso("2026-11-24T12:00:30Z") },
                zone = { "Europe/London" }, syncDelayMillis = 0, random = { bytes -> bytes.fill(7) }, io = dispatcher,
            )
        }
    }

    private fun TestScope.rig(alerts: List<RouteAlert> = listOf(district), registered: Boolean = true, builtIn: Pair<String, String>? = "https://built.example.com" to "built-token", push: Push = Push(), secrets: Secrets = Secrets(), wire: Wire = Wire()) =
        Rig(this, secrets = secrets, wire = wire, push = push, builtIn = builtIn, alerts = alerts, registered = registered).also { advanceUntilIdle() }

    @Test fun nothingIsAskedOfAnyoneUntilThereIsAnAlertAndAServer() = runTest {
        // A server but no alerts: launch, and a look at the alerts page.
        val idle = rig(alerts = emptyList(), registered = false)
        idle.store.resume()
        idle.store.refreshStatus()
        idle.store.enablePush()
        advanceUntilIdle()
        assertEquals(0, idle.wire.requests.size)
        assertEquals(0, idle.push.asked, "the push service isn't asked for an address")
        assertTrue(idle.store.state.value.configured)
        // An alert but no server.
        val off = rig(registered = false, builtIn = null)
        off.store.resume()
        off.store.refreshStatus(force = true)
        advanceUntilIdle()
        assertEquals(0, off.wire.requests.size)
        assertEquals(0, off.push.asked)
        assertFalse(off.store.state.value.configured)
    }

    @Test fun savingAnAlertRegistersThePhoneAndAsksWhatTfLSays() = runTest {
        val rig = rig(alerts = emptyList(), registered = false)
        rig.store.save(district)
        advanceUntilIdle()
        assertEquals(1, rig.push.asked)
        val put = rig.wire.requests.single { it.method == "PUT" }
        assertEquals("https://built.example.com/v1/devices/$ID", put.url)
        assertEquals("Bearer built-token", put.headers["Authorization"])
        assertEquals("Polaris/test", put.headers["User-Agent"])
        assertFalse(put.followRedirects)
        assertEquals(AlertStore.MAX_RESPONSE.toLong(), put.maxBodyBytes)
        val body = HTTP.json.parseToJsonElement(put.body!!.decodeToString()).jsonObject
        assertEquals("production", body.getValue("environment").jsonPrimitive.content)
        assertEquals("Europe/London", body.getValue("timeZone").jsonPrimitive.content)
        assertEquals("District line", body.getValue("alerts").jsonArray[0].jsonObject.getValue("name").jsonPrimitive.content, "the name sent is filled in")
        // The secret: random, URL-safe, long enough for the server, and only on the phone's own routes.
        val secret = assertNotNull(put.headers["X-Device-Secret"])
        assertTrue(Regex("[A-Za-z0-9_-]{32,128}").matches(secret), secret)
        val status = rig.wire.requests.single { it.url.endsWith("/v1/status") }
        assertNull(status.headers["X-Device-Secret"])
        val state = rig.store.state.value
        assertTrue(state.registered)
        assertEquals(true, state.serverCanPush)
        assertNull(state.syncTrouble)
        assertEquals(iso("2026-11-24T12:00:00Z"), state.checkedAt)
        // Kept for the next launch.
        assertEquals(listOf(district), RouteAlert.decodeList(rig.values.getString(AlertStore.ALERTS)))
        assertEquals(ID, rig.values.getString(AlertStore.REGISTERED))
    }

    @Test fun aLaunchResendsOnceHoweverOftenTheScreenIsMadeAnew() = runTest {
        val rig = rig()
        rig.store.resume()
        advanceUntilIdle()
        // A rotation, a fold, a notice tapped: the screen asks again; nothing more goes out.
        rig.store.resume()
        rig.store.resume()
        advanceUntilIdle()
        assertEquals(1, rig.push.asked)
        assertEquals(listOf("PUT /v1/devices/$ID"), rig.wire.said("built.example.com"))
        // With no alerts at launch it stays ready for the first one saved.
        val empty = rig(alerts = emptyList(), registered = false)
        empty.store.resume()
        empty.store.save(district)
        advanceUntilIdle()
        assertEquals(1, empty.push.asked)
        assertEquals(1, empty.wire.requests.count { it.method == "PUT" })
    }

    /** Deleted with no network: the server must still be told, or it goes on watching and pushing. */
    @Test fun theLastAlertDeletedOfflineIsToldToTheServerLater() = runTest {
        val values = InMemoryKeyValueStore()
        val wire = Wire()
        val first = Rig(this, wire = wire, values = values, alerts = listOf(district)).also { advanceUntilIdle() }
        wire.failing += "built.example.com"
        first.store.delete(district.id)
        advanceUntilIdle()
        assertEquals(AlertStore.Trouble.Unreachable, first.store.state.value.syncTrouble)
        assertEquals(ID, values.getString(AlertStore.REGISTERED), "still owed")
        // The next launch, online: no alerts, but the phone is removed.
        wire.failing.clear()
        wire.requests.clear()
        val next = Rig(this, wire = wire, values = values, alerts = emptyList(), registered = false).also { advanceUntilIdle() }
        next.store.resume()
        advanceUntilIdle()
        assertEquals(listOf("DELETE /v1/devices/$ID"), wire.said("built.example.com"))
        assertNull(values.getString(AlertStore.REGISTERED))
        assertEquals(0, next.push.asked, "nothing is asked of the push service for that")
    }

    @Test fun whatTheServerIsOwedIsTriedAgainOnComingBack() = runTest {
        var now = iso("2026-11-24T12:00:30Z")
        val wire = Wire()
        val values = InMemoryKeyValueStore().apply { putString(AlertStore.ALERTS, RouteAlert.encode(listOf(district))) }
        val secrets = Secrets()
        val push = Push()
        val store = AlertStore(
            AppSettings(secrets, BuildDefaults(alertsUrl = "https://built.example.com", alertsToken = "built-token"), values), secrets, values, wire,
            { "Polaris/test" }, push, this, clock = { now }, syncDelayMillis = 0, io = StandardTestDispatcher(testScheduler),
        )
        wire.failing += "built.example.com"
        store.resume()
        advanceUntilIdle()
        assertEquals(AlertStore.Trouble.Unreachable, store.state.value.syncTrouble)
        val failed = wire.requests.size
        // Back to the front, the network back: sent again, without waiting for the next launch.
        wire.failing.clear()
        store.retryIfOwed()
        advanceUntilIdle()
        assertTrue(store.state.value.registered)
        assertNull(store.state.value.syncTrouble)
        assertEquals(failed + 1, wire.requests.size)
        // Nothing owed: nothing sent, however often it is called.
        store.retryIfOwed(); store.retryIfOwed()
        advanceUntilIdle()
        assertEquals(failed + 1, wire.requests.size)
        // Owed again, but within half a minute of the last try: held.
        wire.failing += "built.example.com"
        store.delete(district.id)
        advanceUntilIdle()
        val before = wire.requests.size
        store.retryIfOwed()
        advanceUntilIdle()
        assertEquals(before, wire.requests.size, "not hammered")
        now = now.plusSeconds(31)
        wire.failing.clear()
        store.retryIfOwed()
        advanceUntilIdle()
        assertEquals("DELETE", wire.requests.last().method)
        assertFalse(store.state.value.registered)
    }

    /** The push service doesn't answer in time, but the address from last time is known: the alerts still go to the server under it. */
    @Test fun aSlowPushServiceDoesNotStopTheAlertsBeingSent() = runTest {
        val push = Push().apply { gate = kotlinx.coroutines.CompletableDeferred() }
        val rig = rig(push = push)
        rig.store.resume()
        advanceUntilIdle()
        assertTrue(rig.store.state.value.pushFailed)
        assertEquals(listOf("PUT /v1/devices/$ID"), rig.wire.said("built.example.com"))
    }

    /** What TfL says isn't held back by the push service. */
    @Test fun savingAsksTfLWithoutWaitingForThePushService() = runTest {
        val push = Push().apply { gate = kotlinx.coroutines.CompletableDeferred() }
        val rig = rig(alerts = emptyList(), registered = false, push = push)
        rig.store.save(district)
        testScheduler.advanceTimeBy(1_000)
        assertEquals(listOf("POST /v1/status"), rig.wire.said("built.example.com"), "asked at once")
        assertNotNull(rig.store.state.value.checkedAt)
        push.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(rig.store.state.value.registered)
    }

    /** The secure store's key was lost: a new secret, the same push address, and the server refuses the phone for good. The address is given up for a new one, and the person is told. */
    @Test fun aLostSecretRegistersThePhoneAfresh() = runTest {
        val wire = Wire()
        // The server knows ID under the old secret: anything for it is refused.
        wire.statusFor = { request -> if ("/v1/devices/$ID" in request.url) 403 else null }
        val rig = rig(wire = wire)
        rig.store.resume()
        advanceUntilIdle()
        assertEquals(1, rig.push.resets, "the installation is given up and made again")
        assertEquals(listOf("PUT /v1/devices/$ID", "PUT /v1/devices/$AFRESH"), wire.said("built.example.com").filter { it.startsWith("PUT") })
        val state = rig.store.state.value
        assertTrue(state.registered)
        assertTrue(state.reRegistered, "said to the person")
        assertNull(state.syncTrouble)
        assertEquals(AFRESH, rig.values.getString(AlertStore.REGISTERED))
        rig.store.acknowledgeReRegistered()
        assertFalse(rig.store.state.value.reRegistered)
        // A server that refuses the new address too isn't asked for ever.
        wire.statusFor = { request -> if ("/v1/devices/" in request.url) 403 else null }
        rig.store.didRegister(AFRESH)
        advanceUntilIdle()
        assertEquals(1, rig.push.resets)
        assertEquals(AlertStore.Trouble.Said("This phone is registered by another install of Polaris"), rig.store.state.value.syncTrouble)
    }

    @Test fun aLostSecretsNoticeIsCleared() = runTest {
        val secrets = object : SecretStore {
            val forgotten = mutableListOf<String>()
            val items = mutableMapOf<String, String>()
            override fun read(account: String): SecretRead = items[account]?.let { SecretRead.Value(it) } ?: if (account.startsWith("alerts.secret.")) SecretRead.Lost else SecretRead.Absent
            override fun write(value: String, account: String): Boolean { items[account] = value; return true }
            override fun forgetLost(account: String) { forgotten += account }
        }
        val store = AlertStore(AppSettings(secrets), secrets, InMemoryKeyValueStore(), Wire(), { "Polaris/test" }, Push(), this, io = StandardTestDispatcher(testScheduler))
        val server = AlertServer("https://built.example.com", "t", true)
        val made = store.deviceSecret(server)
        assertEquals(listOf(AlertStore.secretAccount(server)), secrets.forgotten)
        assertEquals(made, store.deviceSecret(server))
        advanceUntilIdle()
    }

    /** Changing server is the store's own work: it finishes though the screen that asked has gone. */
    @Test fun changingServerFinishesThoughTheScreenThatAskedIsGone() = runTest {
        val rig = rig()
        val screen = kotlinx.coroutines.CoroutineScope(StandardTestDispatcher(testScheduler) + kotlinx.coroutines.Job())
        screen.launch { rig.store.useServer("https://mine.example.org", "my-token") }
        testScheduler.runCurrent()
        screen.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        advanceUntilIdle()
        assertEquals("DELETE", rig.wire.to("built.example.com").single().method, "the server left is told")
        assertTrue(rig.wire.to("mine.example.org").any { it.method == "PUT" }, "and the new one given the alerts")
        assertEquals("mine.example.org", rig.store.state.value.serverLabel)
    }

    /** One alert over the server's limits makes it refuse every alert of the phone: nothing over them is saved or sent. */
    @Test fun whatIsSavedAndSentKeepsToTheServersLimits() = runTest {
        val rig = rig(alerts = emptyList(), registered = false)
        val line = district.segments[0]
        val big = RouteAlert(
            id = "BIG", name = "n".repeat(300),
            segments = (1..12).map { line.copy(line = RouteAlert.Line("line$it", "Line $it")) },
            windows = (0 until 12).map { RouteAlert.TimeWindow(it * 60, it * 60 + 30) },
        )
        assertTrue(rig.store.save(big))
        advanceUntilIdle()
        val saved = rig.store.state.value.alerts.single()
        assertEquals(RouteAlert.MAX_SEGMENTS, saved.segments.size)
        assertEquals(RouteAlert.MAX_WINDOWS, saved.windows.size)
        assertEquals(RouteAlert.MAX_NAME, saved.name.length)
        // A default name made of many long line names is cut as it is sent.
        val long = RouteAlert(id = "LONG", segments = (1..8).map { line.copy(line = RouteAlert.Line("l$it", "A very long line name number $it")) })
        assertTrue(long.displayName.length > RouteAlert.MAX_NAME)
        assertEquals(RouteAlert.MAX_NAME, long.wire.name.length)
        // Thirty alerts a phone: the thirty-first isn't saved; an edit to one of the thirty is.
        (2..RouteAlert.MAX_ALERTS).forEach { assertTrue(rig.store.save(district.copy(id = "A$it"))) }
        assertTrue(rig.store.state.value.isFull)
        assertFalse(rig.store.save(district.copy(id = "ONE-TOO-MANY")))
        assertTrue(rig.store.save(district.copy(id = "A2", name = "Edited")))
        advanceUntilIdle()
        assertEquals(RouteAlert.MAX_ALERTS, rig.store.state.value.alerts.size)
        val sent = HTTP.json.parseToJsonElement(rig.wire.requests.last { it.method == "PUT" }.body!!.decodeToString()).jsonObject.getValue("alerts").jsonArray
        assertEquals(RouteAlert.MAX_ALERTS, sent.size)
    }

    @Test fun deletingTheLastAlertRemovesThePhoneFromTheServer() = runTest {
        val rig = rig()
        rig.store.delete(district.id)
        advanceUntilIdle()
        assertEquals(listOf("DELETE /v1/devices/$ID"), rig.wire.said("built.example.com"))
        assertFalse(rig.store.state.value.registered)
        assertNull(rig.values.getString(AlertStore.REGISTERED))
        assertEquals(emptyList(), rig.store.state.value.alerts)
    }

    @Test fun aNewPushAddressReplacesTheOldOneOnTheServer() = runTest {
        val rig = rig()
        rig.store.didRegister("bmV3LWlkLTAwMDAwMDAwMDE")
        advanceUntilIdle()
        assertEquals(listOf("DELETE /v1/devices/$ID", "PUT /v1/devices/bmV3LWlkLTAwMDAwMDAwMDE"), rig.wire.said("built.example.com"))
        assertTrue(rig.store.state.value.registered)
    }

    @Test fun aBuildWithNoPushServiceStillShowsWhatTfLSaysAndRegistersNothing() = runTest {
        val rig = rig(registered = false, push = Push(PushAvailability.notSetUp, id = null))
        rig.store.resume()
        rig.store.save(district.copy(name = "Work"))
        advanceUntilIdle()
        assertEquals(0, rig.push.asked)
        assertEquals(listOf("POST /v1/status"), rig.wire.said("built.example.com").distinct())
        val state = rig.store.state.value
        assertEquals(PushAvailability.notSetUp, state.push)
        assertFalse(state.registered)
        assertFalse(state.pushFailed, "not a failure: there is nothing to ask")
        assertIs<AlertStore.TestResult.NotRegistered>(rig.store.sendTest())
    }

    @Test fun aPushServiceThatGivesNoAddressIsSaidAndTriedAgainNextTime() = runTest {
        val push = Push(id = null)
        val rig = rig(alerts = emptyList(), registered = false, push = push)
        rig.store.save(district)
        advanceUntilIdle()
        assertTrue(rig.store.state.value.pushFailed)
        assertFalse(rig.wire.requests.any { it.method == "PUT" })
        push.id = ID
        rig.store.resume()
        advanceUntilIdle()
        assertFalse(rig.store.state.value.pushFailed)
        assertTrue(rig.store.state.value.registered)
    }

    @Test fun aPushServiceThatNeverAnswersIsSaidAfterHalfAMinuteAndItsLateAnswerStillRegisters() = runTest {
        val silent = object : PushRegistrar {
            override val availability = PushAvailability.ready
            override suspend fun register(): String = kotlinx.coroutines.awaitCancellation()
        }
        val wire = Wire()
        val values = InMemoryKeyValueStore().apply { putString(AlertStore.ALERTS, RouteAlert.encode(listOf(district))) }
        val secrets = Secrets()
        val store = AlertStore(
            AppSettings(secrets, BuildDefaults(alertsUrl = "https://built.example.com", alertsToken = "built-token"), values), secrets, values, wire,
            { "Polaris/test" }, silent, this, syncDelayMillis = 0, io = StandardTestDispatcher(testScheduler),
        )
        store.resume()
        testScheduler.advanceTimeBy(29_000)
        assertFalse(store.state.value.pushFailed, "still waiting")
        assertFalse(store.state.value.registered)
        testScheduler.advanceTimeBy(2_000)
        assertTrue(store.state.value.pushFailed, "said, with Try again")
        assertFalse(wire.requests.any { it.method == "PUT" })
        // The service answers at last, through its own callback.
        store.didRegister(ID)
        advanceUntilIdle()
        assertTrue(store.state.value.registered)
        assertFalse(store.state.value.pushFailed)
    }

    @Test fun theServerSayingItCannotPushToThisPhoneIsKept() = runTest {
        val wire = Wire().apply { putReply = """{"ok":true,"alerts":1,"pushes":false}""" }
        val rig = rig(registered = false, wire = wire)
        rig.store.resume()
        advanceUntilIdle()
        assertTrue(rig.store.state.value.registered)
        assertEquals(false, rig.store.state.value.serverCanPush)
        // A server from before Android phones doesn't say: unknown, not "no".
        wire.putReply = """{"ok":true,"alerts":1}"""
        rig.store.didRegister(ID)
        advanceUntilIdle()
        assertNull(rig.store.state.value.serverCanPush)
    }

    @Test fun aTokenOnlyGoesToItsOwnAddress() = runTest {
        val rig = rig()
        val result = rig.store.useServer("https://mine.example.org", "my-token")
        advanceUntilIdle()
        assertEquals(AlertStore.UseResult.Used(), result)
        // The old server is told to forget the phone, with its own token and its own secret.
        val left = rig.wire.to("built.example.com").single()
        assertEquals("DELETE", left.method)
        assertEquals("Bearer built-token", left.headers["Authorization"])
        val mine = rig.wire.to("mine.example.org")
        assertTrue(mine.isNotEmpty())
        assertTrue(mine.all { it.headers["Authorization"] == "Bearer my-token" })
        assertEquals(setOf("PUT /v1/devices/$ID", "POST /v1/status"), rig.wire.said("mine.example.org").toSet())
        // Each server has its own secret.
        val secretThere = mine.first { it.method == "PUT" }.headers["X-Device-Secret"]
        assertNotNull(secretThere)
        assertEquals(2, rig.secrets.items.keys.count { it.startsWith("alerts.secret.") })
        assertNotEquals(AlertStore.secretAccount(AlertServer("https://built.example.com", "t", true)), AlertStore.secretAccount(AlertServer("https://mine.example.org", "t", false)))
        assertEquals("mine.example.org", rig.store.state.value.serverLabel)
        assertFalse(rig.store.state.value.serverIsBuiltIn)
        // And back: an empty address is the built-in server again.
        rig.wire.requests.clear()
        assertEquals(AlertStore.UseResult.Used(), rig.store.useServer("", ""))
        advanceUntilIdle()
        assertEquals("DELETE", rig.wire.to("mine.example.org").single().method)
        assertTrue(rig.wire.to("built.example.com").any { it.method == "PUT" })
        assertTrue(rig.store.state.value.serverIsBuiltIn)
    }

    @Test fun aFailedRemovalIsSaid() = runTest {
        val rig = rig()
        rig.wire.failing += "built.example.com"
        val result = rig.store.useServer("https://mine.example.org", "my-token")
        advanceUntilIdle()
        assertEquals(AlertStore.UseResult.Used(couldNotLeave = "built.example.com"), result)
        assertTrue(rig.wire.to("mine.example.org").any { it.method == "PUT" }, "the new server is still used")
    }

    @Test fun whatIsTypedIsCheckedBeforeAnythingIsSent() = runTest {
        val rig = rig()
        assertEquals(AlertStore.UseResult.Refused(AlertServer.Problem.notHTTPS), rig.store.useServer("http://mine.example.org", "t"))
        assertEquals(AlertStore.UseResult.Refused(AlertServer.Problem.hasCredentials), rig.store.useServer("https://me:secret@mine.example.org", "t"))
        assertEquals(AlertStore.UseResult.Refused(AlertServer.Problem.notAnAddress), rig.store.useServer("mine", "t"))
        assertEquals(AlertStore.UseResult.NoToken, rig.store.useServer("https://mine.example.org", "  "))
        advanceUntilIdle()
        assertEquals(0, rig.wire.requests.size)
        assertTrue(rig.store.state.value.serverIsBuiltIn)
    }

    @Test fun theBuiltInAddressTypedDifferentlyIsTheSameServer() = runTest {
        val rig = rig()
        assertEquals(AlertStore.UseResult.Used(), rig.store.useServer(" https://BUILT.example.com:443/ ", "another-token"))
        advanceUntilIdle()
        assertFalse(rig.wire.requests.any { it.method == "DELETE" }, "the same server isn't told to forget the phone")
        assertTrue(rig.wire.requests.filter { it.method == "PUT" }.all { it.headers["Authorization"] == "Bearer another-token" })
    }

    @Test fun aStoreThatWillNotKeepTheServerChangesNothing() = runTest {
        val rig = rig()
        rig.secrets.failingWrites += AlertServer.ACCOUNT
        assertEquals(AlertStore.UseResult.NotSaved, rig.store.useServer("https://mine.example.org", "my-token"))
        advanceUntilIdle()
        assertEquals(0, rig.wire.to("mine.example.org").size)
        assertFalse(rig.wire.requests.any { it.method == "DELETE" }, "the phone stays on the server it was on")
        assertTrue(rig.store.state.value.serverIsBuiltIn)
    }

    @Test fun aLockedStoreMeansWaitNotAnotherServer() = runTest {
        val secrets = Secrets()
        val settings = AppSettings(secrets)
        settings.useAlertsServer("https://mine.example.org", "my-token")
        secrets.locked += AlertServer.ACCOUNT
        val rig = rig(secrets = secrets)
        rig.store.resume()
        rig.store.refreshStatus(force = true)
        advanceUntilIdle()
        assertEquals(0, rig.wire.requests.size, "never the built-in server while the entered one can't be read")
        assertFalse(rig.store.state.value.configured)
        // Unlocked: it comes back by itself.
        secrets.locked.clear()
        rig.store.refreshStatus(force = true)
        advanceUntilIdle()
        assertEquals(listOf("POST /v1/status"), rig.wire.said("mine.example.org"))
    }

    @Test fun aLockedSecretIsNeverReplaced() = runTest {
        val rig = rig()
        val server = AlertServer("https://built.example.com", "built-token", true)
        val first = rig.store.deviceSecret(server)
        assertEquals(first, rig.store.deviceSecret(server), "the same one each time")
        rig.secrets.locked += AlertStore.secretAccount(server)
        assertTrue(runCatching { rig.store.deviceSecret(server) }.exceptionOrNull() is AlertStore.AlertError.Locked)
        assertEquals(first, rig.secrets.items[AlertStore.secretAccount(server)])
        // And the request that needed it isn't sent; the page is told why.
        rig.store.didRegister(ID)
        advanceUntilIdle()
        assertFalse(rig.wire.requests.any { it.method == "PUT" })
        assertEquals(AlertStore.Trouble.Locked, rig.store.state.value.syncTrouble)
    }

    @Test fun aSecretThatWillNotSaveIsNotUsed() = runTest {
        val rig = rig(registered = false)
        rig.secrets.failingWrites += "alerts.secret.*"
        rig.store.resume()
        advanceUntilIdle()
        assertFalse(rig.wire.requests.any { it.method == "PUT" })
        assertFalse(rig.store.state.value.registered)
    }

    @Test fun theServersOwnWordsAreShownKeptShort() = runTest {
        val wire = Wire().apply { status = 503; putReply = """{"error":"This alert server has no room for another phone"}""" }
        val rig = rig(registered = false, wire = wire)
        rig.store.resume()
        advanceUntilIdle()
        assertEquals(AlertStore.Trouble.Said("This alert server has no room for another phone"), rig.store.state.value.syncTrouble)
        assertFalse(rig.store.state.value.registered)
        // A test the push service refuses says its reason; anything else, the status; never more than 200 characters.
        val there = rig(wire = wire)
        wire.status = 502
        wire.reply = """{"status":404,"reason":"UNREGISTERED","gone":true}"""
        assertEquals(AlertStore.TestResult.Said("The push service refused it: UNREGISTERED"), there.store.sendTest())
        wire.reply = "<html>bad gateway</html>"
        assertEquals(AlertStore.TestResult.Said("The alert server said 502."), there.store.sendTest())
        wire.reply = """{"error":"${"x".repeat(5000)}"}"""
        assertEquals(200, (there.store.sendTest() as AlertStore.TestResult.Said).message.length)
        wire.status = 200
        assertEquals(AlertStore.TestResult.Sent, there.store.sendTest())
        assertEquals("POST /v1/devices/$ID/test", there.wire.said("built.example.com").last())
    }

    @Test fun noRedirects() = runTest {
        val wire = Wire().apply { status = 302 }
        val rig = rig(wire = wire)
        rig.store.refreshStatus(force = true)
        advanceUntilIdle()
        assertTrue(rig.store.state.value.statusFailed)
        assertTrue(wire.requests.all { !it.followRedirects })
    }

    @Test fun anOversizedReplyIsRefused() = runTest {
        val wire = Wire().apply { reply = """{"checkedAt":"2026-11-24T12:00:00Z","alerts":[],"padding":"${"x".repeat(AlertStore.MAX_RESPONSE)}"}""" }
        val rig = rig(wire = wire)
        rig.store.refreshStatus(force = true)
        advanceUntilIdle()
        assertTrue(rig.store.state.value.statusFailed)
        assertNull(rig.store.state.value.checkedAt)
    }

    @Test fun tflIsAskedAtMostOnceAMinuteUnlessForced() = runTest {
        val wire = Wire().apply { reply = """{"checkedAt":"2026-11-24T12:00:00Z","alerts":[{"id":"DISTRICT","issues":[{"severity":"delays","title":"District line: severe delays","detail":"Signal failure."}],"upcoming":[]}]}""" }
        val rig = rig(wire = wire)
        rig.store.refreshStatus()
        rig.store.refreshStatus()
        advanceUntilIdle()
        assertEquals(1, wire.requests.count { it.url.endsWith("/status") })
        assertEquals("District line: severe delays", rig.store.state.value.status["DISTRICT"]?.issues?.single()?.title)
        rig.store.refreshStatus(force = true)
        advanceUntilIdle()
        assertEquals(2, wire.requests.count { it.url.endsWith("/status") })
        // Offline: what was last heard stays, with the failure said.
        wire.failing += "built.example.com"
        rig.store.refreshStatus(force = true)
        advanceUntilIdle()
        assertTrue(rig.store.state.value.statusFailed)
        assertNotNull(rig.store.state.value.status["DISTRICT"])
    }

    /** While the phone is being registered afresh, the push service announces the new address and the app comes to the front: neither may leave it stuck. */
    @Test fun registeringAfreshSurvivesWhatHappensMeanwhile() = runTest {
        val wire = Wire()
        wire.statusFor = { request -> if ("/v1/devices/$ID" in request.url) 403 else null }
        val push = Push()
        val rig = rig(wire = wire, push = push)
        push.duringReset = {
            rig.store.didRegister(AFRESH) // PushService.onRegistered
            rig.store.retryIfOwed()       // the app in front, the network back
        }
        rig.store.resume()
        advanceUntilIdle()
        assertEquals(1, push.resets)
        val state = rig.store.state.value
        assertTrue(state.registered, "the new address is registered")
        assertNull(state.syncTrouble)
        assertTrue(state.reRegistered)
        assertEquals(AFRESH, rig.values.getString(AlertStore.PUSH_ID))
        assertEquals(AFRESH, rig.values.getString(AlertStore.REGISTERED))
        assertEquals("PUT /v1/devices/$AFRESH", wire.said("built.example.com").last { !it.endsWith("/status") })
    }

    /** The push service gave a new address by itself and the secret was lost too: the old address can't be removed, and that doesn't stop the new one being registered. */
    @Test fun anOldAddressThatCannotBeRemovedDoesNotBlockTheNewOne() = runTest {
        val wire = Wire()
        wire.statusFor = { request -> if ("/v1/devices/$ID" in request.url) 403 else null }
        val rig = rig(wire = wire)
        rig.store.didRegister(AFRESH)
        advanceUntilIdle()
        assertEquals(listOf("DELETE /v1/devices/$ID", "PUT /v1/devices/$AFRESH"), wire.said("built.example.com"))
        assertTrue(rig.store.state.value.registered)
        assertNull(rig.store.state.value.syncTrouble)
        assertEquals(0, rig.push.resets)
    }

    /** A 403 from something in front of the server (a proxy, a firewall) is not the server saying the phone is another install's. */
    @Test fun a403ThatIsNotTheServersOwnChangesNothing() = runTest {
        val wire = Wire().apply { refusal = "<html>Forbidden</html>"; statusFor = { request -> if ("/v1/devices/" in request.url) 403 else null } }
        val rig = rig(wire = wire)
        rig.store.resume()
        advanceUntilIdle()
        assertEquals(0, rig.push.resets, "the Firebase installation is kept")
        assertEquals(ID, rig.values.getString(AlertStore.PUSH_ID))
        assertEquals(AlertStore.Trouble.Said("The alert server said 403."), rig.store.state.value.syncTrouble)
        // A server from before the code says it in its words alone.
        val old = Wire().apply { refusal = """{"error":"This phone is registered by another install of Polaris"}"""; statusFor = { request -> if ("/v1/devices/$ID" in request.url) 403 else null } }
        val before = rig(wire = old)
        before.store.resume()
        advanceUntilIdle()
        assertEquals(1, before.push.resets)
    }

    /** The installation is deleted but no new address comes in time: the phone isn't left on a dead address; the next try gets the new one and registers it. */
    @Test fun registeringAfreshThatGetsNoAnswerIsPickedUpLater() = runTest {
        val wire = Wire()
        wire.statusFor = { request -> if ("/v1/devices/$ID" in request.url) 403 else null }
        val push = Push()
        val rig = rig(wire = wire, push = push)
        push.duringReset = { kotlinx.coroutines.awaitCancellation() } // never answers
        rig.store.resume()
        advanceUntilIdle()
        assertTrue(rig.store.state.value.pushFailed, "said, with Try again")
        assertNotNull(rig.store.state.value.syncTrouble)
        assertEquals(1, push.resets, "not tried round and round")
        // In front again: the push service now gives the new installation's address.
        push.duringReset = {}
        push.id = AFRESH
        rig.store.enablePush()
        advanceUntilIdle()
        assertTrue(rig.store.state.value.registered)
        assertEquals(AFRESH, rig.values.getString(AlertStore.REGISTERED))
        assertNull(rig.store.state.value.syncTrouble)
        assertEquals("PUT /v1/devices/$AFRESH", wire.said("built.example.com").last { !it.endsWith("/status") })
    }

    /** Told only when the server in use becomes another: not at launch, and not for a new token on the same address. */
    @Test fun aChangedServerIsSaidOnce() = runTest {
        var changed = 0
        val secrets = Secrets(); val values = InMemoryKeyValueStore(); val wire = Wire()
        val settings = AppSettings(secrets, BuildDefaults(alertsUrl = "https://built.example.com", alertsToken = "built-token"), values)
        val store = AlertStore(settings, secrets, values, wire, { "Polaris/test" }, Push(), this, syncDelayMillis = 0, io = StandardTestDispatcher(testScheduler), onServerChanged = { changed++ })
        advanceUntilIdle()
        store.resume()
        advanceUntilIdle()
        assertEquals(0, changed, "nothing at launch")
        store.useServer("https://BUILT.example.com/", "another-token")
        assertEquals(0, changed, "the same server")
        store.useServer("https://mine.example.org", "my-token")
        assertEquals(1, changed)
        store.useServer("", "")
        assertEquals(2, changed, "back to the built-in one")
    }

    /** One request out at a time, and what is sent last is the latest. */
    @Test fun changesWhileARequestIsOutAreSentAfterItInOrder() = runTest {
        val wire = Wire()
        val rig = rig(wire = wire)
        wire.gate = kotlinx.coroutines.CompletableDeferred()
        rig.store.save(district.copy(name = "One"))
        advanceUntilIdle()
        rig.store.save(district.copy(name = "Two"))
        rig.store.save(district.copy(name = "Three"))
        advanceUntilIdle()
        assertEquals(1, wire.requests.count { it.method == "PUT" }, "the first is still out; nothing else is sent beside it")
        wire.gate!!.complete(Unit)
        advanceUntilIdle()
        val puts = wire.requests.filter { it.method == "PUT" }
        assertEquals(2, puts.size, "the two changes made meanwhile are one send")
        assertTrue("\"Three\"" in puts.last().body!!.decodeToString())
        assertNull(rig.store.state.value.syncTrouble)
    }

    /** More alerts saved than the server keeps: the first 30 are sent, so it doesn't refuse them all, and the rest are said. */
    @Test fun alertsBeyondTheServersCountAreNotSent() = runTest {
        val many = (1..33).map { district.copy(id = "A$it") }
        val wire = Wire().apply { reply = """{"checkedAt":"2026-11-24T12:00:00Z","alerts":[${(1..30).joinToString(",") { """{"id":"A$it","issues":[],"upcoming":[]}""" }}]}""" }
        val rig = rig(alerts = many, wire = wire)
        rig.store.resume()
        rig.store.refreshStatus(force = true)
        advanceUntilIdle()
        val put = rig.wire.requests.last { it.method == "PUT" }
        assertEquals(30, HTTP.json.parseToJsonElement(put.body!!.decodeToString()).jsonObject["alerts"]!!.jsonArray.size)
        val status = rig.wire.requests.last { it.url.endsWith("/status") }
        assertEquals(30, HTTP.json.parseToJsonElement(status.body!!.decodeToString()).jsonObject["alerts"]!!.jsonArray.size)
        assertEquals(listOf("A31", "A32", "A33"), rig.store.state.value.notWatched.map { it.id })
        // What TfL says is still asked at most once a minute (the three never asked about don't make every look a new question).
        val asked = rig.wire.requests.count { it.url.endsWith("/status") }
        rig.store.refreshStatus()
        advanceUntilIdle()
        assertEquals(asked, rig.wire.requests.count { it.url.endsWith("/status") })
        assertTrue(rig.store.state.value.isFull)
    }

    /** The last alert deleted and the server told: the push address is let go, and the push service isn't set up at the next start. */
    @Test fun theLastAlertGoneLetsThePushAddressGo() = runTest {
        val rig = rig()
        rig.store.delete(district.id)
        advanceUntilIdle()
        assertEquals(listOf("DELETE /v1/devices/$ID"), rig.wire.said("built.example.com"))
        assertEquals(1, rig.push.released)
        assertNull(rig.values.getString(AlertStore.PUSH_ID))
        // Not while the server couldn't be told.
        val offline = rig(wire = Wire().apply { failing += "built.example.com" })
        offline.store.delete(district.id)
        advanceUntilIdle()
        assertEquals(0, offline.push.released)
        assertEquals(ID, offline.values.getString(AlertStore.PUSH_ID))
        // An alert saved again registers again.
        rig.store.save(district)
        advanceUntilIdle()
        assertTrue(rig.store.state.value.registered)
    }

    /** Two switches asked for at once run one after the other; the second starts from what the first left. */
    @Test fun twoServerSwitchesAtOnceRunInTurn() = runTest {
        val rig = rig()
        val first = async { rig.store.useServer("https://one.example.org", "t1") }
        val second = async { rig.store.useServer("https://two.example.org", "t2") }
        advanceUntilIdle()
        assertEquals(AlertStore.UseResult.Used(), first.await())
        assertEquals(AlertStore.UseResult.Used(), second.await())
        assertEquals("two.example.org", rig.store.state.value.serverLabel)
        assertEquals(listOf("DELETE /v1/devices/$ID"), rig.wire.said("built.example.com"))
        assertEquals(1, rig.wire.said("one.example.org").count { it.startsWith("DELETE") }, "the first server is left by the second switch")
        assertEquals("PUT /v1/devices/$ID", rig.wire.said("two.example.org").last { !it.endsWith("/status") })
    }

    companion object {
        /** A Firebase installation ID's shape. */
        const val ID = "cXk3Tm9wQl9aLWFiY2RlZg"
        const val AFRESH = "YWZyZXNoLWFkZHJlc3MtMDE"
    }
}
