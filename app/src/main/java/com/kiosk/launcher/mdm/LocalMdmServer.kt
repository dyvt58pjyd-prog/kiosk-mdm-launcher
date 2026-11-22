package com.kiosk.launcher.mdm

import android.content.Context
import android.util.Log
import com.kiosk.launcher.dpc.KioskPolicyManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Lightweight local HTTP server using standard java.net.ServerSocket.
 * Provides on-device telemetry inspection without external dependencies.
 */
class LocalMdmServer(private val context: Context, private val port: Int = 8080) {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val executor = Executors.newFixedThreadPool(2)
    private val telemetryCollector = MdmTelemetryCollector(context)

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
        if (isRunning) return
        isRunning = true

        executor.execute {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Local MDM Server listening on port $port")

                while (isRunning && serverSocket != null && !serverSocket!!.isClosed) {
                    val clientSocket = serverSocket!!.accept()
                    executor.execute { handleClient(clientSocket) }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Local MDM server socket error", e)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
            Log.i(TAG, "Local MDM Server stopped.")
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val line = reader.readLine() ?: return
                val parts = line.split(" ")
                val path = if (parts.size > 1) parts[1] else "/"

                val output: OutputStream = s.getOutputStream()

                if (path.startsWith("/api/telemetry")) {
                    val data = telemetryCollector.collect()
                    val json = telemetryCollector.toJson(data)
                    val bytes = json.toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json; charset=UTF-8\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Access-Control-Allow-Origin: *\r\n\r\n"
                    output.write(header.toByteArray(Charsets.UTF_8))
                    output.write(bytes)
                } else {
                    val data = telemetryCollector.collect()
                    val html = "<html><body style='font-family:sans-serif;background:#0D1117;color:#F0F6FC;padding:24px;'>" +
                            "<h2>🛡️ Kiosk MDM Tablet Status</h2>" +
                            "<p>Device: <b>${data.deviceName}</b></p>" +
                            "<p>Battery: <b>${data.batteryLevel}%</b> (${if (data.isCharging) "Charging" else "Discharging"})</p>" +
                            "<p>Wi-Fi: <b>${data.wifiSsid}</b> (RSSI: ${data.wifiRssi} dBm)</p>" +
                            "<p>IP: <code>${data.ipAddress}</code></p>" +
                            "<p>Uptime: ${data.uptimeSeconds / 3600} hours</p>" +
                            "<p>Device Owner: <b>${if (data.isDeviceOwner) "ACTIVE" else "NO"}</b></p>" +
                            "</body></html>"
                    val bytes = html.toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=UTF-8\r\n" +
                            "Content-Length: ${bytes.size}\r\n\r\n"
                    output.write(header.toByteArray(Charsets.UTF_8))
                    output.write(bytes)
                }
                output.flush()
            }
        } catch (e: Exception) {
            // Ignore client connection drops
        }
    }
}
