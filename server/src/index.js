const express = require('express');
const http = require('http');
const { WebSocketServer, WebSocket } = require('ws');
const cors = require('cors');
const fs = require('fs');
const path = require('path');
const QRCode = require('qrcode');
const db = require('./db');
require('dotenv').config({ path: path.join(__dirname, '../.env') });

const app = express();
const server = http.createServer(app);
const PORT = process.env.PORT || 3000;

// WebSocket Server for sub-second real-time tablet connection & instant push
const wss = new WebSocketServer({ noServer: true });

// Map of currently connected tablets (deviceId -> WebSocket)
const liveTabletSockets = new Map();

app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, '../public')));

// ---------------------- Status & Neon DB Configuration ----------------------

app.get('/api/status', (req, res) => {
  res.json({
    app: 'Kiosk MDM Central Dashboard',
    version: '2.0.0 (Live WebSocket + Neon)',
    timestamp: new Date().toISOString(),
    connectedTabletsCount: liveTabletSockets.size,
    neon: db.getStatus()
  });
});

app.post('/api/neon/configure', async (req, res) => {
  const { databaseUrl } = req.body;
  if (!databaseUrl || typeof databaseUrl !== 'string') {
    return res.status(400).json({ error: 'databaseUrl is required' });
  }

  try {
    const success = await db.initDb(databaseUrl.trim());

    // Update .env file on disk
    try {
      const envPath = path.join(__dirname, '../.env');
      let envContent = '';
      if (fs.existsSync(envPath)) {
        envContent = fs.readFileSync(envPath, 'utf8');
      }

      if (/DATABASE_URL=.*/.test(envContent)) {
        envContent = envContent.replace(/DATABASE_URL=.*/, `DATABASE_URL=${databaseUrl.trim()}`);
      } else {
        envContent += `\nDATABASE_URL=${databaseUrl.trim()}\n`;
      }
      fs.writeFileSync(envPath, envContent.trim() + '\n', 'utf8');
    } catch (e) {
      console.warn('Could not write to .env:', e.message);
    }

    if (success) {
      res.json({ status: 'connected', message: 'Successfully connected to Neon PostgreSQL and initialized tables!' });
    } else {
      res.status(500).json({ status: 'failed', error: db.getStatus().error });
    }
  } catch (err) {
    res.status(500).json({ status: 'error', error: err.message });
  }
});

// ---------------------- QR Code Quick Enrollment ----------------------

app.get('/api/enrollment/qr', async (req, res) => {
  try {
    const host = req.headers.host || `localhost:${PORT}`;
    const protocol = req.secure || req.headers['x-forwarded-proto'] === 'https' ? 'https' : 'http';
    const serverUrl = `${protocol}://${host}`;

    const enrollmentPayload = {
      mdmServerUrl: serverUrl,
      enrollmentKey: 'MDM_KIOSK_ENROLL_' + Date.now(),
      timestamp: Date.now()
    };

    const qrDataUrl = await QRCode.toDataURL(JSON.stringify(enrollmentPayload), {
      width: 320,
      margin: 2,
      color: { dark: '#0D1117', light: '#FFFFFF' }
    });

    res.json({
      serverUrl,
      payload: enrollmentPayload,
      qrDataUrl
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ---------------------- WebSocket Sub-Second Live Tablet Connection ----------------------

server.on('upgrade', (request, socket, head) => {
  const parsedUrl = new URL(request.url, `http://${request.headers.host}`);
  if (parsedUrl.pathname === '/ws/device') {
    wss.handleUpgrade(request, socket, head, (ws) => {
      wss.emit('connection', ws, request, parsedUrl);
    });
  } else {
    socket.destroy();
  }
});

wss.on('connection', (ws, request, parsedUrl) => {
  const deviceId = parsedUrl.searchParams.get('deviceId') || 'unknown';
  const deviceName = parsedUrl.searchParams.get('deviceName') || 'Android Tablet';

  liveTabletSockets.set(deviceId, ws);
  console.log(`[WS] Tablet connected live: ${deviceId} (${deviceName})`);

  db.logAudit(deviceId, 'SOCKET_CONNECTED', `Real-time WebSocket connection established for ${deviceName}`);

  ws.on('message', async (message) => {
    try {
      const data = JSON.parse(message.toString());
      if (data.event === 'telemetry') {
        await db.upsertDevice(data);
      } else if (data.event === 'command_ack') {
        await db.acknowledgeCommand(data.commandId, data.status || 'EXECUTED');
        console.log(`[WS] Command ${data.commandId} ACK received from ${deviceId}: ${data.status}`);
      }
    } catch (e) {
      console.error('[WS] Error processing message from tablet:', e);
    }
  });

  ws.on('close', () => {
    console.log(`[WS] Tablet disconnected: ${deviceId}`);
    liveTabletSockets.delete(deviceId);
    db.logAudit(deviceId, 'SOCKET_DISCONNECTED', 'Real-time WebSocket connection closed');
  });

  ws.on('error', (err) => {
    console.warn(`[WS] Tablet socket error (${deviceId}):`, err.message);
  });
});

// ---------------------- Tablet MDM Agent Endpoints (HTTP Fallback) ----------------------

app.post('/api/mdm/heartbeat', async (req, res) => {
  try {
    const data = req.body;
    if (!data) return res.status(400).json({ error: 'Missing telemetry payload' });

    const device = await db.upsertDevice(data);
    const deviceId = device.id;
    const pendingCommands = await db.getPendingCommands(deviceId);

    res.json({
      status: 'acknowledged',
      serverTime: new Date().toISOString(),
      pendingCommands: pendingCommands.map(c => ({
        id: c.id,
        command: c.command,
        payload: typeof c.payload === 'string' ? JSON.parse(c.payload) : c.payload
      }))
    });
  } catch (err) {
    console.error('Error handling heartbeat:', err);
    res.status(500).json({ error: err.message });
  }
});

app.post('/api/mdm/command/ack', async (req, res) => {
  try {
    const { commandId, status } = req.body;
    if (!commandId) return res.status(400).json({ error: 'commandId is required' });

    const updated = await db.acknowledgeCommand(commandId, status || 'EXECUTED');
    res.json({ status: 'success', command: updated });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ---------------------- Admin Dashboard API Endpoints ----------------------

app.get('/api/devices', async (req, res) => {
  try {
    const devices = await db.getDevices();
    // Add real-time liveSocket indicator
    const enriched = devices.map(d => ({
      ...d,
      isLiveSocketActive: liveTabletSockets.has(d.id)
    }));
    res.json(enriched);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.get('/api/devices/:id', async (req, res) => {
  try {
    const device = await db.getDeviceById(req.params.id);
    if (!device) return res.status(404).json({ error: 'Device not found' });

    device.isLiveSocketActive = liveTabletSockets.has(device.id);
    res.json(device);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

/**
 * Dispatch command to tablet:
 * 1. Inserts into Neon PostgreSQL device_commands table.
 * 2. If tablet has an active WebSocket, instantly pushes to device in < 50ms!
 */
app.post('/api/devices/:id/commands', async (req, res) => {
  try {
    const { command, payload } = req.body;
    if (!command) return res.status(400).json({ error: 'command is required' });

    const deviceId = req.params.id;
    const queued = await db.addCommand(deviceId, command.toUpperCase(), payload || {});

    // Real-Time Sub-Second Push via WebSocket
    const ws = liveTabletSockets.get(deviceId);
    let pushedInstant = false;

    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({
        id: queued.id,
        command: command.toUpperCase(),
        payload: payload || {}
      }));
      pushedInstant = true;
      console.log(`[PUSH] Instantly pushed command ${command} to tablet ${deviceId} via WebSocket`);
    }

    res.json({
      status: 'dispatched',
      pushedInstant,
      command: queued
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.put('/api/devices/:id/whitelist', async (req, res) => {
  try {
    const { whitelist } = req.body;
    if (!Array.isArray(whitelist)) return res.status(400).json({ error: 'whitelist must be an array' });

    await db.updateDeviceWhitelist(req.params.id, whitelist);

    // Instant WebSocket push if active
    const ws = liveTabletSockets.get(req.params.id);
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({
        command: 'SET_WHITELIST',
        payload: { whitelist }
      }));
    }

    res.json({ status: 'success', message: 'Whitelist updated & push command dispatched.' });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.get('/api/audit-logs', async (req, res) => {
  try {
    const logs = await db.getAuditLogs(parseInt(req.query.limit) || 50);
    res.json(logs);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ---------------------- Seed Mock Devices for Testing ----------------------

app.post('/api/mock/seed', async (req, res) => {
  try {
    const sampleTablet = {
      deviceId: 'samsung_galaxy_tab_s8',
      deviceName: 'Samsung Galaxy Tab S8 (Reception)',
      manufacturer: 'Samsung',
      model: 'SM-X700',
      androidVersion: '13',
      apiLevel: 33,
      securityPatch: '2024-02-01',
      uptimeSeconds: 142800,
      ipAddress: '192.168.1.142',
      isDeviceOwner: true,
      batteryLevel: 88,
      isCharging: true,
      batteryHealth: 'Good',
      batteryTempCelsius: 29.4,
      batteryVoltageMv: 4120,
      wifiConnected: true,
      wifiSsid: 'Enterprise-Guest-WiFi',
      wifiRssi: -52,
      totalRamMb: 8192,
      availableRamMb: 4620,
      totalStorageGb: 128.0,
      availableStorageGb: 84.5,
      whitelistedApps: [
        'com.android.chrome',
        'com.google.android.apps.docs',
        'com.google.android.calculator'
      ],
      location: { latitude: 37.7749, longitude: -122.4194 }
    };

    const sampleTablet2 = {
      deviceId: 'xiaomi_pad_6_floor1',
      deviceName: 'Xiaomi Pad 6 (Conference Room)',
      manufacturer: 'Xiaomi',
      model: '23043RP34G',
      androidVersion: '14',
      apiLevel: 34,
      securityPatch: '2024-03-01',
      uptimeSeconds: 78500,
      ipAddress: '192.168.1.189',
      isDeviceOwner: true,
      batteryLevel: 19,
      isCharging: false,
      batteryHealth: 'Good',
      batteryTempCelsius: 32.1,
      batteryVoltageMv: 3740,
      wifiConnected: true,
      wifiSsid: 'Enterprise-Guest-WiFi',
      wifiRssi: -68,
      totalRamMb: 6144,
      availableRamMb: 2400,
      totalStorageGb: 128.0,
      availableStorageGb: 92.1,
      whitelistedApps: [
        'com.android.chrome',
        'com.google.android.apps.docs'
      ],
      location: { latitude: 37.7833, longitude: -122.4167 }
    };

    await db.upsertDevice(sampleTablet);
    await db.upsertDevice(sampleTablet2);
    await db.logAudit('samsung_galaxy_tab_s8', 'DEVICE_ENROLLED', 'Device provisioned as Device Owner');
    await db.logAudit('xiaomi_pad_6_floor1', 'BATTERY_LOW', 'Tablet battery dropped below 20%');

    res.json({ status: 'success', message: 'Sample tablets seeded successfully' });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Start DB and Express Server with WebSocket Upgrade
db.initDb().then(() => {
  server.listen(PORT, '0.0.0.0', () => {
    console.log(`\n==================================================================`);
    console.log(`🛡️  MDM Admin Dashboard running on http://localhost:${PORT}`);
    console.log(`⚡ Live WebSocket & Neon PostgreSQL Remote Control Server Online`);
    console.log(`==================================================================\n`);
  });
});
