package uk.anttheantster.antplayertv.data

import android.content.Context
import android.provider.Settings
import java.util.UUID

object LicenseUtils {

    private const val PREFS_NAME = "license_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_LICENSE = "license_key"

    fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing

        // Prefer ANDROID_ID as base
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()

        prefs.edit().putString(KEY_DEVICE_ID, androidId).apply()
        return androidId
    }

    fun getStoredLicenseKey(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LICENSE, null)
    }

    fun saveLicenseKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LICENSE, key).apply()
    }

    fun clearLicenseKey(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_LICENSE)
            .apply()
    }
}
