package polaris.android.settings

import polaris.engine.AppSettings
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import polaris.android.ui.surface
import polaris.engine.Appearance
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
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp)
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

/** What covers the foot of a cover page: the keyboard or the navigation bar; nothing when wide (a card). */
@Composable
fun coverInset(): androidx.compose.ui.unit.Dp =
    if (LocalWindowLayout.current.wide) 0.dp else WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding()

/**
 * Settings (iOS's `SettingsView`, as Android lists): appearance, sources, keys and the Transitous
 * contact, saved places, the data credits the sources ask for, and the licences. No alert-server
 * or push fields yet: alerts come in a later update.
 */
@Composable
fun SettingsScreen(graph: AppGraph, shell: ShellViewModel, night: Boolean, openSearch: (SearchFor) -> Unit, openLicences: () -> Unit, onClose: () -> Unit) {
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
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = coverBottom()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.lost.isNotEmpty()) item(key = "lost") { LostNotice(ui.lost, model::acknowledgeLost) }

            item(key = "appearance-h") { SectionHeader(stringResource(R.string.settings_appearance)) }
            item(key = "appearance") {
                val labels = listOf(R.string.settings_appearance_system, R.string.settings_appearance_light, R.string.settings_appearance_dark)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Appearance.entries.forEachIndexed { i, a ->
                        SegmentedButton(
                            selected = ui.appearance == a,
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
                        SwitchRow(
                            title = provider.displayName,
                            detail = stringResource(
                                when (provider) {
                                    ProviderID.google -> R.string.settings_source_google
                                    ProviderID.tfl -> R.string.settings_source_tfl
                                    else -> R.string.settings_source_transitous
                                },
                            ),
                            checked = ui.enabled[provider] == true,
                            leading = { SourceBadge(provider) },
                        ) { model.setEnabled(provider, it) }
                    }
                    HorizontalDivider(color = c.hairline)
                    SwitchRow(stringResource(R.string.settings_deep), stringResource(R.string.settings_deep_detail), ui.deepSearch) { model.setDeepSearch(it) }
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
            extra = graph.identity.certificateSha1?.let { sha -> stringResource(R.string.item_google_identity, graph.identity.packageName, sha) },
        )
    }
    item(key = "key-tfl") { KeyItem(SettingsViewModel.Item.tfl, R.string.item_tfl_title, R.string.item_tfl_purpose, R.string.item_tfl_source, ui, model, showLater) }
    item(key = "contact") { ContactItem(ui, model, showLater) }
}

@Composable
private fun Caption(purpose: Int, source: Int, showLater: Boolean, extra: String? = null) {
    val c = polaris
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(purpose), style = MaterialTheme.typography.bodySmall, color = c.ink)
        Text(stringResource(R.string.item_optional, stringResource(source)), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        extra?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkSoft) }
        if (showLater) Text(stringResource(R.string.item_later), style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
    }
}

/** A key: a password-style field with a show toggle, saved as it is typed, beating any built into the app. */
@Composable
private fun KeyItem(item: SettingsViewModel.Item, title: Int, purpose: Int, source: Int, ui: SettingsViewModel.Ui, model: SettingsViewModel, showLater: Boolean, extra: String? = null) {
    val c = polaris
    val field = ui.field(item)
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
            value = field.text,
            onValueChange = { model.type(item, it.trim()) },
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = stateText },
            enabled = ui.loaded,
            label = { Text(name) },
            singleLine = true,
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = if (field.text.isNotEmpty()) ({
                val label = stringResource(if (shown) R.string.key_hide_label else R.string.key_show_label, name)
                TextButton(onClick = { shown = !shown }, modifier = Modifier.semantics { contentDescription = label }) {
                    Text(stringResource(if (shown) R.string.key_hide else R.string.key_show))
                }
            }) else null,
            supportingText = { Text(stateText, color = stateColor) },
        )
        Caption(purpose, source, showLater, extra)
    }
}

@Composable
private fun ContactItem(ui: SettingsViewModel.Ui, model: SettingsViewModel, showLater: Boolean) {
    val c = polaris
    val field = ui.field(SettingsViewModel.Item.contact)
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = field.text,
            onValueChange = { model.type(SettingsViewModel.Item.contact, it) },
            modifier = Modifier.fillMaxWidth(),
            enabled = ui.loaded,
            label = { Text(stringResource(R.string.item_contact_title)) },
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
        Caption(R.string.item_contact_purpose, R.string.item_contact_source, showLater)
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, leading: (@Composable () -> Unit)? = null, onChange: (Boolean) -> Unit) {
    val c = polaris
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        leadingContent = leading,
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = c.surface),
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

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

/** One block of the notices: a heading, a list item or a paragraph, with Markdown's marks taken out. */
data class NoticeBlock(val text: String, val heading: Boolean = false, val bullet: Boolean = false)

/**
 * The notices file (Markdown, wrapped at 100 columns) as blocks to show: lines joined into
 * paragraphs and list items, headings apart, `**` and backticks dropped. Plain, so it is tested.
 */
fun noticeBlocks(lines: List<String>): List<NoticeBlock> {
    val blocks = mutableListOf<NoticeBlock>()
    var current: StringBuilder? = null
    var bullet = false
    fun flush() {
        current?.let { blocks += NoticeBlock(it.toString().replace("**", "").replace("`", "").trim(), bullet = bullet) }
        current = null
        bullet = false
    }
    for (raw in lines) {
        val line = raw.trimEnd()
        when {
            line.isBlank() -> flush()
            line.startsWith("#") -> { flush(); blocks += NoticeBlock(line.trimStart('#', ' '), heading = true) }
            line.trimStart().startsWith("- ") -> { flush(); current = StringBuilder(line.trimStart().removePrefix("- ")); bullet = true }
            current == null -> current = StringBuilder(line.trim())
            else -> current!!.append(' ').append(line.trim())
        }
    }
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
            runCatching { noticeBlocks(context.assets.open("THIRD_PARTY_NOTICES.md").bufferedReader().readLines()) }.getOrElse { emptyList() }
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
            shown?.let { all ->
                items(all.size) { i ->
                    val b = all[i]
                    when {
                        b.heading -> Text(b.text, Modifier.padding(top = 12.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = c.ink)
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
