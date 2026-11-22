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
 * Background WorkManager worker that periodically collects telemetry, POSTs
 * it to the Central MDM Dashboard (backed by Neon PostgreSQL), and executes
 * any pending remote commands (LOCK, REBOOT, SET_WHITELIST).
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
        val serverUrl = prefs.mdmServerUrl

        if (serverUrl.isBlank()) {
            // Local monitoring only (no central MDM URL configured yet)
            return@withContext Result.success()
        }

        try {
            val collector = MdmTelemetryCollector(applicationContext)
            val telemetryData = collector.collect()

            // Get device unique ID (Android ID)
            val androidId = Settings.Secure.getString(
                applicationContext.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "unknown_device"

            // Construct heartbeat payload
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

            // Normalize endpoint URL (ensures target is /api/mdm/heartbeat)
            val baseUrl = if (serverUrl.endsWith("/")) serverUrl.dropLast(1) else serverUrl
            val heartbeatUrl = if (baseUrl.endsWith("/api/mdm/heartbeat")) baseUrl else "$baseUrl/api/mdm/heartbeat"

            val url = URL(heartbeatUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
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
            Log.i(TAG, "MDM Telemetry sync response code: $responseCode")

            if (responseCode in 200..299) {
                val responseBody = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).readText()
                val resJson = JSONObject(responseBody)

                // Process any pending commands received from Central Admin Dashboard
                if (resJson.has("pendingCommands")) {
                    val commands = resJson.getJSONArray("pendingCommands")
                    val policyManager = KioskPolicyManager(applicationContext)

                    for (i in 0 until commands.length()) {
                        val cmdObj = commands.getJSONObject(i)
                        val cmdId = cmdObj.optInt("id", -1)
                        val commandName = cmdObj.optString("command", "").uppercase()
                        val payload = cmdObj.optJSONObject("payload")

                        Log.i(TAG, "Received remote MDM command: $commandName (ID: $cmdId)")

                        var executionStatus = "EXECUTED"
                        when (commandName) {
                            "LOCK" -> {
                                policyManager.lockDeviceNow()
                            }
                            "REBOOT" -> {
                                policyManager.rebootDevice()
                            }
                            "SET_WHITELIST" -> {
                                if (payload != null && payload.has("whitelist")) {
                                    val list = payload.getJSONArray("whitelist")
                                    val newWhitelist = mutableSetOf<String>()
                                    for (j in 0 until list.length()) {
                                        newWhitelist.add(list.getString(j))
                                    }
                                    prefs.whitelistedPackages = newWhitelist
                                    policyManager.updateLockTaskPackages()
                                }
                            }
                            "PING" -> {
                                // No-op, ping verified
                            }
                            else -> {
                                executionStatus = "UNSUPPORTED"
                            }
                        }

                        // Send command acknowledgment
                        if (cmdId > 0) {
                            sendAck(baseUrl, cmdId, executionStatus)
                        }
                    }
                }
                Result.success()
            } else {
                Log.w(TAG, "Server returned error: $responseCode")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload MDM telemetry", e)
            Result.retry()
        }
    }

    private fun sendAck(baseUrl: String, commandId: Int, status: String) {
        try {
            val ackUrl = "$baseUrl/api/mdm/command/ack"
            val conn = (URL(ackUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
            }
            val ackPayload = JSONObject().apply {
                put("commandId", commandId)
                put("status", status)
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(ackPayload.toString()) }
            conn.responseCode
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command ACK", e)
        }
    }
}
