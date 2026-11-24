package polaris.engine

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Settings as they are kept: secrets sealed (a fake cipher here; the real Android Keystore one is
 * tested on a device), ordinary choices under iOS's names, the same precedence as iOS, and
 * unreadable kept apart from absent.
 */
class SettingsStorageTest {
    /** Reversible and recognisable, so a test can see nothing was stored in the clear. */
    private class FakeCipher : SecretCipher {
        /** Fails as a busy or locked Keystore would: maybe later. */
        var busy = false
        /** Fails as a lost key would: never again. */
        var lost = false
        override fun seal(plain: ByteArray): ByteArray {
            if (busy) throw IllegalStateException("busy")
            return byteArrayOf(42) + plain.map { (it.toInt() xor 0x5A).toByte() }
        }
        override fun open(sealed: ByteArray): ByteArray {
            if (lost) throw SecretCipher.KeyLost("gone")
            if (busy) throw IllegalStateException("busy")
            if (sealed.firstOrNull() != 42.toByte()) throw SecretCipher.KeyLost("not ours")
            return sealed.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private class FailingStore : DurableStore {
        override fun read(key: String): String? = null
        override fun write(key: String, value: String?): Boolean = false
    }

    /** A secret store whose writes start failing from the [failFrom]th write after [arm]. */
    private class FlakySecrets : SecretStore {
        val inner = InMemorySecretStore()
        private var writes = 0
        private var failFrom = Int.MAX_VALUE
        fun arm(failFrom: Int) { writes = 0; this.failFrom = failFrom }
        override fun read(account: String) = inner.read(account)
        override fun write(value: String, account: String): Boolean {
            writes++
            return if (writes >= failFrom) false else inner.write(value, account)
        }
    }

    private fun text(read: SecretRead) = (read as? SecretRead.Value)?.text

    @Test fun secretsAreSealedAndReadBack() {
        val disk = InMemoryDurableStore()
        val secrets = EncryptedSecretStore(FakeCipher(), disk)
        assertTrue(secrets.write("tfl-key-123", "tfl"))
        assertEquals("tfl-key-123", text(secrets.read("tfl")))
        val stored = disk.values.getValue("secret.tfl")
        assertFalse("tfl-key-123" in stored)
        assertFalse("tfl-key-123" in String(java.util.Base64.getDecoder().decode(stored)))
    }

    @Test fun anEmptyValueRemovesTheEntry() {
        val disk = InMemoryDurableStore()
        val secrets = EncryptedSecretStore(FakeCipher(), disk)
        secrets.write("abc", "google")
        assertTrue(secrets.write("", "google"))
        assertEquals(SecretRead.Absent, secrets.read("google"))
        assertTrue(disk.values.isEmpty())
    }

    @Test fun aSaveThatFailsIsReportedAndNothingIsKept() {
        val busy = FakeCipher().apply { this.busy = true }
        val disk = InMemoryDurableStore()
        assertFalse(EncryptedSecretStore(busy, disk).write("abc", "google"))
        assertTrue(disk.values.isEmpty())
        val settings = AppSettings(EncryptedSecretStore(FakeCipher(), FailingStore()))
        assertFalse(settings.setGoogleKeyOverride("abc"), "the screen can say it wasn't saved")
        assertFalse(settings.setTflKeyOverride("abc"))
        assertFalse(settings.setContactOverride("me@example.org"))
    }

    @Test fun anUnreadableKeyWaitsAndIsNotReplacedByTheBuilds() {
        val disk = InMemoryDurableStore()
        val cipher = FakeCipher()
        EncryptedSecretStore(cipher, disk).write("entered-g", "google")
        cipher.busy = true
        val settings = AppSettings(EncryptedSecretStore(cipher, disk), BuildDefaults(googleKey = "built-in"))
        assertEquals(SecretRead.Unreadable, EncryptedSecretStore(cipher, disk).read("google"))
        assertNull(settings.googleKey, "not the build's key while the entered one can't be read")
        assertTrue(settings.keysUnreadable)
        // Kept in place: once the Keystore answers, the entered key is back.
        cipher.busy = false
        assertEquals("entered-g", settings.googleKey)
        assertFalse(settings.keysUnreadable)
    }

    @Test fun aLostKeyIsRemovedReportedOnceThenAbsent() {
        val disk = InMemoryDurableStore()
        val cipher = FakeCipher()
        EncryptedSecretStore(cipher, disk).write("entered-t", "tfl")
        cipher.lost = true
        val settings = AppSettings(EncryptedSecretStore(cipher, disk), BuildDefaults(tflKey = "built-in"))
        // Gone for good, so the build's applies, and the person is told.
        assertEquals("built-in", settings.tflKey)
        assertEquals(setOf("tfl"), settings.lostSecrets)
        assertTrue(disk.values.isEmpty())
        assertEquals(SecretRead.Absent, EncryptedSecretStore(cipher, disk).read("tfl"))
        settings.acknowledgeLost()
        assertEquals(emptySet(), settings.lostSecrets)
        // Garbage that isn't even base64 can never be opened either.
        disk.values["secret.tfl"] = "%%%"
        assertEquals(SecretRead.Lost, EncryptedSecretStore(FakeCipher(), disk).read("tfl"))
    }

    @Test fun enteredBeatsBuiltInBeatsNone() {
        val secrets = EncryptedSecretStore(FakeCipher(), InMemoryDurableStore())
        val settings = AppSettings(secrets, BuildDefaults(googleKey = " built-g ", tflKey = null, contact = "https://example.org/build"))
        assertEquals("built-g", settings.googleKey)
        assertNull(settings.tflKey)
        assertEquals("https://example.org/build", settings.contact)
        assertTrue(settings.setGoogleKeyOverride("entered-g"))
        assertTrue(settings.setTflKeyOverride("entered-t"))
        assertTrue(settings.setContactOverride("me@example.org"))
        // A fresh AppSettings over the same store: it was kept, not just held in memory.
        val again = AppSettings(secrets, BuildDefaults(googleKey = "built-g"))
        assertEquals("entered-g", again.googleKey)
        assertEquals("entered-t", again.tflKey)
        assertEquals("me@example.org", again.contact)
        // Blank entered values fall back to the build's; an invalid contact to the project's page.
        again.setGoogleKeyOverride("   ")
        assertEquals("built-g", again.googleKey)
        again.setContactOverride("not a contact")
        assertEquals(Contact.PROJECT, again.contact)
    }

    @Test fun ordinaryChoicesAreKeptUnderIOSsNames() {
        val prefs = InMemoryKeyValueStore()
        val settings = AppSettings(preferences = prefs)
        assertEquals(true, settings.deepSearch)
        assertEquals(SortOrder.best, settings.sortOrder)
        assertEquals(Appearance.system, settings.appearance)
        settings.deepSearch = false
        settings.sortOrder = SortOrder.fewestChanges
        settings.appearance = Appearance.dark
        settings.setEnabled(ProviderID.google, false)
        settings.routeFilter = RouteFilter(maxChanges = 1, avoided = setOf(ModeGroup.bus, ModeGroup.tube))
        assertEquals("false", prefs.getString("deepSearch"))
        assertEquals("fewestChanges", prefs.getString("sortOrder"))
        assertEquals("dark", prefs.getString("appearance"))
        assertEquals("google", prefs.getString("disabledProviders"))
        assertEquals("""{"maxChanges":1,"avoided":["tube","bus"]}""", prefs.getString("routeFilter"))

        val again = AppSettings(preferences = prefs)
        assertEquals(false, again.deepSearch)
        assertEquals(SortOrder.fewestChanges, again.sortOrder)
        assertEquals(Appearance.dark, again.appearance)
        assertFalse(again.isEnabled(ProviderID.google))
        assertEquals(RouteFilter(maxChanges = 1, avoided = setOf(ModeGroup.bus, ModeGroup.tube)), again.routeFilter)
    }

    @Test fun storedValuesThisVersionCantReadFallBackToTheDefaults() {
        val prefs = InMemoryKeyValueStore()
        prefs.putString("sortOrder", "quickest")
        prefs.putString("deepSearch", "maybe")
        prefs.putString("appearance", "sepia")
        prefs.putString("disabledProviders", "google,citymapper")
        prefs.putString("routeFilter", """{"maxWalkMinutes":10,"avoided":["bus","spaceship"]}""")
        val settings = AppSettings(preferences = prefs)
        assertEquals(SortOrder.best, settings.sortOrder)
        assertEquals(true, settings.deepSearch)
        assertEquals(Appearance.system, settings.appearance)
        assertEquals(setOf(ProviderID.google), settings.disabledProviders)
        assertEquals(RouteFilter(maxWalkMinutes = 10, avoided = setOf(ModeGroup.bus)), settings.routeFilter)
        prefs.putString("routeFilter", "{not json")
        assertEquals(RouteFilter(), AppSettings(preferences = prefs).routeFilter)
    }

    @Test fun theAlertsServerGoesAsAPair() {
        val secrets = InMemorySecretStore()
        val builtIn = BuildDefaults(alertsUrl = "https://alerts.example.org/", alertsToken = "built-token")
        val settings = AppSettings(secrets, builtIn)
        assertEquals(AlertServer("https://alerts.example.org", "built-token", isBuiltIn = true), settings.alertServer)

        // An entered address never borrows the built-in token.
        assertFalse(settings.useAlertsServer("https://mine.example.org", ""))
        assertEquals("built-token", settings.alertServer?.token)
        assertTrue(settings.useAlertsServer(" https://Mine.Example.org:443/ ", "my-token"))
        assertEquals(AlertServer("https://mine.example.org", "my-token", isBuiltIn = false), settings.alertServer)
        assertEquals(AppSettings.EnteredServer.Saved("https://mine.example.org", "my-token"), settings.enteredAlertsServer)

        // Both blank goes back to the built-in one.
        assertTrue(settings.useAlertsServer("", ""))
        assertEquals(true, settings.alertServer?.isBuiltIn)
        // With nothing built in, alerts are off.
        assertNull(AppSettings(InMemorySecretStore()).alertServer)
    }

    @Test fun aTokenIsNeverStoredBesideAnotherServersAddressEvenWhenASaveFails() {
        // Moving from server A to B, with the save's first write failing, then its second.
        for (failFrom in listOf(1, 2)) {
            val secrets = FlakySecrets()
            val settings = AppSettings(secrets)
            assertTrue(settings.useAlertsServer("https://a.example.org", "token-a"))
            secrets.arm(failFrom)
            val saved = settings.useAlertsServer("https://b.example.org", "token-b")
            val pair = settings.alertServer.let { it?.url to it?.token }
            val expected = if (saved) "https://b.example.org" to "token-b" else "https://a.example.org" to "token-a"
            assertEquals(expected, pair, "failing from write $failFrom (saved: $saved)")
        }
    }

    @Test fun anUnreadableAlertsServerMeansWaitNotTheBuiltInOne() {
        val disk = InMemoryDurableStore()
        val cipher = FakeCipher()
        val settings = AppSettings(EncryptedSecretStore(cipher, disk), BuildDefaults(alertsUrl = "https://built.example.org", alertsToken = "built-token"))
        assertTrue(settings.useAlertsServer("https://mine.example.org", "my-token"))
        cipher.busy = true
        assertEquals(AppSettings.EnteredServer.Unreadable, settings.enteredAlertsServer)
        assertNull(settings.alertServer, "never the built-in server while the entered one can't be read")
        cipher.busy = false
        assertEquals("https://mine.example.org", settings.alertServer?.url)
    }

    @Test fun typesHoldingKeysOrTokensNeverPrintThem() {
        val build = BuildDefaults(googleKey = "g-secret-1", tflKey = "t-secret-2", contact = "me@example.org",
            alertsUrl = "https://a.example.org", alertsToken = "a-secret-3")
        val secrets = InMemorySecretStore().apply { write("g-secret-4", "google") }
        val settings = AppSettings(secrets, build).apply { useAlertsServer("https://b.example.org", "a-secret-5") }
        val request = HttpRequest(
            "https://api.tfl.gov.uk/Journey/JourneyResults/a/to/b?app_key=t-secret-6",
            headers = mapOf("X-Goog-Api-Key" to "g-secret-7"), body = "g-secret-8".encodeToByteArray(),
        )
        val printed = listOf(
            build.toString(), settings.toString(), settings.alertServer.toString(), settings.enteredAlertsServer.toString(),
            secrets.read("google").toString(), secrets.toString(), request.toString(),
        )
        for (text in printed) assertFalse("secret" in text, text)
        assertTrue("set" in build.toString() && "https://a.example.org" in build.toString(), build.toString())
        assertEquals("HttpRequest(GET https://api.tfl.gov.uk/Journey/JourneyResults/a/to/b)", request.toString())
    }

    @Test fun alertAddressesMatchIOSCaseByCase() {
        val file = HTTP.json.parseToJsonElement(Fixtures.bytes("alert-server-addresses.json").decodeToString()).jsonObject
        val cases = file["cases"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.size > 30)
        for (case in cases) {
            val input = case["input"]!!.jsonPrimitive.content
            val url = case["url"]?.jsonPrimitive?.content
            val outcome = runCatching { AlertServer.address(input) }
            if (url != null) {
                assertEquals(url, outcome.getOrElse { fail("$input failed: ${it.message}") }, input)
            } else {
                val problem = assertIs<AlertServer.InvalidAddress>(outcome.exceptionOrNull(), "$input passed as ${outcome.getOrNull()}").problem
                assertEquals(case["problem"]!!.jsonPrimitive.content, problem.name, input)
            }
        }
        // A built-in address that doesn't pass is no server.
        assertNull(AlertServer.resolve("", "", "http://a.example.org", "t"))
    }
}
