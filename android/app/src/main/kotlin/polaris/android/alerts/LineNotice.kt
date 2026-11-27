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
    /** When the server sent it (ms since 1970), to keep an older one from replacing a newer. */
    val sent: Long,
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

    /** One line for a collapsed notification: what is wrong, else the words themselves. */
    val summary: String get() = subtitle ?: body

    /** Everything, for the expanded notification. */
    val full: String get() = listOfNotNull(subtitle, body.takeIf { it.isNotBlank() && it != subtitle }).joinToString("\n")

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
                // A time in the future (a server's clock ahead) would block every later notice: capped at now.
                sent = data["sent"]?.toLongOrNull()?.takeIf { it > 0 }?.coerceAtMost(now) ?: now,
            )
        }

        /**
         * Whether a notice is shown over what its tag last showed: not when it was sent before
         * that one (FCM can deliver late, out of order, after the phone was offline).
         */
        fun replaces(notice: LineNotice, lastSent: Long?): Boolean = lastSent == null || notice.sent >= lastSent
    }
}
