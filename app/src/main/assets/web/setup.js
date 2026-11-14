// LensDaemon Setup Wizard
'use strict';

let currentStep = 1;
let selectedPreset = null;
let thermalPollInterval = null;
let isTestStreaming = false;
let startedTestRtsp = false;

/**
 * Fetch JSON from the API. Resolves to { ok, status, data }: ok only for a 2xx
 * answer that doesn't report success: false. Rejects when the phone can't be reached.
 */
function api(url, method, body) {
    const options = { method: method || 'GET', headers: {} };
    if (body !== undefined && body !== null) {
        options.headers['Content-Type'] = 'application/json; charset=utf-8';
        options.body = JSON.stringify(body);
    }
    return fetch(url, options).then(function(r) {
        return r.json().catch(function() { return {}; }).then(function(data) {
            data = data || {};
            return { ok: r.ok && data.success !== false, status: r.status, data: data };
        });
    });
}

function errorOf(result) {
    return (result.data && (result.data.error || result.data.message)) || ('HTTP ' + result.status);
}

// ==================== Navigation ====================

function goToStep(step) {
    // Leaving the test step ends the test stream it started
    if (currentStep === 4 && step !== 4 && isTestStreaming) {
        stopTestStream();
    }

    // Mark previous steps as done
    for (let i = 1; i < step; i++) {
        const el = document.querySelector('.step[data-step="' + i + '"]');
        if (el) {
            el.classList.remove('active');
            el.classList.add('done');
        }
    }

    // Mark current step as active
    const activeStep = document.querySelector('.step[data-step="' + step + '"]');
    if (activeStep) {
        activeStep.classList.remove('done');
        activeStep.classList.add('active');
    }

    // Mark future steps as inactive
    for (let i = step + 1; i <= 5; i++) {
        const el = document.querySelector('.step[data-step="' + i + '"]');
        if (el) {
            el.classList.remove('active', 'done');
        }
    }

    // Show/hide step content
    document.querySelectorAll('.step-content').forEach(function(el) {
        el.classList.remove('active');
    });
    const stepContent = document.getElementById('step-' + step);
    if (stepContent) {
        stepContent.classList.add('active');
    }

    currentStep = step;

    // Run step-specific init
    if (step === 1) runChecks();
    if (step === 4) initTestStep();
    if (step === 5) updateSummary();
}

// ==================== Step 1: Device Checks ====================

function runChecks() {
    checkDevice();
    checkKioskStatus();
    checkCamera();
    checkThermalProfile();
}

function setCheckStatus(id, status, detail) {
    const icon = document.getElementById(id + '-icon');
    const detailEl = document.getElementById(id + '-detail');
    if (icon) {
        icon.className = 'check-icon ' + status;
        icon.textContent = status === 'pass' ? '✓' : status === 'fail' ? '✗' : status === 'warn' ? '!' : '...';
    }
    if (detailEl) {
        detailEl.textContent = detail;
    }
}

function checkDevice() {
    api('/api/device')
        .then(function(result) {
            if (!result.ok) {
                setCheckStatus('check-device', 'fail', 'Device API error: ' + errorOf(result));
                return;
            }
            const data = result.data;
            setCheckStatus('check-device', 'pass', data.manufacturer + ' ' + data.model + ' (Android ' + data.androidVersion + ')');
        })
        .catch(function() {
            setCheckStatus('check-device', 'fail', 'Cannot reach device API');
        });
}

function checkKioskStatus() {
    api('/api/kiosk/status')
        .then(function(result) {
            if (!result.ok) {
                // The kiosk service is not answering: that says nothing about Device Owner
                setCheckStatus('check-kiosk', 'fail', 'Kiosk service not available: ' + errorOf(result));
                document.getElementById('device-owner-help').style.display = 'none';
            } else if (result.data.isDeviceOwner) {
                setCheckStatus('check-kiosk', 'pass', 'Device Owner is set. Kiosk mode available.');
                document.getElementById('device-owner-help').style.display = 'none';
            } else {
                setCheckStatus('check-kiosk', 'warn', 'Device Owner not set. Required to lock the phone to LensDaemon.');
                document.getElementById('device-owner-help').style.display = 'block';
            }
            // Proceeding is still possible: without Device Owner, kiosk mode starts
            // LensDaemon on boot but cannot lock the screen
            enableStep1Next();
        })
        .catch(function() {
            setCheckStatus('check-kiosk', 'fail', 'Cannot reach the kiosk API');
            enableStep1Next();
        });
}

function checkCamera() {
    api('/api/status')
        .then(function(result) {
            const camera = result.data.camera;
            if (!result.ok || !camera) {
                setCheckStatus('check-camera', 'fail', 'Status API error: ' + errorOf(result));
            } else if (camera.currentLens && camera.currentLens !== 'none') {
                setCheckStatus('check-camera', 'pass', 'Camera open (' + camera.currentLens.toLowerCase().replace('_', ' ') + ' lens)');
            } else {
                setCheckStatus('check-camera', 'warn', 'Camera not open yet: open LensDaemon on the phone and allow the camera');
            }
        })
        .catch(function() {
            setCheckStatus('check-camera', 'fail', 'Cannot reach API');
        });
}

function checkThermalProfile() {
    api('/api/thermal/profile')
        .then(function(result) {
            if (!result.ok || !result.data.profile) {
                setCheckStatus('check-thermal', 'warn', 'Thermal profile not available (using defaults)');
                return;
            }
            setCheckStatus('check-thermal', 'pass', result.data.profile.displayName + ' (' + result.data.source + ')');
        })
        .catch(function() {
            setCheckStatus('check-thermal', 'warn', 'Thermal profile not available (using defaults)');
        });
}

function enableStep1Next() {
    document.getElementById('btn-step1-next').disabled = false;
}

// ==================== Step 2: Preset Selection ====================

function selectPreset(preset) {
    selectedPreset = preset;

    document.getElementById('preset-appliance').classList.remove('selected');
    document.getElementById('preset-interactive').classList.remove('selected');

    if (preset === 'appliance') {
        document.getElementById('preset-appliance').classList.add('selected');
    } else {
        document.getElementById('preset-interactive').classList.add('selected');
    }

    document.getElementById('btn-step2-next').disabled = false;
}

// ==================== Step 3: Storage Config ====================

function updateStorageForm() {
    const type = document.getElementById('storage-type').value;
    document.getElementById('s3-config').style.display = type === 's3' ? 'block' : 'none';
    document.getElementById('smb-config').style.display = type === 'smb' ? 'block' : 'none';
}

// The settings in the field names the API reads
function s3Settings() {
    return {
        backend: document.getElementById('s3-backend').value,
        endpoint: document.getElementById('s3-endpoint').value.trim(),
        region: document.getElementById('s3-region').value.trim(),
        bucket: document.getElementById('s3-bucket').value.trim(),
        accessKeyId: document.getElementById('s3-access-key').value.trim(),
        secretAccessKey: document.getElementById('s3-secret-key').value
    };
}

function smbSettings() {
    return {
        server: document.getElementById('smb-server').value.trim(),
        share: document.getElementById('smb-share').value.trim(),
        username: document.getElementById('smb-username').value.trim(),
        password: document.getElementById('smb-password').value
    };
}

function showBanner(el, kind, text, html) {
    el.className = 'result-banner ' + kind;
    el.style.display = 'block';
    if (html) {
        el.innerHTML = html;
    } else {
        el.textContent = text;
    }
}

// Test the settings as entered: nothing is saved until activation
function testConnection(kind, settings) {
    const resultEl = document.getElementById(kind + '-test-result');
    const label = kind === 's3' ? 'S3' : 'SMB';
    showBanner(resultEl, 'pending', null, '<span class="spinner"></span> Testing ' + label + ' connection...');

    api('/api/upload/' + kind + '/test', 'POST', settings)
        .then(function(result) {
            if (result.ok) {
                showBanner(resultEl, 'success', label + ' connection successful!');
            } else {
                showBanner(resultEl, 'error', 'Connection failed: ' + errorOf(result));
            }
        })
        .catch(function(err) {
            showBanner(resultEl, 'error', 'Test failed: ' + err.message);
        });
}

function testS3() {
    testConnection('s3', s3Settings());
}

function testSmb() {
    testConnection('smb', smbSettings());
}

// ==================== Step 4: Test Stream ====================

function initTestStep() {
    document.getElementById('btn-test-stream').style.display = isTestStreaming ? 'none' : 'inline-block';
    document.getElementById('btn-stop-test').style.display = isTestStreaming ? 'inline-block' : 'none';
    if (!isTestStreaming) {
        document.getElementById('thermal-status-area').style.display = 'none';
        setCheckStatus('check-stream', 'pending', 'Not started');
        setCheckStatus('check-thermal-ok', 'pending', 'Monitoring...');
    }
}

// The test stream is the real thing: the RTSP server, at the default settings
function startTestStream() {
    setCheckStatus('check-stream', 'pending', 'Starting stream...');

    api('/api/status')
        .then(function(status) {
            const alreadyRunning = status.ok && status.data.rtsp && status.data.rtsp.running;
            return alreadyRunning ? status.data.rtsp : api('/api/rtsp/start', 'POST', {}).then(function(result) {
                if (!result.ok) {
                    throw new Error(errorOf(result));
                }
                startedTestRtsp = true;
                return result.data;
            });
        })
        .then(function(rtsp) {
            isTestStreaming = true;
            const detail = document.getElementById('check-stream-detail');
            setCheckStatus('check-stream', 'pass', 'Streaming at ');
            const link = document.createElement('a');
            link.href = rtsp.url;
            link.textContent = rtsp.url;
            detail.appendChild(link);
            detail.appendChild(document.createTextNode(': open it in VLC or OBS'));

            const preview = document.getElementById('test-preview');
            preview.src = '/mjpeg?' + Date.now();
            preview.style.display = 'block';

            document.getElementById('btn-test-stream').style.display = 'none';
            document.getElementById('btn-stop-test').style.display = 'inline-block';
            document.getElementById('thermal-status-area').style.display = 'block';
            startThermalPolling();
        })
        .catch(function(err) {
            setCheckStatus('check-stream', 'fail', 'Failed to start: ' + err.message);
        });
}

function stopTestStream() {
    const preview = document.getElementById('test-preview');
    preview.removeAttribute('src');
    preview.style.display = 'none';
    stopThermalPolling();
    isTestStreaming = false;
    document.getElementById('btn-test-stream').style.display = 'inline-block';
    document.getElementById('btn-stop-test').style.display = 'none';

    // Stop only what the test started: an RTSP stream already running stays up
    const stopping = startedTestRtsp ? api('/api/rtsp/stop', 'POST') : Promise.resolve({ ok: true });
    startedTestRtsp = false;
    stopping
        .then(function(result) {
            setCheckStatus('check-stream', result.ok ? 'pass' : 'warn',
                result.ok ? 'Stream test completed' : 'Could not stop the test stream: ' + errorOf(result));
        })
        .catch(function() {
            setCheckStatus('check-stream', 'warn', 'Could not reach the phone to stop the test stream');
        });
}

function startThermalPolling() {
    stopThermalPolling();
    pollThermal();
    thermalPollInterval = setInterval(pollThermal, 3000);
}

function stopThermalPolling() {
    if (thermalPollInterval) {
        clearInterval(thermalPollInterval);
        thermalPollInterval = null;
    }
}

function pollThermal() {
    api('/api/thermal/status')
        .then(function(result) {
            if (!result.ok) {
                setCheckStatus('check-thermal-ok', 'warn', 'Thermal monitoring is not running: ' + errorOf(result));
                return;
            }
            const data = result.data;
            const cpuTemp = data.cpuTemperatureC || 0;
            const batteryTemp = data.batteryTemperatureC || 0;
            if (cpuTemp <= 0 && batteryTemp <= 0) {
                setCheckStatus('check-thermal-ok', 'pending', 'Waiting for the first temperature reading...');
                return;
            }

            document.getElementById('cpu-temp').textContent = cpuTemp.toFixed(1) + '°C';
            document.getElementById('battery-temp').textContent = batteryTemp.toFixed(1) + '°C';

            // Scale bars (CPU 0-80°C, battery 0-60°C)
            const cpuPct = Math.min(100, (cpuTemp / 80) * 100);
            const batteryPct = Math.min(100, (batteryTemp / 60) * 100);

            const cpuBar = document.getElementById('cpu-bar');
            const batteryBar = document.getElementById('battery-bar');

            cpuBar.style.width = cpuPct + '%';
            batteryBar.style.width = batteryPct + '%';

            cpuBar.style.background = tempColor(cpuTemp, 50, 60);
            batteryBar.style.background = tempColor(batteryTemp, 42, 48);

            // Overall thermal check
            const level = data.overallLevel || 'NORMAL';
            if (level === 'NORMAL' || level === 'ELEVATED') {
                setCheckStatus('check-thermal-ok', 'pass', 'Thermal behavior normal (' + level + ')');
            } else if (level === 'WARNING') {
                setCheckStatus('check-thermal-ok', 'warn', 'Thermal warning — consider lower settings');
            } else {
                setCheckStatus('check-thermal-ok', 'fail', 'Thermal ' + level + ' — reduce resolution or bitrate');
            }
        })
        .catch(function() {
            // Ignore polling errors
        });
}

function tempColor(temp, warnThreshold, critThreshold) {
    if (temp >= critThreshold) return 'var(--danger-color)';
    if (temp >= warnThreshold) return 'var(--warning-color)';
    return 'var(--success-color)';
}

// ==================== Step 5: Activate ====================

const S3_PROVIDERS = { S3: 'Amazon S3', BACKBLAZE_B2: 'Backblaze B2', MINIO: 'MinIO', CLOUDFLARE_R2: 'Cloudflare R2' };

function updateSummary() {
    document.getElementById('summary-preset').textContent =
        selectedPreset === 'appliance' ? 'Appliance (24/7 unattended)' :
        selectedPreset === 'interactive' ? 'Interactive (kiosk with preview)' :
        'Not selected';

    const storageType = document.getElementById('storage-type').value;
    let storage = 'Local only';
    if (storageType === 's3') {
        const s3 = s3Settings();
        storage = (S3_PROVIDERS[s3.backend] || 'S3') + (s3.bucket ? ': ' + s3.bucket : '');
    } else if (storageType === 'smb') {
        const smb = smbSettings();
        storage = 'SMB/CIFS share' + (smb.server ? ': \\\\' + smb.server + '\\' + smb.share : '');
    }
    document.getElementById('summary-storage').textContent = storage;

    api('/api/device')
        .then(function(result) {
            if (result.ok) {
                document.getElementById('summary-device').textContent = result.data.manufacturer + ' ' + result.data.model;
            }
        })
        .catch(function() {});

    api('/api/thermal/profile')
        .then(function(result) {
            if (result.ok && result.data.profile) {
                document.getElementById('summary-thermal').textContent = result.data.profile.displayName;
            }
        })
        .catch(function() {});

    document.getElementById('btn-activate').disabled = !selectedPreset;
}

// Each step must succeed before the next: a failure stops activation and says where
function activateKiosk() {
    const resultEl = document.getElementById('activation-result');
    showBanner(resultEl, 'pending', null, '<span class="spinner"></span> Applying preset and activating kiosk mode...');

    const activateBtn = document.getElementById('btn-activate');
    activateBtn.disabled = true;

    const presetUrl = selectedPreset === 'appliance'
        ? '/api/kiosk/preset/appliance'
        : '/api/kiosk/preset/interactive';

    function step(name, request) {
        return request.then(function(result) {
            if (!result.ok) {
                throw new Error(name + ' failed: ' + errorOf(result));
            }
            return result;
        });
    }

    step('Applying the preset', api(presetUrl, 'POST'))
        .then(function() {
            return saveStorageConfig(step);
        })
        .then(function() {
            return step('Turning on kiosk mode', api('/api/kiosk/enable', 'POST'));
        })
        .then(function(result) {
            const lockTask = result.data.lockTask;
            showBanner(resultEl, lockTask === false ? 'pending' : 'success', null,
                '<strong>' + (lockTask === false ? 'Kiosk settings saved' : 'Kiosk mode activated!') + '</strong><br>');
            resultEl.appendChild(document.createTextNode(
                'Preset: ' + (selectedPreset === 'appliance' ? 'Appliance' : 'Interactive') + '. ' + (result.data.message || '')));

            const step5 = document.querySelector('.step[data-step="5"]');
            if (step5) {
                step5.classList.remove('active');
                step5.classList.add('done');
            }
        })
        .catch(function(err) {
            showBanner(resultEl, 'error', err.message);
            activateBtn.disabled = false;
        });
}

function saveStorageConfig(step) {
    const storageType = document.getElementById('storage-type').value;

    if (storageType === 's3') {
        return step('Saving the S3 settings', api('/api/upload/s3/config', 'POST', s3Settings()));
    } else if (storageType === 'smb') {
        return step('Saving the SMB settings', api('/api/upload/smb/config', 'POST', smbSettings()));
    }

    return Promise.resolve();
}

// ==================== Init ====================

document.addEventListener('DOMContentLoaded', function() {
    runChecks();
});
