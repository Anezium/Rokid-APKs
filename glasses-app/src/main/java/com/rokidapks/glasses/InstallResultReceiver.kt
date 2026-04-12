package com.rokidapks.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast

class InstallResultReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "RokidApksSpp"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        Log.d(TAG, "InstallResultReceiver status=$status message='$statusMessage'")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmationIntent = extractConfirmationIntent(intent)
                confirmationIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmationIntent != null) {
                    context.startActivity(confirmationIntent)
                }
                publishStatus(context, status, "Install prompt opened on the glasses.")
            }

            PackageInstaller.STATUS_SUCCESS -> {
                PackageInstallHelper.cleanupPendingApk(context)
                publishStatus(context, status, "Install succeeded.")
                Toast.makeText(context, "APK installed on the glasses.", Toast.LENGTH_LONG).show()
            }

            else -> {
                PackageInstallHelper.cleanupPendingApk(context)
                val message = if (statusMessage.isBlank()) {
                    "Install failed with status $status."
                } else {
                    "Install failed: $statusMessage"
                }
                publishStatus(context, status, message)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun publishStatus(context: Context, status: Int, message: String) {
        context.sendBroadcast(
            Intent(PackageInstallHelper.ACTION_INSTALL_STATUS)
                .setPackage(context.packageName)
                .putExtra(PackageInstallHelper.EXTRA_STATUS, status)
                .putExtra(PackageInstallHelper.EXTRA_MESSAGE, message),
        )
    }

    @Suppress("DEPRECATION")
    private fun extractConfirmationIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    }
}
