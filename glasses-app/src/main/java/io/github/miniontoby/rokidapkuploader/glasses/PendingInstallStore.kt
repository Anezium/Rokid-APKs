package io.github.miniontoby.rokidapkuploader.glasses

import android.content.Context

object PendingInstallStore {
    private const val PREFS_NAME = "rokid_apks_spp_companion"
    private const val KEY_PENDING_APK_PATH = "pending_apk_path"
    private const val KEY_PENDING_APK_SAVED_AT = "pending_apk_saved_at"
    private const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"

    fun savePendingApk(context: Context, path: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_APK_PATH, path)
            .putLong(KEY_PENDING_APK_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    fun getPendingApk(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_APK_PATH, null)
    }

    fun getPendingApkSavedAt(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_PENDING_APK_SAVED_AT, 0L)
    }

    fun clearPendingApk(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_APK_PATH)
            .remove(KEY_PENDING_APK_SAVED_AT)
            .apply()
    }

    fun saveLastDeviceAddress(context: Context, address: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DEVICE_ADDRESS, address)
            .apply()
    }

    fun getLastDeviceAddress(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_DEVICE_ADDRESS, null)
    }
}
