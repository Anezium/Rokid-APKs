package com.rokidapks

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CxrLHiRokidSession(
    private val activity: AppCompatActivity,
    private val onStatus: (String) -> Unit,
    private val onBusyChanged: (Boolean) -> Unit,
) {
    companion object {
        const val AUTH_REQUEST_CODE = 4027

        private const val GLOBAL_AI_APP_PACKAGE = "com.rokid.sprite.global.aiapp"
        private const val AUTH_ACTIVITY_CLASS = "com.rokid.sprite.aiapp.externalapp.auth.AuthorizationActivity"
        private const val AUTH_ACTION = "com.rokid.sprite.aiapp.externalapp.AUTHORIZATION"
        private const val MEDIA_SERVICE_ACTION = "com.rokid.sprite.aiapp.externalapp.MEDIA_STREAM_SERVICE"
        private const val AUTH_TOKEN_EXTRA = "auth_token"
    }

    private var token: String? = null
    private var cxrLink: CXRLink? = null
    private var connectedPackageName: String? = null
    private var cxrlConnected = false
    private var glassBtConnected = false
    private var pendingUpload: File? = null
    private var uploadStarted = false
    private var timeoutJob: Job? = null

    fun requestAuthorization() {
        if (!isGlobalHiRokidInstalled()) {
            onStatus("CXR-L: global Hi Rokid app is not installed.")
            return
        }

        runCatching {
            val intent = Intent().setComponent(ComponentName(GLOBAL_AI_APP_PACKAGE, AUTH_ACTIVITY_CLASS))
            activity.startActivityForResult(intent, AUTH_REQUEST_CODE)
        }.recoverCatching {
            val fallback = Intent(AUTH_ACTION).setPackage(GLOBAL_AI_APP_PACKAGE)
            activity.startActivityForResult(fallback, AUTH_REQUEST_CODE)
        }.onSuccess {
            onStatus("CXR-L: authorization opened in Hi Rokid.")
        }.onFailure { error ->
            onStatus("CXR-L: failed to open Hi Rokid authorization: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun handleAuthorizationResult(resultCode: Int, data: Intent?) {
        val result = AuthorizationHelper.INSTANCE.parseAuthorizationResult(resultCode, data)
        when (result) {
            is AuthResult.AuthSuccess -> {
                token = result.token
                onStatus("CXR-L: authorization token received.")
            }

            is AuthResult.AuthCancel -> {
                onStatus("CXR-L: authorization cancelled.")
            }

            is AuthResult.AuthFail -> {
                onStatus("CXR-L: authorization failed.")
            }
        }
    }

    fun installApk(apkUri: Uri) {
        if (!isGlobalHiRokidInstalled()) {
            onStatus("CXR-L: install global Hi Rokid on this phone first.")
            return
        }

        if (!isWifiEnabled()) {
            onStatus("CXR-L: turn on phone Wi-Fi first. Hi Rokid needs it to join the glasses hotspot.")
            return
        }

        val authToken = token
        if (authToken.isNullOrBlank()) {
            onStatus("CXR-L: press AUTH first, then approve Hi Rokid authorization.")
            requestAuthorization()
            return
        }

        activity.lifecycleScope.launch {
            onBusyChanged(true)
            runCatching {
                val staged = withContext(Dispatchers.IO) { stageApk(apkUri) }
                val packageName = readPackageName(staged)
                onStatus("CXR-L: APK package detected: $packageName")
                connectAndUpload(authToken, packageName, staged)
            }.onFailure { error ->
                pendingUpload = null
                onStatus("CXR-L failed: ${error.message ?: error.javaClass.simpleName}")
                onBusyChanged(false)
            }
        }
    }

    fun cleanup() {
        timeoutJob?.cancel()
        timeoutJob = null
        runCatching {
            cxrLink?.disconnect()
        }
        pendingUpload?.delete()
        cxrLink = null
        pendingUpload = null
        connectedPackageName = null
        cxrlConnected = false
        glassBtConnected = false
        uploadStarted = false
    }

    private fun connectAndUpload(authToken: String, packageName: String, apkFile: File) {
        cleanup()

        val link = CXRLink(activity.applicationContext).also { newLink ->
            newLink.setCXRLinkCbk(object : ICXRLinkCbk {
                override fun onCXRLConnected(connected: Boolean) {
                    activity.runOnUiThread {
                        cxrlConnected = connected
                        onStatus("CXR-L service connected: $connected")
                        maybeUploadPending()
                    }
                }

                override fun onGlassBtConnected(connected: Boolean) {
                    activity.runOnUiThread {
                        glassBtConnected = connected
                        onStatus("CXR-L glasses Bluetooth connected: $connected")
                        maybeUploadPending()
                    }
                }

                override fun onGlassAiAssistStart() {
                    activity.runOnUiThread { onStatus("CXR-L: Hi Rokid assistant started.") }
                }

                override fun onGlassAiAssistStop() {
                    activity.runOnUiThread { onStatus("CXR-L: Hi Rokid assistant stopped.") }
                }
            })
            cxrLink = newLink
            newLink
        }

        connectedPackageName = packageName
        pendingUpload = apkFile
        cxrlConnected = false
        glassBtConnected = false
        uploadStarted = false
        timeoutJob = activity.lifecycleScope.launch {
            delay(90_000)
            if (pendingUpload != null) {
                onStatus("CXR-L: timed out waiting for Hi Rokid/glasses install result.")
                cleanup()
                onBusyChanged(false)
            }
        }

        val configured = link.configCXRSession(
            CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, packageName),
        )
        if (!configured) {
            pendingUpload = null
            onBusyChanged(false)
            onStatus("CXR-L: failed to configure CUSTOMAPP session.")
            return
        }

        onStatus("CXR-L: binding to global Hi Rokid service...")
        val bindStarted = bindGlobalHiRokidService(link, authToken)
        if (!bindStarted) {
            pendingUpload = null
            onBusyChanged(false)
            onStatus("CXR-L: service bind failed. Open/force-close Hi Rokid, then retry.")
        }
    }

    private fun maybeUploadPending() {
        val apkFile = pendingUpload ?: return
        if (uploadStarted || !cxrlConnected || !glassBtConnected) {
            return
        }

        uploadStarted = true
        onStatus("CXR-L: link ready, uploading APK through Hi Rokid...")
        cxrLink?.appUploadAndInstall(apkFile.absolutePath, object : IGlassAppCbk {
            override fun onInstallAppResult(success: Boolean) {
                activity.runOnUiThread {
                    timeoutJob?.cancel()
                    timeoutJob = null
                    pendingUpload = null
                    uploadStarted = false
                    apkFile.delete()
                    onStatus(if (success) "CXR-L install succeeded." else "CXR-L install failed.")
                    onBusyChanged(false)
                }
            }

            override fun onUnInstallAppResult(success: Boolean) = Unit
            override fun onOpenAppResult(success: Boolean) = Unit
            override fun onStopAppResult(success: Boolean) = Unit
            override fun onGlassAppResume(resumed: Boolean) = Unit
            override fun onQueryAppResult(installed: Boolean) = Unit
        })
    }

    private fun bindGlobalHiRokidService(link: CXRLink, authToken: String): Boolean {
        return runCatching {
            val connection = findServiceConnection(link)
            val intent = Intent(MEDIA_SERVICE_ACTION)
                .setPackage(GLOBAL_AI_APP_PACKAGE)
                .putExtra(AUTH_TOKEN_EXTRA, authToken)
            activity.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrElse { error ->
            onStatus("CXR-L: reflection bind failed: ${error.message ?: error.javaClass.simpleName}")
            false
        }
    }

    private fun findServiceConnection(link: CXRLink): ServiceConnection {
        var type: Class<*>? = link.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { field ->
                ServiceConnection::class.java.isAssignableFrom(field.type)
            }
            if (field != null) {
                field.isAccessible = true
                return field.get(link) as ServiceConnection
            }
            type = type.superclass
        }
        error("CXR-L ServiceConnection field not found")
    }

    private fun isGlobalHiRokidInstalled(): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.packageManager.getPackageInfo(
                    GLOBAL_AI_APP_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                activity.packageManager.getPackageInfo(GLOBAL_AI_APP_PACKAGE, 0)
            }
        }.isSuccess
    }

    private fun isWifiEnabled(): Boolean {
        val wifiManager = activity.applicationContext.getSystemService(WifiManager::class.java)
        return wifiManager?.isWifiEnabled == true
    }

    private suspend fun stageApk(uri: Uri): File = withContext(Dispatchers.IO) {
        val dir = File(activity.cacheDir, "cxrl-upload").apply { mkdirs() }
        val file = File(dir, "selected-${System.currentTimeMillis()}.apk")
        activity.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Cannot open selected APK")
        file
    }

    private fun readPackageName(apkFile: File): String {
        @Suppress("DEPRECATION")
        val info = activity.packageManager.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_ACTIVITIES,
        )
        return info?.packageName?.takeIf { it.isNotBlank() }
            ?: error("Cannot read APK package name")
    }
}
