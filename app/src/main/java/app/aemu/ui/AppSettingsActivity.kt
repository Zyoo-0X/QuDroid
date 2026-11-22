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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.aemu.AppPrefs
import app.aemu.BuildConfig
import app.aemu.R
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.Surface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight

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

@Composable
private fun AppSettings(onBack: () -> Unit, onRestyle: () -> Unit) {
    val ctx = LocalContext.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
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

    if (accentDialog) {
        ColorPickerDialog(
            initial = if (accent == 0) AppPurple else Color(accent),
            onDismiss = { accentDialog = false },
            onReset = { accent = FirstRunDefaults.PURPLE; AppPrefs.setAccentColor(ctx, FirstRunDefaults.PURPLE); accentDialog = false; onRestyle() },
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
                        if (UpdateVersion.isNewer(rel.versionName, BuildConfig.VERSION_NAME)) {
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

    // Settings is a list of buttons; a button opens just its own options
    var page by remember { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = page != null) { page = null }
    val pageTitle = when (page) {
        "updates" -> stringResource(R.string.as_updates)
        "appearance" -> stringResource(R.string.as_appearance)
        "defaults" -> stringResource(R.string.as_defaults)
        "shizuku" -> "Shizuku"
        "links" -> stringResource(R.string.as_links)
        else -> stringResource(R.string.settings)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { if (page != null) page = null else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(pageTitle, style = MaterialTheme.typography.titleLarge, maxLines = 1) },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        androidx.compose.animation.AnimatedContent(
            targetState = page,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val deeper = targetState != null
                (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(320)) { if (deeper) it / 3 else -it / 3 } +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(320))) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(320)) { if (deeper) -it / 3 else it / 3 } +
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)))
            },
            label = "settingsPage",
        ) { p ->
            val seen = remember(p) { mutableSetOf<Int>() }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (p == null) {
                    item(key = "hero") { StaggerIn(0, seen) { SettingsHero() } }
                    item(key = "hub_catalog") { StaggerIn(1, seen) {
                        SettingsHubTile(Icons.Rounded.List, stringResource(R.string.catalog_title), stringResource(R.string.catalog_open)) {
                            ctx.startActivity(Intent(ctx, RomCatalogActivity::class.java))
                        }
                    } }
                    item(key = "hub_updates") { StaggerIn(2, seen) {
                        SettingsHubTile(Icons.Rounded.SystemUpdate, stringResource(R.string.as_updates), "v${BuildConfig.VERSION_NAME}") { page = "updates" }
                    } }
                    item(key = "hub_appearance") { StaggerIn(3, seen) {
                        SettingsHubTile(Icons.Rounded.Palette, stringResource(R.string.as_appearance), langName) { page = "appearance" }
                    } }
                    item(key = "hub_defaults") { StaggerIn(4, seen) {
                        SettingsHubTile(Icons.Rounded.Tune, stringResource(R.string.as_defaults), stringResource(R.string.as_defaults_sub)) { page = "defaults" }
                    } }
                    item(key = "hub_shizuku") { StaggerIn(5, seen) {
                        SettingsHubTile(Icons.Rounded.Lock, "Shizuku", "Lock parts of the phone while a machine is on screen") { page = "shizuku" }
                    } }
                    item(key = "hub_links") { StaggerIn(6, seen) {
                        SettingsHubTile(Icons.Rounded.Person, stringResource(R.string.as_links), "Zyoo-0X") { page = "links" }
                    } }
                } else {
                    when (p) {
                    "updates" -> item(key = "updates") { StaggerIn(0, seen) {
                Section(stringResource(R.string.as_updates)) {
                    InfoToggle(
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
                    } }
                    "appearance" -> item(key = "appearance") { StaggerIn(0, seen) {
                Section(stringResource(R.string.as_appearance)) {
                    InfoToggle(stringResource(R.string.as_new_home), stringResource(R.string.as_new_home_sub), newHome) {
                        newHome = it; AppPrefs.setNewHome(ctx, it)
                    }
                    InfoToggle(stringResource(R.string.as_library_3d), stringResource(R.string.as_library_3d_sub), library3d) {
                        library3d = it; AppPrefs.setLibrary3d(ctx, it)
                    }
                    InfoToggle(stringResource(R.string.as_builtin_browser), stringResource(R.string.as_builtin_browser_sub), builtinBrowser) {
                        builtinBrowser = it; AppPrefs.setBuiltinBrowser(ctx, it)
                    }
                    if (app.aemu.core.HostDisplayPolicy.rotateWindow(ctx.resources.configuration.smallestScreenWidthDp)) {
                        InfoToggle(stringResource(R.string.as_tablet_navbar_rotation), stringResource(R.string.as_tablet_navbar_rotation_sub), hostUi.tabletNavbarRotation) {
                            hostUi = hostUi.copy(tabletNavbarRotation = it); AppPrefs.setHostUiOptions(ctx, hostUi)
                        }
                    }
                    InfoToggle(stringResource(R.string.as_cutout_barrier), stringResource(R.string.as_cutout_barrier_sub), hostUi.cutoutBarrier) {
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
                                .background(if (accent == 0) AppPurple else Color(accent)))
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { accentDialog = true },
                    )
                    if (Build.VERSION.SDK_INT >= 31 && accent == 0) {
                        InfoToggle(stringResource(R.string.as_dynamic), stringResource(R.string.as_dynamic_sub), dynamic) {
                            dynamic = it; AppPrefs.setDynamicColor(ctx, it)
                        }
                    }
                }
                    } }
                    "defaults" -> item(key = "defaults") { StaggerIn(0, seen) {
                Section(stringResource(R.string.as_defaults), stringResource(R.string.as_defaults_sub)) {
                    fun set(s: app.aemu.core.VmSettings) { defaults = s; AppPrefs.setDefaults(ctx, s) }
                    InfoToggle(stringResource(R.string.vs_gpu), stringResource(R.string.vs_gpu_sub), defaults.gpu) { set(defaults.copy(gpu = it)) }
                    InfoToggle(stringResource(R.string.vs_jit), stringResource(R.string.vs_jit_sub), defaults.jit) { set(defaults.copy(jit = it)) }
                    InfoToggle(stringResource(R.string.vs_proxy), stringResource(R.string.vs_proxy_sub), defaults.netProxy) { set(defaults.copy(netProxy = it)) }
                    InfoToggle(stringResource(R.string.vs_nav), stringResource(R.string.vs_nav_sub), defaults.showNavBar) { set(defaults.copy(showNavBar = it)) }
                    NavigationSettings(defaults, set = ::set)
                    InfoToggle(stringResource(R.string.vs_awake), stringResource(R.string.vs_awake_sub), defaults.keepScreenOn) { set(defaults.copy(keepScreenOn = it)) }
                    InfoToggle(stringResource(R.string.as_led), stringResource(R.string.as_led_sub), defaults.showLed) { set(defaults.copy(showLed = it)) }
                }
                    } }
                    "shizuku" -> item(key = "shizuku") { StaggerIn(0, seen) {
                ShizukuSettings()
                    } }
                    "links" -> item(key = "links") { StaggerIn(0, seen) {
                Section(stringResource(R.string.as_links)) {
                    Row_(Icons.Rounded.Person, stringResource(R.string.as_author), "Zyoo-0X") { open(Links.AUTHOR) }
                    Row_(Icons.Rounded.History, stringResource(R.string.as_orig), "t.me/istratiit_ech") { open(Links.ORIGINAL) }
                    Row_(Icons.Rounded.Code, stringResource(R.string.as_github), "github.com/Zyoo-0X/QuDroid") { open(Links.GITHUB) }
                    Row_(Icons.Rounded.History, stringResource(R.string.as_upstream_row, "AEmulator Plus"), "github.com/somerandomusernam2/AEmulator-Plus") { open(Links.PLUS) }
                    Row_(Icons.Rounded.History, stringResource(R.string.as_upstream_row, "AEmulator Sunset"), "github.com/drel4/AEmulator-Sunset") { open(Links.SUNSET) }
                    Row_(Icons.Rounded.History, stringResource(R.string.as_original_row, "AEmulator"), "github.com/uxazu/AEmulator") { open(Links.UPSTREAM) }
                    Row_(Icons.Rounded.Code, stringResource(R.string.as_license_notices), "GPL-3.0") { legalDialog = true }
                }
                    } }
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
        InfoToggle("Use Shizuku", status, opts.enabled) { on ->
            save(if (on) opts.copy(enabled = true, notifications = true, quickSettings = true, home = true, back = true) else opts.copy(enabled = false, notifications = false, quickSettings = false, home = false, back = false))
            if (on && running && !granted) app.aemu.core.ShizukuAccess.request()
        }
        if (opts.enabled) {
            if (!running) Row_(Icons.Rounded.Lock, "Start Shizuku", "Shizuku must be running. Tap to open it.") { openShizuku() }
            else if (!granted) Row_(Icons.Rounded.Lock, "Allow QuDroid", "Tap to give QuDroid permission in Shizuku") { app.aemu.core.ShizukuAccess.request() }
            InfoToggle("Disable notifications", "No alerts, pop-ups or notification icons in a machine", opts.notifications) { save(opts.copy(notifications = it)) }
            InfoToggle("Disable quick settings", "The quick settings panel cannot be pulled down in a machine", opts.quickSettings) { save(opts.copy(quickSettings = it)) }
            InfoToggle("Disable home", "The phone's Home does nothing in a machine; use the machine's own Home", opts.home) { save(opts.copy(home = it)) }
            InfoToggle("Disable back", "The phone's Back does nothing in a machine; use the machine's own Back", opts.back) { save(opts.copy(back = it)) }
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

/** The top of Settings: a card in the app's colours; the gear spins in and settles when the screen opens. */
@Composable
private fun SettingsHero() {
    val rot = remember { Animatable(-200f) }
    LaunchedEffect(Unit) { rot.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessVeryLow)) }
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(cs.primary, cs.tertiary)))
            .padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(58.dp).clip(RoundedCornerShape(19.dp)).background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Settings, null, Modifier.size(32.dp).graphicsLayer { rotationZ = rot.value }, tint = cs.onPrimary)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text("QuDroid", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onPrimary)
                Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = cs.onPrimary.copy(alpha = 0.85f))
            }
        }
    }
}

/** The app's default accent: purple. */
private val AppPurple = Color(0xFF9C27B0)

/**
 * Once, on the first launch of this version: the Android button bar and the LED are off by default and the accent
 * colour is purple. Later changes by the user are kept (the flag stops it from running again).
 */
object FirstRunDefaults {
    const val PURPLE: Int = 0xFF9C27B0.toInt()
    private const val PREF = "qudroid_onboarding"
    private const val KEY = "defaults_purple_v1"

    fun apply(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (sp.getBoolean(KEY, false)) return
        runCatching {
            AppPrefs.setDefaults(ctx, AppPrefs.defaults(ctx).copy(showNavBar = false, showLed = false))
            AppPrefs.setAccentColor(ctx, PURPLE)
        }
        sp.edit().putBoolean(KEY, true).apply()
    }
}

/** One button of the Settings list: icon, name, a short line under it and an arrow. Tapping opens that part. */
@Composable
private fun SettingsHubTile(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "hubTile")
    Surface(
        onClick = onClick,
        interactionSource = press,
        shape = RoundedCornerShape(26.dp),
        color = cs.surfaceContainer,
        modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.size(50.dp).clip(RoundedCornerShape(17.dp)).background(cs.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, Modifier.size(26.dp), tint = cs.primary) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
        }
    }
}
