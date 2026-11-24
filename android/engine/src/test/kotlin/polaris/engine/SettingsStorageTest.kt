package polaris.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings as they are kept: secrets sealed (a fake cipher here; the real Android Keystore one is
 * tested on a device), ordinary choices under iOS's names, and the same precedence as iOS.
 */
class SettingsStorageTest {
    /** Reversible and recognisable, so a test can see nothing was stored in the clear. */
    private class FakeCipher : SecretCipher {
        var broken = false
        override fun seal(plain: ByteArray): ByteArray {
            if (broken) throw IllegalStateException("no key")
            return byteArrayOf(42) + plain.map { (it.toInt() xor 0x5A).toByte() }
        }
        override fun open(sealed: ByteArray): ByteArray {
            if (broken || sealed.firstOrNull() != 42.toByte()) throw IllegalStateException("can't open")
            return sealed.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private class FailingStore : DurableStore {
        override fun read(key: String): String? = null
        override fun write(key: String, value: String?): Boolean = false
    }

    @Test fun secretsAreSealedAndReadBack() {
        val disk = InMemoryDurableStore()
        val secrets = EncryptedSecretStore(FakeCipher(), disk)
        assertTrue(secrets.write("tfl-key-123", "tfl"))
        assertEquals("tfl-key-123", secrets.read("tfl"))
        val stored = disk.values.getValue("secret.tfl")
        assertFalse("tfl-key-123" in stored)
        assertFalse("tfl-key-123" in String(java.util.Base64.getDecoder().decode(stored)))
    }

    @Test fun anEmptyValueRemovesTheEntry() {
        val disk = InMemoryDurableStore()
        val secrets = EncryptedSecretStore(FakeCipher(), disk)
        secrets.write("abc", "google")
        assertTrue(secrets.write("", "google"))
        assertNull(secrets.read("google"))
        assertTrue(disk.values.isEmpty())
    }

    @Test fun aValueThatCantBeSealedOrWrittenIsNotKept() {
        val cipher = FakeCipher().apply { broken = true }
        val disk = InMemoryDurableStore()
        assertFalse(EncryptedSecretStore(cipher, disk).write("abc", "google"))
        assertTrue(disk.values.isEmpty())
        assertFalse(EncryptedSecretStore(FakeCipher(), FailingStore()).write("abc", "google"))
    }

    @Test fun aValueThatCantBeOpenedReadsAsAbsentAndIsRemoved() {
        val disk = InMemoryDurableStore()
        EncryptedSecretStore(FakeCipher(), disk).write("abc", "google")
        // As after a restore onto another phone: the sealed bytes are there, the key isn't.
        val lost = FakeCipher().apply { broken = true }
        val settings = AppSettings(EncryptedSecretStore(lost, disk), BuildDefaults(googleKey = "built-in"))
        assertEquals("built-in", settings.googleKey)
        assertTrue(disk.values.isEmpty())
        // Garbage that isn't even base64 is the same.
        disk.values["secret.tfl"] = "%%%"
        assertNull(EncryptedSecretStore(FakeCipher(), disk).read("tfl"))
    }

    @Test fun enteredBeatsBuiltInBeatsNone() {
        val secrets = EncryptedSecretStore(FakeCipher(), InMemoryDurableStore())
        val settings = AppSettings(secrets, BuildDefaults(googleKey = " built-g ", tflKey = null, contact = "https://example.org/build"))
        assertEquals("built-g", settings.googleKey)
        assertNull(settings.tflKey)
        assertEquals("https://example.org/build", settings.contact)
        settings.googleKeyOverride = "entered-g"
        settings.tflKeyOverride = "entered-t"
        settings.contactOverride = "me@example.org"
        // A fresh AppSettings over the same store: it was kept, not just held in memory.
        val again = AppSettings(secrets, BuildDefaults(googleKey = "built-g"))
        assertEquals("entered-g", again.googleKey)
        assertEquals("entered-t", again.tflKey)
        assertEquals("me@example.org", again.contact)
        // Blank entered values fall back to the build's.
        again.googleKeyOverride = "   "
        assertEquals("built-g", again.googleKey)
        again.contactOverride = "not a contact"
        assertNull(again.contact)
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
        assertEquals("my-token", secrets.read(AlertServer.TOKEN_ACCOUNT))
        assertEquals("https://mine.example.org", secrets.read(AlertServer.URL_ACCOUNT))

        // Both blank goes back to the built-in one.
        assertTrue(settings.useAlertsServer("", ""))
        assertEquals(true, settings.alertServer?.isBuiltIn)
        // With nothing built in, alerts are off.
        assertNull(AppSettings(InMemorySecretStore()).alertServer)
    }

    @Test fun alertAddressesFollowIOSsRules() {
        assertEquals("https://a.example.org/base", AlertServer.address("https://A.example.org/base/"))
        assertEquals("http://localhost:8787", AlertServer.address("http://localhost:8787"))
        assertEquals("https://a.example.org:8443", AlertServer.address("https://a.example.org:8443"))
        fun problem(text: String) = runCatching { AlertServer.address(text) }.exceptionOrNull()?.let { (it as AlertServer.InvalidAddress).problem }
        assertEquals(AlertServer.Problem.NotHttps, problem("http://a.example.org"))
        assertEquals(AlertServer.Problem.HasCredentials, problem("https://user:pw@a.example.org"))
        assertEquals(AlertServer.Problem.NotAnAddress, problem("https://a.example.org/?token=1"))
        assertEquals(AlertServer.Problem.NotAnAddress, problem("alerts"))
        assertEquals(AlertServer.Problem.NotAnAddress, problem("ftp://a.example.org"))
        // A built-in address that doesn't pass is no server, not a fallback to nothing-checked.
        assertNull(AlertServer.resolve("", "", "http://a.example.org", "t"))
    }
}
