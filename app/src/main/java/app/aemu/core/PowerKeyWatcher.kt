/* Added for QuDroid: sees a long press of the phone's Power button through Shizuku (getevent), so that the machine's own
 * power menu can open instead of the phone's. GPL-3.0; see NOTICE.md. */
package app.aemu.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * An app cannot see the Power button, but the shell user can read the input devices. While a machine is on screen the
 * watcher runs `getevent -l` through Shizuku and reports a Power key that is held for [LONG_MS]. The phone's own
 * long-press is switched off meanwhile (see SystemUiLock), so only the machine reacts.
 */
object PowerKeyWatcher {
    private const val LONG_MS = 550L
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var want = false
    @Volatile private var proc: Process? = null
    @Volatile private var lastLong = 0L
    @Volatile private var onLong: (() -> Unit)? = null
    private val fire = Runnable { lastLong = SystemClock.elapsedRealtime(); onLong?.invoke() }

    @Synchronized
    fun start(onLongPress: () -> Unit) {
        onLong = onLongPress
        if (want) return
        want = true
        Thread({ loop() }, "power-key-watch").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        want = false
        main.removeCallbacks(fire)
        runCatching { proc?.destroy() }
        proc = null
    }

    /** True if a long press was seen in the last [ms] milliseconds (the phone may still turn its screen off on release). */
    fun longPressedWithin(ms: Long): Boolean = lastLong != 0L && SystemClock.elapsedRealtime() - lastLong < ms

    private fun pause(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) { } }

    private fun loop() {
        while (want) {
            if (!ShizukuAccess.awaitReady(3000)) { pause(1000); continue }
            val p = ShizukuAccess.spawn("getevent", "-l")
            if (p == null) { pause(2000); continue }
            proc = p
            try {
                p.inputStream.bufferedReader().forEachLine { if (want) handle(it) }
            } catch (_: Throwable) { }
            runCatching { p.destroy() }
            main.removeCallbacks(fire)
            if (want) pause(1000)
        }
    }

    private fun handle(line: String) {
        if (!line.contains("KEY_POWER")) return
        val l = line.trimEnd()
        if (l.endsWith("DOWN")) { main.removeCallbacks(fire); main.postDelayed(fire, LONG_MS) }
        else if (l.endsWith("UP")) main.removeCallbacks(fire)
    }
}
