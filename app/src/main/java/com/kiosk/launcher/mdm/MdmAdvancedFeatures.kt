package com.kiosk.launcher.mdm

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import com.kiosk.launcher.dpc.KioskPolicyManager
import com.kiosk.launcher.receiver.KioskAdminReceiver
import org.json.JSONArray
import org.json.JSONObject

/**
 * Controller executing advanced MDM hardware and security commands pushed
 * from the Central Web Dashboard:
 * - Emergency Siren / Find My Tablet
 * - Remote Pop-up Broadcast Messages
 * - Remote Brightness & Volume Control
 * - Camera Restriction (Hardware disable)
 * - Screen Timeout Lockdown
 * - GPS / Network Geolocation Tracking
 * - Full Device App Inventory
 * - Enterprise Remote Wipe (Safety Guarded)
 */
class MdmAdvancedFeatures(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = KioskAdminReceiver.getComponentName(context)
    private val policyManager = KioskPolicyManager(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    companion object {
        private const val TAG = "MdmAdvancedFeatures"
        private var currentRingtone: Ringtone? = null
    }

    /**
     * Triggers an emergency high-pitched alarm / siren at maximum volume,
     * overriding silent mode to locate a misplaced or stolen tablet.
     */
    fun playEmergencySiren(durationSeconds: Int = 10) {
        try {
            stopEmergencySiren()

            // Maximize alarm and music stream volumes
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            currentRingtone = RingtoneManager.getRingtone(context, alarmUri).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                play()
            }

            Log.i(TAG, "Emergency siren playing for $durationSeconds seconds.")

            // Automatically stop after specified duration
            Handler(Looper.getMainLooper()).postDelayed({
                stopEmergencySiren()
            }, durationSeconds * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play emergency siren", e)
        }
    }

    fun stopEmergencySiren() {
        try {
            currentRingtone?.let {
                if (it.isPlaying) it.stop()
            }
            currentRingtone = null
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Displays a critical broadcast pop-up message over the Kiosk screen.
     */
    fun showBroadcastMessage(title: String, message: String, activity: Activity? = null) {
        Handler(Looper.getMainLooper()).post {
            try {
                val act = activity ?: return@post
                AlertDialog.Builder(act)
                    .setTitle(title.ifBlank { "Administrator Notification" })
                    .setMessage(message)
                    .setPositiveButton("Acknowledge") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .setCancelable(false)
                    .show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to display broadcast dialog", e)
            }
        }
    }

    /**
     * Remotely controls the tablet's media volume (0 to 100%).
     */
    fun setVolumeLevel(percentage: Int) {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = ((percentage.coerceIn(0, 100) / 100.0) * max).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            Log.i(TAG, "Media volume set to $percentage% ($target/$max)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume", e)
        }
    }

    /**
     * Remotely overrides the tablet's screen brightness (0.05 to 1.0).
     */
    fun setScreenBrightness(brightnessPct: Float, activity: Activity? = null) {
        Handler(Looper.getMainLooper()).post {
            try {
                val act = activity ?: return@post
                val lp = act.window.attributes
                lp.screenBrightness = brightnessPct.coerceIn(0.05f, 1.0f)
                act.window.attributes = lp
                Log.i(TAG, "Screen brightness remotely adjusted to $brightnessPct")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set brightness", e)
            }
        }
    }

    /**
     * Remotely disables or enables all hardware cameras on the tablet.
     */
    fun setCameraDisabled(disabled: Boolean) {
        if (!policyManager.isDeviceOwner()) return
        try {
            dpm.setCameraDisabled(adminComponent, disabled)
            Log.i(TAG, "Hardware Camera disabled set to: $disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set camera disabled", e)
        }
    }

    fun isCameraDisabled(): Boolean {
        return if (policyManager.isDeviceOwner()) dpm.getCameraDisabled(adminComponent) else false
    }

    /**
     * Remotely sets maximum inactivity timeout before screen lock.
     */
    fun setScreenTimeout(timeoutSeconds: Long) {
        if (!policyManager.isDeviceOwner()) return
        try {
            dpm.setMaximumTimeToLock(adminComponent, timeoutSeconds * 1000L)
            Log.i(TAG, "Screen inactivity timeout set to: $timeoutSeconds s")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set screen timeout", e)
        }
    }

    /**
     * Enterprise Remote Wipe: Erases device data to protect company assets if stolen.
     */
    fun wipeDeviceData() {
        if (!policyManager.isDeviceOwner()) return
        try {
            Log.w(TAG, "CRITICAL: Initiating remote factory wipe data!")
            dpm.wipeData(0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wipe device data", e)
        }
    }

    /**
     * Retrieves current GPS / Network geographic coordinates for fleet mapping.
     */
    @SuppressLint("MissingPermission")
    fun getDeviceLocation(): JSONObject? {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = lm.getProviders(true)
            var bestLoc: Location? = null

            for (p in providers) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (bestLoc == null || l.accuracy < bestLoc.accuracy) {
                    bestLoc = l
                }
            }

            bestLoc?.let {
                val loc = JSONObject()
                loc.put("latitude", it.latitude)
                loc.put("longitude", it.longitude)
                loc.put("accuracy", it.accuracy)
                loc.put("provider", it.provider)
                loc.put("timestamp", it.time)
                return loc
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve location", e)
        }
        return null
    }

    /**
     * Compiles a comprehensive inventory of all installed applications on the device.
     */
    fun getInstalledAppsInventory(): JSONArray {
        val array = JSONArray()
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)

            for (pkg in packages) {
                val obj = JSONObject()
                obj.put("packageName", pkg.packageName)
                obj.put("versionName", pkg.versionName ?: "N/A")
                val isSystem = (pkg.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                obj.put("isSystemApp", isSystem)
                obj.put("label", pkg.applicationInfo.loadLabel(pm).toString())
                array.put(obj)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching installed apps inventory", e)
        }
        return array
    }
}
