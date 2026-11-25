package polaris.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Ported from Polaris/Services/AppSettings.swift: the parts the engine needs.

/** What reading a secret found. Unreadable and absent are different answers, as on iOS. */
sealed interface SecretRead {
    data class Value(val text: String) : SecretRead {
        override fun toString() = "Value(redacted)"
    }

    /** Nothing was ever saved, or it was removed. */
    data object Absent : SecretRead

    /** Something is saved but can't be read now (the Keystore busy or locked): wait, use nothing else. */
    data object Unreadable : SecretRead

    /**
     * Something was saved under a key that is proven gone for good. The store has removed it and
     * recorded a notice ([SecretStore.lostAccounts]); the next read is [Absent].
     */
    data object Lost : SecretRead
}

/**
 * Where entered keys live. On iOS it's the Keychain; on Android `EncryptedSecretStore` over the
 * Android Keystore. `InMemorySecretStore` is for tests and previews.
 */
interface SecretStore {
    fun read(account: String): SecretRead
    /** False when it couldn't be kept: a value that wasn't saved mustn't be used. Empty removes. */
    fun write(value: String, account: String): Boolean
    /** Accounts whose value was lost for good and the person hasn't been told yet (kept across launches). */
    fun lostAccounts(): Set<String> = emptySet()
    fun acknowledgeLost() {}
}

class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    override fun read(account: String): SecretRead =
        synchronized(values) { values[account]?.let { SecretRead.Value(it) } ?: SecretRead.Absent }
    override fun write(value: String, account: String): Boolean {
        synchronized(values) { if (value.isEmpty()) values.remove(account) else values[account] = value }
        return true
    }
    override fun toString() = "InMemorySecretStore(${synchronized(values) { values.size }} values)"
}

/**
 * Values a builder can set at build time (`local.properties` on Android, `Secrets.xcconfig` on
 * iOS). The keys are empty in a stranger's build, which works with none of them; the contact
 * defaults to the project's page there (and `contact` falls back to it here).
 */
data class BuildDefaults(
    val googleKey: String? = null,
    val tflKey: String? = null,
    val contact: String? = null,
    val alertsUrl: String? = null,
    val alertsToken: String? = null,
) {
    /** Keys and tokens never reach a log through `toString`; only whether each is set. */
    override fun toString() = "BuildDefaults(googleKey=${set(googleKey)}, tflKey=${set(tflKey)}, contact=$contact, " +
        "alertsUrl=$alertsUrl, alertsToken=${set(alertsToken)})"

    private fun set(value: String?) = if (value.isNullOrBlank()) "none" else "set"
}

/** Light, dark, or whatever the phone is set to. */
@Suppress("EnumEntryName")
enum class Appearance(val label: String) { system("System"), light("Light"), dark("Dark") }

/**
 * The app's settings. Ordinary choices (sources switched off, deep search, sort, filters,
 * appearance) are kept in [preferences] under the names iOS keeps them under in UserDefaults;
 * keys, the contact and the alerts server go to [secrets]. A stored choice this version can't
 * read falls back to the default.
 *
 * Keys entered in settings beat the build-time ones, exactly as on iOS: the entered value if it
 * isn't blank, else the build's, else none. One difference, on purpose: while an entered key is
 * saved but unreadable, that key is treated as missing rather than replaced by the build's
 * ([unreadableSecrets] says which), and the alerts server is off rather than the built-in one
 * used, so nothing goes out with a value the person didn't choose. Reads aren't cached: each one
 * tries the store again (so after a restart or an unlock, a value comes back by itself). A value
 * lost for good is listed in [lostSecrets], across launches, until a screen has told the person.
 */
class AppSettings(
    private val secrets: SecretStore = InMemorySecretStore(),
    private val build: BuildDefaults = BuildDefaults(),
    private val preferences: KeyValueStore = InMemoryKeyValueStore(),
) {
    var disabledProviders: Set<ProviderID> =
        preferences.getString(DISABLED)?.split(",")?.mapNotNull { name -> ProviderID.entries.firstOrNull { it.name == name } }?.toSet() ?: emptySet()
        set(value) {
            field = value
            preferences.putString(DISABLED, ProviderID.entries.filter { it in value }.joinToString(",") { it.name })
        }

    /** Query each source several ways (fewer changes, less walking, mode filters). */
    var deepSearch: Boolean = preferences.getString(DEEP)?.toBooleanStrictOrNull() ?: true
        set(value) {
            field = value
            preferences.putString(DEEP, value.toString())
        }

    /** Results order and filters stick between searches. */
    var sortOrder: SortOrder = preferences.getString(SORT)?.let { name -> SortOrder.entries.firstOrNull { it.name == name } } ?: SortOrder.best
        set(value) {
            field = value
            preferences.putString(SORT, value.name)
        }

    var routeFilter: RouteFilter = preferences.getString(FILTER)?.let(::decodeFilter) ?: RouteFilter()
        set(value) {
            field = value
            preferences.putString(FILTER, encodeFilter(value))
        }

    var appearance: Appearance = preferences.getString(APPEARANCE)?.let { name -> Appearance.entries.firstOrNull { it.name == name } } ?: Appearance.system
        set(value) {
            field = value
            preferences.putString(APPEARANCE, value.name)
        }

    /** Accounts whose saved value was lost for good, until a screen has told the person and called [acknowledgeLost]. */
    val lostSecrets: Set<String> get() = secrets.lostAccounts()

    fun acknowledgeLost() = secrets.acknowledgeLost()

    /** What a settings field shows for one secret. Unreadable is its own state, never an empty field. */
    sealed interface SecretField {
        data object Empty : SecretField
        data class Entered(val text: String) : SecretField {
            override fun toString() = "Entered(redacted)"
        }
        /** Saved but can't be read now: show "saved, can't be read yet", offer to re-enter. */
        data object Unreadable : SecretField
    }

    /** How a save went, for the screen to say so. */
    enum class Save {
        saved,
        /** The secure store couldn't keep it; the old value stands. */
        notSaved,
        /** A blank field over a value that can't be read: refused, so a screen can't delete it by accident. Use [forget]. */
        keptUnreadable,
    }

    private fun field(account: String): SecretField = when (val read = secrets.read(account)) {
        is SecretRead.Value -> if (read.text.isEmpty()) SecretField.Empty else SecretField.Entered(read.text)
        SecretRead.Absent, SecretRead.Lost -> SecretField.Empty
        SecretRead.Unreadable -> SecretField.Unreadable
    }

    /** The entered value, "" when none, or null while it is saved but can't be read. Every read goes through here. */
    private fun entered(account: String): String? = when (val f = field(account)) {
        is SecretField.Entered -> f.text
        SecretField.Empty -> ""
        SecretField.Unreadable -> null
    }

    val googleKeyField: SecretField get() = field(GOOGLE)
    val tflKeyField: SecretField get() = field(TFL)
    /** Who Transitous can reach about this install's requests (User-Agent); empty is the build's. */
    val contactField: SecretField get() = field(CONTACT)

    /**
     * Saves an entered value. A non-blank value always replaces what is there, including a value
     * that can't be read (re-entering is the way out). A blank one removes the value, except over
     * an unreadable one, which it refuses ([Save.keptUnreadable]): removing that takes [forget].
     */
    fun setGoogleKeyOverride(value: String): Save = save(GOOGLE, value)
    fun setTflKeyOverride(value: String): Save = save(TFL, value)
    fun setContactOverride(value: String): Save = save(CONTACT, value)

    /** Removes a saved value on purpose, readable or not. */
    fun forget(account: String): Boolean = secrets.write("", account).also { if (it) onSecretsChanged() }

    /** Called after a secret changes, so a cache of the contact (the map's User-Agent) can refresh. */
    @Volatile var onSecretsChanged: () -> Unit = {}

    private fun save(account: String, value: String): Save {
        if (value.isBlank() && secrets.read(account) == SecretRead.Unreadable) return Save.keptUnreadable
        val kept = secrets.write(if (value.isBlank()) "" else value, account)
        if (kept) onSecretsChanged()
        return if (kept) Save.saved else Save.notSaved
    }

    /** Which secrets are saved but can't be read right now (keys, contact, alerts server). Each read tries again. */
    val unreadableSecrets: Set<String>
        get() = listOf(GOOGLE, TFL, CONTACT).filter { field(it) == SecretField.Unreadable }.toSet() +
            (if (enteredAlertsServer == EnteredServer.Unreadable) setOf(AlertServer.ACCOUNT) else emptySet())

    /** An entered key is saved but can't be read right now. */
    val keysUnreadable: Boolean get() = field(GOOGLE) == SecretField.Unreadable || field(TFL) == SecretField.Unreadable

    /** One key as one search uses it: the value to send (null for none), or saved but unreadable. */
    sealed interface Key {
        data class Use(val value: String?) : Key {
            override fun toString() = "Use(${if (value == null) "none" else "redacted"})"
        }
        data object Unreadable : Key
    }

    data class Keys(val google: Key, val tfl: Key)

    /**
     * Google's and TfL's keys, each read from the store once, so everything one search decides
     * (whether to ask, what to count, what to send) comes from the same read.
     */
    fun readKeys(): Keys {
        fun key(account: String, built: String?): Key = when (val f = field(account)) {
            is SecretField.Entered -> Key.Use(firstNonEmpty(f.text, built))
            SecretField.Empty -> Key.Use(firstNonEmpty(built))
            SecretField.Unreadable -> Key.Unreadable
        }
        return Keys(key(GOOGLE, build.googleKey), key(TFL, build.tflKey))
    }

    val googleKey: String? get() = entered(GOOGLE)?.let { firstNonEmpty(it, build.googleKey) }
    val tflKey: String? get() = entered(TFL)?.let { firstNonEmpty(it, build.tflKey) }
    val hasBuiltInGoogleKey: Boolean get() = firstNonEmpty(build.googleKey) != null
    val hasBuiltInTfLKey: Boolean get() = firstNonEmpty(build.tflKey) != null

    /**
     * The entered contact if it's a valid one, else the build's, else the project's page, as on
     * iOS. The contact only names who runs the client, so while the entered one can't be read the
     * build's stands in.
     */
    val contact: String get() = Contact.valid(entered(CONTACT)) ?: Contact.valid(build.contact) ?: Contact.PROJECT

    /**
     * The alerts server entered in settings, kept as one sealed record holding the address and
     * its token together, so a token can never be stored next to another server's address, even
     * if a save fails halfway: a save is one write, and a failed write leaves the old pair whole.
     * A record that can't be decoded is unreadable, never "none" (which would mean the built-in).
     */
    sealed interface EnteredServer {
        data object None : EnteredServer
        data object Unreadable : EnteredServer
        data class Saved(val url: String, val token: String) : EnteredServer {
            override fun toString() = "Saved(url=$url, token=redacted)"
        }
    }

    val enteredAlertsServer: EnteredServer
        get() = when (val read = secrets.read(AlertServer.ACCOUNT)) {
            is SecretRead.Value -> AlertServer.decodeRecord(read.text)?.let { (url, token) -> EnteredServer.Saved(url, token) } ?: EnteredServer.Unreadable
            SecretRead.Absent, SecretRead.Lost -> EnteredServer.None
            SecretRead.Unreadable -> EnteredServer.Unreadable
        }

    /**
     * Saves an entered server, or with both blank goes back to the built-in one. Nothing is saved
     * unless the address passes and has a token. Going back to the built-in server over one that
     * can't be read is refused, as for keys; [forget] does it on purpose.
     */
    fun useAlertsServer(url: String, token: String): Save {
        if (url.isBlank() && token.isBlank()) {
            if (enteredAlertsServer == EnteredServer.Unreadable) return Save.keptUnreadable
            return if (secrets.write("", AlertServer.ACCOUNT)) Save.saved else Save.notSaved
        }
        val server = AlertServer.resolve(url, token, null, null) ?: return Save.notSaved
        return if (secrets.write(AlertServer.encodeRecord(server.url, server.token), AlertServer.ACCOUNT)) Save.saved else Save.notSaved
    }

    /**
     * The server alerts would use, or null when alerts are off: also while the entered server is
     * saved but unreadable, which means wait, never fall back to the built-in one (as iOS does).
     */
    val alertServer: AlertServer?
        get() = when (val entered = enteredAlertsServer) {
            EnteredServer.Unreadable -> null
            is EnteredServer.Saved -> AlertServer.resolve(entered.url, entered.token, build.alertsUrl, build.alertsToken)
            EnteredServer.None -> AlertServer.resolve("", "", build.alertsUrl, build.alertsToken)
        }

    fun isEnabled(provider: ProviderID): Boolean = provider !in disabledProviders

    fun setEnabled(provider: ProviderID, enabled: Boolean) {
        disabledProviders = if (enabled) disabledProviders - provider else disabledProviders + provider
    }

    /** Whether to show first-run setup, decided once at launch ([FirstRun.takeTurn]). */
    fun takeFirstRunTurn(): Boolean = FirstRun.takeTurn(preferences, secrets::read)

    override fun toString() = "AppSettings(redacted)"

    companion object {
        // The names iOS uses in UserDefaults and the Keychain.
        const val DISABLED = "disabledProviders"
        const val DEEP = "deepSearch"
        const val SORT = "sortOrder"
        const val FILTER = "routeFilter"
        const val APPEARANCE = "appearance"
        const val GOOGLE = "google"
        const val TFL = "tfl"
        const val CONTACT = "contact"

        private fun firstNonEmpty(vararg values: String?): String? = values.asSequence().mapNotNull { it?.trim() }.firstOrNull { it.isNotEmpty() }

        /** The shape Swift's `JSONEncoder` gives `RouteFilter`: absent limits are left out. */
        fun encodeFilter(filter: RouteFilter): String = buildJsonObject {
            filter.maxChanges?.let { put("maxChanges", it) }
            filter.maxWalkMinutes?.let { put("maxWalkMinutes", it) }
            put("avoided", JsonArray(ModeGroup.entries.filter { it in filter.avoided }.map { JsonPrimitive(it.name) }))
        }.toString()

        /** Unreadable text is no filter; a mode name this version doesn't know is skipped. */
        fun decodeFilter(text: String): RouteFilter? = runCatching {
            val o: JsonObject = HTTP.json.parseToJsonElement(text).jsonObject
            RouteFilter(
                maxChanges = o["maxChanges"]?.jsonPrimitive?.intOrNull,
                maxWalkMinutes = o["maxWalkMinutes"]?.jsonPrimitive?.intOrNull,
                avoided = o["avoided"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.let(ModeGroup::named) }?.toSet() ?: emptySet(),
            )
        }.getOrNull()
    }
}
