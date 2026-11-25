package polaris.android.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import polaris.android.R
import polaris.android.app.AppGraph
import polaris.android.shell.factory
import polaris.android.ui.FractalStar
import polaris.android.ui.PolarisColors
import polaris.android.ui.polaris

/**
 * Shown once, to a new install (iOS's `SetupView`, decided by [polaris.engine.FirstRun]): Polaris
 * works with nothing set, and each optional item says what it adds, that it's optional, where to
 * get it and that it can be added later in Settings. The same fields as Settings, where all of it
 * can change. System back is Skip.
 */
@Composable
fun SetupScreen(graph: AppGraph, night: Boolean, onDone: () -> Unit) {
    val model: SettingsViewModel = viewModel(factory = factory { SettingsViewModel(it, graph) })
    val ui by model.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val c = polaris
    // Keys are saved as they are typed; leaving waits for the last one to be written.
    val finish: () -> Unit = { scope.launch { model.flush(); onDone() } }
    BackHandler(onBack = finish)
    // Settled for good once it is on screen, not when the launch decided to show it (FirstRun).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { graph.settings.markSetupSeen() }
    }
    CoverPage(night) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.setup_title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = c.ink)
            TextButton(onClick = finish, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.setup_skip)) }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "welcome") {
                Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FractalStar(28.dp)
                        Spacer(Modifier.padding(start = 10.dp))
                        Text(stringResource(R.string.setup_welcome), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = c.ink)
                    }
                    Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    Text(stringResource(R.string.setup_optional), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                }
            }
            connectionItems(ui, model, graph, showLater = true)
        }
        // The one primary action, pinned at the foot so it is on the first screenful (a folded phone, large text), in the chart blue.
        HorizontalDivider(color = c.hairline)
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp + coverInset()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = finish,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.tint, contentColor = if (night) PolarisColors.Navy else androidx.compose.ui.graphics.Color.White),
            ) { Text(stringResource(R.string.setup_start), fontWeight = FontWeight.SemiBold) }
            Text(stringResource(R.string.setup_later), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = c.inkSoft, textAlign = TextAlign.Center)
        }
    }
}
