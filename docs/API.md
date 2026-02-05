# LensDaemon REST API Reference

Base URL: `http://{device-ip}:8080`

## Authentication

Authentication is optional and controlled by the `apiToken` configuration property. When enabled, include the token with every request using one of two methods:

**Header (preferred):**
```
Authorization: Bearer <token>
```

**Query parameter:**
```
GET /api/status?token=<token>
```

If no `apiToken` is configured, all endpoints are accessible without credentials.

---

## Core

### GET /api/status

Returns device info, temperatures, and streaming state.

**Response:**
```json
{
  "device": "Pixel 7 Pro",
  "android": "13",
  "uptime": 86400,
  "streaming": true,
  "rtsp": true,
  "recording": false,
  "temperatures": {
    "cpu": 42.5,
    "battery": 38.0,
    "gpu": 40.0
  },
  "encoder": {
    "codec": "H265",
    "resolution": "1920x1080",
    "bitrate": 6000000,
    "fps": 30
  },
  "lens": "main",
  "zoom": 1.0,
  "clients": {
    "rtsp": 2,
    "mjpeg": 1
  }
}
```

### POST /api/stream/start

Start the video encoder.

**Response:**
```json
{
  "status": "ok",
  "message": "Streaming started"
}
```

### POST /api/stream/stop

Stop the video encoder.

**Response:**
```json
{
  "status": "ok",
  "message": "Streaming stopped"
}
```

### POST /api/lens/{lens}

Switch the active camera lens.

**Path parameters:**
| Parameter | Values |
|-----------|--------|
| `lens` | `wide`, `main`, `tele` |

**Response:**
```json
{
  "status": "ok",
  "lens": "tele",
  "zoom": 3.0
}
```

Returns an error if the requested lens is not available on the device.

### POST /api/snapshot

Capture a JPEG snapshot from the current camera feed.

**Response:** JPEG image binary with `Content-Type: image/jpeg`.

### GET /api/config

Get the current encoder configuration.

**Response:**
```json
{
  "codec": "H265",
  "resolution": "1920x1080",
  "bitrate": 6000000,
  "bitrateMode": "VBR",
  "fps": 30,
  "keyframeInterval": 2,
  "profile": "Main"
}
```

### PUT /api/config

Update encoder configuration. Accepts partial updates -- only include the fields you want to change.

**Request:**
```json
{
  "codec": "H264",
  "bitrate": 4000000,
  "fps": 24
}
```

**Response:**
```json
{
  "status": "ok",
  "config": {
    "codec": "H264",
    "resolution": "1920x1080",
    "bitrate": 4000000,
    "bitrateMode": "VBR",
    "fps": 24,
    "keyframeInterval": 2,
    "profile": "High"
  }
}
```

---

## RTSP

The RTSP server runs on port 8554 and supports up to 10 concurrent clients.

### POST /api/rtsp/start

Start the RTSP server.

**Response:**
```json
{
  "status": "ok",
  "message": "RTSP server started",
  "url": "rtsp://192.168.1.100:8554/stream"
}
```

### POST /api/rtsp/stop

Stop the RTSP server and disconnect all clients.

**Response:**
```json
{
  "status": "ok",
  "message": "RTSP server stopped"
}
```

---

## Camera Controls

### POST /api/zoom

Set the camera zoom level.

**Request:**
```json
{
  "level": 2.5
}
```

**Response:**
```json
{
  "status": "ok",
  "zoom": 2.5,
  "lens": "tele"
}
```

The lens may switch automatically to achieve the requested zoom level.

### POST /api/focus

Trigger tap-to-focus at the given coordinates. Values are normalized (0.0 to 1.0) relative to the preview frame.

**Request:**
```json
{
  "x": 0.5,
  "y": 0.5
}
```

**Response:**
```json
{
  "status": "ok",
  "focusState": "scanning"
}
```

Focus state transitions through `inactive` -> `scanning` -> `focused` or `failed`.

### POST /api/exposure

Set exposure compensation in EV steps.

**Request:**
```json
{
  "ev": -1.0
}
```

**Response:**
```json
{
  "status": "ok",
  "ev": -1.0
}
```

---

## Recording

### POST /api/recording/start

Start local recording to MP4.

**Response:**
```json
{
  "status": "ok",
  "message": "Recording started"
}
```

### POST /api/recording/stop

Stop the current recording.

**Response:**
```json
{
  "status": "ok",
  "message": "Recording stopped",
  "file": "LensDaemon_Pixel7Pro_20240115_143022.mp4"
}
```

### POST /api/recording/pause

Pause the current recording.

**Response:**
```json
{
  "status": "ok",
  "message": "Recording paused"
}
```

### POST /api/recording/resume

Resume a paused recording.

**Response:**
```json
{
  "status": "ok",
  "message": "Recording resumed"
}
```

### GET /api/recording/status

Get current recording state and statistics.

**Response:**
```json
{
  "recording": true,
  "paused": false,
  "duration": 125.4,
  "fileSize": 52428800,
  "file": "LensDaemon_Pixel7Pro_20240115_143022.mp4",
  "segmentDuration": "15min"
}
```

### GET /api/recordings

List all local recordings.

**Response:**
```json
{
  "recordings": [
    {
      "filename": "LensDaemon_Pixel7Pro_20240115_143022.mp4",
      "size": 104857600,
      "duration": 300.0,
      "created": "2024-01-15T14:30:22Z"
    }
  ]
}
```

### DELETE /api/recordings/{filename}

Delete a specific recording.

**Path parameters:**
| Parameter | Description |
|-----------|-------------|
| `filename` | Name of the recording file |

**Response:**
```json
{
  "status": "ok",
  "message": "Recording deleted"
}
```

### GET /api/storage/status

Get storage usage and space information.

**Response:**
```json
{
  "location": "EXTERNAL_APP",
  "totalSpace": 128849018880,
  "freeSpace": 64424509440,
  "usedByApp": 524288000,
  "recordingCount": 5,
  "retention": {
    "type": "MAX_AGE_AND_SIZE",
    "maxAge": "7d",
    "maxSize": "10GB"
  }
}
```

### POST /api/storage/cleanup

Manually trigger retention policy enforcement. Removes recordings that exceed the configured age or size limits.

**Response:**
```json
{
  "status": "ok",
  "deletedCount": 3,
  "freedSpace": 314572800
}
```

---

## Upload

### GET /api/upload/status

Get upload service state.

**Response:**
```json
{
  "running": true,
  "queueSize": 3,
  "completed": 12,
  "failed": 1,
  "s3Configured": true,
  "smbConfigured": false
}
```

### GET /api/upload/queue

Get pending and completed upload tasks.

**Response:**
```json
{
  "tasks": [
    {
      "id": "task_001",
      "file": "LensDaemon_Pixel7Pro_20240115_143022.mp4",
      "destination": "S3",
      "status": "UPLOADING",
      "progress": 0.65,
      "size": 104857600,
      "createdAt": "2024-01-15T15:00:00Z"
    }
  ]
}
```

### POST /api/upload/start

Start processing the upload queue.

**Response:**
```json
{
  "status": "ok",
  "message": "Upload processing started"
}
```

### POST /api/upload/stop

Stop processing uploads. In-progress uploads are paused.

**Response:**
```json
{
  "status": "ok",
  "message": "Upload processing stopped"
}
```

### POST /api/upload/enqueue

Add a file to the upload queue.

**Request:**
```json
{
  "file": "LensDaemon_Pixel7Pro_20240115_143022.mp4",
  "destination": "S3"
}
```

`destination` can be `S3` or `SMB`.

**Response:**
```json
{
  "status": "ok",
  "taskId": "task_002"
}
```

### DELETE /api/upload/task/{id}

Cancel or remove an upload task.

**Path parameters:**
| Parameter | Description |
|-----------|-------------|
| `id` | Upload task ID |

**Response:**
```json
{
  "status": "ok",
  "message": "Task cancelled"
}
```

### POST /api/upload/retry

Retry all failed uploads.

**Response:**
```json
{
  "status": "ok",
  "retriedCount": 1
}
```

### POST /api/upload/clear

Clear all pending (not in-progress) upload tasks.

**Response:**
```json
{
  "status": "ok",
  "clearedCount": 2
}
```

### GET /api/upload/s3/config

Get the current S3 configuration (credentials are masked).

**Response:**
```json
{
  "configured": true,
  "backend": "AWS_S3",
  "endpoint": "https://s3.amazonaws.com",
  "bucket": "my-recordings",
  "region": "us-east-1",
  "prefix": "lensdaemon/",
  "accessKey": "AKIA****XXXX"
}
```

### POST /api/upload/s3/config

Configure S3-compatible storage credentials.

**Request:**
```json
{
  "backend": "AWS_S3",
  "endpoint": "https://s3.amazonaws.com",
  "bucket": "my-recordings",
  "region": "us-east-1",
  "prefix": "lensdaemon/",
  "accessKey": "AKIAIOSFODNN7EXAMPLE",
  "secretKey": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
}
```

Supported `backend` values: `AWS_S3`, `BACKBLAZE_B2`, `MINIO`, `CLOUDFLARE_R2`.

**Response:**
```json
{
  "status": "ok",
  "message": "S3 configuration saved"
}
```

### DELETE /api/upload/s3/config

Remove saved S3 credentials.

**Response:**
```json
{
  "status": "ok",
  "message": "S3 configuration removed"
}
```

### POST /api/upload/s3/test

Test the current S3 configuration by attempting a connection.

**Response:**
```json
{
  "status": "ok",
  "message": "S3 connection successful",
  "bucket": "my-recordings"
}
```

### GET /api/upload/smb/config

Get the current SMB configuration (password is masked).

**Response:**
```json
{
  "configured": true,
  "server": "192.168.1.50",
  "share": "recordings",
  "path": "lensdaemon/",
  "username": "backup",
  "domain": "WORKGROUP"
}
```

### POST /api/upload/smb/config

Configure SMB/CIFS network share credentials.

**Request:**
```json
{
  "server": "192.168.1.50",
  "share": "recordings",
  "path": "lensdaemon/",
  "username": "backup",
  "password": "secret",
  "domain": "WORKGROUP"
}
```

**Response:**
```json
{
  "status": "ok",
  "message": "SMB configuration saved"
}
```

### DELETE /api/upload/smb/config

Remove saved SMB credentials.

**Response:**
```json
{
  "status": "ok",
  "message": "SMB configuration removed"
}
```

### POST /api/upload/smb/test

Test the current SMB configuration by attempting a connection.

**Response:**
```json
{
  "status": "ok",
  "message": "SMB connection successful",
  "share": "\\\\192.168.1.50\\recordings"
}
```

---

## Thermal

### GET /api/thermal/status

Get current temperatures and thermal levels.

**Response:**
```json
{
  "cpu": 42.5,
  "battery": 38.0,
  "gpu": 40.0,
  "level": "NORMAL",
  "throttling": false,
  "actions": []
}
```

Thermal levels: `NORMAL`, `ELEVATED`, `WARNING`, `CRITICAL`, `EMERGENCY`.

Possible throttle actions: `REDUCE_BITRATE`, `REDUCE_RESOLUTION`, `REDUCE_FRAMERATE`, `PAUSE_STREAMING`.

### GET /api/thermal/history

Get temperature history for dashboard graphs.

**Response:**
```json
{
  "interval": "1min",
  "points": [
    {
      "timestamp": "2024-01-15T14:00:00Z",
      "cpu": 41.0,
      "battery": 37.5,
      "gpu": 39.0,
      "level": "NORMAL"
    }
  ]
}
```

History covers the last 24 hours at minute granularity.

### GET /api/thermal/stats

Get thermal statistics summary.

**Response:**
```json
{
  "cpu": {
    "min": 35.0,
    "max": 55.0,
    "avg": 43.2
  },
  "battery": {
    "min": 30.0,
    "max": 42.0,
    "avg": 37.5
  },
  "timeInState": {
    "NORMAL": 72000,
    "ELEVATED": 10800,
    "WARNING": 3600,
    "CRITICAL": 0,
    "EMERGENCY": 0
  }
}
```

### GET /api/thermal/events

Get thermal event log.

**Response:**
```json
{
  "events": [
    {
      "timestamp": "2024-01-15T14:32:00Z",
      "type": "LEVEL_CHANGE",
      "from": "NORMAL",
      "to": "ELEVATED",
      "cpu": 46.0,
      "battery": 39.0
    }
  ]
}
```

### GET /api/thermal/battery

Get battery bypass status.

**Response:**
```json
{
  "bypassAvailable": true,
  "bypassActive": false,
  "chargeLevel": 80,
  "targetLevel": 50,
  "temperature": 38.0,
  "charging": true
}
```

### POST /api/thermal/battery/disable

Disable battery charging (requires root or compatible kernel).

**Response:**
```json
{
  "status": "ok",
  "message": "Charging disabled"
}
```

### POST /api/thermal/battery/enable

Re-enable battery charging.

**Response:**
```json
{
  "status": "ok",
  "message": "Charging enabled"
}
```

---

## Kiosk

### GET /api/kiosk/status

Get kiosk mode state.

**Response:**
```json
{
  "state": "ENABLED",
  "isDeviceOwner": true,
  "lockTaskActive": true,
  "screenMode": "OFF",
  "autoStart": true,
  "uptime": 259200
}
```

Kiosk states: `DISABLED`, `ENABLED`, `SETUP_REQUIRED`, `NOT_DEVICE_OWNER`.

### POST /api/kiosk/enable

Enable kiosk mode. The device must be set as Device Owner first.

**Response:**
```json
{
  "status": "ok",
  "message": "Kiosk mode enabled"
}
```

### POST /api/kiosk/disable

Disable kiosk mode and restore normal device operation.

**Response:**
```json
{
  "status": "ok",
  "message": "Kiosk mode disabled"
}
```

### GET /api/kiosk/config

Get the full kiosk configuration.

**Response:**
```json
{
  "autoStart": {
    "enabled": true,
    "delaySeconds": 5,
    "startStreaming": true,
    "startRtsp": true,
    "startRecording": false
  },
  "screen": {
    "mode": "OFF",
    "brightness": 0,
    "autoOffTimeout": 30
  },
  "security": {
    "exitPin": "1234",
    "gestureEnabled": true,
    "lockStatusBar": true
  },
  "networkRecovery": {
    "autoReconnect": true,
    "maxRetries": 10,
    "retryInterval": 30
  },
  "crashRecovery": {
    "autoRestart": true,
    "maxAttempts": 10,
    "cooldownMinutes": 5
  }
}
```

### PUT /api/kiosk/config

Update kiosk configuration. Accepts partial updates.

**Request:**
```json
{
  "screen": {
    "mode": "DIM",
    "brightness": 20
  },
  "security": {
    "exitPin": "5678"
  }
}
```

**Response:**
```json
{
  "status": "ok",
  "config": { }
}
```

### POST /api/kiosk/preset/appliance

Apply the APPLIANCE preset for 24/7 unattended operation. Sets screen OFF, enables auto-start for streaming and RTSP, locks system UI, and enables crash recovery.

**Response:**
```json
{
  "status": "ok",
  "message": "Appliance preset applied"
}
```

### POST /api/kiosk/preset/interactive

Apply the INTERACTIVE preset for kiosk with live preview. Keeps screen on with camera preview, enables navigation bar, and allows gesture-based exit.

**Response:**
```json
{
  "status": "ok",
  "message": "Interactive preset applied"
}
```

### GET /api/kiosk/events

Get kiosk event log.

**Response:**
```json
{
  "events": [
    {
      "timestamp": "2024-01-15T14:00:00Z",
      "type": "KIOSK_ENABLED",
      "details": "Appliance preset applied"
    },
    {
      "timestamp": "2024-01-15T14:00:05Z",
      "type": "LOCK_TASK_STARTED",
      "details": "Lock task mode activated"
    }
  ]
}
```

---

## AI Director

The AI Director provides script-driven camera automation. It is completely inert when disabled and has zero thermal impact in that state.

### GET /api/director/status

Get the current director state.

**Response:**
```json
{
  "state": "RUNNING",
  "enabled": true,
  "currentScene": "Interview Setup",
  "currentSceneIndex": 0,
  "currentCue": "[SHOT: MEDIUM]",
  "currentCueIndex": 3,
  "totalCues": 12,
  "takeNumber": 2,
  "sessionDuration": 300
}
```

Director states: `DISABLED`, `IDLE`, `PARSING`, `READY`, `RUNNING`, `PAUSED`, `THERMAL_HOLD`.

### POST /api/director/enable

Enable the AI Director module.

**Response:**
```json
{
  "status": "ok",
  "message": "Director enabled"
}
```

### POST /api/director/disable

Disable the AI Director and release all resources.

**Response:**
```json
{
  "status": "ok",
  "message": "Director disabled"
}
```

### GET /api/director/config

Get the director configuration.

**Response:**
```json
{
  "inferenceMode": "PRE_PARSED",
  "thermalPauseThreshold": 55.0,
  "thermalResumeThreshold": 45.0,
  "defaultHoldDuration": 2.0,
  "llmConfig": {
    "endpoint": "https://api.openai.com",
    "model": "gpt-4",
    "maxTokens": 1000,
    "temperature": 0.3
  }
}
```

### PUT /api/director/config

Update director configuration.

**Request:**
```json
{
  "inferenceMode": "REMOTE",
  "llmConfig": {
    "endpoint": "https://api.anthropic.com",
    "apiKey": "sk-ant-...",
    "model": "claude-3-haiku-20240307"
  }
}
```

**Response:**
```json
{
  "status": "ok",
  "config": { }
}
```

### POST /api/director/script

Load and parse a script.

**Request:**
```json
{
  "script": "[SCENE: Interview Setup]\n[SHOT: WIDE]\n[HOLD: 3]\n[TRANSITION: PUSH IN] - 2s\n[SHOT: MEDIUM]\n[FOCUS: FACE]\n\n[SCENE: Close-ups]\n[SHOT: CLOSE-UP]\n[FOCUS: HANDS]"
}
```

**Response:**
```json
{
  "status": "ok",
  "scenes": 2,
  "totalCues": 8,
  "warnings": []
}
```

### POST /api/director/script/clear

Clear the loaded script and reset the session.

**Response:**
```json
{
  "status": "ok",
  "message": "Script cleared"
}
```

### POST /api/director/start

Start executing the loaded script.

**Response:**
```json
{
  "status": "ok",
  "message": "Execution started"
}
```

### POST /api/director/stop

Stop script execution and reset position.

**Response:**
```json
{
  "status": "ok",
  "message": "Execution stopped"
}
```

### POST /api/director/pause

Pause script execution.

**Response:**
```json
{
  "status": "ok",
  "message": "Execution paused"
}
```

### POST /api/director/resume

Resume paused execution.

**Response:**
```json
{
  "status": "ok",
  "message": "Execution resumed"
}
```

### POST /api/director/cue

Execute a single cue immediately without loading a full script.

**Request:**
```json
{
  "cue": "[SHOT: CLOSE-UP]"
}
```

**Response:**
```json
{
  "status": "ok",
  "cue": "[SHOT: CLOSE-UP]",
  "mapped": true
}
```

### POST /api/director/advance

Advance to the next cue in the script.

**Response:**
```json
{
  "status": "ok",
  "cue": "[FOCUS: FACE]",
  "cueIndex": 4,
  "scene": "Interview Setup"
}
```

### POST /api/director/scene

Jump to a specific scene by index.

**Request:**
```json
{
  "index": 1
}
```

**Response:**
```json
{
  "status": "ok",
  "scene": "Close-ups",
  "sceneIndex": 1,
  "cueIndex": 0
}
```

### GET /api/director/takes

Get all recorded takes.

**Response:**
```json
{
  "takes": [
    {
      "takeNumber": 1,
      "sceneId": "scene_000",
      "sceneLabel": "Interview Setup",
      "qualityScore": 7.8,
      "duration": 45.2,
      "mark": "GOOD",
      "linkedFile": "LensDaemon_Pixel7Pro_20240115_143022.mp4",
      "factors": {
        "focus": 8.5,
        "exposure": 7.0,
        "stability": 8.0,
        "timing": 7.5
      }
    }
  ]
}
```

### POST /api/director/takes/mark

Mark a take with a quality label.

**Request:**
```json
{
  "takeNumber": 1,
  "mark": "CIRCLE"
}
```

Mark values: `UNMARKED`, `GOOD`, `BAD`, `CIRCLE`, `HOLD`.

**Response:**
```json
{
  "status": "ok",
  "takeNumber": 1,
  "mark": "CIRCLE"
}
```

### GET /api/director/takes/best

Get the best take for each scene based on quality scores.

**Response:**
```json
{
  "bestTakes": [
    {
      "sceneId": "scene_000",
      "sceneLabel": "Interview Setup",
      "takeNumber": 3,
      "qualityScore": 9.1
    },
    {
      "sceneId": "scene_001",
      "sceneLabel": "Close-ups",
      "takeNumber": 1,
      "qualityScore": 8.4
    }
  ]
}
```

### GET /api/director/takes/compare

Compare all takes for the current scene.

**Response:**
```json
{
  "scene": "Interview Setup",
  "takes": [
    {
      "takeNumber": 1,
      "qualityScore": 7.8,
      "mark": "GOOD"
    },
    {
      "takeNumber": 2,
      "qualityScore": 9.1,
      "mark": "CIRCLE"
    }
  ],
  "recommendation": "Take 2 has the highest quality score"
}
```

### GET /api/director/session

Get session info and statistics.

**Response:**
```json
{
  "sessionId": "sess_20240115_143022",
  "startTime": "2024-01-15T14:30:22Z",
  "duration": 1800,
  "totalTakes": 8,
  "avgQuality": 8.2,
  "bestTakes": 4,
  "cueSuccessRate": 0.95,
  "scenesCompleted": 1,
  "totalScenes": 2
}
```

### GET /api/director/events

Server-Sent Events (SSE) stream of real-time director events. Connect with an `EventSource` client.

**Response (SSE stream):**
```
event: cue_executed
data: {"cue":"[SHOT: MEDIUM]","cueIndex":3,"success":true}

event: scene_changed
data: {"scene":"Close-ups","sceneIndex":1}

event: take_started
data: {"takeNumber":2,"sceneId":"scene_001"}

event: take_ended
data: {"takeNumber":2,"qualityScore":8.5}

event: state_changed
data: {"state":"PAUSED","reason":"manual"}
```

### GET /api/director/scripts

List saved script files on the device.

**Response:**
```json
{
  "scripts": [
    {
      "fileName": "interview.txt",
      "size": 1024,
      "modified": "2024-01-15T12:00:00Z"
    },
    {
      "fileName": "product_demo.txt",
      "size": 2048,
      "modified": "2024-01-14T09:30:00Z"
    }
  ]
}
```

### POST /api/director/scripts/save

Save the current or provided script to a file.

**Request:**
```json
{
  "fileName": "interview.txt",
  "script": "[SCENE: Interview Setup]\n[SHOT: WIDE]\n..."
}
```

**Response:**
```json
{
  "status": "ok",
  "fileName": "interview.txt",
  "size": 1024
}
```

### GET /api/director/scripts/{fileName}

Load a saved script file. The script is parsed and made ready for execution.

**Path parameters:**
| Parameter | Description |
|-----------|-------------|
| `fileName` | Name of the script file |

**Response:**
```json
{
  "status": "ok",
  "fileName": "interview.txt",
  "script": "[SCENE: Interview Setup]\n...",
  "scenes": 2,
  "totalCues": 8
}
```

### DELETE /api/director/scripts/{fileName}

Delete a saved script file.

**Response:**
```json
{
  "status": "ok",
  "message": "Script deleted"
}
```

### GET /api/director/scripts/export

Export the currently loaded script as plain text.

**Response:** Plain text with `Content-Type: text/plain`.

### POST /api/director/scripts/import

Import a script from text, optionally saving it to a file.

**Request:**
```json
{
  "script": "[SCENE: Demo]\n[SHOT: WIDE]",
  "fileName": "demo.txt",
  "save": true
}
```

If `save` is `false` or omitted, the script is loaded into memory only.

**Response:**
```json
{
  "status": "ok",
  "scenes": 1,
  "totalCues": 2,
  "saved": true,
  "fileName": "demo.txt"
}
```

### POST /api/director/takes/link

Link a take to a recording file for post-production reference.

**Request:**
```json
{
  "takeNumber": 1,
  "filePath": "/storage/emulated/0/Android/data/com.lensdaemon/files/LensDaemon_Pixel7Pro_20240115_143022.mp4"
}
```

**Response:**
```json
{
  "status": "ok",
  "takeNumber": 1,
  "linked": true
}
```

### GET /api/director/takes/markers

Get take markers for recording metadata. Useful for aligning takes with recorded video files.

**Response:**
```json
{
  "markers": [
    {
      "type": "TAKE_START",
      "takeNumber": 1,
      "sceneId": "scene_000",
      "timestampMs": 1705325422000
    },
    {
      "type": "CUE",
      "takeNumber": 1,
      "sceneId": "scene_000",
      "timestampMs": 1705325425000,
      "cueText": "[SHOT: MEDIUM]",
      "cueSuccess": true
    },
    {
      "type": "TAKE_END",
      "takeNumber": 1,
      "sceneId": "scene_000",
      "timestampMs": 1705325452000,
      "qualityScore": 8.5,
      "durationMs": 30000
    }
  ]
}
```

Marker types: `TAKE_START`, `TAKE_END`, `CUE`, `SCENE_CHANGE`.

---

## MJPEG Preview

A live MJPEG preview stream is available at:

```
GET /mjpeg
GET /stream.mjpeg
```

These return a `multipart/x-mixed-replace` stream of JPEG frames suitable for embedding in an `<img>` tag:

```html
<img src="http://{device-ip}:8080/mjpeg" />
```

---

## Error Responses

All endpoints return a consistent error format on failure:

```json
{
  "status": "error",
  "message": "Description of what went wrong"
}
```

Common HTTP status codes:

| Code | Meaning |
|------|---------|
| 200 | Success |
| 400 | Bad request (missing or invalid parameters) |
| 401 | Unauthorized (invalid or missing token) |
| 404 | Endpoint or resource not found |
| 409 | Conflict (e.g., starting a stream that is already running) |
| 500 | Internal server error |

---

## Script Cue Format Reference

When writing scripts for the AI Director, use these cue formats:

```
[SCENE: Label]                   Scene marker
[SHOT: WIDE|MEDIUM|CLOSE-UP]     Shot type
[TRANSITION: PUSH IN|PULL BACK]  Animated zoom (append "- Ns" for duration)
[FOCUS: FACE|HANDS|BACKGROUND]   Focus target
[EXPOSURE: AUTO|BRIGHT|DARK]     Exposure preset
[BEAT]                           Timing marker (default hold)
[HOLD: N]                        Hold for N seconds
[TAKE: N]                        Take boundary
[CUT TO: WIDE|MEDIUM|CLOSE-UP]   Hard cut to shot type
```

Full shot types: `ESTABLISHING`, `WIDE`, `FULL_SHOT`, `MEDIUM`, `CLOSE-UP` (and natural language equivalents like "wide shot", "close up").

Transition types: `CUT`, `PUSH_IN`, `PULL_BACK`, `RACK_FOCUS`, `HOLD`.

Focus targets: `AUTO`, `FACE`, `HANDS`, `OBJECT`, `BACKGROUND`, `MANUAL`.

Exposure presets: `AUTO`, `BRIGHT`, `DARK`, `BACKLIT`, `SILHOUETTE`.
