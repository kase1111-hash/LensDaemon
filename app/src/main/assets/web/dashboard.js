/**
 * LensDaemon Dashboard JavaScript
 *
 * Talks to the phone's REST API (docs/API.md). Every panel reads the fields the
 * API actually returns; the status, thermal, upload and kiosk panels poll while
 * the page is visible and stop while it is hidden, to spare the phone, and the
 * live preview pauses with them.
 */
'use strict';

// API base URL
const API_BASE = '';

// Polling intervals
const STATUS_POLL_MS = 2000;
const SLOW_POLL_MS = 5000;
const KIOSK_POLL_MS = 10000;
const LIBRARY_POLL_MS = 30000;

// State
let isStreaming = false;
let isRtspRunning = false;
let isMpegTsRunning = false;
let isPreviewActive = false;
// The preview was on when the page was hidden: it comes back when the page is shown
let previewResumeOnShow = false;
let pollTimers = [];
let connected = false;
let deviceInfoLoaded = false;

// Encoder settings form: follows the running encoder until edited
let encoderFormEdited = false;

// The phone's saved stream defaults (GET /api/config "defaults"), or null when unknown
let streamDefaults = null;

// Camera controls
let zoomDragging = false;
let exposureDragging = false;
let exposureStep = 0;
let availableLenses = [];

// Recording: "active" while a recording is open, running or paused
let recordingActive = false;
let recordingPaused = false;
let recordingStartPending = false;
let currentRecordingFile = '';
let recordingsCache = [];

// Network storage
const uploadConfigured = { S3: false, SMB: false };
let failedUploadsKey = '';

// Kiosk
let kioskEnabled = false;
let kioskDeviceOwner = false;
let kioskConfig = null;
// The settings form follows the phone's settings until edited
let kioskFormEdited = false;
const MAX_BOOT_DELAY_S = 120;
const MAX_GESTURE_HOLD_S = 30;

// Thermal thresholds in force (replaced by the governor's, from GET /api/thermal/profile)
const thermalLimits = { cpuWarn: 50, cpuEmergency: 60, batteryWarn: 42, batteryEmergency: 48 };
let thermalProfileLoaded = false;
let thermalProfilePending = false;

// Director state
let directorEnabled = false;
let directorState = 'DISABLED';
let directorEventSource = null;
let directorReconnectTimer = null;
let directorDataLoaded = false;
let loadedScriptFileName = null;
let sceneOptionsKey = '';

const $ = id => document.getElementById(id);

// DOM Elements
const elements = {
    connectionStatus: $('connection-status'),
    toastArea: $('toast-area'),
    previewImage: $('mjpeg-preview'),
    previewOverlay: $('preview-overlay'),
    btnPreview: $('btn-preview'),
    btnSnapshot: $('btn-snapshot'),
    btnStreamStart: $('btn-stream-start'),
    btnStreamStop: $('btn-stream-stop'),
    btnRtspStart: $('btn-rtsp-start'),
    btnRtspStop: $('btn-rtsp-stop'),
    streamStatus: $('stream-status'),
    rtspUrl: $('rtsp-url'),
    rtspClients: $('rtsp-clients'),
    mpegtsHost: $('mpegts-host'),
    mpegtsPort: $('mpegts-port'),
    mpegtsStatus: $('mpegts-status'),
    mpegtsObsInput: $('mpegts-obs-input'),
    btnMpegtsStart: $('btn-mpegts-start'),
    btnMpegtsStop: $('btn-mpegts-stop'),
    lensButtons: $('lens-buttons'),
    zoomSlider: $('zoom-slider'),
    zoomValue: $('zoom-value'),
    exposureSlider: $('exposure-slider'),
    exposureValue: $('exposure-value'),
    resolution: $('resolution'),
    bitrate: $('bitrate'),
    framerate: $('framerate'),
    codec: $('codec'),
    audioEnabled: $('audio-enabled'),
    audioState: $('audio-state'),
    encoderNote: $('encoder-note'),
    btnSaveDefaults: $('btn-save-defaults'),
    defaultsSummary: $('defaults-summary'),
    defaultsResult: $('defaults-result'),
    statFrames: $('stat-frames'),
    statFps: $('stat-fps'),
    statBitrate: $('stat-bitrate'),
    statEncoder: $('stat-encoder'),
    deviceInfo: $('device-info'),

    // Recording
    recordingStatus: $('recording-status'),
    recordingDetail: $('recording-detail'),
    segmentMinutes: $('segment-minutes'),
    btnRecordStart: $('btn-record-start'),
    btnRecordPause: $('btn-record-pause'),
    btnRecordStop: $('btn-record-stop'),
    recordingsList: $('recordings-list'),
    btnRecordingsRefresh: $('btn-recordings-refresh'),
    btnStorageCleanup: $('btn-storage-cleanup'),
    storageMeter: $('storage-meter'),
    storageText: $('storage-text'),

    // Thermal
    thermalUnavailable: $('thermal-unavailable'),
    thermalBody: $('thermal-body'),
    cpuMeter: $('cpu-meter'),
    cpuTemp: $('cpu-temp'),
    cpuLevel: $('cpu-level'),
    batteryMeter: $('battery-meter'),
    batteryTemp: $('battery-temp'),
    batteryLevel: $('battery-level'),
    batteryDetail: $('battery-detail'),
    chargeAdvice: $('charge-advice'),
    throttleDetail: $('throttle-detail'),
    thermalProfile: $('thermal-profile'),

    // Network storage
    uploadUnavailable: $('upload-unavailable'),
    uploadBody: $('upload-body'),
    uploadState: $('upload-state'),
    uploadQueue: $('upload-queue'),
    uploadFailed: $('upload-failed'),
    uploadCurrent: $('upload-current'),
    btnUploadStart: $('btn-upload-start'),
    btnUploadStop: $('btn-upload-stop'),
    btnUploadRetry: $('btn-upload-retry'),
    btnUploadClear: $('btn-upload-clear'),
    s3Summary: $('s3-summary'),
    s3Backend: $('s3-backend'),
    s3Endpoint: $('s3-endpoint'),
    s3Region: $('s3-region'),
    s3Bucket: $('s3-bucket'),
    s3AccessKey: $('s3-access-key'),
    s3SecretKey: $('s3-secret-key'),
    s3Prefix: $('s3-prefix'),
    s3Result: $('s3-result'),
    smbSummary: $('smb-summary'),
    smbServer: $('smb-server'),
    smbShare: $('smb-share'),
    smbUsername: $('smb-username'),
    smbPassword: $('smb-password'),
    smbDomain: $('smb-domain'),
    smbPrefix: $('smb-prefix'),
    smbResult: $('smb-result'),

    // Kiosk
    kioskUnavailable: $('kiosk-unavailable'),
    kioskBody: $('kiosk-body'),
    kioskEnabled: $('kiosk-enabled'),
    kioskPinned: $('kiosk-pinned'),
    kioskOwner: $('kiosk-owner'),
    kioskScreen: $('kiosk-screen'),
    kioskHint: $('kiosk-hint'),
    btnKioskToggle: $('btn-kiosk-toggle'),
    kioskPinState: $('kiosk-pin-state'),
    kioskExitPin: $('kiosk-exit-pin'),
    btnKioskPinSet: $('btn-kiosk-pin-set'),
    btnKioskPinRemove: $('btn-kiosk-pin-remove'),
    kioskStartRtsp: $('kiosk-start-rtsp'),
    kioskStartRecording: $('kiosk-start-recording'),
    kioskDelay: $('kiosk-delay'),
    kioskScreenMode: $('kiosk-screen-mode'),
    kioskExitGesture: $('kiosk-exit-gesture'),
    kioskGestureSeconds: $('kiosk-gesture-seconds'),
    btnKioskSave: $('btn-kiosk-save'),
    kioskResult: $('kiosk-result'),

    // Director elements
    directorSection: document.querySelector('.director-section'),
    directorEnabled: $('director-enabled'),
    directorPanel: $('director-panel'),
    directorState: $('director-state'),
    directorScene: $('director-scene'),
    directorCue: $('director-cue'),
    directorTake: $('director-take'),
    scriptTextarea: $('script-textarea'),
    scriptProblems: $('script-problems'),
    btnLoadScript: $('btn-load-script'),
    btnClearScript: $('btn-clear-script'),
    btnDirectorStart: $('btn-director-start'),
    btnDirectorPause: $('btn-director-pause'),
    btnDirectorStop: $('btn-director-stop'),
    btnDirectorAdvance: $('btn-director-advance'),
    cueButtons: document.querySelectorAll('.btn-cue'),
    takesList: $('takes-list'),
    btnRefreshTakes: $('btn-refresh-takes'),
    statTotalTakes: $('dir-stat-takes'),
    statAvgQuality: $('dir-stat-quality'),
    statBestTakes: $('dir-stat-best'),
    statCueSuccess: $('dir-stat-success'),
    sceneProgress: $('scene-progress'),
    sceneSelect: $('scene-select'),

    // Script browser elements
    btnRefreshScripts: $('btn-refresh-scripts'),
    scriptFilesList: $('script-files-list'),
    scriptFilename: $('script-filename'),
    btnSaveScript: $('btn-save-script')
};

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    setupEventListeners();
    fetchDeviceInfo();
    fetchLenses();
    fetchThermalProfile();
    fetchStorageConfig();
    fetchStreamDefaults();
    refreshLibrary();
    startPolling();
});

// Poll only while the page is visible: every request costs the phone a little.
// The preview, a JPEG per frame, stops too and comes back when the page is shown.
document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
        stopPolling();
        if (isPreviewActive) {
            previewResumeOnShow = true;
            stopPreview();
        }
    } else {
        startPolling();
        if (previewResumeOnShow) {
            previewResumeOnShow = false;
            startPreview();
        }
    }
});

function startPolling() {
    stopPolling();
    fetchStatus();
    fetchThermal();
    fetchUploadStatus();
    fetchKioskStatus();
    pollTimers = [
        setInterval(fetchStatus, STATUS_POLL_MS),
        setInterval(fetchThermal, SLOW_POLL_MS),
        setInterval(fetchUploadStatus, SLOW_POLL_MS),
        setInterval(fetchKioskStatus, KIOSK_POLL_MS),
        setInterval(refreshLibrary, LIBRARY_POLL_MS)
    ];
}

function stopPolling() {
    pollTimers.forEach(clearInterval);
    pollTimers = [];
}

// Setup event listeners
function setupEventListeners() {
    // Preview
    elements.btnPreview.addEventListener('click', togglePreview);
    elements.previewOverlay.addEventListener('click', togglePreview);
    elements.btnSnapshot.addEventListener('click', captureSnapshot);

    // Encoder and outputs
    elements.btnStreamStart.addEventListener('click', startStream);
    elements.btnStreamStop.addEventListener('click', stopStream);
    elements.btnRtspStart.addEventListener('click', startRtsp);
    elements.btnRtspStop.addEventListener('click', stopRtsp);
    elements.btnMpegtsStart.addEventListener('click', startMpegTs);
    elements.btnMpegtsStop.addEventListener('click', stopMpegTs);
    elements.mpegtsPort.addEventListener('input', () => {
        elements.mpegtsObsInput.textContent = `udp://@:${elements.mpegtsPort.value || 9000}`;
    });

    // Phone microphone
    elements.audioEnabled.addEventListener('change', setAudioEnabled);

    // Settings edited by hand stop following the running encoder
    [elements.resolution, elements.bitrate, elements.framerate, elements.codec].forEach(el => {
        el.addEventListener('change', () => {
            encoderFormEdited = true;
            updateEncoderNote();
        });
    });
    elements.btnSaveDefaults.addEventListener('click', saveStreamDefaults);
    // What "Save as phone default" last answered no longer applies once the settings change
    [elements.resolution, elements.bitrate, elements.framerate, elements.codec, elements.segmentMinutes].forEach(el => {
        el.addEventListener('change', () => showResult(elements.defaultsResult, null, ''));
    });

    // Lens selection (the default buttons; replaced by the phone's own lenses once known)
    elements.lensButtons.querySelectorAll('.btn-lens').forEach(btn => {
        btn.addEventListener('click', () => switchLens(btn.dataset.lens));
    });

    // Zoom control
    elements.zoomSlider.addEventListener('input', (e) => {
        zoomDragging = true;
        elements.zoomValue.textContent = `${parseFloat(e.target.value).toFixed(1)}x`;
    });
    elements.zoomSlider.addEventListener('change', (e) => {
        zoomDragging = false;
        setZoom(parseFloat(e.target.value));
    });

    // Exposure control
    elements.exposureSlider.addEventListener('input', (e) => {
        exposureDragging = true;
        elements.exposureValue.textContent = formatExposure(parseInt(e.target.value, 10));
    });
    elements.exposureSlider.addEventListener('change', (e) => {
        exposureDragging = false;
        setExposure(parseInt(e.target.value, 10));
    });

    // Recording
    elements.btnRecordStart.addEventListener('click', startRecording);
    elements.btnRecordPause.addEventListener('click', toggleRecordingPause);
    elements.btnRecordStop.addEventListener('click', stopRecording);
    elements.btnRecordingsRefresh.addEventListener('click', refreshLibrary);
    elements.btnStorageCleanup.addEventListener('click', cleanUpStorage);

    // Network storage
    elements.btnUploadStart.addEventListener('click', () => uploadAction('/api/upload/start'));
    elements.btnUploadStop.addEventListener('click', () => uploadAction('/api/upload/stop'));
    elements.btnUploadRetry.addEventListener('click', retryFailedUploads);
    elements.btnUploadClear.addEventListener('click', () => uploadAction('/api/upload/clear'));
    $('btn-s3-save').addEventListener('click', saveS3);
    $('btn-s3-test').addEventListener('click', testS3);
    $('btn-s3-remove').addEventListener('click', () => removeStorage('s3'));
    $('btn-smb-save').addEventListener('click', saveSmb);
    $('btn-smb-test').addEventListener('click', testSmb);
    $('btn-smb-remove').addEventListener('click', () => removeStorage('smb'));

    // Kiosk
    elements.btnKioskToggle.addEventListener('click', toggleKiosk);
    elements.btnKioskPinSet.addEventListener('click', setExitPin);
    elements.btnKioskPinRemove.addEventListener('click', removeExitPin);
    elements.btnKioskSave.addEventListener('click', saveKioskSettings);
    [elements.kioskStartRtsp, elements.kioskStartRecording, elements.kioskDelay, elements.kioskScreenMode,
        elements.kioskExitGesture, elements.kioskGestureSeconds].forEach(el => {
        el.addEventListener('input', () => { kioskFormEdited = true; });
        el.addEventListener('change', () => { kioskFormEdited = true; });
    });
    elements.kioskExitGesture.addEventListener('change', () => {
        elements.kioskGestureSeconds.disabled = !elements.kioskExitGesture.checked;
    });

    // Director controls
    elements.directorEnabled.addEventListener('change', toggleDirector);
    elements.btnLoadScript.addEventListener('click', loadScript);
    elements.btnClearScript.addEventListener('click', clearScript);
    elements.btnDirectorStart.addEventListener('click', startDirector);
    elements.btnDirectorPause.addEventListener('click', pauseDirector);
    elements.btnDirectorStop.addEventListener('click', stopDirector);
    elements.btnDirectorAdvance.addEventListener('click', advanceDirector);
    elements.sceneSelect.addEventListener('change', jumpToScene);
    elements.cueButtons.forEach(btn => {
        btn.addEventListener('click', () => executeQuickCue(btn.dataset.cue));
    });
    elements.btnRefreshTakes.addEventListener('click', fetchTakesList);
    elements.btnRefreshScripts.addEventListener('click', fetchScriptFiles);
    elements.btnSaveScript.addEventListener('click', saveScriptFile);
}

// ==================== API ====================

/**
 * Call the API. Returns the parsed JSON, or null when the phone can't be
 * reached. A failed request always carries an `error`: the server's own, else
 * its `message` (older handlers put the reason there), else the HTTP status.
 */
async function apiCall(endpoint, method = 'GET', body = null) {
    try {
        const options = { method, headers: {} };
        if (body !== null && body !== undefined) {
            options.headers['Content-Type'] = 'application/json; charset=utf-8';
            options.body = JSON.stringify(body);
        }
        const response = await fetch(`${API_BASE}${endpoint}`, options);
        let data = {};
        try {
            data = await response.json();
        } catch (e) {
            data = {};
        }
        if (data === null || typeof data !== 'object') {
            data = {};
        }
        if (!response.ok && !data.error) {
            data.error = data.message || `HTTP ${response.status}`;
        }
        Object.defineProperty(data, 'httpStatus', { value: response.status, enumerable: false });
        return data;
    } catch (error) {
        console.warn('API call failed:', endpoint, error);
        return null;
    }
}

/** Whether an API answer reports success. */
function succeeded(result) {
    return !!result && result.httpStatus < 400 && result.success !== false && !result.error;
}

function errorText(result) {
    if (!result) return 'the phone did not answer';
    return result.error || result.message || 'unknown error';
}

const MAX_TOASTS = 4;

/** A short notice in the corner, instead of a blocking alert. */
function notify(message, kind = 'info') {
    while (elements.toastArea.childElementCount >= MAX_TOASTS) {
        elements.toastArea.firstElementChild.remove();
    }
    const toast = document.createElement('div');
    toast.className = `toast ${kind}`;
    toast.textContent = message;
    elements.toastArea.appendChild(toast);
    setTimeout(() => toast.remove(), kind === 'error' ? 8000 : 4000);
}

function setConnected(isConnected) {
    connected = isConnected;
    elements.connectionStatus.textContent = isConnected ? 'Connected' : 'Disconnected';
    elements.connectionStatus.className = `status-indicator ${isConnected ? 'connected' : 'disconnected'}`;
}

// ==================== Device and status ====================

async function fetchDeviceInfo() {
    const info = await apiCall('/api/device');
    if (!info || info.error) return;
    deviceInfoLoaded = true;
    const version = info.appVersion ? `${info.appVersion} | ` : '';
    elements.deviceInfo.textContent = `${version}${info.manufacturer} ${info.model} | Android ${info.androidVersion}`;
    // Default the MPEG-TS target to the computer this dashboard is open on
    if (info.clientAddress && !elements.mpegtsHost.value) {
        elements.mpegtsHost.value = info.clientAddress;
    }
}

async function fetchStatus() {
    const status = await apiCall('/api/status');

    if (!status || status.status !== 'ok') {
        setConnected(false);
        return;
    }

    const reconnected = !connected;
    setConnected(true);
    if (reconnected && !deviceInfoLoaded) {
        fetchDeviceInfo();
    }

    updateStreamStatus(status);
    updateEncoderStats(status.encoder);
    syncEncoderForm(status.encoder);
    updateRtspStatus(status.rtsp);
    updateMpegTsStatus(status.mpegts);
    updateAudioStatus(status.audio);
    updateCameraControls(status.camera);
    updateRecordingStatus(status.recording);

    // Director: the section only exists on builds with the director
    if (status.director) {
        elements.directorSection.style.display = '';
        updateDirectorStatus(status.director);
    } else {
        elements.directorSection.style.display = 'none';
    }
}

// Overall streaming status: the shared encoder and the outputs using it. A
// thermal pause stops every output; the phone restarts them once it has cooled.
function updateStreamStatus(status) {
    const encoderOn = !!status.camera?.streamingActive;
    const thermalPaused = !!status.camera?.thermalPaused;
    const outputs = [];
    if (status.rtsp?.running) outputs.push(`RTSP (${status.rtsp.playing || 0} watching)`);
    if (status.mpegts?.running) outputs.push('MPEG-TS');
    if (status.recording?.active) outputs.push(status.recording.paused ? 'recording (paused)' : 'recording');

    isStreaming = encoderOn || outputs.length > 0;
    let text = 'Stopped';
    if (outputs.length > 0) {
        text = `Live: ${outputs.join(', ')}`;
    } else if (encoderOn) {
        text = 'Encoder running, no outputs';
    } else if (thermalPaused) {
        text = 'Paused: the phone is too hot; outputs restart when it cools';
    }
    elements.streamStatus.textContent = text;
    elements.streamStatus.className = `status-text ${isStreaming ? 'running' : 'stopped'}`;
    elements.btnStreamStart.disabled = encoderOn;
    // During a thermal pause, Stop All cancels the restart after cool-down
    elements.btnStreamStop.disabled = !isStreaming && !thermalPaused;
    elements.btnStreamStop.title = !isStreaming && thermalPaused ? 'Cancel the restart after cool-down' : '';
    document.body.classList.toggle('streaming', isStreaming);
}

function updateEncoderStats(enc) {
    if (!enc) return;
    elements.statFrames.textContent = formatNumber(enc.framesEncoded || 0);
    elements.statFps.textContent = (enc.currentFps || 0).toFixed(1);
    elements.statBitrate.textContent = formatBitrate(enc.currentBitrate || 0);
    elements.statEncoder.textContent = enc.width
        ? `${enc.state} ${enc.width}x${enc.height}@${enc.frameRate} ${enc.codec}`
        : (enc.state || 'IDLE');
}

// The settings form shows what the running encoder uses, until edited
let runningEncoder = null;
function syncEncoderForm(enc) {
    runningEncoder = enc && enc.width ? enc : null;
    if (runningEncoder && !encoderFormEdited) {
        setSelectValue(elements.resolution, `${enc.width}x${enc.height}`, `${enc.width}x${enc.height}`);
        elements.bitrate.value = +(enc.bitrate / 1000000).toFixed(1);
        setSelectValue(elements.framerate, String(enc.frameRate), `${enc.frameRate} fps`);
        setSelectValue(elements.codec, enc.codec, enc.codec);
    }
    updateEncoderNote();
}

function updateEncoderNote() {
    const settings = encoderSettings();
    const differs = runningEncoder && (
        settings.width !== runningEncoder.width || settings.height !== runningEncoder.height ||
        settings.frameRate !== runningEncoder.frameRate || settings.codec !== runningEncoder.codec ||
        Math.abs(settings.bitrate - runningEncoder.bitrate) >= 50000);
    elements.encoderNote.hidden = !differs;
    if (differs) {
        elements.encoderNote.textContent = `The encoder is running at ${runningEncoder.width}x${runningEncoder.height} ` +
            `${runningEncoder.frameRate} fps ${runningEncoder.codec}, ${formatBitrate(runningEncoder.bitrate)}. ` +
            'These settings apply when it next starts: use Stop All first to change them now.';
    }
}

function setSelectValue(select, value, label) {
    if (![...select.options].some(o => o.value === value)) {
        select.add(new Option(label, value));
    }
    select.value = value;
}

// ==================== Phone stream defaults ====================

/**
 * The phone's saved stream defaults (GET /api/config "defaults"): what its
 * Start Stream button, kiosk boot and restarts use. While no encoder runs the
 * settings form starts from them. Older phones have none: nothing changes.
 */
async function fetchStreamDefaults() {
    const r = await apiCall('/api/config');
    if (!r || r.error || !r.defaults) return;
    streamDefaults = r.defaults;
    showStreamDefaults();
    const encoderRunning = !!(runningEncoder || (r.encoder && r.encoder.width));
    if (!encoderRunning && !encoderFormEdited) {
        applyDefaultsToForm(r.defaults);
    }
}

function applyDefaultsToForm(d) {
    if (d.width && d.height) setSelectValue(elements.resolution, `${d.width}x${d.height}`, `${d.width}x${d.height}`);
    if (d.bitrate) elements.bitrate.value = +(d.bitrate / 1000000).toFixed(1);
    if (d.frameRate) setSelectValue(elements.framerate, String(d.frameRate), `${d.frameRate} fps`);
    if (d.codec) setSelectValue(elements.codec, d.codec, d.codec);
    if (typeof d.segmentMinutes === 'number') {
        setSelectValue(elements.segmentMinutes, String(d.segmentMinutes), `${d.segmentMinutes} minutes`);
    }
    updateEncoderNote();
}

function describeDefaults(d) {
    const codec = d.codec === 'H265' ? 'H.265' : 'H.264';
    const files = d.segmentMinutes === 0 ? 'one file per recording' : `new file every ${d.segmentMinutes} min`;
    return `${d.width}x${d.height} ${d.frameRate} fps ${codec}, ${formatBitrate(d.bitrate)} · ${files} · RTSP port ${d.rtspPort}`;
}

function showStreamDefaults() {
    elements.defaultsSummary.textContent = streamDefaults ? `Phone default: ${describeDefaults(streamDefaults)}` : '';
}

/**
 * Save the settings form (and the recording's file length) as the phone's
 * stream defaults. The form has no RTSP port: the phone's own goes back with
 * them, or, while it isn't known, is left out, which keeps the saved one.
 */
async function saveStreamDefaults() {
    const defaults = { ...encoderSettings() };
    if (streamDefaults?.rtspPort) defaults.rtspPort = streamDefaults.rtspPort;
    defaults.segmentMinutes = parseInt(elements.segmentMinutes.value, 10);
    showResult(elements.defaultsResult, null, 'Saving…');
    const result = await apiCall('/api/config', 'PUT', { defaults });
    if (!succeeded(result)) {
        showResult(elements.defaultsResult, false, 'Not saved: ' + errorText(result));
    } else if (!result.defaults) {
        showResult(elements.defaultsResult, false, 'Not saved: this version of LensDaemon keeps no stream defaults');
    } else {
        streamDefaults = result.defaults;
        showStreamDefaults();
        showResult(elements.defaultsResult, true, 'Saved: the phone starts with these settings from now on');
    }
}

// Update RTSP status UI
function updateRtspStatus(rtsp) {
    if (!rtsp) return;

    isRtspRunning = rtsp.running;
    elements.btnRtspStart.disabled = rtsp.running;
    elements.btnRtspStop.disabled = !rtsp.running;
    elements.rtspClients.textContent = rtsp.playing || 0;

    if (rtsp.running && rtsp.url) {
        elements.rtspUrl.textContent = rtsp.url;
        elements.rtspUrl.href = rtsp.url;
    } else {
        elements.rtspUrl.textContent = 'Not running';
        elements.rtspUrl.href = '#';
    }
}

// Update MPEG-TS status UI
function updateMpegTsStatus(mpegts) {
    if (!mpegts) return;

    isMpegTsRunning = mpegts.running;
    elements.btnMpegtsStart.disabled = mpegts.running;
    elements.btnMpegtsStop.disabled = !mpegts.running;

    if (!mpegts.running) {
        elements.mpegtsStatus.textContent = 'Stopped';
        elements.mpegtsStatus.className = 'status-text stopped';
    } else if (mpegts.mode === 'CALLER') {
        elements.mpegtsStatus.textContent = `Sending to ${mpegts.remoteAddress}`;
        elements.mpegtsStatus.className = 'status-text running';
    } else {
        elements.mpegtsStatus.textContent = mpegts.connected
            ? `Sending to ${mpegts.remoteAddress}`
            : `Waiting for a receiver on port ${mpegts.port}`;
        elements.mpegtsStatus.className = 'status-text running';
    }
}

// Update microphone status UI
function updateAudioStatus(audio) {
    if (!audio) return;

    elements.audioEnabled.checked = audio.enabled;
    if (!audio.permission) {
        elements.audioState.textContent = 'No permission: allow the microphone on the phone';
    } else if (!audio.enabled) {
        elements.audioState.textContent = 'Off';
    } else if (audio.active) {
        const channels = audio.channels === 2 ? 'stereo' : 'mono';
        elements.audioState.textContent = `On · AAC ${audio.sampleRate / 1000} kHz ${channels}`;
    } else {
        elements.audioState.textContent = 'On (starts with the stream)';
    }
}

async function setAudioEnabled() {
    const result = await apiCall('/api/audio', 'POST', { enabled: elements.audioEnabled.checked });
    if (succeeded(result)) {
        updateAudioStatus(result);
    } else {
        notify('Could not switch the microphone: ' + errorText(result), 'error');
    }
}

// Encoder settings from the settings panel
function encoderSettings() {
    const resolution = elements.resolution.value.split('x');
    return {
        width: parseInt(resolution[0], 10),
        height: parseInt(resolution[1], 10),
        bitrate: Math.round(parseFloat(elements.bitrate.value) * 1000000),
        frameRate: parseInt(elements.framerate.value, 10),
        codec: elements.codec.value
    };
}

// ==================== Preview and snapshot ====================

function togglePreview() {
    if (isPreviewActive) {
        stopPreview();
    } else {
        startPreview();
    }
}

function startPreview() {
    let framesShown = false;
    elements.previewImage.onload = () => {
        framesShown = true;
    };
    // An error before the first frame is usually the phone refusing another viewer (503)
    elements.previewImage.onerror = () => {
        if (isPreviewActive) {
            notify(framesShown ? 'The preview stopped'
                : 'The preview could not start (the phone shows it to at most 8 viewers at a time)', 'error');
        }
        stopPreview();
    };
    elements.previewImage.src = '/mjpeg?' + Date.now();
    elements.previewImage.classList.add('active');
    elements.previewOverlay.classList.add('hidden');
    elements.btnPreview.textContent = 'Stop Preview';
    isPreviewActive = true;
}

function stopPreview() {
    isPreviewActive = false;
    elements.previewImage.onerror = null;
    elements.previewImage.onload = null;
    elements.previewImage.removeAttribute('src');
    elements.previewImage.classList.remove('active');
    elements.previewOverlay.classList.remove('hidden');
    elements.btnPreview.textContent = 'Start Preview';
}

async function captureSnapshot() {
    try {
        const response = await fetch('/api/snapshot');
        if (!response.ok) {
            const data = await response.json().catch(() => ({}));
            notify('Snapshot failed: ' + (data.error || `HTTP ${response.status}`), 'error');
            return;
        }
        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `snapshot_${new Date().toISOString().replace(/[:.]/g, '-')}.jpg`;
        document.body.appendChild(a);
        a.click();
        a.remove();
        // Revoking at once can cancel the download in some browsers
        setTimeout(() => URL.revokeObjectURL(url), 10000);
    } catch (error) {
        console.error('Snapshot error:', error);
        notify('Snapshot failed: the phone did not answer', 'error');
    }
}

// ==================== Encoder and outputs ====================

// Start Encoder applies the settings panel; outputs started later share the
// running encoder. Stop All stops every output and the encoder.
async function startStream() {
    const result = await apiCall('/api/stream/start', 'POST', encoderSettings());
    if (succeeded(result)) {
        encoderFormEdited = false;
        notify('Encoder started: start RTSP, MPEG-TS or a recording to send it somewhere', 'success');
        fetchStreamDefaults();
    } else {
        notify('Failed to start the encoder: ' + errorText(result), 'error');
    }
    fetchStatus();
}

async function stopStream() {
    const result = await apiCall('/api/stream/stop', 'POST');
    if (!succeeded(result)) {
        notify('Failed to stop: ' + errorText(result), 'error');
    }
    fetchStatus();
    refreshLibrary();
}

async function startRtsp() {
    // On the phone's own RTSP port, so the address viewers know stays the same;
    // while it isn't known the port is left out and the phone picks it
    const config = { ...encoderSettings() };
    if (streamDefaults?.rtspPort) config.port = streamDefaults.rtspPort;
    const result = await apiCall('/api/rtsp/start', 'POST', config);
    if (succeeded(result)) {
        encoderFormEdited = false;
        updateRtspStatus({ running: true, url: result.url, playing: 0 });
        fetchStreamDefaults();
    } else {
        notify('Failed to start RTSP: ' + errorText(result), 'error');
    }
    fetchStatus();
}

async function stopRtsp() {
    const result = await apiCall('/api/rtsp/stop', 'POST');
    if (succeeded(result)) {
        updateRtspStatus({ running: false, url: '', playing: 0 });
    } else {
        notify('Failed to stop RTSP: ' + errorText(result), 'error');
    }
    fetchStatus();
}

// MPEG-TS/UDP control: push to the machine running OBS
async function startMpegTs() {
    const targetHost = elements.mpegtsHost.value.trim();
    if (!targetHost) {
        notify('Enter the address of the machine running OBS', 'error');
        elements.mpegtsHost.focus();
        return;
    }
    const config = {
        ...encoderSettings(),
        mode: 'caller',
        targetHost,
        targetPort: parseInt(elements.mpegtsPort.value, 10) || 9000
    };

    const result = await apiCall('/api/mpegts/start', 'POST', config);
    if (succeeded(result)) {
        encoderFormEdited = false;
        fetchStreamDefaults();
    } else {
        notify('Failed to start MPEG-TS: ' + errorText(result), 'error');
    }
    fetchStatus();
}

async function stopMpegTs() {
    const result = await apiCall('/api/mpegts/stop', 'POST');
    if (!succeeded(result)) {
        notify('Failed to stop MPEG-TS: ' + errorText(result), 'error');
    }
    fetchStatus();
}

// ==================== Camera ====================

const LENS_FAMILY = {
    ULTRA_WIDE: 'wide', WIDE: 'wide', MAIN: 'main', TELEPHOTO: 'tele', SUPER_TELEPHOTO: 'tele'
};

// The phone's own lenses replace the default Wide / Main / Tele buttons
async function fetchLenses() {
    const result = await apiCall('/api/lenses');
    if (!result || result.error || !Array.isArray(result.available) || result.available.length === 0) return;

    const seen = new Set();
    availableLenses = result.available.filter(lens => {
        if (seen.has(lens.type)) return false;
        seen.add(lens.type);
        return true;
    });
    elements.lensButtons.innerHTML = '';
    availableLenses.forEach(lens => {
        const btn = document.createElement('button');
        btn.className = 'btn btn-lens';
        btn.dataset.lens = lens.type.toLowerCase();
        btn.dataset.type = lens.type;
        btn.title = lens.focalLength ? `${lens.displayName}, ${lens.focalLength.toFixed(1)} mm` : lens.displayName;
        btn.textContent = lens.type === 'MAIN' ? 'Main' : lens.displayName;
        if (lens.zoomFactor) {
            const factor = document.createElement('span');
            factor.className = 'lens-factor';
            factor.textContent = lens.zoomFactor;
            btn.appendChild(factor);
        }
        btn.addEventListener('click', () => switchLens(btn.dataset.lens));
        elements.lensButtons.appendChild(btn);
    });
    markActiveLens(result.current);
}

function markActiveLens(type) {
    if (!type || type === 'none') return;
    elements.lensButtons.querySelectorAll('.btn-lens').forEach(btn => {
        const active = btn.dataset.type
            ? btn.dataset.type === type
            : btn.dataset.lens === LENS_FAMILY[type];
        btn.classList.toggle('active', active);
    });
}

async function switchLens(lens) {
    const result = await apiCall(`/api/lens/${encodeURIComponent(lens)}`, 'POST');
    if (succeeded(result)) {
        markActiveLens(result.lens);
    } else {
        notify('Could not switch lens: ' + errorText(result), 'error');
    }
}

function updateCameraControls(camera) {
    if (!camera) return;

    markActiveLens(camera.currentLens);
    // A lens this page hasn't listed: the camera's lenses changed
    if (availableLenses.length > 0 && camera.currentLens !== 'none' &&
        !availableLenses.some(lens => lens.type === camera.currentLens)) {
        fetchLenses();
    }

    if (camera.maxZoom && camera.maxZoom >= 1) {
        elements.zoomSlider.max = camera.maxZoom;
    }
    if (!zoomDragging && typeof camera.zoom === 'number') {
        elements.zoomSlider.value = camera.zoom;
        elements.zoomValue.textContent = `${camera.zoom.toFixed(1)}x`;
    }

    const exposure = camera.exposure;
    if (exposure && exposure.max > exposure.min) {
        exposureStep = exposure.step || 0;
        elements.exposureSlider.min = exposure.min;
        elements.exposureSlider.max = exposure.max;
        if (!exposureDragging) {
            elements.exposureSlider.value = exposure.value;
            elements.exposureValue.textContent = formatExposure(exposure.value);
        }
    }
}

// Exposure compensation is set in the camera's own steps (often 1/6 or 1/3 EV)
function formatExposure(steps) {
    if (!exposureStep) return `${steps} EV`;
    const ev = steps * exposureStep;
    const text = Math.abs(ev) < 0.05 ? '0' : `${ev > 0 ? '+' : ''}${ev.toFixed(1)}`;
    return `${text} EV`;
}

async function setZoom(zoom) {
    const result = await apiCall('/api/zoom', 'POST', { zoom });
    if (!succeeded(result)) {
        notify('Could not set zoom: ' + errorText(result), 'error');
    }
}

async function setExposure(ev) {
    const result = await apiCall('/api/exposure', 'POST', { ev });
    if (!succeeded(result)) {
        notify('Could not set exposure: ' + errorText(result), 'error');
    }
}

// ==================== Recording ====================

// "active" means a recording is open, running or paused: a paused one keeps
// Resume and Stop, and Start stays off (a second start would only answer
// "Already recording")
function updateRecordingStatus(recording) {
    if (!recording) return;
    const wasActive = recordingActive;
    const wasPaused = recordingPaused;
    recordingPaused = !!recording.paused;
    recordingActive = !!recording.active || recordingPaused;

    if (recordingActive) {
        elements.recordingStatus.textContent = recordingPaused ? 'Paused' : 'Recording';
        elements.recordingStatus.className = `status-text ${recordingPaused ? 'paused' : 'running'}`;
        fetchRecordingDetail();
    } else {
        elements.recordingStatus.textContent = 'Stopped';
        elements.recordingStatus.className = 'status-text stopped';
        elements.recordingDetail.textContent = '';
        currentRecordingFile = '';
    }
    elements.btnRecordStart.disabled = recordingActive || recordingStartPending;
    elements.btnRecordPause.disabled = !recordingActive;
    elements.btnRecordPause.textContent = recordingPaused ? 'Resume' : 'Pause';
    elements.btnRecordStop.disabled = !recordingActive;

    if (wasActive && !recordingActive) {
        // A recording that just ended has left new files
        refreshLibrary();
    } else if (wasPaused !== recordingPaused) {
        // The file being recorded is marked paused or recording
        renderRecordings();
    }
}

async function fetchRecordingDetail() {
    const r = await apiCall('/api/recording/status');
    if (!r || r.error || !(r.recording || r.paused)) return;
    const previousFile = currentRecordingFile;
    currentRecordingFile = baseName(r.currentFile || '');
    const parts = [formatDuration(r.durationSec || 0), `file ${(r.segmentIndex || 0) + 1}`, formatFileSize(r.totalBytes || 0)];
    if (r.avgBitrateKbps) parts.push(formatBitrate(r.avgBitrateKbps * 1000));
    elements.recordingDetail.textContent = parts.join(' · ');
    if (currentRecordingFile !== previousFile) {
        // A new segment file: show it in the list
        fetchRecordings();
    }
}

// Start stays off while the request is out, so a double click starts one recording
async function startRecording() {
    const body = { ...encoderSettings(), segmentMinutes: parseInt(elements.segmentMinutes.value, 10) };
    recordingStartPending = true;
    elements.btnRecordStart.disabled = true;
    const result = await apiCall('/api/recording/start', 'POST', body);
    recordingStartPending = false;
    if (succeeded(result) && result.alreadyRecording) {
        // Started elsewhere (another dashboard, the phone, kiosk auto-start): left as it is
        notify(result.paused ? 'The recording is paused: press Resume to continue it' : 'Already recording', 'info');
    } else if (succeeded(result)) {
        encoderFormEdited = false;
        notify(`Recording (new file every ${result.segmentDuration || 'segment'})`, 'success');
        fetchStreamDefaults();
    } else {
        elements.btnRecordStart.disabled = recordingActive;
        notify('Failed to start recording: ' + errorText(result), 'error');
    }
    fetchStatus();
}

async function toggleRecordingPause() {
    const endpoint = recordingPaused ? '/api/recording/resume' : '/api/recording/pause';
    const result = await apiCall(endpoint, 'POST');
    if (!succeeded(result)) {
        notify(errorText(result), 'error');
    }
    fetchStatus();
}

async function stopRecording() {
    const result = await apiCall('/api/recording/stop', 'POST');
    if (succeeded(result)) {
        const count = result.segmentCount || 0;
        notify(`Recording saved: ${count} file${count === 1 ? '' : 's'}`, 'success');
    } else {
        notify('Failed to stop recording: ' + errorText(result), 'error');
    }
    fetchStatus();
    refreshLibrary();
}

// ==================== Recordings and storage ====================

function refreshLibrary() {
    fetchRecordings();
    fetchStorageStatus();
}

async function fetchRecordings() {
    const result = await apiCall('/api/recordings');
    if (!result || result.error) {
        elements.recordingsList.innerHTML = '';
        elements.recordingsList.appendChild(emptyNote(`Recordings unavailable: ${errorText(result)}`));
        return;
    }
    recordingsCache = (result.recordings || []).slice().sort((a, b) => b.lastModified - a.lastModified);
    renderRecordings();
}

function renderRecordings() {
    const list = elements.recordingsList;
    list.innerHTML = '';
    if (recordingsCache.length === 0) {
        list.appendChild(emptyNote('No recordings yet'));
        return;
    }
    recordingsCache.forEach(rec => {
        // The file being recorded (also while paused) can't be downloaded, uploaded or deleted until it closes
        const live = recordingActive && rec.name === currentRecordingFile;
        const item = document.createElement('div');
        item.className = 'list-item';

        const main = document.createElement('div');
        main.className = 'list-item-main';
        const name = document.createElement('div');
        name.className = 'list-item-name';
        // Long names may wrap after an underscore rather than mid-word
        rec.name.split('_').forEach((part, i, parts) => {
            name.append(i < parts.length - 1 ? `${part}_` : part);
            if (i < parts.length - 1) name.appendChild(document.createElement('wbr'));
        });
        const meta = document.createElement('div');
        meta.className = 'list-item-meta';
        meta.textContent = `${formatDate(rec.lastModified)} · ${formatFileSize(rec.sizeBytes || 0)}`;
        if (live) {
            const badge = document.createElement('span');
            badge.className = 'live-badge';
            badge.textContent = recordingPaused ? ' · ● recording (paused)' : ' · ● recording';
            meta.appendChild(badge);
        }
        main.append(name, meta);

        const actions = document.createElement('div');
        actions.className = 'list-item-actions';
        if (!live) {
            const download = document.createElement('a');
            download.className = 'btn btn-small';
            download.href = `/api/recordings/${encodeURIComponent(rec.name)}`;
            download.setAttribute('download', rec.name);
            download.textContent = 'Download';
            actions.appendChild(download);

            ['S3', 'SMB'].filter(dest => uploadConfigured[dest]).forEach(dest => {
                actions.appendChild(actionButton(`Upload to ${dest}`, 'btn btn-small', () => uploadRecording(rec, dest)));
            });
            actions.appendChild(actionButton('Delete', 'btn btn-small btn-danger-outline', () => deleteRecording(rec)));
        }

        item.append(main, actions);
        list.appendChild(item);
    });
}

async function deleteRecording(rec) {
    if (!confirm(`Delete ${rec.name} from the phone?`)) return;
    const result = await apiCall(`/api/recordings/${encodeURIComponent(rec.name)}`, 'DELETE');
    if (succeeded(result)) {
        notify(`Deleted ${rec.name}`, 'success');
    } else {
        notify('Delete failed: ' + errorText(result), 'error');
    }
    refreshLibrary();
}

async function uploadRecording(rec, destination) {
    const result = await apiCall('/api/upload/enqueue', 'POST', { filePath: rec.path, destination });
    if (succeeded(result)) {
        notify(`${rec.name} queued for upload to ${destination}`, 'success');
    } else {
        notify('Upload failed: ' + errorText(result), 'error');
    }
    fetchUploadStatus();
}

// The phone's fixed retention policy, which POST /api/storage/cleanup applies
const RETENTION_POLICY = 'recordings older than 7 days, the oldest beyond 10 GB in total, ' +
    'and as many as needed to keep 1 GB free; never the file being recorded';

async function cleanUpStorage() {
    if (!confirm(`Clean up now? This deletes ${RETENTION_POLICY}.`)) return;
    elements.btnStorageCleanup.disabled = true;
    const result = await apiCall('/api/storage/cleanup', 'POST');
    elements.btnStorageCleanup.disabled = false;
    if (!succeeded(result)) {
        notify('Clean-up failed: ' + errorText(result), 'error');
    } else if (typeof result.filesDeleted !== 'number') {
        // Older phones clean up in the background and report nothing
        notify(result.message || 'Clean-up started', 'success');
        setTimeout(refreshLibrary, 2000);
        return;
    } else {
        const count = result.filesDeleted;
        notify(count > 0
            ? `Deleted ${count} recording${count === 1 ? '' : 's'} (${formatFileSize(result.bytesFreed || 0)} freed)`
            : 'Nothing to clean up: every recording is within the retention policy', 'success');
        const failed = Array.isArray(result.failed) ? result.failed : [];
        if (failed.length > 0) {
            notify(`Could not delete ${failed.map(baseName).join(', ')}`, 'error');
        }
    }
    refreshLibrary();
}

async function fetchStorageStatus() {
    const r = await apiCall('/api/storage/status');
    if (!r || r.error || !r.storage) {
        elements.storageText.textContent = `Storage status unavailable: ${errorText(r)}`;
        elements.storageMeter.style.width = '0';
        return;
    }
    const st = r.storage;
    const percent = Math.max(0, Math.min(100, st.usagePercent || 0));
    elements.storageMeter.style.width = `${percent}%`;
    elements.storageMeter.className = `meter-fill${st.isCriticallyLowSpace ? ' critical' : st.isLowSpace ? ' warning' : ''}`;
    const count = r.totalRecordings || 0;
    elements.storageText.textContent = `${formatGb(st.availableGB)} free of ${formatGb(st.totalGB)} · ` +
        `${count} recording${count === 1 ? '' : 's'}, ${formatFileSize(r.totalRecordingsSizeBytes || 0)}` +
        (st.isLowSpace ? ' · running low on space' : '');
}

// ==================== Thermal ====================

/**
 * The thermal profile and the thresholds in force: GET /api/thermal/profile's
 * "config" is what the governor uses (the profile's, or an override's).
 * Retried from fetchThermal until it has loaded.
 */
async function fetchThermalProfile() {
    if (thermalProfilePending) return;
    thermalProfilePending = true;
    const r = await apiCall('/api/thermal/profile');
    thermalProfilePending = false;
    if (!r || r.error || !r.profile) return;
    const p = r.profile;
    const c = r.config || {};
    thermalLimits.cpuWarn = c.cpuWarningC ?? p.cpuWarnC;
    thermalLimits.cpuEmergency = c.cpuEmergencyC ?? p.cpuEmergencyC;
    thermalLimits.batteryWarn = c.batteryWarningC ?? p.batteryWarnC;
    thermalLimits.batteryEmergency = c.batteryEmergencyC ?? p.batteryEmergencyC;
    thermalProfileLoaded = true;
    elements.thermalProfile.textContent = `Profile: ${p.displayName} (${r.source}). ` +
        `Throttling starts at ${formatDegrees(thermalLimits.cpuWarn)} CPU or ${formatDegrees(thermalLimits.batteryWarn)} battery.`;
}

const LEVEL_CLASS = { NORMAL: 'normal', ELEVATED: 'normal', WARNING: 'warning', CRITICAL: 'critical', EMERGENCY: 'critical' };
const THROTTLE_LABELS = {
    REDUCE_RESOLUTION: 'lower resolution',
    REDUCE_FRAMERATE: 'lower frame rate',
    PAUSE_STREAMING: 'streaming paused',
    DISABLE_CHARGING: 'charging paused',
    ALERT_USER: 'alert'
};

async function fetchThermal() {
    const r = await apiCall('/api/thermal/status');
    const available = !!r && !r.error;
    elements.thermalUnavailable.hidden = available;
    elements.thermalBody.hidden = !available;
    if (!available) {
        elements.thermalUnavailable.textContent = r
            ? `Thermal monitoring is not running (${errorText(r)}).`
            : 'Thermal monitoring is unavailable: the phone did not answer.';
        return;
    }
    if (!thermalProfileLoaded) {
        fetchThermalProfile();
    }
    if (r.cpuTemperatureC <= 0 && r.batteryTemperatureC <= 0) {
        elements.throttleDetail.textContent = 'Waiting for the first reading…';
        return;
    }

    showTemperature(elements.cpuMeter, elements.cpuTemp, elements.cpuLevel, r.cpuTemperatureC, r.cpuLevel, thermalLimits.cpuEmergency);
    showTemperature(elements.batteryMeter, elements.batteryTemp, elements.batteryLevel,
        r.batteryTemperatureC, r.batteryLevel, thermalLimits.batteryEmergency);

    // Most phones have no charging switch LensDaemon can write: chargingDisabled
    // then only says the battery is too warm to charge, and the phone keeps charging
    const noChargeSwitch = !!r.chargeControl && r.chargeControl.available === false;
    const battery = [`Battery ${r.batteryPercent}%`, r.isCharging ? 'charging' : 'not charging'];
    if (r.chargingDisabled && !noChargeSwitch) {
        battery.push('charging held back to keep it cool');
    } else if (r.chargingDisabled && r.isCharging) {
        battery.push('too warm to charge: unplug the charger (this phone doesn\'t let LensDaemon switch charging off)');
    } else if (r.chargingDisabled) {
        battery.push('too warm to charge: let it cool before plugging it in');
    }
    elements.batteryDetail.textContent = battery.join(' · ');
    // The phone's advice (unplug at the charge target, plug in when low), unless the line above gives it
    const advice = r.chargeControl?.recommendation || '';
    elements.chargeAdvice.textContent = r.chargingDisabled && noChargeSwitch ? '' : advice;

    const actions = (r.activeActions || []).filter(a => a !== 'NONE').map(action => {
        if (action === 'REDUCE_BITRATE') return `bitrate −${r.bitrateReductionPercent}%`;
        if (action === 'DISABLE_CHARGING' && noChargeSwitch) return r.isCharging ? 'unplug the charger' : 'too warm to charge';
        return THROTTLE_LABELS[action] || action.toLowerCase();
    });
    elements.throttleDetail.textContent = actions.length > 0
        ? `Throttling: ${actions.join(', ')}`
        : (r.isThrottling ? `Throttling (${r.statusText})` : 'Not throttling');
}

function showTemperature(meter, valueEl, chip, tempC, level, emergencyC) {
    const floor = 20;
    const percent = Math.max(0, Math.min(100, ((tempC - floor) / Math.max(1, emergencyC - floor)) * 100));
    const cls = LEVEL_CLASS[level] || 'normal';
    meter.style.width = `${percent}%`;
    meter.className = `meter-fill${cls === 'normal' ? '' : ' ' + cls}`;
    valueEl.textContent = `${tempC.toFixed(1)}°C`;
    chip.textContent = (level || '--').toLowerCase();
    chip.className = `level-chip ${cls}`;
}

// ==================== Network storage ====================

const UPLOAD_STATES = { IDLE: 'Idle', UPLOADING: 'Uploading', PAUSED: 'Paused', ERROR: 'Error' };

async function fetchUploadStatus() {
    const r = await apiCall('/api/upload/status');
    const available = !!r && !r.error;
    elements.uploadUnavailable.hidden = available;
    elements.uploadBody.hidden = !available;
    if (!available) {
        elements.uploadUnavailable.textContent = r
            ? `The upload service is not running (${errorText(r)}).`
            : 'The upload service is unavailable: the phone did not answer.';
        return;
    }

    const configuredBefore = `${uploadConfigured.S3}/${uploadConfigured.SMB}`;
    uploadConfigured.S3 = !!r.isS3Configured;
    uploadConfigured.SMB = !!r.isSmbConfigured;
    if (`${uploadConfigured.S3}/${uploadConfigured.SMB}` !== configuredBefore) {
        renderRecordings();
    }

    const q = r.queue || {};
    elements.uploadState.textContent = UPLOAD_STATES[r.state] || r.state;
    elements.uploadQueue.textContent = `${q.pendingCount || 0} waiting · ${q.uploadingCount || 0} uploading · ` +
        `${q.completedCount || 0} done · ${q.failedCount || 0} failed`;
    elements.uploadCurrent.textContent = r.currentFile ? `Uploading ${r.currentFile} (${r.currentProgress || 0}%)` : '';
    elements.btnUploadStart.disabled = r.state === 'UPLOADING';
    elements.btnUploadStop.disabled = r.state !== 'UPLOADING';
    elements.btnUploadRetry.disabled = !q.failedCount;
    elements.btnUploadClear.disabled = !q.pendingCount;

    if (q.failedCount > 0) {
        fetchFailedUploads();
    } else {
        renderFailedUploads([]);
    }
}

/** The uploads that used up their attempts, with why (GET /api/upload/queue "failed"). */
async function fetchFailedUploads() {
    const r = await apiCall('/api/upload/queue');
    // Older phones don't keep failed uploads
    if (!r || r.error || !Array.isArray(r.failed)) return;
    renderFailedUploads(r.failed);
}

function renderFailedUploads(failed) {
    // Redrawn only when something changed, so a click on Remove isn't lost to a poll
    const key = failed.map(task => `${task.id}|${task.error}`).join('\n');
    if (key === failedUploadsKey) return;
    failedUploadsKey = key;
    const list = elements.uploadFailed;
    list.innerHTML = '';
    list.hidden = failed.length === 0;
    failed.forEach(task => {
        const item = document.createElement('div');
        item.className = 'list-item';
        const main = document.createElement('div');
        main.className = 'list-item-main';
        const name = document.createElement('div');
        name.className = 'list-item-name';
        name.textContent = `${task.fileName} → ${task.destination}`;
        const meta = document.createElement('div');
        meta.className = 'list-item-meta failed-reason';
        meta.textContent = `Failed: ${task.error || 'no reason given'}`;
        main.append(name, meta);
        const actions = document.createElement('div');
        actions.className = 'list-item-actions';
        actions.appendChild(actionButton('Remove', 'btn btn-small btn-danger-outline', () => removeFailedUpload(task)));
        item.append(main, actions);
        list.appendChild(item);
    });
}

async function retryFailedUploads() {
    const result = await apiCall('/api/upload/retry', 'POST');
    if (succeeded(result) && typeof result.retried === 'number') {
        notify(`Retrying ${result.retried} upload${result.retried === 1 ? '' : 's'}`, 'success');
    } else if (succeeded(result)) {
        notify(result.message || 'Retrying the failed uploads', 'success');
    } else {
        notify('Retry failed: ' + errorText(result), 'error');
    }
    fetchUploadStatus();
}

async function removeFailedUpload(task) {
    const result = await apiCall(`/api/upload/task/${encodeURIComponent(task.id)}`, 'DELETE');
    if (succeeded(result)) {
        notify(`${task.fileName} removed from the uploads`, 'success');
    } else {
        notify('Could not remove the upload: ' + errorText(result), 'error');
    }
    fetchUploadStatus();
}

async function uploadAction(endpoint) {
    const result = await apiCall(endpoint, 'POST');
    if (succeeded(result)) {
        notify(result.message || 'Done', 'success');
    } else {
        notify(errorText(result), 'error');
    }
    fetchUploadStatus();
}

// Saved settings are shown without their secrets: enter them again to change anything
async function fetchStorageConfig() {
    const [s3, smb] = await Promise.all([apiCall('/api/upload/s3/config'), apiCall('/api/upload/smb/config')]);
    if (s3 && !s3.error) {
        if (s3.configured) {
            elements.s3Backend.value = s3.backend || 'S3';
            elements.s3Endpoint.value = s3.endpoint || '';
            elements.s3Region.value = s3.region || '';
            elements.s3Bucket.value = s3.bucket || '';
            elements.s3Prefix.value = s3.pathPrefix || '';
            elements.s3AccessKey.placeholder = `saved: ${s3.accessKeyId}`;
            elements.s3SecretKey.placeholder = 'saved: enter again to change';
        }
        setSummary(elements.s3Summary, s3.configured ? `${s3.bucket} on ${s3.endpoint}` : 'Not set up', s3.configured);
    }
    if (smb && !smb.error) {
        if (smb.configured) {
            elements.smbServer.value = smb.server || '';
            elements.smbShare.value = smb.share || '';
            elements.smbUsername.value = smb.username || '';
            elements.smbDomain.value = smb.domain || '';
            elements.smbPrefix.value = smb.pathPrefix || '';
            elements.smbPassword.placeholder = 'saved: enter again to change';
        }
        setSummary(elements.smbSummary, smb.configured ? `\\\\${smb.server}\\${smb.share}` : 'Not set up', smb.configured);
    }
}

function setSummary(el, text, configured) {
    el.textContent = text;
    el.classList.toggle('configured', !!configured);
}

function s3FormValues() {
    return {
        backend: elements.s3Backend.value,
        endpoint: elements.s3Endpoint.value.trim(),
        region: elements.s3Region.value.trim(),
        bucket: elements.s3Bucket.value.trim(),
        accessKeyId: elements.s3AccessKey.value.trim(),
        secretAccessKey: elements.s3SecretKey.value,
        pathPrefix: elements.s3Prefix.value.trim()
    };
}

function smbFormValues() {
    return {
        server: elements.smbServer.value.trim(),
        share: elements.smbShare.value.trim(),
        username: elements.smbUsername.value.trim(),
        password: elements.smbPassword.value,
        domain: elements.smbDomain.value.trim(),
        pathPrefix: elements.smbPrefix.value.trim()
    };
}

/** ok: true, false, or null while waiting for an answer. */
function showResult(el, ok, text) {
    el.textContent = text;
    el.className = ok === null ? 'result-line' : `result-line ${ok ? 'success' : 'error'}`;
}

async function saveS3() {
    const values = s3FormValues();
    if (!values.bucket || !values.accessKeyId || !values.secretAccessKey) {
        showResult(elements.s3Result, false, 'Enter the bucket, access key ID and secret access key');
        return;
    }
    const result = await apiCall('/api/upload/s3/config', 'POST', values);
    if (succeeded(result)) {
        showResult(elements.s3Result, true, `Saved: ${result.bucket}${result.endpoint ? ' on ' + result.endpoint : ''}`);
        elements.s3AccessKey.value = '';
        elements.s3SecretKey.value = '';
        fetchStorageConfig();
        fetchUploadStatus();
    } else {
        showResult(elements.s3Result, false, 'Not saved: ' + errorText(result));
    }
}

async function testS3() {
    const values = s3FormValues();
    // Test what is in the form when the keys are typed in, else the saved settings
    const body = values.secretAccessKey ? values : null;
    showResult(elements.s3Result, null, 'Testing…');
    const result = await apiCall('/api/upload/s3/test', 'POST', body);
    showResult(elements.s3Result, succeeded(result), succeeded(result) ? (result.message || 'Connection works') : errorText(result));
}

async function saveSmb() {
    const values = smbFormValues();
    if (!values.server || !values.share || !values.username || !values.password) {
        showResult(elements.smbResult, false, 'Enter the server, share, username and password');
        return;
    }
    const result = await apiCall('/api/upload/smb/config', 'POST', values);
    if (succeeded(result)) {
        showResult(elements.smbResult, true, `Saved: \\\\${result.server}\\${result.share}`);
        elements.smbPassword.value = '';
        fetchStorageConfig();
        fetchUploadStatus();
    } else {
        showResult(elements.smbResult, false, 'Not saved: ' + errorText(result));
    }
}

async function testSmb() {
    const values = smbFormValues();
    const body = values.password ? values : null;
    showResult(elements.smbResult, null, 'Testing…');
    const result = await apiCall('/api/upload/smb/test', 'POST', body);
    showResult(elements.smbResult, succeeded(result), succeeded(result) ? (result.message || 'Connection works') : errorText(result));
}

async function removeStorage(kind) {
    const label = kind === 's3' ? 'S3' : 'SMB';
    if (!confirm(`Remove the saved ${label} settings?`)) return;
    const result = await apiCall(`/api/upload/${kind}/config`, 'DELETE');
    const resultEl = kind === 's3' ? elements.s3Result : elements.smbResult;
    showResult(resultEl, succeeded(result), succeeded(result) ? `${label} settings removed` : errorText(result));
    if (kind === 's3') {
        elements.s3AccessKey.placeholder = '';
        elements.s3SecretKey.placeholder = '';
    } else {
        elements.smbPassword.placeholder = '';
    }
    fetchStorageConfig();
    fetchUploadStatus();
}

// ==================== Kiosk ====================

const SCREEN_MODES = { PREVIEW: 'Camera preview', OFF: 'Off (phone\'s screen timeout)', DIM: 'Dimmed', BLACK: 'Black' };

async function fetchKioskStatus() {
    const [r, config] = await Promise.all([apiCall('/api/kiosk/status'), apiCall('/api/kiosk/config')]);
    const available = !!r && !r.error;
    elements.kioskUnavailable.hidden = available;
    elements.kioskBody.hidden = !available;
    if (!available) {
        elements.kioskUnavailable.textContent = r
            ? `Kiosk mode is not available (${errorText(r)}).`
            : 'Kiosk mode is unavailable: the phone did not answer.';
        return;
    }

    kioskEnabled = !!r.kioskEnabled;
    kioskDeviceOwner = !!r.isDeviceOwner;
    elements.kioskEnabled.textContent = kioskEnabled ? 'On' : 'Off';
    elements.kioskPinned.textContent = r.isLockTaskActive ? 'Yes' : 'No';
    elements.kioskOwner.textContent = r.isDeviceOwner ? 'Yes' : 'No';
    elements.kioskScreen.textContent = SCREEN_MODES[r.screenMode] || r.screenMode || '--';
    if (config && !config.error) {
        kioskConfig = config;
        showKioskConfig(config);
    }

    elements.kioskHint.textContent = kioskHint(r, kioskConfig);
    elements.btnKioskToggle.textContent = kioskEnabled ? 'Turn Off' : 'Turn On';
    elements.btnKioskToggle.className = `btn ${kioskEnabled ? 'btn-danger' : 'btn-primary'}`;
    elements.btnKioskToggle.disabled = false;
}

/**
 * What to know at the phone: how to leave kiosk mode (from the exit settings),
 * and whether LensDaemon comes back by itself after a reboot, which takes
 * Device Owner (Android won't open its screen, or on Android 11+ its camera,
 * from the background otherwise) and comes after the phone's first unlock.
 */
function kioskHint(status, config) {
    const parts = [];
    if (!status.isDeviceOwner) {
        parts.push('LensDaemon can only lock the phone to its screen as device owner: ' +
            'adb shell dpm set-device-owner com.lensdaemon/.AdminReceiver (see the setup wizard).');
    } else if (kioskEnabled) {
        if (!status.isLockTaskActive) {
            parts.push('Kiosk mode is on: LensDaemon pins itself as soon as its screen is open on the phone.');
        }
        parts.push(exitHint(config));
    }
    if (kioskEnabled && status.autoStartEnabled) {
        parts.push(status.isDeviceOwner
            ? 'LensDaemon starts by itself after a reboot (on a phone with a screen lock, only once it is unlocked).'
            : 'Starting by itself after a reboot needs Device Owner (and a phone without a screen lock).');
    }
    return parts.join(' ');
}

function exitHint(config) {
    const security = config && config.security;
    // Without the settings (older phones), the default gesture
    if (!security) return 'To leave kiosk mode at the phone, hold Volume Up + Volume Down for 5 s.';
    if (security.allowedExitGesture === false) return 'The exit gesture is off: turn kiosk mode off from this dashboard.';
    const seconds = +((security.exitGestureTimeoutMs ?? 5000) / 1000).toFixed(1);
    return `To leave kiosk mode at the phone, hold Volume Up + Volume Down for ${seconds} s` +
        `${security.exitPinSet ? ', then enter the exit PIN' : ''}.`;
}

/** The kiosk settings form, from GET /api/kiosk/config; fields being edited are left alone. */
function showKioskConfig(config) {
    const security = config.security || {};
    const pinSet = !!security.exitPinSet;
    elements.kioskPinState.textContent = pinSet ? 'Exit PIN: set' : 'Exit PIN: not set';
    elements.kioskPinState.classList.toggle('configured', pinSet);
    elements.kioskExitPin.placeholder = pinSet ? 'Set: type a new one to change it' : 'Not set';
    elements.btnKioskPinRemove.disabled = !pinSet;
    if (kioskFormEdited) return;

    const autoStart = config.autoStart || {};
    elements.kioskStartRtsp.checked = !!autoStart.startRtsp;
    elements.kioskStartRecording.checked = !!autoStart.startRecording;
    elements.kioskDelay.value = autoStart.delaySeconds ?? 5;
    const mode = (config.screen && config.screen.mode) || 'PREVIEW';
    setSelectValue(elements.kioskScreenMode, mode, SCREEN_MODES[mode] || mode);
    elements.kioskExitGesture.checked = security.allowedExitGesture !== false;
    elements.kioskGestureSeconds.value = +((security.exitGestureTimeoutMs ?? 5000) / 1000).toFixed(1);
    elements.kioskGestureSeconds.disabled = !elements.kioskExitGesture.checked;
}

/**
 * Save the kiosk settings (PUT /api/kiosk/config), with the exit PIN when one
 * is typed. The phone checks them (PIN of 4-12 digits, known screen mode, no
 * negative time) and refuses the whole change with its reason otherwise.
 */
async function saveKioskSettings() {
    const delay = Number(elements.kioskDelay.value);
    if (elements.kioskDelay.value === '' || !Number.isInteger(delay) || delay < 0 || delay > MAX_BOOT_DELAY_S) {
        showResult(elements.kioskResult, false, `The start delay must be a whole number of seconds from 0 to ${MAX_BOOT_DELAY_S}`);
        return;
    }
    const body = {
        autoStart: {
            startRtsp: elements.kioskStartRtsp.checked,
            startRecording: elements.kioskStartRecording.checked,
            delaySeconds: delay
        },
        screen: { mode: elements.kioskScreenMode.value },
        security: { allowedExitGesture: elements.kioskExitGesture.checked }
    };
    if (elements.kioskExitGesture.checked) {
        const hold = Number(elements.kioskGestureSeconds.value);
        if (elements.kioskGestureSeconds.value === '' || !(hold >= 1 && hold <= MAX_GESTURE_HOLD_S)) {
            showResult(elements.kioskResult, false, `Hold the exit gesture for 1 to ${MAX_GESTURE_HOLD_S} seconds`);
            return;
        }
        body.security.exitGestureTimeoutMs = Math.round(hold * 1000);
    }
    const pin = elements.kioskExitPin.value.trim();
    if (pin) body.security.exitPin = pin;

    showResult(elements.kioskResult, null, 'Saving…');
    const result = await apiCall('/api/kiosk/config', 'PUT', body);
    if (succeeded(result)) {
        kioskFormEdited = false;
        elements.kioskExitPin.value = '';
        showResult(elements.kioskResult, true, pin ? 'Saved, with the new exit PIN' : 'Saved');
    } else {
        showResult(elements.kioskResult, false, 'Not saved: ' + errorText(result));
    }
    fetchKioskStatus();
}

async function setExitPin() {
    const pin = elements.kioskExitPin.value.trim();
    if (!pin) {
        showResult(elements.kioskResult, false, 'Type the new exit PIN first');
        elements.kioskExitPin.focus();
        return;
    }
    const result = await apiCall('/api/kiosk/config', 'PUT', { security: { exitPin: pin } });
    if (succeeded(result)) {
        elements.kioskExitPin.value = '';
        showResult(elements.kioskResult, true, 'Exit PIN set: the phone asks for it after the exit gesture');
    } else {
        showResult(elements.kioskResult, false, 'Exit PIN not set: ' + errorText(result));
    }
    fetchKioskStatus();
}

async function removeExitPin() {
    if (!confirm('Remove the exit PIN? Anyone at the phone can then leave kiosk mode with the exit gesture.')) return;
    const result = await apiCall('/api/kiosk/config', 'PUT', { security: { exitPin: '' } });
    showResult(elements.kioskResult, succeeded(result),
        succeeded(result) ? 'Exit PIN removed' : 'Exit PIN not removed: ' + errorText(result));
    fetchKioskStatus();
}

async function toggleKiosk() {
    const enable = !kioskEnabled;
    let question = 'Turn off kiosk mode? LensDaemon will unpin itself and no longer start on boot.';
    if (enable) {
        question = kioskDeviceOwner
            ? 'Turn on kiosk mode? LensDaemon locks the phone to its screen and starts by itself after a reboot.'
            : 'Turn on kiosk mode? Without Device Owner, LensDaemon can\'t lock the screen, and Android won\'t open it ' +
                'or (Android 11+) its camera by itself after a reboot.';
    }
    if (!confirm(question)) return;
    const result = await apiCall(enable ? '/api/kiosk/enable' : '/api/kiosk/disable', 'POST');
    if (succeeded(result)) {
        notify(result.message || (enable ? 'Kiosk mode on' : 'Kiosk mode off'), enable && result.lockTask === false ? 'info' : 'success');
    } else {
        notify('Kiosk mode not changed: ' + errorText(result), 'error');
    }
    fetchKioskStatus();
}

// ==================== Utility functions ====================

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

function formatFileSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}

function formatGb(gb) {
    return typeof gb === 'number' ? `${gb.toFixed(1)} GB` : '--';
}

function formatDegrees(celsius) {
    return typeof celsius === 'number' ? `${+celsius.toFixed(1)}°C` : '--';
}

function formatDate(timestamp) {
    const date = new Date(timestamp);
    return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function baseName(path) {
    return path.split('/').pop();
}

function emptyNote(text) {
    const note = document.createElement('div');
    note.className = 'empty-note';
    note.textContent = text;
    return note;
}

function actionButton(label, className, onClick) {
    const btn = document.createElement('button');
    btn.className = className;
    btn.textContent = label;
    btn.addEventListener('click', onClick);
    return btn;
}

// ==================== AI Director Functions ====================

// Toggle director enabled state
async function toggleDirector() {
    const enabled = elements.directorEnabled.checked;
    const result = await apiCall(enabled ? '/api/director/enable' : '/api/director/disable', 'POST');

    if (succeeded(result)) {
        fetchStatus();
    } else {
        elements.directorEnabled.checked = !enabled;
        notify('Failed to toggle the director: ' + errorText(result), 'error');
    }
}

// Update director panel enabled/disabled state
function updateDirectorPanelState(enabled) {
    elements.directorPanel.classList.toggle('disabled', !enabled);
}

// Update director status from the /api/status director block
function updateDirectorStatus(director) {
    const wasEnabled = directorEnabled;
    directorEnabled = !!director.enabled;
    directorState = director.state || 'DISABLED';

    elements.directorEnabled.checked = directorEnabled;
    updateDirectorPanelState(directorEnabled);

    // Live updates and lists, however the director came to be enabled
    if (directorEnabled && (!wasEnabled || !directorDataLoaded)) {
        directorDataLoaded = true;
        startDirectorEventStream();
        fetchTakesList();
        fetchScriptFiles();
    } else if (!directorEnabled && wasEnabled) {
        directorDataLoaded = false;
        stopDirectorEventStream();
    }

    elements.directorState.textContent = directorState;
    elements.directorState.className = 'value state-' + directorState.toLowerCase().replace(/_/g, '-');
    elements.directorScene.textContent = director.currentScene || '-';
    elements.directorCue.textContent = director.currentCue || '-';
    elements.directorTake.textContent = director.currentTake > 0 ? `#${director.currentTake}` : '-';

    updateDirectorControls(directorState);
    if (director.stats) {
        updateDirectorStats(director.stats);
    }
    updateSceneProgress(director);
}

// "Scene 2/5 · Cue 3/12" (counted from 1), and the scenes to jump to
function updateSceneProgress(director) {
    const hasScript = typeof director.sceneCount === 'number' && director.sceneCount > 0;
    if (hasScript) {
        const scene = `Scene ${director.sceneIndex + 1}/${director.sceneCount}`;
        const cue = director.cueCount > 0 ? ` · Cue ${Math.min(director.cueIndex + 1, director.cueCount)}/${director.cueCount}` : '';
        const total = director.totalCues ? ` (${director.executedCues || 0} of ${director.totalCues} cues done)` : '';
        elements.sceneProgress.textContent = `${scene}${cue}${total}`;
    } else {
        elements.sceneProgress.textContent = director.currentScene && directorState === 'RUNNING' ? director.currentScene : '-';
    }

    const labels = hasScript && Array.isArray(director.sceneLabels) ? director.sceneLabels : [];
    const key = labels.join('\u0000');
    if (key !== sceneOptionsKey) {
        sceneOptionsKey = key;
        elements.sceneSelect.innerHTML = '';
        if (labels.length === 0) {
            elements.sceneSelect.add(new Option('No script loaded', ''));
        } else {
            labels.forEach((label, i) => elements.sceneSelect.add(new Option(`${i + 1}. ${label}`, String(i))));
        }
    }
    if (labels.length > 0 && document.activeElement !== elements.sceneSelect) {
        elements.sceneSelect.value = String(director.sceneIndex);
    }
    elements.sceneSelect.disabled = labels.length === 0 || !directorEnabled;
}

// Update director control button states based on state
function updateDirectorControls(state) {
    const canStart = state === 'READY' || state === 'PAUSED';
    const canPause = state === 'RUNNING';
    const canStop = state === 'RUNNING' || state === 'PAUSED' || state === 'THERMAL_HOLD';
    const canAdvance = state === 'RUNNING' || state === 'PAUSED';

    elements.btnDirectorStart.disabled = !canStart;
    elements.btnDirectorStart.textContent = state === 'PAUSED' ? 'Resume' : 'Start';
    elements.btnDirectorPause.disabled = !canPause;
    elements.btnDirectorStop.disabled = !canStop;
    elements.btnDirectorAdvance.disabled = !canAdvance;
}

// Update director stats display
function updateDirectorStats(stats) {
    elements.statTotalTakes.textContent = stats.totalTakes || 0;
    const score = stats.averageScore || stats.avgQualityScore || 0;
    elements.statAvgQuality.textContent = score > 0 ? score.toFixed(1) : '-';
    elements.statBestTakes.textContent = stats.goodTakes || stats.bestTakeCount || 0;
    const rate = stats.cueSuccessRate || 0;
    elements.statCueSuccess.textContent = rate > 0 ? `${(rate * 100).toFixed(0)}%` : '-';
}

// Load script into director
async function loadScript() {
    const script = elements.scriptTextarea.value.trim();
    if (!script) {
        notify('Enter a script first', 'error');
        return;
    }

    const result = await apiCall('/api/director/script', 'POST', { script });
    if (succeeded(result)) {
        const problems = result.errors || [];
        notify(`Script loaded: ${result.scenes} scene${result.scenes === 1 ? '' : 's'}, ${result.totalCues} cues` +
            (result.estimatedDuration ? `, about ${result.estimatedDuration}` : '') +
            (problems.length ? ` (${problems.length} problem${problems.length === 1 ? '' : 's'} listed under the script)` : ''),
        'success');
        showScriptProblems(problems);
    } else {
        // The director keeps the script it had: say why this one wasn't taken
        notify('Failed to load script: ' + errorText(result), 'error');
        showScriptProblems([`Not loaded: ${errorText(result)}`]);
    }
    fetchStatus();
}

/** The problems the phone found in the script (lines that aren't cues, empty scenes, shots it can't do). */
function showScriptProblems(problems) {
    const list = elements.scriptProblems;
    list.innerHTML = '';
    (problems || []).forEach(problem => {
        const item = document.createElement('li');
        item.textContent = problem;
        list.appendChild(item);
    });
    list.hidden = list.childElementCount === 0;
}

// Clear script
async function clearScript() {
    elements.scriptTextarea.value = '';
    showScriptProblems([]);
    loadedScriptFileName = null;
    elements.scriptFilesList.querySelectorAll('.script-file-item').forEach(item => item.classList.remove('active'));
    const result = await apiCall('/api/director/script/clear', 'POST');
    if (!succeeded(result)) {
        notify('Failed to clear the script: ' + errorText(result), 'error');
    }
    fetchStatus();
}

// Start, or resume when paused
async function startDirector() {
    const resuming = directorState === 'PAUSED';
    const result = await apiCall(resuming ? '/api/director/resume' : '/api/director/start', 'POST');
    if (!succeeded(result)) {
        notify(`Failed to ${resuming ? 'resume' : 'start'} the director: ` + errorText(result), 'error');
    }
    fetchStatus();
}

async function pauseDirector() {
    const result = await apiCall('/api/director/pause', 'POST');
    if (!succeeded(result)) {
        notify('Failed to pause the director: ' + errorText(result), 'error');
    }
    fetchStatus();
}

async function stopDirector() {
    const result = await apiCall('/api/director/stop', 'POST');
    if (!succeeded(result)) {
        notify('Failed to stop the director: ' + errorText(result), 'error');
    }
    fetchStatus();
    fetchTakesList();
}

async function advanceDirector() {
    const result = await apiCall('/api/director/advance', 'POST');
    if (!succeeded(result)) {
        notify(result?.message === 'No more cues' ? 'That was the last cue' : 'Failed to advance: ' + errorText(result), 'error');
    }
    fetchStatus();
}

async function jumpToScene() {
    const sceneIndex = parseInt(elements.sceneSelect.value, 10);
    if (Number.isNaN(sceneIndex)) return;
    const result = await apiCall('/api/director/scene', 'POST', { sceneIndex });
    if (!succeeded(result)) {
        notify('Could not go to that scene: ' + errorText(result), 'error');
    }
    elements.sceneSelect.blur();
    fetchStatus();
}

async function executeQuickCue(cue) {
    const result = await apiCall('/api/director/cue', 'POST', { cue });
    if (!succeeded(result)) {
        notify(`Cue ${cue} failed: ` + errorText(result), 'error');
    }
}

// Fetch takes list
async function fetchTakesList() {
    const result = await apiCall('/api/director/takes');
    if (result && Array.isArray(result.takes)) {
        renderTakesList(result.takes);
    }
}

const TAKE_MARKS = ['UNMARKED', 'GOOD', 'BAD', 'CIRCLE', 'HOLD'];

// Render takes list, newest first
function renderTakesList(takes) {
    const list = elements.takesList;
    list.innerHTML = '';
    if (!takes || takes.length === 0) {
        const none = document.createElement('div');
        none.className = 'no-takes';
        none.textContent = 'No takes recorded yet';
        list.appendChild(none);
        return;
    }

    takes.slice().reverse().forEach(take => {
        const item = document.createElement('div');
        item.className = `take-item${take.suggested ? ' best' : ''}`;

        const number = document.createElement('div');
        number.className = 'take-number';
        number.textContent = `#${take.takeNumber}`;

        const info = document.createElement('div');
        info.className = 'take-info';
        const scene = document.createElement('div');
        scene.className = 'take-scene';
        scene.textContent = take.sceneLabel || take.sceneId || 'Scene';
        const detail = document.createElement('div');
        detail.className = 'take-scene';
        detail.textContent = `${take.durationFormatted || formatDuration((take.durationMs || 0) / 1000)} · ` +
            `${take.cuesExecuted || 0} cues${take.cuesFailed ? `, ${take.cuesFailed} failed` : ''}`;
        info.append(scene, detail);
        if (take.suggested) {
            const suggested = document.createElement('span');
            suggested.className = 'take-suggested';
            suggested.textContent = 'Suggested';
            info.appendChild(suggested);
        }

        const mark = document.createElement('select');
        mark.title = 'Mark this take';
        mark.setAttribute('aria-label', `Mark take ${take.takeNumber}`);
        TAKE_MARKS.forEach(m => mark.add(new Option(m === 'UNMARKED' ? 'Unmarked' : m.charAt(0) + m.slice(1).toLowerCase(), m)));
        mark.value = TAKE_MARKS.includes(take.manualMark) ? take.manualMark : 'UNMARKED';
        mark.addEventListener('change', () => markTake(take.takeNumber, mark.value));

        const score = document.createElement('div');
        score.className = `quality-score ${getQualityClass(take.qualityScore || 0)}`;
        score.textContent = (take.qualityScore || 0).toFixed(1);

        item.append(number, info, mark, score);
        list.appendChild(item);
    });
}

async function markTake(takeNumber, quality) {
    const result = await apiCall('/api/director/takes/mark', 'POST', { takeNumber, quality });
    if (!succeeded(result)) {
        notify('Could not mark the take: ' + errorText(result), 'error');
    }
    fetchTakesList();
}

// Get quality class based on score
function getQualityClass(score) {
    if (score >= 9.0) return 'excellent';
    if (score >= 7.0) return 'good';
    if (score >= 5.0) return 'fair';
    return 'poor';
}

// Server-Sent Events for director updates
function startDirectorEventStream() {
    stopDirectorEventStream();
    try {
        directorEventSource = new EventSource('/api/director/events');
        directorEventSource.onmessage = (event) => {
            try {
                handleDirectorEvent(JSON.parse(event.data));
            } catch (e) {
                console.error('Failed to parse director event:', e);
            }
        };
        directorEventSource.onerror = () => {
            // Reconnect after a pause while the director stays enabled
            stopDirectorEventStream();
            directorReconnectTimer = setTimeout(() => {
                directorReconnectTimer = null;
                if (directorEnabled && !document.hidden) {
                    startDirectorEventStream();
                } else {
                    // Picked up again by the next status poll that finds the director enabled
                    directorDataLoaded = false;
                }
            }, 5000);
        };
    } catch (e) {
        console.error('Failed to start director event stream:', e);
    }
}

function stopDirectorEventStream() {
    if (directorReconnectTimer) {
        clearTimeout(directorReconnectTimer);
        directorReconnectTimer = null;
    }
    if (directorEventSource) {
        directorEventSource.close();
        directorEventSource = null;
    }
}

// Director events, as DirectorApiHandler sends them
function handleDirectorEvent(data) {
    switch (data.type) {
        case 'state':
        case 'state_changed':
        case 'script_loaded':
        case 'take_started':
            fetchStatus();
            break;
        case 'cue_executed':
            elements.directorCue.textContent = data.cueText || '-';
            if (data.success === false) {
                notify(`Cue failed: ${data.cueText}`, 'error');
            }
            break;
        case 'take_ended':
            fetchTakesList();
            fetchStatus();
            break;
        case 'error':
            notify(`Director: ${data.message}`, 'error');
            break;
        case 'thermal_warning':
            notify(`Director: ${data.action} (${data.temperature}°C)`, 'error');
            fetchStatus();
            break;
        default:
            break;
    }
}

// ==================== Script File Browser Functions ====================

async function fetchScriptFiles() {
    const result = await apiCall('/api/director/scripts');
    if (result && Array.isArray(result.scripts)) {
        renderScriptFiles(result.scripts);
    }
}

function renderScriptFiles(scripts) {
    const list = elements.scriptFilesList;
    list.innerHTML = '';
    if (!scripts || scripts.length === 0) {
        const none = document.createElement('div');
        none.className = 'no-scripts';
        none.textContent = 'No saved scripts';
        list.appendChild(none);
        return;
    }

    scripts.forEach(script => {
        const item = document.createElement('div');
        item.className = `script-file-item${loadedScriptFileName === script.fileName ? ' active' : ''}`;
        item.dataset.filename = script.fileName;

        const name = document.createElement('div');
        name.className = 'script-file-name';
        name.textContent = script.fileName;

        const meta = document.createElement('div');
        meta.className = 'script-file-meta';
        const size = document.createElement('span');
        size.className = 'script-file-size';
        size.textContent = formatFileSize(script.sizeBytes || 0);
        const date = document.createElement('span');
        date.className = 'script-file-date';
        date.textContent = script.lastModified ? formatDate(script.lastModified) : '';
        const del = document.createElement('button');
        del.className = 'btn-delete-script';
        del.title = 'Delete';
        del.textContent = 'X';
        del.addEventListener('click', (event) => {
            event.stopPropagation();
            deleteScriptFile(script.fileName);
        });
        meta.append(size, date, del);

        item.append(name, meta);
        item.addEventListener('click', () => loadScriptFromFile(script.fileName));
        list.appendChild(item);
    });
}

// Open a saved script: it goes into the editor and is loaded into the director.
// The director refuses it while a script runs or is paused: the file still opens,
// with "loaded": false and the reason in "loadError".
async function loadScriptFromFile(fileName) {
    const result = await apiCall(`/api/director/scripts/${encodeURIComponent(fileName)}`);
    if (succeeded(result)) {
        elements.scriptTextarea.value = result.content || '';
        loadedScriptFileName = fileName;
        elements.scriptFilename.value = fileName.replace(/\.[^.]+$/, '');
        elements.scriptFilesList.querySelectorAll('.script-file-item').forEach(item => {
            item.classList.toggle('active', item.dataset.filename === fileName);
        });
        if (result.loaded) {
            notify(`Loaded ${fileName}: ${result.scenes} scenes, ${result.totalCues} cues`, 'success');
            showScriptProblems(result.errors);
        } else if (result.loadError) {
            notify(`Opened ${fileName} (not loaded: ${result.loadError})`, 'info');
            showScriptProblems([`Not loaded: ${result.loadError}`]);
        } else {
            notify(`Opened ${fileName}`, 'success');
            showScriptProblems([]);
        }
        fetchStatus();
    } else {
        notify('Failed to open the script: ' + errorText(result), 'error');
    }
}

async function saveScriptFile() {
    const script = elements.scriptTextarea.value.trim();
    if (!script) {
        notify('There is no script to save', 'error');
        return;
    }

    let fileName = elements.scriptFilename.value.trim();
    if (!fileName) {
        notify('Enter a file name', 'error');
        elements.scriptFilename.focus();
        return;
    }
    if (!fileName.endsWith('.txt')) {
        fileName += '.txt';
    }

    const result = await apiCall('/api/director/scripts/save', 'POST', { fileName, script });
    if (succeeded(result)) {
        loadedScriptFileName = result.fileName || fileName;
        notify(`Saved ${loadedScriptFileName}`, 'success');
        fetchScriptFiles();
    } else {
        notify('Failed to save the script: ' + errorText(result), 'error');
    }
}

async function deleteScriptFile(fileName) {
    if (!confirm(`Delete script "${fileName}"?`)) return;

    const result = await apiCall(`/api/director/scripts/${encodeURIComponent(fileName)}`, 'DELETE');
    if (succeeded(result)) {
        if (loadedScriptFileName === fileName) {
            loadedScriptFileName = null;
        }
        fetchScriptFiles();
    } else {
        notify('Failed to delete the script: ' + errorText(result), 'error');
    }
}
