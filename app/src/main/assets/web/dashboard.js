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

// Director state
let directorEnabled = false;
let directorState = 'DISABLED';
let directorEventSource = null;
let loadedScriptFileName = null;
let scriptScenes = [];
let currentSceneIndex = -1;
let currentCueIndex = -1;
let totalCues = 0;

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
    deviceInfo: document.getElementById('device-info'),

    // Director elements
    directorEnabled: document.getElementById('director-enabled'),
    directorPanel: document.getElementById('director-panel'),
    directorState: document.getElementById('director-state'),
    directorScene: document.getElementById('director-scene'),
    directorCue: document.getElementById('director-cue'),
    directorTake: document.getElementById('director-take'),
    scriptTextarea: document.getElementById('script-textarea'),
    btnLoadScript: document.getElementById('btn-load-script'),
    btnClearScript: document.getElementById('btn-clear-script'),
    btnDirectorStart: document.getElementById('btn-director-start'),
    btnDirectorPause: document.getElementById('btn-director-pause'),
    btnDirectorStop: document.getElementById('btn-director-stop'),
    btnDirectorAdvance: document.getElementById('btn-director-advance'),
    cueButtons: document.querySelectorAll('.btn-cue'),
    takesList: document.getElementById('takes-list'),
    btnRefreshTakes: document.getElementById('btn-refresh-takes'),
    statTotalTakes: document.getElementById('dir-stat-takes'),
    statAvgQuality: document.getElementById('dir-stat-quality'),
    statBestTakes: document.getElementById('dir-stat-best'),
    statCueSuccess: document.getElementById('dir-stat-success'),

    // Script browser elements
    btnRefreshScripts: document.getElementById('btn-refresh-scripts'),
    scriptFilesList: document.getElementById('script-files-list'),
    scriptFilename: document.getElementById('script-filename'),
    btnSaveScript: document.getElementById('btn-save-script'),

    // Timeline elements
    timelineTrack: document.getElementById('timeline-track'),
    timelineScenes: document.getElementById('timeline-scenes'),
    timelinePlayhead: document.getElementById('timeline-playhead'),
    timelineProgress: document.getElementById('timeline-progress'),
    sceneNav: document.getElementById('scene-nav'),

    // Session export elements
    btnExportSession: document.getElementById('btn-export-session'),
    btnExportScript: document.getElementById('btn-export-script'),
    btnExportTakes: document.getElementById('btn-export-takes')
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

    // Director controls
    if (elements.directorEnabled) {
        elements.directorEnabled.addEventListener('change', toggleDirector);
    }
    if (elements.btnLoadScript) {
        elements.btnLoadScript.addEventListener('click', loadScript);
    }
    if (elements.btnClearScript) {
        elements.btnClearScript.addEventListener('click', clearScript);
    }
    if (elements.btnDirectorStart) {
        elements.btnDirectorStart.addEventListener('click', startDirector);
    }
    if (elements.btnDirectorPause) {
        elements.btnDirectorPause.addEventListener('click', pauseDirector);
    }
    if (elements.btnDirectorStop) {
        elements.btnDirectorStop.addEventListener('click', stopDirector);
    }
    if (elements.btnDirectorAdvance) {
        elements.btnDirectorAdvance.addEventListener('click', advanceDirector);
    }

    // Quick cue buttons
    if (elements.cueButtons) {
        elements.cueButtons.forEach(btn => {
            btn.addEventListener('click', () => executeQuickCue(btn.dataset.cue));
        });
    }

    // Refresh takes button
    if (elements.btnRefreshTakes) {
        elements.btnRefreshTakes.addEventListener('click', fetchTakesList);
    }

    // Script browser
    if (elements.btnRefreshScripts) {
        elements.btnRefreshScripts.addEventListener('click', fetchScriptFiles);
    }
    if (elements.btnSaveScript) {
        elements.btnSaveScript.addEventListener('click', saveScriptFile);
    }

    // Session export
    if (elements.btnExportSession) {
        elements.btnExportSession.addEventListener('click', exportSessionReport);
    }
    if (elements.btnExportScript) {
        elements.btnExportScript.addEventListener('click', exportScript);
    }
    if (elements.btnExportTakes) {
        elements.btnExportTakes.addEventListener('click', exportTakesCsv);
    }
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

        // Director status — hide section entirely if backend has no director
        var directorSection = document.querySelector('.director-section');
        if (status.director) {
            if (directorSection) directorSection.style.display = '';
            updateDirectorStatus(status.director);
        } else {
            if (directorSection) directorSection.style.display = 'none';
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

function formatDuration(seconds) {
    const hrs = Math.floor(seconds / 3600);
    const mins = Math.floor((seconds % 3600) / 60);
    const secs = Math.floor(seconds % 60);
    if (hrs > 0) {
        return `${hrs}:${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
    }
    return `${mins}:${secs.toString().padStart(2, '0')}`;
}

// ==================== AI Director Functions ====================

// Toggle director enabled state
async function toggleDirector() {
    const enabled = elements.directorEnabled.checked;

    // Use separate enable/disable endpoints
    const endpoint = enabled ? '/api/director/enable' : '/api/director/disable';
    const result = await apiCall(endpoint, 'POST');

    if (result?.success) {
        directorEnabled = enabled;
        updateDirectorPanelState(enabled);

        if (enabled) {
            // Start SSE connection for real-time updates
            startDirectorEventStream();
            // Fetch takes list and script files
            fetchTakesList();
            fetchScriptFiles();
        } else {
            // Stop SSE connection
            stopDirectorEventStream();
        }
    } else {
        // Revert checkbox
        elements.directorEnabled.checked = !enabled;
        alert('Failed to toggle director: ' + (result?.message || 'Unknown error'));
    }
}

// Update director panel enabled/disabled state
function updateDirectorPanelState(enabled) {
    if (elements.directorPanel) {
        if (enabled) {
            elements.directorPanel.classList.remove('disabled');
        } else {
            elements.directorPanel.classList.add('disabled');
        }
    }
}

// Update director status from API response
function updateDirectorStatus(director) {
    directorEnabled = director.enabled;
    directorState = director.state;

    // Update checkbox
    if (elements.directorEnabled) {
        elements.directorEnabled.checked = director.enabled;
    }

    // Update panel state
    updateDirectorPanelState(director.enabled);

    // Update state display
    if (elements.directorState) {
        elements.directorState.textContent = director.state;
        elements.directorState.className = 'value state-' + director.state.toLowerCase().replace('_', '-');
    }

    // Update scene/cue/take info
    if (elements.directorScene) {
        elements.directorScene.textContent = director.currentScene || '-';
    }
    if (elements.directorCue) {
        elements.directorCue.textContent = director.currentCue || '-';
    }
    if (elements.directorTake) {
        elements.directorTake.textContent = director.currentTake || '-';
    }

    // Update control button states
    updateDirectorControls(director.state);

    // Update session stats
    if (director.stats) {
        updateDirectorStats(director.stats);
    }

    // Update timeline from director status
    if (director.scenes && scriptScenes.length === 0) {
        renderTimeline(director.scenes);
    }
    if (director.sceneIndex !== undefined || director.cueIndex !== undefined) {
        updateTimelinePlayhead({
            sceneIndex: director.sceneIndex || 0,
            cueIndex: director.cueIndex || 0
        });
    }

    // Hide playhead when not running
    if (director.state === 'IDLE' || director.state === 'DISABLED') {
        if (elements.timelinePlayhead) {
            elements.timelinePlayhead.classList.remove('active');
        }
    }
}

// Update director control button states based on state
function updateDirectorControls(state) {
    const canStart = state === 'IDLE' || state === 'READY';
    const canPause = state === 'RUNNING';
    const canStop = state === 'RUNNING' || state === 'PAUSED';
    const canAdvance = state === 'RUNNING' || state === 'PAUSED';

    if (elements.btnDirectorStart) {
        elements.btnDirectorStart.disabled = !canStart;
        elements.btnDirectorStart.textContent = state === 'PAUSED' ? 'Resume' : 'Start';
    }
    if (elements.btnDirectorPause) {
        elements.btnDirectorPause.disabled = !canPause;
    }
    if (elements.btnDirectorStop) {
        elements.btnDirectorStop.disabled = !canStop;
    }
    if (elements.btnDirectorAdvance) {
        elements.btnDirectorAdvance.disabled = !canAdvance;
    }
}

// Update director stats display
function updateDirectorStats(stats) {
    if (elements.statTotalTakes) {
        elements.statTotalTakes.textContent = stats.totalTakes || 0;
    }
    if (elements.statAvgQuality) {
        const score = stats.averageScore || stats.avgQualityScore || 0;
        elements.statAvgQuality.textContent = score > 0 ? score.toFixed(1) : '-';
    }
    if (elements.statBestTakes) {
        elements.statBestTakes.textContent = stats.goodTakes || stats.bestTakeCount || 0;
    }
    if (elements.statCueSuccess) {
        const rate = stats.cueSuccessRate || 0;
        elements.statCueSuccess.textContent = rate > 0 ? `${(rate * 100).toFixed(0)}%` : '-';
    }
}

// Load script into director
async function loadScript() {
    const script = elements.scriptTextarea.value.trim();
    if (!script) {
        alert('Please enter a script');
        return;
    }

    const result = await apiCall('/api/director/script', 'POST', { script });
    if (result?.success) {
        elements.btnDirectorStart.disabled = false;
        // Update timeline if scenes came back
        if (result.scenes) {
            renderTimeline(result.scenes);
        }
        alert('Script loaded: ' + (result.cueCount || 0) + ' cues parsed');
    } else {
        alert('Failed to load script: ' + (result?.message || 'Unknown error'));
    }
}

// Clear script
function clearScript() {
    elements.scriptTextarea.value = '';
    loadedScriptFileName = null;
    scriptScenes = [];
    currentSceneIndex = -1;
    currentCueIndex = -1;
    totalCues = 0;
    renderTimeline([]);
    if (elements.timelinePlayhead) {
        elements.timelinePlayhead.classList.remove('active');
    }
    apiCall('/api/director/script/clear', 'POST');
}

// Start director playback
async function startDirector() {
    const result = await apiCall('/api/director/start', 'POST');
    if (!result?.success) {
        alert('Failed to start director: ' + (result?.message || 'Unknown error'));
    }
}

// Pause director playback
async function pauseDirector() {
    const result = await apiCall('/api/director/pause', 'POST');
    if (!result?.success) {
        alert('Failed to pause director: ' + (result?.message || 'Unknown error'));
    }
}

// Stop director playback
async function stopDirector() {
    const result = await apiCall('/api/director/stop', 'POST');
    if (!result?.success) {
        alert('Failed to stop director: ' + (result?.message || 'Unknown error'));
    }
}

// Advance to next cue
async function advanceDirector() {
    const result = await apiCall('/api/director/advance', 'POST');
    if (!result?.success) {
        alert('Failed to advance: ' + (result?.message || 'Unknown error'));
    }
}

// Execute quick cue
async function executeQuickCue(cue) {
    const result = await apiCall('/api/director/cue', 'POST', { cue });
    if (!result?.success) {
        console.error('Failed to execute cue:', result?.message);
    }
}

// Fetch takes list
async function fetchTakesList() {
    const result = await apiCall('/api/director/takes');
    if (result?.success && result.takes) {
        renderTakesList(result.takes);
    }
}

// Render takes list
function renderTakesList(takes) {
    if (!elements.takesList) return;

    if (!takes || takes.length === 0) {
        elements.takesList.innerHTML = '<div class="no-takes">No takes recorded yet</div>';
        return;
    }

    elements.takesList.innerHTML = takes.map((take, index) => {
        const qualityClass = getQualityClass(take.qualityScore);
        const marks = take.marks || [];
        const marksHtml = marks.map(m => `<span class="mark mark-${m.toLowerCase()}">${m}</span>`).join('');

        return `
            <div class="take-item ${take.best ? 'best' : ''}">
                <div class="take-number">#${index + 1}</div>
                <div class="take-info">
                    <div class="take-scene">${take.scene || 'Scene'}</div>
                    <div class="take-duration">${formatDuration(take.duration || 0)}</div>
                </div>
                <div class="take-marks">${marksHtml}</div>
                <div class="quality-score ${qualityClass}">${(take.qualityScore || 0).toFixed(1)}</div>
            </div>
        `;
    }).join('');
}

// Get quality class based on score
function getQualityClass(score) {
    if (score >= 9.0) return 'excellent';
    if (score >= 7.0) return 'good';
    if (score >= 5.0) return 'fair';
    return 'poor';
}

// Start Server-Sent Events for director updates
function startDirectorEventStream() {
    if (directorEventSource) {
        directorEventSource.close();
    }

    try {
        directorEventSource = new EventSource('/api/director/events');

        directorEventSource.onmessage = (event) => {
            try {
                const data = JSON.parse(event.data);
                handleDirectorEvent(data);
            } catch (e) {
                console.error('Failed to parse director event:', e);
            }
        };

        directorEventSource.onerror = (error) => {
            console.error('Director event stream error:', error);
            // Reconnect after delay
            setTimeout(() => {
                if (directorEnabled) {
                    startDirectorEventStream();
                }
            }, 5000);
        };

        directorEventSource.addEventListener('state', (event) => {
            const data = JSON.parse(event.data);
            updateDirectorStatus(data);
        });

        directorEventSource.addEventListener('cue', (event) => {
            const data = JSON.parse(event.data);
            if (elements.directorCue) {
                elements.directorCue.textContent = data.cue || '-';
            }
        });

        directorEventSource.addEventListener('take', (event) => {
            const data = JSON.parse(event.data);
            // Refresh takes list when a new take is recorded
            fetchTakesList();
        });

    } catch (e) {
        console.error('Failed to start director event stream:', e);
    }
}

// Stop Server-Sent Events
function stopDirectorEventStream() {
    if (directorEventSource) {
        directorEventSource.close();
        directorEventSource = null;
    }
}

// Handle director events
function handleDirectorEvent(data) {
    switch (data.type) {
        case 'state':
            updateDirectorStatus(data);
            break;
        case 'cue':
            if (elements.directorCue) {
                elements.directorCue.textContent = data.cue || '-';
            }
            updateTimelinePlayhead(data);
            break;
        case 'take':
            fetchTakesList();
            break;
        case 'scene':
            if (elements.directorScene) {
                elements.directorScene.textContent = data.scene || '-';
            }
            updateTimelineScene(data);
            break;
    }
}

// ==================== Script File Browser Functions ====================

// Fetch list of saved script files
async function fetchScriptFiles() {
    const result = await apiCall('/api/director/scripts');
    if (result?.success && result.scripts) {
        renderScriptFiles(result.scripts);
    }
}

// Render script files list
function renderScriptFiles(scripts) {
    if (!elements.scriptFilesList) return;

    if (!scripts || scripts.length === 0) {
        elements.scriptFilesList.innerHTML = '<div class="no-scripts">No saved scripts</div>';
        return;
    }

    elements.scriptFilesList.innerHTML = scripts.map(script => {
        const isActive = loadedScriptFileName === script.fileName;
        const size = formatFileSize(script.size || 0);
        const date = script.lastModified ? formatDate(script.lastModified) : '';

        return `
            <div class="script-file-item ${isActive ? 'active' : ''}" data-filename="${escapeHtml(script.fileName)}">
                <div class="script-file-name">${escapeHtml(script.fileName)}</div>
                <div class="script-file-meta">
                    <span class="script-file-size">${size}</span>
                    <span class="script-file-date">${date}</span>
                    <button class="btn-delete-script" onclick="event.stopPropagation(); deleteScriptFile('${escapeHtml(script.fileName)}')" title="Delete">X</button>
                </div>
            </div>
        `;
    }).join('');

    // Add click handlers to load scripts
    elements.scriptFilesList.querySelectorAll('.script-file-item').forEach(item => {
        item.addEventListener('click', () => {
            const fileName = item.dataset.filename;
            loadScriptFromFile(fileName);
        });
    });
}

// Load script from saved file
async function loadScriptFromFile(fileName) {
    const result = await apiCall(`/api/director/scripts/${encodeURIComponent(fileName)}`);
    if (result?.success) {
        elements.scriptTextarea.value = result.script || '';
        loadedScriptFileName = fileName;
        elements.scriptFilename.value = fileName.replace(/\.[^.]+$/, '');

        // Update active state in file list
        elements.scriptFilesList.querySelectorAll('.script-file-item').forEach(item => {
            item.classList.toggle('active', item.dataset.filename === fileName);
        });

        // If parsed cues came back, update timeline
        if (result.scenes) {
            scriptScenes = result.scenes;
            renderTimeline(result.scenes);
        }
    } else {
        alert('Failed to load script: ' + (result?.message || 'Unknown error'));
    }
}

// Save current script to file
async function saveScriptFile() {
    const script = elements.scriptTextarea.value.trim();
    if (!script) {
        alert('No script to save');
        return;
    }

    let fileName = elements.scriptFilename.value.trim();
    if (!fileName) {
        alert('Please enter a filename');
        return;
    }

    // Ensure .txt extension
    if (!fileName.endsWith('.txt')) {
        fileName += '.txt';
    }

    const result = await apiCall('/api/director/scripts/save', 'POST', { fileName, script });
    if (result?.success) {
        loadedScriptFileName = fileName;
        fetchScriptFiles();
    } else {
        alert('Failed to save script: ' + (result?.message || 'Unknown error'));
    }
}

// Delete a saved script file
async function deleteScriptFile(fileName) {
    if (!confirm(`Delete script "${fileName}"?`)) return;

    const result = await apiCall(`/api/director/scripts/${encodeURIComponent(fileName)}`, 'DELETE');
    if (result?.success) {
        if (loadedScriptFileName === fileName) {
            loadedScriptFileName = null;
        }
        fetchScriptFiles();
    } else {
        alert('Failed to delete script: ' + (result?.message || 'Unknown error'));
    }
}

// ==================== Timeline Visualization Functions ====================

// Render the timeline from parsed scenes
function renderTimeline(scenes) {
    if (!elements.timelineScenes) return;

    scriptScenes = scenes || [];
    totalCues = 0;

    if (scriptScenes.length === 0) {
        elements.timelineScenes.innerHTML = '';
        updateTimelineProgress(0, 0);
        renderSceneNav([]);
        return;
    }

    // Calculate total cues across all scenes
    scriptScenes.forEach(scene => {
        totalCues += (scene.cues || []).length;
    });

    // Render scene blocks proportional to cue count
    elements.timelineScenes.innerHTML = scriptScenes.map((scene, index) => {
        const cueCount = (scene.cues || []).length;
        const widthPercent = totalCues > 0 ? (cueCount / totalCues) * 100 : (100 / scriptScenes.length);
        const label = scene.label || `Scene ${index + 1}`;
        const stateClass = index < currentSceneIndex ? 'past' :
                          index === currentSceneIndex ? 'current' : 'future';

        return `<div class="timeline-scene-block ${stateClass}"
                     style="width: ${widthPercent}%"
                     data-scene-index="${index}"
                     title="${escapeHtml(label)} (${cueCount} cues)">
                    ${escapeHtml(label)}
                </div>`;
    }).join('');

    updateTimelineProgress(0, totalCues);
    renderSceneNav(scriptScenes);
}

// Update timeline playhead position based on cue progress
function updateTimelinePlayhead(data) {
    if (!elements.timelinePlayhead || totalCues === 0) return;

    const cueIndex = data.cueIndex || 0;
    const sceneIndex = data.sceneIndex || 0;

    currentCueIndex = cueIndex;
    currentSceneIndex = sceneIndex;

    // Calculate cumulative position
    let cuesBefore = 0;
    for (let i = 0; i < sceneIndex && i < scriptScenes.length; i++) {
        cuesBefore += (scriptScenes[i].cues || []).length;
    }
    cuesBefore += cueIndex;

    const position = (cuesBefore / totalCues) * 100;
    elements.timelinePlayhead.style.left = position + '%';
    elements.timelinePlayhead.classList.add('active');

    // Update scene block states
    elements.timelineScenes.querySelectorAll('.timeline-scene-block').forEach((block, idx) => {
        block.classList.remove('past', 'current', 'future');
        if (idx < sceneIndex) block.classList.add('past');
        else if (idx === sceneIndex) block.classList.add('current');
        else block.classList.add('future');
    });

    // Update scene nav active state
    elements.sceneNav.querySelectorAll('.scene-nav-btn').forEach((btn, idx) => {
        btn.classList.remove('active', 'completed');
        if (idx < sceneIndex) btn.classList.add('completed');
        else if (idx === sceneIndex) btn.classList.add('active');
    });

    updateTimelineProgress(cuesBefore, totalCues);
}

// Update timeline scene highlighting
function updateTimelineScene(data) {
    const sceneIndex = data.sceneIndex || 0;
    currentSceneIndex = sceneIndex;

    if (!elements.timelineScenes) return;

    elements.timelineScenes.querySelectorAll('.timeline-scene-block').forEach((block, idx) => {
        block.classList.remove('past', 'current', 'future');
        if (idx < sceneIndex) block.classList.add('past');
        else if (idx === sceneIndex) block.classList.add('current');
        else block.classList.add('future');
    });
}

// Update progress text
function updateTimelineProgress(current, total) {
    if (elements.timelineProgress) {
        elements.timelineProgress.textContent = `${current} / ${total} cues`;
    }
}

// Render scene navigation buttons
function renderSceneNav(scenes) {
    if (!elements.sceneNav) return;

    if (!scenes || scenes.length === 0) {
        elements.sceneNav.innerHTML = '';
        return;
    }

    elements.sceneNav.innerHTML = scenes.map((scene, index) => {
        const label = scene.label || `Scene ${index + 1}`;
        return `<button class="scene-nav-btn" data-scene-index="${index}" title="${escapeHtml(label)}">${escapeHtml(label)}</button>`;
    }).join('');

    // Add click handlers for scene navigation
    elements.sceneNav.querySelectorAll('.scene-nav-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const sceneIndex = parseInt(btn.dataset.sceneIndex);
            jumpToScene(sceneIndex);
        });
    });
}

// Jump to a specific scene
async function jumpToScene(sceneIndex) {
    const result = await apiCall('/api/director/scene', 'POST', { sceneIndex });
    if (!result?.success) {
        console.error('Failed to jump to scene:', result?.message);
    }
}

// ==================== Session Export Functions ====================

// Export session report as JSON
async function exportSessionReport() {
    const [sessionResult, takesResult, statusResult] = await Promise.all([
        apiCall('/api/director/session'),
        apiCall('/api/director/takes'),
        apiCall('/api/director/status')
    ]);

    const report = {
        exportDate: new Date().toISOString(),
        session: sessionResult?.session || {},
        status: statusResult || {},
        takes: takesResult?.takes || [],
        stats: sessionResult?.stats || {}
    };

    downloadJson(report, `session-report-${formatTimestamp()}.json`);
}

// Export current script as text
async function exportScript() {
    const script = elements.scriptTextarea.value.trim();
    if (!script) {
        // Try to get from API
        const result = await apiCall('/api/director/scripts/export');
        if (result?.success && result.script) {
            downloadText(result.script, `script-${formatTimestamp()}.txt`);
        } else {
            alert('No script to export');
        }
        return;
    }

    const fileName = loadedScriptFileName || `script-${formatTimestamp()}.txt`;
    downloadText(script, fileName);
}

// Export takes as CSV
async function exportTakesCsv() {
    const result = await apiCall('/api/director/takes');
    if (!result?.success || !result.takes || result.takes.length === 0) {
        alert('No takes to export');
        return;
    }

    const takes = result.takes;
    const headers = ['Take #', 'Scene', 'Duration (s)', 'Quality Score', 'Quality Class', 'Marks', 'Best', 'File'];
    const rows = takes.map((take, index) => [
        index + 1,
        take.scene || '',
        (take.duration || 0).toFixed(1),
        (take.qualityScore || 0).toFixed(2),
        getQualityClass(take.qualityScore || 0),
        (take.marks || []).join('; '),
        take.best ? 'Yes' : 'No',
        take.filePath || ''
    ]);

    const csv = [headers.join(','), ...rows.map(row => row.map(cell => `"${cell}"`).join(','))].join('\n');
    downloadText(csv, `takes-${formatTimestamp()}.csv`);
}

// ==================== Utility Functions ====================

function downloadJson(data, filename) {
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    downloadBlob(blob, filename);
}

function downloadText(text, filename) {
    const blob = new Blob([text], { type: 'text/plain' });
    downloadBlob(blob, filename);
}

function downloadBlob(blob, filename) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
}

function formatFileSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

function formatDate(timestamp) {
    const date = new Date(timestamp);
    return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function formatTimestamp() {
    const now = new Date();
    return now.getFullYear() +
        String(now.getMonth() + 1).padStart(2, '0') +
        String(now.getDate()).padStart(2, '0') + '-' +
        String(now.getHours()).padStart(2, '0') +
        String(now.getMinutes()).padStart(2, '0') +
        String(now.getSeconds()).padStart(2, '0');
}

function escapeHtml(str) {
    if (!str) return '';
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}
