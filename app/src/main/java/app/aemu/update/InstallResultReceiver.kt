package app.aemu.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import java.io.File

/**
 * Gets the answer of the system PackageInstaller for an update started by [AppUpdateManager.installApk]:
 * asks the person to confirm when Android needs it, and says in words why an install failed.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> { /* the app is replaced and restarted by Android */ }
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                val apk = intent.getStringExtra(AppUpdateManager.EXTRA_APK_PATH)?.let { File(it) }
                val msg = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                        if (apk != null && apk.exists()) AppUpdateManager.saveCopyToDownloads(context, apk)
                        AppUpdateManager.SIGNATURE_MISMATCH
                    }
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update. Free some space and try again."
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "The update was cancelled."
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "The install was blocked by the system. $detail".trim()
                    PackageInstaller.STATUS_FAILURE_INVALID -> "The update file is damaged or invalid. Download it again."
                    else -> "The update could not be installed. $detail".trim()
                }
                Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}
