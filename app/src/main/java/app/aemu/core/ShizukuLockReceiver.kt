package app.aemu.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Runs in the app's main process, where the Shizuku binder always arrives: a machine process that could not get it
 * asks for the lock (or for the unlock) here.
 */
class ShizukuLockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread({
            try {
                when (intent.action) {
                    SystemUiLock.ACTION_APPLY -> SystemUiLock.applyFromRemote(app)
                    SystemUiLock.ACTION_RELEASE_IDLE -> SystemUiLock.releaseIfIdle(app)
                }
            } finally { pending.finish() }
        }, "ui-lock-remote").start()
    }
}
