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

/** Talking to the Shizuku app: is it running, has this app been allowed, run a shell command through it. */
object ShizukuAccess {
    fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun granted(): Boolean = runCatching {
        running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun request(code: Int = 1001) { runCatching { if (running()) Shizuku.requestPermission(code) } }

    /** A machine's process gets the Shizuku binder a moment after it starts. */
    fun awaitReady(ms: Long = 3000): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (granted()) return true
            try { Thread.sleep(150) } catch (_: InterruptedException) { break }
        }
        return granted()
    }

    /** Runs [cmd] as the shell user. Returns the exit code and what the command printed (-1 when it could not run). */
    fun exec(vararg cmd: String): Pair<Int, String> = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf(*cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor() to (out + err).trim()
    } catch (t: Throwable) {
        -1 to (t.message ?: t.toString())
    }

    /** Starts [cmd] as the shell user and returns the running process (for output that is read as it comes), or null. */
    fun spawn(vararg cmd: String): Process? = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        m.invoke(null, arrayOf(*cmd), null, null) as Process
    } catch (t: Throwable) { null }

    /**
     * Machines run in their own processes (":vm", ":vm1", ...), the Shizuku provider is in the main one: the machine
     * processes ask the provider for the binder. Called from Application.onCreate in every process.
     */
    fun setup(app: android.app.Application) {
        val name = runCatching { File("/proc/self/cmdline").readText().trim('\u0000') }.getOrDefault("")
        val isMain = name == app.packageName
        val isVm = name.startsWith(app.packageName + ":vm")
        if (!isMain && !isVm) return
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
    private val FLAGS = listOf(
        "notification-peek", "notification-alerts", "notification-icons", "notification-shade",
        "quick-settings", "statusbar-expansion", "home", "back",
    )
    @Volatile private var cache: Set<String>? = null

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
        releaseNow(app)
    }

    /** Unlocks everything. Blocking: call it off the main thread. */
    @Synchronized
    fun releaseNow(ctx: Context) {
        val app = ctx.applicationContext
        if (!ShizukuAccess.awaitReady(2000)) return
        ShizukuAccess.exec("cmd", "statusbar", "send-disable-flag", "none")
        restorePowerLongPress(app)
        runCatching { marker(app).delete() }
    }

    @Synchronized
    private fun applyLock(app: Context, o: ShizukuOptions) {
        if (!ShizukuAccess.awaitReady()) return
        val wantFlags = o.notifications || o.quickSettings || o.home || o.back
        val sup = if (wantFlags) supported(app) else emptySet()
        val want = LinkedHashSet<String>()
        if (o.notifications) want += listOf("notification-peek", "notification-alerts", "notification-icons")
        if (o.notifications && o.quickSettings) want += "notification-shade"
        if (o.quickSettings) want += "quick-settings"
        if (o.home) want += "home"
        if (o.back) want += "back"
        val use = want.filter { it in sup }.toMutableList()
        // older systems have no separate flags: the whole pull-down goes
        val shade = (o.quickSettings && "quick-settings" !in sup) || (o.notifications && o.quickSettings && "notification-shade" !in sup)
        if (shade && "statusbar-expansion" in sup) use += "statusbar-expansion"
        if (use.isEmpty() && !o.disablePower) return
        runCatching { marker(app).writeText("1") } // before the command: a lock without a marker could never be taken back
        if (o.disablePower) holdPowerLongPress(app)
        if (use.isNotEmpty()) ShizukuAccess.exec("cmd", "statusbar", "send-disable-flag", *use.toTypedArray())
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

    /** Which flag names this Android knows (an unknown name makes the command print an error); remembered per build. */
    private fun supported(app: Context): Set<String> {
        cache?.let { return it }
        val f = File(app.filesDir, "ui-lock-flags-${Build.FINGERPRINT.hashCode()}")
        val saved = runCatching { if (f.exists()) f.readText().split(',').filter { it.isNotEmpty() }.toSet() else null }.getOrNull()
        if (!saved.isNullOrEmpty()) { cache = saved; return saved }
        val ok = FLAGS.filter { name ->
            val (code, out) = ShizukuAccess.exec("cmd", "statusbar", "send-disable-flag", name)
            code == 0 && out.isBlank()
        }
        ShizukuAccess.exec("cmd", "statusbar", "send-disable-flag", "none")
        if (ok.isNotEmpty()) { runCatching { f.writeText(ok.joinToString(",")) }; cache = ok.toSet() }
        return ok.toSet()
    }
}
