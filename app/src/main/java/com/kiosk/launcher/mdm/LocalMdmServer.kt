package com.kiosk.launcher.mdm

import android.content.Context
import android.util.Log
import com.kiosk.launcher.dpc.KioskPolicyManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Lightweight embedded HTTP server providing an MDM dashboard and REST API.
 * Allows administrators on the local network (PC, laptop, phone) to view
 * real-time tablet telemetry and issue remote management commands.
 */
class LocalMdmServer(private val context: Context, private val port: Int = 8080) {

    private var server: HttpServer? = null
    private val telemetryCollector = MdmTelemetryCollector(context)
    private val policyManager = KioskPolicyManager(context)

    companion object {
        private const val TAG = "LocalMdmServer"
        @Volatile private var instance: LocalMdmServer? = null

        fun getInstance(context: Context, port: Int = 8080): LocalMdmServer {
            return instance ?: synchronized(this) {
                instance ?: LocalMdmServer(context.applicationContext, port).also { instance = it }
            }
        }
    }

    fun start() {
        if (server != null) return

        try {
            server = HttpServer.create(InetSocketAddress(port), 0).apply {
                executor = Executors.newFixedThreadPool(4)

                // Web Console Dashboard
                createContext("/", DashboardHandler())
                createContext("/dashboard", DashboardHandler())

                // Telemetry JSON API
                createContext("/api/telemetry", TelemetryApiHandler())

                // MDM Remote Command API
                createContext("/api/command", CommandApiHandler())

                start()
            }
            Log.i(TAG, "Local MDM Server started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Local MDM Server on port $port", e)
        }
    }

    fun stop() {
        try {
            server?.stop(0)
            server = null
            Log.i(TAG, "Local MDM Server stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MDM server", e)
        }
    }

    private inner class DashboardHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod.equals("GET", ignoreCase = true)) {
                val data = telemetryCollector.collect()
                val html = renderDashboardHtml(data)
                val bytes = html.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.sendResponseHeaders(405, -1)
            }
        }
    }

    private inner class TelemetryApiHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod.equals("GET", ignoreCase = true)) {
                val data = telemetryCollector.collect()
                val json = telemetryCollector.toJson(data)
                val bytes = json.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
                exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.sendResponseHeaders(405, -1)
            }
        }
    }

    private inner class CommandApiHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod.equals("POST", ignoreCase = true)) {
                val body = BufferedReader(InputStreamReader(exchange.requestBody, Charsets.UTF_8)).readText()
                val responseJson = JSONObject()
                try {
                    val req = JSONObject(body)
                    val cmd = req.optString("command", "")
                    Log.i(TAG, "Received MDM remote command: $cmd")

                    when (cmd.lowercase()) {
                        "lock" -> {
                            policyManager.lockDeviceNow()
                            responseJson.put("status", "success")
                            responseJson.put("message", "Tablet screen locked successfully")
                        }
                        "reboot" -> {
                            responseJson.put("status", "success")
                            responseJson.put("message", "Reboot initiated")
                            policyManager.rebootDevice()
                        }
                        "ping" -> {
                            responseJson.put("status", "success")
                            responseJson.put("message", "pong")
                        }
                        else -> {
                            responseJson.put("status", "error")
                            responseJson.put("message", "Unknown command: $cmd")
                        }
                    }
                } catch (e: Exception) {
                    responseJson.put("status", "error")
                    responseJson.put("message", e.localizedMessage ?: "Invalid JSON")
                }

                val bytes = responseJson.toString().toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
                exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.sendResponseHeaders(405, -1)
            }
        }
    }

    private fun renderDashboardHtml(d: MdmTelemetryCollector.TelemetryData): String {
        val ramUsedPct = if (d.totalRamMb > 0) ((d.totalRamMb - d.availableRamMb) * 100) / d.totalRamMb else 0
        val storageUsedPct = if (d.totalStorageGb > 0) {
            (((d.totalStorageGb - d.availableStorageGb) / d.totalStorageGb) * 100).toInt()
        } else 0

        val appItems = d.whitelistedApps.joinToString("") {
            """<li class="app-item"><span class="badge">Whitelisted</span> <code>$it</code></li>"""
        }

        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>MDM Live Tablet Monitor</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; background: #0D1117; color: #F0F6FC; padding: 24px; line-height: 1.5; }
        .header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #30363D; padding-bottom: 16px; margin-bottom: 24px; }
        .title { font-size: 22px; font-weight: 700; color: #58A6FF; display: flex; align-items: center; gap: 8px; }
        .status-badge { font-size: 12px; font-weight: 600; padding: 4px 10px; border-radius: 12px; background: ${if (d.isDeviceOwner) "#238636" else "#D29922"}; color: #fff; }
        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 16px; margin-bottom: 24px; }
        .card { background: #161B22; border: 1px solid #30363D; border-radius: 8px; padding: 18px; }
        .card-title { font-size: 13px; text-transform: uppercase; color: #8B949E; font-weight: 600; margin-bottom: 12px; letter-spacing: 0.5px; }
        .stat-value { font-size: 26px; font-weight: 700; color: #F0F6FC; }
        .stat-sub { font-size: 13px; color: #8B949E; margin-top: 4px; }
        .progress-bar { background: #21262D; border-radius: 4px; height: 8px; width: 100%; overflow: hidden; margin-top: 10px; }
        .progress-fill { height: 100%; background: #2F81F7; }
        .btn-group { display: flex; gap: 10px; margin-top: 12px; }
        .btn { background: #21262D; border: 1px solid #30363D; color: #C9D1D9; padding: 8px 16px; border-radius: 6px; cursor: pointer; font-weight: 600; font-size: 13px; transition: all 0.2s; }
        .btn:hover { background: #30363D; color: #FFF; }
        .btn-danger { background: #DA3633; border-color: #F85149; color: #FFF; }
        .btn-danger:hover { background: #B62324; }
        ul.app-list { list-style: none; display: flex; flex-direction: column; gap: 8px; }
        li.app-item { background: #21262D; padding: 8px 12px; border-radius: 6px; font-size: 13px; display: flex; align-items: center; justify-content: space-between; }
        .badge { background: #238636; font-size: 10px; padding: 2px 6px; border-radius: 4px; color: #FFF; }
    </style>
</head>
<body>
    <div class="header">
        <div class="title">
            <span>🛡️ MDM Tablet Monitor</span>
            <span class="status-badge">${if (d.isDeviceOwner) "Device Owner Active" else "Device Admin Only"}</span>
        </div>
        <div style="font-size: 13px; color: #8B949E;">Tablet IP: <b>${d.ipAddress}</b> | Port: <b>$port</b></div>
    </div>

    <div class="grid">
        <div class="card">
            <div class="card-title">Device Telemetry</div>
            <div class="stat-value">${d.deviceName}</div>
            <div class="stat-sub">Android ${d.androidVersion} (API ${d.apiLevel})</div>
            <div class="stat-sub">Security Patch: ${d.securityPatch}</div>
            <div class="stat-sub">Uptime: ${d.uptimeSeconds / 3600}h ${(d.uptimeSeconds % 3600) / 60}m</div>
        </div>

        <div class="card">
            <div class="card-title">Battery Status</div>
            <div class="stat-value">${d.batteryLevel}% ${if (d.isCharging) "⚡ Charging" else "🔋 Discharging"}</div>
            <div class="stat-sub">Health: ${d.batteryHealth} | Temp: ${d.batteryTempCelsius}°C</div>
            <div class="progress-bar"><div class="progress-fill" style="width: ${d.batteryLevel}%; background: ${if (d.batteryLevel < 20) "#DA3633" else "#238636"}"></div></div>
        </div>

        <div class="card">
            <div class="card-title">Network & Connectivity</div>
            <div class="stat-value">${if (d.wifiConnected) "📶 Connected" else "📵 No Wi-Fi"}</div>
            <div class="stat-sub">SSID: <b>${d.wifiSsid}</b></div>
            <div class="stat-sub">Signal RSSI: ${d.wifiRssi} dBm</div>
            <div class="stat-sub">Local IP: ${d.ipAddress}</div>
        </div>

        <div class="card">
            <div class="card-title">Memory (RAM)</div>
            <div class="stat-value">${d.totalRamMb - d.availableRamMb} MB <span style="font-size: 14px; color: #8B949E;">/ ${d.totalRamMb} MB</span></div>
            <div class="stat-sub">${ramUsedPct}% utilized</div>
            <div class="progress-bar"><div class="progress-fill" style="width: ${ramUsedPct}%;"></div></div>
        </div>

        <div class="card">
            <div class="card-title">Storage</div>
            <div class="stat-value">${String.format("%.1f", d.totalStorageGb - d.availableStorageGb)} GB <span style="font-size: 14px; color: #8B949E;">/ ${String.format("%.1f", d.totalStorageGb)} GB</span></div>
            <div class="stat-sub">${storageUsedPct}% occupied</div>
            <div class="progress-bar"><div class="progress-fill" style="width: ${storageUsedPct}%;"></div></div>
        </div>

        <div class="card">
            <div class="card-title">Remote MDM Commands</div>
            <div class="btn-group">
                <button class="btn" onclick="sendCommand('lock')">🔒 Lock Screen</button>
                <button class="btn btn-danger" onclick="sendCommand('reboot')">🔄 Reboot Tablet</button>
                <button class="btn" onclick="location.reload()">🔄 Refresh Stats</button>
            </div>
            <div id="cmd-status" style="font-size: 12px; color: #8B949E; margin-top: 10px;"></div>
        </div>
    </div>

    <div class="card">
        <div class="card-title">Whitelisted Kiosk Apps (${d.whitelistedApps.size})</div>
        <ul class="app-list">
            $appItems
        </ul>
    </div>

    <script>
        async function sendCommand(cmd) {
            const statusEl = document.getElementById('cmd-status');
            statusEl.textContent = 'Sending command ' + cmd + '...';
            try {
                const res = await fetch('/api/command', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ command: cmd })
                });
                const data = await res.json();
                statusEl.textContent = 'Result: ' + data.message;
            } catch (err) {
                statusEl.textContent = 'Error: ' + err.message;
            }
        }
        // Auto-refresh telemetry every 10 seconds
        setTimeout(() => location.reload(), 10000);
    </script>
</body>
</html>
        """.trimIndent()
    }
}
