package polaris.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * TfL's view of the whole network right now: every line's status, and every
 * station that's closed or skipped. It's checked against every source's routes,
 * because the planner that knew about a closure routes round it and says nothing,
 * while the ones that didn't know put you straight through it.
 * Ported from Polaris/Providers/TfLStatus.swift.
 */
data class NetworkStatus(val lines: List<Line>, val stops: List<Stop>) {
    data class Period(val from: Instant, val to: Instant) {
        operator fun contains(date: Instant): Boolean = date >= from && date <= to
    }

    data class Line(
        val name: String,
        val key: String,
        /** TfL's severity: 10 is good service, lower is worse. */
        val severity: Int,
        val summary: String,
        val reason: String,
        val isNationalRail: Boolean,
        val periods: List<Period>,
    )

    data class Stop(
        val name: String,
        val key: String,
        val mode: LegMode,
        /** The whole station is shut, rather than part of it or one line through it. */
        val isClosure: Boolean,
        val description: String,
        val period: Period?,
        /** The line the notice is about when it opens with one ("WINDRUSH LINE: …"). */
        val line: String?,
    )

    /** What TfL says about each ride in a journey. */
    fun notices(journey: Journey): List<ServiceNotice> = journey.legs.filter { it.mode.isTransit }.flatMap(::notices)

    fun notices(leg: Leg): List<ServiceNotice> {
        val mode = leg.mode.refined(leg.line, null)
        if (mode !in setOf(LegMode.tube, LegMode.dlr, LegMode.elizabeth, LegMode.overground, LegMode.tram, LegMode.rail)) return emptyList()
        val lineKey = leg.line?.let(Journey::normalise)
        val label = leg.line ?: mode.label
        val ends = listOf(leg.fromName, leg.toName).map(::stationKey)
        val calls = ends + leg.intermediateStops.map(::stationKey)

        val found = mutableListOf<ServiceNotice>()
        // Stations, grouped by notice: one closure is listed against every station it covers.
        val relevant = stops.filter { stop ->
            stop.mode == mode && (stop.period?.contains(leg.departure) ?: true) &&
                (stop.line == null || lineKey == null || stop.line == lineKey)
        }
        for ((description, group) in relevant.groupBy { it.description }.toSortedMap()) {
            val keys = group.map { it.key }.toSet()
            val lower = description.lowercase()
            if ("no service" in lower || "suspended" in lower) {
                // A stretch of line: riding within it (two of its stations) won't work; touching
                // one end of it may well, since the end station is usually still served.
                val touched = calls.toSet().intersect(keys)
                if (touched.isEmpty()) continue
                found += ServiceNotice(
                    "Part closure on the $label", description,
                    if (touched.size >= 2) ServiceNotice.Severity.closed else ServiceNotice.Severity.delays, ProviderID.tfl, leg.id,
                )
            } else {
                val stop = group.firstOrNull { it.key in ends } ?: continue
                // One station. Trains not calling one way only still call the other way.
                val oneWay = listOf("westbound", "eastbound", "northbound", "southbound").any { it in lower }
                found += ServiceNotice(
                    if (stop.isClosure) "${stop.name} station closed" else "${stop.name} part closed ($label)",
                    description,
                    if (stop.isClosure || !oneWay) ServiceNotice.Severity.closed else ServiceNotice.Severity.delays, ProviderID.tfl, leg.id,
                )
            }
        }

        // The line's own status. A part closure is better told by its stations above.
        if (lineKey != null) {
            for (line in lines) {
                if (line.key != lineKey || !(line.periods.isEmpty() || line.periods.any { leg.departure in it })) continue
                var severity = severity(line.severity) ?: continue
                if (line.severity in setOf(3, 5, 11)) {
                    if (found.isNotEmpty()) continue
                    severity = ServiceNotice.Severity.minor
                }
                val name = if (line.isNationalRail) line.name else "the ${line.name}"
                val summary = line.summary.take(1).uppercase() + line.summary.drop(1).lowercase()
                found += ServiceNotice(
                    if (severity == ServiceNotice.Severity.closed) "${line.name}: ${summary.lowercase()}" else "$summary on $name",
                    line.reason, severity, ProviderID.tfl, leg.id,
                )
            }
        }
        val seen = mutableSetOf<String>()
        return found.filter { seen.add(it.title + it.detail) }
    }

    companion object {
        const val modes = "tube,dlr,elizabeth-line,overground,tram,national-rail"

        /** Two requests: every line's status, and every station's disruptions. */
        fun requests(appKey: String?): List<HttpRequest> =
            listOf("Line/Mode/$modes/Status", "StopPoint/Mode/$modes/Disruption").map { path ->
                HttpRequest(HTTP.url("https://api.tfl.gov.uk/$path", if (appKey.isNullOrEmpty()) emptyList() else listOf("app_key" to appKey)))
            }

        suspend fun fetch(http: HTTP, appKey: String?): NetworkStatus = coroutineScope {
            val requests = requests(appKey)
            val lines = async { http.data(requests[0]) }
            val stops = async { http.data(requests[1]) }
            parse(lines.await(), stops.await())
        }

        fun parse(lines: ByteArray, stops: ByteArray): NetworkStatus {
            val lineDTOs = HTTP.decode<List<LineDTO>>(lines)
            val parsedLines = lineDTOs.flatMap { line ->
                line.lineStatuses.map { status ->
                    Line(
                        name = line.name, key = Journey.normalise(line.name), severity = status.statusSeverity,
                        summary = status.statusSeverityDescription, reason = status.reason ?: "",
                        isNationalRail = line.modeName == "national-rail",
                        periods = (status.validityPeriods ?: emptyList()).mapNotNull { period ->
                            val from = Time.iso(period.fromDate) ?: return@mapNotNull null
                            val to = Time.iso(period.toDate) ?: return@mapNotNull null
                            if (from <= to) Period(from, to) else null
                        },
                    )
                }
            }
            val lineKeys = parsedLines.map { it.key }.toSet()
            val parsedStops = HTTP.decode<List<StopDTO>>(stops).mapNotNull { stop ->
                if (stop.type != "Closure" && stop.type != "Part Closure") return@mapNotNull null
                val mode = mode(stop.mode) ?: return@mapNotNull null
                val name = stop.commonName ?: return@mapNotNull null
                val description = stop.description ?: return@mapNotNull null
                val from = Time.iso(stop.fromDate)
                val to = Time.iso(stop.toDate)
                // "MILDMAY LINE: no service between …" is about that line only, though it's listed
                // against every station on the way, which other lines share.
                val opening = description.split(":", limit = 2).firstOrNull { it.isNotEmpty() } ?: ""
                val line = if (":" in description && Journey.normalise(opening) in lineKeys) Journey.normalise(opening) else null
                Stop(
                    name = TfLProvider.cleanName(name), key = stationKey(name), mode = mode, isClosure = stop.type == "Closure",
                    description = description, period = if (from != null && to != null && from <= to) Period(from, to) else null, line = line,
                )
            }
            return NetworkStatus(parsedLines, parsedStops)
        }

        /** How much a line status matters to a route on that line; null when it doesn't. */
        fun severity(tfl: Int): ServiceNotice.Severity? = when (tfl) {
            1, 2, 4, 16, 20 -> ServiceNotice.Severity.closed
            6, 7, 8, 15, 17 -> ServiceNotice.Severity.delays
            3, 5, 9, 11 -> ServiceNotice.Severity.minor
            // Good service, step-free notes, and National Rail's "special service" (a link to its site).
            else -> null
        }

        fun mode(tfl: String?): LegMode? = when (tfl) {
            "tube" -> LegMode.tube
            "dlr" -> LegMode.dlr
            "elizabeth-line" -> LegMode.elizabeth
            "overground" -> LegMode.overground
            "tram" -> LegMode.tram
            "national-rail" -> LegMode.rail
            else -> null
        }

        /** Station names as different sources spell them: "Shepherd's Bush", "Shepherds Bush Rail Station". */
        fun stationKey(name: String): String {
            var result = name.lowercase()
            while (true) {
                val open = result.indexOf('(')
                if (open < 0) break
                val close = result.indexOf(')', open)
                if (close < 0) break
                result = result.removeRange(open, close + 1)
            }
            for (suffix in listOf(" underground station", " rail station", " dlr station", " station")) {
                if (result.endsWith(suffix)) result = result.dropLast(suffix.length)
            }
            return Normalizer.nfc(result.replace("&", "and")).filter(::isLetterOrNumber)
        }
    }

    @Serializable
    internal data class LineDTO(val name: String, val modeName: String? = null, val lineStatuses: List<StatusDTO>)

    @Serializable
    internal data class StatusDTO(
        val statusSeverity: Int,
        val statusSeverityDescription: String,
        val reason: String? = null,
        val validityPeriods: List<PeriodDTO>? = null,
    )

    @Serializable
    internal data class PeriodDTO(val fromDate: String? = null, val toDate: String? = null)

    @Serializable
    internal data class StopDTO(
        val commonName: String? = null,
        val description: String? = null,
        val type: String? = null,
        val mode: String? = null,
        val fromDate: String? = null,
        val toDate: String? = null,
    )
}

private object Normalizer {
    fun nfc(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
}

/**
 * TfL's network status: two requests, kept 3 minutes (`SearchModel.networkFreshFor` in Swift).
 * `shared` is the app-wide one; concurrent searches share one fetch.
 */
class NetworkStatusCache(private val clock: Clock = Clock.systemUTC()) {
    private val mutex = Mutex()
    private var cached: Pair<Instant, NetworkStatus>? = null

    suspend fun status(http: HTTP, appKey: String?): NetworkStatus? = mutex.withLock {
        val now = clock.instant()
        cached?.let { (at, status) -> if (Duration.between(at, now) < freshFor) return@withLock status }
        val status = try {
            NetworkStatus.fetch(http, appKey)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Without it the routes still show, just unchecked.
            return@withLock null
        }
        cached = clock.instant() to status
        status
    }

    /** Forgets the kept status (tests that use the shared cache start from empty). */
    internal suspend fun clear() = mutex.withLock { cached = null }

    companion object {
        val freshFor: Duration = Duration.ofMinutes(3)

        /** One for the whole app, as Swift's static `cachedNetwork` is: every search shares it. */
        val shared = NetworkStatusCache()
    }
}
