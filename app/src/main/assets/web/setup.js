// LensDaemon Setup Wizard

let currentStep = 1;
let selectedPreset = null;
let thermalPollInterval = null;
let isTestStreaming = false;

// ==================== Navigation ====================

function goToStep(step) {
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
    var stepContent = document.getElementById('step-' + step);
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
    var icon = document.getElementById(id + '-icon');
    var detailEl = document.getElementById(id + '-detail');
    if (icon) {
        icon.className = 'check-icon ' + status;
        icon.textContent = status === 'pass' ? '\u2713' : status === 'fail' ? '\u2717' : status === 'warn' ? '!' : '...';
    }
    if (detailEl) {
        detailEl.textContent = detail;
    }
}

function checkDevice() {
    fetch('/api/device')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            var detail = data.manufacturer + ' ' + data.model + ' (Android ' + data.androidVersion + ')';
            setCheckStatus('check-device', 'pass', detail);
        })
        .catch(function() {
            setCheckStatus('check-device', 'fail', 'Cannot reach device API');
        });
}

function checkKioskStatus() {
    fetch('/api/kiosk/status')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.isDeviceOwner) {
                setCheckStatus('check-kiosk', 'pass', 'Device Owner is set. Kiosk mode available.');
                document.getElementById('device-owner-help').style.display = 'none';
                enableStep1Next();
            } else {
                setCheckStatus('check-kiosk', 'warn', 'Device Owner not set. Required for kiosk mode.');
                document.getElementById('device-owner-help').style.display = 'block';
                // Still allow proceeding - kiosk just won't fully lock
                enableStep1Next();
            }
        })
        .catch(function() {
            setCheckStatus('check-kiosk', 'fail', 'Kiosk service not available');
            enableStep1Next();
        });
}

function checkCamera() {
    fetch('/api/status')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.camera) {
                setCheckStatus('check-camera', 'pass', 'Camera service running');
            } else {
                setCheckStatus('check-camera', 'warn', 'Camera status unknown');
            }
        })
        .catch(function() {
            setCheckStatus('check-camera', 'fail', 'Cannot reach API');
        });
}

function checkThermalProfile() {
    fetch('/api/thermal/profile')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            var profile = data.profile;
            var source = data.source;
            var detail = profile.displayName + ' (' + source + ')';
            setCheckStatus('check-thermal', 'pass', detail);
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
    var type = document.getElementById('storage-type').value;
    document.getElementById('s3-config').style.display = type === 's3' ? 'block' : 'none';
    document.getElementById('smb-config').style.display = type === 'smb' ? 'block' : 'none';
}

function testS3() {
    var resultEl = document.getElementById('s3-test-result');
    resultEl.className = 'result-banner';
    resultEl.style.display = 'block';
    resultEl.innerHTML = '<span class="spinner"></span> Testing S3 connection...';

    var config = {
        endpoint: document.getElementById('s3-endpoint').value,
        region: document.getElementById('s3-region').value,
        bucket: document.getElementById('s3-bucket').value,
        accessKey: document.getElementById('s3-access-key').value,
        secretKey: document.getElementById('s3-secret-key').value
    };

    fetch('/api/upload/s3/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(config)
    })
    .then(function(r) { return r.json(); })
    .then(function(data) {
        if (data.success) {
            resultEl.className = 'result-banner success';
            resultEl.textContent = 'S3 connection successful!';
        } else {
            resultEl.className = 'result-banner error';
            resultEl.textContent = 'Connection failed: ' + (data.error || 'Unknown error');
        }
    })
    .catch(function(err) {
        resultEl.className = 'result-banner error';
        resultEl.textContent = 'Test failed: ' + err.message;
    });
}

function testSmb() {
    var resultEl = document.getElementById('smb-test-result');
    resultEl.className = 'result-banner';
    resultEl.style.display = 'block';
    resultEl.innerHTML = '<span class="spinner"></span> Testing SMB connection...';

    var config = {
        host: document.getElementById('smb-host').value,
        share: document.getElementById('smb-share').value,
        username: document.getElementById('smb-username').value,
        password: document.getElementById('smb-password').value
    };

    fetch('/api/upload/smb/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(config)
    })
    .then(function(r) { return r.json(); })
    .then(function(data) {
        if (data.success) {
            resultEl.className = 'result-banner success';
            resultEl.textContent = 'SMB connection successful!';
        } else {
            resultEl.className = 'result-banner error';
            resultEl.textContent = 'Connection failed: ' + (data.error || 'Unknown error');
        }
    })
    .catch(function(err) {
        resultEl.className = 'result-banner error';
        resultEl.textContent = 'Test failed: ' + err.message;
    });
}

// ==================== Step 4: Test Stream ====================

function initTestStep() {
    isTestStreaming = false;
    document.getElementById('btn-test-stream').style.display = 'inline-block';
    document.getElementById('btn-stop-test').style.display = 'none';
    document.getElementById('thermal-status-area').style.display = 'none';
    setCheckStatus('check-stream', 'pending', 'Not started');
    setCheckStatus('check-thermal-ok', 'pending', 'Monitoring...');
}

function startTestStream() {
    setCheckStatus('check-stream', 'pending', 'Starting stream...');

    fetch('/api/stream/start', { method: 'POST' })
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.success !== false) {
                isTestStreaming = true;
                setCheckStatus('check-stream', 'pass', 'Stream running');
                document.getElementById('btn-test-stream').style.display = 'none';
                document.getElementById('btn-stop-test').style.display = 'inline-block';
                document.getElementById('thermal-status-area').style.display = 'block';
                startThermalPolling();
            } else {
                setCheckStatus('check-stream', 'fail', 'Failed to start: ' + (data.error || 'Unknown'));
            }
        })
        .catch(function(err) {
            setCheckStatus('check-stream', 'fail', 'Error: ' + err.message);
        });
}

function stopTestStream() {
    fetch('/api/stream/stop', { method: 'POST' })
        .then(function() {
            isTestStreaming = false;
            stopThermalPolling();
            document.getElementById('btn-test-stream').style.display = 'inline-block';
            document.getElementById('btn-stop-test').style.display = 'none';
            setCheckStatus('check-stream', 'pass', 'Stream test completed');
        })
        .catch(function() {
            // Still mark as stopped locally
            isTestStreaming = false;
            stopThermalPolling();
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
    fetch('/api/thermal/status')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            var cpuTemp = data.cpuTemperatureC || 0;
            var batteryTemp = data.batteryTemperatureC || 0;

            document.getElementById('cpu-temp').textContent = cpuTemp.toFixed(1) + '\u00B0C';
            document.getElementById('battery-temp').textContent = batteryTemp.toFixed(1) + '\u00B0C';

            // Scale bars (0-80°C range)
            var cpuPct = Math.min(100, (cpuTemp / 80) * 100);
            var batteryPct = Math.min(100, (batteryTemp / 60) * 100);

            var cpuBar = document.getElementById('cpu-bar');
            var batteryBar = document.getElementById('battery-bar');

            cpuBar.style.width = cpuPct + '%';
            batteryBar.style.width = batteryPct + '%';

            cpuBar.style.background = tempColor(cpuTemp, 50, 60);
            batteryBar.style.background = tempColor(batteryTemp, 42, 48);

            // Overall thermal check
            var level = data.overallLevel || 'NORMAL';
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

function updateSummary() {
    document.getElementById('summary-preset').textContent =
        selectedPreset === 'appliance' ? 'Appliance (24/7 unattended)' :
        selectedPreset === 'interactive' ? 'Interactive (kiosk with preview)' :
        'Not selected';

    var storageType = document.getElementById('storage-type').value;
    document.getElementById('summary-storage').textContent =
        storageType === 'none' ? 'Local only' :
        storageType === 's3' ? 'S3-Compatible' :
        storageType === 'smb' ? 'SMB/CIFS Network Share' : 'Local only';

    // Fetch fresh device info
    fetch('/api/device')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            document.getElementById('summary-device').textContent =
                data.manufacturer + ' ' + data.model;
        })
        .catch(function() {});

    fetch('/api/thermal/profile')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            document.getElementById('summary-thermal').textContent =
                data.profile.displayName;
        })
        .catch(function() {});
}

function activateKiosk() {
    var resultEl = document.getElementById('activation-result');
    resultEl.className = 'result-banner';
    resultEl.style.display = 'block';
    resultEl.innerHTML = '<span class="spinner"></span> Applying preset and activating kiosk mode...';

    var activateBtn = document.getElementById('btn-activate');
    activateBtn.disabled = true;

    // Apply preset
    var presetUrl = selectedPreset === 'appliance'
        ? '/api/kiosk/preset/appliance'
        : '/api/kiosk/preset/interactive';

    fetch(presetUrl, { method: 'POST' })
        .then(function(r) { return r.json(); })
        .then(function(data) {
            // Save storage config if set
            return saveStorageConfig();
        })
        .then(function() {
            // Enable kiosk
            return fetch('/api/kiosk/enable', { method: 'POST' });
        })
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.success !== false) {
                resultEl.className = 'result-banner success';
                resultEl.innerHTML =
                    '<strong>Kiosk mode activated!</strong><br>' +
                    'Preset: ' + (selectedPreset === 'appliance' ? 'Appliance' : 'Interactive') + '<br>' +
                    'The device will enter kiosk mode on next restart, or you can start it from the dashboard.';

                // Mark step 5 as done
                var step5 = document.querySelector('.step[data-step="5"]');
                if (step5) {
                    step5.classList.remove('active');
                    step5.classList.add('done');
                }
            } else {
                resultEl.className = 'result-banner error';
                resultEl.textContent = 'Activation failed: ' + (data.error || 'Unknown error');
                activateBtn.disabled = false;
            }
        })
        .catch(function(err) {
            resultEl.className = 'result-banner error';
            resultEl.textContent = 'Error: ' + err.message;
            activateBtn.disabled = false;
        });
}

function saveStorageConfig() {
    var storageType = document.getElementById('storage-type').value;

    if (storageType === 's3') {
        return fetch('/api/upload/s3/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                endpoint: document.getElementById('s3-endpoint').value,
                region: document.getElementById('s3-region').value,
                bucket: document.getElementById('s3-bucket').value,
                accessKey: document.getElementById('s3-access-key').value,
                secretKey: document.getElementById('s3-secret-key').value
            })
        });
    } else if (storageType === 'smb') {
        return fetch('/api/upload/smb/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                host: document.getElementById('smb-host').value,
                share: document.getElementById('smb-share').value,
                username: document.getElementById('smb-username').value,
                password: document.getElementById('smb-password').value
            })
        });
    }

    return Promise.resolve();
}

// ==================== Init ====================

document.addEventListener('DOMContentLoaded', function() {
    runChecks();
});
