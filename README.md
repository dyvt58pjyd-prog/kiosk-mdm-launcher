# Kiosk MDM Launcher, Central Admin Dashboard & Neon PostgreSQL
### Advanced Enterprise Fleet Controller, Real-Time WebSocket Remote Control & Neon Cloud Database

---

## 1. System Architecture & Capabilities

This solution provides a complete, production-grade **Mobile Device Management (MDM)** and **Multi-App Kiosk ecosystem** with **sub-second live remote control** and **Neon Serverless PostgreSQL** integration:

1. **Android Tablet APK (`/app`)**:
   - Built with **Kotlin & Jetpack Compose** for Android Tablets (Android 8.0+ / API 26+ to Android 14+ / API 34).
   - Functions as the on-device **Device Owner (DPC)** to enforce Lock Task mode, hide system navigation, block notification pull-downs, disable Safe Mode, and prevent unauthorized factory resets.
   - **Real-Time Live WebSocket Client (`MdmLiveSocketClient.kt`)**: Maintains an active socket to the central dashboard for **sub-50ms instant remote control**.
   - **Advanced Hardware & Policy Controller (`MdmAdvancedFeatures.kt`)**:
     - 🚨 **Emergency Siren / Find My Tablet**: High-pitched alarm sound at max volume overriding silent mode.
     - 💬 **Remote Broadcast Alerts**: Overlays admin pop-up notifications on the kiosk screen.
     - ☀️ **Remote Screen Brightness**: Overrides screen brightness remotely from the web console.
     - 🔊 **Remote Media Volume**: Overrides volume remotely.
     - 📷 **Hardware Camera Lock**: Disables or enables tablet cameras at the OS level (`dpm.setCameraDisabled`).
     - 🗺️ **GPS Geolocation Tracking**: Reports latitude, longitude, and accuracy to the fleet map.
     - ⚠️ **Enterprise Remote Wipe**: Emergency factory reset to protect corporate data if stolen.

2. **Central MDM Admin Dashboard & Server (`/server`)**:
   - Built with **Node.js, Express, WebSockets (`ws`), and Tailwind CSS**.
   - Connected directly to **Neon Serverless PostgreSQL** via standard `DATABASE_URL` with SSL.
   - **Interactive Leaflet.js Fleet GPS Map**: Visual real-time geographic tracking of all tablets.
   - **Live Telemetry & Diagnostics (Chart.js)**: Continuous battery discharge curves, RAM/storage graphs.
   - **Global Whitelist Manager**: Add/remove approved apps and push them across the entire fleet in one click.
   - **QR Code Quick Enrollment**: Displays a QR code that tablets can scan to automatically bind to the server.

```
+-----------------------------------------------------------------------------------------+
|                                    NEON POSTGRESQL                                      |
|  - Serverless PostgreSQL cloud database (neon.tech)                                     |
|  - Relational Tables: devices, device_telemetry, device_commands, audit_logs            |
+-----------------------------------------------------------------------------------------+
                                             ▲
                                             │ SSL (pg.Pool)
                                             ▼
+-----------------------------------------------------------------------------------------+
|                           CENTRAL MDM ADMIN DASHBOARD (Web UI)                          |
|  - URL: http://localhost:3000                                                           |
|  - Real-Time Live Push Engine (ws://localhost:3000/ws/device)                           |
|  - Interactive Leaflet GPS Fleet Map                                                    |
|  - Sub-Second Remote Control Bar: Lock, Siren, Broadcast Msg, Brightness, Camera       |
|  - Telemetry Trend Charts (Battery discharge curves, RAM utilization)                   |
|  - QR Code Enrollment Generator                                                         |
+-----------------------------------------------------------------------------------------+
                                             ▲
                                             │ Bi-Directional WebSocket & REST Heartbeat
                                             ▼
+-----------------------------------------------------------------------------------------+
|                         ANDROID TABLETS (DEVICE OWNER / DPC)                            |
|  - Multi-App Kiosk Home Launcher (Jetpack Compose)                                      |
|  - Lock Task Mode (API 28+ LOCK_TASK_FEATURE_HOME)                                      |
|  - MdmLiveSocketClient (Sub-50ms instant command listener)                             |
|  - MdmAdvancedFeatures (Siren, Camera lock, Brightness/Volume, GPS Location, Wipe)       |
|  - Staff Quick Settings & Secret 7-Tap Admin Escape Hatch (PIN: 1234)                   |
+-----------------------------------------------------------------------------------------+
```

---

## 2. Quick Start: Running the Central MDM Dashboard

```bash
cd /Users/anuragbawaskar/.gemini/antigravity/scratch/kiosk-mdm-launcher/server
npm start
```
Open **`http://localhost:3000`** in your browser.

---

## 3. Connecting to Neon PostgreSQL (`neon.tech`)

The dashboard features **zero-setup fallback**: it runs out-of-the-box in local memory mode and can be connected to Neon in two easy ways:

### Option A: From the Web Dashboard UI (Easiest)
1. Open `http://localhost:3000`.
2. Click the **"Connect Neon"** badge in the top-right header (or switch to the **Neon PostgreSQL** tab).
3. Paste your Neon connection string into the input box:
   ```
   postgresql://neondb_owner:npg_xxxx@ep-cool-fog-12345.us-east-2.aws.neon.tech/neondb?sslmode=require
   ```
4. Click **"Save & Connect to Neon"**.
5. The server automatically validates credentials, executes auto-migrations, and saves the connection string to `server/.env`.

---

## 4. Advanced Remote Control Features

| Feature | Action in Web Dashboard | Android Device Enforcement |
| :--- | :--- | :--- |
| **Instant Lock** | Click **🔒 Lock Screen** | Calls `dpm.lockNow()` via WebSocket in &lt; 50ms. |
| **Emergency Siren** | Click **🚨 Play Siren (10s)** | Plays loud alarm ringtone at maximum stream volume to locate tablet. |
| **Broadcast Pop-up** | Click **💬 Send Pop-up Msg** | Displays un-dismissible pop-up alert dialog on the kiosk screen. |
| **Remote Brightness** | Move **☀️ Brightness Slider** | Immediately updates window attributes and screen brightness. |
| **Remote Volume** | Move **🔊 Volume Slider** | Directly adjusts `AudioManager.STREAM_MUSIC`. |
| **Hardware Camera Lock** | Click **📷 Lock Camera** | Enforces OS-level hardware camera restriction (`dpm.setCameraDisabled`). |
| **Fleet GPS Tracking** | Switch to **🗺️ Fleet GPS Map** | Shows real-time OpenStreetMap pins based on tablet GPS coordinates. |
| **App Whitelist Push** | Click **🚀 Push Whitelist** | Updates `dpm.setLockTaskPackages()` across all tablets in real time. |
| **Remote Factory Wipe** | Click **⚠️ Remote Wipe** | Guards with confirmation phrase, then calls `dpm.wipeData(0)`. |

---

## 5. Provisioning the Tablet & Connecting It to the Dashboard

### Step 1: Provision Device Owner (DPC) on Tablet
Ensure all accounts are removed from the tablet (*Settings -> Accounts -> 0 accounts*), enable USB Debugging, and run via terminal:
```bash
# 1. Install tablet APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. Grant Device Owner status
adb shell dpm set-device-owner com.kiosk.launcher/.receiver.KioskAdminReceiver
```

### Step 2: Connect Tablet to the Dashboard
- **Method 1 (QR Code):** Click **"📱 Enroll Tablet (QR)"** in the dashboard header and scan the QR code from the tablet admin panel.
- **Method 2 (Manual):**
  1. On the tablet Kiosk screen, tap the **Clock 7 times rapidly** (or tap the Lock icon).
  2. Enter the Admin PIN: **`1234`**.
  3. Go to the **MDM Telemetry** tab.
  4. Under **Central MDM Server Sync**, enter your computer's IP address:
     ```
     http://<your-computer-ip>:3000
     ```
  5. Tap **Apply & Return**. The tablet immediately connects to the live WebSocket and streams its hardware stats to Neon!
