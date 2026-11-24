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

/**
 * Where entered keys live. On iOS it's the Keychain; on Android `EncryptedSecretStore` over the
 * Android Keystore. `InMemorySecretStore` is for tests and previews.
 */
interface SecretStore {
    fun read(account: String): String?
    /** False when it couldn't be kept: a value that wasn't saved mustn't be used. */
    fun write(value: String, account: String): Boolean
}

class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    override fun read(account: String): String? = synchronized(values) { values[account] }
    override fun write(value: String, account: String): Boolean {
        synchronized(values) { if (value.isEmpty()) values.remove(account) else values[account] = value }
        return true
    }
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
)

/** Light, dark, or whatever the phone is set to. */
@Suppress("EnumEntryName")
enum class Appearance(val label: String) { system("System"), light("Light"), dark("Dark") }

/**
 * The app's settings. Ordinary choices (sources switched off, deep search, sort, filters,
 * appearance) are kept in [preferences] under the names iOS keeps them under in UserDefaults;
 * keys, the contact and the alerts server go to [secrets]. Keys entered in settings beat the
 * build-time ones, exactly as on iOS: the entered value if it isn't blank, else the build's,
 * else none. A stored value this version can't read falls back to the default.
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

    var googleKeyOverride: String
        get() = secrets.read("google") ?: ""
        set(value) { secrets.write(value, "google") }
    var tflKeyOverride: String
        get() = secrets.read("tfl") ?: ""
        set(value) { secrets.write(value, "tfl") }
    /** Who Transitous can reach about this install's requests (User-Agent); blank is the build's. */
    var contactOverride: String
        get() = secrets.read("contact") ?: ""
        set(value) { secrets.write(value, "contact") }

    /** The alerts server entered in settings: an address and its own token, kept as a pair. */
    val enteredAlertsUrl: String get() = secrets.read(AlertServer.URL_ACCOUNT) ?: ""
    val enteredAlertsToken: String get() = secrets.read(AlertServer.TOKEN_ACCOUNT) ?: ""

    /**
     * Saves an entered server, or with both blank goes back to the built-in one. Nothing is saved
     * unless the address passes and has a token; false if the secure store couldn't keep it.
     */
    fun useAlertsServer(url: String, token: String): Boolean {
        if (url.isBlank() && token.isBlank()) {
            return secrets.write("", AlertServer.URL_ACCOUNT) and secrets.write("", AlertServer.TOKEN_ACCOUNT)
        }
        val server = AlertServer.resolve(url, token, null, null) ?: return false
        // The token first: an address saved without its token would read as no server at all.
        return secrets.write(server.token, AlertServer.TOKEN_ACCOUNT) && secrets.write(server.url, AlertServer.URL_ACCOUNT)
    }

    /** The server alerts would use, or null when alerts are off. */
    val alertServer: AlertServer?
        get() = AlertServer.resolve(enteredAlertsUrl, enteredAlertsToken, build.alertsUrl, build.alertsToken)

    val googleKey: String? get() = firstNonEmpty(googleKeyOverride, build.googleKey)
    val tflKey: String? get() = firstNonEmpty(tflKeyOverride, build.tflKey)
    val hasBuiltInGoogleKey: Boolean get() = firstNonEmpty(build.googleKey) != null
    val hasBuiltInTfLKey: Boolean get() = firstNonEmpty(build.tflKey) != null

    /** The entered contact if it's a valid one, else the build's, else the project's page, as on iOS. */
    val contact: String get() = Contact.valid(contactOverride) ?: Contact.valid(build.contact) ?: Contact.PROJECT

    fun isEnabled(provider: ProviderID): Boolean = provider !in disabledProviders

    fun setEnabled(provider: ProviderID, enabled: Boolean) {
        disabledProviders = if (enabled) disabledProviders - provider else disabledProviders + provider
    }

    companion object {
        // The names iOS uses in UserDefaults.
        const val DISABLED = "disabledProviders"
        const val DEEP = "deepSearch"
        const val SORT = "sortOrder"
        const val FILTER = "routeFilter"
        const val APPEARANCE = "appearance"

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
