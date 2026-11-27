package polaris.android.alerts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import polaris.engine.AlertStore
import polaris.engine.RouteAlert

/**
 * One alert being edited (iOS's `AlertEditor` state): the draft, kept in the saved state so a
 * rotation, a fold or Android stopping the app loses nothing typed, and saved to the store only
 * on Save. A line picked on the picker's pages comes back through [picks], by this editor's
 * [session].
 */
class AlertEditorViewModel(
    private val saved: SavedStateHandle,
    private val store: AlertStore,
    picks: SharedFlow<Pair<String, RouteAlert.Segment>>,
    /** The alert as the page opened with it, to tell whether it has been edited. */
    val original: RouteAlert,
    val isNew: Boolean,
    private val session: String,
) : ViewModel() {
    enum class Days(val days: List<Int>?) {
        Weekdays((1..5).toList()), EveryDay((1..7).toList()), Weekends(listOf(6, 7)), Custom(null)
    }

    private val _alert = MutableStateFlow(saved.get<String>(DRAFT)?.let(RouteAlert::decodeOne) ?: original)
    val alert: StateFlow<RouteAlert> = _alert.asStateFlow()

    /** Custom chosen: the seven days show even while they happen to match a ready-made set. */
    private val _customDays = MutableStateFlow(saved.get<Boolean>(CUSTOM) ?: (daysChoice(original.days, false) == Days.Custom))
    val customDays: StateFlow<Boolean> = _customDays.asStateFlow()

    val isDirty: Boolean get() = _alert.value != original

    init {
        viewModelScope.launch {
            picks.collect { (to, segment) -> if (to == session && segment !in _alert.value.segments) change { it.copy(segments = it.segments + segment) } }
        }
    }

    fun change(edit: (RouteAlert) -> RouteAlert) {
        _alert.value = edit(_alert.value)
        saved[DRAFT] = RouteAlert.encodeOne(_alert.value)
    }

    fun removeSegment(segment: RouteAlert.Segment) = change { it.copy(segments = it.segments - segment) }

    fun setWay(way: RouteAlert.Direction) = change { it.copy(direction = way) }

    fun chooseDays(choice: Days) {
        _customDays.value = choice == Days.Custom
        saved[CUSTOM] = _customDays.value
        choice.days?.let { days -> change { it.copy(days = days) } }
    }

    fun toggleDay(day: Int) = change { it.copy(days = (if (day in it.days) it.days - day else it.days + day).sorted()) }

    /** A time's start or end, safe while a removed row is still on screen. */
    fun setTime(index: Int, start: Int? = null, end: Int? = null) = change { alert ->
        if (index !in alert.windows.indices) alert
        else alert.copy(windows = alert.windows.mapIndexed { i, w -> if (i == index) w.copy(start = start ?: w.start, end = end ?: w.end) else w })
    }

    /** A time's own way, kept as chosen even when it matches Which way now; null follows Which way again. */
    fun setTimeWay(index: Int, way: RouteAlert.Direction?) = change { alert ->
        if (index !in alert.windows.indices) alert
        else alert.copy(windows = alert.windows.mapIndexed { i, w -> if (i == index) w.copy(direction = way) else w })
    }

    fun removeTime(index: Int) = change { alert -> alert.copy(windows = alert.windows.filterIndexed { i, _ -> i != index }) }

    /** Another time: an hour after the last one ends, two hours long, inside the day. */
    fun addTime() = change { alert ->
        val last = alert.windows.lastOrNull()?.end ?: (8 * 60)
        alert.copy(windows = alert.windows + RouteAlert.TimeWindow(minOf(last + 60, 22 * 60), minOf(last + 180, 23 * 60 + 45)))
    }

    fun setMinorDelays(on: Boolean) = change { it.copy(minorDelays = on) }

    fun setName(name: String) = change { it.copy(name = name.take(MAX_NAME)) }

    enum class Saved { Yes, Incomplete, Full }

    /** Saves the draft. Nothing is saved while it isn't complete, or when it would be one alert more than the server keeps for a phone. */
    fun save(): Saved {
        val draft = _alert.value
        if (!draft.isValid) return Saved.Incomplete
        return if (store.save(draft.copy(name = draft.name.trim()))) Saved.Yes else Saved.Full
    }

    fun delete() = store.delete(original.id)

    companion object {
        private const val DRAFT = "draft"
        private const val CUSTOM = "customDays"
        /** The server takes a name of up to 120 characters. */
        const val MAX_NAME = 120

        fun daysChoice(days: List<Int>, custom: Boolean): Days =
            if (custom) Days.Custom else Days.entries.firstOrNull { it.days?.toSet() == days.toSet() } ?: Days.Custom
    }
}
