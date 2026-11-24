package polaris.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Home, Work and recent destinations, kept on the phone under the names iOS keeps them under
 * (`places.home`, `places.work`, `places.recents`). Ported from Polaris/Services/PlaceStore.swift:
 * a picked destination goes to the top of the recents, the same name and area only once, eight
 * at most, and Current Location is never kept.
 */
class PlaceStore(private val store: KeyValueStore) {
    data class Places(val home: Place? = null, val work: Place? = null, val recents: List<Place> = emptyList())

    private val _places = MutableStateFlow(
        Places(
            home = store.getString(HOME)?.let(Snapshot::decodePlace),
            work = store.getString(WORK)?.let(Snapshot::decodePlace),
            recents = store.getString(RECENTS)?.let(Snapshot::decodePlaces) ?: emptyList(),
        ),
    )
    val places: StateFlow<Places> = _places.asStateFlow()

    var home: Place?
        get() = _places.value.home
        set(value) {
            _places.value = _places.value.copy(home = value)
            store.putString(HOME, value?.let(Snapshot::encode))
        }

    var work: Place?
        get() = _places.value.work
        set(value) {
            _places.value = _places.value.copy(work = value)
            store.putString(WORK, value?.let(Snapshot::encode))
        }

    val recents: List<Place> get() = _places.value.recents

    fun remember(place: Place) {
        if (place.isCurrentLocation) return
        val kept = listOf(place) + recents.filterNot { it.name == place.name && it.subtitle == place.subtitle }
        setRecents(kept.take(MAX_RECENTS))
    }

    fun forget(place: Place) = setRecents(recents.filterNot { it.id == place.id })

    private fun setRecents(value: List<Place>) {
        _places.value = _places.value.copy(recents = value)
        store.putString(RECENTS, Snapshot.encode(value))
    }

    companion object {
        const val HOME = "places.home"
        const val WORK = "places.work"
        const val RECENTS = "places.recents"
        const val MAX_RECENTS = 8
    }
}
