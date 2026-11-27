package polaris.android.settings

import polaris.engine.AppSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import polaris.android.R
import polaris.android.app.AppGraph
import polaris.android.shell.LocalWindowLayout
import polaris.android.shell.SearchFor
import polaris.android.shell.ShellViewModel
import polaris.android.shell.factory
import polaris.android.ui.SectionHeader
import polaris.android.ui.SourceBadge
import polaris.android.ui.Symbol
import polaris.android.ui.polaris
import polaris.android.ui.staysAboveKeyboard
import polaris.android.ui.surface
import polaris.engine.Appearance
import polaris.engine.AlertStore
import polaris.engine.AlertServer
import polaris.android.BuildConfig
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import polaris.engine.GoogleAllowance
import polaris.engine.ProviderID

/**
 * A page over everything: full screen on a phone; wide open, a card over the leading half, never
 * across the fold (as the place search is). Its dialog window is drawn edge to edge.
 */
@Composable
fun CoverPage(night: Boolean, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val layout = LocalWindowLayout.current
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !night
                isAppearanceLightNavigationBars = !night
            }
            w.setDimAmount(if (layout.wide) 0.25f else 0f)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Surface(
            modifier = if (layout.wide) {
                // Where the card under it is: the main window's safe inset, not the dialog's own, which can differ.
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
                    .padding(start = layout.safeStart + 16.dp, top = if (layout.short) 4.dp else 12.dp, bottom = if (layout.short) 4.dp else 12.dp)
                    .width(layout.cardWidth)
                    .fillMaxHeight()
            } else Modifier.fillMaxSize(),
            shape = if (layout.wide) RoundedCornerShape(28.dp) else RoundedCornerShape(0.dp),
            color = polaris.paper,
        ) {
            // A reading width on a wide phone layout (tall-open, a tablet held tall), as the sheet has.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 640.dp).fillMaxSize().windowInsetsPadding(
                        if (layout.wide) WindowInsets(0) else WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    ),
                ) { content() }
            }
        }
    }
}

/** The bottom padding for a cover page's list: the keyboard or the navigation bar, unless wide (a card). */
@Composable
fun coverBottom(): androidx.compose.ui.unit.Dp = 24.dp + coverInset()

/**
 * For a cover page's list that has text fields: the list ends above the keyboard while it is up
 * (so the field being typed in is scrolled into what is left showing), and otherwise runs to the
 * foot of the page with [coverBottom] under its last row. Returns the list's bottom padding and
 * its content's.
 */
@Composable
fun coverKeyboardPadding(): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
    // Wide, the page is a card whose window already ends above the keyboard.
    if (LocalWindowLayout.current.wide) return 0.dp to coverBottom()
    val keyboard = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    return if (keyboard > 0.dp) keyboard to 24.dp else 0.dp to coverBottom()
}

/** What covers the foot of a cover page: the keyboard or the navigation bar; nothing when wide (a card). */
@Composable
fun coverInset(): androidx.compose.ui.unit.Dp =
    if (LocalWindowLayout.current.wide) 0.dp else WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding()

/**
 * Settings (iOS's `SettingsView`, as Android lists): appearance, sources, keys and the Transitous
 * contact and the alerts server, saved places, the data credits the sources ask for, and the
 * licences. Alerts for your lines open from the first row.
 */
@Composable
fun SettingsScreen(graph: AppGraph, shell: ShellViewModel, night: Boolean, openSearch: (SearchFor) -> Unit, openLicences: () -> Unit, openAlerts: () -> Unit = {}, onClose: () -> Unit) {
    val model: SettingsViewModel = viewModel(factory = factory { SettingsViewModel(it, graph) })
    val ui by model.ui.collectAsStateWithLifecycle()
    val places by shell.places.collectAsStateWithLifecycle()
    val c = polaris
    CoverPage(night) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Symbol(R.drawable.sym_arrow_back, stringResource(R.string.settings_close)) }
            Text(stringResource(R.string.settings_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = c.ink)
        }
        val list = rememberLazyListState()
        val (aboveKeyboard, underLast) = coverKeyboardPadding()
        LazyColumn(
            Modifier.fillMaxSize().padding(bottom = aboveKeyboard),
            state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = underLast),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.lost.isNotEmpty()) item(key = "lost") { LostNotice(ui.lost, model::acknowledgeLost) }

            item(key = "alerts") {
                val alerts by graph.alerts.state.collectAsStateWithLifecycle()
                Column(Modifier.fillMaxWidth().surface(c)) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_alerts)) },
                        supportingContent = {
                            Text(
                                if (alerts.alerts.isEmpty()) stringResource(R.string.settings_alerts_detail_none)
                                else androidx.compose.ui.res.pluralStringResource(R.plurals.settings_alerts_count, alerts.alerts.size, alerts.alerts.size.toString()),
                            )
                        },
                        leadingContent = { Symbol(R.drawable.sym_notifications, null, tint = c.tint) },
                        trailingContent = { Symbol(R.drawable.sym_chevron_right, null, tint = c.inkFaint) },
                        colors = ListItemDefaults.colors(containerColor = c.surface),
                        modifier = Modifier.clickable(role = Role.Button, onClick = openAlerts),
                    )
                }
            }

            item(key = "appearance-h") { SectionHeader(stringResource(R.string.settings_appearance)) }
            item(key = "appearance") {
                val labels = listOf(R.string.settings_appearance_system, R.string.settings_appearance_light, R.string.settings_appearance_dark)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Appearance.entries.forEachIndexed { i, a ->
                        SegmentedButton(
                            selected = ui.appearance == a,
                            enabled = ui.loaded,
                            onClick = { model.setAppearance(a) },
                            shape = SegmentedButtonDefaults.itemShape(i, Appearance.entries.size),
                        ) { Text(stringResource(labels[i]), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }

            item(key = "sources-h") { SectionHeader(stringResource(R.string.settings_sources)) }
            item(key = "sources") {
                Column(Modifier.fillMaxWidth().surface(c)) {
                    ui.enabled.keys.forEachIndexed { i, provider ->
                        if (i > 0) HorizontalDivider(color = c.hairline)
                        // Google without a key sends nothing: its switch says so rather than showing on.
                        val needsKey = provider == ProviderID.google && !ui.googleUsable
                        SwitchRow(
                            title = provider.displayName,
                            detail = stringResource(
                                when {
                                    needsKey && ui.googleUnreadable -> R.string.settings_google_unreadable
                                    needsKey -> R.string.settings_google_needs_key
                                    provider == ProviderID.google -> R.string.settings_source_google
                                    provider == ProviderID.tfl -> R.string.settings_source_tfl
                                    else -> R.string.settings_source_transitous
                                },
                            ),
                            checked = ui.enabled[provider] == true && !needsKey,
                            enabled = ui.loaded && !needsKey,
                            leading = { Badge(provider) },
                        ) { model.setEnabled(provider, it) }
                    }
                    HorizontalDivider(color = c.hairline)
                    SwitchRow(stringResource(R.string.settings_deep), stringResource(R.string.settings_deep_detail), ui.deepSearch, enabled = ui.loaded, badgeSpace = true) { model.setDeepSearch(it) }
                }
            }
            item(key = "google-count") {
                val number = java.text.NumberFormat.getIntegerInstance()
                Text(
                    stringResource(
                        R.string.settings_google_count,
                        number.format(ui.googleUsed), number.format(GoogleAllowance.monthlyCap),
                        number.format(GoogleAllowance.deepUntil), number.format(GoogleAllowance.monthlyCap - GoogleAllowance.reserve),
                    ),
                    Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = c.inkSoft,
                )
            }

            item(key = "keys-h") { SectionHeader(stringResource(R.string.settings_connections)) }
            connectionItems(ui, model, graph, showLater = false)

            item(key = "places-h") { SectionHeader(stringResource(R.string.settings_places)) }
            item(key = "places") {
                Column(Modifier.fillMaxWidth().surface(c)) {
                    PlaceRow(stringResource(R.string.settings_home), R.drawable.sym_home, places.home?.name, set = { openSearch(SearchFor.Home) }, clear = { shell.setHome(null) })
                    HorizontalDivider(color = c.hairline)
                    PlaceRow(stringResource(R.string.settings_work), R.drawable.sym_work, places.work?.name, set = { openSearch(SearchFor.Work) }, clear = { shell.setWork(null) })
                }
            }

            item(key = "data-h") { SectionHeader(stringResource(R.string.settings_data)) }
            item(key = "data") { DataCreditsFull(Modifier.padding(horizontal = 4.dp)) }

            item(key = "about-h") { SectionHeader(stringResource(R.string.settings_about)) }
            item(key = "about") {
                Column(Modifier.fillMaxWidth().surface(c)) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_licences)) },
                        supportingContent = { Text(stringResource(R.string.settings_licences_detail)) },
                        trailingContent = { Symbol(R.drawable.sym_chevron_right, null, tint = c.inkFaint) },
                        colors = ListItemDefaults.colors(containerColor = c.surface),
                        modifier = Modifier.clickable(role = Role.Button, onClick = openLicences),
                    )
                    HorizontalDivider(color = c.hairline)
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_version, graph.version)) },
                        colors = ListItemDefaults.colors(containerColor = c.surface),
                    )
                }
            }
        }
    }
}

/**
 * The optional things a person can set, the same items in the same words in setup and Settings
 * (iOS's `ConnectionSections`): each says what it is for, that it's optional and where to get it;
 * in setup, that it can be added later in Settings too.
 */
fun LazyListScope.connectionItems(ui: SettingsViewModel.Ui, model: SettingsViewModel, graph: AppGraph, showLater: Boolean) {
    item(key = "key-google") {
        KeyItem(
            SettingsViewModel.Item.google, R.string.item_google_title, R.string.item_google_purpose, R.string.item_google_source, ui, model, showLater,
            // This build's package and certificate, for restricting a key: in Settings, not in first-run setup.
            extra = if (showLater) null else graph.identity.certificateSha1?.let { sha -> stringResource(R.string.item_google_identity, graph.identity.packageName, sha) },
        )
    }
    item(key = "key-tfl") { KeyItem(SettingsViewModel.Item.tfl, R.string.item_tfl_title, R.string.item_tfl_purpose, R.string.item_tfl_source, ui, model, showLater) }
    item(key = "contact") { ContactItem(ui, model, showLater) }
    item(key = "alerts-server") { AlertServerItem(graph, showLater) }
}

@Composable
private fun Caption(purpose: Int, source: Int, showLater: Boolean, extra: String? = null) {
    val c = polaris
    // The same left edge as the field's helper text and the Remove button: 16 dp.
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(purpose), style = MaterialTheme.typography.bodySmall, color = c.ink)
        Text(stringResource(R.string.item_optional, stringResource(source)), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        extra?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkSoft) }
        if (showLater) Text(stringResource(R.string.item_later), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
    }
}

/**
 * A key: a password-style field with a show toggle, saved as it is typed, beating any built into
 * the app. The text lives here while it is typed (fast typing never loses a character or moves
 * the cursor) and goes to the view model, which trims it and saves it. Not saveable: a key is
 * never put in the saved instance state. Over a key that can't be read, typing only drafts and
 * "Replace the saved one" commits it; a saved key has Remove.
 */
@Composable
private fun KeyItem(item: SettingsViewModel.Item, title: Int, purpose: Int, source: Int, ui: SettingsViewModel.Ui, model: SettingsViewModel, showLater: Boolean, extra: String? = null) {
    val c = polaris
    val field = ui.field(item)
    var text by remember(item, ui.loaded) { mutableStateOf(field.text) }
    var shown by rememberSaveable(item) { mutableStateOf(false) }
    val name = stringResource(title)
    val stateText = when (field.state) {
        SettingsViewModel.KeyState.builtIn -> stringResource(R.string.key_built_in)
        SettingsViewModel.KeyState.notSet -> stringResource(R.string.key_not_set)
        SettingsViewModel.KeyState.saved -> stringResource(R.string.key_saved)
        SettingsViewModel.KeyState.unreadable -> stringResource(R.string.key_unreadable)
        SettingsViewModel.KeyState.notSaved -> stringResource(R.string.key_not_saved)
    }
    val stateColor = when (field.state) {
        SettingsViewModel.KeyState.builtIn -> c.live
        SettingsViewModel.KeyState.unreadable, SettingsViewModel.KeyState.notSaved -> c.warning
        else -> c.inkSoft
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; model.type(item, it) },
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = stateText },
            enabled = ui.loaded,
            label = { Text(name) },
            singleLine = true,
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = if (text.isNotEmpty()) ({
                val label = stringResource(if (shown) R.string.key_hide_label else R.string.key_show_label, name)
                TextButton(onClick = { shown = !shown }, modifier = Modifier.semantics { contentDescription = label }) {
                    Text(stringResource(if (shown) R.string.key_hide else R.string.key_show))
                }
            }) else null,
            supportingText = { Text(stateText, color = stateColor) },
        )
        KeyActions(item, field, text, name, model) { text = it }
        Caption(purpose, source, showLater, extra)
    }
}

/** Replace (over an unreadable value, with a draft) or Remove (a saved value), each a deliberate tap. */
@Composable
private fun KeyActions(item: SettingsViewModel.Item, field: SettingsViewModel.Field, text: String, name: String, model: SettingsViewModel, setText: (String) -> Unit) {
    when {
        field.state == SettingsViewModel.KeyState.unreadable && text.isNotBlank() ->
            TextButton(onClick = { model.replace(item) }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 16.dp)) { Text(stringResource(R.string.key_replace)) }
        field.state == SettingsViewModel.KeyState.saved && text.isNotEmpty() -> {
            val label = stringResource(R.string.key_remove_label, name)
            TextButton(onClick = { setText(""); model.remove(item) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label }, contentPadding = PaddingValues(horizontal = 16.dp)) {
                Text(stringResource(R.string.key_remove))
            }
        }
    }
}

@Composable
private fun ContactItem(ui: SettingsViewModel.Ui, model: SettingsViewModel, showLater: Boolean) {
    val c = polaris
    val field = ui.field(SettingsViewModel.Item.contact)
    var text by remember(ui.loaded) { mutableStateOf(field.text) }
    val name = stringResource(R.string.item_contact_title)
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; model.type(SettingsViewModel.Item.contact, it) },
            modifier = Modifier.fillMaxWidth(),
            enabled = ui.loaded,
            label = { Text(name) },
            placeholder = { Text(stringResource(R.string.item_contact_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
            isError = ui.contactInvalid,
            supportingText = when {
                ui.contactInvalid -> ({ Text(stringResource(R.string.item_contact_invalid), color = c.warning) })
                field.state == SettingsViewModel.KeyState.unreadable -> ({ Text(stringResource(R.string.key_unreadable), color = c.warning) })
                field.state == SettingsViewModel.KeyState.notSaved -> ({ Text(stringResource(R.string.key_not_saved), color = c.warning) })
                else -> null
            },
        )
        KeyActions(SettingsViewModel.Item.contact, field, text, name, model) { text = it }
        Caption(R.string.item_contact_purpose, R.string.item_contact_source, showLater)
    }
}

/**
 * The alerts server (iOS's alerts-server section): its address and its token, entered together
 * and used only on "Use this server", which checks the address, removes this phone from the
 * server it leaves and registers the alerts with the new one. Typing alone changes nothing. The
 * token is never put in the saved instance state, and never shown once saved.
 */
@Composable
private fun AlertServerItem(graph: AppGraph, showLater: Boolean) {
    val c = polaris
    val state by graph.alerts.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val hasBuiltIn = remember(graph) { polarisBuildHasServer() }
    // What is entered now, read off the main thread (it is sealed).
    val entered by androidx.compose.runtime.produceState<AppSettings.EnteredServer?>(null, state.serverLabel, state.configured) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { graph.settings.enteredAlertsServer }
    }
    val saved = entered as? AppSettings.EnteredServer.Saved
    var url by rememberSaveable(saved?.url) { mutableStateOf(saved?.url ?: "") }
    var token by remember(saved?.url) { mutableStateOf("") }
    var shown by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf<String?>(null) }
    val noToken = stringResource(R.string.item_server_no_token)
    val notSaved = stringResource(R.string.item_server_not_saved)
    val couldNotLeave = stringResource(R.string.item_server_could_not_leave, "%s")
    fun use(address: String, appToken: String) {
        scope.launch {
            busy = true
            problem = null
            note = null
            when (val result = graph.alerts.useServer(address, appToken)) {
                is AlertStore.UseResult.Used -> {
                    note = result.couldNotLeave?.let { couldNotLeave.format(it) }
                    token = ""
                    if (address.isBlank()) url = ""
                }
                is AlertStore.UseResult.Refused -> problem = result.problem.message
                AlertStore.UseResult.NoToken -> problem = noToken
                AlertStore.UseResult.NotSaved -> problem = notSaved
            }
            busy = false
        }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val stateText = when {
            entered == AppSettings.EnteredServer.Unreadable -> stringResource(R.string.item_server_state_unreadable)
            state.serverLabel == null -> stringResource(R.string.item_server_state_none)
            state.serverIsBuiltIn -> stringResource(R.string.item_server_state_built_in, state.serverLabel ?: "")
            else -> stringResource(R.string.item_server_state_entered, state.serverLabel ?: "")
        }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; problem = null },
            modifier = Modifier.fillMaxWidth().staysAboveKeyboard(),
            label = { Text(stringResource(R.string.item_server_title)) },
            placeholder = { Text(stringResource(R.string.item_server_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            isError = problem != null,
            supportingText = { Text(problem ?: stateText, color = if (problem != null || entered == AppSettings.EnteredServer.Unreadable) c.warning else c.inkSoft) },
        )
        val tokenName = stringResource(R.string.item_server_token)
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; problem = null },
            modifier = Modifier.fillMaxWidth().staysAboveKeyboard(),
            label = { Text(tokenName) },
            singleLine = true,
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = if (token.isNotEmpty()) ({
                val label = stringResource(if (shown) R.string.key_hide_label else R.string.key_show_label, tokenName)
                TextButton(onClick = { shown = !shown }, modifier = Modifier.semantics { contentDescription = label }) {
                    Text(stringResource(if (shown) R.string.key_hide else R.string.key_show))
                }
            }) else null,
        )
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilledTonalButton(
                onClick = { use(url, token) },
                enabled = !busy && url.isNotBlank() && token.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.item_server_use)) }
            if (saved != null || entered == AppSettings.EnteredServer.Unreadable) {
                TextButton(onClick = { use("", "") }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (hasBuiltIn) R.string.item_server_use_built_in else R.string.item_server_remove))
                }
            }
            if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
        }
        note?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = c.warning) }
        Caption(R.string.item_server_purpose, R.string.item_server_source, showLater)
    }
}

/** Whether this build has an alerts server built in (so removing an entered one goes back to it). */
private fun polarisBuildHasServer(): Boolean =
    AlertServer.resolve("", "", BuildConfig.POLARIS_ALERTS_URL, BuildConfig.POLARIS_ALERTS_TOKEN) != null

/**
 * A switch row: the badge in a fixed column, centred, so every title starts at the same place
 * ([badgeSpace] keeps a row without a badge, Deep search, on that edge too); the whole row
 * toggles. At the largest text the description takes the row's full width under the title, with
 * the badge beside the title, so it isn't squeezed to a few words a line.
 */
@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true, leading: (@Composable () -> Unit)? = null, badgeSpace: Boolean = false, onChange: (Boolean) -> Unit) {
    val c = polaris
    val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val badgeColumn = 40.dp * scale.coerceAtLeast(1f)
    val titleText: @Composable () -> Unit = { Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) c.ink else c.inkSoft) }
    val detailText: @Composable () -> Unit = { Text(detail, style = MaterialTheme.typography.bodyMedium, color = c.inkSoft) }
    val rowModifier = Modifier.fillMaxWidth()
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .heightIn(min = 56.dp)
        .padding(horizontal = 16.dp, vertical = 10.dp)
    if (scale >= 1.5f) {
        Column(rowModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { titleText() }
                leading?.invoke()
                Switch(checked = checked, onCheckedChange = null, enabled = enabled)
            }
            detailText()
        }
        return
    }
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (leading != null || badgeSpace) Box(Modifier.width(badgeColumn), contentAlignment = Alignment.Center) { leading?.invoke() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            titleText()
            detailText()
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A source's badge for Settings: the same letters, in ink, readable in both appearances. */
@Composable
private fun Badge(provider: ProviderID) {
    Text(
        provider.badge,
        // Decorative: the row's title already names the source, and TalkBack reads the merged row once.
        Modifier
            .badgeBackground()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clearAndSetSemantics {},
        style = MaterialTheme.typography.labelMedium,
        color = polaris.ink,
        maxLines = 1,
    )
}

@Composable
private fun Modifier.badgeBackground(): Modifier =
    this.then(Modifier.background(polaris.ink.copy(alpha = if (polaris.isNight) 0.18f else 0.08f), androidx.compose.foundation.shape.CircleShape))

@Composable
private fun PlaceRow(title: String, icon: Int, name: String?, set: () -> Unit, clear: () -> Unit) {
    val c = polaris
    val change = stringResource(R.string.settings_place_change, title)
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(name ?: stringResource(R.string.settings_place_set), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Symbol(icon, null, tint = c.tint) },
        trailingContent = if (name != null) ({
            IconButton(onClick = clear) { Symbol(R.drawable.sym_close, stringResource(R.string.settings_place_clear, title), tint = c.inkSoft) }
        }) else null,
        colors = ListItemDefaults.colors(containerColor = c.surface),
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = change, onClick = set),
    )
}

@Composable
private fun LostNotice(lost: Set<String>, ok: () -> Unit) {
    val c = polaris
    val names = lost.map { account ->
        when (account) {
            AppSettings.GOOGLE -> stringResource(R.string.item_google_title)
            AppSettings.TFL -> stringResource(R.string.item_tfl_title)
            AppSettings.CONTACT -> stringResource(R.string.item_contact_title)
            else -> account
        }
    }
    Column(Modifier.fillMaxWidth().surface(c).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.key_lost, names.joinToString(", ")), style = MaterialTheme.typography.bodyMedium, color = c.ink)
        TextButton(onClick = ok, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.key_lost_ok)) }
    }
}

/**
 * The credits each source's terms ask for, in full: TfL's statement with its OS and Geomni lines
 * (iOS Settings' exact words), Transitous with the link to its sources and OpenStreetMap's credit,
 * and the map's own credit (which the map also shows).
 */
@Composable
fun DataCreditsFull(modifier: Modifier = Modifier) {
    val c = polaris
    val link = TextLinkStyles(SpanStyle(color = c.tint))
    val routes = buildAnnotatedString {
        append(stringResource(R.string.credits_routes_start))
        withLink(LinkAnnotation.Url("https://transitous.org/sources/", link)) { append(stringResource(R.string.credits_routes_sources)) }
        append(stringResource(R.string.credits_routes_middle))
        withLink(LinkAnnotation.Url("https://www.openstreetmap.org/copyright", link)) { append(stringResource(R.string.credits_routes_osm)) }
        append(stringResource(R.string.credits_routes_end))
    }
    val map = buildAnnotatedString {
        append(stringResource(R.string.credits_map_start))
        withLink(LinkAnnotation.Url("https://openfreemap.org", link)) { append(stringResource(R.string.credits_map_openfreemap)) }
        append(" ")
        withLink(LinkAnnotation.Url("https://www.openmaptiles.org/", link)) { append(stringResource(R.string.credits_map_openmaptiles)) }
        append(" ")
        withLink(LinkAnnotation.Url("https://www.openstreetmap.org/copyright", link)) { append(stringResource(R.string.credits_map_data)) }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(routes, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        Text(stringResource(R.string.sources_credits_tfl).trim(), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        Text(map, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        Text(stringResource(R.string.credits_places), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
    }
}

/** One block of the notices: a heading, a list item, a paragraph or a preformatted licence text. */
data class NoticeBlock(val text: String, val heading: Boolean = false, val bullet: Boolean = false, val preformatted: Boolean = false)

/**
 * The notices file as the app shows it: the passages between `<!-- repo-only -->` and
 * `<!-- /repo-only -->` (written for someone reading the repository: paths, "this folder") left
 * out, and any other comment left out up to its own `-->`; lines joined into paragraphs and list
 * items (`- ` and `* `); headings apart; a link shown as its words and its address (once, when
 * they are the same); `---` rules and `=====` rows taken as breaks; fenced licence texts kept line
 * for line; `**` and backticks dropped. A repo-only block or fence still open at the end of the
 * file is an error, never silently dropped text. One file is kept, so the repository's notices
 * and the app's can't drift apart. Plain, so it is tested.
 */
fun noticeBlocks(lines: List<String>): List<NoticeBlock> {
    val blocks = mutableListOf<NoticeBlock>()
    var current: StringBuilder? = null
    var bullet = false
    var repoOnly = false
    var comment = false
    var fence: StringBuilder? = null
    val link = Regex("""\[([^\]]+)]\(([^)]+)\)""")
    fun clean(t: String) = link.replace(t.replace("**", "").replace("`", "")) { m ->
        if (m.groupValues[1] == m.groupValues[2]) m.groupValues[1] else "${m.groupValues[1]} (${m.groupValues[2]})"
    }.trim()
    fun flush() {
        current?.let { blocks += NoticeBlock(clean(it.toString()), bullet = bullet) }
        current = null
        bullet = false
    }
    for (raw in lines) {
        val line = raw.trimEnd()
        val trimmed = line.trim()
        when {
            fence != null -> if (trimmed.startsWith("```")) { blocks += NoticeBlock(fence.toString().trimEnd(), preformatted = true); fence = null } else fence.append(line).append('\n')
            comment -> if (trimmed.endsWith("-->")) comment = false
            trimmed == "<!-- repo-only -->" -> { flush(); repoOnly = true }
            trimmed == "<!-- /repo-only -->" -> { check(repoOnly) { "a /repo-only with no repo-only before it" }; repoOnly = false }
            repoOnly -> Unit
            trimmed.startsWith("<!--") -> { flush(); if (!trimmed.endsWith("-->")) comment = true }
            trimmed.startsWith("```") -> { flush(); fence = StringBuilder() }
            line.isBlank() || Regex("^(-{3,}|={3,})$").matches(trimmed) -> flush()
            line.startsWith("#") -> { flush(); blocks += NoticeBlock(clean(line.trimStart('#', ' ')), heading = true) }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> { flush(); current = StringBuilder(trimmed.drop(2)); bullet = true }
            current == null -> current = StringBuilder(trimmed)
            // An entry's URL and licence each on their own line, as written.
            trimmed.startsWith("URL:") || trimmed.startsWith("License:") -> current!!.append('\n').append(trimmed)
            else -> current!!.append(' ').append(trimmed)
        }
    }
    check(!repoOnly) { "a repo-only block is never closed" }
    check(fence == null) { "a fenced block is never closed" }
    check(!comment) { "a comment is never closed" }
    flush()
    return blocks
}

/** The third-party notices shipped with the app (android/THIRD_PARTY_NOTICES.md), every word of them. */
@Composable
fun LicencesScreen(night: Boolean, onClose: () -> Unit) {
    val c = polaris
    val context = LocalContext.current
    val blocks by androidx.compose.runtime.produceState<List<NoticeBlock>?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { noticeBlocks(context.assets.open("THIRD_PARTY_NOTICES.md").bufferedReader().readLines()).drop(1) }.getOrElse { emptyList() }
        }
    }
    CoverPage(night) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Symbol(R.drawable.sym_arrow_back, stringResource(R.string.licences_back)) }
            Text(stringResource(R.string.licences_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = c.ink)
        }
        val shown = blocks
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = coverBottom()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (shown != null && shown.isEmpty()) item { Text(stringResource(R.string.licences_missing), color = c.inkSoft) }
            if (!shown.isNullOrEmpty()) item { Text(stringResource(R.string.licences_intro), style = MaterialTheme.typography.bodyMedium, color = c.ink) }
            shown?.let { all ->
                items(all.size) { i ->
                    val b = all[i]
                    when {
                        b.heading -> Text(b.text, Modifier.padding(top = 12.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = c.ink)
                        b.preformatted -> Text(b.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = c.inkSoft)
                        b.bullet -> Row {
                            Text("•", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
                            Text(b.text, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
                        }
                        else -> Text(b.text, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
                    }
                }
            }
        }
    }
}
