/* Added for QuDroid: lock parts of the phone's system UI (notifications, quick settings, Home, Back) while a machine is
 * on screen, through Shizuku. GPL-3.0; see NOTICE.md. */
package app.aemu.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process as AndroidProcess
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File

/** What the person chose to lock while a machine is on screen. Nothing happens unless [enabled]. */
data class ShizukuOptions(
    val enabled: Boolean = false,
    val notifications: Boolean = false,
    val quickSettings: Boolean = false,
    val home: Boolean = false,
    val back: Boolean = false,
    /** the phone's volume keys never touch the phone while a machine is on screen: they drive the machine's volume */
    val disableVolume: Boolean = false,
    /** long-press Power opens the machine's power menu, not the phone's (a short press still turns the screen off) */
    val disablePower: Boolean = false,
) {
    val any: Boolean get() = enabled && (notifications || quickSettings || home || back || disableVolume || disablePower)
    val powerActive: Boolean get() = enabled && disablePower
    val volumeActive: Boolean get() = enabled && disableVolume
}

/** The choices, in a file: every machine runs in a process of its own (same way as AppPrefs.hostUiOptions). */
object ShizukuLockStore {
    private fun file(ctx: Context) = android.util.AtomicFile(File(ctx.filesDir, "shizuku-lock.json"))

    fun read(ctx: Context): ShizukuOptions = runCatching {
        val j = JSONObject(file(ctx).openRead().bufferedReader().use { it.readText() })
        ShizukuOptions(j.optBoolean("enabled"), j.optBoolean("notifications"), j.optBoolean("quickSettings"),
            j.optBoolean("home"), j.optBoolean("back"), j.optBoolean("disableVolume"), j.optBoolean("disablePower"))
    }.getOrDefault(ShizukuOptions())

    fun write(ctx: Context, o: ShizukuOptions) {
        val f = file(ctx)
        val s = f.startWrite()
        try {
            s.write(JSONObject().put("enabled", o.enabled).put("notifications", o.notifications)
                .put("quickSettings", o.quickSettings).put("home", o.home).put("back", o.back)
                .put("disableVolume", o.disableVolume).put("disablePower", o.disablePower)
                .toString().toByteArray(Charsets.UTF_8))
            f.finishWrite(s)
        } catch (e: Throwable) { f.failWrite(s); throw e }
    }
}

/** Talking to the Shizuku app: is it installed, running, has this app been allowed, run a shell command through it. */
object ShizukuAccess {
    const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"

    enum class State { NOT_INSTALLED, NOT_RUNNING, TOO_OLD, NEEDS_PERMISSION, DENIED_FOR_GOOD, READY }
    enum class Ask { ASKED, OPEN_SHIZUKU, NOT_RUNNING, TOO_OLD }

    @Volatile private var appCtx: Context? = null
    @Volatile private var vmProcess = false
    @Volatile private var mainProcess = false

    /** Only the app's main process cleans up after a crashed machine; machine processes must not (see AemuApp). */
    fun isMainProcess(): Boolean = mainProcess

    fun installed(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(MANAGER_PACKAGE, 0); true
    }.getOrDefault(false)

    fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Shizuku older than v11 cannot give this app a permission through the API. */
    fun tooOld(): Boolean = runCatching { running() && Shizuku.isPreV11() }.getOrDefault(false)

    fun granted(): Boolean = runCatching {
        running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** The person chose "deny and don't ask again": the system dialog never shows again, only the Shizuku app can allow. */
    fun deniedForGood(): Boolean = runCatching {
        running() && !granted() && Shizuku.shouldShowRequestPermissionRationale()
    }.getOrDefault(false)

    fun state(ctx: Context): State = when {
        running() -> when {
            tooOld() -> State.TOO_OLD
            granted() -> State.READY
            deniedForGood() -> State.DENIED_FOR_GOOD
            else -> State.NEEDS_PERMISSION
        }
        installed(ctx) -> State.NOT_RUNNING
        else -> State.NOT_INSTALLED
    }

    /**
     * Asks Shizuku to allow this app. When the system dialog cannot show (denied for good) the answer is
     * [Ask.OPEN_SHIZUKU]: the caller sends the person to the Shizuku app (Authorized apps).
     */
    fun request(code: Int = 1001): Ask = when {
        !running() -> Ask.NOT_RUNNING
        tooOld() -> Ask.TOO_OLD
        deniedForGood() -> Ask.OPEN_SHIZUKU
        else -> { runCatching { Shizuku.requestPermission(code) }; Ask.ASKED }
    }

    /** Opens the Shizuku app (to start it or to allow QuDroid), or its download page when it is not installed. */
    fun openManager(ctx: Context) {
        val launch = ctx.packageManager.getLaunchIntentForPackage(MANAGER_PACKAGE)
        runCatching {
            ctx.startActivity((launch ?: android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(DOWNLOAD_URL)))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** What to tell the person for each state. */
    fun describe(s: State): String = when (s) {
        State.NOT_INSTALLED -> "Shizuku is not installed"
        State.NOT_RUNNING -> "Shizuku is not running"
        State.TOO_OLD -> "Shizuku is too old, update the Shizuku app"
        State.NEEDS_PERMISSION -> "Shizuku is running, permission needed"
        State.DENIED_FOR_GOOD -> "QuDroid was denied in Shizuku, allow it in the Shizuku app"
        State.READY -> "Shizuku is ready"
    }

    /**
     * A machine's process gets the Shizuku binder a moment after it starts, and only if it asks for it: it is asked
     * again while waiting (Shizuku may have started after the machine, or the first answer may have been lost).
     */
    fun awaitReady(ms: Long = 3000): Boolean {
        val end = System.currentTimeMillis() + ms
        var nextAsk = 0L
        while (System.currentTimeMillis() < end) {
            if (granted()) return true
            val now = System.currentTimeMillis()
            if (vmProcess && !running() && now >= nextAsk) { nextAsk = now + 500; askProviderForBinder() }
            try { Thread.sleep(100) } catch (_: InterruptedException) { break }
        }
        return granted()
    }

    private fun askProviderForBinder() {
        val app = appCtx ?: return
        runCatching {
            Class.forName("rikka.shizuku.ShizukuProvider")
                .getMethod("requestBinderForNonProviderProcess", Context::class.java).invoke(null, app)
        }
    }

    private fun newProcess(cmd: Array<out String>): Process? = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        m.invoke(null, arrayOf(*cmd), null, null) as Process
    } catch (t: Throwable) { null }

    /** Runs [cmd] as the shell user. Returns the exit code and what the command printed (-1 when it could not run). */
    fun exec(vararg cmd: String): Pair<Int, String> {
        if (!running()) return -1 to "Shizuku is not running"
        val p = newProcess(cmd) ?: return -1 to "Shizuku could not start the command"
        return try {
            // both pipes are read at the same time: a command that fills one of them would otherwise never end
            val err = StringBuilder()
            val reader = Thread {
                runCatching { p.errorStream.bufferedReader().use { r -> err.append(r.readText()) } }
            }.apply { isDaemon = true; start() }
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val code = p.waitFor()
            reader.join(1000)
            code to (out + err).trim()
        } catch (t: Throwable) {
            runCatching { p.destroy() }
            -1 to (t.message ?: t.toString())
        }
    }

    /** Starts [cmd] as the shell user and returns the running process (for output that is read as it comes), or null. */
    fun spawn(vararg cmd: String): Process? = if (running()) newProcess(cmd) else null

    /**
     * Machines run in their own processes (":vm", ":vm1", ...), the Shizuku provider is in the main one: the machine
     * processes ask the provider for the binder. Called from Application.onCreate in every process.
     */
    fun setup(app: android.app.Application) {
        val name = runCatching { File("/proc/self/cmdline").readText().trim('\u0000') }.getOrDefault("")
        val isMain = name == app.packageName
        val isVm = name.startsWith(app.packageName + ":vm")
        mainProcess = isMain
        vmProcess = isVm
        if (!isMain && !isVm) return
        appCtx = app
        runCatching {
            val c = Class.forName("rikka.shizuku.ShizukuProvider")
            c.getMethod("enableMultiProcessSupport", java.lang.Boolean.TYPE).invoke(null, isMain)
            if (isVm) c.getMethod("requestBinderForNonProviderProcess", Context::class.java).invoke(null, app)
        }
    }
}

/**
 * Locks the phone's system UI while a machine window is on screen and gives it back when none is.
 *
 * The lock is `cmd statusbar send-disable-flag ...` run through Shizuku. It stays set in the system until it is cleared,
 * so it is cleared whenever no machine window is on screen (see [pause], [releaseIfIdle]); a marker file says "we locked",
 * and the library window and the app's start clear a lock that was left behind (a crashed machine). A reboot clears it too.
 * Machine windows of several processes can be on screen together (split): each one is a holder file named by its process.
 */
object SystemUiLock {
    private val main = Handler(Looper.getMainLooper())

    private fun holderDir(ctx: Context) = File(ctx.filesDir, "ui-lock").apply { mkdirs() }
    private fun holderFile(ctx: Context, owner: Any) = File(holderDir(ctx), "${AndroidProcess.myPid()}-${System.identityHashCode(owner)}")
    private fun marker(ctx: Context) = File(ctx.filesDir, "ui-lock-applied")

    private fun liveHolders(ctx: Context): Int {
        var n = 0
        for (f in holderDir(ctx).listFiles() ?: return 0) {
            val pid = f.name.substringBefore('-').toIntOrNull()
            if (pid != null && File("/proc/$pid").exists()) n++ else f.delete()
        }
        return n
    }

    /** A machine window came on screen: lock what the person chose. */
    fun resume(ctx: Context, owner: Any) {
        val app = ctx.applicationContext
        val o = ShizukuLockStore.read(app)
        if (!o.any) return
        runCatching { holderFile(app, owner).createNewFile() }
        main.removeCallbacksAndMessages(null)
        Thread { applyLock(app, o) }.start()
    }

    /** A machine window left the screen: unlock soon, unless another machine window took over. */
    fun pause(ctx: Context, owner: Any) {
        val app = ctx.applicationContext
        runCatching { holderFile(app, owner).delete() }
        if (!marker(app).exists()) return
        main.postDelayed({ Thread { releaseIfIdle(app) }.start() }, 700)
    }

    /** Unlocks when we locked and no machine window is on screen. Blocking: call it off the main thread. */
    fun releaseIfIdle(ctx: Context) {
        val app = ctx.applicationContext
        if (!marker(app).exists()) return
        if (liveHolders(app) > 0) return
        // wait for Shizuku BEFORE taking the lock, then look again: a machine window may have come on screen meanwhile
        if (!ShizukuAccess.awaitReady(2000)) return
        synchronized(this) {
            if (liveHolders(app) > 0) return
            releaseLocked(app)
        }
    }

    /** Unlocks everything. Blocking: call it off the main thread. */
    fun releaseNow(ctx: Context) {
        val app = ctx.applicationContext
        if (!ShizukuAccess.awaitReady(2000)) return
        synchronized(this) { releaseLocked(app) }
    }

    private fun releaseLocked(app: Context) {
        var (code, _) = ShizukuAccess.exec("cmd", "statusbar", "send-disable-flag", "none")
        if (code != 0) { // once more: the status bar service may be busy
            try { Thread.sleep(300) } catch (_: InterruptedException) { }
            code = ShizukuAccess.exec("cmd", "statusbar", "send-disable-flag", "none").first
        }
        watchFlags = null
        restorePowerLongPress(app)
        note(app, "unlocked -> exit $code")
        // the marker stays when the system did not take the unlock back: the next start tries again
        if (code == 0) runCatching { marker(app).delete() }
    }

    /** The system's disable flags for what the person chose. Names the system does not know are ignored by it. */
    private fun flagsFor(o: ShizukuOptions): List<String> {
        val want = LinkedHashSet<String>()
        if (o.notifications) want += listOf("notification-peek", "notification-alerts", "notification-icons")
        if (o.notifications && o.quickSettings) want += "notification-shade"
        // "quick-settings" is a separate flag only on newer Android (and some skins ignore it): the whole pull-down goes as well
        if (o.quickSettings) want += listOf("quick-settings", "statusbar-expansion")
        // Recents is locked with Home: otherwise the app switcher is a way out of the machine
        if (o.home) want += listOf("home", "recents")
        if (o.back) want += "back"
        return want.toList()
    }

    /** Sends the flags; the answer of the system is kept (see [lastResult]) so a lock that does not work can be understood. */
    private fun sendFlags(app: Context, use: List<String>): Boolean {
        var r = ShizukuAccess.exec("cmd", "statusbar", "send-disable-flag", *use.toTypedArray())
        if (r.first != 0) { // once more: the status bar service may be busy
            try { Thread.sleep(300) } catch (_: InterruptedException) { }
            r = ShizukuAccess.exec("cmd", "statusbar", "send-disable-flag", *use.toTypedArray())
        }
        note(app, "send-disable-flag ${use.joinToString(" ")} -> exit ${r.first}" + (if (r.second.isNotBlank()) ": ${r.second.take(160)}" else ""))
        return r.first == 0
    }

    private fun lastFile(app: Context) = File(app.filesDir, "ui-lock-last.txt")
    private fun note(app: Context, text: String) {
        android.util.Log.i("QuDroidLock", text)
        runCatching { lastFile(app).writeText(text) }
    }
    /** What the last attempt to lock or unlock the phone's system UI said (empty: none yet). */
    fun lastResult(ctx: Context): String = runCatching { lastFile(ctx.applicationContext).readText() }.getOrDefault("")

    @Volatile private var watchFlags: List<String>? = null
    @Volatile private var watching = false

    /** Some systems drop the flags (screen off/on, status bar restart): while a machine is on screen they are sent again. */
    private fun startWatch(app: Context, use: List<String>) {
        watchFlags = use
        if (watching) return
        watching = true
        Thread({
            try {
                while (true) {
                    Thread.sleep(3000)
                    val flags = watchFlags ?: break
                    synchronized(this) {
                        if (!marker(app).exists() || liveHolders(app) == 0) { watchFlags = null }
                        else ShizukuAccess.exec("cmd", "statusbar", "send-disable-flag", *flags.toTypedArray())
                    }
                    if (watchFlags == null) break
                }
            } catch (_: InterruptedException) {
            } finally { watching = false }
        }, "ui-lock-watch").apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun applyLock(app: Context, o: ShizukuOptions) {
        if (!ShizukuAccess.awaitReady()) {
            note(app, "not locked: " + ShizukuAccess.describe(ShizukuAccess.state(app)))
            return
        }
        val use = flagsFor(o)
        if (use.isEmpty() && !o.disablePower) return
        runCatching { marker(app).writeText("1") } // before the command: a lock without a marker could never be taken back
        if (o.disablePower) holdPowerLongPress(app)
        if (use.isNotEmpty()) {
            sendFlags(app, use)
            startWatch(app, use)
        }
    }

    private const val POWER_LONG = "power_button_long_press"
    private fun powerSaved(app: Context) = File(app.filesDir, "ui-lock-power-long")

    /**
     * The phone's own long-press of Power (its power menu) is set to "nothing" while a machine is on screen; the value
     * it had is kept in a file and put back in [releaseNow]. The long press is then taken by [PowerKeyWatcher].
     * The setting exists from Android 11; on older systems the phone's menu may still open.
     */
    private fun holdPowerLongPress(app: Context) {
        val f = powerSaved(app)
        if (!f.exists()) {
            val (code, out) = ShizukuAccess.exec("settings", "get", "global", POWER_LONG)
            if (code != 0) return
            runCatching { f.writeText(out.trim().ifEmpty { "null" }) }
        }
        if (f.exists()) ShizukuAccess.exec("settings", "put", "global", POWER_LONG, "0")
    }

    private fun restorePowerLongPress(app: Context) {
        val f = powerSaved(app)
        if (!f.exists()) return
        val v = runCatching { f.readText().trim() }.getOrDefault("null")
        if (v.isEmpty() || v == "null") ShizukuAccess.exec("settings", "delete", "global", POWER_LONG)
        else ShizukuAccess.exec("settings", "put", "global", POWER_LONG, v)
        runCatching { f.delete() }
    }
}
