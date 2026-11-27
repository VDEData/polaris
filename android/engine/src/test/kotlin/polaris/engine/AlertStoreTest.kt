package polaris.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
        var reply = """{"checkedAt":"2026-11-24T12:00:00Z","alerts":[]}"""
        var putReply = """{"ok":true,"alerts":1,"pushes":true}"""
        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request
            if (request.host in failing) throw java.net.ConnectException("refused")
            return HttpResponse(status, (if (request.method == "PUT") putReply else reply).encodeToByteArray())
        }
        fun to(host: String) = requests.filter { it.host == host }
        fun said(host: String) = to(host).map { "${it.method} ${URI(it.url).path}" }
    }

    class Push(override var availability: PushAvailability = PushAvailability.ready, var id: String? = ID) : PushRegistrar {
        var asked = 0
        override suspend fun register(): String {
            asked++
            return id ?: throw IllegalStateException("no address")
        }
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

    companion object {
        /** A Firebase installation ID's shape. */
        const val ID = "cXk3Tm9wQl9aLWFiY2RlZg"
    }
}
