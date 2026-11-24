package polaris.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import polaris.android.storage.DataStoreKeyValueStore
import polaris.android.storage.KeystoreCipher
import polaris.android.storage.SealedPreferences
import polaris.engine.AppSettings
import polaris.engine.BuildDefaults
import polaris.engine.EncryptedSecretStore
import polaris.engine.SortOrder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What only a device can show: the Android Keystore sealing and opening, a lost key reading as
 * absent, and DataStore keeping preferences across a fresh read. Run on an emulator with
 * `./gradlew :app:connectedDebugAndroidTest` (set ANDROID_SERIAL when more than one device is
 * connected: Gradle runs on every one).
 */
@RunWith(AndroidJUnit4::class)
class StorageDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cipher = KeystoreCipher(alias = "polaris.test")

    @After fun tidy() {
        cipher.deleteKey()
        SealedPreferences(context).apply { listOf("google", "tfl").forEach { write("secret.$it", null) } }
    }

    @Test fun theKeystoreSealsAndOpens() {
        val plain = "a key value".encodeToByteArray()
        val sealed = cipher.seal(plain)
        assertFalse(sealed.decodeToString().contains("a key value"))
        assertContentEquals(plain, cipher.open(sealed))
        // A fresh IV each time: the same value never seals to the same bytes.
        assertFalse(sealed.contentEquals(cipher.seal(plain)))
    }

    @Test fun enteredKeysAreKeptSealedAndBeatTheBuild() {
        val disk = SealedPreferences(context)
        val settings = AppSettings(EncryptedSecretStore(cipher, disk), BuildDefaults(googleKey = "built-in"))
        settings.googleKeyOverride = "entered-key"
        val raw = disk.read("secret.google")!!
        assertFalse(raw.contains("entered-key"))
        val again = AppSettings(EncryptedSecretStore(KeystoreCipher(alias = "polaris.test"), SealedPreferences(context)), BuildDefaults(googleKey = "built-in"))
        assertEquals("entered-key", again.googleKey)
    }

    @Test fun aLostKeyReadsAsAbsent() {
        val disk = SealedPreferences(context)
        EncryptedSecretStore(cipher, disk).write("entered-key", "tfl")
        cipher.deleteKey()
        val settings = AppSettings(EncryptedSecretStore(cipher, disk), BuildDefaults(tflKey = "built-in"))
        assertEquals("built-in", settings.tflKey)
        assertNull(disk.read("secret.tfl"))
    }

    @Test fun preferencesSurviveAFreshRead() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = DataStoreKeyValueStore.load(context, scope)
        val settings = AppSettings(preferences = store)
        val before = settings.sortOrder
        val next = if (before == SortOrder.fastest) SortOrder.leastWalking else SortOrder.fastest
        settings.sortOrder = next
        // Writes go to DataStore in the background; DataStore only shows a value once it is on disk.
        delay(500)
        val reread = DataStoreKeyValueStore.load(context, scope)
        assertEquals(next.name, reread.getString(AppSettings.SORT))
        assertTrue(context.filesDir.resolve("datastore/polaris-preferences.preferences_pb").exists())
        settings.sortOrder = before
        delay(300)
    }
}
