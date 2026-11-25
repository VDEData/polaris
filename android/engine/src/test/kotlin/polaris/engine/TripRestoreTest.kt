package polaris.engine

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Polaris stopped mid-trip and opened again: the trip is kept, picked up where it got to, caught up
 * by the clock, and carries on with the same ongoing notification and alerts. Mirrors
 * TripRestoreTests.swift. Android's resume is decided once at launch ([TripResume.decide]); iOS's
 * `TripResume.take`, `hold` and `applyHeld` aren't ported, so a tap on the offer's notification
 * from a cold launch is the trip's own [LiveTrip.keepRoute] or [LiveTrip.acceptOffer].
 */
class TripRestoreTest {
    private fun at(clock: String) = Go.at(clock)

    /** A store of its own under the build directory, written on a writer the test shuts down. */
    private class Scratch {
        val dir = File("build/tmp/trip-restore-test/${UUID.randomUUID()}").absoluteFile
        val file = File(dir, "trip.json")
        val writer: ExecutorService = Executors.newSingleThreadExecutor()
        val store = FileTripStore(file, writer)

        /** Whether the file is there once every save and delete asked for has been done. */
        val exists: Boolean get() { store.flush(); return file.exists() }

        fun close() {
            writer.shutdownNow()
            writer.awaitTermination(5, TimeUnit.SECONDS)
            dir.deleteRecursively()
        }
    }

    private inline fun <T> withStore(block: (Scratch) -> T): T {
        val scratch = Scratch()
        try {
            return block(scratch)
        } finally {
            scratch.close()
        }
    }

    /** A trip opened again from [saved], with the test's clock, fix and boards (iOS's `Reopened`). */
    private fun TestScope.reopened(
        saved: SavedTrip,
        at: String,
        boards: Map<String, DepartureBoard>,
        fix: LiveTrip.Fix?,
        store: TripStore,
        search: (suspend (JourneyQuery, Replan.Watch) -> ReplanAnswer)? = null,
    ) = Drive(this, saved.journey, Go.at(at), boards, null, search, saved = saved, savedFix = fix, store = store)

    private fun paddingtonAt(clock: String) = LiveTrip.Fix(Go.paddington, at(clock))

    /**
     * The sample Bakerloo trip as far as getting on the 06:30 (a tap at 06:30:20), then Polaris
     * stopped: what was saved. Gets off 06:38, the 06:43 Jubilee, there 07:08.
     */
    private fun TestScope.savedOnTheEarlyBakerloo(store: TripStore? = null): Pair<SavedTrip, Journey> {
        val journey = Go.bakerloo
        val drive = Drive(this, journey, at("06:25:00"), Go.bakerlooBoards(journey), Go.start)
        drive.tick("06:28:00", Go.paddington)
        drive.clock = at("06:30:20")
        drive.trip.take(LiveTrip.Step.Boarded(journey.legs[1]))
        val saved = drive.trip.saved
        assertTrue(saved.log.boardings[1] != null && saved.arrival == at("07:08:00"))
        store?.save(saved)
        return saved to journey
    }

    /** The island trip, early onto the 06:26 Bakerloo (a tap at 06:26:20), as Go had it then. */
    private fun TestScope.earlyOnTheIslandTrip(): Pair<Drive, Journey> {
        val journey = Island.toIsland
        val drive = Drive(this, journey, at("06:22:00"), Island.boards(journey), Go.paddington)
        drive.tick("06:22:00", Go.paddington)
        drive.clock = at("06:26:20")
        drive.trip.take(LiveTrip.Step.Boarded(journey.legs[1]))
        return drive to journey
    }

    // Saving

    /**
     * A real recorded TfL journey, mid-trip, through the file and back: the same journey (ids,
     * times, paths, line names), so the log and timeline still find their legs and matching across
     * sources still sees the same route.
     */
    @Test fun savedTripRoundTripsARecordedJourney() = withStore { scratch ->
        val saved = recordedSavedTrip()
        val journey = saved.journey
        val ride = saved.legIndex
        scratch.store.save(saved)
        val back = assertNotNull(scratch.store.load())
        assertEquals(saved, back)
        assertEquals(journey.id, back.journey.id)
        assertEquals(journey.legs.map { it.id }, back.journey.legs.map { it.id })
        assertEquals(journey.routeSignature, back.journey.routeSignature)
        assertEquals(journey.legs.map { it.path }, back.journey.legs.map { it.path })
        assertEquals("tfl-123", back.log.boardings[ride]?.departure?.id)
        assertTrue(back.replanWatch.sends == 1 && back.replanWatch.googleRequests == 1)
        assertEquals(setOf("wait.3", "cancelled.3"), back.replanWatch.settled)
        // An unreadable file is no trip, not a crash.
        scratch.file.writeText("{not json")
        assertNull(scratch.store.load())
    }

    /** Saved when something happens (a boarding), not on every tick. */
    @Test fun savesOnEventsNotTicks() = runTest {
        withStore { scratch ->
            val journey = Go.bakerloo
            val saved = SavedTrip(
                journey = journey,
                query = JourneyQuery(Place(name = "Norfolk Square", coordinate = Go.start), Place(name = "One Canada Square", coordinate = Go.canadaSquare), TimeMode.LeaveNow),
                legIndex = 1, log = TripLog(began = at("06:25:00")), replanWatch = Replan.Watch(), checkedWaits = emptySet(), arrival = at("07:20:00"),
            )
            val reopened = reopened(saved, "06:28:00", Go.bakerlooBoards(journey), paddingtonAt("06:28:00"), scratch.store)
            val trip = reopened.trip
            trip.catchUp()
            reopened.tick("06:28:02")
            reopened.tick("06:28:04")
            reopened.tick("06:28:06")
            assertEquals(0, trip.saveCount)
            // Ticking on to the tap, so the ticks after it are ticks, not a return from the background.
            reopened.steady("06:30:00")
            assertEquals(0, trip.saveCount)
            reopened.clock = at("06:30:20")
            trip.take(LiveTrip.Step.Boarded(journey.legs[1]))
            assertEquals(1, trip.saveCount)
            assertNotNull(scratch.store.load()?.log?.boardings?.get(1))
            reopened.tick("06:30:22")
            reopened.tick("06:30:24")
            assertEquals(1, trip.saveCount)
        }
    }

    // Picking it up

    /**
     * Opened five minutes later, still on the train: the same ride, the same train, the same times.
     * One ongoing notification, adopted; the alerts rescheduled once.
     */
    @Test fun reopenedMidRide() = runTest {
        withStore { scratch ->
            val (saved, journey) = savedOnTheEarlyBakerloo(scratch.store)
            // Underground: the phone still holds Paddington's fix from before you got on.
            val reopened = reopened(assertNotNull(scratch.store.load()), "06:35:00", emptyMap(), paddingtonAt("06:28:00"), scratch.store)
            val trip = reopened.trip
            reopened.start()
            assertTrue(trip.isResumed)
            assertTrue(trip.legIndex == 1 && trip.boarded)
            assertEquals(at("06:30:00"), trip.yourDeparture?.scheduled)
            assertEquals(at("06:38:00"), trip.rideArrival)
            assertEquals(saved.log, trip.log)
            assertEquals(listOf(true), reopened.presenter.starts)
            assertEquals(1, reopened.alerts.schedules)
            assertEquals(at("06:36:30"), reopened.alerts.time("getoff.1"))
            assertNull(reopened.alerts.offered)
            // Later rides' boards are fetched once each.
            testScheduler.runCurrent()
            assertTrue(journey.legs[3].id in reopened.loaded)
            assertEquals(reopened.loaded.size, reopened.loaded.toSet().size)
            // End: nothing left to pick up.
            trip.stop()
            assertFalse(scratch.exists)
            assertEquals(1, reopened.presenter.ended)
        }
    }

    /**
     * Opened at 06:45, after the train got in at 06:38: on to the change without a tap, the ride
     * ended when it got in (not at 06:45), so the 06:43 Jubilee and 07:08 still stand. No alert for
     * the ride that's done.
     */
    @Test fun reopenedAfterTheRideEnded() = runTest {
        withStore { scratch ->
            val (_, journey) = savedOnTheEarlyBakerloo(scratch.store)
            val reopened = reopened(assertNotNull(scratch.store.load()), "06:45:00", Go.bakerlooBoards(journey), paddingtonAt("06:28:00"), scratch.store)
            val trip = reopened.trip
            reopened.start()
            assertEquals(2, trip.legIndex)
            assertEquals(at("06:38:00"), trip.log.ended[1])
            assertEquals(at("06:43:00"), trip.timeline.steps[3].service?.scheduled)
            assertEquals(at("07:08:00"), trip.eta)
            assertEquals(listOf(true), reopened.presenter.starts)
            assertEquals(1, reopened.alerts.schedules)
            assertNull(reopened.alerts.time("getoff.1"))
            assertEquals(at("07:01:30"), reopened.alerts.time("getoff.3"))
            // Saved again: the leg ended counts.
            assertEquals(2, scratch.store.load()?.legIndex)
            trip.stop()
        }
    }

    /**
     * Opened at 07:20, out at One Canada Square: on the last leg, there, with no alerts left. As on
     * a phone, the first fix comes a moment after Go starts tracking, not at launch.
     */
    @Test fun reopenedAfterArriving() = runTest {
        withStore { scratch ->
            val (saved, journey) = savedOnTheEarlyBakerloo(scratch.store)
            assertTrue(saved.isResumable(at("07:20:00")))
            val reopened = reopened(assertNotNull(scratch.store.load()), "07:20:00", Go.bakerlooBoards(journey), null, scratch.store)
            val trip = reopened.trip
            reopened.start()
            // No fix yet: the clock alone ends the Bakerloo; the change walk waits to be seen.
            assertEquals(2, trip.legIndex)
            reopened.tick("07:20:02", Go.canadaSquare)
            assertTrue(trip.legIndex == journey.legs.size - 1 && trip.isLastLeg)
            assertEquals(at("06:38:00"), trip.log.ended[1])
            assertEquals(at("07:03:00"), trip.log.ended[3])
            assertTrue(reopened.alerts.pending.isEmpty())
            assertEquals(listOf(true), reopened.presenter.starts)
            trip.stop()
        }
    }

    /**
     * Opened 20 seconds before "Get off at Baker Street": the alert handed over before Polaris
     * stopped is still pending, so it stays (inside the half-minute a new one would need).
     */
    @Test fun reopenedJustBeforeAnAlertKeepsIt() = runTest {
        withStore { scratch ->
            val (_, journey) = savedOnTheEarlyBakerloo(scratch.store)
            val reopened = reopened(assertNotNull(scratch.store.load()), "06:36:10", Go.bakerlooBoards(journey), paddingtonAt("06:28:00"), scratch.store)
            reopened.start()
            assertEquals(1, reopened.alerts.schedules)
            assertEquals(at("06:36:30"), reopened.alerts.time("getoff.1"))
            reopened.trip.stop()
        }
    }

    /**
     * Long after (more than half an hour past its arrival), the trip isn't picked up: the file goes,
     * and what it left is taken back. iOS keeps a trip still in time for the sheet to take once
     * (`TripResume.take`); Android's [TripResume.decide] hands it over as it launches.
     */
    @Test fun longAfterIsDropped() = runTest {
        withStore { scratch ->
            val (saved, _) = savedOnTheEarlyBakerloo(scratch.store)
            assertTrue(saved.isResumable(at("07:38:00")))
            assertFalse(saved.isResumable(at("07:38:01")))
            assertFalse(saved.isResumable(at("18:00:00")))
            assertFalse(saved.copy(version = SavedTrip.CURRENT_VERSION + 1).isResumable(at("06:35:00")))
            var dropped = 0
            assertNull(TripResume.decide(scratch.store, at("09:00:00")) { dropped += 1 })
            assertFalse(scratch.exists)
            assertEquals(1, dropped)
            // Still in time: handed over, and kept.
            scratch.store.save(saved)
            assertEquals(saved, TripResume.decide(scratch.store, at("06:40:00")) { dropped += 1 })
            assertTrue(scratch.exists)
            assertEquals(1, dropped)
        }
    }

    // Re-planning across a relaunch

    /**
     * Picked up with a reason to re-plan already there (early onto the Bakerloo, a long wait at
     * Baker Street): the restore asks nothing. The next event (out at Baker Street) can.
     */
    @Test fun restoreNeverSearches() = runTest {
        // Same trip shape, to the island, with its bus board, early on the 06:26.
        val (drive, journey) = earlyOnTheIslandTrip()
        assertEquals(1, drive.trip.saved.legIndex)
        val planner = Planner(listOf(Island.dlr))
        withStore { scratch ->
            // As in the app, no boards at first: they come in a moment after the restore.
            val reopened = reopened(drive.trip.saved, "06:28:00", emptyMap(), paddingtonAt("06:22:00"), scratch.store, planner.search)
            reopened.start()
            reopened.tick("06:28:02")
            reopened.boards = Island.boards(journey)
            reopened.tick("06:28:04")
            reopened.tick("06:28:06")
            testScheduler.advanceUntilIdle()
            assertTrue(planner.queries.isEmpty())
            // Swift's next look is 06:34:10 at Baker Street. Android would take that gap for another
            // return from the background, whose catch-up never re-plans, so the app ticks a minute
            // apart until then (the last before the clock's say-so for the ride, 06:33:30).
            reopened.steady("06:33:10")
            testScheduler.advanceUntilIdle()
            assertTrue(planner.queries.isEmpty())
            reopened.tick("06:34:10", Go.bakerStreet)
            testScheduler.advanceUntilIdle()
            assertEquals(1, planner.queries.size)
            reopened.trip.stop()
        }
    }

    /** The trip's search count and a declined offer come back with it: "Keep my route" stays kept. */
    @Test fun capsAndDeclinedReasonsSurvive() = runTest {
        val first = Planner(listOf(Island.dlr))
        val drive = with(Island) { early(first) }
        drive.trip.keepRoute()
        val saved = drive.trip.saved
        assertEquals(1, saved.replanWatch.sends)
        val back = assertNotNull(SavedTrip.decode(SavedTrip.encode(saved)))
        assertEquals(drive.trip.replanWatch, back.replanWatch)

        val planner = Planner(listOf(Island.dlr))
        withStore { scratch ->
            val reopened = reopened(back, "06:30:00", Island.boards(back.journey), paddingtonAt("06:22:00"), scratch.store, planner.search)
            val trip = reopened.trip
            reopened.start()
            assertEquals(drive.trip.replanWatch, trip.replanWatch)
            // Out at Baker Street with the same long wait: declined, so not asked again. Ticking a
            // minute apart (as restoreNeverSearches says why), so that event is looked at.
            reopened.steady("06:33:10")
            reopened.tick("06:34:10", Go.bakerStreet)
            reopened.steady("06:39:30")
            testScheduler.advanceUntilIdle()
            assertTrue(planner.queries.isEmpty())
            assertNull(trip.offer)
            trip.stop()
        }
    }

    // Review fixes

    /**
     * Waiting at Paddington for the 06:41, ten minutes late on the board, then stopped; got on
     * underground with no signal and reopened at 06:49:30, the timetable's arrival. With no board
     * yet and no boarding seen, the clock mustn't end a ride you may be still on: it waits for the
     * board, which says the train left 06:51.
     */
    @Test fun lateUnseenRideIsNotEndedByTheTimetable() = runTest {
        val journey = Go.bakerloo
        val late = Go.bakerlooBoards(journey)
        val board = late.getValue(journey.legs[1].id)
        late[journey.legs[1].id] = board.copy(departures = board.departures.map {
            if (it.id == board.plannedId) it.copy(expected = at("06:51:00"), arrivalThere = at("06:59:00")) else it
        })
        val drive = Drive(this, journey, at("06:38:00"), late, Go.paddington)
        drive.tick("06:40:00", Go.paddington)
        assertTrue(drive.trip.legIndex == 1 && drive.trip.isWaiting)
        withStore { scratch ->
            val reopened = reopened(drive.trip.saved, "06:49:30", emptyMap(), paddingtonAt("06:40:00"), scratch.store)
            val trip = reopened.trip
            reopened.start()
            assertEquals(1, trip.legIndex)
            assertNull(trip.log.ended[1])
            // The board comes in: the 06:51, at Baker Street 06:59.
            reopened.boards = late
            reopened.tick("06:49:32")
            assertEquals(1, trip.legIndex)
            assertEquals(at("06:59:00"), trip.rideArrival)
            trip.stop()
        }
    }

    /**
     * A board the restore asked for never comes (no signal): after a minute the quiet ends, and the
     * event that ends it is still looked at, not swallowed.
     *
     * Swift reopens at 06:28 and next looks at 06:34:10, out at Baker Street. On Android that gap is
     * itself a return from the background (its catch-up never re-plans), and with looks a minute
     * apart the quiet would end on an empty tick, so the event here is the one that can land on the
     * quiet's last look: reopened at 06:32:20, the clock ends the ride (due 06:34, so from 06:33:30)
     * on the 06:33:30 look, the first more than a minute after the reopen. No new fix: a fresh one
     * within two minutes of a fix-less reopen is caught up, and a catch-up never re-plans.
     */
    @Test fun quietTimeoutKeepsTheEvent() = runTest {
        val (drive, journey) = earlyOnTheIslandTrip()
        val boards = Island.boards(journey)
        boards.remove(journey.legs[1].id)
        val planner = Planner(listOf(Island.dlr))
        withStore { scratch ->
            val reopened = reopened(drive.trip.saved, "06:32:20", boards, paddingtonAt("06:22:00"), scratch.store, planner.search)
            reopened.start()
            reopened.tick("06:32:22")
            reopened.tick("06:33:20")
            testScheduler.advanceUntilIdle()
            assertEquals(1, reopened.trip.legIndex)
            assertTrue(planner.queries.isEmpty())
            reopened.tick("06:33:30")
            testScheduler.advanceUntilIdle()
            assertEquals(2, reopened.trip.legIndex)
            assertEquals(1, planner.queries.size)
            reopened.trip.stop()
        }
    }

    /**
     * iOS: `takeChecksTheAgeAgain`. Android decides once, at launch, so there's no later take to
     * check the age again; what's left is that the age is checked against the moment asked.
     */
    @Test fun theAgeIsCheckedAgainstTheMomentAsked() = runTest {
        withStore { scratch ->
            val (saved, _) = savedOnTheEarlyBakerloo(scratch.store)
            assertNull(TripResume.decide(scratch.store, at("09:00:00")))
            assertFalse(scratch.exists)
            scratch.store.save(saved)
            assertEquals(saved, TripResume.decide(scratch.store, at("06:41:00")))
        }
    }

    /** An unreadable file at launch is dropped. */
    @Test fun decideDeletesACorruptFile() = withStore { scratch ->
        scratch.dir.mkdirs()
        scratch.file.writeText("{not json")
        var dropped = 0
        assertNull(TripResume.decide(scratch.store, at("06:40:00")) { dropped += 1 })
        assertFalse(scratch.exists)
        assertEquals(1, dropped)
    }

    /**
     * Keep my route tapped on the notification that opens Polaris cold: once the trip is back, that
     * reason is declined (and saved). Switch can't be done (the offer wasn't kept), so Go says it
     * has gone.
     */
    @Test fun offerTappedFromAColdLaunch() = runTest {
        val drive = with(Island) { early(Planner(listOf(Island.dlr))) }
        val saved = drive.trip.saved
        val key = assertNotNull(saved.offerKey)
        withStore { scratch ->
            val reopened = reopened(saved, "06:27:00", Island.boards(saved.journey), paddingtonAt("06:22:00"), scratch.store)
            reopened.start()
            reopened.trip.keepRoute()
            assertTrue(key in reopened.trip.replanWatch.settled)
            // Kept: the offer's reason is settled (the search that found it already did that) and
            // the saved trip no longer carries an offer for a later tap to act on.
            assertNull(scratch.store.load()?.offerKey)
            assertEquals(true, scratch.store.load()?.replanWatch?.settled?.contains(key))
            reopened.trip.stop()

            val again = reopened(saved, "06:27:00", Island.boards(saved.journey), paddingtonAt("06:22:00"), scratch.store)
            again.start()
            again.trip.acceptOffer()
            assertEquals(LiveTrip.ReplanState.Said(ReplanMessage.OfferGone), again.trip.replan)
            assertEquals(saved.journey, again.trip.journey)
            again.trip.stop()
        }
    }

    // Android's own

    /**
     * Saves and End's delete go one after another on the writer, in the order asked: a save queued
     * just before End never brings the trip back, and of two saves the last is what's there. The
     * writer is held until both are queued, so neither runs before the other is asked for.
     */
    @Test fun theFileStoreKeepsTheOrderAsked() = runTest {
        val (saved, _) = savedOnTheEarlyBakerloo()
        withStore { scratch ->
            val held = CountDownLatch(1)
            scratch.writer.execute { held.await() }
            scratch.store.save(saved)
            scratch.store.clear()
            held.countDown()
            assertFalse(scratch.exists)

            val later = saved.copy(legIndex = 2)
            val stillHeld = CountDownLatch(1)
            scratch.writer.execute { stillHeld.await() }
            scratch.store.save(saved)
            scratch.store.save(later)
            stillHeld.countDown()
            assertEquals(later, scratch.store.load())
        }
    }

    /**
     * Android's own file shape: a recorded TfL journey with a boarding onto a real departure (its
     * colour, platform and time there), through [SavedTrip.encode] and [SavedTrip.decode] and back
     * the same.
     */
    @Test fun aSavedTripsJsonRoundTripsItsDeparture() {
        val saved = recordedSavedTrip()
        val back = assertNotNull(SavedTrip.decode(SavedTrip.encode(saved)))
        assertEquals(saved, back)
        val departure = assertNotNull(back.log.boardings[saved.legIndex]?.departure)
        val original = assertNotNull(saved.log.boardings[saved.legIndex]?.departure)
        assertEquals(original, departure)
        assertEquals("4", departure.platform)
        assertEquals(RGB.hex("000000"), departure.lineColor)
        assertEquals(saved.journey.legs[saved.legIndex].arrival, departure.arrivalThere)
        assertEquals(saved.log.began, back.log.began)
        assertNull(SavedTrip.decode("{not json"))
    }

    /** A recorded TfL journey (the shared fixture), mid-ride, as Go would save it. */
    private fun recordedSavedTrip(): SavedTrip {
        val journeys = TfLProvider.parse(Fixtures.bytes("tfl-journeys.json"), "Fastest")
        val parsed = journeys.first()
        val journey = parsed.copy(notices = parsed.notices + ServiceNotice("Minor delays on the Northern", "Signal failure.",
            ServiceNotice.Severity.delays, ProviderID.tfl, parsed.legs[0].id))
        val ride = journey.legs.indexOfFirst { it.mode.isTransit }
        assertTrue(ride >= 0)
        val leg = journey.legs[ride]
        val log = TripLog(
            began = journey.departure.plusSeconds(-90.25),
            boardings = mapOf(ride to TripLog.Boarding(leg.departure.plusSeconds(12.5), Departure(
                id = "tfl-123", line = "Northern", mode = LegMode.tube, lineColor = RGB.hex("000000"), headsign = "Morden", stopName = "King's Cross",
                stopCoordinate = leg.start!!, scheduled = leg.departure, expected = leg.departure, isRealtime = true, isCancelled = false,
                platform = "4", arrivalThere = leg.arrival,
            ))),
            ended = mapOf(0 to journey.legs[0].arrival),
        )
        val query = JourneyQuery(Place(name = "King's Cross", coordinate = Coordinate(51.5308, -0.1238)),
            Place(name = "Canary Wharf", coordinate = Coordinate(51.5035, -0.0187)), TimeMode.DepartAt(journey.departure))
        val watch = Replan.Watch()
            .asked(Replan.Reason.LongWait(3, 600.0), journey.departure, usedGoogle = true, answered = true)
            .declined(Replan.Reason.Cancelled(3))
        return SavedTrip(journey = journey, query = query, legIndex = ride, log = log, replanWatch = watch,
            checkedWaits = setOf("wait.3"), arrival = journey.arrival)
    }
}
