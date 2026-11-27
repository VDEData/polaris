package polaris.android.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
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
import polaris.engine.Contact
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
        /** The app's own file. Tests make their own DataStore on another file and never use this. */
        const val APP_FILE = "polaris-preferences"

        private val Context.preferences by preferencesDataStore(
            name = APP_FILE,
            produceMigrations = { context -> listOf(SharedPreferencesMigration(context, "polaris")) },
        )

        fun appStore(context: Context): DataStore<Preferences> = context.applicationContext.preferences

        /** Reads the file (on the IO dispatcher) and returns the store, ready for synchronous use. */
        suspend fun load(store: DataStore<Preferences>, scope: CoroutineScope): DataStoreKeyValueStore = withContext(Dispatchers.IO) {
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
 * go in it, never a value in the clear. [file] is the app's own unless a test passes its own.
 */
class SealedPreferences(context: Context, file: String = APP_FILE) : DurableStore {
    private val prefs = context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)
    override fun read(key: String): String? = prefs.getString(key, null)
    override fun write(key: String, value: String?): Boolean =
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()

    companion object {
        const val APP_FILE = "polaris-secrets"
    }
}

/** The app's settings and counts, built once per process from real storage. */
object Storage {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var ready: Pair<AppSettings, GoogleUsage>? = null
    @Volatile private var preferences: KeyValueStore? = null
    @Volatile private var secrets: polaris.engine.SecretStore? = null

    /**
     * `AppSettings.contact`, kept in memory for the map's tile, style and font requests, which
     * mustn't wait on storage or the Keystore. Until settings load it is the build's contact (or
     * the project's page), which is what `AppSettings.contact` gives with nothing entered. It is
     * refreshed when settings load, after a secret is saved, and when the phone is unlocked (an
     * entered contact that couldn't be read before the first unlock is read then).
     */
    @Volatile var contact: String = Contact.valid(BuildConfig.POLARIS_CONTACT) ?: Contact.PROJECT
        private set

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
        ready ?: withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val preferences = DataStoreKeyValueStore.load(DataStoreKeyValueStore.appStore(app), scope)
            val sealed = SealedPreferences(app)
            // Development installs only: the first builds of this storage kept the alerts server
            // as two entries. Nothing reads them; they are removed so no token lingers.
            listOf("secret.alerts.server.url", "secret.alerts.server.token").forEach { if (sealed.read(it) != null) sealed.write(it, null) }
            val secrets = EncryptedSecretStore(KeystoreCipher(), sealed)
            this@Storage.secrets = secrets
            val settings = AppSettings(secrets, buildDefaults, preferences)
            settings.onSecretsChanged = { refreshContact() }
            contact = settings.contact
            ContextCompat.registerReceiver(
                app,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) = refreshContact()
                },
                IntentFilter(Intent.ACTION_USER_PRESENT),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            this@Storage.preferences = preferences
            (settings to GoogleUsage(preferences)).also { ready = it }
        }
    }

    /** The same preferences file, for other small values (saved places). Loads settings first if needed. */
    suspend fun preferences(context: Context): KeyValueStore {
        settings(context)
        return preferences!!
    }

    /** The sealed store the settings use, for the alerts' own secrets. Loads settings first if needed. */
    suspend fun secrets(context: Context): polaris.engine.SecretStore {
        settings(context)
        return secrets!!
    }

    private fun refreshContact() {
        val settings = ready?.first ?: return
        scope.launch { contact = runCatching { settings.contact }.getOrDefault(contact) }
    }
}
