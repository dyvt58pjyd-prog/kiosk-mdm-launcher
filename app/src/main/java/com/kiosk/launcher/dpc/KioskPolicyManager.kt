package com.kiosk.launcher.dpc

import android.app.Activity
import android.app.ActivityOptions
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import android.util.Log
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kiosk.launcher.data.KioskPreferences
import com.kiosk.launcher.receiver.KioskAdminReceiver
import com.kiosk.launcher.ui.KioskLauncherActivity

/**
 * Core Device Policy Controller (DPC) manager that interacts directly with Android's
 * DevicePolicyManager to enforce kiosk lock task mode, persistent home activity,
 * and device lockdown policies.
 */
class KioskPolicyManager(private val context: Context) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent: ComponentName = KioskAdminReceiver.getComponentName(context)
    private val prefs: KioskPreferences = KioskPreferences(context)

    companion object {
        private const val TAG = "KioskPolicyManager"
    }

    /**
     * Checks if this application is registered as the Device Owner.
     */
    fun isDeviceOwner(): Boolean {
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    /**
     * Checks if this application is an active device admin.
     */
    fun isAdminActive(): Boolean {
        return dpm.isAdminActive(adminComponent)
    }

    /**
     * Updates the Lock Task whitelisted packages on the system.
     * Always includes this launcher's package plus all approved admin packages.
     */
    fun updateLockTaskPackages(additionalPackages: Collection<String> = emptySet()) {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Cannot update lock task packages: App is not Device Owner.")
            return
        }

        try {
            val fullWhitelist = mutableSetOf<String>()
            fullWhitelist.add(context.packageName) // Kiosk Launcher itself
            fullWhitelist.addAll(prefs.whitelistedPackages)
            fullWhitelist.addAll(additionalPackages)
            // Allow settings package safely for Wi-Fi access if needed
            fullWhitelist.add("com.android.settings")

            val packageArray = fullWhitelist.toTypedArray()
            dpm.setLockTaskPackages(adminComponent, packageArray)
            Log.i(TAG, "Lock task packages updated successfully: ${packageArray.joinToString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating lock task packages", e)
        }
    }

    /**
     * Configures System UI Lockdown in Lock Task Mode.
     * On Android 9.0+ (API 28+), restricts all system UI features except the Home button.
     * Explicitly blocks notifications, status bar dropdown, recents/overview, and global actions.
     */
    fun configureLockTaskFeatures() {
        if (!isDeviceOwner()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                // Allow ONLY HOME button navigation. Block notifications, recents, global actions, keyguard.
                val flags = DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                dpm.setLockTaskFeatures(adminComponent, flags)
                Log.i(TAG, "Configured LOCK_TASK_FEATURE_HOME on API 28+")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting lock task features", e)
            }
        }
    }

    /**
     * Enforces this Kiosk Launcher as the persistent default Home application,
     * ensuring pressing the Home button or booting always directs back to Kiosk.
     */
    fun setPersistentDefaultLauncher() {
        if (!isDeviceOwner()) return

        try {
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            val launcherActivity = ComponentName(context, KioskLauncherActivity::class.java)
            dpm.addPersistentPreferredActivity(adminComponent, filter, launcherActivity)
            Log.i(TAG, "Persistent default Home launcher registered.")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting persistent default launcher", e)
        }
    }

    /**
     * Clears the persistent preferred activity association so the user can
     * return to the stock launcher during administrative maintenance.
     */
    fun clearPersistentDefaultLauncher() {
        if (!isDeviceOwner()) return

        try {
            dpm.clearPackagePersistentPreferredActivities(adminComponent, context.packageName)
            Log.i(TAG, "Cleared persistent preferred launcher activity.")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing persistent launcher activity", e)
        }
    }

    /**
     * Applies standard OS hardening restrictions (Safe Boot, Factory Reset, User creation, USB storage).
     */
    fun applyUserRestrictions() {
        if (!isDeviceOwner()) return

        try {
            setUserRestriction(UserManager.DISALLOW_SAFE_BOOT, prefs.disallowSafeBoot)
            setUserRestriction(UserManager.DISALLOW_FACTORY_RESET, prefs.disallowFactoryReset)
            setUserRestriction(UserManager.DISALLOW_ADD_USER, prefs.disallowAddUser)
            setUserRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, prefs.disallowMountPhysicalMedia)
            setUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER, true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && prefs.disallowStatusBar) {
                setUserRestriction("no_status_bar", true)
            }
            Log.i(TAG, "User lockdown restrictions applied.")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying user restrictions", e)
        }
    }

    /**
     * Clears all lockdown restrictions for maintenance.
     */
    fun clearUserRestrictions() {
        if (!isDeviceOwner()) return

        try {
            setUserRestriction(UserManager.DISALLOW_SAFE_BOOT, false)
            setUserRestriction(UserManager.DISALLOW_FACTORY_RESET, false)
            setUserRestriction(UserManager.DISALLOW_ADD_USER, false)
            setUserRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, false)
            setUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER, false)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setUserRestriction("no_status_bar", false)
            }
            Log.i(TAG, "User lockdown restrictions cleared.")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing user restrictions", e)
        }
    }

    private fun setUserRestriction(restrictionKey: String, disallow: Boolean) {
        try {
            if (disallow) {
                dpm.addUserRestriction(adminComponent, restrictionKey)
            } else {
                dpm.clearUserRestriction(adminComponent, restrictionKey)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle restriction $restrictionKey -> $disallow", e)
        }
    }

    /**
     * Master activation method: Enforces whitelist, UI lock, persistent launcher, and user restrictions.
     */
    fun activateKioskLockdown(activity: Activity) {
        if (!isDeviceOwner()) {
            Log.w(TAG, "App is not Device Owner. Lock Task will function as unpinned/screen-pinned fallback.")
            return
        }

        updateLockTaskPackages()
        configureLockTaskFeatures()
        setPersistentDefaultLauncher()
        applyUserRestrictions()

        try {
            activity.startLockTask()
            Log.i(TAG, "Activity entered Lock Task mode.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Lock Task mode", e)
        }
    }

    /**
     * Master deactivation method: Stops lock task and removes restrictions for admin maintenance.
     */
    fun deactivateKioskLockdown(activity: Activity) {
        try {
            activity.stopLockTask()
            Log.i(TAG, "Activity exited Lock Task mode.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop Lock Task mode", e)
        }

        clearPersistentDefaultLauncher()
        clearUserRestrictions()
    }

    /**
     * Launches a whitelisted app inside Lock Task Mode using ActivityOptions.
     */
    fun launchWhitelistedApp(activity: Activity, targetPackageName: String) {
        try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(targetPackageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // API 28+: Launch target locked inside task stack
                    val options = ActivityOptions.makeBasic()
                    options.setLockTaskEnabled(true)
                    activity.startActivity(launchIntent, options.toBundle())
                } else {
                    activity.startActivity(launchIntent)
                }
            } else {
                Log.e(TAG, "No launch intent found for package: $targetPackageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch whitelisted app $targetPackageName", e)
        }
    }

    /**
     * Enforces Immersive Fullscreen (Hides Status & Navigation bars with swipe-to-reveal fallback).
     */
    fun applyImmersiveFullscreen(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /**
     * Reboots the device (MDM remote command / Admin action).
     */
    fun rebootDevice() {
        if (!isDeviceOwner()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.reboot(adminComponent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reboot device", e)
        }
    }

    /**
     * Forces immediate screen lock (MDM remote command / Admin action).
     */
    fun lockDeviceNow() {
        try {
            dpm.lockNow()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lock device", e)
        }
    }

    /**
     * Dangerous Action: Relinquishes Device Owner status.
     * Allows clean uninstallation without factory reset.
     */
    fun deactivateDeviceOwner() {
        if (!isDeviceOwner()) return
        try {
            clearPersistentDefaultLauncher()
            clearUserRestrictions()
            dpm.clearDeviceOwnerApp(context.packageName)
            Log.i(TAG, "Device Owner status relinquished.")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing Device Owner", e)
        }
    }
}
