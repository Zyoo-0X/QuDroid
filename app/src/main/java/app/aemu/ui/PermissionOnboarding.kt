/* Added for QuDroid: first-launch permission screen (Nearby devices, Notifications, Camera, Files, Microphone,
 * Music & audio, Photos & video, Shizuku optional) with Skip. GPL-3.0; see NOTICE.md. */
package app.aemu.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Remembers that the first-launch permission screen was shown (Skip and Continue both count). */
object PermissionGate {
    private const val PREF = "qudroid_onboarding"
    private const val KEY = "permissions_done"
    fun isDone(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun markDone(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
}

/** All texts of this screen in one place: move them to strings.xml (and translate) if you want. */
private object Txt {
    const val TITLE = "Allow access"
    const val SUB = "QuDroid works best with these. You can skip and allow them later."
    const val ALLOW = "Allow"
    const val ALLOWED = "Allowed"
    const val OPTIONAL = "Optional"
    const val ALLOW_ALL = "Allow all"
    const val CONTINUE = "Continue"
    const val SKIP = "Skip"
    const val NEARBY = "Nearby devices"
    const val NEARBY_SUB = "Find and connect to devices around you"
    const val NOTIF = "Notifications"
    const val NOTIF_SUB = "Show machine status and alerts"
    const val CAMERA = "Camera"
    const val CAMERA_SUB = "For machines that use the camera"
    const val FILES = "Files"
    const val FILES_SUB = "Access all files for firmware and shared folder"
    const val MIC = "Microphone"
    const val MIC_SUB = "For machines that use the microphone"
    const val MUSIC = "Music and audio"
    const val MUSIC_SUB = "Read audio files on this phone"
    const val PHOTOS = "Photos and video"
    const val PHOTOS_SUB = "Read pictures and videos on this phone"
    const val SHIZUKU = "Shizuku"
    const val SHIZUKU_SUB = "Lock notifications, Home and Back while a machine is on screen"
    const val SHIZUKU_START = "Open Shizuku and start it, then come back"
}

private class PermItem(
    val id: String,
    val title: String,
    val sub: String,
    val icon: ImageVector,
    val manifest: List<String> = emptyList(),
    val optional: Boolean = false,
)

private fun permItems(): List<PermItem> {
    val sdk = Build.VERSION.SDK_INT
    val nearby = when {
        sdk >= 33 -> listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.NEARBY_WIFI_DEVICES)
        sdk >= 31 -> listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
        else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val audio = if (sdk >= 33) listOf(Manifest.permission.READ_MEDIA_AUDIO) else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    val photos = when {
        sdk >= 34 -> listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        sdk >= 33 -> listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return listOf(
        PermItem("nearby", Txt.NEARBY, Txt.NEARBY_SUB, Icons.Rounded.Devices, nearby),
        PermItem("notif", Txt.NOTIF, Txt.NOTIF_SUB, Icons.Rounded.Notifications,
            if (sdk >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()),
        PermItem("camera", Txt.CAMERA, Txt.CAMERA_SUB, Icons.Rounded.CameraAlt, listOf(Manifest.permission.CAMERA)),
        // Files: "all files access" from Android 11, the classic storage permissions before that
        PermItem("files", Txt.FILES, Txt.FILES_SUB, Icons.Rounded.Folder,
            if (sdk >= 30) emptyList() else listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)),
        PermItem("mic", Txt.MIC, Txt.MIC_SUB, Icons.Rounded.Mic, listOf(Manifest.permission.RECORD_AUDIO)),
        PermItem("audio", Txt.MUSIC, Txt.MUSIC_SUB, Icons.Rounded.MusicNote, audio),
        PermItem("photos", Txt.PHOTOS, Txt.PHOTOS_SUB, Icons.Rounded.PhotoLibrary, photos),
        PermItem("shizuku", Txt.SHIZUKU, Txt.SHIZUKU_SUB, Icons.Rounded.AdminPanelSettings, optional = true),
    )
}

/** Permissions this build declares in AndroidManifest.xml: asking for one that is not declared silently does nothing. */
@Suppress("DEPRECATION")
private fun declared(ctx: Context): Set<String> = declaredCache ?: runCatching {
    ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        .requestedPermissions?.toSet() ?: emptySet()
}.getOrDefault(emptySet()).also { declaredCache = it }

private var declaredCache: Set<String>? = null

/** What can really be asked for this row: its permissions that the manifest declares. */
private fun PermItem.req(ctx: Context): List<String> = manifest.filter { it in declared(ctx) }

private fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun PermItem.granted(ctx: Context, shizuku: Boolean): Boolean = when (id) {
    "files" -> if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else req(ctx).let { it.isNotEmpty() && it.all { p -> has(ctx, p) } }
    "shizuku" -> shizuku
    // notifications: the real switch of the app (also covers Android 12 and older, which have no permission dialog)
    "notif" -> NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    // Android 14: "selected photos only" counts as allowed
    "photos" -> if (Build.VERSION.SDK_INT >= 34)
        (has(ctx, Manifest.permission.READ_MEDIA_IMAGES) && has(ctx, Manifest.permission.READ_MEDIA_VIDEO)) ||
            has(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    else req(ctx).let { it.isNotEmpty() && it.all { p -> has(ctx, p) } }
    else -> req(ctx).let { it.isNotEmpty() && it.all { p -> has(ctx, p) } }
}

private fun openNotificationSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
    }.onFailure { openAppDetails(ctx) }
}

private fun openAllFilesAccess(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
    }.onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
}

private fun openAppDetails(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    }
}

/** The screen shown before the library on the first launch. [onFinish] is called by both Skip and Continue. */
@Composable
fun PermissionOnboardingScreen(onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val items = remember { permItems() }
    var tick by remember { mutableIntStateOf(0) }
    var shizukuRunning by remember { mutableStateOf(false) }
    var shizukuGranted by remember { mutableStateOf(false) }
    var pendingFiles by remember { mutableStateOf(false) }
    val asked = remember { mutableStateMapOf<String, Int>() }

    // come back from a system dialog or Settings: look at everything again
    val life = LocalLifecycleOwner.current
    DisposableEffect(life) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        life.lifecycle.addObserver(obs)
        onDispose { life.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                runCatching {
                    shizukuRunning = app.aemu.core.ShizukuAccess.running()
                    shizukuGranted = app.aemu.core.ShizukuAccess.granted()
                }
            }
            delay(1000)
        }
    }

    val status = remember(tick, shizukuGranted) { items.associate { it.id to it.granted(ctx, shizukuGranted) } }

    var lastAsk by remember { mutableStateOf<String?>(null) }
    var lastAskAt by remember { mutableStateOf(0L) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        tick++
        // the answer came back at once and nothing was granted: Android did not show a dialog (denied for good, or the
        // permission is not allowed to be asked here), so send the user to the matching page of system Settings
        val instant = SystemClock.elapsedRealtime() - lastAskAt < 400
        if (instant && result.isNotEmpty() && result.values.none { it }) {
            if (lastAsk == "notif") openNotificationSettings(ctx) else if (lastAsk != null) openAppDetails(ctx)
        }
        if (pendingFiles) {
            pendingFiles = false
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) openAllFilesAccess(ctx)
        }
    }

    fun allow(item: PermItem) {
        when (item.id) {
            "files" -> if (Build.VERSION.SDK_INT >= 30) openAllFilesAccess(ctx) else launcher.launch(item.req(ctx).toTypedArray())
            "shizuku" -> {
                if (!shizukuRunning) {
                    val launch = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    runCatching { ctx.startActivity(launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) }
                } else app.aemu.core.ShizukuAccess.request()
            }
            else -> {
                val todo = item.req(ctx).filter { !has(ctx, it) }
                val n = (asked[item.id] ?: 0) + 1
                asked[item.id] = n
                when {
                    // nothing of it is declared in the manifest, or it was refused twice: only system Settings can help
                    todo.isEmpty() || n > 2 -> if (item.id == "notif") openNotificationSettings(ctx) else openAppDetails(ctx)
                    else -> { lastAsk = item.id; lastAskAt = SystemClock.elapsedRealtime(); launcher.launch(todo.toTypedArray()) }
                }
            }
        }
    }

    // a row whose permissions are not declared in the manifest cannot be asked for, so it does not block Continue
    val required = items.filter { !it.optional && (it.id == "files" || it.id == "notif" || it.req(ctx).isNotEmpty()) }
    val doneCount = required.count { status[it.id] == true }
    val allRequired = doneCount == required.size
    val progress by animateFloatAsState(doneCount / required.size.toFloat(), spring(stiffness = Spring.StiffnessLow), label = "permProgress")
    val seen = remember { mutableSetOf<Int>() }

    fun allowAll() {
        val todo = items.filter { it.id != "shizuku" && it.id != "files" && status[it.id] != true }
            .flatMap { it.req(ctx) }.filter { !has(ctx, it) }.distinct()
        pendingFiles = Build.VERSION.SDK_INT >= 30 && status["files"] != true
        if (todo.isNotEmpty()) { lastAsk = null; launcher.launch(todo.toTypedArray()) }
        else if (pendingFiles) { pendingFiles = false; openAllFilesAccess(ctx) }
        else if (status["notif"] != true) openNotificationSettings(ctx)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding()) {
        // header
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 28.dp, bottom = 16.dp)) {
            val pop = remember { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(Unit) { pop.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) }
            Box(
                Modifier.size(64.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value; rotationZ = (1f - pop.value) * -40f }
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Security, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onPrimary) }
            Spacer(Modifier.height(20.dp))
            Text(Txt.TITLE, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(Txt.SUB, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
                StaggerIn(i, seen) {
                    PermissionRow(item, granted = status[item.id] == true, onAllow = { allow(item) })
                }
            }
        }

        // bottom bar: Skip and Allow all / Continue
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { PermissionGate.markDone(ctx); onFinish() }) { Text(Txt.SKIP) }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { if (allRequired) { PermissionGate.markDone(ctx); onFinish() } else allowAll() },
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
            ) { Text(if (allRequired) Txt.CONTINUE else Txt.ALLOW_ALL, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun PermissionRow(item: PermItem, granted: Boolean, onAllow: () -> Unit) {
    val container by animateColorAsState(
        if (granted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        label = "permRowColor",
    )
    val check by animateFloatAsState(if (granted) 1f else 0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "permCheck")
    Surface(shape = RoundedCornerShape(22.dp), color = container, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(15.dp))
                    .background(if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(item.icon, null, Modifier.size(24.dp),
                    tint = if (granted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (item.optional) {
                        Spacer(Modifier.width(8.dp))
                        Text(Txt.OPTIONAL, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                Text(item.sub, style = MaterialTheme.typography.bodySmall,
                    color = if (granted) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (granted) {
                Icon(Icons.Rounded.CheckCircle, Txt.ALLOWED, Modifier.size(28.dp).graphicsLayer { scaleX = check; scaleY = check },
                    tint = MaterialTheme.colorScheme.primary)
            } else {
                FilledTonalButton(onClick = onAllow, contentPadding = PaddingValues(horizontal = 16.dp)) { Text(Txt.ALLOW) }
            }
        }
    }
}
