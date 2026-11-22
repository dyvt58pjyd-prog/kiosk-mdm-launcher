const { Pool } = require('pg');
const path = require('path');
require('dotenv').config({ path: path.join(__dirname, '../.env') });

let pool = null;
let isNeonConnected = false;
let connectionError = null;

// Fallback in-memory store if Neon connection string is not provided yet
const memoryStore = {
  devices: new Map(),
  telemetry: [],
  commands: [],
  auditLogs: []
};

const DEFAULT_NEON_URL = 'postgresql://neondb_owner:npg_6NGxiCS0Ubjp@ep-quiet-lab-bdb0wmhf-pooler.c-8.us-east-2.aws.neon.tech/neondb?sslmode=require';

/**
 * Initializes or reconnects to the Neon PostgreSQL database.
 */
async function initDb(databaseUrl = process.env.DATABASE_URL || DEFAULT_NEON_URL) {
  if (pool) {
    try {
      await pool.end();
    } catch (e) {
      // Ignore
    }
    pool = null;
  }

  const targetUrl = (databaseUrl && databaseUrl.trim()) || DEFAULT_NEON_URL;

  try {
    console.log('[DB] Connecting to Neon PostgreSQL...');
    pool = new Pool({
      connectionString: targetUrl.trim(),
      ssl: {
        rejectUnauthorized: false
      },
      connectionTimeoutMillis: 10000
    });

    // Test connection
    const client = await pool.connect();
    const res = await client.query('SELECT NOW() as current_time, version()');
    client.release();

    isNeonConnected = true;
    connectionError = null;
    console.log('[DB] Successfully connected to Neon PostgreSQL! Time:', res.rows[0].current_time);

    // Run table migrations
    await runMigrations();
    return true;
  } catch (err) {
    isNeonConnected = false;
    connectionError = err.message;
    console.error('[DB] Failed to connect to Neon PostgreSQL:', err.message);
    return false;
  }
}

/**
 * Runs DDL migrations to ensure all required tables exist in Neon.
 */
async function runMigrations() {
  if (!pool) return;

  const ddl = `
    -- Tablets / Devices registered with the MDM
    CREATE TABLE IF NOT EXISTS devices (
      id VARCHAR(64) PRIMARY KEY,
      device_name VARCHAR(128) NOT NULL,
      manufacturer VARCHAR(64),
      model VARCHAR(64),
      android_version VARCHAR(32),
      api_level INT,
      security_patch VARCHAR(32),
      uptime_seconds BIGINT DEFAULT 0,
      ip_address VARCHAR(45),
      is_device_owner BOOLEAN DEFAULT FALSE,
      battery_level INT DEFAULT 0,
      is_charging BOOLEAN DEFAULT FALSE,
      battery_health VARCHAR(32),
      battery_temp_c NUMERIC(5,2),
      battery_voltage_mv INT,
      wifi_connected BOOLEAN DEFAULT FALSE,
      wifi_ssid VARCHAR(64),
      wifi_rssi INT,
      total_ram_mb BIGINT DEFAULT 0,
      available_ram_mb BIGINT DEFAULT 0,
      total_storage_gb NUMERIC(6,2) DEFAULT 0,
      available_storage_gb NUMERIC(6,2) DEFAULT 0,
      whitelisted_apps JSONB DEFAULT '[]'::jsonb,
      latitude NUMERIC(10, 7),
      longitude NUMERIC(10, 7),
      camera_disabled BOOLEAN DEFAULT FALSE,
      volume_level INT DEFAULT 100,
      brightness_level INT DEFAULT 70,
      installed_apps JSONB DEFAULT '[]'::jsonb,
      last_seen_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
      created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
      updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
    );

    -- Time-series telemetry logs for history graphs
    CREATE TABLE IF NOT EXISTS device_telemetry (
      id SERIAL PRIMARY KEY,
      device_id VARCHAR(64) REFERENCES devices(id) ON DELETE CASCADE,
      battery_level INT,
      is_charging BOOLEAN,
      battery_temp_c NUMERIC(5,2),
      ram_used_mb BIGINT,
      storage_used_gb NUMERIC(6,2),
      wifi_rssi INT,
      latitude NUMERIC(10, 7),
      longitude NUMERIC(10, 7),
      recorded_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
    );

    -- Remote command queue (LOCK, REBOOT, SET_WHITELIST, SIREN, MESSAGE, WIPE, etc.)
    CREATE TABLE IF NOT EXISTS device_commands (
      id SERIAL PRIMARY KEY,
      device_id VARCHAR(64) REFERENCES devices(id) ON DELETE CASCADE,
      command VARCHAR(32) NOT NULL,
      payload JSONB DEFAULT '{}'::jsonb,
      status VARCHAR(20) DEFAULT 'PENDING',
      created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
      executed_at TIMESTAMP WITH TIME ZONE
    );

    -- Audit trail for security & operational tracking
    CREATE TABLE IF NOT EXISTS audit_logs (
      id SERIAL PRIMARY KEY,
      device_id VARCHAR(64),
      event_type VARCHAR(64) NOT NULL,
      description TEXT NOT NULL,
      created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
    );

    -- Indexes
    CREATE INDEX IF NOT EXISTS idx_telemetry_device_time ON device_telemetry (device_id, recorded_at DESC);
    CREATE INDEX IF NOT EXISTS idx_commands_device_status ON device_commands (device_id, status);
    CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_logs (created_at DESC);
  `;

  try {
    await pool.query(ddl);
    console.log('[DB] Neon schema migrations applied successfully.');
  } catch (err) {
    console.error('[DB] Error applying Neon schema migrations:', err.message);
  }
}

function getStatus() {
  return {
    connected: isNeonConnected,
    mode: isNeonConnected ? 'neon_postgresql' : 'in_memory_fallback',
    error: connectionError
  };
}

// ----------------- Data Access Layer with Neon + In-Memory Fallback -----------------

async function upsertDevice(data) {
  const deviceId = data.deviceId || `${data.manufacturer}_${data.model}`.replace(/\s+/g, '_').toLowerCase();
  const lat = data.location?.latitude || null;
  const lon = data.location?.longitude || null;

  if (isNeonConnected && pool) {
    const query = `
      INSERT INTO devices (
        id, device_name, manufacturer, model, android_version, api_level,
        security_patch, uptime_seconds, ip_address, is_device_owner,
        battery_level, is_charging, battery_health, battery_temp_c, battery_voltage_mv,
        wifi_connected, wifi_ssid, wifi_rssi, total_ram_mb, available_ram_mb,
        total_storage_gb, available_storage_gb, whitelisted_apps, latitude, longitude,
        camera_disabled, last_seen_at, updated_at
      ) VALUES (
        $1, $2, $3, $4, $5, $6, $7, $8, $9, $10,
        $11, $12, $13, $14, $15, $16, $17, $18, $19, $20,
        $21, $22, $23, $24, $25, $26, NOW(), NOW()
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
        latitude = COALESCE(EXCLUDED.latitude, devices.latitude),
        longitude = COALESCE(EXCLUDED.longitude, devices.longitude),
        camera_disabled = COALESCE(EXCLUDED.camera_disabled, devices.camera_disabled),
        last_seen_at = NOW(),
        updated_at = NOW()
      RETURNING *;
    `;

    const values = [
      deviceId,
      data.deviceName || `${data.manufacturer} ${data.model}`,
      data.manufacturer || 'Generic',
      data.model || 'Tablet',
      data.androidVersion || 'Unknown',
      data.apiLevel || 0,
      data.securityPatch || 'N/A',
      data.uptimeSeconds || 0,
      data.ipAddress || '127.0.0.1',
      data.isDeviceOwner !== false,
      data.batteryLevel || 0,
      data.isCharging || false,
      data.batteryHealth || 'Good',
      data.batteryTempCelsius || 0,
      data.batteryVoltageMv || 0,
      data.wifiConnected !== false,
      data.wifiSsid || 'Disconnected',
      data.wifiRssi || -100,
      data.totalRamMb || 0,
      data.availableRamMb || 0,
      data.totalStorageGb || 0,
      data.availableStorageGb || 0,
      JSON.stringify(data.whitelistedApps || []),
      lat,
      lon,
      data.cameraDisabled || false
    ];

    const res = await pool.query(query, values);

    // Record time-series telemetry
    const ramUsed = (data.totalRamMb || 0) - (data.availableRamMb || 0);
    const storageUsed = (data.totalStorageGb || 0) - (data.availableStorageGb || 0);
    await pool.query(
      `INSERT INTO device_telemetry (device_id, battery_level, is_charging, battery_temp_c, ram_used_mb, storage_used_gb, wifi_rssi, latitude, longitude)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
      [deviceId, data.batteryLevel || 0, data.isCharging || false, data.batteryTempCelsius || 0, ramUsed, storageUsed, data.wifiRssi || -100, lat, lon]
    );

    return res.rows[0];
  } else {
    // Memory fallback
    const device = {
      id: deviceId,
      device_name: data.deviceName || `${data.manufacturer} ${data.model}`,
      manufacturer: data.manufacturer || 'Generic',
      model: data.model || 'Tablet',
      android_version: data.androidVersion || 'Unknown',
      api_level: data.apiLevel || 0,
      security_patch: data.securityPatch || 'N/A',
      uptime_seconds: data.uptimeSeconds || 0,
      ip_address: data.ipAddress || '127.0.0.1',
      is_device_owner: data.isDeviceOwner !== false,
      battery_level: data.batteryLevel || 0,
      is_charging: data.isCharging || false,
      battery_health: data.batteryHealth || 'Good',
      battery_temp_c: data.batteryTempCelsius || 0,
      battery_voltage_mv: data.batteryVoltageMv || 0,
      wifi_connected: data.wifiConnected !== false,
      wifi_ssid: data.wifiSsid || 'Disconnected',
      wifi_rssi: data.wifiRssi || -100,
      total_ram_mb: data.totalRamMb || 0,
      available_ram_mb: data.availableRamMb || 0,
      total_storage_gb: data.totalStorageGb || 0,
      available_storage_gb: data.availableStorageGb || 0,
      whitelisted_apps: data.whitelistedApps || [],
      latitude: lat || 37.7749,
      longitude: lon || -122.4194,
      camera_disabled: data.cameraDisabled || false,
      last_seen_at: new Date().toISOString()
    };
    memoryStore.devices.set(deviceId, device);

    memoryStore.telemetry.push({
      device_id: deviceId,
      battery_level: data.batteryLevel,
      ram_used_mb: (data.totalRamMb || 0) - (data.availableRamMb || 0),
      recorded_at: new Date().toISOString()
    });

    return device;
  }
}

async function getDevices() {
  if (isNeonConnected && pool) {
    const res = await pool.query(`
      SELECT *,
        CASE 
          WHEN last_seen_at > NOW() - INTERVAL '3 minutes' THEN 'ONLINE'
          ELSE 'OFFLINE'
        END as status
      FROM devices
      ORDER BY last_seen_at DESC;
    `);
    return res.rows;
  } else {
    const list = Array.from(memoryStore.devices.values());
    const now = Date.now();
    return list.map(d => {
      const diffMinutes = (now - new Date(d.last_seen_at).getTime()) / (1000 * 60);
      return {
        ...d,
        status: diffMinutes <= 3 ? 'ONLINE' : 'OFFLINE'
      };
    });
  }
}

async function getDeviceById(id) {
  if (isNeonConnected && pool) {
    const devRes = await pool.query('SELECT * FROM devices WHERE id = $1', [id]);
    if (devRes.rows.length === 0) return null;

    const device = devRes.rows[0];

    const telemRes = await pool.query(`
      SELECT * FROM device_telemetry
      WHERE device_id = $1
      ORDER BY recorded_at DESC
      LIMIT 30;
    `, [id]);

    const cmdRes = await pool.query(`
      SELECT * FROM device_commands
      WHERE device_id = $1
      ORDER BY created_at DESC
      LIMIT 10;
    `, [id]);

    return {
      ...device,
      telemetryHistory: telemRes.rows.reverse(),
      commands: cmdRes.rows
    };
  } else {
    const device = memoryStore.devices.get(id);
    if (!device) return null;

    const telem = memoryStore.telemetry.filter(t => t.device_id === id).slice(-30);
    const cmds = memoryStore.commands.filter(c => c.device_id === id).slice(-10);

    return {
      ...device,
      telemetryHistory: telem,
      commands: cmds
    };
  }
}

async function addCommand(deviceId, command, payload = {}) {
  if (isNeonConnected && pool) {
    const res = await pool.query(`
      INSERT INTO device_commands (device_id, command, payload, status)
      VALUES ($1, $2, $3, 'PENDING')
      RETURNING *;
    `, [deviceId, command, JSON.stringify(payload)]);

    await logAudit(deviceId, 'COMMAND_QUEUED', `Dispatched command ${command} (Payload: ${JSON.stringify(payload)})`);
    return res.rows[0];
  } else {
    const cmd = {
      id: memoryStore.commands.length + 1,
      device_id: deviceId,
      command,
      payload,
      status: 'PENDING',
      created_at: new Date().toISOString()
    };
    memoryStore.commands.push(cmd);
    await logAudit(deviceId, 'COMMAND_QUEUED', `Dispatched command ${command}`);
    return cmd;
  }
}

async function getPendingCommands(deviceId) {
  if (isNeonConnected && pool) {
    const res = await pool.query(`
      SELECT * FROM device_commands
      WHERE device_id = $1 AND status = 'PENDING'
      ORDER BY created_at ASC;
    `, [deviceId]);
    return res.rows;
  } else {
    return memoryStore.commands.filter(c => c.device_id === deviceId && c.status === 'PENDING');
  }
}

async function acknowledgeCommand(commandId, status = 'EXECUTED') {
  if (isNeonConnected && pool) {
    const res = await pool.query(`
      UPDATE device_commands
      SET status = $1, executed_at = NOW()
      WHERE id = $2
      RETURNING *;
    `, [status, commandId]);
    return res.rows[0];
  } else {
    const cmd = memoryStore.commands.find(c => c.id === parseInt(commandId));
    if (cmd) {
      cmd.status = status;
      cmd.executed_at = new Date().toISOString();
    }
    return cmd;
  }
}

async function updateDeviceWhitelist(deviceId, whitelist) {
  if (isNeonConnected && pool) {
    await pool.query(`
      UPDATE devices
      SET whitelisted_apps = $1, updated_at = NOW()
      WHERE id = $2;
    `, [JSON.stringify(whitelist), deviceId]);

    await addCommand(deviceId, 'SET_WHITELIST', { whitelist });
    await logAudit(deviceId, 'WHITELIST_UPDATED', `Updated whitelist: ${whitelist.join(', ')}`);
  } else {
    const dev = memoryStore.devices.get(deviceId);
    if (dev) {
      dev.whitelisted_apps = whitelist;
    }
    await addCommand(deviceId, 'SET_WHITELIST', { whitelist });
    await logAudit(deviceId, 'WHITELIST_UPDATED', `Updated whitelist: ${whitelist.join(', ')}`);
  }
}

async function logAudit(deviceId, eventType, description) {
  if (isNeonConnected && pool) {
    await pool.query(`
      INSERT INTO audit_logs (device_id, event_type, description)
      VALUES ($1, $2, $3);
    `, [deviceId, eventType, description]);
  } else {
    memoryStore.auditLogs.unshift({
      id: memoryStore.auditLogs.length + 1,
      device_id: deviceId,
      event_type: eventType,
      description,
      created_at: new Date().toISOString()
    });
    if (memoryStore.auditLogs.length > 100) memoryStore.auditLogs.pop();
  }
}

async function getAuditLogs(limit = 50) {
  if (isNeonConnected && pool) {
    const res = await pool.query(`
      SELECT * FROM audit_logs
      ORDER BY created_at DESC
      LIMIT $1;
    `, [limit]);
    return res.rows;
  } else {
    return memoryStore.auditLogs.slice(0, limit);
  }
}

module.exports = {
  initDb,
  getStatus,
  upsertDevice,
  getDevices,
  getDeviceById,
  addCommand,
  getPendingCommands,
  acknowledgeCommand,
  updateDeviceWhitelist,
  logAudit,
  getAuditLogs
};
