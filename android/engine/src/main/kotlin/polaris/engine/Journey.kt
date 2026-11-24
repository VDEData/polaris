package polaris.engine

import java.text.Normalizer
import java.time.Instant
import java.util.UUID

// Ported from Polaris/Models/Journey.swift. Names and behaviour follow the Swift so the two
// platforms stay comparable; the shared expectations in PolarisTests/Fixtures keep them honest.

@Suppress("EnumEntryName")
enum class ProviderID(val displayName: String, val badge: String, val mergePriority: Int) {
    // Declared in Swift's order: `ProviderID.allCases` orders a card's providers.
    google("Google", "G", 1),
    tfl("TfL", "TfL", 0),
    transitous("Transitous", "Tr", 2),
    apple("Apple", "A", 3),
}

/** Which provider found a journey, and with which of its query variants ("Fewer changes", "Rail only", ...). */
data class SourceTag(val provider: ProviderID, val variant: String)

@Suppress("EnumEntryName")
enum class LegMode(val label: String) {
    walk("Walk"), bus("Bus"), tube("Underground"), rail("Rail"), overground("Overground"),
    elizabeth("Elizabeth line"), dlr("DLR"), tram("Tram"), ferry("Boat"), cableCar("Cable car"),
    coach("Coach"), cycle("Cycle"), other("Transit");

    val isTransit: Boolean get() = this != walk && this != cycle

    /**
     * Coarse grouping for matching across sources, which disagree on whether the
     * Elizabeth line is "rail" or "subway" and the Overground "rail" or "overground".
     */
    val family: String
        get() = when (this) {
            rail, overground, elizabeth, tube, dlr, tram -> "rail"
            bus, coach -> "road"
            else -> name
        }

    /**
     * Sources describe the Elizabeth line, DLR and Overground as generic rail or
     * subway; their names say which they really are.
     */
    fun refined(line: String?, agency: String?): LegMode {
        val text = "${line ?: ""} ${agency ?: ""}".lowercase()
        if ("elizabeth" in text) return elizabeth
        if ("dlr" in text || "docklands" in text) return dlr
        if ("overground" in text) return overground
        if (line != null && Journey.normalise(line) in LineColors.overgroundLines) return overground
        if ("london underground" in text && this == rail) return tube
        return this
    }
}

/** sRGB colour from a provider's hex string. */
data class RGB(val r: Double, val g: Double, val b: Double) {
    /** Relative luminance, to pick legible text on a line pill. */
    val luminance: Double get() = 0.2126 * r + 0.7152 * g + 0.0722 * b

    companion object {
        fun hex(hex: String?): RGB? {
            var s = hex?.trim { it == ' ' || it == '\t' } ?: return null
            if (s.isEmpty()) return null
            if (s.startsWith("#")) s = s.drop(1)
            if (s.length != 6) return null
            val v = s.toUIntOrNull(16)?.toLong() ?: return null
            return RGB(((v shr 16) and 0xFF) / 255.0, ((v shr 8) and 0xFF) / 255.0, (v and 0xFF) / 255.0)
        }
    }
}

data class Leg(
    val id: String = UUID.randomUUID().toString(),
    val mode: LegMode,
    /** "Northern", "25", "Thameslink". Null for walking. */
    val line: String? = null,
    val lineColor: RGB? = null,
    val headsign: String? = null,
    val fromName: String,
    val toName: String,
    val departure: Instant,
    val arrival: Instant,
    val stopCount: Int? = null,
    val distanceMeters: Double? = null,
    val path: List<Coordinate> = emptyList(),
    val isRealtime: Boolean = false,
    /** Platform or stand at the boarding stop, where the source knows it. */
    val platform: String? = null,
    /** Stops called at between boarding and alighting, in order. */
    val intermediateStops: List<String> = emptyList(),
) {
    /** Seconds, as Swift's `TimeInterval`. */
    val duration: Double get() = arrival.seconds - departure.seconds

    /** "platform 2", or for a bus "stop G". A no-break space keeps the word and its number together. */
    fun standing(at: String): String =
        if (mode == LegMode.bus || mode == LegMode.coach) "stop $at" else "platform $at"

    val start: Coordinate? get() = path.firstOrNull()
    val end: Coordinate? get() = path.lastOrNull()
}

data class Journey(
    val id: String = UUID.randomUUID().toString(),
    val departure: Instant,
    val arrival: Instant,
    val legs: List<Leg>,
    val sources: List<SourceTag>,
    /** e.g. "£2.80", when the source could price every leg. */
    val fare: String? = null,
    val hasDisruption: Boolean = false,
    /** What TfL reports about the lines and stations this journey uses. */
    val notices: List<ServiceNotice> = emptyList(),
) {
    /** A reported closure means this journey probably won't work as planned. */
    val isBlocked: Boolean get() = notices.any { it.severity == ServiceNotice.Severity.closed }
    val worstNotice: ServiceNotice? get() = notices.maxByOrNull { it.severity }

    val duration: Double get() = arrival.seconds - departure.seconds
    val transitLegs: List<Leg> get() = legs.filter { it.mode.isTransit }
    val changes: Int get() = maxOf(0, transitLegs.size - 1)
    val walkingTime: Double get() = legs.filter { it.mode == LegMode.walk }.fold(0.0) { sum, leg -> sum + leg.duration }
    val isRealtime: Boolean get() = legs.any { it.isRealtime }
    val providers: List<ProviderID> get() = sources.map { it.provider }.distinct()

    /** Same sequence of lines = same journey, whichever source found it. */
    val routeSignature: String
        get() {
            val parts = transitLegs.map { "${it.mode.family}:${normalise(it.line ?: it.mode.name)}" }
            return if (parts.isEmpty()) "walk" else parts.joinToString(">")
        }

    /** First boarding time, used to tell apart the same route on different departures. */
    val firstBoarding: Instant get() = transitLegs.firstOrNull()?.departure ?: departure

    companion object {
        fun normalise(line: String): String =
            Normalizer.normalize(line.lowercase(), Normalizer.Form.NFC)
                .replace("&", "and")
                .replace(" line", "")
                .filter(::isLetterOrNumber)
    }
}

/** Swift's `Character.isLetter || isNumber`: numbers include ½ and Roman numerals, not just digits. */
internal fun isLetterOrNumber(c: Char): Boolean = c.isLetter() || when (Character.getType(c).toByte()) {
    Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
    else -> false
}

/** Seconds since 1 January 2001, Swift's `timeIntervalSinceReferenceDate`, so scores match to the bit. */
internal val Instant.seconds: Double get() = (epochSecond - 978_307_200L).toDouble() + nano / 1e9

internal fun Instant.plusSeconds(seconds: Double): Instant =
    plusNanos(Math.round(seconds * 1e9))

/** Something reported wrong on the network that touches one leg of a journey. */
data class ServiceNotice(
    /** "Canary Wharf part closed (Elizabeth line)", "Minor delays on the Jubilee". */
    val title: String,
    /** The operator's own words. */
    val detail: String,
    val severity: Severity,
    val source: ProviderID,
    val leg: String,
) {
    @Suppress("EnumEntryName")
    enum class Severity {
        /** Worth knowing. */
        minor,
        /** Expect it to take longer. */
        delays,
        /** The journey probably won't work as planned. */
        closed,
    }
}

data class Coordinate(val latitude: Double, val longitude: Double) {
    /**
     * "lat,lon", the form every provider takes. Written out in full: Kotlin would print a
     * longitude just off the Greenwich meridian as "5.0E-4".
     */
    val pair: String get() = "${plain(latitude)},${plain(longitude)}"

    private fun plain(value: Double): String {
        val text = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        return if ('.' in text) text else "$text.0"
    }
}

data class Place(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val subtitle: String? = null,
    val coordinate: Coordinate,
    /** Resolved from GPS at search time rather than picked from search. */
    val isCurrentLocation: Boolean = false,
)

sealed interface TimeMode {
    data object LeaveNow : TimeMode
    data class DepartAt(val at: Instant) : TimeMode
    data class ArriveBy(val at: Instant) : TimeMode

    val date: Instant
        get() = when (this) {
            LeaveNow -> Instant.now()
            is DepartAt -> at
            is ArriveBy -> at
        }

    val isArriveBy: Boolean get() = this is ArriveBy
}

data class JourneyQuery(val origin: Place, val destination: Place, val time: TimeMode)
