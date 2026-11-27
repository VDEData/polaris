package polaris.android.alerts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import polaris.android.R
import polaris.android.app.AppGraph
import polaris.android.settings.CoverPage
import polaris.android.settings.coverBottom
import polaris.android.ui.Clock
import polaris.android.ui.SectionHeader
import polaris.android.ui.Symbol
import polaris.android.ui.polaris
import polaris.android.ui.surface
import polaris.engine.AlertStore
import polaris.engine.PushAvailability
import polaris.engine.RouteAlert

/**
 * The lines you use regularly (iOS's `AlertsView`). Polaris's server watches them while you
 * travel and sends a notification when one closes or is delayed. A cover page: full screen on a
 * phone, a card over the leading half when the window is wide.
 */
@Composable
fun AlertsScreen(graph: AppGraph, night: Boolean, openEditor: (RouteAlert, Boolean) -> Unit, openSettings: () -> Unit, onClose: () -> Unit) {
    val state by graph.alerts.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val c = polaris
    var checking by remember { mutableStateOf(false) }
    // What TfL says, asked when the page shows and when Polaris comes back (at most once a minute).
    LifecycleResumeEffect(Unit) {
        val job = scope.launch { graph.alerts.refreshStatus() }
        onPauseOrDispose { job.cancel() }
    }
    CoverPage(night) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Symbol(R.drawable.sym_arrow_back, stringResource(R.string.alerts_close)) }
            Text(stringResource(R.string.alerts_title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = c.ink)
            // One 48 dp slot, whether it holds Refresh or the spinner.
            if (state.alerts.isNotEmpty() && state.configured) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else IconButton(onClick = {
                    scope.launch {
                        checking = true
                        try { graph.alerts.refreshStatus(force = true) } finally { checking = false }
                    }
                }) { Symbol(R.drawable.sym_refresh, stringResource(R.string.alerts_refresh), tint = c.tint) }
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = coverBottom()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.alerts.isEmpty()) {
                item(key = "intro") {
                    Column(Modifier.fillMaxWidth().surface(c).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.alerts_intro_title), style = MaterialTheme.typography.titleMedium, color = c.ink)
                        Text(stringResource(R.string.alerts_intro), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                    }
                }
            } else {
                item(key = "lines-h") { SectionHeader(stringResource(R.string.alerts_your_lines)) }
                item(key = "lines") {
                    val open = stringResource(R.string.alerts_open_alert)
                    Column(Modifier.fillMaxWidth().surface(c)) {
                        state.alerts.forEachIndexed { index, alert ->
                            if (index > 0) HorizontalDivider(color = c.hairline)
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(role = Role.Button, onClickLabel = open) { openEditor(alert, false) }
                                    .heightIn(min = 56.dp).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
                                    .semantics(mergeDescendants = true) {},
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AlertRow(alert, state.status[alert.id], state.configured, Modifier.weight(1f))
                                Symbol(R.drawable.sym_chevron_right, null, Modifier.size(20.dp), tint = c.inkFaint)
                            }
                        }
                    }
                }
                item(key = "lines-foot") {
                    val trouble = state.syncTrouble
                    val problem = when {
                        state.statusFailed -> stringResource(R.string.alerts_status_failed)
                        trouble is AlertStore.Trouble.Unreachable -> stringResource(R.string.alerts_sync_unreachable)
                        trouble is AlertStore.Trouble.Locked -> stringResource(R.string.alerts_sync_locked)
                        trouble is AlertStore.Trouble.Said -> stringResource(R.string.alerts_sync_said, trouble.message)
                        else -> null
                    }
                    if (problem != null) {
                        Text(problem, Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall, color = c.warning)
                    } else state.checkedAt?.let {
                        Text(stringResource(R.string.alerts_checked, Clock.of(it)), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
                    }
                }
            }

            item(key = "add") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { openEditor(RouteAlert(), true) },
                        enabled = state.configured,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Symbol(R.drawable.sym_add, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.alerts_add), Modifier.padding(start = 8.dp))
                    }
                    if (!state.configured) {
                        Text(stringResource(R.string.alerts_no_server), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
                        TextButton(onClick = openSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.alerts_open_settings)) }
                    }
                }
            }

            if (state.alerts.isNotEmpty() && state.configured) {
                item(key = "notify-h") { SectionHeader(stringResource(R.string.alerts_notifications)) }
                item(key = "notify") { NotificationsCard(graph, state) }
            }

            item(key = "about") {
                Text(stringResource(R.string.alerts_about), Modifier.padding(horizontal = 4.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
            }
        }
    }
}

/**
 * Whether notices can reach this phone, said plainly, with the one thing to do about it: no push
 * service in the build, no Play services, the server not set up for Android, notifications not
 * allowed or turned off; otherwise the urgent kind's state and a test.
 */
@Composable
private fun NotificationsCard(graph: AppGraph, state: AlertStore.State) {
    val c = polaris
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permission = rememberNotificationPermission()
    var testing by remember { mutableStateOf(false) }
    var testResult by rememberSaveable { mutableStateOf<String?>(null) }
    var urgentOn by remember { mutableStateOf(LineNotices.channelOn(context, LineNotices.CHANNEL_URGENT)) }
    LifecycleResumeEffect(Unit) {
        urgentOn = LineNotices.channelOn(context, LineNotices.CHANNEL_URGENT)
        onPauseOrDispose { }
    }
    Column(Modifier.fillMaxWidth().surface(c)) {
        when {
            state.push == PushAvailability.notSetUp -> Note(R.drawable.sym_notifications_off, stringResource(R.string.alerts_push_not_set_up_title), stringResource(R.string.alerts_push_not_set_up))
            state.push == PushAvailability.unavailable -> Note(R.drawable.sym_notifications_off, stringResource(R.string.alerts_push_unavailable_title), stringResource(R.string.alerts_push_unavailable))
            !permission.granted && !permission.asked -> {
                Note(R.drawable.sym_notifications, stringResource(R.string.alerts_allow), stringResource(R.string.alerts_allow_detail), onClick = permission.ask)
            }
            !permission.granted -> {
                Note(R.drawable.sym_notifications_off, stringResource(R.string.alerts_off), stringResource(R.string.alerts_off_detail), warning = true) { openNotificationSettings(context, null) }
            }
            else -> {
                if (state.pushFailed) {
                    Note(R.drawable.sym_warning, stringResource(R.string.alerts_push_failed), null, warning = true)
                    TextButton(onClick = { scope.launch { graph.alerts.enablePush() } }, modifier = Modifier.padding(start = 44.dp).heightIn(min = 48.dp)) { Text(stringResource(R.string.alerts_try_again)) }
                    HorizontalDivider(color = c.hairline)
                }
                if (state.serverCanPush == false) {
                    Note(R.drawable.sym_warning, stringResource(R.string.alerts_server_cannot_push), null, warning = true)
                    HorizontalDivider(color = c.hairline)
                }
                Note(
                    if (urgentOn) R.drawable.sym_notifications else R.drawable.sym_notifications_off,
                    stringResource(R.string.alerts_urgent_row),
                    stringResource(if (urgentOn) R.string.alerts_urgent_on else R.string.alerts_urgent_off),
                    warning = !urgentOn, onClickLabel = stringResource(R.string.alerts_urgent_open),
                ) {
                    LineNotices.channels(context)
                    openNotificationSettings(context, LineNotices.CHANNEL_URGENT)
                }
                HorizontalDivider(color = c.hairline)
                val sent = stringResource(R.string.alerts_test_sent)
                val noServer = stringResource(R.string.alerts_test_no_server)
                val notRegistered = stringResource(R.string.alerts_test_not_registered)
                val unreachable = stringResource(R.string.alerts_test_unreachable)
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = !testing, role = Role.Button) {
                            scope.launch {
                                testing = true
                                testResult = null
                                testResult = when (val result = graph.alerts.sendTest()) {
                                    AlertStore.TestResult.Sent -> sent
                                    AlertStore.TestResult.NoServer -> noServer
                                    AlertStore.TestResult.NotRegistered -> notRegistered
                                    AlertStore.TestResult.Unreachable -> unreachable
                                    is AlertStore.TestResult.Said -> result.message
                                }
                                testing = false
                            }
                        }
                        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Symbol(R.drawable.sym_send, null, Modifier.size(20.dp), tint = c.tint)
                    Text(stringResource(R.string.alerts_test), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.tint)
                    if (testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                testResult?.let {
                    Text(it, Modifier.padding(start = 48.dp, end = 16.dp, bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
                }
            }
        }
    }
}

@Composable
private fun Note(icon: Int, title: String, detail: String?, warning: Boolean = false, onClickLabel: String? = null, onClick: (() -> Unit)? = null) {
    val c = polaris
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Symbol(icon, null, Modifier.padding(top = 2.dp).size(20.dp), tint = if (warning) c.warning else if (onClick != null) c.tint else c.inkSoft)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (warning) c.warning else if (onClick != null && detail != null) c.tint else c.ink)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.inkSoft) }
        }
        if (onClick != null) Symbol(R.drawable.sym_chevron_right, null, Modifier.padding(top = 2.dp).size(20.dp), tint = c.inkFaint)
    }
}

/** The notification permission as the alerts need it: whether it's given, whether Polaris has asked, and the ask. */
class NotificationPermission(val granted: Boolean, val asked: Boolean, val ask: () -> Unit)

private const val ASKED_FILE = "polaris-notices-asked"

/** Whether Polaris has asked for notifications for alerts before (after a refusal Android may not ask again: then the way is its settings). */
fun askedForNotifications(context: Context): Boolean = context.getSharedPreferences(ASKED_FILE, Context.MODE_PRIVATE).getBoolean("asked", false)

private fun markAsked(context: Context) = context.getSharedPreferences(ASKED_FILE, Context.MODE_PRIVATE).edit().putBoolean("asked", true).apply()

/**
 * Android 13 and later ask; before that notifications are on unless turned off in settings, so
 * there is nothing to ask and "asked" is true. [then] runs once the person has answered.
 */
@Composable
fun rememberNotificationPermission(then: () -> Unit = {}): NotificationPermission {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(LineNotices.allowed(context)) }
    var asked by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 || askedForNotifications(context)) }
    LifecycleResumeEffect(Unit) {
        granted = LineNotices.allowed(context)
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        markAsked(context)
        asked = true
        granted = result && LineNotices.allowed(context)
        if (granted) LineNotices.channels(context)
        then()
    }
    return NotificationPermission(granted, asked) {
        if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else then()
    }
}

/** The phone's notification settings for Polaris, or for one kind of notice. */
fun openNotificationSettings(context: Context, channel: String?) {
    val intent = if (channel != null) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channel)
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
