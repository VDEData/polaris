package polaris.engine

// Ported from Polaris/Services/AppSettings.swift: the parts the engine needs.

/**
 * Where entered keys live. On iOS it's the Keychain; on Android it will be Keystore-backed
 * storage (a later phase). Until then `InMemorySecretStore` stands in.
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
 * iOS). All empty in a stranger's build, which works with none of them.
 */
data class BuildDefaults(
    val googleKey: String? = null,
    val tflKey: String? = null,
    val contact: String? = null,
)

/**
 * Keys entered in settings beat the build-time ones, exactly as on iOS: the entered value
 * if it isn't blank, else the build's, else none.
 */
class AppSettings(
    private val secrets: SecretStore = InMemorySecretStore(),
    private val build: BuildDefaults = BuildDefaults(),
) {
    var disabledProviders: Set<ProviderID> = emptySet()

    /** Query each source several ways (fewer changes, less walking, mode filters). */
    var deepSearch: Boolean = true
    var sortOrder: SortOrder = SortOrder.best
    var routeFilter: RouteFilter = RouteFilter()

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

    val googleKey: String? get() = firstNonEmpty(googleKeyOverride, build.googleKey)
    val tflKey: String? get() = firstNonEmpty(tflKeyOverride, build.tflKey)
    val hasBuiltInGoogleKey: Boolean get() = firstNonEmpty(build.googleKey) != null
    val hasBuiltInTfLKey: Boolean get() = firstNonEmpty(build.tflKey) != null

    /** The entered contact if it's a valid one, else the build's, as `HTTP.userAgent` uses it on iOS. */
    val contact: String? get() = Contact.valid(contactOverride) ?: Contact.valid(build.contact)

    fun isEnabled(provider: ProviderID): Boolean = provider !in disabledProviders

    fun setEnabled(provider: ProviderID, enabled: Boolean) {
        disabledProviders = if (enabled) disabledProviders - provider else disabledProviders + provider
    }

    private companion object {
        fun firstNonEmpty(vararg values: String?): String? = values.asSequence().mapNotNull { it?.trim() }.firstOrNull { it.isNotEmpty() }
    }
}
