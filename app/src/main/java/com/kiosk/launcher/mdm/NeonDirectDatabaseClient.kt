package com.kiosk.launcher.mdm

import android.content.Context
import android.util.Log
import com.kiosk.launcher.data.KioskPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.util.Properties

/**
 * Direct Neon PostgreSQL database client running natively inside the Android tablet APK.
 * Connects directly to Neon Serverless PostgreSQL over SSL using standard JDBC,
 * allowing the tablet to sync telemetry, receive remote commands, and write audit logs
 * directly to Neon even without an intermediate web server.
 */
class NeonDirectDatabaseClient(private val context: Context) {

    private val prefs = KioskPreferences(context)

    companion object {
        private const val TAG = "NeonDirectDbClient"
        private var driverLoaded = false

        init {
            try {
                Class.forName("org.postgresql.Driver")
                driverLoaded = true
            } catch (e: Exception) {
                Log.w(TAG, "PostgreSQL JDBC driver not found on classpath", e)
            }
        }
    }

    /**
     * Parses the Neon postgresql:// URL and establishes a direct SSL connection.
     */
    private fun getConnection(): Connection? {
        val rawUrl = prefs.neonDatabaseUrl
        if (rawUrl.isBlank()) return null

        try {
            // Convert postgresql://user:pass@host/db?sslmode=require into JDBC format
            val cleanUrl = if (rawUrl.startsWith("jdbc:")) rawUrl.removePrefix("jdbc:") else rawUrl
            val uri = URI(cleanUrl)

            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 5432
            val path = uri.path ?: "/neondb"
            val dbName = path.removePrefix("/")

            val userInfo = uri.userInfo ?: ""
            val user = if (userInfo.contains(":")) userInfo.substringBefore(":") else userInfo
            val password = if (userInfo.contains(":")) userInfo.substringAfter(":") else ""

            val jdbcUrl = "jdbc:postgresql://$host:$port/$dbName?sslmode=require"

            val props = Properties().apply {
                setProperty("user", user)
                setProperty("password", password)
                setProperty("ssl", "true")
                setProperty("sslmode", "require")
                setProperty("connectTimeout", "8")
                setProperty("socketTimeout", "10")
            }

            return DriverManager.getConnection(jdbcUrl, props)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect directly to Neon PostgreSQL", e)
            return null
        }
    }

    /**
     * Verifies whether the tablet can reach the Neon database directly.
     */
    fun testConnection(): Boolean {
        return try {
            getConnection()?.use { conn ->
                val stmt = conn.createStatement()
                val rs = stmt.executeQuery("SELECT NOW() as current_time;")
                val hasRow = rs.next()
                Log.i(TAG, "Neon direct connectivity verified! Time: ${rs.getString("current_time")}")
                hasRow
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Neon direct connectivity test failed", e)
            false
        }
    }

    /**
     * Directly inserts or updates tablet status and appends time-series telemetry in Neon.
     */
    fun syncTelemetryDirectly(telemetry: MdmTelemetryCollector.TelemetryData, deviceId: String): Boolean {
        val conn = getConnection() ?: return false

        return try {
            conn.use { c ->
                c.autoCommit = false

                // 1. Upsert devices table
                val upsertSql = """
                    INSERT INTO devices (
                        id, device_name, manufacturer, model, android_version, api_level,
                        security_patch, uptime_seconds, ip_address, is_device_owner,
                        battery_level, is_charging, battery_health, battery_temp_c, battery_voltage_mv,
                        wifi_connected, wifi_ssid, wifi_rssi, total_ram_mb, available_ram_mb,
                        total_storage_gb, available_storage_gb, whitelisted_apps, last_seen_at, updated_at
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?::jsonb, NOW(), NOW()
                    )
                    ON CONFLICT (id) DO UPDATE SET
                        device_name = EXCLUDED.device_name,
                        uptime_seconds = EXCLUDED.uptime_seconds,
                        ip_address = EXCLUDED.ip_address,
                        is_device_owner = EXCLUDED.is_device_owner,
                        battery_level = EXCLUDED.battery_level,
                        is_charging = EXCLUDED.is_charging,
                        battery_health = EXCLUDED.battery_health,
                        battery_temp_c = EXCLUDED.battery_temp_c,
                        battery_voltage_mv = EXCLUDED.battery_voltage_mv,
                        wifi_connected = EXCLUDED.wifi_connected,
                        wifi_ssid = EXCLUDED.wifi_ssid,
                        wifi_rssi = EXCLUDED.wifi_rssi,
                        total_ram_mb = EXCLUDED.total_ram_mb,
                        available_ram_mb = EXCLUDED.available_ram_mb,
                        total_storage_gb = EXCLUDED.total_storage_gb,
                        available_storage_gb = EXCLUDED.available_storage_gb,
                        whitelisted_apps = EXCLUDED.whitelisted_apps,
                        last_seen_at = NOW(),
                        updated_at = NOW();
                """.trimIndent()

                val appsJson = JSONArray().apply {
                    telemetry.whitelistedApps.forEach { put(it) }
                }.toString()

                c.prepareStatement(upsertSql).use { ps ->
                    ps.setString(1, deviceId)
                    ps.setString(2, telemetry.deviceName)
                    ps.setString(3, telemetry.manufacturer)
                    ps.setString(4, telemetry.model)
                    ps.setString(5, telemetry.androidVersion)
                    ps.setInt(6, telemetry.apiLevel)
                    ps.setString(7, telemetry.securityPatch)
                    ps.setLong(8, telemetry.uptimeSeconds)
                    ps.setString(9, telemetry.ipAddress)
                    ps.setBoolean(10, telemetry.isDeviceOwner)
                    ps.setInt(11, telemetry.batteryLevel)
                    ps.setBoolean(12, telemetry.isCharging)
                    ps.setString(13, telemetry.batteryHealth)
                    ps.setDouble(14, telemetry.batteryTempCelsius.toDouble())
                    ps.setInt(15, telemetry.batteryVoltageMv)
                    ps.setBoolean(16, telemetry.wifiConnected)
                    ps.setString(17, telemetry.wifiSsid)
                    ps.setInt(18, telemetry.wifiRssi)
                    ps.setLong(19, telemetry.totalRamMb)
                    ps.setLong(20, telemetry.availableRamMb)
                    ps.setDouble(21, telemetry.totalStorageGb.toDouble())
                    ps.setDouble(22, telemetry.availableStorageGb.toDouble())
                    ps.setString(23, appsJson)
                    ps.executeUpdate()
                }

                // 2. Append time-series telemetry row
                val telemSql = """
                    INSERT INTO device_telemetry (
                        device_id, battery_level, is_charging, battery_temp_c,
                        ram_used_mb, storage_used_gb, wifi_rssi, recorded_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, NOW());
                """.trimIndent()

                val ramUsed = telemetry.totalRamMb - telemetry.availableRamMb
                val storageUsed = (telemetry.totalStorageGb - telemetry.availableStorageGb).toDouble()

                c.prepareStatement(telemSql).use { ps ->
                    ps.setString(1, deviceId)
                    ps.setInt(2, telemetry.batteryLevel)
                    ps.setBoolean(3, telemetry.isCharging)
                    ps.setDouble(4, telemetry.batteryTempCelsius.toDouble())
                    ps.setLong(5, ramUsed)
                    ps.setDouble(6, storageUsed)
                    ps.setInt(7, telemetry.wifiRssi)
                    ps.executeUpdate()
                }

                c.commit()
                Log.i(TAG, "Direct Neon telemetry sync succeeded for device $deviceId")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing telemetry directly to Neon", e)
            false
        }
    }

    /**
     * Directly queries pending commands queued by the Web Dashboard in Neon and executes them.
     */
    fun pollAndExecutePendingCommands(
        deviceId: String,
        executor: (command: String, payload: JSONObject) -> Boolean
    ): Int {
        val conn = getConnection() ?: return 0
        var executedCount = 0

        try {
            conn.use { c ->
                val query = """
                    SELECT id, command, payload 
                    FROM device_commands 
                    WHERE device_id = ? AND status = 'PENDING'
                    ORDER BY created_at ASC;
                """.trimIndent()

                val commands = mutableListOf<Triple<Int, String, JSONObject>>()

                c.prepareStatement(query).use { ps ->
                    ps.setString(1, deviceId)
                    val rs = ps.executeQuery()
                    while (rs.next()) {
                        val id = rs.getInt("id")
                        val cmd = rs.getString("command")
                        val payloadStr = rs.getString("payload") ?: "{}"
                        val payload = try { JSONObject(payloadStr) } catch (e: Exception) { JSONObject() }
                        commands.add(Triple(id, cmd, payload))
                    }
                }

                // Execute each command and mark EXECUTED in Neon
                val updateSql = "UPDATE device_commands SET status = ?, executed_at = NOW() WHERE id = ?"
                c.prepareStatement(updateSql).use { updatePs ->
                    for ((cmdId, cmdName, payload) in commands) {
                        Log.i(TAG, "Executing pending Neon command $cmdName (ID: $cmdId)")
                        val success = executor(cmdName, payload)
                        updatePs.setString(1, if (success) "EXECUTED" else "FAILED")
                        updatePs.setInt(2, cmdId)
                        updatePs.executeUpdate()
                        executedCount++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error polling pending commands from Neon", e)
        }
        return executedCount
    }

    /**
     * Directly inserts an audit event into Neon.
     */
    fun logAuditDirectly(deviceId: String, eventType: String, description: String) {
        try {
            getConnection()?.use { c ->
                val sql = "INSERT INTO audit_logs (device_id, event_type, description, created_at) VALUES (?, ?, ?, NOW())"
                c.prepareStatement(sql).use { ps ->
                    ps.setString(1, deviceId)
                    ps.setString(2, eventType)
                    ps.setString(3, description)
                    ps.executeUpdate()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write audit log directly to Neon", e)
        }
    }
}
