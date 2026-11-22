package com.kiosk.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.kiosk.launcher.data.InstalledAppInfo
import com.kiosk.launcher.data.KioskPreferences
import com.kiosk.launcher.dpc.KioskPolicyManager
import com.kiosk.launcher.mdm.LocalMdmServer
import com.kiosk.launcher.mdm.MdmLiveSocketClient
import com.kiosk.launcher.mdm.MdmSyncWorker
import com.kiosk.launcher.mdm.MdmTelemetryCollector
import com.kiosk.launcher.ui.admin.AdminDashboard
import com.kiosk.launcher.ui.components.AppGrid
import com.kiosk.launcher.ui.components.CustomStatusBar
import com.kiosk.launcher.ui.dialogs.AdminPinDialog
import com.kiosk.launcher.ui.dialogs.QuickSettingsDialog
import com.kiosk.launcher.ui.theme.BackgroundDark
import com.kiosk.launcher.ui.theme.KioskMDMTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Main persistent Kiosk Home Launcher Activity and MDM Host.
 */
class KioskLauncherActivity : ComponentActivity() {

    private lateinit var prefs: KioskPreferences
    private lateinit var policyManager: KioskPolicyManager
    private lateinit var mdmServer: LocalMdmServer
    private lateinit var telemetryCollector: MdmTelemetryCollector
    private lateinit var liveSocketClient: MdmLiveSocketClient

    // App lists
    private var allInstalledApps by mutableStateOf<List<InstalledAppInfo>>(emptyList())
    private var whitelistedApps by mutableStateOf<List<InstalledAppInfo>>(emptyList())

    // Telemetry state
    private var batteryLevel by mutableIntStateOf(100)
    private var isCharging by mutableStateOf(false)
    private var wifiConnected by mutableStateOf(false)
    private var wifiSsid by mutableStateOf("Offline")
    private var ipAddress by mutableStateOf("127.0.0.1")

    // Broadcast receiver for live battery updates
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    batteryLevel = (level * 100) / scale
                }
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = KioskPreferences(this)
        policyManager = KioskPolicyManager(this)
        mdmServer = LocalMdmServer.getInstance(this, prefs.localServerPort)
        telemetryCollector = MdmTelemetryCollector(this)
        liveSocketClient = MdmLiveSocketClient(this, this)

        // Connect real-time MDM WebSocket for sub-second remote control
        liveSocketClient.connect()

        // Start local MDM HTTP server if enabled
        if (prefs.localServerEnabled) {
            mdmServer.start()
        }

        // Trigger immediate background sync directly to Neon PostgreSQL & MDM Server
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val androidId = android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ) ?: "device_${android.os.Build.MODEL}"
                val neonClient = com.kiosk.launcher.mdm.NeonDirectDatabaseClient(applicationContext)
                val data = telemetryCollector.collect()
                neonClient.syncTelemetryDirectly(data, androidId)
            } catch (e: Exception) {
                android.util.Log.w("KioskLauncher", "Immediate Neon sync failed", e)
            }
        }

        // Schedule immediate and periodic central MDM sync
        scheduleMdmSyncWorker()

        // Load installed packages asynchronously
        loadInstalledApps()

        // Register battery receiver
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // Set Compose Content
        setContent {
            KioskMDMTheme {
                // Completely intercept Back button so users cannot back out of Kiosk
                BackHandler(enabled = true) {
                    // Intentionally consume back press
                }

                var showPinDialog by remember { mutableStateOf(false) }
                var showAdminDashboard by remember { mutableStateOf(false) }
                var showQuickSettings by remember { mutableStateOf(false) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BackgroundDark)
                ) {
                    if (showAdminDashboard) {
                        AdminDashboard(
                            allInstalledApps = allInstalledApps,
                            onWhitelistUpdated = {
                                filterWhitelistedApps()
                            },
                            onClose = {
                                showAdminDashboard = false
                                if (prefs.isKioskModeEnabled) {
                                    policyManager.activateKioskLockdown(this@KioskLauncherActivity)
                                }
                            }
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // High-Contrast Custom Status Bar
                            CustomStatusBar(
                                batteryLevel = batteryLevel,
                                isCharging = isCharging,
                                wifiConnected = wifiConnected,
                                wifiSsid = wifiSsid,
                                ipAddress = ipAddress,
                                onQuickSettingsClick = { showQuickSettings = true },
                                onAdminUnlocked = { showPinDialog = true }
                            )

                            // Tablet Kiosk App Grid
                            AppGrid(
                                apps = whitelistedApps,
                                onAppClick = { packageName ->
                                    policyManager.launchWhitelistedApp(this@KioskLauncherActivity, packageName)
                                },
                                onOpenAdmin = { showPinDialog = true },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Admin PIN Dialog
                    if (showPinDialog) {
                        AdminPinDialog(
                            correctPin = prefs.adminPin,
                            onSuccess = {
                                showPinDialog = false
                                showAdminDashboard = true
                            },
                            onDismiss = { showPinDialog = false }
                        )
                    }

                    // Quick Settings (Wi-Fi & Brightness) Dialog
                    if (showQuickSettings) {
                        QuickSettingsDialog(
                            wifiConnected = wifiConnected,
                            wifiSsid = wifiSsid,
                            onDismiss = {
                                showQuickSettings = false
                                refreshNetworkStats()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-enforce immersive fullscreen
        policyManager.applyImmersiveFullscreen(window)

        // Ensure live WebSocket is active
        liveSocketClient.attachActivity(this)
        liveSocketClient.connect()

        // Refresh network telemetry
        refreshNetworkStats()

        // Re-enforce Kiosk Lock Task Mode if enabled
        if (prefs.isKioskModeEnabled && policyManager.isDeviceOwner()) {
            policyManager.activateKioskLockdown(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        liveSocketClient.disconnect()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }

    private fun refreshNetworkStats() {
        val t = telemetryCollector.collect()
        wifiConnected = t.wifiConnected
        wifiSsid = t.wifiSsid
        ipAddress = t.ipAddress
    }

    private fun loadInstalledApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            val appList = mutableListOf<InstalledAppInfo>()

            for (info in resolveInfos) {
                val pkgName = info.activityInfo.packageName
                // Skip our own launcher from being displayed as an app icon in the grid
                if (pkgName == packageName) continue

                val label = info.loadLabel(pm).toString()
                val icon = info.loadIcon(pm)
                val isSystem = (info.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                appList.add(
                    InstalledAppInfo(
                        label = label,
                        packageName = pkgName,
                        icon = icon,
                        isWhitelisted = prefs.isPackageWhitelisted(pkgName),
                        isSystemApp = isSystem
                    )
                )
            }

            appList.sortBy { it.label.lowercase() }

            withContext(Dispatchers.Main) {
                allInstalledApps = appList
                filterWhitelistedApps()
            }
        }
    }

    private fun filterWhitelistedApps() {
        val currentApproved = prefs.whitelistedPackages
        whitelistedApps = allInstalledApps.filter { currentApproved.contains(it.packageName) }
    }

    private fun scheduleMdmSyncWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Immediate one-time sync so the tablet appears on MDM server immediately
        val oneTimeRequest = OneTimeWorkRequestBuilder<MdmSyncWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(applicationContext).enqueue(oneTimeRequest)

        // Recurring periodic sync
        val syncRequest = PeriodicWorkRequestBuilder<MdmSyncWorker>(
            prefs.mdmReportingIntervalMinutes.toLong().coerceAtLeast(15),
            TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "MdmTelemetryPeriodicSync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }
}
