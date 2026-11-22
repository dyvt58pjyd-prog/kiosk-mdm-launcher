package com.kiosk.launcher.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages persistent configuration for the Kiosk Launcher and MDM Agent.
 */
class KioskPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "kiosk_mdm_preferences"
        private const val KEY_WHITELISTED_PACKAGES = "whitelisted_packages"
        private const val KEY_ADMIN_PIN = "admin_pin"
        private const val KEY_KIOSK_ENABLED = "kiosk_enabled"
        private const val KEY_DISALLOW_SAFE_BOOT = "disallow_safe_boot"
        private const val KEY_DISALLOW_FACTORY_RESET = "disallow_factory_reset"
        private const val KEY_DISALLOW_ADD_USER = "disallow_add_user"
        private const val KEY_DISALLOW_MOUNT_MEDIA = "disallow_mount_media"
        private const val KEY_DISALLOW_STATUS_BAR = "disallow_status_bar"
        private const val KEY_MDM_SERVER_URL = "mdm_server_url"
        private const val KEY_MDM_REPORTING_INTERVAL = "mdm_reporting_interval_min"
        private const val KEY_LOCAL_SERVER_ENABLED = "local_server_enabled"
        private const val KEY_LOCAL_SERVER_PORT = "local_server_port"

        // Standard default whitelisted apps (Settings + Browser if installed)
        private val DEFAULT_WHITELIST = setOf(
            "com.android.chrome",
            "com.google.android.apps.docs",
            "com.android.calculator2",
            "com.google.android.calculator"
        )
        const val DEFAULT_PIN = "1234"
    }

    var whitelistedPackages: Set<String>
        get() = prefs.getStringSet(KEY_WHITELISTED_PACKAGES, DEFAULT_WHITELIST) ?: DEFAULT_WHITELIST
        set(value) = prefs.edit().putStringSet(KEY_WHITELISTED_PACKAGES, value).apply()

    fun addWhitelistedPackage(packageName: String) {
        val current = whitelistedPackages.toMutableSet()
        current.add(packageName)
        whitelistedPackages = current
    }

    fun removeWhitelistedPackage(packageName: String) {
        val current = whitelistedPackages.toMutableSet()
        current.remove(packageName)
        whitelistedPackages = current
    }

    fun isPackageWhitelisted(packageName: String): Boolean {
        return whitelistedPackages.contains(packageName)
    }

    var adminPin: String
        get() = prefs.getString(KEY_ADMIN_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
        set(value) = prefs.edit().putString(KEY_ADMIN_PIN, value).apply()

    var isKioskModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_KIOSK_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_KIOSK_ENABLED, value).apply()

    var disallowSafeBoot: Boolean
        get() = prefs.getBoolean(KEY_DISALLOW_SAFE_BOOT, true)
        set(value) = prefs.edit().putBoolean(KEY_DISALLOW_SAFE_BOOT, value).apply()

    var disallowFactoryReset: Boolean
        get() = prefs.getBoolean(KEY_DISALLOW_FACTORY_RESET, true)
        set(value) = prefs.edit().putBoolean(KEY_DISALLOW_FACTORY_RESET, value).apply()

    var disallowAddUser: Boolean
        get() = prefs.getBoolean(KEY_DISALLOW_ADD_USER, true)
        set(value) = prefs.edit().putBoolean(KEY_DISALLOW_ADD_USER, value).apply()

    var disallowMountPhysicalMedia: Boolean
        get() = prefs.getBoolean(KEY_DISALLOW_MOUNT_MEDIA, true)
        set(value) = prefs.edit().putBoolean(KEY_DISALLOW_MOUNT_MEDIA, value).apply()

    var disallowStatusBar: Boolean
        get() = prefs.getBoolean(KEY_DISALLOW_STATUS_BAR, true)
        set(value) = prefs.edit().putBoolean(KEY_DISALLOW_STATUS_BAR, value).apply()

    var mdmServerUrl: String
        get() = prefs.getString(KEY_MDM_SERVER_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MDM_SERVER_URL, value).apply()

    var mdmReportingIntervalMinutes: Int
        get() = prefs.getInt(KEY_MDM_REPORTING_INTERVAL, 15)
        set(value) = prefs.edit().putInt(KEY_MDM_REPORTING_INTERVAL, value).apply()

    var localServerEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_SERVER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_LOCAL_SERVER_ENABLED, value).apply()

    var localServerPort: Int
        get() = prefs.getInt(KEY_LOCAL_SERVER_PORT, 8080)
        set(value) = prefs.edit().putInt(KEY_LOCAL_SERVER_PORT, value).apply()
}
