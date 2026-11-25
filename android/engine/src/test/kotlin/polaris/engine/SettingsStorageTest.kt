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
        assertEquals(AppSettings.Save.notSaved, settings.setGoogleKeyOverride("abc"), "the screen can say it wasn't saved")
        assertEquals(AppSettings.Save.notSaved, settings.setTflKeyOverride("abc"))
        assertEquals(AppSettings.Save.notSaved, settings.setContactOverride("me@example.org"))
    }

    @Test fun anUnreadableKeyWaitsAndIsNotReplacedByTheBuilds() {
        val disk = InMemoryDurableStore()
        val cipher = FakeCipher()
        EncryptedSecretStore(cipher, disk).write("entered-g", "google")
        cipher.busy = true
        val settings = AppSettings(EncryptedSecretStore(cipher, disk), BuildDefaults(googleKey = "built-in"))
        assertNull(settings.googleKey, "not the build's key while the entered one can't be read")
        assertEquals(AppSettings.SecretField.Unreadable, settings.googleKeyField, "its own state, not an empty field")
        assertTrue(settings.keysUnreadable)
        // Each read tries again: once the Keystore answers, the entered key is back.
        cipher.busy = false
        assertEquals("entered-g", settings.googleKey)
        assertEquals(AppSettings.SecretField.Entered("entered-g"), settings.googleKeyField)
    }

    @Test fun aBlankSaveCantDeleteAnUnreadableValueButReEnteringReplacesIt() {
        val disk = InMemoryDurableStore()
        val cipher = FakeCipher()
        val settings = AppSettings(EncryptedSecretStore(cipher, disk))
        settings.setGoogleKeyOverride("entered-g")
        cipher.busy = true
        // A settings screen saving its (empty) field on the way out.
        assertEquals(AppSettings.Save.keptUnreadable, settings.setGoogleKeyOverride(""))
        assertTrue("secret.google" in disk.values)
        // Re-entering is the way out (the cipher answers again for the new value).
        cipher.busy = false
        val sealedBefore = disk.values.getValue("secret.google")
        cipher.busy = true
        val stillBusy = settings.setGoogleKeyOverride("new-g")
        assertEquals(AppSettings.Save.notSaved, stillBusy, "sealing needs the Keystore too")
        assertEquals(sealedBefore, disk.values.getValue("secret.google"))
        cipher.busy = false
        assertEquals(AppSettings.Save.saved, settings.setGoogleKeyOverride("new-g"))
        assertEquals("new-g", settings.googleKey)
        // Forgetting on purpose works, readable or not.
        cipher.busy = true
        assertTrue(settings.forget(AppSettings.GOOGLE))
        cipher.busy = false
        assertEquals(AppSettings.SecretField.Empty, settings.googleKeyField)
    }

    @Test fun everySecretReportsWhenItCantBeRead() {
        val disk = InMemoryDurableStore()
        val cipher = FakeCipher()
        val settings = AppSettings(EncryptedSecretStore(cipher, disk))
        settings.setGoogleKeyOverride("g")
        settings.setTflKeyOverride("t")
        settings.setContactOverride("me@example.org")
        settings.useAlertsServer("https://mine.example.org", "tok")
        assertEquals(emptySet(), settings.unreadableSecrets)
        cipher.busy = true
        assertEquals(setOf("google", "tfl", "contact", AlertServer.ACCOUNT), settings.unreadableSecrets)
    }

    @Test fun aLostKeyIsRemovedAndTheNoticeOutlivesTheProcess() {
        val disk = InMemoryDurableStore()
        val cipher = FakeCipher()
        EncryptedSecretStore(cipher, disk).write("entered-t", "tfl")
        cipher.lost = true
        val settings = AppSettings(EncryptedSecretStore(cipher, disk), BuildDefaults(tflKey = "built-in"))
        // Gone for good, so the build's applies; the notice is recorded before the value goes.
        assertEquals("built-in", settings.tflKey)
        assertEquals(setOf("tfl"), settings.lostSecrets)
        assertFalse("secret.tfl" in disk.values)
        assertEquals("1", disk.values["lost.tfl"], "names only, never the value")
        // A new process (new objects over the same file) still has the notice.
        val afterRestart = AppSettings(EncryptedSecretStore(FakeCipher(), disk))
        assertEquals(setOf("tfl"), afterRestart.lostSecrets)
        afterRestart.acknowledgeLost()
        assertEquals(emptySet(), AppSettings(EncryptedSecretStore(FakeCipher(), disk)).lostSecrets)
    }

    @Test fun anyReadPathRecordsTheNotice() {
        val disk = InMemoryDurableStore()
        val cipher = FakeCipher()
        EncryptedSecretStore(cipher, disk).write("g", "google")
        cipher.lost = true
        val settings = AppSettings(EncryptedSecretStore(cipher, disk))
        // Only the "is anything unreadable?" check touches it.
        assertFalse(settings.keysUnreadable)
        assertEquals(setOf("google"), settings.lostSecrets)
    }

    @Test fun aLostNoticeThatCantBeWrittenKeepsTheValue() {
        val disk = object : DurableStore {
            val inner = InMemoryDurableStore()
            override fun read(key: String) = inner.read(key)
            override fun write(key: String, value: String?) = if (key.startsWith("lost.")) false else inner.write(key, value)
        }
        val cipher = FakeCipher()
        EncryptedSecretStore(cipher, disk).write("g", "google")
        cipher.lost = true
        assertEquals(SecretRead.Unreadable, EncryptedSecretStore(cipher, disk).read("google"))
        assertTrue("secret.google" in disk.inner.values, "never deleted without telling")
    }

    @Test fun aValueSavedWhileAReadIsFailingIsNeverDeleted() {
        val disk = InMemoryDurableStore()
        // Between this read's fetch and its decision, another save lands (the cipher fake does it
        // mid-open, then reports the old bytes as lost).
        lateinit var store: EncryptedSecretStore
        val cipher = object : SecretCipher {
            val plain = FakeCipher()
            var interfere = false
            override fun seal(plain: ByteArray) = this.plain.seal(plain)
            override fun open(sealed: ByteArray): ByteArray {
                if (interfere) {
                    interfere = false
                    disk.write("secret.google", java.util.Base64.getEncoder().encodeToString(this.plain.seal("saved meanwhile".encodeToByteArray())))
                    throw SecretCipher.KeyLost("old bytes")
                }
                return this.plain.open(sealed)
            }
        }
        store = EncryptedSecretStore(cipher, disk)
        store.write("old", "google")
        cipher.interfere = true
        assertEquals(SecretRead.Unreadable, store.read("google"))
        assertEquals("saved meanwhile", text(store.read("google")))
        assertEquals(emptySet(), store.lostAccounts())
    }

    @Test fun enteredBeatsBuiltInBeatsNone() {
        val secrets = EncryptedSecretStore(FakeCipher(), InMemoryDurableStore())
        val settings = AppSettings(secrets, BuildDefaults(googleKey = " built-g ", tflKey = null, contact = "https://example.org/build"))
        assertEquals("built-g", settings.googleKey)
        assertNull(settings.tflKey)
        assertEquals("https://example.org/build", settings.contact)
        assertEquals(AppSettings.Save.saved, settings.setGoogleKeyOverride("entered-g"))
        assertEquals(AppSettings.Save.saved, settings.setTflKeyOverride("entered-t"))
        assertEquals(AppSettings.Save.saved, settings.setContactOverride("me@example.org"))
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

    @Test fun savingASecretTellsTheContactCache() {
        var told = 0
        val settings = AppSettings().apply { onSecretsChanged = { told++ } }
        settings.setContactOverride("me@example.org")
        settings.forget(AppSettings.CONTACT)
        assertEquals(2, told)
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
        assertEquals(AppSettings.Save.notSaved, settings.useAlertsServer("https://mine.example.org", ""))
        assertEquals("built-token", settings.alertServer?.token)
        assertEquals(AppSettings.Save.saved, settings.useAlertsServer(" https://Mine.Example.org:443/ ", "my-token"))
        assertEquals(AlertServer("https://mine.example.org", "my-token", isBuiltIn = false), settings.alertServer)
        assertEquals(AppSettings.EnteredServer.Saved("https://mine.example.org", "my-token"), settings.enteredAlertsServer)

        // Both blank goes back to the built-in one.
        assertEquals(AppSettings.Save.saved, settings.useAlertsServer("", ""))
        assertEquals(true, settings.alertServer?.isBuiltIn)
        // With nothing built in, alerts are off.
        assertNull(AppSettings(InMemorySecretStore()).alertServer)
    }

    /**
     * Records the stored pair after every write a save makes, so a moment where a token sits
     * beside another server's address is caught whether or not anything fails.
     */
    @Test fun aTokenIsNeverStoredBesideAnotherServersAddressAtAnyMoment() {
        val inner = InMemorySecretStore()
        lateinit var settings: AppSettings
        val moments = mutableListOf<Pair<String?, String?>>()
        val recording = object : SecretStore {
            override fun read(account: String) = inner.read(account)
            override fun write(value: String, account: String): Boolean = inner.write(value, account).also {
                val reader = AppSettings(inner)
                moments += reader.alertServer.let { s -> s?.url to s?.token }
            }
        }
        settings = AppSettings(recording)
        settings.useAlertsServer("https://a.example.org", "token-a")
        settings.useAlertsServer("https://b.example.org", "token-b")
        val allowed = setOf<Pair<String?, String?>>(null to null, "https://a.example.org" to "token-a", "https://b.example.org" to "token-b")
        assertTrue(moments.all { it in allowed }, "$moments")
        assertEquals("https://b.example.org" to "token-b", moments.last())
    }

    @Test fun aFailedSaveKeepsTheOldPairWhole() {
        val secrets = FlakySecrets()
        val settings = AppSettings(secrets)
        assertEquals(AppSettings.Save.saved, settings.useAlertsServer("https://a.example.org", "token-a"))
        secrets.arm(1)
        assertEquals(AppSettings.Save.notSaved, settings.useAlertsServer("https://b.example.org", "token-b"))
        assertEquals("https://a.example.org" to "token-a", settings.alertServer.let { it?.url to it?.token })
    }

    @Test fun anUnreadableOrUndecodableAlertsServerMeansWaitNotTheBuiltInOne() {
        val disk = InMemoryDurableStore()
        val cipher = FakeCipher()
        val settings = AppSettings(EncryptedSecretStore(cipher, disk), BuildDefaults(alertsUrl = "https://built.example.org", alertsToken = "built-token"))
        assertEquals(AppSettings.Save.saved, settings.useAlertsServer("https://mine.example.org", "my-token"))
        cipher.busy = true
        assertEquals(AppSettings.EnteredServer.Unreadable, settings.enteredAlertsServer)
        assertNull(settings.alertServer, "never the built-in server while the entered one can't be read")
        assertEquals(AppSettings.Save.keptUnreadable, settings.useAlertsServer("", ""), "a blank save can't drop it either")
        cipher.busy = false
        assertEquals("https://mine.example.org", settings.alertServer?.url)
        // A record that opens but doesn't decode is bad data: unreadable too, not "none".
        EncryptedSecretStore(cipher, disk).write("{not a record", AlertServer.ACCOUNT)
        assertEquals(AppSettings.EnteredServer.Unreadable, settings.enteredAlertsServer)
        assertNull(settings.alertServer)
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
            HttpResponse(302, ByteArray(3), location = "https://api.tfl.gov.uk/next?app_key=t-secret-9").toString(),
            EncryptedSecretStore(FakeCipher(), InMemoryDurableStore()).toString(),
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

    /** Switches set on several threads at once over a slow store: none is lost (the read, change and write are one step). */
    @Test fun switchesSetAtOnceAreAllKept() {
        val slow = object : KeyValueStore {
            private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
            override fun getString(key: String): String? { Thread.sleep(5); return values[key] }
            override fun putString(key: String, value: String?) { Thread.sleep(5); if (value == null) values.remove(key) else values[key] = value }
        }
        val settings = AppSettings(preferences = slow)
        val providers = listOf(ProviderID.google, ProviderID.tfl, ProviderID.transitous)
        repeat(10) {
            providers.forEach { settings.setEnabled(it, true) }
            val threads = providers.map { p -> Thread { settings.setEnabled(p, false) } }
            threads.forEach { it.start() }; threads.forEach { it.join() }
            // Read back from the store itself, not the in-memory field.
            kotlin.test.assertEquals(providers.toSet(), AppSettings(preferences = slow).disabledProviders, "round $it")
        }
    }
}
