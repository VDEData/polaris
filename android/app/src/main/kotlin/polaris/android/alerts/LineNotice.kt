package polaris.android.alerts

/**
 * One notice from the alerts server, as its FCM data message carries it (`worker/src/fcm.ts`):
 * the alert it is for, what to head it with, the words, how loudly it comes, and the tag it
 * replaces an earlier notice under. Plain, so reading it is tested on the JVM.
 */
data class LineNotice(
    val alert: String,
    val tag: String,
    val title: String,
    val subtitle: String?,
    val body: String,
    val level: Level,
    /**
     * When the server sent it (ms since 1970, by the server's clock, untouched), to keep an older
     * one from replacing a newer. Only ever compared with another notice's: the phone's clock,
     * which may be wrong either way, has no say in which of two notices is newer.
     */
    val sent: Long,
    /** "Send a test notification": said on the notice, whatever words it carries. */
    val isTest: Boolean = false,
) {
    @Suppress("EnumEntryName")
    enum class Level {
        /** Closures and delays: may be let through Do Not Disturb. */
        urgent,
        /** Minor delays, planned works, and "it has ended": an ordinary notification, with a sound. */
        normal,
        /** A second ended notice soon after one that sounded, or one saying something goes on: no sound, no pop-up. */
        quiet,
    }

    /** What happened, first: what is wrong ("Jubilee line: severe delays"), else the words themselves ("… have cleared"). */
    val headline: String get() = subtitle ?: body

    /** The rest, under the headline: TfL's own words about what is wrong. Null when the headline is all there is. */
    val detail: String? get() = body.takeIf { subtitle != null && it.isNotBlank() && it != subtitle }

    /** When to show it as having come: the server's time, but never later than the phone's now (a server clock ahead would show a notice from the future). */
    fun shownAt(now: Long): Long = minOf(sent, now)

    companion object {
        /** A sender is whoever holds the server's keys, so what is shown is kept to a size. */
        const val MAX_TITLE = 120
        const val MAX_BODY = 1_000

        /**
         * A message's data as a notice, or null for anything that isn't one (no alert, no words).
         * A level this build doesn't know is an ordinary notification; a missing time is [now].
         */
        fun parse(data: Map<String, String>, now: Long): LineNotice? {
            val alert = data["alert"]?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
            val title = data["title"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val subtitle = data["subtitle"]?.trim()?.takeIf { it.isNotEmpty() }
            val body = data["body"]?.trim() ?: ""
            if (subtitle == null && body.isEmpty()) return null
            return LineNotice(
                alert = alert,
                tag = data["tag"]?.takeIf { it.isNotBlank() && it.length <= 80 } ?: alert,
                title = title.take(MAX_TITLE),
                subtitle = subtitle?.take(MAX_TITLE),
                body = body.take(MAX_BODY),
                level = Level.entries.firstOrNull { it.name == data["level"] } ?: Level.normal,
                // The server's own time; with none (it always sends one), the phone's.
                sent = data["sent"]?.toLongOrNull()?.takeIf { it > 0 } ?: now,
                isTest = data["test"] == "1",
            )
        }

        /**
         * Whether a notice is shown over what its tag last showed: not when it was sent before
         * that one (FCM can deliver late, out of order, after the phone was offline).
         */
        fun replaces(notice: LineNotice, lastSent: Long?): Boolean = lastSent == null || notice.sent >= lastSent
    }
}
