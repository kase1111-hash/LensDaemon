# LensDaemon REST API Reference

Base URL: `http://{device-ip}:8080`

## Authentication

Authentication is optional and off by default. A Bearer token can be set programmatically through `WebServerService.setApiToken` (it is persisted in the app's private preferences); there is no dashboard UI or API endpoint for it yet, and the bundled dashboard does not send a token, so enable it only for headless API use. When enabled, include the token with every request using one of two methods:

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

## Request bodies

POST and PUT bodies are JSON objects, read as UTF-8 unless the `Content-Type`
names another charset. Errors come back as `{"error": "..."}` (with
`"success": false` where the endpoint reports success), with a 4xx or 5xx status.

---

## Core

### GET /api/status

Returns the camera, encoder and output state. The dashboard polls it every
two seconds.

**Response:**
```json
{
  "status": "ok",
  "timestamp": 1699887600000,
  "camera": {
    "previewActive": true,
    "streamingActive": true,
    "currentLens": "MAIN",
    "zoom": 1.0,
    "maxZoom": 8.0,
    "exposure": { "value": 0, "min": -24, "max": 24, "step": 0.16666667 }
  },
  "encoder": {
    "state": "ENCODING",
    "framesEncoded": 5400,
    "currentBitrate": 3950000,
    "currentFps": 30.0,
    "width": 1920,
    "height": 1080,
    "frameRate": 30,
    "bitrate": 4000000,
    "codec": "H264"
  },
  "rtsp": {
    "running": true,
    "url": "rtsp://192.168.1.50:8554/stream",
    "clients": 1,
    "playing": 1
  },
  "mpegts": {
    "running": true,
    "mode": "CALLER",
    "connected": true,
    "remoteAddress": "192.168.1.20:9000",
    "port": 9000
  },
  "recording": {
    "active": false,
    "paused": false
  },
  "audio": {
    "enabled": true,
    "permission": true,
    "active": true,
    "sampleRate": 48000,
    "channels": 2,
    "bitrate": 128000,
    "framesEncoded": 28125
  },
  "thermal": {
    "cpuTemperatureC": 41.5,
    "batteryTemperatureC": 33.0,
    "overallLevel": "NORMAL",
    "isThrottling": false
  },
  "director": {
    "enabled": true,
    "state": "RUNNING",
    "currentScene": "Interview",
    "currentCue": "[SHOT: MEDIUM]",
    "currentTake": 1,
    "totalCues": 7,
    "executedCues": 3,
    "sceneIndex": 1,
    "sceneCount": 3,
    "cueIndex": 0,
    "cueCount": 2,
    "sceneLabels": ["Opening", "Interview", "Outro"],
    "stats": {
      "totalTakes": 1,
      "goodTakes": 1,
      "averageScore": 7.8,
      "sessionTime": 95,
      "cueSuccessRate": 1.0
    }
  }
}
```

- `camera.exposure` is in the camera's compensation steps: `value * step` is the
  compensation in EV. `maxZoom` and `exposure` are present while the camera
  service is connected.
- `thermal` is present once thermal monitoring runs (the app starts it when it
  opens); `GET /api/thermal/status` has the detail.
- `director` is present on builds with the AI Director. The scene and cue
  fields count from 0 and appear while a script is loaded; `cueSuccessRate` is
  a fraction (0-1).

### GET /api/device

The phone, the app version, and the address the request came from (the
dashboard offers it as the MPEG-TS target: the computer it is open on). Like
`/api/status`, it needs no token.

**Response:**
```json
{
  "clientAddress": "192.168.1.20",
  "manufacturer": "Google",
  "model": "Pixel 7 Pro",
  "device": "cheetah",
  "androidVersion": "14",
  "sdkVersion": 34,
  "appVersion": "0.1.0"
}
```

### Encoder settings in start requests

`/api/stream/start`, `/api/rtsp/start`, `/api/mpegts/start` and
`/api/recording/start` all accept the same optional encoder fields:

| Field | Default | Notes |
|-------|---------|-------|
| `width`, `height` | `1920`, `1080` | Must be a size the hardware encoder supports |
| `bitrate` | `4000000` | Bits per second |
| `frameRate` | `30` | The camera is locked to this rate (see below) |
| `codec` | `H264` | `H264` or `H265` |

There is one encoder, shared by every output. An output started while the
encoder is already running joins it as it is, and the response's `config`
reports the settings actually being sent. To change settings while streaming,
call `/api/stream/start` with the new ones.

The camera's auto-exposure is held at the stream's frame rate, so the stream
does not drop frames in dim light; if the camera cannot reach the requested
rate it runs at its fastest fixed rate instead.

### POST /api/stream/start

Start the encoder with the given settings (see above) and keep it running until
`/api/stream/stop`, whether or not any output is attached. If the encoder is
already running with different settings it is restarted: RTSP and MPEG-TS
viewers stay connected and continue from the new stream's first keyframe, and
an active recording continues in a new segment.

**Response:**
```json
{
  "success": true,
  "message": "Streaming started",
  "config": { "width": 1920, "height": 1080, "bitrate": 4000000, "frameRate": 30, "codec": "H264" }
}
```

Returns `500` with `"success": false` if the encoder could not be started.

### GET /api/audio, POST /api/audio

The phone's microphone, captured whenever the encoder runs and sent with the
video as AAC-LC (48 kHz, stereo or mono depending on the phone, 128 kbps) on
every output. Audio is stamped on the camera's clock, so it stays in sync with
the picture. It needs the RECORD_AUDIO permission, granted on the phone; without
it, or with audio switched off, streams carry video only.

**Request (POST):** `{"enabled": false}` switches the microphone off (`true` on).
It applies at once while streaming: RTSP clients that connect afterwards are
offered (or not offered) an audio track, MPEG-TS receivers see the PMT change,
and a recording changes from its next segment. The setting is kept across
restarts.

**Response (both):** the `audio` object shown under `/api/status`; `active` is
false until the encoder runs, and the settings fields are present only while
audio is being captured.

### POST /api/stream/stop

Stop streaming: the RTSP server, the MPEG-TS publisher, any recording, and the
encoder.

**Response:**
```json
{
  "success": true,
  "message": "Streaming stopped"
}
```

### POST /api/lens/{lens}

Switch the active camera lens.

**Path parameters:**
| Parameter | Values |
|-----------|--------|
| `lens` | A lens family: `wide`, `main`, `tele` (also `ultrawide`, `telephoto`, `zoom`, `primary`), or a lens type from `GET /api/lenses` such as `ultra_wide` or `super_telephoto` |

A family takes the nearest lens of it the phone has: `wide` uses the
ultra-wide on a phone without a plain wide lens, `tele` a 5x periscope on a
phone without a 3x.

**Response:**
```json
{
  "success": true,
  "lens": "ULTRA_WIDE"
}
```

`404` with `{"success": false, "error": "This phone has no tele lens"}` when the
phone has no such lens; `400` for a name that is not a lens.

### GET /api/lenses

The phone's back camera lenses and the one in use. Lenses are typed the way
phones label them: the ~24mm main camera is `MAIN`.

**Response:**
```json
{
  "current": "MAIN",
  "available": [
    { "type": "ULTRA_WIDE", "displayName": "Ultra Wide", "group": "wide", "zoomFactor": "0.5x",
      "cameraId": "0", "focalLength": 2.23, "aperture": 2.2 },
    { "type": "MAIN", "displayName": "Main", "group": "main", "zoomFactor": "1x",
      "cameraId": "0", "focalLength": 6.81, "aperture": 1.85 },
    { "type": "SUPER_TELEPHOTO", "displayName": "Super Telephoto", "group": "tele", "zoomFactor": "5x+",
      "cameraId": "0", "focalLength": 18.9, "aperture": 3.5 }
  ]
}
```

### POST /api/snapshot

Capture a JPEG snapshot from the current camera feed.

**Response:** JPEG image binary with `Content-Type: image/jpeg`.

### GET /api/config

The running encoder's settings (empty while it is stopped) and the camera's.

**Response:**
```json
{
  "encoder": {
    "width": 1920,
    "height": 1080,
    "bitrate": 4000000,
    "frameRate": 30,
    "codec": "H264",
    "keyframeInterval": 2
  },
  "camera": {
    "resolution": "1920x1080",
    "focusMode": "CONTINUOUS_VIDEO",
    "whiteBalance": "AUTO",
    "exposureCompensation": 0,
    "zoom": 1.0
  }
}
```

### PUT /api/config

Change settings of the running camera and encoder. Every field is optional.
Resolution, frame rate and codec are chosen when an output starts the encoder
(see "Encoder settings in start requests").

**Request:**
```json
{
  "bitrate": 6000000,
  "zoom": 2.0,
  "exposureCompensation": -3
}
```

`bitrate` is in bits per second and applies to the running encoder at once;
`exposureCompensation` is in the camera's steps (see `camera.exposure` in the
status).

**Response:**
```json
{
  "success": true,
  "message": "Configuration updated"
}
```

---

## RTSP

The RTSP server runs on port 8554 and supports up to 10 concurrent clients,
over UDP or TCP (interleaved). Any path is accepted;
`rtsp://<device-ip>:8554/stream` is the advertised one.

With audio on, the SDP offers a second track, `trackID=1`: AAC as
`MPEG4-GENERIC` (RFC 3640, AAC-hbr). Each track sends RTCP sender reports
(on the RTCP port, or the odd interleaved channel over TCP), which is what
players use to keep audio and video in sync.

A viewer that connects to a running stream (OBS reconnecting, a second player)
is sent nothing until a keyframe, which is requested from the encoder the moment
it sends PLAY, and that keyframe carries the SPS/PPS it needs to decode. A
viewer that falls more than about two seconds behind skips to the next keyframe
rather than decoding a damaged picture.

### POST /api/rtsp/start

Start the RTSP server, starting the encoder too if it is not running.

**Request (all optional):** the encoder fields above, plus `port` (default `8554`).

**Response:**
```json
{
  "success": true,
  "message": "RTSP streaming started",
  "url": "rtsp://192.168.1.50:8554/stream",
  "config": { "width": 1920, "height": 1080, "bitrate": 4000000, "frameRate": 30, "codec": "H264" }
}
```

### POST /api/rtsp/stop

Stop the RTSP server and disconnect its clients. The encoder keeps running if
another output (or `/api/stream/start`) still uses it.

**Response:**
```json
{
  "success": true,
  "message": "RTSP streaming stopped"
}
```

### GET /api/rtsp/status

```json
{
  "running": true,
  "url": "rtsp://192.168.1.50:8554/stream",
  "clients": 1,
  "playing": 1,
  "totalConnections": 3,
  "totalPackets": 120000,
  "totalBytes": 150000000,
  "uptime": 600000
}
```

---

## MPEG-TS over UDP

Plain MPEG transport stream in UDP datagrams (7 TS packets each). There is no
encryption and no retransmission, so use it on a wired or solid WiFi LAN. It
has lower latency than RTSP in OBS. Each receiver starts on a keyframe with its
parameter sets, and every picture carries an access unit delimiter. With audio
on, AAC travels as ADTS on PID 257 (stream type 0x0F) next to the video on PID
256, on the same clock.

### POST /api/mpegts/start

Start publishing, starting the encoder too if it is not running.

**Request:**
| Field | Default | Notes |
|-------|---------|-------|
| `mode` | `listener` | `caller` pushes to `targetHost:targetPort`; `listener` waits on `port` and sends to whoever sends it a datagram first |
| `targetHost` | | Required for `caller`: the machine running OBS/VLC/ffplay |
| `targetPort` | `9000` | |
| `port` | `9000` | Listener mode's local port |
| `latencyMs` | `120` | Decoder buffer delay (PCR to PTS) |

plus the encoder fields above. A publisher already running with other settings
is replaced.

Receive a `caller` stream in OBS with a Media Source (untick "Local File") whose
Input is `udp://@:9000`, or with `ffplay udp://@:9000`.

**Response:**
```json
{
  "success": true,
  "message": "MPEG-TS/UDP streaming started",
  "port": 9000,
  "mode": "CALLER",
  "target": "192.168.1.20:9000",
  "config": { "width": 1920, "height": 1080, "bitrate": 4000000, "frameRate": 30, "codec": "H264" }
}
```

Returns `400` if `caller` mode has no `targetHost`.

### POST /api/mpegts/stop

Stop the publisher. The encoder keeps running if another output still uses it.

### GET /api/mpegts/status

```json
{
  "running": true,
  "connected": true,
  "mode": "CALLER",
  "port": 9000,
  "remoteAddress": "192.168.1.20:9000",
  "bytesSent": 75000000,
  "packetsSent": 400000,
  "framesSent": 18000,
  "uptime": 600000
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

Set exposure compensation, in the camera's compensation steps: an integer
within `camera.exposure.min`..`max` from `GET /api/status`. One step is
`camera.exposure.step` EV (often 1/6 or 1/3), so `-6` is -1 EV at 1/6 EV steps.

**Request:**
```json
{
  "ev": -6
}
```

**Response:**
```json
{
  "success": true,
  "ev": -6
}
```

---

## Recording

### POST /api/recording/start

Start local recording to MP4, alongside any stream (it shares the running
encoder) or on its own.

**Request (all optional):** the encoder fields above, plus `segmentMinutes`
(`0` for one continuous file, or `1`, `5`, `15`, `30`, `60`; default `5`).

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

### GET /api/recordings/{filename}

Download a recording as an MP4 file (`Content-Disposition: attachment`). Only
files listed by `GET /api/recordings` can be fetched. The file being written
while recording is unfinished until its segment ends.

### DELETE /api/recordings/{filename}

Delete a specific recording.

**Path parameters:**
| Parameter | Description |
|-----------|-------------|
| `filename` | Name of the recording file |

**Response:**
```json
{
  "success": true,
  "filename": "LensDaemon_Pixel7Pro_20261114_090000.mp4"
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

Get the saved S3 settings, without the secret key and with the access key ID masked.

**Response:**
```json
{
  "configured": true,
  "endpoint": "s3.us-east-1.amazonaws.com",
  "region": "us-east-1",
  "bucket": "my-recordings",
  "accessKeyId": "AKIA****MPLE",
  "pathPrefix": "lensdaemon/",
  "useHttps": true,
  "backend": "S3"
}
```

`{"configured": false}` when nothing is saved.

### POST /api/upload/s3/config

Save S3-compatible storage settings (the credentials are encrypted with the
Android Keystore).

**Request:**
```json
{
  "backend": "S3",
  "endpoint": "",
  "region": "us-east-1",
  "bucket": "my-recordings",
  "accessKeyId": "AKIAIOSFODNN7EXAMPLE",
  "secretAccessKey": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
  "pathPrefix": "lensdaemon/"
}
```

- `bucket`, `accessKeyId` and `secretAccessKey` are required.
- `backend`: `S3` (default), `BACKBLAZE_B2`, `MINIO` or `CLOUDFLARE_R2`.
- `endpoint` is a host name (and port). Left empty, it follows from the region
  for `S3` and `BACKBLAZE_B2`. Given as a URL, it is reduced to its host;
  `http://` turns HTTPS off (`useHttps`, default `true`).

**Response:**
```json
{
  "success": true,
  "message": "S3 configured",
  "backend": "S3",
  "bucket": "my-recordings",
  "endpoint": "s3.us-east-1.amazonaws.com"
}
```

### DELETE /api/upload/s3/config

Remove the saved S3 settings.

**Response:**
```json
{
  "success": true,
  "message": "S3 credentials cleared"
}
```

### POST /api/upload/s3/test

Test a connection to the bucket. With S3 settings in the body (the fields of
`POST /api/upload/s3/config`), those are tested without being saved; with no
body, the saved settings are.

**Response:**
```json
{
  "success": true,
  "message": "S3 connection successful"
}
```

On failure: `{"success": false, "error": "S3 connection test failed: S3 connection failed: HTTP 403"}`
(`400` when the settings are incomplete or none are saved).

### GET /api/upload/smb/config

Get the saved SMB settings, without the password.

**Response:**
```json
{
  "configured": true,
  "server": "192.168.1.50",
  "share": "recordings",
  "username": "backup",
  "domain": "WORKGROUP",
  "port": 445,
  "pathPrefix": "lensdaemon/"
}
```

### POST /api/upload/smb/config

Save SMB/CIFS network share settings.

**Request:**
```json
{
  "server": "192.168.1.50",
  "share": "recordings",
  "username": "backup",
  "password": "secret",
  "domain": "WORKGROUP",
  "port": 445,
  "pathPrefix": "lensdaemon/"
}
```

`server`, `share`, `username` and `password` are required; a server given as
`smb://host` or `\\host` is reduced to the host.

**Response:**
```json
{
  "success": true,
  "message": "SMB configured",
  "server": "192.168.1.50",
  "share": "recordings"
}
```

### DELETE /api/upload/smb/config

Remove the saved SMB settings.

**Response:**
```json
{
  "success": true,
  "message": "SMB credentials cleared"
}
```

### POST /api/upload/smb/test

Test a connection to the share. With SMB settings in the body, those are
tested without being saved; with no body, the saved settings are.

**Response:**
```json
{
  "success": true,
  "message": "SMB connection successful"
}
```

On failure: `{"success": false, "error": "SMB connection test failed: ..."}`.

---

## Thermal

### GET /api/thermal/status

Get current temperatures, thermal levels and the throttling in effect. Monitoring
starts when the app opens; until the first reading the temperatures are `0`.

**Response:**
```json
{
  "cpuTemperatureC": 52.0,
  "batteryTemperatureC": 36.0,
  "cpuLevel": "WARNING",
  "batteryLevel": "NORMAL",
  "overallLevel": "WARNING",
  "statusText": "Warning - Throttling",
  "isThrottling": true,
  "batteryPercent": 78,
  "isCharging": true,
  "chargingDisabled": false,
  "bitrateReductionPercent": 20,
  "resolutionReduced": false,
  "framerateReduced": false,
  "activeActions": ["REDUCE_BITRATE"]
}
```

Levels: `NORMAL`, `ELEVATED`, `WARNING`, `CRITICAL`, `EMERGENCY`. Actions:
`REDUCE_BITRATE`, `REDUCE_RESOLUTION`, `REDUCE_FRAMERATE`, `PAUSE_STREAMING`,
`DISABLE_CHARGING`. A bitrate reduction is a share of the bitrate the encoder
was started with, restored when the phone cools down.

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
  "isLockTaskActive": true,
  "isScreenLocked": false,
  "uptimeMs": 259200000,
  "lastRestartTime": 0,
  "restartCount": 0,
  "kioskEnabled": true,
  "autoStartEnabled": true,
  "screenMode": "OFF"
}
```

- `kioskEnabled`: kiosk mode is on (LensDaemon starts on boot and pins itself).
- `isLockTaskActive`: LensDaemon is pinned to the screen right now.
- Kiosk states: `DISABLED`, `ENABLED` (on and pinned), `SETUP_REQUIRED` (on,
  not pinned yet: the app's screen is not open), `NOT_DEVICE_OWNER`.

### POST /api/kiosk/enable

Turn kiosk mode on. LensDaemon starts by itself after a reboot and, when it is
the device owner, comes to the front and pins itself to the screen (lock task
mode). Without Device Owner the screen cannot be locked: the answer says so
with `"lockTask": false`.

**Response:**
```json
{
  "success": true,
  "lockTask": true,
  "message": "Kiosk mode on: LensDaemon is pinned to the screen and starts on boot"
}
```

To leave kiosk mode at the phone, hold Volume Up + Volume Down for 5 seconds
(then enter the exit PIN, if one is set).

### POST /api/kiosk/disable

Turn kiosk mode off: LensDaemon unpins itself and no longer starts on boot.

**Response:**
```json
{
  "success": true,
  "message": "Kiosk mode off: LensDaemon is unpinned and no longer starts on boot"
}
```

### GET /api/kiosk/config

Get the full kiosk configuration (the exit PIN itself is not returned).

**Response:**
```json
{
  "enabled": true,
  "autoStart": {
    "enabled": true,
    "delaySeconds": 10,
    "startStreaming": true,
    "startRtsp": true,
    "startRecording": false
  },
  "screen": {
    "mode": "OFF",
    "dimBrightness": 10,
    "autoOffTimeoutSec": 30,
    "wakeOnMotion": false,
    "keepScreenOn": false
  },
  "security": {
    "exitPinSet": false,
    "allowedExitGesture": true,
    "exitGestureTimeoutMs": 5000,
    "lockStatusBar": true,
    "lockNavigationBar": true,
    "lockNotifications": true,
    "blockScreenshots": false
  },
  "networkRecovery": {
    "autoReconnect": true,
    "reconnectIntervalSec": 30,
    "maxReconnectAttempts": 0,
    "restartStreamOnReconnect": true
  },
  "crashRecovery": {
    "autoRestart": true,
    "restartDelayMs": 3000,
    "maxRestartAttempts": 10,
    "restartWindowMinutes": 60
  }
}
```

### PUT /api/kiosk/config

Update the kiosk configuration. Every field is optional; a section given
replaces only the fields it names. `security.exitPin` sets the PIN asked for
after the exit gesture (empty for none). `enabled` turns kiosk mode on or off
like `/enable` and `/disable`, without bringing the app to the front.

**Request:**
```json
{
  "screen": {
    "mode": "DIM",
    "dimBrightness": 20
  },
  "security": {
    "exitPin": "5678"
  }
}
```

**Response:**
```json
{
  "success": true,
  "message": "Kiosk configuration updated"
}
```

### POST /api/kiosk/preset/appliance

Apply the APPLIANCE preset for 24/7 unattended operation: screen off, streaming
and RTSP auto-start, system UI locked, crash recovery and network reconnect on.
Kiosk mode stays on or off as it was; turn it on with `/api/kiosk/enable`.

**Response:**
```json
{
  "success": true,
  "message": "Appliance preset applied (24/7 unattended operation)"
}
```

### POST /api/kiosk/preset/interactive

Apply the INTERACTIVE preset for a kiosk with live preview: screen on with the
camera preview, navigation bar available, gesture exit allowed. Kiosk mode stays
on or off as it was.

**Response:**
```json
{
  "success": true,
  "message": "Interactive preset applied (kiosk with preview)"
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
