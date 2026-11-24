package polaris.android

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import polaris.android.storage.DataStoreKeyValueStore
import polaris.android.storage.KeystoreCipher
import polaris.android.storage.SealedPreferences
import polaris.engine.AppSettings
import polaris.engine.BuildDefaults
import polaris.engine.EncryptedSecretStore
import polaris.engine.SecretRead
import polaris.engine.SortOrder
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What only a device can show: the Android Keystore sealing and opening, a lost key, and DataStore
 * keeping preferences on disk. Every test uses its own Keystore alias and its own files, never the
 * app's (`polaris-secrets`, `polaris-preferences`), so running these where Polaris is installed
 * leaves that person's settings alone; the app's files are checked untouched at the end.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest`, with `ANDROID_SERIAL` set when more than one
 * device is connected: Gradle installs on and runs against every connected device.
 */
@RunWith(AndroidJUnit4::class)
class StorageDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cipher = KeystoreCipher(alias = TEST_ALIAS)
    private val appSecrets = File(context.applicationInfo.dataDir, "shared_prefs/${SealedPreferences.APP_FILE}.xml")
    private val appPrefs = context.preferencesDataStoreFile(DataStoreKeyValueStore.APP_FILE)
    private var appSecretsBefore: String? = null
    private var appPrefsBefore: ByteArray? = null

    @Before fun noteTheAppsFiles() {
        appSecretsBefore = appSecrets.takeIf { it.exists() }?.readText()
        appPrefsBefore = appPrefs.takeIf { it.exists() }?.readBytes()
    }

    @After fun tidyAndCheckTheAppsFilesAreUntouched() {
        cipher.deleteKey()
        context.deleteSharedPreferences(TEST_SECRETS)
        context.preferencesDataStoreFile(TEST_PREFS).delete()
        assertEquals(appSecretsBefore, appSecrets.takeIf { it.exists() }?.readText(), "the app's secrets file changed")
        assertContentEquals(appPrefsBefore, appPrefs.takeIf { it.exists() }?.readBytes(), "the app's preferences file changed")
    }

    private fun disk() = SealedPreferences(context, TEST_SECRETS)

    @Test fun theKeystoreSealsAndOpens() {
        val plain = "a key value".encodeToByteArray()
        val sealed = cipher.seal(plain)
        assertFalse(sealed.decodeToString().contains("a key value"))
        assertContentEquals(plain, cipher.open(sealed))
        // A fresh IV each time: the same value never seals to the same bytes.
        assertFalse(sealed.contentEquals(cipher.seal(plain)))
    }

    @Test fun enteredKeysAreKeptSealedAndBeatTheBuild() {
        val settings = AppSettings(EncryptedSecretStore(cipher, disk()), BuildDefaults(googleKey = "built-in"))
        assertTrue(settings.setGoogleKeyOverride("entered-key"))
        val raw = disk().read("secret.google")!!
        assertFalse(raw.contains("entered-key"))
        val again = AppSettings(EncryptedSecretStore(KeystoreCipher(alias = TEST_ALIAS), disk()), BuildDefaults(googleKey = "built-in"))
        assertEquals("entered-key", again.googleKey)
    }

    @Test fun aLostKeyIsDroppedReportedAndANewValueWorks() {
        EncryptedSecretStore(cipher, disk()).write("entered-key", "tfl")
        cipher.deleteKey()
        val settings = AppSettings(EncryptedSecretStore(cipher, disk()), BuildDefaults(tflKey = "built-in"))
        assertEquals("built-in", settings.tflKey)
        assertEquals(setOf("tfl"), settings.lostSecrets)
        assertNull(disk().read("secret.tfl"))
        // A value entered now seals with a new key and reads back.
        assertTrue(settings.setTflKeyOverride("new-key"))
        assertEquals("new-key", settings.tflKey)
    }

    @Test fun bytesSealedByAnotherKeyAreLost() {
        val other = KeystoreCipher(alias = "$TEST_ALIAS.other")
        try {
            disk().write("secret.google", java.util.Base64.getEncoder().encodeToString(other.seal("x".encodeToByteArray())))
            cipher.seal("make our key".encodeToByteArray())
            assertEquals(SecretRead.Lost, EncryptedSecretStore(cipher, disk()).read("google"))
        } finally {
            other.deleteKey()
        }
    }

    @Test fun preferencesAreReadBackFromDiskByAFreshDataStore() = runBlocking {
        val file = context.preferencesDataStoreFile(TEST_PREFS)
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        val settings = AppSettings(preferences = DataStoreKeyValueStore.load(first, firstScope))
        settings.sortOrder = SortOrder.leastWalking
        delay(500)
        // Close the first DataStore (one per file per process), then open the file afresh.
        firstScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        assertTrue(file.exists() && file.length() > 0)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val second = PreferenceDataStoreFactory.create(scope = secondScope) { file }
            val reread = DataStoreKeyValueStore.load(second, secondScope)
            assertEquals("leastWalking", reread.getString(AppSettings.SORT))
            assertEquals(SortOrder.leastWalking, AppSettings(preferences = reread).sortOrder)
        } finally {
            secondScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        }
    }

    private companion object {
        const val TEST_ALIAS = "polaris.test"
        const val TEST_SECRETS = "polaris-test-secrets"
        const val TEST_PREFS = "polaris-test-preferences"
    }
}
