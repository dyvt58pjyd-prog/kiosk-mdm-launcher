package com.kiosk.launcher.mdm

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.kiosk.launcher.data.KioskPreferences
import com.kiosk.launcher.dpc.KioskPolicyManager
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Real-time bidirectional WebSocket client connecting the Android Tablet APK
 * directly to the Central MDM Dashboard.
 * Enables sub-second instant remote command execution (Lock, Siren, Message, Reboot, Whitelist).
 */
class MdmLiveSocketClient(
    private val context: Context,
    private var activity: Activity? = null
) {

    private val prefs = KioskPreferences(context)
    private val policyManager = KioskPolicyManager(context)
    private val advancedFeatures = MdmAdvancedFeatures(context)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private val reconnectHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "MdmLiveSocket"
    }

    fun attachActivity(act: Activity) {
        this.activity = act
    }

    fun connect() {
        val serverUrl = prefs.mdmServerUrl
        if (serverUrl.isBlank()) return

        disconnect()

        val wsUrl = convertHttpToWsUrl(serverUrl)
        val androidId = getDeviceId()
        val finalUrl = "$wsUrl?deviceId=$androidId&deviceName=${android.os.Build.MANUFACTURER}+${android.os.Build.MODEL}"

        Log.i(TAG, "Connecting live MDM WebSocket: $finalUrl")

        val request = Request.Builder().url(finalUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                Log.i(TAG, "✓ Live MDM WebSocket connected! Instant remote control active.")
                // Send initial telemetry immediately upon connection
                sendTelemetryPayload()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.i(TAG, "Live command received: $text")
                handleIncomingCommand(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                Log.w(TAG, "Live MDM WebSocket failure: ${t.message}")
                scheduleReconnect()
            }
        })
    }

    fun disconnect() {
        try {
            reconnectHandler.removeCallbacksAndMessages(null)
            webSocket?.close(1000, "App paused or reconfiguring")
            webSocket = null
            isConnected = false
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun scheduleReconnect() {
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed({
            if (!isConnected && prefs.mdmServerUrl.isNotBlank()) {
                Log.i(TAG, "Attempting WebSocket reconnection...")
                connect()
            }
        }, 5000)
    }

    private fun handleIncomingCommand(jsonStr: String) {
        try {
            val obj = JSONObject(jsonStr)
            val command = obj.optString("command", "").uppercase()
            val cmdId = obj.optInt("id", -1)
            val payload = obj.optJSONObject("payload") ?: JSONObject()

            var status = "EXECUTED"

            when (command) {
                "LOCK" -> {
                    policyManager.lockDeviceNow()
                }
                "REBOOT" -> {
                    policyManager.rebootDevice()
                }
                "SIREN" -> {
                    val duration = payload.optInt("duration", 10)
                    advancedFeatures.playEmergencySiren(duration)
                }
                "STOP_SIREN" -> {
                    advancedFeatures.stopEmergencySiren()
                }
                "MESSAGE" -> {
                    val title = payload.optString("title", "Administrator Notification")
                    val msg = payload.optString("message", "")
                    advancedFeatures.showBroadcastMessage(title, msg, activity)
                }
                "SET_BRIGHTNESS" -> {
                    val b = payload.optDouble("brightness", 0.7).toFloat()
                    advancedFeatures.setScreenBrightness(b, activity)
                }
                "SET_VOLUME" -> {
                    val vol = payload.optInt("volume", 50)
                    advancedFeatures.setVolumeLevel(vol)
                }
                "SET_CAMERA_DISABLED" -> {
                    val disabled = payload.optBoolean("disabled", false)
                    advancedFeatures.setCameraDisabled(disabled)
                }
                "SET_SCREEN_TIMEOUT" -> {
                    val sec = payload.optLong("timeoutSeconds", 120)
                    advancedFeatures.setScreenTimeout(sec)
                }
                "WIPE_DATA" -> {
                    val confirm = payload.optString("confirmation", "")
                    if (confirm == "CONFIRM_ENTERPRISE_WIPE") {
                        advancedFeatures.wipeDeviceData()
                    } else {
                        status = "REJECTED_UNCONFIRMED"
                    }
                }
                "SET_WHITELIST" -> {
                    if (payload.has("whitelist")) {
                        val arr = payload.getJSONArray("whitelist")
                        val set = mutableSetOf<String>()
                        for (i in 0 until arr.length()) set.add(arr.getString(i))
                        prefs.whitelistedPackages = set
                        policyManager.updateLockTaskPackages()
                    }
                }
                "REQUEST_TELEMETRY" -> {
                    sendTelemetryPayload()
                }
                "PING" -> {
                    // Handled automatically
                }
                else -> {
                    status = "UNSUPPORTED"
                }
            }

            // Send instant ACK back through WebSocket
            if (cmdId > 0) {
                sendAck(cmdId, status)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing live WebSocket command", e)
        }
    }

    private fun sendAck(commandId: Int, status: String) {
        val ack = JSONObject()
        ack.put("event", "command_ack")
        ack.put("commandId", commandId)
        ack.put("status", status)
        webSocket?.send(ack.toString())
    }

    fun sendTelemetryPayload() {
        try {
            val collector = MdmTelemetryCollector(context)
            val data = collector.collect()
            val json = JSONObject()
            json.put("event", "telemetry")
            json.put("deviceId", getDeviceId())
            json.put("deviceName", data.deviceName)
            json.put("manufacturer", data.manufacturer)
            json.put("model", data.model)
            json.put("androidVersion", data.androidVersion)
            json.put("apiLevel", data.apiLevel)
            json.put("securityPatch", data.securityPatch)
            json.put("uptimeSeconds", data.uptimeSeconds)
            json.put("ipAddress", data.ipAddress)
            json.put("isDeviceOwner", data.isDeviceOwner)
            json.put("batteryLevel", data.batteryLevel)
            json.put("isCharging", data.isCharging)
            json.put("batteryHealth", data.batteryHealth)
            json.put("batteryTempCelsius", data.batteryTempCelsius)
            json.put("batteryVoltageMv", data.batteryVoltageMv)
            json.put("wifiConnected", data.wifiConnected)
            json.put("wifiSsid", data.wifiSsid)
            json.put("wifiRssi", data.wifiRssi)
            json.put("totalRamMb", data.totalRamMb)
            json.put("availableRamMb", data.availableRamMb)
            json.put("totalStorageGb", data.totalStorageGb)
            json.put("availableStorageGb", data.availableStorageGb)
            json.put("cameraDisabled", advancedFeatures.isCameraDisabled())

            // Add Location if available
            advancedFeatures.getDeviceLocation()?.let {
                json.put("location", it)
            }

            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending live telemetry", e)
        }
    }

    @SuppressLint("HardwareIds")
    private fun getDeviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "tablet_${android.os.Build.MODEL}".replace("\\s+".toRegex(), "_").lowercase()
    }

    private fun convertHttpToWsUrl(httpUrl: String): String {
        var base = httpUrl.trim()
        if (base.endsWith("/")) base = base.dropLast(1)
        base = if (base.startsWith("https://")) {
            base.replace("https://", "wss://")
        } else {
            base.replace("http://", "ws://")
        }
        return if (base.endsWith("/ws/device")) base else "$base/ws/device"
    }
}
