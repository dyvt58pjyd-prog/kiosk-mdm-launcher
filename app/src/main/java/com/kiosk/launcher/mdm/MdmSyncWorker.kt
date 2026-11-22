package com.kiosk.launcher.mdm

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kiosk.launcher.data.KioskPreferences
import com.kiosk.launcher.dpc.KioskPolicyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Background WorkManager worker that performs direct sync to Neon PostgreSQL
 * AND optionally to the Central MDM Dashboard server.
 */
class MdmSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "MdmSyncWorker"
    }

    @SuppressLint("HardwareIds")
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = KioskPreferences(applicationContext)
        val policyManager = KioskPolicyManager(applicationContext)
        val advancedFeatures = MdmAdvancedFeatures(applicationContext)
        val collector = MdmTelemetryCollector(applicationContext)
        val telemetryData = collector.collect()

        val androidId = Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown_device"

        // ----------------- 1. Direct Neon PostgreSQL Sync -----------------
        val neonClient = NeonDirectDatabaseClient(applicationContext)
        var neonSuccess = false

        if (prefs.neonDatabaseUrl.isNotBlank()) {
            try {
                neonSuccess = neonClient.syncTelemetryDirectly(telemetryData, androidId)
                Log.i(TAG, "Direct Neon sync executed: success=$neonSuccess")

                // Poll pending commands directly from Neon PostgreSQL
                neonClient.pollAndExecutePendingCommands(androidId) { cmdName, payload ->
                    Log.i(TAG, "Executing command directly from Neon: $cmdName")
                    when (cmdName.uppercase()) {
                        "LOCK" -> { policyManager.lockDeviceNow(); true }
                        "REBOOT" -> { policyManager.rebootDevice(); true }
                        "SIREN" -> {
                            advancedFeatures.playEmergencySiren(payload.optInt("duration", 10))
                            true
                        }
                        "SET_VOLUME" -> {
                            advancedFeatures.setVolumeLevel(payload.optInt("volume", 50))
                            true
                        }
                        "SET_CAMERA_DISABLED" -> {
                            advancedFeatures.setCameraDisabled(payload.optBoolean("disabled", false))
                            true
                        }
                        "SET_WHITELIST" -> {
                            if (payload.has("whitelist")) {
                                val list = payload.getJSONArray("whitelist")
                                val newWhitelist = mutableSetOf<String>()
                                for (j in 0 until list.length()) newWhitelist.add(list.getString(j))
                                prefs.whitelistedPackages = newWhitelist
                                policyManager.updateLockTaskPackages()
                            }
                            true
                        }
                        else -> true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Direct Neon sync error", e)
            }
        }

        // ----------------- 2. Central MDM Web Server Sync (Optional) -----------------
        val serverUrl = prefs.mdmServerUrl
        if (serverUrl.isNotBlank()) {
            try {
                val root = JSONObject()
                root.put("deviceId", androidId)
                root.put("deviceName", telemetryData.deviceName)
                root.put("manufacturer", telemetryData.manufacturer)
                root.put("model", telemetryData.model)
                root.put("androidVersion", telemetryData.androidVersion)
                root.put("apiLevel", telemetryData.apiLevel)
                root.put("securityPatch", telemetryData.securityPatch)
                root.put("uptimeSeconds", telemetryData.uptimeSeconds)
                root.put("ipAddress", telemetryData.ipAddress)
                root.put("isDeviceOwner", telemetryData.isDeviceOwner)
                root.put("batteryLevel", telemetryData.batteryLevel)
                root.put("isCharging", telemetryData.isCharging)
                root.put("batteryHealth", telemetryData.batteryHealth)
                root.put("batteryTempCelsius", telemetryData.batteryTempCelsius)
                root.put("batteryVoltageMv", telemetryData.batteryVoltageMv)
                root.put("wifiConnected", telemetryData.wifiConnected)
                root.put("wifiSsid", telemetryData.wifiSsid)
                root.put("wifiRssi", telemetryData.wifiRssi)
                root.put("totalRamMb", telemetryData.totalRamMb)
                root.put("availableRamMb", telemetryData.availableRamMb)
                root.put("totalStorageGb", telemetryData.totalStorageGb)
                root.put("availableStorageGb", telemetryData.availableStorageGb)

                val appsArray = JSONArray()
                telemetryData.whitelistedApps.forEach { appsArray.put(it) }
                root.put("whitelistedApps", appsArray)

                val baseUrl = if (serverUrl.endsWith("/")) serverUrl.dropLast(1) else serverUrl
                val heartbeatUrl = if (baseUrl.endsWith("/api/mdm/heartbeat")) baseUrl else "$baseUrl/api/mdm/heartbeat"

                val conn = (URL(heartbeatUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("User-Agent", "KioskMDM-TabletAgent/1.0")
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                }

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(root.toString())
                    writer.flush()
                }

                val responseCode = conn.responseCode
                Log.i(TAG, "Central MDM HTTP sync response: $responseCode")
            } catch (e: Exception) {
                Log.w(TAG, "HTTP sync failed: ${e.message}")
            }
        }

        Result.success()
    }
}
