package com.kiosk.launcher.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * DeviceAdminReceiver responsible for handling Device Owner lifecycle events,
 * Lock Task state transitions, and administration callbacks.
 */
class KioskAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "KioskAdminReceiver"

        /**
         * Helper to get the ComponentName representing this receiver.
         */
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, KioskAdminReceiver::class.java)
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Owner / Device Admin privileges granted.")
        Toast.makeText(context, "Kiosk DPC: Device Owner Enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device Owner / Device Admin privileges revoked.")
        Toast.makeText(context, "Kiosk DPC: Device Owner Disabled", Toast.LENGTH_LONG).show()
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Log.i(TAG, "Lock Task Mode ENTERING for package: $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        Log.w(TAG, "Lock Task Mode EXITED.")
    }
}
