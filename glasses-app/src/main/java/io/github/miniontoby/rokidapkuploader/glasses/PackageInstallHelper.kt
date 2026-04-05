package io.github.miniontoby.rokidapkuploader.glasses

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File

object PackageInstallHelper {
    const val ACTION_INSTALL_STATUS = "io.github.miniontoby.rokidapkuploader.glasses.INSTALL_STATUS"
    const val EXTRA_STATUS = "status"
    const val EXTRA_MESSAGE = "message"

    fun requestInstall(activity: Activity, apkFile: File, onStatus: (String) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            PendingInstallStore.savePendingApk(activity, apkFile.absolutePath)
            onStatus("Allow installs from this companion once, then return to the app.")
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}"),
                ),
            )
            return false
        }

        return startPackageInstaller(activity, apkFile, onStatus)
    }

    fun resumePendingInstallIfPossible(activity: Activity, onStatus: (String) -> Unit): Boolean {
        val pendingPath = PendingInstallStore.getPendingApk(activity) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            return false
        }

        val pendingFile = File(pendingPath)
        if (!pendingFile.exists()) {
            PendingInstallStore.clearPendingApk(activity)
            onStatus("Pending APK no longer exists. Send it again from the phone.")
            return false
        }

        return startPackageInstaller(activity, pendingFile, onStatus)
    }

    private fun startPackageInstaller(
        context: Context,
        apkFile: File,
        onStatus: (String) -> Unit,
    ): Boolean {
        return runCatching {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setSize(apkFile.length())
            }
            val sessionId = packageInstaller.createSession(params)
            packageInstaller.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite("package.apk", 0, apkFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }

                PendingInstallStore.clearPendingApk(context)
                val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, InstallResultReceiver::class.java),
                    pendingIntentFlags,
                )
                session.commit(pendingIntent.intentSender)
            }
            onStatus("PackageInstaller started. Watch the glasses for the confirmation prompt.")
        }.isSuccess
    }
}
