package app.aemu.ui

import app.aemu.core.userMessage
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import android.text.format.Formatter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aemu.AppPrefs
import app.aemu.R
import app.aemu.catalog.*
import kotlinx.coroutines.CancellationException

class RomCatalogActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppPrefs.wrap(base))
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AemuTheme { CatalogScreen(::finish) } }
    }
}

/** Progress of a catalog download: [name] is empty while the link is still being checked. */
private data class DownloadUi(val name: String = "", val done: Long = 0L, val total: Long = -1L, val speed: Long = 0L)

/** Smoothed transfer speed in bytes per second, fed with the running byte count. */
private class SpeedMeter {
    private var lastNs = 0L
    private var lastBytes = 0L
    private var rate = 0.0
    @Synchronized fun update(bytes: Long): Long {
        val now = System.nanoTime()
        if (lastNs == 0L) { lastNs = now; lastBytes = bytes; return 0L }
        val dt = (now - lastNs) / 1e9
        if (dt >= 0.5) {
            val inst = (bytes - lastBytes) / dt
            rate = if (rate == 0.0) inst else rate * 0.7 + inst * 0.3
            lastNs = now; lastBytes = bytes
        }
        return rate.toLong()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    // The catalog source is fixed: it cannot be changed or seen in the UI.
    val source = CatalogUrls.DEFAULT
    var revision by remember { mutableIntStateOf(0) }
    var catalog by remember { mutableStateOf<RomCatalog?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedSection by rememberSaveable { mutableIntStateOf(-1) }
    var submitting by rememberSaveable { mutableStateOf(false) }
    var download by remember { mutableStateOf<DownloadUi?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val apiBase = CatalogUrls.apiBase(source)
    // A download button first checks the link: a file (binary / archive) is downloaded here and handed to the
    // importer on the home screen; a web page opens in the browser.
    val startDownload: (String) -> Unit = start@{ url ->
        if (downloadJob?.isActive == true) return@start
        download = DownloadUi()
        downloadJob = scope.launch {
            try {
                RomDownloader.purgeStale(ctx)
                val meter = SpeedMeter()
                val outcome = RomDownloader.fetch(ctx, url,
                    onFile = { name, total -> download = DownloadUi(name = name, total = total) },
                    onProgress = { done, total -> download = download?.copy(done = done, total = total, speed = meter.update(done)) })
                when (outcome) {
                    is RomDownloader.Outcome.Page -> { download = null; openBrowser(ctx, url) }
                    is RomDownloader.Outcome.Saved -> {
                        download = null
                        ctx.startActivity(Intent(ctx, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(EXTRA_IMPORT, outcome.file.absolutePath)
                            .putExtra(EXTRA_IMPORT_DELETE, true))
                        (ctx as? android.app.Activity)?.finish()
                    }
                }
            } catch (e: CancellationException) { download = null; throw e }
            catch (e: Exception) {
                download = null
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.catalog_download_failed, e.userMessage(ctx)),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    val section = catalog?.sections?.getOrNull(selectedSection)
    BackHandler(enabled = selectedSection >= 0) { selectedSection = -1 }
    LaunchedEffect(source, revision) {
        loading = true
        error = null
        try {
            val loaded = CatalogLoader.load(source)
            catalog = loaded
            if (selectedSection !in loaded.sections.indices) selectedSection = -1
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.userMessage(ctx) }
        finally { loading = false }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(section?.name?.ifEmpty { stringResource(R.string.catalog_roms) }
            ?: stringResource(R.string.catalog_title)) }, navigationIcon = {
            IconButton(onClick = { if (selectedSection >= 0) selectedSection = -1 else onBack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(
                    if (selectedSection >= 0) R.string.catalog_back_sections else R.string.back))
            }
        }, actions = {
            if (apiBase != null) IconButton(onClick = { submitting = true }) { Icon(Icons.Rounded.Add, stringResource(R.string.submit_action)) }
            IconButton(onClick = { selectedSection = -1; revision++ }, enabled = !loading) { Icon(Icons.Rounded.Refresh, stringResource(R.string.catalog_refresh)) }
        })
    }) { pad ->
        key(selectedSection) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(pad), state = rememberLazyListState(),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { problem -> item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.catalog_load_failed, problem), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { revision++ }, enabled = !loading) { Text(stringResource(R.string.catalog_retry)) }
                    }
                }
            } }
            catalog?.let { list ->
                if (selectedSection < 0 && list.motd.isNotEmpty()) item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Text(list.motd, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (list.warningLines.isNotEmpty()) item {
                    Text(stringResource(R.string.catalog_warnings, list.warningLines.take(10).joinToString(", ") +
                        if (list.warningLines.size > 10) "…" else ""),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (list.sections.isEmpty()) item { Text(stringResource(R.string.catalog_no_roms)) }
                if (section == null) list.sections.forEachIndexed { sectionIndex, entry ->
                    item(key = "section-$sectionIndex") {
                        Card(Modifier.fillMaxWidth()) {
                            ListItem(modifier = Modifier.clickable { selectedSection = sectionIndex },
                                headlineContent = { Text(entry.name.ifEmpty { stringResource(R.string.catalog_roms) },
                                    style = MaterialTheme.typography.titleMedium) },
                                supportingContent = {
                                    Column {
                                        Text(if (entry.roms.isEmpty()) stringResource(R.string.catalog_no_roms)
                                            else pluralStringResource(R.plurals.catalog_rom_count, entry.roms.size, entry.roms.size))
                                        if (entry.comment.isNotEmpty()) Text(entry.comment, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                },
                                trailingContent = { Icon(Icons.Rounded.ChevronRight, null) })
                        }
                    }
                } else {
                    item(key = "section-info") {
                        Column {
                            if (section.comment.isNotEmpty()) Text(section.comment, style = MaterialTheme.typography.bodyMedium)
                            section.source?.let { url -> TextButton(onClick = { openBrowser(ctx, url) }) {
                                Text(stringResource(R.string.catalog_section_source))
                            } }
                            if (section.roms.isEmpty()) Text(stringResource(R.string.catalog_no_roms),
                                Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    section.roms.forEachIndexed { romIndex, rom ->
                        item(key = "rom-$romIndex") { CatalogRomCard(rom, startDownload) }
                    }
                }
            }
            }
        }
    }
    download?.let { d ->
        AlertDialog(
            // no dismissing by a tap outside or Back: Cancel is the way out
            onDismissRequest = {},
            title = { Text(stringResource(if (d.name.isEmpty()) R.string.catalog_download_checking else R.string.catalog_download_title)) },
            text = { Column {
                if (d.name.isNotEmpty()) {
                    Text(d.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                }
                if (d.total > 0) LinearProgressIndicator(progress = { (d.done.toFloat() / d.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                if (d.name.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    val sizes = if (d.total > 0) "${Formatter.formatShortFileSize(ctx, d.done)} / ${Formatter.formatShortFileSize(ctx, d.total)}"
                        else Formatter.formatShortFileSize(ctx, d.done)
                    Text(if (d.speed > 0) "$sizes (${Formatter.formatShortFileSize(ctx, d.speed)}/s)" else sizes,
                        style = MaterialTheme.typography.bodySmall)
                }
            } },
            confirmButton = { TextButton(onClick = { downloadJob?.cancel(); download = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (submitting && apiBase != null) SubmitRomDialog(apiBase) { submitting = false }
}

@Composable
private fun CatalogRomCard(rom: CatalogRom, onDownload: (String) -> Unit) {
    val ctx = LocalContext.current
    rom.details?.let { SubmittedRomCard(rom, it, onDownload); return }
    val status = when (rom.status) {
        0 -> R.string.catalog_status_no_boot
        1 -> R.string.catalog_status_issues
        2 -> R.string.catalog_status_works
        else -> R.string.catalog_status_unknown
    }
    val statusColor = when (rom.status) {
        0 -> MaterialTheme.colorScheme.error
        1 -> MaterialTheme.colorScheme.tertiary
        2 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(rom.device, style = MaterialTheme.typography.titleMedium)
                Text(listOf("Android ${rom.android}", rom.skin).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(status), color = statusColor, style = MaterialTheme.typography.labelLarge)
                if (rom.comment.isNotEmpty()) Text(rom.comment, style = MaterialTheme.typography.bodyMedium)
                if (rom.url == null) Text(stringResource(R.string.catalog_no_url), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            rom.url?.let { url -> IconButton(onClick = { onDownload(url) }) {
                Icon(Icons.Rounded.Download, stringResource(R.string.catalog_download_rom, rom.device))
            } }
        }
    }
}

/** Card for entries from the JSON catalog: name, device, ROM version/category, size, emulator, notes, submitter. */
@Composable
private fun SubmittedRomCard(rom: CatalogRom, d: RomDetails, onDownload: (String) -> Unit) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium)
                Text(rom.device, style = MaterialTheme.typography.bodyMedium)
                Text(listOf(d.romVersion, d.versionCategory).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium)
                val extras = listOfNotNull(d.fileSizeBytes?.let { Formatter.formatShortFileSize(ctx, it) },
                    d.emulatorVersion?.let { stringResource(R.string.catalog_tested_on, it) })
                if (extras.isNotEmpty()) Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                d.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (d.submittedBy.isNotBlank()) Text(stringResource(R.string.catalog_submitted_by, d.submittedBy),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (rom.url == null) Text(stringResource(R.string.catalog_no_url), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            rom.url?.let { url -> IconButton(onClick = { onDownload(url) }) {
                Icon(Icons.Rounded.Download, stringResource(R.string.catalog_download_rom, rom.device))
            } }
        }
    }
}
