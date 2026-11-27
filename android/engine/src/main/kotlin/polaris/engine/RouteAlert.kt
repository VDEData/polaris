package polaris.engine

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID

/**
 * A stretch of line you use regularly, and when you travel. Polaris's server (`worker/`) checks
 * TfL every two minutes during those times and sends a notification when something on that
 * stretch closes or is delayed, and the evening before for planned works. The JSON is the
 * server's contract: `PolarisTests/Fixtures/alert-contract.json`. Ported from
 * Polaris/Models/RouteAlert.swift, names and wording included: the name goes to the server and
 * is what a notification is headed with.
 */
@Serializable
data class RouteAlert(
    val id: String = UUID.randomUUID().toString().uppercase(),
    /** Empty means [defaultName]. */
    val name: String = "",
    /** ISO weekdays: 1 is Monday. */
    val days: List<Int> = (1..5).toList(),
    val windows: List<TimeWindow> = listOf(TimeWindow(7 * 60, 10 * 60), TimeWindow(16 * 60 + 30, 19 * 60 + 30)),
    val minorDelays: Boolean = true,
    val segments: List<Segment> = emptyList(),
    /**
     * Which way you ride. Null, as alerts saved before this are, is both ways; the server reads a
     * missing one the same. Only trouble TfL reports one way only depends on it.
     */
    val direction: Direction? = null,
) {
    @Serializable(with = DirectionSerializer::class)
    @Suppress("EnumEntryName")
    enum class Direction {
        /** From the first stretch's `from` to the last one's `to`. */
        out, back, both
    }

    /** A word a newer build saved is both ways, so the alerts still load. */
    object DirectionSerializer : KSerializer<Direction> {
        override val descriptor = PrimitiveSerialDescriptor("polaris.Direction", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: Direction) = encoder.encodeString(value.name)
        override fun deserialize(decoder: Decoder): Direction = decoder.decodeString().let { word -> Direction.entries.firstOrNull { it.name == word } ?: Direction.both }
    }

    val way: Direction get() = direction ?: Direction.both

    @Serializable
    data class Line(
        /** TfL's line id ("elizabeth"). */
        val id: String,
        /** As people say it ("Elizabeth line", "District line", "Southeastern"). */
        val name: String,
    )

    @Serializable
    data class Station(
        /** TfL's ids for it, "|" between: "910GLIVSTLL|910GLIVST". */
        val id: String,
        val name: String,
    )

    @Serializable
    data class Segment(
        val line: Line,
        /** TfL's mode name: tube, dlr, elizabeth-line, overground, tram, national-rail. */
        val mode: String,
        /** Null for the whole line. */
        val from: Station? = null,
        val to: Station? = null,
        /** From `from` to `to`, in order; empty for the whole line. */
        val stations: List<String> = emptyList(),
    ) {
        val id: String get() = "${line.id}|${from?.id ?: ""}|${to?.id ?: ""}"
        val legMode: LegMode get() = NetworkStatus.mode(mode)?.refined(line.name, null) ?: LegMode.rail
        val color: RGB? get() = LineColors.color(line.name, legMode)
        val isWholeLine: Boolean get() = from == null || to == null
        /** "Liverpool Street to Canary Wharf", or "Whole line". */
        val stretch: String get() = if (from != null && to != null) "${from.name} to ${to.name}" else "Whole line"
    }

    @Serializable
    data class TimeWindow(
        /** Minutes after midnight. */
        val start: Int,
        val end: Int,
        /**
         * This time's own way ("to Paddington" mornings, "to Reading" evenings), kept as chosen
         * even when it matches the alert's. Null, as every time saved before this is, follows the
         * alert's `direction`; it isn't written out.
         */
        val direction: Direction? = null,
    )

    /** Where the alert starts and ends, when it's a stretch rather than whole lines. */
    val ends: Pair<String, String>?
        get() {
            val from = segments.firstOrNull()?.from?.name ?: return null
            val to = segments.lastOrNull()?.to?.name ?: return null
            return if (from != to) from to to else null
        }

    /** "Both ways", "To Canary Wharf", "To Liverpool Street". */
    fun label(direction: Direction): String {
        val ends = ends
        return when {
            direction == Direction.out && ends != null -> "To ${ends.second}"
            direction == Direction.back && ends != null -> "To ${ends.first}"
            else -> "Both ways"
        }
    }

    /** The way a time is ridden: its own, else the alert's. */
    fun way(window: TimeWindow): Direction = window.direction ?: way

    /** Times that go different ways, on a stretch with ends: then names and times say each one's way. */
    val waysDiffer: Boolean get() = ends != null && windows.map(::way).toSet().size > 1

    val displayName: String get() = name.trim().ifEmpty { defaultName }

    /**
     * "Liverpool Street → Canary Wharf" (the other way round coming back), or the line for a
     * whole-line alert.
     */
    val defaultName: String
        get() {
            if (segments.isEmpty()) return "New alert"
            val ends = ends
            if (ends != null) {
                if (waysDiffer) return "${ends.first} ⇄ ${ends.second}"
                return if (way == Direction.back) "${ends.second} → ${ends.first}" else "${ends.first} → ${ends.second}"
            }
            return list(segments.map { it.line.name }.distinct())
        }

    /**
     * "Weekdays, 07:00–10:00 and 16:30–19:30", or with times going different ways,
     * "Weekdays, 07:00–10:00 to Canary Wharf and 16:30–19:30 to Liverpool Street".
     */
    val whenText: String
        get() {
            val differ = waysDiffer
            val times = list(windows.map { window ->
                val time = "${clock(window.start)}–${clock(window.end)}"
                if (!differ) time else "$time ${label(way(window)).replaceFirstChar { it.lowercase() }}"
            })
            return if (times.isEmpty()) daysText else "$daysText, $times"
        }

    val daysText: String
        get() = when (days.toSet()) {
            (1..5).toSet() -> "Weekdays"
            (1..7).toSet() -> "Every day"
            setOf(6, 7) -> "Weekends"
            else -> list(days.sorted().distinct().mapNotNull { SHORT_DAYS.getOrNull(it - 1) })
        }

    /** Complete enough to save: a line, a day, and times that each start and end apart. */
    val isValid: Boolean get() = segments.isNotEmpty() && days.isNotEmpty() && windows.isNotEmpty() && windows.all { it.start != it.end }

    /** As it is sent: the name always filled in, so the notification says it, and within the server's length for one. */
    val wire: RouteAlert get() = withinLimits.let { it.copy(name = it.displayName.take(MAX_NAME)) }

    /**
     * Cut to what the server takes for one alert ([MAX_SEGMENTS] lines, [MAX_WINDOWS] times, a
     * name of [MAX_NAME] characters). The server refuses a whole registration for one alert over
     * any of them, and then none of the phone's alerts is updated.
     */
    val withinLimits: RouteAlert
        get() = if (segments.size <= MAX_SEGMENTS && windows.size <= MAX_WINDOWS && name.length <= MAX_NAME) this
        else copy(segments = segments.take(MAX_SEGMENTS), windows = windows.take(MAX_WINDOWS), name = name.take(MAX_NAME))

    /** A body the server takes (`PUT /v1/devices/:token`). */
    @Serializable
    data class Registration(val environment: String, val timeZone: String, val alerts: List<RouteAlert>)

    /** What `POST /v1/status` takes. */
    @Serializable
    data class StatusRequest(val alerts: List<RouteAlert>, val timeZone: String)

    companion object {
        /** The server's limits (`worker/src/validate.ts`): alerts a phone, lines and times an alert, a name's length. */
        const val MAX_ALERTS = 30
        const val MAX_SEGMENTS = 8
        const val MAX_WINDOWS = 8
        const val MAX_NAME = 120

        val SHORT_DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

        fun clock(minutes: Int): String = "%02d:%02d".format((minutes / 60) % 24, minutes % 60)

        /** "A", "A and B", "A, B and C". */
        fun list(items: List<String>): String = when (items.size) {
            0 -> ""
            1 -> items[0]
            else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
        }

        fun encode(alerts: List<RouteAlert>): String = HTTP.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(serializer()), alerts)

        /** Saved alerts, or none if what's saved can't be read (never a crash at launch). */
        fun decodeList(text: String?): List<RouteAlert> = text?.let {
            runCatching { HTTP.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(serializer()), it) }.getOrNull()
        } ?: emptyList()

        fun encodeOne(alert: RouteAlert): String = HTTP.json.encodeToString(serializer(), alert)
        fun decodeOne(text: String): RouteAlert? = runCatching { HTTP.json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/** What the server says about each alert: trouble now, and planned works on the times you travel. */
data class AlertStatus(val checkedAt: Instant, val alerts: List<Entry>) {
    data class Entry(val id: String, val issues: List<Issue>, val upcoming: List<Issue>)

    data class Issue(val severity: String, val title: String, val detail: String, val from: Instant? = null) {
        val id: String get() = title + detail
        val level: ServiceNotice.Severity
            get() = when (severity) {
                "closed" -> ServiceNotice.Severity.closed
                "delays" -> ServiceNotice.Severity.delays
                else -> ServiceNotice.Severity.minor
            }
    }

    companion object {
        /**
         * The server is whatever address was entered, so what it says is kept to a size the
         * screen can show: a title of 120 characters, a notice of 1,500, ten issues an alert.
         */
        const val MAX_TITLE = 120
        const val MAX_DETAIL = 1_500
        const val MAX_ISSUES = 10
        const val MAX_ALERTS = 64

        /** Throws [ProviderError.BadResponse] for anything that isn't the server's status. */
        fun decode(data: ByteArray): AlertStatus = try {
            val root = HTTP.json.parseToJsonElement(data.decodeToString()).jsonObject
            val checkedAt = Time.iso(root["checkedAt"]?.jsonPrimitive?.contentOrNull) ?: throw ProviderError.BadResponse
            fun issues(element: kotlinx.serialization.json.JsonElement?) = (element?.jsonArray ?: emptyList()).take(MAX_ISSUES).map { raw ->
                val o = raw.jsonObject
                Issue(
                    severity = (o["severity"]?.jsonPrimitive?.contentOrNull ?: "").take(16),
                    title = (o["title"]?.jsonPrimitive?.contentOrNull ?: "").take(MAX_TITLE),
                    detail = (o["detail"]?.jsonPrimitive?.contentOrNull ?: "").take(MAX_DETAIL),
                    from = Time.iso(o["from"]?.jsonPrimitive?.contentOrNull),
                )
            }
            AlertStatus(
                checkedAt,
                (root["alerts"]?.jsonArray ?: emptyList()).take(MAX_ALERTS).map { raw ->
                    val o = raw.jsonObject
                    Entry((o["id"]?.jsonPrimitive?.contentOrNull ?: "").take(64), issues(o["issues"]), issues(o["upcoming"]))
                },
            )
        } catch (e: ProviderError) {
            throw e
        } catch (e: Exception) {
            throw ProviderError.BadResponse
        }
    }
}
