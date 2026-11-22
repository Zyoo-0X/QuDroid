/* Modified for AEmulator Sunset through 2026-10-03: host display options, fork links, catalog,
 * experimental unlock and local license/notices.
 * Modified for AEmulator Plus, 2026-10-12: VM screen themes (replace the navigation bar style; merged from Super Plus). GPL-3.0; see NOTICE.md. */
package app.aemu.ui

import app.aemu.core.userMessageOrNull
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import app.aemu.update.AppUpdateManager
import app.aemu.update.ReleaseInfo
import app.aemu.update.UpdateState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.aemu.AppPrefs
import app.aemu.BuildConfig
import app.aemu.R

/** Modified for AEmulator Sunset on 2026-09-30: fork links and attribution.
 * Modified for AEmulator Plus on 2026-10-04: Plus links; Sunset and original credited as upstream. */
object Links {
    const val GITHUB = "https://github.com/Zyoo-0X/QuDroid"
    const val AUTHOR = "https://github.com/Zyoo-0X"
    const val PLUS = "https://github.com/somerandomusernam2/AEmulator-Plus"
    const val SUNSET = "https://github.com/drel4/AEmulator-Sunset"
    const val UPSTREAM = "https://github.com/uxazu/AEmulator"
    const val ORIGINAL = "https://t.me/istratiit_ech"
}

private enum class SettingsSection {
    MAIN,
    UPDATES,
    APPEARANCE,
    DEFAULTS,
    SHIZUKU,
    LINKS
}

class AppSettingsActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppPrefs.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AemuTheme { AppSettings(onBack = ::finish, onRestyle = ::restart) } }
    }

    /** Язык применяется пересозданием активности для применения нового ContextWrapper с локалью. */
    private fun restart() {
        recreate()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSettings(onBack: () -> Unit, onRestyle: () -> Unit) {
    val ctx = LocalContext.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var section by remember { mutableStateOf(SettingsSection.MAIN) }
    var langDialog by remember { mutableStateOf(false) }
    var legalDialog by remember { mutableStateOf(false) }
    var defaults by remember { mutableStateOf(AppPrefs.defaults(ctx)) }
    var theme by remember { mutableStateOf(AppPrefs.theme(ctx)) }
    var dynamic by remember { mutableStateOf(AppPrefs.dynamicColor(ctx)) }
    var accent by remember { mutableStateOf(AppPrefs.accentColor(ctx)) }
    var accentDialog by remember { mutableStateOf(false) }
    var hostUi by remember { mutableStateOf(AppPrefs.hostUiOptions(ctx)) }
    val scope = rememberCoroutineScope()
    var autoUpdates by remember { mutableStateOf(AppPrefs.autoCheckUpdates(ctx)) }
    var library3d by remember { mutableStateOf(AppPrefs.library3d(ctx)) }
    var builtinBrowser by remember { mutableStateOf(AppPrefs.builtinBrowser(ctx)) }
    var newHome by remember { mutableStateOf(AppPrefs.newHome(ctx)) }
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    BackHandler(enabled = section != SettingsSection.MAIN) {
        section = SettingsSection.MAIN
    }

    if (accentDialog) {
        ColorPickerDialog(
            initial = if (accent == 0) Color(0xFFF4511E) else Color(accent),
            onDismiss = { accentDialog = false },
            onReset = { accent = 0; AppPrefs.setAccentColor(ctx, 0); accentDialog = false; onRestyle() },
            onApply = { c ->
                val argb = c.toArgb() or 0xFF000000.toInt()
                accent = argb; AppPrefs.setAccentColor(ctx, argb); accentDialog = false; onRestyle()
            },
        )
    }

    fun checkForUpdates() {
        updateState = UpdateState.Checking
        scope.launch {
            val res = AppUpdateManager.checkLatestRelease()
            res.fold(
                onSuccess = { rel ->
                    if (rel != null) {
                        if (AppUpdateManager.isNewerVersion(rel.versionName, BuildConfig.VERSION_NAME)) {
                            updateState = UpdateState.Available(rel)
                        } else {
                            updateState = UpdateState.UpToDate(BuildConfig.VERSION_NAME, rel.tagName)
                        }
                    } else {
                        updateState = UpdateState.UpToDate(BuildConfig.VERSION_NAME, "")
                    }
                },
                onFailure = { err ->
                    updateState = UpdateState.Error(err.userMessageOrNull(ctx) ?: ctx.getString(R.string.update_network_error))
                }
            )
        }
    }

    fun startDownload(rel: ReleaseInfo) {
        updateState = UpdateState.Downloading(rel, 0f, 0L, rel.sizeBytes)
        downloadJob = scope.launch {
            try {
                val file = AppUpdateManager.downloadApk(ctx, rel) { progress, downloaded, total ->
                    updateState = UpdateState.Downloading(rel, progress, downloaded, total)
                }
                updateState = UpdateState.ReadyToInstall(rel, file)
                AppUpdateManager.installApk(ctx, file)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    updateState = UpdateState.Error(e.userMessageOrNull(ctx) ?: ctx.getString(R.string.update_download_failed_generic))
                }
            }
        }
    }

    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    val langTag = AppPrefs.language(ctx)
    val langName = AppPrefs.LANGUAGES.firstOrNull { it.first == langTag }?.second?.ifEmpty { null } ?: stringResource(R.string.as_lang_system)

    val titleText = when (section) {
        SettingsSection.MAIN -> stringResource(R.string.settings)
        SettingsSection.UPDATES -> stringResource(R.string.as_updates)
        SettingsSection.APPEARANCE -> stringResource(R.string.as_appearance)
        SettingsSection.DEFAULTS -> stringResource(R.string.as_defaults)
        SettingsSection.SHIZUKU -> "Shizuku"
        SettingsSection.LINKS -> stringResource(R.string.as_links)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        if (section != SettingsSection.MAIN) section = SettingsSection.MAIN else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                    }
                },
                title = { Text(titleText, style = MaterialTheme.typography.titleLarge, maxLines = 1) },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (section) {
                SettingsSection.MAIN -> {
                    item {
                        Section("Settings Sections") {
                            Row_(Icons.Rounded.List, stringResource(R.string.catalog_title), stringResource(R.string.catalog_open)) {
                                ctx.startActivity(Intent(ctx, RomCatalogActivity::class.java))
                            }
                            Row_(Icons.Rounded.SystemUpdate, stringResource(R.string.as_updates), "Version ${BuildConfig.VERSION_NAME}") {
                                section = SettingsSection.UPDATES
                            }
                            Row_(Icons.Rounded.Palette, stringResource(R.string.as_appearance), "Theme, accent color, layout & language") {
                                section = SettingsSection.APPEARANCE
                            }
                            Row_(Icons.Rounded.Tune, stringResource(R.string.as_defaults), stringResource(R.string.as_defaults_sub)) {
                                section = SettingsSection.DEFAULTS
                            }
                            Row_(Icons.Rounded.Lock, "Shizuku", "Lock parts of the phone while a machine is on screen") {
                                section = SettingsSection.SHIZUKU
                            }
                            Row_(Icons.Rounded.Info, stringResource(R.string.as_links), "Author, repositories & licenses") {
                                section = SettingsSection.LINKS
                            }
                        }
                    }
                }

                SettingsSection.UPDATES -> {
                    item {
                        Section(stringResource(R.string.as_updates)) {
                            Toggle(
                                stringResource(R.string.as_auto_updates),
                                stringResource(R.string.as_auto_updates_sub),
                                autoUpdates
                            ) {
                                autoUpdates = it
                                AppPrefs.setAutoCheckUpdates(ctx, it)
                            }
                            Row_(
                                Icons.Rounded.SystemUpdate,
                                stringResource(R.string.as_check_updates),
                                stringResource(R.string.as_check_updates_sub, BuildConfig.VERSION_NAME)
                            ) {
                                checkForUpdates()
                            }
                        }
                    }
                }

                SettingsSection.APPEARANCE -> {
                    item {
                        Section(stringResource(R.string.as_appearance)) {
                            Toggle(stringResource(R.string.as_new_home), stringResource(R.string.as_new_home_sub), newHome) {
                                newHome = it; AppPrefs.setNewHome(ctx, it)
                            }
                            Toggle(stringResource(R.string.as_library_3d), stringResource(R.string.as_library_3d_sub), library3d) {
                                library3d = it; AppPrefs.setLibrary3d(ctx, it)
                            }
                            Toggle(stringResource(R.string.as_builtin_browser), stringResource(R.string.as_builtin_browser_sub), builtinBrowser) {
                                builtinBrowser = it; AppPrefs.setBuiltinBrowser(ctx, it)
                            }
                            if (app.aemu.core.HostDisplayPolicy.rotateWindow(ctx.resources.configuration.smallestScreenWidthDp)) {
                                Toggle(stringResource(R.string.as_tablet_navbar_rotation), stringResource(R.string.as_tablet_navbar_rotation_sub), hostUi.tabletNavbarRotation) {
                                    hostUi = hostUi.copy(tabletNavbarRotation = it); AppPrefs.setHostUiOptions(ctx, hostUi)
                                }
                            }
                            Toggle(stringResource(R.string.as_cutout_barrier), stringResource(R.string.as_cutout_barrier_sub), hostUi.cutoutBarrier) {
                                hostUi = hostUi.copy(cutoutBarrier = it); AppPrefs.setHostUiOptions(ctx, hostUi)
                            }
                            // the navigation bar style is now part of the VM screen theme (Original, Sunset, 2.x-5.x eras, own themes)
                            VmThemeRow()
                            Row_(Icons.Rounded.Translate, stringResource(R.string.as_language), langName) { langDialog = true }
                            Text(stringResource(R.string.as_theme), style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp))
                            val options = listOf(R.string.as_theme_system, R.string.as_theme_light, R.string.as_theme_dark)
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                                options.forEachIndexed { i, res ->
                                    ToggleButton(
                                        checked = theme == i,
                                        onCheckedChange = { if (theme != i) { theme = i; AppPrefs.setTheme(ctx, i) } },
                                        shapes = when (i) {
                                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                            options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                        },
                                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                    ) {
                                        // одна строка: длинные названия («Системная») не переносятся по буквам
                                        Text(stringResource(res), maxLines = 1, softWrap = false,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.as_accent)) },
                                supportingContent = { Text(if (accent == 0) stringResource(R.string.as_accent_default)
                                    else "#%06X".format(accent and 0xFFFFFF)) },
                                trailingContent = {
                                    androidx.compose.foundation.layout.Box(Modifier.size(28.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(if (accent == 0) Color(0xFFF4511E) else Color(accent)))
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable { accentDialog = true },
                            )
                            if (Build.VERSION.SDK_INT >= 31 && accent == 0) {
                                Toggle(stringResource(R.string.as_dynamic), stringResource(R.string.as_dynamic_sub), dynamic) {
                                    dynamic = it; AppPrefs.setDynamicColor(ctx, it)
                                }
                            }
                        }
                    }
                }

                SettingsSection.DEFAULTS -> {
                    item {
                        Section(stringResource(R.string.as_defaults), stringResource(R.string.as_defaults_sub)) {
                            fun set(s: app.aemu.core.VmSettings) { defaults = s; AppPrefs.setDefaults(ctx, s) }
                            Toggle(stringResource(R.string.vs_gpu), stringResource(R.string.vs_gpu_sub), defaults.gpu) { set(defaults.copy(gpu = it)) }
                            Toggle(stringResource(R.string.vs_jit), stringResource(R.string.vs_jit_sub), defaults.jit) { set(defaults.copy(jit = it)) }
                            Toggle(stringResource(R.string.vs_proxy), stringResource(R.string.vs_proxy_sub), defaults.netProxy) { set(defaults.copy(netProxy = it)) }
                            Toggle(stringResource(R.string.vs_nav), stringResource(R.string.vs_nav_sub), defaults.showNavBar) { set(defaults.copy(showNavBar = it)) }
                            NavigationSettings(defaults, set = ::set)
                            Toggle(stringResource(R.string.vs_awake), stringResource(R.string.vs_awake_sub), defaults.keepScreenOn) { set(defaults.copy(keepScreenOn = it)) }
                            Toggle(stringResource(R.string.as_led), stringResource(R.string.as_led_sub), defaults.showLed) { set(defaults.copy(showLed = it)) }
                        }
                    }
                }

                SettingsSection.SHIZUKU -> {
                    item { ShizukuSettings() }
                }

                SettingsSection.LINKS -> {
                    item {
                        Section(stringResource(R.string.as_links)) {
                            Row_(Icons.Rounded.Person, stringResource(R.string.as_author), "Zyoo-0X") { open(Links.AUTHOR) }
                            Row_(Icons.Rounded.History, stringResource(R.string.as_orig), "t.me/istratiit_ech") { open(Links.ORIGINAL) }
                            Row_(Icons.Rounded.Code, stringResource(R.string.as_github), "github.com/Zyoo-0X/QuDroid") { open(Links.GITHUB) }
                            Row_(Icons.Rounded.History, stringResource(R.string.as_upstream_row, "AEmulator Plus"), "github.com/somerandomusernam2/AEmulator-Plus") { open(Links.PLUS) }
                            Row_(Icons.Rounded.History, stringResource(R.string.as_upstream_row, "AEmulator Sunset"), "github.com/drel4/AEmulator-Sunset") { open(Links.SUNSET) }
                            Row_(Icons.Rounded.History, stringResource(R.string.as_original_row, "AEmulator"), "github.com/uxazu/AEmulator") { open(Links.UPSTREAM) }
                            Row_(Icons.Rounded.Code, stringResource(R.string.as_license_notices), "GPL-3.0") { legalDialog = true }
                        }
                    }
                }
            }
        }
    }

    if (legalDialog) {
        val notices = remember {
            listOf("NOTICE.md", "license-audit.md", "LICENSE").joinToString("\n\n") { file ->
                ctx.assets.open("legal/$file").bufferedReader().use { it.readText() }
            }
        }
        AlertDialog(onDismissRequest = { legalDialog = false },
            title = { Text(stringResource(R.string.as_license_notices)) },
            text = {
                SelectionContainer {
                    Text(notices, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = { TextButton(onClick = { legalDialog = false }) { Text(stringResource(R.string.cancel)) } })
    }

    if (langDialog) {
        AlertDialog(
            onDismissRequest = { langDialog = false },
            icon = { Icon(Icons.Rounded.Language, null) },
            title = { Text(stringResource(R.string.as_language)) },
            text = {
                LazyColumn {
                    items(AppPrefs.LANGUAGES) { (tag, name) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                                langDialog = false
                                if (tag != langTag) { AppPrefs.setLanguage(ctx, tag); onRestyle() }
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = tag == langTag, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                            Text(name.ifEmpty { stringResource(R.string.as_lang_system) }, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { langDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    UpdateDialog(
        state = updateState,
        onDismiss = { updateState = UpdateState.Idle },
        onStartDownload = ::startDownload,
        onCancelDownload = {
            downloadJob?.cancel()
            updateState = UpdateState.Idle
        },
        onInstall = { file -> AppUpdateManager.installApk(ctx, file) },
        onOpenUrl = { url -> open(url) }
    )
}

/** Added for QuDroid: lock parts of the phone while a machine is on screen (needs the Shizuku app). */
@Composable
private fun ShizukuSettings() {
    val ctx = LocalContext.current
    var opts by remember { mutableStateOf(app.aemu.core.ShizukuLockStore.read(ctx)) }
    var running by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                running = app.aemu.core.ShizukuAccess.running()
                granted = app.aemu.core.ShizukuAccess.granted()
            }
            kotlinx.coroutines.delay(1000)
        }
    }
    fun save(o: app.aemu.core.ShizukuOptions) {
        opts = o
        runCatching { app.aemu.core.ShizukuLockStore.write(ctx, o) }
        if (!o.any) Thread { app.aemu.core.SystemUiLock.releaseNow(ctx) }.start()
    }
    fun openShizuku() {
        val launch = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        runCatching {
            ctx.startActivity(launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
        }
    }
    val status = when {
        !running -> "Shizuku is not running"
        !granted -> "Shizuku is running, permission needed"
        else -> "Shizuku is ready"
    }
    Section("Shizuku", "Lock parts of the phone while a machine is on screen") {
        Toggle("Use Shizuku", status, opts.enabled) { on ->
            save(opts.copy(enabled = on))
            if (on && running && !granted) app.aemu.core.ShizukuAccess.request()
        }
        if (opts.enabled) {
            if (!running) Row_(Icons.Rounded.Lock, "Start Shizuku", "Shizuku must be running. Tap to open it.") { openShizuku() }
            else if (!granted) Row_(Icons.Rounded.Lock, "Allow QuDroid", "Tap to give QuDroid permission in Shizuku") { app.aemu.core.ShizukuAccess.request() }
            Toggle("Disable notifications", "No alerts, pop-ups or notification icons in a machine", opts.notifications) { save(opts.copy(notifications = it)) }
            Toggle("Disable quick settings", "The quick settings panel cannot be pulled down in a machine", opts.quickSettings) { save(opts.copy(quickSettings = it)) }
            Toggle("Disable home", "The phone's Home does nothing in a machine; use the machine's own Home", opts.home) { save(opts.copy(home = it)) }
            Toggle("Disable back", "The phone's Back does nothing in a machine; use the machine's own Back", opts.back) { save(opts.copy(back = it)) }
            Row_(Icons.Rounded.Lock, "Leaving a machine", "Use the menu button at the top of the machine: Minimize or Shutdown") { }
            Row_(Icons.Rounded.Lock, "Unlock the phone now", "Tap if notifications, quick settings, Home or Back stay disabled") {
                Thread { app.aemu.core.SystemUiLock.releaseNow(ctx) }.start()
            }
        }
    }
}

@Composable
private fun Section(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = if (subtitle == null) 8.dp else 0.dp))
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        }
        Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(vertical = 8.dp)) { content() }
        }
    }
}

@Composable
private fun Row_(icon: ImageVector, title: String, sub: String, mono: Boolean = false, onClick: () -> Unit) {
    val supporting: (@Composable () -> Unit)? =
        if (sub.isEmpty()) null else ({ Text(sub, fontFamily = if (mono) FontFamily.Monospace else null, maxLines = 2) })
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting,
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
