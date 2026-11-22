package app.aemu

import android.app.Application
import app.aemu.core.Keeper

class AemuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Keeper.ensureChannel(this)
        app.aemu.core.ShizukuAccess.setup(this)
        // a lock left behind by a machine that died is taken back when the app starts again
        // main process only: a machine process that started a moment ago must not undo the lock its own window sets
        if (app.aemu.core.ShizukuAccess.isMainProcess()) Thread { app.aemu.core.SystemUiLock.releaseIfIdle(this) }.start()
    }
}
