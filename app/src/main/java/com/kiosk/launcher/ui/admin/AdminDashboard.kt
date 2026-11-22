package com.kiosk.launcher.ui.admin

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiosk.launcher.data.InstalledAppInfo
import com.kiosk.launcher.data.KioskPreferences
import com.kiosk.launcher.dpc.KioskPolicyManager
import com.kiosk.launcher.mdm.MdmTelemetryCollector
import com.kiosk.launcher.mdm.NeonDirectDatabaseClient
import com.kiosk.launcher.ui.components.drawableToBitmap
import com.kiosk.launcher.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Administrative control center for Whitelist management, DPC policies,
 * MDM telemetry diagnostics, and maintenance mode exit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboard(
    allInstalledApps: List<InstalledAppInfo>,
    onWhitelistUpdated: (Set<String>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val prefs = remember { KioskPreferences(context) }
    val policyManager = remember { KioskPolicyManager(context) }
    val telemetryCollector = remember { MdmTelemetryCollector(context) }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Whitelisted Apps", "DPC Policies", "MDM Telemetry", "Maintenance & Exit")

    var isDeviceOwner by remember { mutableStateOf(policyManager.isDeviceOwner()) }
    var currentWhitelist by remember { mutableStateOf(prefs.whitelistedPackages) }

    // Dialog states
    var showDeactivateDialog by remember { mutableStateOf(false) }
    var showChangePinDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Kiosk MDM Administration",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (isDeviceOwner) "Status: Device Owner ACTIVE" else "Status: Unprovisioned (Standard Mode)",
                            fontSize = 11.sp,
                            color = if (isDeviceOwner) AccentSuccess else AccentWarning
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                    ) {
                        Text(text = "Apply & Return", color = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        },
        containerColor = BackgroundDark,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Tab Selector
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = SurfaceDark,
                contentColor = AccentPrimary,
                divider = { HorizontalDivider(color = BorderDark) }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                color = if (selectedTab == index) AccentPrimary else TextSecondary,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            // Tab Content
            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> WhitelistManagerTab(
                        allApps = allInstalledApps,
                        currentWhitelist = currentWhitelist,
                        onToggle = { pkg, isChecked ->
                            val updated = currentWhitelist.toMutableSet()
                            if (isChecked) updated.add(pkg) else updated.remove(pkg)
                            currentWhitelist = updated
                            prefs.whitelistedPackages = updated
                            policyManager.updateLockTaskPackages()
                            onWhitelistUpdated(updated)
                        }
                    )
                    1 -> DpcPoliciesTab(
                        isDeviceOwner = isDeviceOwner,
                        prefs = prefs,
                        onPolicyChanged = {
                            policyManager.applyUserRestrictions()
                        }
                    )
                    2 -> MdmTelemetryTab(
                        collector = telemetryCollector,
                        prefs = prefs
                    )
                    3 -> MaintenanceTab(
                        isDeviceOwner = isDeviceOwner,
                        onExitKiosk = {
                            activity?.let { act ->
                                policyManager.deactivateKioskLockdown(act)
                                Toast.makeText(context, "Kiosk Lockdown Exited. Stock launcher accessible.", Toast.LENGTH_LONG).show()
                                onClose()
                            }
                        },
                        onReboot = {
                            policyManager.rebootDevice()
                        },
                        onChangePin = { showChangePinDialog = true },
                        onDeactivateDeviceOwner = { showDeactivateDialog = true }
                    )
                }
            }
        }
    }

    // Confirmation dialog for deactivating Device Owner
    if (showDeactivateDialog) {
        AlertDialog(
            onDismissRequest = { showDeactivateDialog = false },
            title = { Text(text = "Deactivate Device Owner?", color = AccentDanger, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "This will permanently remove Device Owner privileges from this application. " +
                            "To regain Device Owner status, you will need to re-run the ADB command. " +
                            "This action is typically only performed before uninstalling the application.",
                    color = TextPrimary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        policyManager.deactivateDeviceOwner()
                        isDeviceOwner = false
                        showDeactivateDialog = false
                        Toast.makeText(context, "Device Owner Deactivated", Toast.LENGTH_LONG).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentDanger)
                ) {
                    Text("Confirm Deactivate", color = TextPrimary)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeactivateDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }

    // Dialog for changing Admin PIN
    if (showChangePinDialog) {
        var newPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var pinError by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showChangePinDialog = false },
            title = { Text("Change Admin PIN", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { newPin = it },
                        label = { Text("New PIN (min 4 digits)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPrimary,
                            unfocusedBorderColor = BorderDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { confirmPin = it },
                        label = { Text("Confirm New PIN") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPrimary,
                            unfocusedBorderColor = BorderDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true
                    )
                    if (pinError.isNotEmpty()) {
                        Text(text = pinError, color = AccentDanger, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPin.length < 4) {
                            pinError = "PIN must be at least 4 digits"
                        } else if (newPin != confirmPin) {
                            pinError = "PINs do not match"
                        } else {
                            prefs.adminPin = newPin
                            showChangePinDialog = false
                            Toast.makeText(context, "Admin PIN Updated Successfully", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                ) {
                    Text("Save PIN", color = TextPrimary)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showChangePinDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }
}

// ======================= TAB 1: WHITELIST MANAGER =======================
@Composable
private fun WhitelistManagerTab(
    allApps: List<InstalledAppInfo>,
    currentWhitelist: Set<String>,
    onToggle: (String, Boolean) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(searchQuery, allApps) {
        if (searchQuery.isBlank()) allApps
        else allApps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search installed applications...", color = TextSecondary) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentPrimary,
                unfocusedBorderColor = BorderDark,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "${currentWhitelist.size} apps approved for Kiosk display",
            fontSize = 13.sp,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredApps, key = { it.packageName }) { app ->
                val isChecked = currentWhitelist.contains(app.packageName)
                val bitmap = remember(app.packageName) {
                    app.icon?.drawableToBitmap()?.asImageBitmap()
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SurfaceDark),
                                contentAlignment = Alignment.Center
                            ) {
                                if (bitmap != null) {
                                    Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(36.dp))
                                } else {
                                    Icon(Icons.Default.Apps, contentDescription = null, tint = AccentPrimary)
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(text = app.label, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(text = app.packageName, color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }

                        Switch(
                            checked = isChecked,
                            onCheckedChange = { onToggle(app.packageName, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TextPrimary,
                                checkedTrackColor = AccentSuccess,
                                uncheckedTrackColor = BorderDark
                            )
                        )
                    }
                }
            }
        }
    }
}

// ======================= TAB 2: DPC POLICIES =======================
@Composable
private fun DpcPoliciesTab(
    isDeviceOwner: Boolean,
    prefs: KioskPreferences,
    onPolicyChanged: () -> Unit
) {
    val context = LocalContext.current
    var disallowSafeBoot by remember { mutableStateOf(prefs.disallowSafeBoot) }
    var disallowFactoryReset by remember { mutableStateOf(prefs.disallowFactoryReset) }
    var disallowAddUser by remember { mutableStateOf(prefs.disallowAddUser) }
    var disallowMountMedia by remember { mutableStateOf(prefs.disallowMountPhysicalMedia) }
    var disallowStatusBar by remember { mutableStateOf(prefs.disallowStatusBar) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!isDeviceOwner) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentWarning),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = AccentWarning)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Device Owner Provisioning Required", fontWeight = FontWeight.Bold, color = AccentWarning)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "To lock the system Home button, block notification pull-down, and disallow safe mode, register this app as Device Owner via ADB:",
                            fontSize = 13.sp,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        val adbCmd = "adb shell dpm set-device-owner com.kiosk.launcher/.receiver.KioskAdminReceiver"
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(SurfaceDark)
                                .padding(10.dp)
                        ) {
                            Text(text = adbCmd, color = AccentPrimaryHover, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("ADB Command", adbCmd))
                                Toast.makeText(context, "Command copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Copy ADB Command", color = TextPrimary)
                        }
                    }
                }
            }
        }

        item {
            Text("Hardware & OS Security Restrictions", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
        }

        item {
            PolicySwitchCard(
                title = "Disallow Safe Boot Mode",
                description = "Prevents users from bypassing kiosk mode by rebooting into Android Safe Mode.",
                checked = disallowSafeBoot,
                enabled = isDeviceOwner,
                onCheckedChange = {
                    disallowSafeBoot = it
                    prefs.disallowSafeBoot = it
                    onPolicyChanged()
                }
            )
        }

        item {
            PolicySwitchCard(
                title = "Disallow Factory Reset",
                description = "Blocks unauthorized factory resetting of the tablet via Settings.",
                checked = disallowFactoryReset,
                enabled = isDeviceOwner,
                onCheckedChange = {
                    disallowFactoryReset = it
                    prefs.disallowFactoryReset = it
                    onPolicyChanged()
                }
            )
        }

        item {
            PolicySwitchCard(
                title = "Disallow Adding Secondary Users",
                description = "Disables Guest user profiles or secondary user creation.",
                checked = disallowAddUser,
                enabled = isDeviceOwner,
                onCheckedChange = {
                    disallowAddUser = it
                    prefs.disallowAddUser = it
                    onPolicyChanged()
                }
            )
        }

        item {
            PolicySwitchCard(
                title = "Disallow Physical Media Mounting",
                description = "Prevents reading from external USB drives or SD cards.",
                checked = disallowMountMedia,
                enabled = isDeviceOwner,
                onCheckedChange = {
                    disallowMountMedia = it
                    prefs.disallowMountPhysicalMedia = it
                    onPolicyChanged()
                }
            )
        }

        item {
            PolicySwitchCard(
                title = "Disallow Status Bar Expansion",
                description = "Completely blocks pulling down the Android notification shade and quick tiles.",
                checked = disallowStatusBar,
                enabled = isDeviceOwner,
                onCheckedChange = {
                    disallowStatusBar = it
                    prefs.disallowStatusBar = it
                    onPolicyChanged()
                }
            )
        }
    }
}

@Composable
private fun PolicySwitchCard(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = description, color = TextSecondary, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TextPrimary,
                    checkedTrackColor = AccentSuccess,
                    uncheckedTrackColor = BorderDark
                )
            )
        }
    }
}

// ======================= TAB 3: MDM TELEMETRY =======================
@Composable
private fun MdmTelemetryTab(
    collector: MdmTelemetryCollector,
    prefs: KioskPreferences
) {
    val context = LocalContext.current
    val telemetry = remember { collector.collect() }
    var mdmUrl by remember { mutableStateOf(prefs.mdmServerUrl) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Local MDM Web Monitor", fontWeight = FontWeight.Bold, color = AccentPrimaryHover)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Access this tablet's real-time monitoring dashboard and remote command center from any PC on the same Wi-Fi network:",
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceDark)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "http://${telemetry.ipAddress}:8080/dashboard",
                            color = AccentSuccess,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        item {
            Text("Live Diagnostics Snapshot", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TelemetryRow("Device Model", "${telemetry.manufacturer} ${telemetry.model}")
                    TelemetryRow("Android Version", "Android ${telemetry.androidVersion} (API ${telemetry.apiLevel})")
                    TelemetryRow("Battery", "${telemetry.batteryLevel}% (${if (telemetry.isCharging) "Charging" else "On Battery"}) - ${telemetry.batteryTempCelsius}°C")
                    TelemetryRow("RAM Usage", "${telemetry.totalRamMb - telemetry.availableRamMb} MB / ${telemetry.totalRamMb} MB")
                    TelemetryRow("Storage", "${String.format("%.1f", telemetry.totalStorageGb - telemetry.availableStorageGb)} GB / ${String.format("%.1f", telemetry.totalStorageGb)} GB")
                    TelemetryRow("Wi-Fi SSID", telemetry.wifiSsid)
                    TelemetryRow("Wi-Fi RSSI", "${telemetry.wifiRssi} dBm")
                    TelemetryRow("Uptime", "${telemetry.uptimeSeconds / 3600} hours ${(telemetry.uptimeSeconds % 3600) / 60} mins")
                }
            }
        }

        item {
            Text("Central MDM Web Server (Live Control)", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
        }

        item {
            var mdmTestStatus by remember { mutableStateOf("") }
            val coroutineScope = rememberCoroutineScope()

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter your PC / Server MDM Dashboard URL (e.g., http://192.168.0.11:3000):",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = mdmUrl,
                        onValueChange = {
                            mdmUrl = it
                            prefs.mdmServerUrl = it
                        },
                        label = { Text("MDM Server URL") },
                        placeholder = { Text("http://192.168.0.11:3000") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPrimary,
                            unfocusedBorderColor = BorderDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                mdmTestStatus = "Connecting to MDM Server..."
                                coroutineScope.launch(Dispatchers.IO) {
                                    val ok = try {
                                        val base = if (mdmUrl.endsWith("/")) mdmUrl.dropLast(1) else mdmUrl
                                        val target = if (base.endsWith("/api/status") || base.endsWith("/api/mdm/heartbeat")) base else "$base/api/status"
                                        val conn = (java.net.URL(target).openConnection() as java.net.HttpURLConnection).apply {
                                            connectTimeout = 5000
                                            readTimeout = 5000
                                            requestMethod = "GET"
                                        }
                                        val code = conn.responseCode
                                        code in 200..299
                                    } catch (e: Exception) {
                                        false
                                    }
                                    withContext(Dispatchers.Main) {
                                        mdmTestStatus = if (ok) "✓ MDM Server Reached!" else "✕ Failed to connect. Check Wi-Fi & IP"
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                        ) {
                            Text("Test & Ping Server", color = TextPrimary, fontSize = 12.sp)
                        }
                        if (mdmTestStatus.isNotEmpty()) {
                            Text(
                                text = mdmTestStatus,
                                color = if (mdmTestStatus.startsWith("✓")) AccentSuccess else AccentDanger,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        item {
            Text("Neon Serverless PostgreSQL (Direct Tablet Sync)", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
        }

        item {
            var neonUrl by remember { mutableStateOf(prefs.neonDatabaseUrl) }
            var neonTestStatus by remember { mutableStateOf("") }
            val coroutineScope = rememberCoroutineScope()
            val neonClient = remember { NeonDirectDatabaseClient(context) }

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "The tablet APK writes live telemetry directly to Neon PostgreSQL and executes commands:",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = neonUrl,
                        onValueChange = {
                            neonUrl = it
                            prefs.neonDatabaseUrl = it
                        },
                        label = { Text("Neon Database Connection String") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPrimary,
                            unfocusedBorderColor = BorderDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                neonTestStatus = "Syncing tablet to Neon..."
                                coroutineScope.launch(Dispatchers.IO) {
                                    val androidId = android.provider.Settings.Secure.getString(
                                        context.contentResolver,
                                        android.provider.Settings.Secure.ANDROID_ID
                                    ) ?: "device_${android.os.Build.MODEL}"
                                    val ok = neonClient.syncTelemetryDirectly(telemetry, androidId)
                                    withContext(Dispatchers.Main) {
                                        neonTestStatus = if (ok) "✓ Synced! Tablet now visible on Web" else "✕ Direct Neon sync failed"
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentSuccess)
                        ) {
                            Text("Sync Tablet to Neon Now", color = TextPrimary, fontSize = 12.sp)
                        }
                        if (neonTestStatus.isNotEmpty()) {
                            Text(
                                text = neonTestStatus,
                                color = if (neonTestStatus.startsWith("✓")) AccentSuccess else AccentDanger,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = TextSecondary, fontSize = 13.sp)
        Text(text = value, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ======================= TAB 4: MAINTENANCE & EXIT =======================
@Composable
private fun MaintenanceTab(
    isDeviceOwner: Boolean,
    onExitKiosk: () -> Unit,
    onReboot: () -> Unit,
    onChangePin: () -> Unit,
    onDeactivateDeviceOwner: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Administrative Actions", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onChangePin,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
                    ) {
                        Icon(Icons.Default.Password, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Change Admin PIN", color = TextPrimary)
                    }

                    Button(
                        onClick = onExitKiosk,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentWarning)
                    ) {
                        Icon(Icons.Default.ExitToApp, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Exit Kiosk Mode (Maintenance Mode)", color = BackgroundDark, fontWeight = FontWeight.Bold)
                    }

                    if (isDeviceOwner) {
                        Button(
                            onClick = onReboot,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Reboot Tablet", color = TextPrimary)
                        }
                    }
                }
            }
        }

        if (isDeviceOwner) {
            item {
                Text("Danger Zone", fontWeight = FontWeight.Bold, color = AccentDanger, fontSize = 16.sp)
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentDanger),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Deactivate Device Owner", fontWeight = FontWeight.Bold, color = AccentDanger)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Relinquishes Android Device Owner status. Required if you wish to uninstall this app cleanly.",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onDeactivateDeviceOwner,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentDanger)
                        ) {
                            Text("Deactivate Device Owner", color = TextPrimary)
                        }
                    }
                }
            }
        }
    }
}
