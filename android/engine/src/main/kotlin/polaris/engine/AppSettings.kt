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
     * Something was saved under a key that is gone for good (the app's data restored onto another
     * phone, or the Keystore reset). The store has removed it; the person should be told to enter
     * it again. Reported once: the next read is [Absent].
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
 * ([keysUnreadable] says so), and the alerts server is off rather than the built-in one used,
 * so nothing goes out with a value the person didn't choose. A key that was lost for good is
 * reported once in [lostSecrets] so a screen can ask for it again.
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

    private val lost = mutableSetOf<String>()

    /** Accounts whose saved value was lost for good since the last [acknowledgeLost], for a screen to mention. */
    val lostSecrets: Set<String> get() = synchronized(lost) { lost.toSet() }

    fun acknowledgeLost() = synchronized(lost) { lost.clear() }

    /** The entered value, "" when none, or null while it is saved but can't be read. */
    private fun entered(account: String): String? = when (val read = secrets.read(account)) {
        is SecretRead.Value -> read.text
        SecretRead.Absent -> ""
        SecretRead.Lost -> {
            synchronized(lost) { lost += account }
            ""
        }
        SecretRead.Unreadable -> null
    }

    /** For a settings field: what was entered, or "" (also while unreadable; see [keysUnreadable]). */
    val googleKeyOverride: String get() = entered(GOOGLE) ?: ""
    val tflKeyOverride: String get() = entered(TFL) ?: ""
    /** Who Transitous can reach about this install's requests (User-Agent); blank is the build's. */
    val contactOverride: String get() = entered(CONTACT) ?: ""

    /** Saves an entered value ("" removes it). False if it wasn't kept, for the screen to say "not saved". */
    fun setGoogleKeyOverride(value: String): Boolean = secrets.write(value, GOOGLE)
    fun setTflKeyOverride(value: String): Boolean = secrets.write(value, TFL)
    fun setContactOverride(value: String): Boolean = secrets.write(value, CONTACT)

    /** An entered key is saved but can't be read right now. */
    val keysUnreadable: Boolean get() = listOf(GOOGLE, TFL).any { secrets.read(it) == SecretRead.Unreadable }

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
            is SecretRead.Value -> AlertServer.decodeRecord(read.text)?.let { (url, token) -> EnteredServer.Saved(url, token) } ?: EnteredServer.None
            SecretRead.Absent -> EnteredServer.None
            SecretRead.Lost -> {
                synchronized(lost) { lost += AlertServer.ACCOUNT }
                EnteredServer.None
            }
            SecretRead.Unreadable -> EnteredServer.Unreadable
        }

    /**
     * Saves an entered server, or with both blank goes back to the built-in one. Nothing is saved
     * unless the address passes and has a token; false if it wasn't saved.
     */
    fun useAlertsServer(url: String, token: String): Boolean {
        if (url.isBlank() && token.isBlank()) return secrets.write("", AlertServer.ACCOUNT)
        val server = AlertServer.resolve(url, token, null, null) ?: return false
        return secrets.write(AlertServer.encodeRecord(server.url, server.token), AlertServer.ACCOUNT)
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
