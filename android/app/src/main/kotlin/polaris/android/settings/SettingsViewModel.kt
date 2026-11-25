package polaris.android.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import polaris.android.app.AppGraph
import polaris.engine.AppSettings
import polaris.engine.Appearance
import polaris.engine.Contact
import polaris.engine.ProviderID
import java.time.Instant

/**
 * Settings and first-run setup: the same values, each kept in one place (keys and the contact
 * Keystore-sealed through [AppSettings], the rest in preferences), so the two screens are two views
 * of it, as on iOS. Keys are saved as they are typed, as on iOS, off the main thread and one at a
 * time, each write taking the latest text, so the stored value is always the last one typed. Saves
 * run on the app's scope, so leaving the screen never cancels one. A typed key is never put in the
 * saved instance state (it would sit there unsealed); only the contact is.
 *
 * What a field shows when it opens comes from one read of the store. A key saved but unreadable
 * right now shows as that, never as an empty field, and a blank field over it never deletes it
 * (the engine refuses); typing a new key replaces it. Nothing here sends a request.
 */
class SettingsViewModel(
    private val handle: SavedStateHandle,
    private val graph: AppGraph,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Where saves run: the app's, so one typed just before leaving still lands. */
    private val saves: kotlinx.coroutines.CoroutineScope = graph.background,
) : ViewModel() {
    enum class Item(val account: String) { google(AppSettings.GOOGLE), tfl(AppSettings.TFL), contact(AppSettings.CONTACT) }

    /** What a key's field says under it. */
    enum class KeyState { builtIn, notSet, saved, unreadable, notSaved }

    data class Field(val text: String = "", val state: KeyState = KeyState.notSet)

    data class Ui(
        val loaded: Boolean = false,
        val appearance: Appearance = Appearance.system,
        val enabled: Map<ProviderID, Boolean> = emptyMap(),
        val deepSearch: Boolean = true,
        val googleUsed: Int = 0,
        val fields: Map<Item, Field> = emptyMap(),
        /** Accounts whose saved value was lost for good, until the person has seen the notice. */
        val lost: Set<String> = emptySet(),
    ) {
        fun field(item: Item) = fields[item] ?: Field()
        /** The typed contact is used only if it's an email or web address. */
        val contactInvalid: Boolean get() = field(Item.contact).text.isNotBlank() && Contact.valid(field(Item.contact).text) == null
    }

    private val settings get() = graph.settings
    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val writes = Mutex()
    /** The text waiting to be written per item; each write takes the latest. */
    private val pending = java.util.concurrent.ConcurrentHashMap<Item, String>()

    init {
        viewModelScope.launch {
            // Reading the Keystore can take a moment: off the main thread, once.
            val read = withContext(io) {
                Ui(
                    loaded = true,
                    appearance = settings.appearance,
                    enabled = ProviderID.entries.filter { it != ProviderID.apple }.associateWith { settings.isEnabled(it) },
                    deepSearch = settings.deepSearch,
                    googleUsed = graph.usage.allowance.usedThisMonth(Instant.now()),
                    fields = Item.entries.associateWith { item -> fieldFor(item) },
                    lost = settings.lostSecrets,
                )
            }
            // The contact as typed before a rotation wins over what was read (keys are read back from the store).
            val typed = listOf(Item.contact).mapNotNull { item -> handle.get<String>(item.name)?.let { item to it } }.toMap()
            _ui.value = read.copy(fields = read.fields.mapValues { (item, field) -> typed[item]?.let { field.copy(text = it) } ?: field })
        }
    }

    private fun fieldFor(item: Item): Field {
        val field = when (item) {
            Item.google -> settings.googleKeyField
            Item.tfl -> settings.tflKeyField
            Item.contact -> settings.contactField
        }
        return when (field) {
            is AppSettings.SecretField.Entered -> Field(field.text, KeyState.saved)
            AppSettings.SecretField.Unreadable -> Field("", KeyState.unreadable)
            AppSettings.SecretField.Empty -> Field("", if (builtIn(item)) KeyState.builtIn else KeyState.notSet)
        }
    }

    private fun builtIn(item: Item) = when (item) {
        Item.google -> settings.hasBuiltInGoogleKey
        Item.tfl -> settings.hasBuiltInTfLKey
        Item.contact -> false
    }

    fun setAppearance(appearance: Appearance) {
        settings.appearance = appearance
        graph.appearance.value = appearance
        _ui.value = _ui.value.copy(appearance = appearance)
    }

    fun setEnabled(provider: ProviderID, enabled: Boolean) {
        settings.setEnabled(provider, enabled)
        _ui.value = _ui.value.copy(enabled = _ui.value.enabled + (provider to enabled))
    }

    fun setDeepSearch(on: Boolean) {
        settings.deepSearch = on
        _ui.value = _ui.value.copy(deepSearch = on)
    }

    /** A key or the contact typed: shown at once, saved in order off the main thread. */
    fun type(item: Item, text: String) {
        if (item == Item.contact) handle[item.name] = text
        val before = _ui.value.field(item)
        _ui.value = _ui.value.copy(fields = _ui.value.fields + (item to before.copy(text = text)))
        pending[item] = text
        val job = saves.launch(io) {
            writes.withLock {
                val latest = pending.remove(item) ?: return@withLock
                val result = when (item) {
                    Item.google -> settings.setGoogleKeyOverride(latest)
                    Item.tfl -> settings.setTflKeyOverride(latest)
                    Item.contact -> settings.setContactOverride(latest)
                }
                val state = when (result) {
                    AppSettings.Save.saved -> if (latest.isBlank()) (if (builtIn(item)) KeyState.builtIn else KeyState.notSet) else KeyState.saved
                    AppSettings.Save.notSaved -> KeyState.notSaved
                    AppSettings.Save.keptUnreadable -> KeyState.unreadable
                }
                val now = _ui.value.field(item)
                _ui.value = _ui.value.copy(fields = _ui.value.fields + (item to now.copy(state = state)))
            }
        }
        synchronized(jobs) {
            jobs.removeAll { it.isCompleted }
            jobs += job
        }
    }

    private val jobs = mutableListOf<kotlinx.coroutines.Job>()

    /** The person has read the notice about values lost for good. */
    fun acknowledgeLost() {
        viewModelScope.launch(io) { settings.acknowledgeLost() }
        _ui.value = _ui.value.copy(lost = emptySet())
    }

    /** Waits for every key typed so far to be written (tests, and leaving setup). */
    suspend fun flush() {
        synchronized(jobs) { jobs.toList() }.forEach { it.join() }
    }
}
