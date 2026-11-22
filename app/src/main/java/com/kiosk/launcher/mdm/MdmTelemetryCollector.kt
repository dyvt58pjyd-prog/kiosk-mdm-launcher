package com.kiosk.launcher.mdm

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.kiosk.launcher.data.KioskPreferences
import com.kiosk.launcher.dpc.KioskPolicyManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * Collects comprehensive device telemetry for MDM monitoring.
 */
class MdmTelemetryCollector(private val context: Context) {

    data class TelemetryData(
        val timestamp: Long,
        val deviceName: String,
        val manufacturer: String,
        val model: String,
        val androidVersion: String,
        val apiLevel: Int,
        val securityPatch: String,
        val uptimeSeconds: Long,
        // Battery
        val batteryLevel: Int,
        val isCharging: Boolean,
        val batteryHealth: String,
        val batteryTempCelsius: Float,
        val batteryVoltageMv: Int,
        // Network
        val wifiConnected: Boolean,
        val wifiSsid: String,
        val wifiRssi: Int,
        val ipAddress: String,
        // Memory & Storage
        val totalRamMb: Long,
        val availableRamMb: Long,
        val totalStorageGb: Float,
        val availableStorageGb: Float,
        // DPC & Kiosk
        val isDeviceOwner: Boolean,
        val whitelistedApps: List<String>
    )

    fun collect(): TelemetryData {
        val policyManager = KioskPolicyManager(context)
        val prefs = KioskPreferences(context)

        // Battery
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else 0
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val temp = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0f
        val voltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val healthCode = batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        val health = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            else -> "Normal"
        }

        // Memory
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val availRamMb = memInfo.availMem / (1024 * 1024)

        // Storage
        val stat = StatFs(Environment.getDataDirectory().path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong
        val totalStorageGb = (totalBlocks * blockSize) / (1024f * 1024f * 1024f)
        val availableStorageGb = (availableBlocks * blockSize) / (1024f * 1024f * 1024f)

        // Wi-Fi
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNet = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNet)
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val ssid = if (isWifi && wifiInfo != null) {
            val s = wifiInfo.ssid
            if (s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s
        } else "Disconnected"

        // IP Address
        val ipAddress = getLocalIpAddress()

        return TelemetryData(
            timestamp = System.currentTimeMillis(),
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else "N/A",
            uptimeSeconds = SystemClock.elapsedRealtime() / 1000,
            batteryLevel = batteryPct,
            isCharging = isCharging,
            batteryHealth = health,
            batteryTempCelsius = temp,
            batteryVoltageMv = voltage,
            wifiConnected = isWifi,
            wifiSsid = ssid,
            wifiRssi = wifiInfo?.rssi ?: -127,
            ipAddress = ipAddress,
            totalRamMb = totalRamMb,
            availableRamMb = availRamMb,
            totalStorageGb = totalStorageGb,
            availableStorageGb = availableStorageGb,
            isDeviceOwner = policyManager.isDeviceOwner(),
            whitelistedApps = prefs.whitelistedPackages.toList()
        )
    }

    fun toJson(data: TelemetryData): String {
        val root = JSONObject()
        root.put("timestamp", data.timestamp)
        root.put("uptime_seconds", data.uptimeSeconds)

        val device = JSONObject()
        device.put("device_name", data.deviceName)
        device.put("manufacturer", data.manufacturer)
        device.put("model", data.model)
        device.put("android_version", data.androidVersion)
        device.put("api_level", data.apiLevel)
        device.put("security_patch", data.securityPatch)
        device.put("is_device_owner", data.isDeviceOwner)
        root.put("device", device)

        val battery = JSONObject()
        battery.put("level_pct", data.batteryLevel)
        battery.put("is_charging", data.isCharging)
        battery.put("health", data.batteryHealth)
        battery.put("temperature_c", data.batteryTempCelsius)
        battery.put("voltage_mv", data.batteryVoltageMv)
        root.put("battery", battery)

        val network = JSONObject()
        network.put("wifi_connected", data.wifiConnected)
        network.put("wifi_ssid", data.wifiSsid)
        network.put("rssi_dbm", data.wifiRssi)
        network.put("ip_address", data.ipAddress)
        root.put("network", network)

        val memory = JSONObject()
        memory.put("total_ram_mb", data.totalRamMb)
        memory.put("available_ram_mb", data.availableRamMb)
        memory.put("used_ram_pct", ((data.totalRamMb - data.availableRamMb) * 100) / data.totalRamMb)
        root.put("memory", memory)

        val storage = JSONObject()
        storage.put("total_gb", String.format("%.2f", data.totalStorageGb))
        storage.put("available_gb", String.format("%.2f", data.availableStorageGb))
        root.put("storage", storage)

        val apps = JSONArray()
        data.whitelistedApps.forEach { apps.put(it) }
        root.put("whitelisted_apps", apps)

        return root.toString(2)
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addresses = intf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return "127.0.0.1"
    }
}
