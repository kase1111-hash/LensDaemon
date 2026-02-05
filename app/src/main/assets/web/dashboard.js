/**
 * LensDaemon Dashboard JavaScript
 */

// API base URL
const API_BASE = '';

// State
let isStreaming = false;
let isRtspRunning = false;
let isPreviewActive = false;
let statusInterval = null;

// DOM Elements
const elements = {
    connectionStatus: document.getElementById('connection-status'),
    previewImage: document.getElementById('mjpeg-preview'),
    previewOverlay: document.getElementById('preview-overlay'),
    btnPreview: document.getElementById('btn-preview'),
    btnSnapshot: document.getElementById('btn-snapshot'),
    btnStreamStart: document.getElementById('btn-stream-start'),
    btnStreamStop: document.getElementById('btn-stream-stop'),
    btnRtspStart: document.getElementById('btn-rtsp-start'),
    btnRtspStop: document.getElementById('btn-rtsp-stop'),
    streamStatus: document.getElementById('stream-status'),
    rtspUrl: document.getElementById('rtsp-url'),
    rtspClients: document.getElementById('rtsp-clients'),
    lensButtons: document.querySelectorAll('.btn-lens'),
    zoomSlider: document.getElementById('zoom-slider'),
    zoomValue: document.getElementById('zoom-value'),
    exposureSlider: document.getElementById('exposure-slider'),
    exposureValue: document.getElementById('exposure-value'),
    resolution: document.getElementById('resolution'),
    bitrate: document.getElementById('bitrate'),
    framerate: document.getElementById('framerate'),
    codec: document.getElementById('codec'),
    statFrames: document.getElementById('stat-frames'),
    statFps: document.getElementById('stat-fps'),
    statBitrate: document.getElementById('stat-bitrate'),
    statEncoder: document.getElementById('stat-encoder'),
    deviceInfo: document.getElementById('device-info')
};

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    setupEventListeners();
    fetchDeviceInfo();
    startStatusPolling();
});

// Setup event listeners
function setupEventListeners() {
    // Preview
    elements.btnPreview.addEventListener('click', togglePreview);
    elements.previewOverlay.addEventListener('click', togglePreview);
    elements.btnSnapshot.addEventListener('click', captureSnapshot);

    // Stream control
    elements.btnStreamStart.addEventListener('click', startStream);
    elements.btnStreamStop.addEventListener('click', stopStream);

    // RTSP control
    elements.btnRtspStart.addEventListener('click', startRtsp);
    elements.btnRtspStop.addEventListener('click', stopRtsp);

    // Lens selection
    elements.lensButtons.forEach(btn => {
        btn.addEventListener('click', () => switchLens(btn.dataset.lens));
    });

    // Zoom control
    elements.zoomSlider.addEventListener('input', (e) => {
        const zoom = parseFloat(e.target.value);
        elements.zoomValue.textContent = `${zoom.toFixed(1)}x`;
    });
    elements.zoomSlider.addEventListener('change', (e) => {
        setZoom(parseFloat(e.target.value));
    });

    // Exposure control
    elements.exposureSlider.addEventListener('input', (e) => {
        const ev = parseInt(e.target.value);
        elements.exposureValue.textContent = `${ev} EV`;
    });
    elements.exposureSlider.addEventListener('change', (e) => {
        setExposure(parseInt(e.target.value));
    });
}

// API calls
async function apiCall(endpoint, method = 'GET', body = null) {
    try {
        const options = {
            method,
            headers: {
                'Content-Type': 'application/json'
            }
        };

        if (body) {
            options.body = JSON.stringify(body);
        }

        const response = await fetch(`${API_BASE}${endpoint}`, options);
        return await response.json();
    } catch (error) {
        console.error('API call failed:', error);
        return null;
    }
}

// Fetch device info
async function fetchDeviceInfo() {
    const info = await apiCall('/api/device');
    if (info) {
        elements.deviceInfo.textContent = `${info.manufacturer} ${info.model} | Android ${info.androidVersion}`;
    }
}

// Status polling
function startStatusPolling() {
    fetchStatus();
    statusInterval = setInterval(fetchStatus, 2000);
}

async function fetchStatus() {
    const status = await apiCall('/api/status');

    if (status && status.status === 'ok') {
        // Connected
        elements.connectionStatus.textContent = 'Connected';
        elements.connectionStatus.className = 'status-indicator connected';

        // Camera status
        updateStreamStatus(status.camera?.streamingActive || false);

        // Encoder stats
        if (status.encoder) {
            elements.statFrames.textContent = formatNumber(status.encoder.framesEncoded || 0);
            elements.statFps.textContent = (status.encoder.currentFps || 0).toFixed(1);
            elements.statBitrate.textContent = formatBitrate(status.encoder.currentBitrate || 0);
            elements.statEncoder.textContent = status.encoder.state || 'IDLE';
        }

        // RTSP status
        updateRtspStatus(status.rtsp);

        // Zoom
        if (status.camera?.zoom) {
            elements.zoomSlider.value = status.camera.zoom;
            elements.zoomValue.textContent = `${status.camera.zoom.toFixed(1)}x`;
        }

    } else {
        // Disconnected
        elements.connectionStatus.textContent = 'Disconnected';
        elements.connectionStatus.className = 'status-indicator disconnected';
    }
}

// Update stream status UI
function updateStreamStatus(streaming) {
    isStreaming = streaming;
    elements.streamStatus.textContent = streaming ? 'Streaming' : 'Stopped';
    elements.streamStatus.className = streaming ? 'status-text running' : 'status-text stopped';
    elements.btnStreamStart.disabled = streaming;
    elements.btnStreamStop.disabled = !streaming;

    if (streaming) {
        document.body.classList.add('streaming');
    } else {
        document.body.classList.remove('streaming');
    }
}

// Update RTSP status UI
function updateRtspStatus(rtsp) {
    if (!rtsp) return;

    isRtspRunning = rtsp.running;
    elements.btnRtspStart.disabled = rtsp.running;
    elements.btnRtspStop.disabled = !rtsp.running;
    elements.rtspClients.textContent = rtsp.clients || 0;

    if (rtsp.running && rtsp.url) {
        elements.rtspUrl.textContent = rtsp.url;
        elements.rtspUrl.href = rtsp.url;
    } else {
        elements.rtspUrl.textContent = 'Not running';
        elements.rtspUrl.href = '#';
    }
}

// Preview control
function togglePreview() {
    if (isPreviewActive) {
        stopPreview();
    } else {
        startPreview();
    }
}

function startPreview() {
    elements.previewImage.src = '/mjpeg?' + Date.now();
    elements.previewImage.classList.add('active');
    elements.previewOverlay.classList.add('hidden');
    elements.btnPreview.textContent = 'Stop Preview';
    isPreviewActive = true;

    elements.previewImage.onerror = () => {
        console.log('MJPEG stream error');
        stopPreview();
    };
}

function stopPreview() {
    elements.previewImage.src = '';
    elements.previewImage.classList.remove('active');
    elements.previewOverlay.classList.remove('hidden');
    elements.btnPreview.textContent = 'Start Preview';
    isPreviewActive = false;
}

// Stream control
async function startStream() {
    const resolution = elements.resolution.value.split('x');
    const config = {
        width: parseInt(resolution[0]),
        height: parseInt(resolution[1]),
        bitrate: parseFloat(elements.bitrate.value) * 1000000,
        frameRate: parseInt(elements.framerate.value),
        codec: elements.codec.value
    };

    const result = await apiCall('/api/stream/start', 'POST', config);
    if (result?.success) {
        updateStreamStatus(true);
    } else {
        alert('Failed to start streaming: ' + (result?.message || 'Unknown error'));
    }
}

async function stopStream() {
    const result = await apiCall('/api/stream/stop', 'POST');
    if (result?.success) {
        updateStreamStatus(false);
    }
}

// RTSP control
async function startRtsp() {
    const resolution = elements.resolution.value.split('x');
    const config = {
        width: parseInt(resolution[0]),
        height: parseInt(resolution[1]),
        bitrate: parseFloat(elements.bitrate.value) * 1000000,
        frameRate: parseInt(elements.framerate.value),
        port: 8554
    };

    const result = await apiCall('/api/rtsp/start', 'POST', config);
    if (result?.success) {
        updateRtspStatus({ running: true, url: result.url, clients: 0 });
    } else {
        alert('Failed to start RTSP: ' + (result?.message || 'Unknown error'));
    }
}

async function stopRtsp() {
    const result = await apiCall('/api/rtsp/stop', 'POST');
    if (result?.success) {
        updateRtspStatus({ running: false, url: '', clients: 0 });
    }
}

// Lens control
async function switchLens(lens) {
    const result = await apiCall(`/api/lens/${lens}`, 'POST');
    if (result?.success) {
        elements.lensButtons.forEach(btn => {
            btn.classList.toggle('active', btn.dataset.lens === lens);
        });
    }
}

// Camera controls
async function setZoom(zoom) {
    await apiCall('/api/zoom', 'POST', { zoom });
}

async function setExposure(ev) {
    await apiCall('/api/exposure', 'POST', { ev });
}

// Snapshot
async function captureSnapshot() {
    try {
        const response = await fetch('/api/snapshot');
        if (response.ok) {
            const blob = await response.blob();
            const url = URL.createObjectURL(blob);

            // Download the image
            const a = document.createElement('a');
            a.href = url;
            a.download = `snapshot_${Date.now()}.jpg`;
            a.click();

            URL.revokeObjectURL(url);
        } else {
            alert('Failed to capture snapshot');
        }
    } catch (error) {
        console.error('Snapshot error:', error);
        alert('Failed to capture snapshot');
    }
}

// Utility functions
function formatNumber(num) {
    if (num >= 1000000) {
        return (num / 1000000).toFixed(1) + 'M';
    } else if (num >= 1000) {
        return (num / 1000).toFixed(1) + 'K';
    }
    return num.toString();
}

function formatBitrate(bps) {
    if (bps >= 1000000) {
        return (bps / 1000000).toFixed(1) + ' Mbps';
    } else if (bps >= 1000) {
        return (bps / 1000).toFixed(0) + ' kbps';
    }
    return bps + ' bps';
}
