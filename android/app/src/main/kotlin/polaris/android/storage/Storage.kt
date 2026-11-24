package polaris.android.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import polaris.android.BuildConfig
import polaris.engine.AppSettings
import polaris.engine.BuildDefaults
import polaris.engine.DurableStore
import polaris.engine.EncryptedSecretStore
import polaris.engine.GoogleUsage
import polaris.engine.KeyValueStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Ordinary preferences (sources switched off, deep search, sort, filters, appearance, Google's
 * monthly count) in a Preferences DataStore. The engine reads them synchronously, so the file is
 * read once, off the main thread, before anything uses it ([load]); after that reads come from
 * memory and each write goes to memory at once and to disk in the background. Each disk write
 * takes the key's value at the moment it runs, so however the writes are scheduled the file
 * ends up with the latest value (Google's count never goes backwards on disk).
 *
 * Phase 1 kept Google's count in SharedPreferences ("polaris"); it is carried over on first load.
 */
class DataStoreKeyValueStore private constructor(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
) : KeyValueStore {
    private val values = ConcurrentHashMap<String, String>()
    private val writes = Mutex()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
        scope.launch {
            writes.withLock {
                val latest = values[key]
                store.edit { prefs -> if (latest == null) prefs.remove(stringPreferencesKey(key)) else prefs[stringPreferencesKey(key)] = latest }
            }
        }
    }

    companion object {
        private val Context.preferences by preferencesDataStore(
            name = "polaris-preferences",
            produceMigrations = { context -> listOf(SharedPreferencesMigration(context, "polaris")) },
        )

        /** Reads the file (on the IO dispatcher) and returns the store, ready for synchronous use. */
        suspend fun load(context: Context, scope: CoroutineScope): DataStoreKeyValueStore = withContext(Dispatchers.IO) {
            val store = context.applicationContext.preferences
            val prefs = store.data.first()
            DataStoreKeyValueStore(store, scope).apply {
                prefs.asMap().forEach { (key, value) -> if (value is String) values[key.name] = value }
            }
        }
    }
}

/**
 * Where sealed secrets are written: a private SharedPreferences file, with `commit`, so a write
 * says whether it was kept (a key that wasn't saved mustn't be used). Only Keystore-sealed bytes
 * go in it, never a value in the clear.
 */
class SealedPreferences(context: Context) : DurableStore {
    private val prefs = context.applicationContext.getSharedPreferences("polaris-secrets", Context.MODE_PRIVATE)
    override fun read(key: String): String? = prefs.getString(key, null)
    override fun write(key: String, value: String?): Boolean =
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
}

/** The app's settings and counts, built once per process from real storage. */
object Storage {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var ready: Pair<AppSettings, GoogleUsage>? = null

    val buildDefaults: BuildDefaults
        get() = BuildDefaults(
            googleKey = BuildConfig.GOOGLE_ROUTES_API_KEY,
            tflKey = BuildConfig.TFL_APP_KEY,
            contact = BuildConfig.POLARIS_CONTACT,
            alertsUrl = BuildConfig.POLARIS_ALERTS_URL,
            alertsToken = BuildConfig.POLARIS_ALERTS_TOKEN,
        )

    /** Call from a coroutine; the first call reads the files off the main thread. */
    suspend fun settings(context: Context): Pair<AppSettings, GoogleUsage> = mutex.withLock {
        ready ?: run {
            val preferences = DataStoreKeyValueStore.load(context, scope)
            val secrets = EncryptedSecretStore(KeystoreCipher(), SealedPreferences(context))
            (AppSettings(secrets, buildDefaults, preferences) to GoogleUsage(preferences)).also { ready = it }
        }
    }
}
