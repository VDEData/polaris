package polaris.android

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import polaris.android.alerts.AlertEditorViewModel
import polaris.android.alerts.AlertEditorViewModel.Days
import polaris.engine.AlertStore
import polaris.engine.AppSettings
import polaris.engine.BuildDefaults
import polaris.engine.HttpRequest
import polaris.engine.HttpResponse
import polaris.engine.HttpTransport
import polaris.engine.InMemoryKeyValueStore
import polaris.engine.InMemorySecretStore
import polaris.engine.PushAvailability
import polaris.engine.PushRegistrar
import polaris.engine.RouteAlert
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The alert editor's draft: what each control changes, what survives the page being rebuilt, and what Save sends. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertEditorTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val elizabeth = RouteAlert.Segment(RouteAlert.Line("elizabeth", "Elizabeth line"), "elizabeth-line", RouteAlert.Station("a", "Liverpool Street"), RouteAlert.Station("b", "Canary Wharf"), listOf("a", "b"))
    private val district = RouteAlert.Segment(RouteAlert.Line("district", "District line"), "tube")

    private class Wire : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request
            return HttpResponse(200, (if (request.method == "PUT") """{"ok":true,"alerts":1,"pushes":true}""" else """{"checkedAt":"2026-11-24T12:00:00Z","alerts":[]}""").encodeToByteArray())
        }
    }

    private class Rig(scope: TestScope, dispatcher: kotlinx.coroutines.test.TestDispatcher, alerts: List<RouteAlert> = emptyList()) {
        val wire = Wire()
        val values = InMemoryKeyValueStore().apply { if (alerts.isNotEmpty()) putString(AlertStore.ALERTS, RouteAlert.encode(alerts)) }
        val push = object : PushRegistrar {
            override val availability = PushAvailability.ready
            override suspend fun register() = "cXk3Tm9wQl9aLWFiY2RlZg"
        }
        val store = AlertStore(
            AppSettings(InMemorySecretStore(), BuildDefaults(alertsUrl = "https://alerts.example.com", alertsToken = "token"), values),
            InMemorySecretStore(), values, wire, { "Polaris/test" }, push, scope, syncDelayMillis = 0, io = dispatcher,
        )
        val picks = MutableSharedFlow<Pair<String, RouteAlert.Segment>>(extraBufferCapacity = 4)
        fun editor(original: RouteAlert = RouteAlert(id = "NEW"), isNew: Boolean = true, saved: SavedStateHandle = SavedStateHandle(), session: String = "one") =
            AlertEditorViewModel(saved, store, picks, original, isNew, session)
    }

    @Test fun aNewAlertCannotBeSavedUntilItHasALine() = runTest(dispatcher) {
        val rig = Rig(this, dispatcher)
        val editor = rig.editor()
        advanceUntilIdle()
        assertFalse(editor.alert.value.isValid)
        assertEquals(AlertEditorViewModel.Saved.Incomplete, editor.save())
        assertEquals(emptyList(), rig.store.state.value.alerts)
        // The picker hands a line to this editor (and not to another's).
        rig.picks.emit("two" to district)
        rig.picks.emit("one" to elizabeth)
        rig.picks.emit("one" to elizabeth)
        advanceUntilIdle()
        assertEquals(listOf(elizabeth), editor.alert.value.segments, "added once, and only its own")
        assertTrue(editor.isDirty)
        assertEquals(AlertEditorViewModel.Saved.Yes, editor.save())
        advanceUntilIdle()
        assertEquals(listOf("Liverpool Street → Canary Wharf"), rig.store.state.value.alerts.map { it.displayName })
        assertTrue(rig.wire.requests.any { it.method == "PUT" }, "saved alerts go to the server")
    }

    @Test fun daysWaysTimesAndTheRest() = runTest(dispatcher) {
        val rig = Rig(this, dispatcher)
        val editor = rig.editor(RouteAlert(id = "A", segments = listOf(elizabeth)), isNew = false)
        advanceUntilIdle()
        assertFalse(editor.isDirty)
        // Days: the ready-made sets, and Custom showing the seven days while they still match one.
        assertEquals(Days.Weekdays, AlertEditorViewModel.daysChoice(editor.alert.value.days, editor.customDays.value))
        editor.chooseDays(Days.Weekends)
        assertEquals(listOf(6, 7), editor.alert.value.days)
        editor.chooseDays(Days.Custom)
        assertTrue(editor.customDays.value)
        assertEquals(listOf(6, 7), editor.alert.value.days, "Custom keeps the days as they were")
        assertEquals(Days.Custom, AlertEditorViewModel.daysChoice(editor.alert.value.days, editor.customDays.value))
        editor.toggleDay(1)
        editor.toggleDay(7)
        assertEquals(listOf(1, 6), editor.alert.value.days)
        editor.toggleDay(1); editor.toggleDay(6)
        assertFalse(editor.alert.value.isValid, "no day: not saved")
        editor.chooseDays(Days.EveryDay)
        assertFalse(editor.customDays.value)

        // Which way, and a time's own way kept as chosen.
        editor.setWay(RouteAlert.Direction.out)
        editor.setTimeWay(1, RouteAlert.Direction.back)
        assertEquals("Liverpool Street ⇄ Canary Wharf", editor.alert.value.displayName)
        editor.setTimeWay(1, RouteAlert.Direction.out)
        assertEquals(RouteAlert.Direction.out, editor.alert.value.windows[1].direction, "kept even when it matches Which way")
        editor.setTimeWay(1, null)
        assertNull(editor.alert.value.windows[1].direction, "following Which way again")

        // Times: set, added an hour after the last (inside the day), removed; never out of range.
        editor.setTime(0, start = 6 * 60 + 15)
        editor.setTime(0, end = 9 * 60)
        assertEquals(RouteAlert.TimeWindow(375, 540), editor.alert.value.windows[0])
        editor.addTime()
        assertEquals(RouteAlert.TimeWindow(20 * 60 + 30, 22 * 60 + 30), editor.alert.value.windows[2])
        editor.addTime()
        assertEquals(RouteAlert.TimeWindow(22 * 60, 23 * 60 + 45), editor.alert.value.windows[3], "the last possible time, not past midnight")
        editor.removeTime(3); editor.removeTime(2)
        editor.setTime(9, start = 1); editor.setTimeWay(9, RouteAlert.Direction.back); editor.removeTime(9)
        assertEquals(2, editor.alert.value.windows.size, "a row that has gone changes nothing")
        editor.setTime(1, start = 600, end = 600)
        assertFalse(editor.alert.value.isValid, "a time that starts and ends together can't be saved")
        assertEquals(AlertEditorViewModel.Saved.Incomplete, editor.save())
        editor.setTime(1, end = 720)

        editor.setMinorDelays(false)
        editor.setName("  Work  " + "x".repeat(300))
        assertEquals(AlertEditorViewModel.MAX_NAME, editor.alert.value.name.length)
        editor.setName("  Work ")
        editor.removeSegment(elizabeth)
        assertFalse(editor.alert.value.isValid)
        rig.picks.emit("one" to district)
        advanceUntilIdle()
        assertEquals(AlertEditorViewModel.Saved.Yes, editor.save())
        advanceUntilIdle()
        val saved = rig.store.state.value.alerts.single()
        assertEquals("Work", saved.name, "trimmed as it is saved")
        assertEquals(listOf(district), saved.segments)
        assertFalse(saved.minorDelays)
    }

    /** At the most alerts a phone can have, a new one (the bell's draft, say) isn't saved, and the editor is told so it stays open. */
    @Test fun aNewAlertBeyondTheMostIsNotSavedAndSaysSo() = runTest(dispatcher) {
        val full = (1..RouteAlert.MAX_ALERTS).map { RouteAlert(id = "A$it", segments = listOf(district)) }
        val rig = Rig(this, dispatcher, full)
        advanceUntilIdle()
        val editor = rig.editor(RouteAlert(id = "NEW", segments = listOf(elizabeth)))
        assertEquals(AlertEditorViewModel.Saved.Full, editor.save())
        advanceUntilIdle()
        assertEquals(RouteAlert.MAX_ALERTS, rig.store.state.value.alerts.size)
        assertTrue(rig.store.state.value.alerts.none { it.id == "NEW" })
        assertTrue(editor.isDirty || editor.isNew, "the draft is still there to keep or discard")
        // One of the 30 can still be changed.
        assertEquals(AlertEditorViewModel.Saved.Yes, rig.editor(full.first().copy(name = "Renamed"), isNew = false).save())
    }

    @Test fun theDraftSurvivesThePageBeingRebuilt() = runTest(dispatcher) {
        val rig = Rig(this, dispatcher)
        val original = RouteAlert(id = "A", segments = listOf(elizabeth))
        val state = SavedStateHandle()
        val first = rig.editor(original, isNew = false, saved = state)
        first.setName("Evenings")
        first.chooseDays(Days.Custom)
        first.toggleDay(3)
        // Android stopped the app: a new view model from the same saved state.
        val again = rig.editor(original, isNew = false, saved = state)
        assertEquals("Evenings", again.alert.value.name)
        assertEquals(listOf(1, 2, 4, 5), again.alert.value.days)
        assertTrue(again.customDays.value)
        assertTrue(again.isDirty)
        assertEquals(emptyList(), rig.store.state.value.alerts, "nothing saved until Save")
        advanceUntilIdle()
    }

    @Test fun deletingRemovesItFromTheStoreAndTheServer() = runTest(dispatcher) {
        val alert = RouteAlert(id = "A", segments = listOf(district))
        val rig = Rig(this, dispatcher, alerts = listOf(alert))
        rig.values.putString(AlertStore.PUSH_ID, "cXk3Tm9wQl9aLWFiY2RlZg")
        val store = rig.store
        advanceUntilIdle()
        store.didRegister("cXk3Tm9wQl9aLWFiY2RlZg")
        advanceUntilIdle()
        val editor = rig.editor(alert, isNew = false)
        editor.delete()
        advanceUntilIdle()
        assertEquals(emptyList(), store.state.value.alerts)
        assertEquals("DELETE", rig.wire.requests.last().method)
    }
}
