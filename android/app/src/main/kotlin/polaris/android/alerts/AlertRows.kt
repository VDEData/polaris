package polaris.android.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import polaris.android.R
import polaris.android.ui.LinePill
import polaris.android.ui.Symbol
import polaris.android.ui.polaris
import polaris.engine.AlertStatus
import polaris.engine.RouteAlert
import polaris.engine.ServiceNotice
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One alert: its lines, when, and what TfL says now (iOS's `AlertRow`). [compact] (Home) leaves out when. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertRow(alert: RouteAlert, status: AlertStatus.Entry?, configured: Boolean, modifier: Modifier = Modifier, compact: Boolean = false, failed: Boolean = false, notWatched: Boolean = false) {
    val c = polaris
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            alert.segments.forEach { LinePill(it.legMode, it.line.name, it.color) }
        }
        Text(alert.displayName, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!compact) Text(alert.whenText, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        // The last check didn't come back and nothing is known: the page's own line says so, not "Checking".
        // One beyond what the server keeps for a phone is never asked about: said, not "Checking" for ever.
        if (notWatched) Text(stringResource(R.string.alerts_row_not_watched), style = MaterialTheme.typography.bodySmall, color = c.warning)
        else if (status != null || !failed) AlertSummary(status, configured)
    }
}

/** "Good service", the worst of what's wrong, or what's planned on your days (iOS's `AlertSummary`). */
@Composable
fun AlertSummary(status: AlertStatus.Entry?, configured: Boolean) {
    val c = polaris
    if (status == null) {
        Text(stringResource(if (configured) R.string.alerts_checking else R.string.alerts_not_checked), style = MaterialTheme.typography.bodySmall, color = c.inkFaint)
        return
    }
    val worst = status.issues.maxByOrNull { it.level }
    if (worst != null) {
        val minor = worst.level == ServiceNotice.Severity.minor
        val text = if (status.issues.size > 1) stringResource(R.string.alerts_and_more, worst.title, (status.issues.size - 1).toString()) else worst.title
        IconLine(if (minor) R.drawable.sym_info else R.drawable.sym_warning, text, if (minor) c.inkSoft else c.warning, bold = true)
    } else {
        IconLine(R.drawable.sym_check_circle, stringResource(R.string.alerts_good_service), c.live)
    }
    status.upcoming.firstOrNull()?.let { next ->
        val text = next.from?.let { stringResource(R.string.alerts_planned_on, shortDay(it), next.title) } ?: stringResource(R.string.alerts_planned, next.title)
        IconLine(R.drawable.sym_event, text, c.inkSoft, maxLines = 2)
    }
}

@Composable
private fun IconLine(icon: Int, text: String, color: androidx.compose.ui.graphics.Color, bold: Boolean = false, maxLines: Int = 3) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Symbol(icon, null, Modifier.padding(top = 1.dp).size(scaled(16)), tint = color)
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.SemiBold else null, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** A whole TfL notice: what, how bad, and its own words (iOS's `AlertIssueRow`). */
@Composable
fun AlertIssueRow(issue: AlertStatus.Issue, modifier: Modifier = Modifier, planned: Boolean = false) {
    val c = polaris
    val minor = issue.level == ServiceNotice.Severity.minor
    Row(modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Symbol(
            if (planned) R.drawable.sym_event else if (minor) R.drawable.sym_info else R.drawable.sym_warning, null,
            Modifier.padding(top = 2.dp).size(scaled(20)), tint = if (planned || minor) c.inkSoft else c.warning,
        )
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(issue.title, style = MaterialTheme.typography.titleSmall, color = c.ink)
            if (planned) issue.from?.let { Text(longDay(it), style = MaterialTheme.typography.labelMedium, color = c.inkSoft) }
            if (issue.detail.isNotEmpty()) Text(issue.detail, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        }
    }
}

/** "Sun 15 Nov". */
fun shortDay(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK).format(at.atZone(zone))

/** "Sunday 15 November, 04:30". */
fun longDay(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String = DateTimeFormatter.ofPattern("EEEE d MMMM, HH:mm", Locale.UK).format(at.atZone(zone))
