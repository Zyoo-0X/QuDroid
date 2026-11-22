package app.aemu

import android.app.Application
import app.aemu.core.Keeper

class AemuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Keeper.ensureChannel(this)
        app.aemu.core.ShizukuAccess.setup(this)
        // a lock left behind by a machine that died is taken back when the app starts again
        Thread { app.aemu.core.SystemUiLock.releaseIfIdle(this) }.start()
    }
}
