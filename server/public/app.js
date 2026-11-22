// Global Application State
let devices = [];
let selectedDeviceId = null;
let telemetryChart = null;
let fleetMap = null;
let mapMarkers = [];
let globalWhitelist = [
    'com.android.chrome',
    'com.google.android.apps.docs',
    'com.google.android.calculator',
    'com.android.settings'
];

// --------------------------- Navigation & Initialization ---------------------------

document.addEventListener('DOMContentLoaded', () => {
    checkNeonStatus();
    fetchDevices();
    fetchAuditLogs();
    renderWhitelistTable();

    // Auto refresh every 5 seconds for live device telemetry
    setInterval(fetchDevices, 5000);
    setInterval(checkNeonStatus, 15000);
});

function switchTab(tabName) {
    const tabs = ['fleet', 'map', 'whitelist', 'audit', 'neon'];
    tabs.forEach(t => {
        const btn = document.getElementById(`tab-${t}`);
        const view = document.getElementById(`view-${t}`);
        if (t === tabName) {
            btn.className = 'py-3 border-b-2 border-accentBlue text-white font-semibold';
            view.classList.remove('hidden');
        } else {
            btn.className = 'py-3 border-b-2 border-transparent text-gray-400 hover:text-gray-200';
            view.classList.add('hidden');
        }
    });

    if (tabName === 'map') {
        setTimeout(initOrUpdateMap, 200);
    } else if (tabName === 'audit') {
        fetchAuditLogs();
    }
}

function openNeonModal() {
    switchTab('neon');
}

// --------------------------- QR Code Quick Enrollment ---------------------------

async function openQrModal() {
    try {
        const res = await fetch('/api/enrollment/qr');
        const data = await res.json();
        document.getElementById('qr-image').src = data.qrDataUrl;
        document.getElementById('qr-server-url').textContent = data.serverUrl;
        document.getElementById('qr-modal').classList.remove('hidden');
    } catch (e) {
        alert('Could not generate QR: ' + e.message);
    }
}

function closeQrModal() {
    document.getElementById('qr-modal').classList.add('hidden');
}

// --------------------------- Neon Database Controls ---------------------------

async function checkNeonStatus() {
    try {
        const res = await fetch('/api/status');
        const data = await res.json();
        const neon = data.neon;

        const indicator = document.getElementById('neon-indicator');
        const statusText = document.getElementById('neon-status-text');
        const badgePill = document.getElementById('neon-badge-pill');
        const socketIndicator = document.getElementById('live-socket-count');

        if (socketIndicator) {
            socketIndicator.textContent = `${data.connectedTabletsCount || 0} Live Socket`;
        }

        if (neon.connected) {
            indicator.className = 'w-2.5 h-2.5 rounded-full bg-accentGreen';
            statusText.textContent = 'Neon Connected';
            statusText.className = 'text-accentGreen font-semibold';
            badgePill.textContent = 'Connected (Neon PostgreSQL)';
            badgePill.className = 'px-3 py-1 rounded-full text-xs font-semibold bg-accentGreen/20 text-accentGreen border border-accentGreen/40';
        } else {
            indicator.className = 'w-2.5 h-2.5 rounded-full bg-accentYellow pulsing-dot';
            statusText.textContent = 'Connect Neon';
            statusText.className = 'text-accentYellow';
            badgePill.textContent = 'In-Memory Mode (No Neon URL)';
            badgePill.className = 'px-3 py-1 rounded-full text-xs font-semibold bg-accentYellow/20 text-accentYellow border border-accentYellow/40';
        }
    } catch (e) {
        console.warn('Status check failed:', e);
    }
}

async function saveNeonConfig() {
    const input = document.getElementById('neon-input-url');
    const resultBox = document.getElementById('neon-result-box');
    const url = input.value.trim();

    if (!url) {
        alert('Please enter a valid Neon PostgreSQL connection string (postgresql://...)');
        return;
    }

    resultBox.classList.remove('hidden');
    resultBox.className = 'p-4 rounded-lg text-sm font-mono bg-darkElevated border border-darkBorder text-gray-300';
    resultBox.textContent = 'Connecting to Neon and applying schema migrations...';

    try {
        const res = await fetch('/api/neon/configure', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ databaseUrl: url })
        });
        const data = await res.json();

        if (res.ok) {
            resultBox.className = 'p-4 rounded-lg text-sm font-mono bg-accentGreen/20 border border-accentGreen text-accentGreen';
            resultBox.textContent = '✓ ' + data.message;
            checkNeonStatus();
            fetchDevices();
        } else {
            resultBox.className = 'p-4 rounded-lg text-sm font-mono bg-accentRed/20 border border-accentRed text-accentRed';
            resultBox.textContent = '✕ Connection Failed: ' + (data.error || 'Check credentials');
        }
    } catch (err) {
        resultBox.className = 'p-4 rounded-lg text-sm font-mono bg-accentRed/20 border border-accentRed text-accentRed';
        resultBox.textContent = '✕ Error: ' + err.message;
    }
}

// --------------------------- Fleet & Device Management ---------------------------

async function fetchDevices() {
    try {
        const res = await fetch('/api/devices');
        devices = await res.json();
        updateKpis(devices);
        renderDevices();
        if (fleetMap) updateMapMarkers();
    } catch (err) {
        console.warn('Failed to fetch devices:', err);
    }
}

function updateKpis(devs) {
    const total = devs.length;
    const online = devs.filter(d => d.status === 'ONLINE' || d.isLiveSocketActive).length;
    const lowBattery = devs.filter(d => d.battery_level < 20).length;
    const locked = devs.filter(d => d.is_device_owner).length;

    document.getElementById('stat-total').textContent = total;
    document.getElementById('stat-online').textContent = online;
    document.getElementById('stat-battery').textContent = lowBattery;
    document.getElementById('stat-locked').textContent = locked;
}

function renderDevices() {
    const container = document.getElementById('devices-container');
    const search = document.getElementById('device-search').value.toLowerCase();
    const filter = document.getElementById('status-filter').value;

    const filtered = devices.filter(d => {
        const matchesSearch = d.device_name.toLowerCase().includes(search) ||
            d.model.toLowerCase().includes(search) ||
            (d.ip_address && d.ip_address.includes(search));
        const matchesFilter = filter === 'ALL' || d.status === filter;
        return matchesSearch && matchesFilter;
    });

    if (filtered.length === 0) {
        container.innerHTML = `
            <div class="col-span-full py-16 text-center text-gray-500 bg-darkSurface border border-darkBorder rounded-xl">
                <div class="text-4xl mb-3">📵</div>
                <div class="text-base font-semibold text-gray-300">No Tablets Enrolled</div>
                <div class="text-xs text-gray-500 mt-1">Configure your tablet app's MDM Server URL, scan the QR code, or click "Seed Demo".</div>
            </div>
        `;
        return;
    }

    container.innerHTML = filtered.map(d => {
        const isOnline = d.status === 'ONLINE' || d.isLiveSocketActive;
        const batteryColor = d.battery_level < 20 ? 'text-accentRed' : 'text-accentGreen';
        const ramUsed = d.total_ram_mb > 0 ? Math.round(((d.total_ram_mb - d.available_ram_mb) / d.total_ram_mb) * 100) : 0;

        return `
            <div onclick="openDeviceModal('${d.id}')" class="bg-darkSurface border border-darkBorder hover:border-gray-500 rounded-xl p-5 cursor-pointer transition-all hover:shadow-xl">
                <div class="flex items-start justify-between">
                    <div>
                        <div class="flex items-center space-x-2">
                            <span class="w-2.5 h-2.5 rounded-full ${isOnline ? 'bg-accentGreen pulsing-dot' : 'bg-gray-600'}"></span>
                            <h3 class="font-bold text-white text-base">${escapeHtml(d.device_name)}</h3>
                        </div>
                        <div class="text-xs text-gray-400 font-mono mt-1">${escapeHtml(d.model)} • ${escapeHtml(d.ip_address || '127.0.0.1')}</div>
                    </div>
                    <div class="flex flex-col items-end space-y-1">
                        <span class="px-2 py-0.5 text-xs font-semibold rounded ${isOnline ? 'bg-accentGreen/20 text-accentGreen border border-accentGreen/40' : 'bg-darkElevated text-gray-400'}">
                            ${d.status}
                        </span>
                        ${d.isLiveSocketActive ? '<span class="text-[10px] text-accentPurple font-bold">⚡ Live Socket</span>' : ''}
                    </div>
                </div>

                <div class="grid grid-cols-2 gap-3 mt-5 pt-4 border-t border-darkBorder/60 text-xs">
                    <div>
                        <span class="text-gray-400">Battery:</span>
                        <span class="font-bold ml-1 ${batteryColor}">${d.battery_level}%</span>
                        ${d.is_charging ? '<span class="text-accentYellow">⚡</span>' : ''}
                    </div>
                    <div>
                        <span class="text-gray-400">Wi-Fi:</span>
                        <span class="text-white font-medium ml-1 truncate">${escapeHtml(d.wifi_ssid || 'Offline')}</span>
                    </div>
                    <div>
                        <span class="text-gray-400">RAM:</span>
                        <span class="text-white font-medium ml-1">${ramUsed}% used</span>
                    </div>
                    <div>
                        <span class="text-gray-400">Android:</span>
                        <span class="text-white font-medium ml-1">v${escapeHtml(d.android_version || '?')}</span>
                    </div>
                </div>

                <div class="mt-4 pt-3 border-t border-darkBorder flex items-center justify-between text-xs text-gray-400">
                    <span class="truncate">Apps: <b>${(d.whitelisted_apps || []).length}</b> whitelisted</span>
                    <span class="text-accentBlue hover:underline font-semibold">Remote Control ➔</span>
                </div>
            </div>
        `;
    }).join('');
}

// --------------------------- Device Detail Inspector Modal ---------------------------

async function openDeviceModal(deviceId) {
    selectedDeviceId = deviceId;
    const modal = document.getElementById('device-modal');
    modal.classList.remove('hidden');

    try {
        const res = await fetch(`/api/devices/${deviceId}`);
        const d = await res.json();

        document.getElementById('modal-device-name').textContent = d.device_name;
        document.getElementById('modal-device-sub').textContent = `${d.manufacturer} ${d.model} • IP: ${d.ip_address} • Android ${d.android_version} (API ${d.api_level})`;
        
        const badge = document.getElementById('modal-status-badge');
        const isOnline = d.status === 'ONLINE' || d.isLiveSocketActive;
        badge.textContent = isOnline ? 'ONLINE' : 'OFFLINE';
        badge.className = `px-2 py-0.5 text-xs font-semibold rounded ${isOnline ? 'bg-accentGreen text-white' : 'bg-gray-700 text-gray-300'}`;

        const socketBadge = document.getElementById('modal-socket-badge');
        if (d.isLiveSocketActive) {
            socketBadge.textContent = '⚡ Live WebSocket Active';
            socketBadge.className = 'px-2 py-0.5 text-xs font-semibold rounded bg-accentPurple text-white';
        } else {
            socketBadge.textContent = 'HTTP Heartbeat Mode';
            socketBadge.className = 'px-2 py-0.5 text-xs font-semibold rounded bg-darkElevated text-gray-400';
        }

        // Camera toggle label
        const camBtn = document.getElementById('btn-camera-toggle');
        if (d.camera_disabled) {
            camBtn.innerHTML = '<span>📷 Enable Camera</span>';
            camBtn.className = 'px-3.5 py-2 rounded-lg bg-accentYellow/20 border border-accentYellow text-xs font-semibold text-accentYellow flex items-center space-x-1.5 transition-all';
        } else {
            camBtn.innerHTML = '<span>📷 Lock Camera</span>';
            camBtn.className = 'px-3.5 py-2 rounded-lg bg-darkSurface border border-darkBorder hover:border-gray-400 text-xs font-semibold text-gray-200 flex items-center space-x-1.5 transition-all';
        }

        // Metrics
        document.getElementById('modal-battery-val').textContent = `${d.battery_level}%`;
        document.getElementById('modal-battery-sub').textContent = `${d.is_charging ? '⚡ Charging' : '🔋 Battery'} (${d.battery_temp_c || 28}°C)`;

        const ramUsed = (d.total_ram_mb - d.available_ram_mb) / 1024;
        const ramTotal = d.total_ram_mb / 1024;
        document.getElementById('modal-ram-val').textContent = `${ramUsed.toFixed(1)} GB`;
        document.getElementById('modal-ram-sub').textContent = `${Math.round((ramUsed / ramTotal) * 100)}% of ${ramTotal.toFixed(0)} GB`;

        const storageUsed = (d.total_storage_gb - d.available_storage_gb);
        document.getElementById('modal-storage-val').textContent = `${storageUsed.toFixed(1)} GB`;
        document.getElementById('modal-storage-sub').textContent = `${Math.round((storageUsed / d.total_storage_gb) * 100)}% of ${Number(d.total_storage_gb).toFixed(0)} GB`;

        document.getElementById('modal-wifi-val').textContent = `${d.wifi_rssi || -100} dBm`;
        document.getElementById('modal-wifi-sub').textContent = `${d.wifi_ssid || 'Disconnected'}`;

        // Whitelisted apps tags
        const appsContainer = document.getElementById('modal-whitelisted-apps');
        const apps = d.whitelisted_apps || [];
        if (apps.length === 0) {
            appsContainer.innerHTML = '<span class="text-xs text-gray-500">No apps whitelisted yet.</span>';
        } else {
            appsContainer.innerHTML = apps.map(pkg => `
                <span class="px-2.5 py-1 rounded bg-darkSurface border border-darkBorder text-xs font-mono text-gray-300 flex items-center space-x-1">
                    <span>${escapeHtml(pkg)}</span>
                </span>
            `).join('');
        }

        // Render Telemetry Chart
        renderTelemetryChart(d.telemetryHistory || []);
    } catch (err) {
        console.error('Error loading device modal:', err);
    }
}

function closeDeviceModal() {
    document.getElementById('device-modal').classList.add('hidden');
    selectedDeviceId = null;
}

function renderTelemetryChart(history) {
    const ctx = document.getElementById('telemetryChart').getContext('2d');
    if (telemetryChart) telemetryChart.destroy();

    const labels = history.map(h => new Date(h.recorded_at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }));
    const batteryData = history.map(h => h.battery_level);
    const ramData = history.map(h => Math.round(h.ram_used_mb / 1024 * 10) / 10);

    telemetryChart = new Chart(ctx, {
        type: 'line',
        data: {
            labels: labels.length > 0 ? labels : ['10:00', '10:05', '10:10', '10:15', '10:20'],
            datasets: [
                {
                    label: 'Battery Level (%)',
                    data: batteryData.length > 0 ? batteryData : [90, 88, 86, 85, 84],
                    borderColor: '#238636',
                    backgroundColor: 'rgba(35, 134, 54, 0.1)',
                    tension: 0.3,
                    fill: true
                },
                {
                    label: 'RAM Used (GB)',
                    data: ramData.length > 0 ? ramData : [3.2, 3.4, 3.5, 3.5, 3.6],
                    borderColor: '#2F81F7',
                    backgroundColor: 'rgba(47, 129, 247, 0.1)',
                    tension: 0.3,
                    fill: true
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            scales: {
                x: { grid: { color: '#21262D' }, ticks: { color: '#8B949E' } },
                y: { grid: { color: '#21262D' }, ticks: { color: '#8B949E' } }
            },
            plugins: {
                legend: { labels: { color: '#F0F6FC' } }
            }
        }
    });
}

// --------------------------- Advanced Remote Commands ---------------------------

async function sendRemoteCommand(cmd, payload = {}) {
    if (!selectedDeviceId) return;
    const feedback = document.getElementById('modal-cmd-feedback');
    feedback.textContent = `Dispatching ${cmd} to tablet...`;

    try {
        const res = await fetch(`/api/devices/${selectedDeviceId}/commands`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ command: cmd, payload })
        });
        const data = await res.json();
        const modeText = data.pushedInstant ? '⚡ Executed Instantly via WebSocket (<50ms)' : '✓ Queued for next tablet check-in';
        feedback.textContent = `${modeText} (${cmd})`;
        feedback.className = 'text-xs mt-3 text-accentGreen font-semibold';
    } catch (err) {
        feedback.textContent = `✕ Failed to send command: ${err.message}`;
        feedback.className = 'text-xs mt-3 text-accentRed font-semibold';
    }
}

function openMessageDialog() {
    const title = prompt('Enter Alert Title:', 'Administrator Notification');
    if (title === null) return;
    const message = prompt('Enter Pop-Up Message text for tablet screen:');
    if (!message) return;
    sendRemoteCommand('MESSAGE', { title, message });
}

function toggleCameraLock() {
    const dev = devices.find(d => d.id === selectedDeviceId);
    const newState = !(dev && dev.camera_disabled);
    sendRemoteCommand('SET_CAMERA_DISABLED', { disabled: newState });
    if (dev) dev.camera_disabled = newState;
    setTimeout(() => openDeviceModal(selectedDeviceId), 600);
}

function pushBrightness(val) {
    document.getElementById('lbl-remote-brightness').textContent = `${val}%`;
    sendRemoteCommand('SET_BRIGHTNESS', { brightness: parseFloat(val) / 100.0 });
}

function pushVolume(val) {
    document.getElementById('lbl-remote-volume').textContent = `${val}%`;
    sendRemoteCommand('SET_VOLUME', { volume: parseInt(val) });
}

function promptEnterpriseWipe() {
    const confirmText = prompt('⚠️ DANGER: To completely factory reset and wipe this tablet, type CONFIRM_ENTERPRISE_WIPE:');
    if (confirmText === 'CONFIRM_ENTERPRISE_WIPE') {
        sendRemoteCommand('WIPE_DATA', { confirmation: 'CONFIRM_ENTERPRISE_WIPE' });
        alert('Remote Wipe Command Dispatched.');
    } else if (confirmText !== null) {
        alert('Wipe cancelled: Confirmation phrase did not match.');
    }
}

// --------------------------- Interactive Fleet GPS Map ---------------------------

function initOrUpdateMap() {
    const mapEl = document.getElementById('fleet-map');
    if (!mapEl) return;

    if (!fleetMap) {
        fleetMap = L.map('fleet-map').setView([37.7749, -122.4194], 11);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution: '© OpenStreetMap'
        }).addTo(fleetMap);
    }

    updateMapMarkers();
}

function updateMapMarkers() {
    if (!fleetMap) return;

    // Clear old markers
    mapMarkers.forEach(m => fleetMap.removeLayer(m));
    mapMarkers = [];

    const bounds = [];

    devices.forEach(d => {
        const lat = parseFloat(d.latitude);
        const lon = parseFloat(d.longitude);

        if (!isNaN(lat) && !isNaN(lon) && lat !== 0 && lon !== 0) {
            const isOnline = d.status === 'ONLINE' || d.isLiveSocketActive;
            const marker = L.circleMarker([lat, lon], {
                radius: 10,
                color: isOnline ? '#238636' : '#8B949E',
                fillColor: isOnline ? '#2F81F7' : '#6E7681',
                fillOpacity: 0.9,
                weight: 2
            }).addTo(fleetMap);

            marker.bindPopup(`
                <div style="font-family: sans-serif; font-size: 13px;">
                    <b style="color: #2F81F7;">${escapeHtml(d.device_name)}</b><br/>
                    Status: <b>${d.status}</b><br/>
                    Battery: <b>${d.battery_level}%</b><br/>
                    Wi-Fi: <b>${escapeHtml(d.wifi_ssid || 'N/A')}</b><br/>
                    IP: <code>${escapeHtml(d.ip_address)}</code>
                </div>
            `);

            mapMarkers.push(marker);
            bounds.push([lat, lon]);
        }
    });

    if (bounds.length > 0) {
        fleetMap.fitBounds(bounds, { maxZoom: 14, padding: [40, 40] });
    }
}

// --------------------------- Global Whitelist Management ---------------------------

function renderWhitelistTable() {
    const tbody = document.getElementById('whitelist-table-body');
    tbody.innerHTML = globalWhitelist.map((pkg, idx) => `
        <tr class="hover:bg-darkElevated/50">
            <td class="px-4 py-3 font-mono font-medium text-white">${escapeHtml(pkg)}</td>
            <td class="px-4 py-3 text-xs text-gray-400">Lock Task Whitelisted</td>
            <td class="px-4 py-3 text-right">
                <button onclick="removeGlobalPackage(${idx})" class="text-xs text-accentRed hover:underline font-semibold">Remove</button>
            </td>
        </tr>
    `).join('');
}

function addGlobalPackage() {
    const input = document.getElementById('new-pkg-input');
    const val = input.value.trim();
    if (val && !globalWhitelist.includes(val)) {
        globalWhitelist.push(val);
        input.value = '';
        renderWhitelistTable();
    }
}

function removeGlobalPackage(index) {
    globalWhitelist.splice(index, 1);
    renderWhitelistTable();
}

async function pushWhitelistToAll() {
    if (devices.length === 0) {
        alert('No tablets currently enrolled.');
        return;
    }

    if (!confirm(`Push this whitelist (${globalWhitelist.length} apps) to all ${devices.length} tablet(s)?`)) return;

    for (const d of devices) {
        try {
            await fetch(`/api/devices/${d.id}/whitelist`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ whitelist: globalWhitelist })
            });
        } catch (e) {
            console.error('Push error for device', d.id, e);
        }
    }
    alert('✓ App whitelist push command dispatched instantly to all enrolled tablets!');
}

// --------------------------- Audit Logs ---------------------------

async function fetchAuditLogs() {
    try {
        const res = await fetch('/api/audit-logs?limit=50');
        const logs = await res.json();
        const tbody = document.getElementById('audit-table-body');

        if (logs.length === 0) {
            tbody.innerHTML = '<tr><td colspan="4" class="px-4 py-8 text-center text-gray-500">No audit events recorded yet.</td></tr>';
            return;
        }

        tbody.innerHTML = logs.map(l => `
            <tr class="hover:bg-darkElevated/50">
                <td class="px-4 py-3 text-gray-400">${new Date(l.created_at).toLocaleString()}</td>
                <td class="px-4 py-3 text-accentBlue font-bold">${escapeHtml(l.device_id || 'System')}</td>
                <td class="px-4 py-3"><span class="px-2 py-0.5 rounded bg-darkElevated border border-darkBorder text-gray-300">${escapeHtml(l.event_type)}</span></td>
                <td class="px-4 py-3 text-gray-300 font-sans text-xs">${escapeHtml(l.description)}</td>
            </tr>
        `).join('');
    } catch (e) {
        console.warn('Failed to fetch audit logs:', e);
    }
}

// --------------------------- Seed Demo Data ---------------------------

async function seedDemoData() {
    try {
        const res = await fetch('/api/mock/seed', { method: 'POST' });
        const data = await res.json();
        alert('✓ Seeded 2 demo tablets (Samsung Tab S8 & Xiaomi Pad 6) with GPS coordinates!');
        fetchDevices();
        fetchAuditLogs();
    } catch (e) {
        alert('Seed failed: ' + e.message);
    }
}

function escapeHtml(str) {
    if (!str) return '';
    return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
