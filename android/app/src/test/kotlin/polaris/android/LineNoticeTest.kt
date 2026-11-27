package polaris.android

import polaris.android.alerts.LineNotice
import polaris.android.alerts.LineNotices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A line alert's notice as its FCM data message carries it (`worker/src/fcm.ts` writes these
 * keys; `worker/test/fcm.test.ts` pins them): what is shown, on which channel, and what replaces what.
 */
class LineNoticeTest {
    private val now = 1_800_000_000_000L
    private val closure = mapOf(
        "alert" to "6F1C2E1A-0B8E-4D59-9C3A-2E7A5D9B1C11", "tag" to "6F1C2E1A-0B8E-4D59-9C3A-2E7A5D9B1C11",
        "title" to "Liverpool Street → Canary Wharf", "subtitle" to "Canary Wharf closed (Elizabeth line)",
        "body" to "Closed - escalators.\n\nAlso: Elizabeth line: minor delays.", "level" to "urgent", "sent" to (now - 5_000).toString(),
    )

    @Test fun aClosureIsUrgentUnderItsAlertsTag() {
        val notice = assertNotNull(LineNotice.parse(closure, now))
        assertEquals("Liverpool Street → Canary Wharf", notice.title)
        assertEquals("Canary Wharf closed (Elizabeth line)", notice.headline, "what happened comes first: a collapsed notice, or a group's line, says it")
        assertEquals("Closed - escalators.\n\nAlso: Elizabeth line: minor delays.", notice.detail)
        assertFalse(notice.isTest)
        assertEquals(LineNotice.Level.urgent, notice.level)
        assertEquals(LineNotices.CHANNEL_URGENT, LineNotices.channel(notice.level))
        assertEquals(closure["alert"], notice.tag)
        assertEquals(now - 5_000, notice.sent)
    }

    @Test fun anEndedNoticeHasNoSubtitleAndAQuietOneGoesToTheSilentChannel() {
        val ended = closure - "subtitle" + ("body" to "Canary Wharf has reopened (Elizabeth line).") + ("level" to "normal")
        val notice = assertNotNull(LineNotice.parse(ended, now))
        assertNull(notice.subtitle)
        assertEquals("Canary Wharf has reopened (Elizabeth line).", notice.headline)
        assertNull(notice.detail, "the sentence is all there is")
        assertEquals(LineNotices.CHANNEL_NORMAL, LineNotices.channel(notice.level))
        // The same tag as the closure it settles: it replaces it.
        assertEquals(LineNotice.parse(closure, now)?.tag, notice.tag)
        val quiet = assertNotNull(LineNotice.parse(ended + ("level" to "quiet"), now))
        assertEquals(LineNotices.CHANNEL_QUIET, LineNotices.channel(quiet.level))
        // A level from a newer server is an ordinary notification, not dropped and not silent.
        assertEquals(LineNotice.Level.normal, LineNotice.parse(ended + ("level" to "critical"), now)?.level)
        assertEquals(LineNotice.Level.normal, LineNotice.parse(ended - "level", now)?.level)
    }

    @Test fun plannedWorksAndTestsKeepTheirOwnTags() {
        val planned = closure + ("tag" to "${closure["alert"]}-planned") + ("level" to "normal")
        assertEquals("${closure["alert"]}-planned", LineNotice.parse(planned, now)?.tag)
        // With no tag (it never happens from this server) the alert's own id is the tag.
        assertEquals(closure["alert"], LineNotice.parse(closure - "tag", now)?.tag)
    }

    @Test fun whatIsNotANoticeIsNotShown() {
        assertNull(LineNotice.parse(emptyMap(), now))
        assertNull(LineNotice.parse(closure - "alert", now))
        assertNull(LineNotice.parse(closure - "title", now))
        assertNull(LineNotice.parse(closure + ("title" to "  "), now))
        assertNull(LineNotice.parse(closure - "subtitle" - "body", now), "nothing to say")
        assertNull(LineNotice.parse(closure + ("alert" to "x".repeat(65)), now))
        // A subtitle alone is enough (a notice with no detail).
        assertNotNull(LineNotice.parse(closure - "body", now))
    }

    @Test fun whatIsShownIsKeptToASize() {
        val long = closure + ("title" to "t".repeat(500)) + ("subtitle" to "s".repeat(500)) + ("body" to "b".repeat(5000))
        val notice = assertNotNull(LineNotice.parse(long, now))
        assertEquals(LineNotice.MAX_TITLE, notice.title.length)
        assertEquals(LineNotice.MAX_TITLE, notice.subtitle?.length)
        assertEquals(LineNotice.MAX_BODY, notice.body.length)
    }

    @Test fun aLaterNoticeReplacesAnEarlierOneNeverTheOtherWayRound() {
        val first = assertNotNull(LineNotice.parse(closure + ("sent" to "1000"), now))
        val second = assertNotNull(LineNotice.parse(closure + ("sent" to "2000"), now))
        assertTrue(LineNotice.replaces(first, null), "the first for its tag is shown")
        assertTrue(LineNotice.replaces(second, first.sent))
        assertFalse(LineNotice.replaces(first, second.sent), "one delivered late doesn't put old news back")
        assertTrue(LineNotice.replaces(second, second.sent), "the same moment still shows (a resend)")
    }

    /** Which of two notices is newer is the server's to say: both times are its own, and the phone's clock has no part in it. */
    @Test fun thePhonesClockBeingWrongChangesNothing() {
        val earlier = now + 60_000
        val later = now + 120_000
        // The phone's clock set back an hour (to it, both notices are "from the future").
        val slow = now - 3_600_000
        val first = assertNotNull(LineNotice.parse(closure + ("sent" to earlier.toString()), slow))
        val second = assertNotNull(LineNotice.parse(closure + ("sent" to later.toString()), slow))
        assertEquals(earlier, first.sent, "the server's time is kept as sent")
        assertTrue(LineNotice.replaces(second, first.sent), "a new notice is not refused because the phone's clock is behind")
        assertFalse(LineNotice.replaces(first, second.sent), "nor does a late older one get past it")
        // The phone's clock an hour ahead: the same answers.
        val fast = now + 3_600_000
        val third = assertNotNull(LineNotice.parse(closure + ("sent" to earlier.toString()), fast))
        val fourth = assertNotNull(LineNotice.parse(closure + ("sent" to later.toString()), fast))
        assertTrue(LineNotice.replaces(fourth, third.sent))
        assertFalse(LineNotice.replaces(third, fourth.sent))
        // Only what is shown as its time is kept to the phone's now, so a notice never looks as if from the future.
        assertEquals(slow, second.shownAt(slow))
        assertEquals(later, fourth.shownAt(fast))
    }

    @Test fun aNoticeWithNoTimeTakesThePhones() {
        assertEquals(now, LineNotice.parse(closure - "sent", now)?.sent)
        assertEquals(now, LineNotice.parse(closure + ("sent" to "soon"), now)?.sent)
        assertEquals(now, LineNotice.parse(closure + ("sent" to "-5"), now)?.sent)
    }

    /** "Send a test notification" while TfL has trouble on the alert carries that trouble's own words: the mark is what says it is a test. */
    @Test fun aTestIsMarkedAsOne() {
        val test = assertNotNull(LineNotice.parse(closure + ("test" to "1") + ("tag" to "${closure["alert"]}-test"), now))
        assertTrue(test.isTest)
        assertEquals("Canary Wharf closed (Elizabeth line)", test.headline, "the words are as sent; the app adds that it is a test")
        assertFalse(assertNotNull(LineNotice.parse(closure + ("test" to "0"), now)).isTest)
        assertFalse(assertNotNull(LineNotice.parse(closure + ("test" to "true"), now)).isTest, "only the server's own mark")
    }
}
