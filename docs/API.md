# LensDaemon REST API Reference

Base URL: `http://{device-ip}:8080`

The dashboard (`/`) and the setup wizard (`/setup`) are built on this API.
Requests and responses are JSON unless noted.

## Security

### Who can use the API

No token is set out of the box, and nothing in the dashboard, the setup wizard
or this API sets one. So anyone who can reach the phone on the network can use
every endpoint, watch the MJPEG preview and play the RTSP or MPEG-TS stream.
Run LensDaemon on a network you trust.

The token check is there for builds or tools that set a token
(`WebServerService.setApiToken`, kept in the app's private preferences). Once
one is set, every request needs it except `GET /api/status` and
`GET /api/device`:

```
Authorization: Bearer <token>
```

or, for clients that cannot send headers (an `<img>`, an `EventSource`), the
query parameter:

```
GET /api/recordings?token=<token>
```

A request without it gets `401`:

```json
{"error": "Authentication required", "hint": "Provide Bearer token in Authorization header"}
```

The MJPEG preview (`/mjpeg`, `/stream.mjpeg`) follows the same rule. The
bundled dashboard and setup wizard send no token, so with one set their
controls fail with 401. RTSP and MPEG-TS have no authentication.

The rate limit (below) is checked before the token, so requests with a wrong
token use up the client's allowance like any others: once a token is set, the
limit is what slows down guessing it.

### Cross-site requests

A `POST`, `PUT` or `DELETE` whose `Origin` header names another site than the
`Host` header is refused with `403`:

```json
{"error": "Cross-site request refused"}
```

This keeps a web page on another site, opened by anyone on the phone's network,
from driving the phone: turning kiosk mode off, pushing the camera to an
internet host, replacing the storage settings. The origin `null` is refused
too. The dashboard and the wizard are served by the phone, so their requests
match. Clients that send no `Origin` (curl, scripts, apps) and reads (`GET`)
are not affected. This does not protect against DNS rebinding.

### Rate limit

Each client address (the connection's; `X-Forwarded-For` is ignored) may send
bursts of 60 requests, refilled at 10 per second; `/api/` requests and MJPEG
preview connections count alike (the dashboard's pages and scripts do not).
Beyond that the answer is `429`, with a `Retry-After` header (seconds) and
`X-RateLimit-Remaining: 0`:

```json
{"error": "Rate limit exceeded", "retryAfter": 1}
```

At most 1024 clients are tracked at once.

---

## Requests and errors

POST and PUT bodies are JSON objects, read as UTF-8 unless the `Content-Type`
names another charset. A body that is not a JSON object, or is larger than
1 MB, counts as no body.

Every `/api/` request passes these steps in order: the body is read, the
cross-site check (403), the rate limit (429), the token (401), then the
endpoint itself.

Errors come back as JSON with an `error` field. Endpoints that report `success`
add `"success": false`, and some repeat the reason in `message`:

```json
{"success": false, "error": "No encoder running: give the bitrate in a start request or save it as a default"}
```

| Code | Meaning |
|------|---------|
| 200 | Success. A few outcomes that are not errors also answer 200 with `"success": false`, as noted (no more cues, a failed connection test) |
| 400 | Missing or invalid input |
| 401 | Token missing or wrong (only once a token is set) |
| 403 | Cross-site write refused |
| 404 | Unknown endpoint (`{"error": "Endpoint not found", "uri": "/api/x", "method": "GET"}`), or a recording, script file, take or upload task that does not exist |
| 409 | Conflicts with what runs now: no encoder for a bitrate change, the file being recorded, a lens without tap-to-focus, a director that runs or is too hot, a phone that cannot switch charging |
| 429 | Rate limit, see above |
| 500 | The phone could not do it: an encoder or server that did not start, a file that could not be deleted |
| 503 | The service behind the endpoint is not connected (yet): `{"error": "Camera service not available"}`, likewise the Upload, Thermal, Kiosk and Director services |

Builds without the AI Director (the `core` flavor) answer the `/api/director/`
endpoints with 503, except the script file endpoints (list, save, open and
delete), which work without it.

---

## Core

### GET /api/status

The camera, encoder and output state. The dashboard polls it every two
seconds. It needs no token.

**Response:**
```json
{
  "status": "ok",
  "timestamp": 1794646800000,
  "camera": {
    "previewActive": true,
    "streamingActive": true,
    "currentLens": "MAIN",
    "thermalPaused": false,
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
  "audio": {
    "enabled": true,
    "permission": true,
    "active": true,
    "sampleRate": 48000,
    "channels": 2,
    "bitrate": 128000,
    "framesEncoded": 28125
  },
  "recording": {
    "active": true,
    "paused": false
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
    "currentTake": 2,
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

- `camera.thermalPaused`: the phone got too hot (thermal EMERGENCY) and
  LensDaemon stopped the outputs that ran. They start again by themselves once
  it has cooled (see [How throttling works](#how-throttling-works)).
- `camera.exposure` is in the camera's compensation steps: `value * step` is
  the compensation in EV. `maxZoom` and `exposure` are present while the camera
  service is connected.
- `encoder.state` is `IDLE`, `CONFIGURING`, `READY`, `ENCODING`, `STOPPING` or
  `ERROR`. `currentBitrate` and `currentFps` are measured over the last two
  seconds, and read 0 once nothing has been encoded for two seconds. `width`,
  `height` and `frameRate` are what the encoder runs at (lower while the thermal
  CRITICAL limits are on); `bitrate` is its configured bitrate (a thermal
  bitrate cut shows in `currentBitrate` only). They are missing while no
  encoder runs.
- `recording.active` is true while a recording is open, running or paused;
  `paused` is true while it is paused.
- `thermal` is present once the web server is connected to thermal monitoring,
  which it starts; the temperatures read 0 until the first reading.
  `GET /api/thermal/status` has the detail.
- `director` is present on builds with the AI Director. The scene and cue
  fields count from 0 and appear while a script is loaded. In `stats`,
  `averageScore` is the average take score (0-10), `goodTakes` counts takes
  scoring at least 7, `sessionTime` is the seconds since the script was loaded,
  and `cueSuccessRate` is a fraction (0-1).

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

Fields left out take these values, not the phone's saved stream defaults (see
[GET /api/config](#get-apiconfig)).

There is one encoder, shared by every output. An output started while the
encoder is already running joins it as it is, and the response's `config`
reports the settings actually being sent. To change settings while streaming,
call `/api/stream/start` with the new ones.

While the thermal governor's CRITICAL limits are on, an encoder starts one
resolution step down (4K to 1080p, 1080p to 720p) and at no more than 24 fps;
`config` shows what runs. The requested settings come back when the phone
cools down.

Each successful start saves the encoder's requested settings (before any
thermal limit) as the phone's stream defaults, together with the RTSP port for
`/api/rtsp/start` and the file length for `/api/recording/start`: the settings
the request asked for when it started (or, with `/api/stream/start`,
restarted) the encoder, else those of the encoder it joined. So a start
without settings while no encoder runs makes 1080p30 H.264 at 4 Mbps the
defaults again.

The camera's auto-exposure is held at the stream's frame rate, so the stream
does not drop frames in dim light; if the camera cannot reach the requested
rate it runs at its fastest fixed rate instead.

### POST /api/stream/start

Start the encoder only, with the given settings (see above), and keep it
running until `/api/stream/stop`, whether or not any output is attached. RTSP,
MPEG-TS and recording are started by their own endpoints and share this
encoder. If the encoder is already running with different settings it is
restarted: RTSP and MPEG-TS viewers stay connected and continue from the new
stream's first keyframe, and an open recording continues in a new file (a
paused one stays paused).

**Response:**
```json
{
  "success": true,
  "message": "Encoder started",
  "config": { "width": 1920, "height": 1080, "bitrate": 4000000, "frameRate": 30, "codec": "H264" }
}
```

`500` when the encoder could not be started:

```json
{"success": false, "message": "Failed to start the encoder", "error": "Failed to start the encoder"}
```

### GET /api/stream/status

The encoder's state and counters.

**Response:**
```json
{
  "active": true,
  "encoderState": "ENCODING",
  "framesEncoded": 5400,
  "framesDropped": 0,
  "currentBitrate": 3950000,
  "currentFps": 30.0,
  "totalBytes": 90000000,
  "duration": 180.0,
  "config": { "width": 1920, "height": 1080, "bitrate": 4000000, "frameRate": 30, "codec": "H264" }
}
```

`active` is true while the encoder runs; `duration` is in seconds. The counters
and `config` are missing while no encoder runs.

### POST /api/stream/stop

Stop everything: the RTSP server, the MPEG-TS publisher, any recording, and
the encoder.

**Response:**
```json
{
  "success": true,
  "message": "Streaming stopped"
}
```

### GET /api/audio, POST /api/audio

The phone's microphone, captured whenever the encoder runs and sent with the
video as AAC-LC (48 kHz, stereo or mono depending on the phone, 128 kbps) on
every output. Audio is stamped on the camera's clock, so it stays in sync with
the picture. It needs the RECORD_AUDIO permission, granted on the phone; without
it, or with audio switched off, streams carry video only.

**Request (POST):** `{"enabled": false}` switches the microphone off (`true` on).
It applies at once while streaming: RTSP clients that connect afterwards are
offered (or not offered) an audio track, MPEG-TS receivers see the PMT change,
and a recording changes from its next file. The setting is kept across
restarts. A body without `enabled` gets `400`.

**Response (both):** the `audio` object shown under `/api/status` (POST adds
`"success": true`); `active` is false until the encoder runs, and the settings
fields are present only while audio is being captured.

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
phone has no such lens; `400` with `{"success": false, "error": "Unknown lens: x"}`
for a name that is not a lens.

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

Capture a JPEG from the current camera feed (`GET` works too).

**Response:** the JPEG image with `Content-Type: image/jpeg`. `503`
`{"error": "Snapshot not available"}` while the camera service is not
connected; `500` `{"error": "Failed to capture snapshot"}` when no frame came
within three seconds.

### GET /api/config

The running encoder's settings, the camera's, and the phone's stream defaults.

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
  },
  "defaults": {
    "codec": "H264",
    "width": 1920,
    "height": 1080,
    "frameRate": 30,
    "bitrate": 4000000,
    "rtspPort": 8554,
    "segmentMinutes": 5
  }
}
```

- `encoder` is `{}` while no encoder runs. `keyframeInterval` is in seconds and
  `bitrate` is the configured bitrate.
- `defaults` are the phone's stream defaults: what the phone's Start Stream
  button, kiosk auto-start at boot and the restart after a crash use. They are
  1080p30 H.264 at 4 Mbps, RTSP port 8554 and 5-minute files until others are
  saved. Each successful start saves its settings here (see "Encoder settings
  in start requests"), and `PUT /api/config` sets them directly. A start with
  the saved defaults (the phone's button, kiosk auto-start, the restart after a
  crash) that fails on this phone (a size, codec or frame rate its encoder
  cannot do, a port in use) is tried once more at 1080p30 on port 8554, and the
  saved defaults stay as they were.

### PUT /api/config

Change the running encoder's bitrate, the zoom, the exposure, and the stream
defaults. Every field is optional:

| Field | What it does |
|-------|--------------|
| `bitrate` | Bits per second, a whole number from 100000 to 50000000. It becomes the running encoder's configured bitrate, clamped to what the codec supports: `GET /api/config` and the status report it, encoder restarts (thermal limits, recovery) keep it, and a thermal bitrate cut applies on top of it. `409` while no encoder runs. This does not change the saved defaults: send `defaults.bitrate` for that |
| `zoom` | As `POST /api/zoom` |
| `exposureCompensation` | As `POST /api/exposure`: whole steps of the camera's size |
| `defaults` | Some or all of the stream defaults, saved: `codec` (`H264` or `H265`), `width` (even, 160-3840), `height` (even, 120-2160), `frameRate` (1-60), `bitrate` (100000-50000000), `rtspPort` (1024-65535), `segmentMinutes` (0 for one file, 1, 5, 15, 30 or 60). Fields left out keep their saved values |

Resolution, frame rate and codec of a running encoder change only with a start
request (see "Encoder settings in start requests").

The whole body is checked before anything changes, and the bitrate is applied
first, so a `409` leaves everything as it was.

**Request:**
```json
{
  "bitrate": 6000000,
  "zoom": 2.0,
  "exposureCompensation": -3,
  "defaults": { "width": 1280, "height": 720, "segmentMinutes": 15 }
}
```

**Response:** the values applied, with keys only for what was sent:
```json
{
  "success": true,
  "message": "Configuration updated",
  "bitrate": 6000000,
  "zoom": 2.0,
  "exposureCompensation": -3,
  "defaults": {
    "codec": "H264", "width": 1280, "height": 720, "frameRate": 30,
    "bitrate": 4000000, "rtspPort": 8554, "segmentMinutes": 15
  }
}
```

**Errors:**
- `400` `{"success": false, "error": ...}` with one of:
  - `Request body required`
  - `bitrate must be a whole number of bits per second between 100000 and 50000000`
  - `zoom must be a finite number`
  - `exposureCompensation must be a whole number of the camera's exposure steps`
  - `defaults must be a JSON object`
  - `defaults.codec must be H264 or H265`
  - `defaults.<key> must be a whole number`
  - `defaults.width must be an even number from 160 to 3840` (and the like for
    `height`, `frameRate`, `bitrate`, `rtspPort`, and
    `defaults.segmentMinutes must be 0 (one file), 1, 5, 15, 30 or 60`)
- `409` `{"success": false, "error": "No encoder running: give the bitrate in a start request or save it as a default"}`

### GET /api/encoder/capabilities

A fixed list, the same on every phone: it is not read from this phone's
encoder, which may support less or more. (`1080p60` is a name only: its size is
1080p's.)

**Response:**
```json
{
  "codecs": ["H264", "H265"],
  "resolutions": [
    { "name": "720p", "width": 1280, "height": 720 },
    { "name": "1080p", "width": 1920, "height": 1080 },
    { "name": "1080p60", "width": 1920, "height": 1080 },
    { "name": "4K", "width": 3840, "height": 2160 }
  ],
  "frameRates": [15, 24, 30, 60],
  "bitrateRange": { "min": 500000, "max": 20000000 }
}
```

---

## RTSP

The RTSP server runs on port 8554 and supports up to 10 concurrent clients,
over UDP or TCP (interleaved). Any path is accepted;
`rtsp://<device-ip>:8554/stream` is the advertised one. It has no
authentication.

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

**Request (all optional):** the encoder fields above, plus `port` (default
`8554`). A server that already runs keeps its port.

**Response:**
```json
{
  "success": true,
  "message": "RTSP streaming started",
  "url": "rtsp://192.168.1.50:8554/stream",
  "config": { "width": 1920, "height": 1080, "bitrate": 4000000, "frameRate": 30, "codec": "H264" }
}
```

`500` when the encoder or the server did not start (a port in use, say):

```json
{"success": false, "message": "Failed to start RTSP streaming", "error": "Failed to start RTSP streaming"}
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

`uptime` is in milliseconds.

---

## MPEG-TS over UDP

Plain MPEG transport stream in UDP datagrams (7 TS packets each). There is no
encryption, no authentication and no retransmission, so use it on a wired or
solid WiFi LAN. It has lower latency than RTSP in OBS. Each receiver starts on
a keyframe with its parameter sets, and every picture carries an access unit
delimiter. With audio on, AAC travels as ADTS on PID 257 (stream type 0x0F) next
to the video on PID 256, on the same clock.

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

`400` when `caller` mode has no `targetHost`, or with
`{"error": "Cannot resolve targetHost"}`; `500` with
`{"success": false, "message": "Failed to start MPEG-TS/UDP streaming", "error": "Failed to start MPEG-TS/UDP streaming"}`
when the encoder or the publisher did not start.

### POST /api/mpegts/stop

Stop the publisher. The encoder keeps running if another output still uses it.

**Response:**
```json
{
  "success": true,
  "message": "MPEG-TS/UDP streaming stopped"
}
```

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

Set the zoom of the lens in use, from 1x to its `maxZoom` (see `/api/status`).
The lens does not change: switch lenses with `/api/lens/{lens}`.

**Request:**
```json
{
  "zoom": 2.5
}
```

`level` is taken as well when the body has no `zoom`.

**Response:** the zoom applied, within the lens's range:
```json
{
  "success": true,
  "zoom": 2.5
}
```

`400` `{"success": false, "error": "zoom must be a finite number"}` when the zoom
is missing, not a number, or not finite.

### POST /api/focus

Focus on a point of the picture. `x` and `y` are shares of the frame as the
stream shows it, from the left and the top edge (0 to 1; values beyond are
clamped). The camera stays in continuous video autofocus and is given a focus
region there; the exposure is not metered on it. A lens without continuous
video autofocus (fixed focus, or one-shot autofocus only) has no tap-to-focus:
the request is refused with `409` and nothing changes, so a success means the
region was set. The answer does not report a focus state.

**Request:**
```json
{
  "x": 0.5,
  "y": 0.4
}
```

**Response:** the point used:
```json
{
  "success": true,
  "x": 0.5,
  "y": 0.4
}
```

`{"reset": true}` drops the focus and metering regions and goes back to plain
continuous autofocus, answering `{"success": true, "reset": true}`.

**Errors:**
- `400` `{"success": false, "error": "x and y must be finite numbers from 0 to 1"}`
  when `x` or `y` is missing, not a number, or not finite
- `409` `{"success": false, "error": "This lens has no tap-to-focus (it needs continuous video autofocus)"}`
- `409` `{"success": false, "error": "The camera is not open"}` before the
  camera has opened a lens

### POST /api/exposure

Set exposure compensation, in the camera's compensation steps: a whole number
within `camera.exposure.min`..`max` from `GET /api/status` (values beyond are
clamped). One step is `camera.exposure.step` EV (often 1/6 or 1/3), so `-6` is
-1 EV at 1/6 EV steps.

**Request:**
```json
{
  "ev": -6
}
```

**Response:** the compensation applied:
```json
{
  "success": true,
  "ev": -6
}
```

`400` `{"success": false, "error": "ev must be a whole number of the camera's exposure steps"}`
when `ev` is missing or not a whole number.

---

## Recording

Recordings are MP4 files (H.264 or H.265, with AAC audio when the microphone is
on) in the app's folder on the phone, named
`LensDaemon_{model}_{yyyyMMdd_HHmmss}_seg{NNN}.mp4` (no `_seg` part for one
continuous file).

### POST /api/recording/start

Start recording, alongside any stream (it shares the running encoder) or on
its own.

**Request (all optional):** the encoder fields above, plus `segmentMinutes`:
a new file every `1`, `5`, `15`, `30` or `60` minutes, or `0` for one
continuous file (default `5`; other values count as `5`).

**Response:**
```json
{
  "success": true,
  "message": "Recording started",
  "segmentDuration": "5 minutes",
  "outputPath": "/storage/emulated/0/Android/data/com.lensdaemon/files/recordings"
}
```

While a recording is open (running or paused) it is left as it is and the
answer says so, so a double click or a retry does no harm:

```json
{
  "success": true,
  "alreadyRecording": true,
  "paused": false,
  "message": "Already recording",
  "outputPath": "/storage/emulated/0/Android/data/com.lensdaemon/files/recordings"
}
```

When it is paused, `"paused": true` and the message is
`Recording is paused: resume it with POST /api/recording/resume`.

`500` when it could not start (the encoder did not start, or there is less
than 500 MB free after the clean-up, see [Storage](#storage)):

```json
{"success": false, "message": "Failed to start recording", "error": "Failed to start recording"}
```

### POST /api/recording/stop

Stop the recording and close its file. The encoder keeps running if a stream
(or `/api/stream/start`) still uses it.

**Response:** the files this recording wrote:
```json
{
  "success": true,
  "message": "Recording stopped",
  "segmentCount": 2,
  "segments": [
    "/storage/emulated/0/Android/data/com.lensdaemon/files/recordings/LensDaemon_Pixel_7_Pro_20261114_090000_seg000.mp4",
    "/storage/emulated/0/Android/data/com.lensdaemon/files/recordings/LensDaemon_Pixel_7_Pro_20261114_090500_seg001.mp4"
  ]
}
```

### POST /api/recording/pause

Pause the recording: nothing is written until it resumes, into the same file.

**Response:**
```json
{
  "success": true,
  "message": "Recording paused"
}
```

`400` `{"success": false, "message": "Not recording", "error": "Not recording"}`
when no recording runs.

### POST /api/recording/resume

Resume a paused recording.

**Response:**
```json
{
  "success": true,
  "message": "Recording resumed"
}
```

`400` `{"success": false, "message": "Not paused", "error": "Not paused"}`
when the recording is not paused.

### GET /api/recording/status

**Response:**
```json
{
  "recording": true,
  "paused": false,
  "state": "RECORDING",
  "currentFile": "/storage/emulated/0/Android/data/com.lensdaemon/files/recordings/LensDaemon_Pixel_7_Pro_20261114_090500_seg001.mp4",
  "framesInSegment": 1800,
  "totalFrames": 10800,
  "bytesInSegment": 30000000,
  "totalBytes": 180000000,
  "segmentIndex": 1,
  "durationSec": 360.0,
  "avgBitrateKbps": 4000.0,
  "completedSegments": [
    "/storage/emulated/0/Android/data/com.lensdaemon/files/recordings/LensDaemon_Pixel_7_Pro_20261114_090000_seg000.mp4"
  ]
}
```

- `recording` is true while a recording is open, running or paused; `paused`
  while it is paused.
- `state` is `IDLE`, `STARTING`, `RECORDING`, `PAUSED`, `STOPPING` or `ERROR`.
- `currentFile` is the file being written (`""` when none), which download,
  delete and upload refuse with 409 until it closes.

---

## Recordings

The recordings on the phone, from the moment the app starts (no recording has
to run first).

### GET /api/recordings

Newest first. The file being written is listed too.

**Response:**
```json
{
  "count": 1,
  "totalSizeBytes": 150000000,
  "recordings": [
    {
      "name": "LensDaemon_Pixel_7_Pro_20261114_090000_seg000.mp4",
      "path": "/storage/emulated/0/Android/data/com.lensdaemon/files/recordings/LensDaemon_Pixel_7_Pro_20261114_090000_seg000.mp4",
      "sizeBytes": 150000000,
      "sizeMB": 143.05,
      "lastModified": 1794647100000,
      "lastModifiedFormatted": "2026-11-14 09:05:00",
      "ageHours": 2
    }
  ]
}
```

`lastModified` is in milliseconds since 1970; `lastModifiedFormatted` is in the
phone's time zone.

### GET /api/recordings/{name}

Download a recording, for a name `GET /api/recordings` lists, as an MP4 file with

```
Content-Disposition: attachment; filename="LensDaemon_Pixel_7_Pro_20261114_090000_seg000.mp4"; filename*=UTF-8''LensDaemon_Pixel_7_Pro_20261114_090000_seg000.mp4
```

(in `filename`, characters other than letters, digits, `.`, `_` and `-` become
`_`; `filename*` carries the name as it is).

**Errors:**
- `400` `{"error": "Invalid filename"}` for an empty name or one holding `/`,
  `\`, `..` or a NUL character
- `404` `{"error": "Recording not found"}` for a name that is not in the list
- `409` `{"success": false, "error": "This file is being recorded: stop the recording or wait for the next file"}`

### DELETE /api/recordings/{name}

Delete a recording, for a name `GET /api/recordings` lists.

**Response:**
```json
{
  "success": true,
  "filename": "LensDaemon_Pixel_7_Pro_20261114_090000_seg000.mp4"
}
```

The same `400`, `404` and `409` as for downloading, and `500`
`{"success": false, "filename": "...", "error": "Could not delete the recording"}`.

---

## Storage

### Retention policy

Recordings are deleted, oldest first, when they are older than 7 days, when all
recordings together take more than 10 GB, or to keep 1 GB of the phone's
storage free. The file being recorded is never deleted. The policy is fixed:
there is no setting for it.

It runs:
- when a recording starts,
- every hour while a recording is open,
- when free space falls under 1 GB or under 500 MB (watched every 30 seconds
  once a recording has run since the app started, and every 5 seconds while
  one is open),
- on `POST /api/storage/cleanup`.

A recording does not start with less than 500 MB free after the clean-up. A
recording whose clean-up cannot bring free space back over 500 MB is stopped,
with the error `Storage full - recording stopped`, while there is still room to
close its file.

### GET /api/storage/status

Storage space and the recordings on the phone, from the moment the app starts.

**Response:**
```json
{
  "state": "IDLE",
  "warningLevel": "NORMAL",
  "totalRecordings": 5,
  "totalRecordingsSizeBytes": 524288000,
  "storage": {
    "totalBytes": 128849018880,
    "availableBytes": 64424509440,
    "usedBytes": 64424509440,
    "path": "/storage/emulated/0/Android/data/com.lensdaemon/files",
    "totalGB": 120.0,
    "availableGB": 60.0,
    "usagePercent": 50.0,
    "isLowSpace": false,
    "isCriticallyLowSpace": false
  }
}
```

- `state` is the recorder's: `IDLE`, `RECORDING`, `PAUSED` or `ERROR`.
- `warningLevel` is `NORMAL`, `LOW` (under 1 GB free) or `CRITICAL` (under
  500 MB), as are `isLowSpace` and `isCriticallyLowSpace`.

### POST /api/storage/cleanup

Apply the retention policy now, and answer once it has finished with what this
clean-up removed.

**Response:**
```json
{
  "success": true,
  "filesDeleted": 2,
  "bytesFreed": 123456789,
  "failed": []
}
```

`failed` lists the paths of files the policy chose that could not be deleted.

---

## Upload

Recordings can be uploaded to an S3-compatible bucket or an SMB share (see
[Network storage settings](#network-storage-settings)). Uploads are started
per recording, with `POST /api/upload/enqueue` (the dashboard's "Upload to S3" /
"Upload to SMB"): finished recordings are not uploaded automatically.

How uploads run:
- One upload at a time, in the order they were queued.
- A failed upload is tried 3 times in all, 2 s and then 4 s apart. One that
  fails every time moves to a failed list, kept until it is retried or
  cancelled.
- The queue, the upload in progress (which starts over), the failed list and
  the last 100 completed uploads are kept on the phone, so they survive an app
  restart. Queued uploads carry on when the upload service starts.
- Failed uploads are queued again when the network comes back (a network after
  a loss, or internet access returning), while kiosk mode's
  `networkRecovery.autoReconnect` is on (the default). While uploads are
  paused they wait in the queue for `/api/upload/start`.
- Each file goes to the destination's `pathPrefix` plus its `remotePath`.

### GET /api/upload/status

**Response:**
```json
{
  "state": "UPLOADING",
  "isS3Configured": true,
  "isSmbConfigured": false,
  "currentFile": "LensDaemon_Pixel_7_Pro_20261114_090000_seg000.mp4",
  "currentProgress": 65,
  "queue": {
    "totalCount": 4,
    "pendingCount": 1,
    "uploadingCount": 1,
    "completedCount": 1,
    "failedCount": 1,
    "totalBytes": 300000000,
    "uploadedBytes": 247500000,
    "currentProgress": 65
  }
}
```

- `state` is `IDLE` (nothing to upload), `UPLOADING`, `PAUSED` or `ERROR`.
- `currentFile` is the file being uploaded (`""` when none); `currentProgress`
  is its progress in percent (0-100).
- `queue.failedCount` counts the uploads in the failed list.

### GET /api/upload/queue

The waiting, completed and failed uploads. The one in progress is in
`GET /api/upload/status`.

**Response:**
```json
{
  "pending": [
    {
      "id": "6f1c2d3e-4b5a-4c7d-8e9f-0a1b2c3d4e5f",
      "fileName": "LensDaemon_Pixel_7_Pro_20261114_090500_seg001.mp4",
      "localPath": "/storage/emulated/0/Android/data/com.lensdaemon/files/recordings/LensDaemon_Pixel_7_Pro_20261114_090500_seg001.mp4",
      "remotePath": "LensDaemon_Pixel_7_Pro_20261114_090500_seg001.mp4",
      "destination": "S3",
      "status": "PENDING",
      "fileSize": 150000000,
      "bytesUploaded": 0,
      "progress": 0,
      "retryCount": 0,
      "error": ""
    }
  ],
  "completed": [
    {
      "id": "0b9e8d7c-6a5b-4c3d-2e1f-a0b1c2d3e4f5",
      "fileName": "LensDaemon_Pixel_7_Pro_20261114_085500_seg002.mp4",
      "destination": "SMB",
      "status": "COMPLETED",
      "fileSize": 150000000
    }
  ],
  "failed": [
    {
      "id": "9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d",
      "fileName": "LensDaemon_Pixel_7_Pro_20261114_085000_seg001.mp4",
      "destination": "S3",
      "error": "HTTP 403 AccessDenied",
      "retryCount": 3,
      "fileSize": 150000000
    }
  ]
}
```

`progress` is in percent (0-100); `error` is the last failure (`""` when none).

### POST /api/upload/start

Start (or resume) working through the queue.

**Response:**
```json
{
  "success": true,
  "message": "Upload processing started"
}
```

### POST /api/upload/stop

Pause the queue: the file being uploaded finishes, then no new upload starts
until `/api/upload/start`, or until a new upload is queued.

**Response:**
```json
{
  "success": true,
  "message": "Upload processing stopped"
}
```

### POST /api/upload/enqueue

Upload a recording. Only recordings `GET /api/recordings` lists can be
uploaded; uploads start at once if they are not running.

**Request:**
```json
{
  "filePath": "/storage/emulated/0/Android/data/com.lensdaemon/files/recordings/LensDaemon_Pixel_7_Pro_20261114_090000_seg000.mp4",
  "destination": "S3"
}
```

| Field | Notes |
|-------|-------|
| `filePath` | The `path` `GET /api/recordings` gives, or |
| `file` | the recording's `name` |
| `destination` | `S3` (default) or `SMB` |
| `remotePath` | Name at the destination, under its `pathPrefix`; default: the recording's name |
| `deleteAfterUpload` | `true` deletes the recording from the phone once it is uploaded (default `false`) |

**Response:**
```json
{
  "success": true,
  "taskId": "6f1c2d3e-4b5a-4c7d-8e9f-0a1b2c3d4e5f",
  "fileName": "LensDaemon_Pixel_7_Pro_20261114_090000_seg000.mp4",
  "destination": "S3",
  "message": "File enqueued for upload"
}
```

**Errors:**
- `400` `{"success": false, "error": "Only recordings listed by GET /api/recordings can be uploaded"}`,
  `{"success": false, "error": "Invalid destination: FTP. Use S3 or SMB"}`, or
  `{"success": false, "error": "Failed to enqueue the recording: it no longer exists"}`
  (`{"error": "Request body required"}` without a body)
- `409` `{"success": false, "error": "Recording in progress: wait for the file to close"}`
  for the file being recorded
- `503` `{"error": "Upload service not available"}`

### DELETE /api/upload/task/{id}

Cancel a waiting upload, or drop one from the failed list. An upload in
progress cannot be cancelled.

**Response:**
```json
{
  "success": true,
  "taskId": "6f1c2d3e-4b5a-4c7d-8e9f-0a1b2c3d4e5f"
}
```

`404` `{"success": false, "taskId": "...", "error": "No pending or failed upload with this ID"}`.

### POST /api/upload/retry

Queue every failed upload again, with a fresh set of attempts, and start
uploading if it is not running.

**Response:**
```json
{
  "success": true,
  "retried": 2
}
```

### POST /api/upload/clear

Drop every waiting upload (not the one in progress, nor the failed list).

**Response:**
```json
{
  "success": true,
  "message": "Pending uploads cleared"
}
```

---

## Network storage settings

### GET /api/upload/s3/config

Get the saved S3 settings, without the secret key and with the access key ID
masked (its first four characters, then `****`).

**Response:**
```json
{
  "configured": true,
  "endpoint": "s3.us-east-1.amazonaws.com",
  "region": "us-east-1",
  "bucket": "my-recordings",
  "accessKeyId": "AKIA****",
  "pathPrefix": "lensdaemon/",
  "useHttps": true,
  "backend": "S3"
}
```

`{"configured": false}` when nothing is saved.

### POST /api/upload/s3/config

Save S3-compatible storage settings (the credentials are encrypted with the
Android Keystore, and kept out of backups and device transfers).

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
  for `S3` and `BACKBLAZE_B2`; the others need one. Given as a URL, it is
  reduced to its host; `http://` turns HTTPS off (`useHttps`, default `true`).
- Requests are signed with AWS Signature V4, with path-style URLs.

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

`400` with `{"error": "bucket, accessKeyId, and secretAccessKey are required"}`
or `{"error": "endpoint is required for this backend type"}`.

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

Test a connection to the bucket (listing at most one object). With S3 settings
in the body (the fields of `POST /api/upload/s3/config`, with `bucket`), those
are tested without being saved; with no body, the saved settings are.

**Response:**
```json
{
  "success": true,
  "message": "S3 connection successful"
}
```

A failed test answers `200` with the HTTP status and S3's error code:
`{"success": false, "error": "S3 connection test failed: HTTP 403 SignatureDoesNotMatch"}`
(or `S3 connection test timed out` after 15 seconds). `400` when the settings
sent are incomplete or none are saved (`{"success": false, "error": "S3 not configured"}`).

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

Save SMB/CIFS network share settings (SMB 2/3; the password is encrypted like
the S3 keys).

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
`smb://host` or `\\host` is reduced to the host. Folders in `pathPrefix` that
do not exist are created before the first upload.

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

Test a connection to the share. With SMB settings in the body (with `server`),
those are tested without being saved; with no body, the saved settings are. The
test signs in, creates the `pathPrefix` folders when they are missing, and
checks that files can be created there, so a wrong path, a read-only share or a
read-only account fails here rather than on the first upload.

**Response:**
```json
{
  "success": true,
  "message": "SMB connection successful"
}
```

A failed test answers `200` with `{"success": false, "error": "SMB connection test failed: ..."}`,
naming the folder when it could not be created or written to.

---

## Thermal

### How throttling works

Thermal monitoring reads the CPU and battery temperatures every 5 seconds and
gives each a level. The thresholds come from the phone's thermal profile:
bundled for some phones (`GET /api/thermal/profiles`), generic otherwise, or an
override saved with `PUT /api/thermal/profile`. With the generic profile:

| Level | CPU | Battery | What happens |
|-------|-----|---------|--------------|
| `ELEVATED` | 45°C | 38°C | Nothing |
| `WARNING` | 50°C | 42°C | CPU: bitrate cut by 20%. Battery: charging held |
| `CRITICAL` | 55°C | 45°C | CPU: bitrate cut by 40%, and the encoder restarts one resolution step down (4K to 1080p, 1080p to 720p) at no more than 24 fps. Battery: charging held |
| `EMERGENCY` | 60°C | 48°C | Every output and the encoder stop until the phone cools. Battery: charging held |

- `ELEVATED` starts 5°C (CPU) or 4°C (battery) below `WARNING` in every
  profile.
- A level is left only once the temperature is 3°C (CPU) or 2°C (battery)
  below its threshold, so readings around a threshold do not switch the
  throttling on and off. Each measure is worked out from the CPU and battery
  levels together, so one cooling down never lifts a measure the other needs.
- The bitrate cut is a share of the configured bitrate, never below 500 kbps
  (or the configured bitrate when that is lower). It applies to every encoder
  that starts while it lasts.
- At `CRITICAL` the outputs stay connected while the encoder restarts: viewers
  continue from the next keyframe and a recording continues in a new file (a
  paused one stays paused). The requested settings come back once the CPU has
  cooled.
- At `EMERGENCY` LensDaemon remembers what ran (RTSP with its port, MPEG-TS
  with its settings, the recording with its file length and whether it was
  paused, the encoder settings) and stops it all; `/api/status` reports
  `camera.thermalPaused`. Once neither the CPU nor the battery is at
  `EMERGENCY` any more (with the generic CPU thresholds: below 57°C), exactly
  those outputs start again. MPEG-TS receivers in listener mode have to send a
  datagram again. Starting or stopping an output during the pause cancels the
  automatic restart. The CPU is kept awake during the pause, so the cool-down is noticed
  with the screen off.
- "Charging held" stops charging only on phones whose charging switch the app
  can write (`chargeControl.available`). Elsewhere the phone keeps charging and
  `chargeControl.recommendation` says to unplug the charger.
- `ALERT_USER` in the event log only marks a level that rose to `CRITICAL` or
  `EMERGENCY`; no notification is posted.

### GET /api/thermal/status

Current temperatures, levels and the throttling in force. Monitoring starts
when the app or the web server starts; until the first reading the temperatures
are `0`.

**Response:**
```json
{
  "cpuTemperatureC": 52.0,
  "batteryTemperatureC": 43.5,
  "cpuLevel": "WARNING",
  "batteryLevel": "WARNING",
  "overallLevel": "WARNING",
  "statusText": "Warning - Throttling",
  "isThrottling": true,
  "batteryPercent": 78,
  "isCharging": true,
  "chargingDisabled": true,
  "bitrateReductionPercent": 20,
  "resolutionReduced": false,
  "framerateReduced": false,
  "activeActions": ["REDUCE_BITRATE", "DISABLE_CHARGING"],
  "chargeControl": {
    "available": false,
    "state": "THERMAL_HOLD",
    "recommendation": "Unplug charger to reduce battery temperature (43.5°C)"
  }
}
```

- Levels: `NORMAL`, `ELEVATED`, `WARNING`, `CRITICAL`, `EMERGENCY`;
  `overallLevel` is the higher of the two.
- `activeActions`: the measures in force, from `REDUCE_BITRATE`,
  `REDUCE_RESOLUTION`, `REDUCE_FRAMERATE`, `PAUSE_STREAMING` and
  `DISABLE_CHARGING`; `isThrottling` is true while there is any.
- `chargingDisabled` means the governor wants charging held for heat. Whether
  the phone actually stops charging is `chargeControl.available`: false on most
  phones, which keep charging; `recommendation` then says what to do (it may be
  `""`). `chargeControl.state` is the battery bypass's state (see
  `GET /api/thermal/battery`).

### GET /api/thermal/history

Temperatures and levels, one entry a minute for up to the last 24 hours. Long
histories are thinned to every n-th entry (roughly 100 to 200 points). The
history is saved on the phone about every 10 minutes and when monitoring stops,
so it survives restarts.

**Response:**
```json
{
  "count": 2,
  "data": [
    { "timestamp": 1794643200000, "cpuTemp": 41.0, "batteryTemp": 33.5, "cpuLevel": "NORMAL", "batteryLevel": "NORMAL" },
    { "timestamp": 1794643260000, "cpuTemp": 50.5, "batteryTemp": 34.0, "cpuLevel": "WARNING", "batteryLevel": "NORMAL" }
  ]
}
```

`timestamp` is in milliseconds since 1970; the levels are the governor's.

### GET /api/thermal/stats

A summary of the last 24 hours of history.

**Response:**
```json
{
  "periodStartMs": 1794643200000,
  "periodEndMs": 1794646800000,
  "cpu": { "minTemp": 38.5, "maxTemp": 52.0, "avgTemp": 44.1 },
  "battery": { "minTemp": 31.0, "maxTemp": 36.5, "avgTemp": 33.8 },
  "timeInWarningMs": 600000,
  "timeInCriticalMs": 0,
  "timeInEmergencyMs": 0,
  "throttleEventCount": 2
}
```

- The time at a level counts the higher of the CPU and battery levels. Each
  step between history entries counts for at most two minutes, so time when the
  app was not running is left out.
- `throttleEventCount` counts the level changes to `WARNING` or above.
- With no history yet, the temperatures and times are `0`.

### GET /api/thermal/events

The governor's level changes in the last 24 hours (the last 500 are kept,
saved like the history).

**Response:**
```json
{
  "count": 1,
  "events": [
    {
      "timestamp": 1794645000000,
      "source": "CPU",
      "oldLevel": "ELEVATED",
      "newLevel": "WARNING",
      "temperatureC": 50.5,
      "actions": ["REDUCE_BITRATE"]
    }
  ]
}
```

`source` is `CPU` or `BATTERY`. `actions` are the measures in force after the
change, plus `ALERT_USER` when the level rose to `CRITICAL` or `EMERGENCY`.

### GET /api/thermal/battery

The battery and the battery bypass (charge limiting).

**Response:**
```json
{
  "batteryPercent": 80,
  "batteryTemperatureC": 31.2,
  "isCharging": true,
  "chargingDisabled": false,
  "batteryLevel": "NORMAL",
  "bypass": {
    "state": "DISABLED",
    "targetPercent": 50,
    "resumePercent": 40,
    "chargingBlocked": false,
    "hasHardwareControl": false,
    "softwareOnly": true,
    "recommendation": "Battery at 80%. Unplug charger to preserve battery health"
  }
}
```

- `chargingDisabled`: the governor wants charging held for heat.
- `bypass` is missing when thermal monitoring runs without a bypass.
- Charging can only be switched off on phones whose kernel offers a charging
  switch the app itself may write (`hasHardwareControl`; there is no root
  path). There the bypass charges to `targetPercent`, holds (`HOLDING`),
  charges again below `resumePercent` (`CHARGING`), holds while the battery is
  at `WARNING` or above (`THERMAL_HOLD`), and holds on request
  (`MANUAL_HOLD`, see below). `chargingBlocked` is true while charging is
  actually switched off.
- Most phones have no such switch (`softwareOnly`): the state stays `DISABLED`,
  or `THERMAL_HOLD` as what the battery needs, `chargingBlocked` is always
  false, and `recommendation` says what to do instead, for example
  `Unplug charger to reduce battery temperature (43.5°C)`,
  `Battery too warm to charge (43.5°C); let it cool before plugging in`,
  `Battery at 80%. Unplug charger to preserve battery health`,
  `Battery low (35%). Plug in charger`, or `""`.

### POST /api/thermal/battery/disable, POST /api/thermal/battery/enable

`/disable` holds charging until `/enable` (a manual hold: a cool-down never
ends it). `/enable` ends the manual hold; a thermal hold stays until the battery
cools.

**Response:**
```json
{
  "success": true,
  "state": "MANUAL_HOLD",
  "chargingBlocked": true
}
```

**Errors:**
- `409` on a phone without a charging switch the app can write; nothing
  changes:
  ```json
  {
    "success": false,
    "softwareOnly": true,
    "error": "This phone does not let LensDaemon switch charging off",
    "recommendation": "Unplug the charger to stop charging"
  }
  ```
- `500` `{"success": false, "state": "...", "chargingBlocked": true, "error": "The phone's charging switch did not accept the change"}`
  when the switch rejected the change.
- `503` `{"error": "Thermal service not available"}` or
  `{"error": "Battery bypass not available"}`.

### GET /api/thermal/profile

The thermal profile in force and the thresholds the governor uses (applied
from the app's start).

**Response:**
```json
{
  "profile": {
    "deviceModel": "Pixel 7",
    "socModel": "Tensor G2",
    "displayName": "Google Pixel 7 (Tensor G2)",
    "cpuWarnC": 46.0,
    "cpuCriticalC": 51.0,
    "cpuEmergencyC": 56.0,
    "batteryWarnC": 41.0,
    "batteryCriticalC": 44.0,
    "batteryEmergencyC": 47.0,
    "sustainableBitrateKbps": 4000,
    "sustainableResolution": "1080p",
    "sustainableFps": 30,
    "notes": "Slightly improved thermals over G1 but still runs warm."
  },
  "source": "auto-detected",
  "config": {
    "cpuNormalMaxC": 41.0,
    "cpuWarningC": 46.0,
    "cpuCriticalC": 51.0,
    "cpuEmergencyC": 56.0,
    "batteryWarningC": 41.0,
    "batteryCriticalC": 44.0,
    "batteryEmergencyC": 47.0
  }
}
```

`source` is `auto-detected` (a bundled profile matched by the phone's model,
device name or chip, else the generic one, `deviceModel: "generic"`) or
`override`. `sustainable*` and `notes` are information only.

### PUT /api/thermal/profile

Save an override with your own thresholds; it applies at once and after
restarts, until `DELETE /api/thermal/profile`.

**Request:**
```json
{
  "displayName": "Hot room",
  "cpuWarnC": 45,
  "cpuCriticalC": 50,
  "cpuEmergencyC": 55,
  "batteryWarnC": 38,
  "batteryCriticalC": 42,
  "batteryEmergencyC": 45
}
```

Fields: `deviceModel` (default `custom`), `displayName` (`Custom Override`),
`cpuWarnC` (50), `cpuCriticalC` (55), `cpuEmergencyC` (60), `batteryWarnC`
(42), `batteryCriticalC` (45), `batteryEmergencyC` (48),
`sustainableBitrateKbps` (4000), `sustainableResolution` (`1080p`),
`sustainableFps` (30), `notes`. A field left out takes the default shown, not
the value in force, so send all six thresholds. The warn, critical and
emergency thresholds must rise in that order.

**Response:**
```json
{
  "success": true,
  "message": "Profile override applied",
  "profile": "Hot room"
}
```

`400` `{"error": "CPU thresholds must be in ascending order: warn < critical < emergency"}`
(or the battery's).

### DELETE /api/thermal/profile

Remove the override and go back to the detected profile.

**Response:**
```json
{
  "success": true,
  "message": "Override cleared, reverted to auto-detected profile",
  "profile": "Google Pixel 7 (Tensor G2)"
}
```

### GET /api/thermal/profile/device

How the phone identifies itself to the profile matching (`socModel` is the
chip's model as Android reports it, empty on some phones).

**Response:**
```json
{
  "manufacturer": "Google",
  "model": "Pixel 7",
  "device": "panther",
  "board": "panther",
  "socModel": "GS201",
  "sdkVersion": 34
}
```

### GET /api/thermal/profiles

The bundled profiles, in the shape of `profile` above.

**Response:**
```json
{
  "count": 10,
  "activeProfile": "Pixel 7",
  "profiles": [ { "deviceModel": "Pixel 6", "displayName": "Google Pixel 6 (Tensor G1)", "cpuWarnC": 45.0 } ]
}
```

(each profile carries all the fields shown under `GET /api/thermal/profile`;
`activeProfile` is the `deviceModel` in force).

### POST, GET, DELETE /api/thermal/stress-test

A thermal stress test is planned but not wired in: these endpoints answer `503`
`{"error": "Stress test not available"}`.

---

## Kiosk

Kiosk mode turns the phone into an appliance: LensDaemon pins itself to the
screen and comes back after a reboot or a crash. Pinning needs LensDaemon to be
the device owner:

```bash
adb shell dpm set-device-owner com.lensdaemon/.AdminReceiver
```

What kiosk mode does, with Device Owner:
- **Pinned** (lock task mode): Home and Recents are blocked, the status bar is
  hidden; the power menu stays available while the exit gesture is allowed.
- **Restrictions** while kiosk mode is on: no safe boot (which would start the
  phone without LensDaemon), no factory reset, no USB file transfer. They are
  set as soon as kiosk mode is turned on, pinned or not (and again when
  LensDaemon pins itself), and lifted when it is turned off.
- **Screen** (while pinned): `PREVIEW` shows the camera preview; `DIM` lowers
  the brightness to `dimBrightness` (0-255); `BLACK` covers the screen with
  black at that brightness; `OFF`, or `keepScreenOn: false`, lets the phone's
  own screen timeout turn the display off (nothing locks the phone, so the exit
  gesture stays reachable). Streaming goes on with the display off: LensDaemon
  keeps the CPU and Wi-Fi awake while the encoder runs. `autoOffTimeoutSec` and
  `wakeOnMotion` are not used.
- **Leaving at the phone** (while `allowedExitGesture` is on): hold Volume Up +
  Volume Down for `exitGestureTimeoutMs` (5 s by default), then enter the exit
  PIN if one is set (after 5 wrong PINs the PIN entry is locked for a minute).
  The gesture and every PIN attempt are logged.

**After a reboot**, when kiosk mode and `autoStart.enabled` are on:
- Android delivers the boot only after the phone's first unlock, so an
  unattended restart (after a power cut) needs a phone without a PIN, pattern
  or password lock.
- At once: the web server (dashboard and API) and thermal monitoring.
- After `autoStart.delaySeconds` (0-120 s): the app's screen, which pins itself
  again, and the outputs `autoStart` asks for: RTSP (`startRtsp`) and a
  recording (`startRecording`), each on its own, with the stream defaults (see
  `GET /api/config`). `startStreaming` alone starts nothing. The settings are
  read again at that point, so turning kiosk mode or its auto-start off during
  the wait (from the dashboard, which is already up) starts nothing more.
- Without Device Owner, Android 10+ does not let LensDaemon open its screen by
  itself, and Android 11+ gives it no camera when started at boot: on Android
  11+ the camera is therefore not started, and only the web server and thermal
  monitoring come up.

**After a crash or when Android kills the app**, Android restarts LensDaemon's
camera service. With kiosk mode, `autoStart.enabled` and
`crashRecovery.autoRestart` on, it brings back the auto-start outputs (with the
stream defaults), thermal monitoring and, as device owner, the pinned screen.
At most `crashRecovery.maxRestartAttempts` restarts happen within
`crashRecovery.restartWindowMinutes` (0 attempts means no limit); each is
counted in `restartCount`. `restartDelayMs` is not used: Android chooses when
to restart.

**Network recovery** is only `networkRecovery.autoReconnect`: failed uploads
are queued again when the network comes back. The other `networkRecovery`
settings are not used.

### GET /api/kiosk/status

**Response:**
```json
{
  "state": "ENABLED",
  "isDeviceOwner": true,
  "isLockTaskActive": true,
  "isScreenLocked": false,
  "uptimeMs": 259200000,
  "lastRestartTime": 1794643200000,
  "restartCount": 1,
  "kioskEnabled": true,
  "autoStartEnabled": true,
  "screenMode": "OFF"
}
```

- `kioskEnabled`: kiosk mode is on. `isLockTaskActive`: LensDaemon is pinned to
  the screen right now.
- `state`: `DISABLED`, `ENABLED` (on and pinned), `SETUP_REQUIRED` (on, not
  pinned yet: the app's screen is not open) or `NOT_DEVICE_OWNER`.
- `restartCount` and `lastRestartTime` count the automatic restarts after a
  crash or kill, and are kept across restarts. `isScreenLocked` is always
  false: kiosk mode never locks the screen.
- `uptimeMs` is the time since LensDaemon was last pinned (0 when it has not
  been since the app started).

### POST /api/kiosk/enable

Turn kiosk mode on. As device owner, LensDaemon comes to the front and pins
itself (`"lockTask": true`).

**Response:**
```json
{
  "success": true,
  "lockTask": true,
  "message": "Kiosk mode on: LensDaemon is pinned to the screen and starts on boot. Android starts it only after the phone's first unlock, so restarting unattended needs a phone without a PIN, pattern or password lock"
}
```

Without Device Owner the setting is saved but the screen cannot be pinned:

```json
{
  "success": true,
  "lockTask": false,
  "message": "Kiosk mode is on, but without Device Owner LensDaemon can't lock the screen, and Android won't open its screen or (Android 11+) its camera by itself after a reboot: adb shell dpm set-device-owner com.lensdaemon/.AdminReceiver"
}
```

### POST /api/kiosk/disable

Turn kiosk mode off: LensDaemon unpins itself, lifts the kiosk restrictions and
no longer starts on boot.

**Response:**
```json
{
  "success": true,
  "message": "Kiosk mode off: LensDaemon is unpinned and no longer starts on boot"
}
```

### GET /api/kiosk/config

The kiosk settings; the exit PIN itself is not returned (`exitPinSet` says
whether there is one). This example is the APPLIANCE preset with kiosk mode on:

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
    "exitPinSet": true,
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
    "restartWindowMinutes": 10
  }
}
```

- `security.blockScreenshots` blocks screenshots of the app's screen.
- LensDaemon's own screen hides the status and navigation bars whatever
  `lockStatusBar` and `lockNavigationBar` say (a swipe shows them for a moment);
  while pinned, Home and Recents are blocked either way.
- The meaning of the other settings is described above.

### PUT /api/kiosk/config

Change some settings. Every section and field is optional; what is left out
stays as it is. `enabled` turns kiosk mode on or off like `/enable` and
`/disable`, without bringing the app to the front.

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

- `security.exitPin`: 4 to 12 digits (a number is taken too); `""` or `null`
  removes the PIN.
- `screen.mode`: `PREVIEW`, `OFF`, `DIM` or `BLACK`, in any letter case.
- `screen.dimBrightness` is clamped to 0-255.
- The times listed below must not be negative.

**Response:**
```json
{
  "success": true,
  "message": "Kiosk configuration updated"
}
```

`400` `{"success": false, "error": ...}`, changing nothing, with one of:
- `exitPin must be 4-12 digits, or empty to remove it`
- `screen.mode must be PREVIEW, OFF, DIM or BLACK`
- `<section>.<field> must not be negative`, for `autoStart.delaySeconds`,
  `screen.autoOffTimeoutSec`, `security.exitGestureTimeoutMs`,
  `networkRecovery.reconnectIntervalSec`, `crashRecovery.restartDelayMs` and
  `crashRecovery.restartWindowMinutes`

### POST /api/kiosk/preset/appliance

Apply the APPLIANCE preset for 24/7 unattended use: screen off through the
phone's screen timeout, RTSP started 10 s after boot, system bars hidden, the
outputs restarted after a crash (up to 10 times in 10 minutes), failed uploads
retried when the network returns. The exit PIN and kiosk mode on/off stay as
they were (turn it on with `/api/kiosk/enable`). The preset is logged as
`PRESET_APPLIED`.

**Response:**
```json
{
  "success": true,
  "message": "Appliance preset applied (24/7 unattended operation)"
}
```

### POST /api/kiosk/preset/interactive

Apply the INTERACTIVE preset: the screen shows the camera preview and stays on,
RTSP starts 3 s after boot, Start/Stop on the phone's screen, the exit gesture
allowed, the outputs restarted after a crash (up to 5 times in 10 minutes). The
exit PIN and kiosk mode on/off stay as they were.

**Response:**
```json
{
  "success": true,
  "message": "Interactive preset applied (kiosk with preview)"
}
```

### GET /api/kiosk/events

The kiosk event log, newest last. It is kept in memory (the last 100 events)
and starts empty when the app starts.

**Response:**
```json
{
  "count": 3,
  "events": [
    {
      "timestamp": 1794643200000,
      "type": "BOOT_COMPLETED",
      "message": "Phone started (boot 12)",
      "details": { "boot": 12 }
    },
    {
      "timestamp": 1794643210000,
      "type": "AUTO_START",
      "message": "Auto-start: RTSP",
      "details": { "rtsp": true, "record": false }
    },
    {
      "timestamp": 1794646800000,
      "type": "EXIT_PIN_ENTERED",
      "message": "Wrong exit PIN entered at the phone",
      "details": { "correct": false }
    }
  ]
}
```

Types, with their `details`:
- `KIOSK_ENABLED`, `KIOSK_DISABLED`, `LOCK_TASK_STARTED`, `LOCK_TASK_STOPPED`
- `EXIT_GESTURE_DETECTED` `{"pinRequired": true}`
- `EXIT_PIN_ENTERED` `{"correct": false}`
- `BOOT_COMPLETED` `{"boot": 12}` (the phone's boot count, kept across restarts)
- `AUTO_START` `{"rtsp": true, "record": false}`
- `CRASH_RECOVERY` (an automatic restart after a crash or kill)
- `PRESET_APPLIED` `{"preset": "APPLIANCE"}` (or `INTERACTIVE`)

---

## AI Director

Script-driven camera control: a script of cues (see
[Script Cue Format Reference](#script-cue-format-reference)) switches lenses,
zooms, focuses and sets the exposure, and splits the run into takes that are
scored for quality. Scripts are parsed on the phone; nothing is sent to a
language model. The director is completely inert while disabled.

The director's states: `DISABLED`, `IDLE` (on, no script), `READY` (script
loaded), `RUNNING`, `PAUSED`, `THERMAL_HOLD`.

**Thermal hold:** while thermal protection is on (`thermalAutoDisable`), a run
that finds the CPU at `thermalThresholdDisable` (55°C by default) or hotter
stops in `THERMAL_HOLD`: its take ends and the position is kept. The hold
clears by itself (to `READY`, without resuming) once the phone is 5°C cooler;
Start and Resume read the temperature again and refuse while it is too hot.

**Takes:** each scene a run enters gets its own take (`autoTakeSeparation`).
Pause keeps the take open and Resume continues it; Stop and the end of the
script end it, and go back to the first scene. Takes score 0 to 10 from samples
taken while they run: focus lock (30%), exposure stability (20%), steadiness
(25%), cue timing (15%, left out when no script cue ran) and audio (10%). On
the phone, exposure and steadiness are placeholders (the configured
compensation, and a constant per stabilisation mode) and audio is not
measured, so takes differ mostly in focus lock and cue timing. The best take of each
scene scoring at least 5 is `suggested`. With `qualityScoring` off, takes
score 0.

### GET /api/director/status

**Response:**
```json
{
  "state": "RUNNING",
  "enabled": true,
  "inferenceMode": "PRE_PARSED",
  "hasScript": true,
  "currentScene": "Interview Setup",
  "currentCue": "[SHOT: MEDIUM]",
  "nextCue": "[FOCUS: FACE]",
  "takeNumber": 2,
  "totalCues": 7,
  "executedCues": 3,
  "thermalProtectionActive": false
}
```

`thermalProtectionActive` is true while in `THERMAL_HOLD`. `/api/status` has
the position as indexes.

### POST /api/director/enable

**Response:**
```json
{
  "success": true,
  "message": "AI Director enabled",
  "state": "IDLE"
}
```

### POST /api/director/disable

Stop a run (its take ends), unload the script and go back to the inert state;
the director stays off after a restart.

**Response:**
```json
{
  "success": true,
  "message": "AI Director disabled (inert)",
  "state": "DISABLED"
}
```

### GET /api/director/config

**Response:**
```json
{
  "inferenceMode": "PRE_PARSED",
  "autoTakeSeparation": true,
  "qualityScoring": true,
  "thermalAutoDisable": true,
  "thermalThresholdInference": 50,
  "thermalThresholdDisable": 55,
  "defaultTransitionDurationMs": 1000,
  "defaultHoldDurationMs": 2000,
  "enabled": true,
  "thermalProtectionActive": false
}
```

- `inferenceMode`: `PRE_PARSED` (cues parsed when the script loads) or `OFF`.
  `REMOTE` is not available.
- `thermalThresholdDisable` (°C): see "Thermal hold" above.
  `thermalThresholdInference` is kept for `REMOTE` and not used.
- `defaultTransitionDurationMs`: a `[TRANSITION]` without a duration, and the
  time a `[SHOT]` counts for in the estimated duration.
  `defaultHoldDurationMs`: a `[BEAT]`.

### PUT /api/director/config

Change some settings: the fields of `GET /api/director/config` (except
`thermalProtectionActive`); the others keep their values. `enabled` turns the
director on or off.

**Request:**
```json
{
  "thermalThresholdDisable": 60,
  "defaultHoldDurationMs": 3000
}
```

**Response:**
```json
{
  "success": true,
  "message": "Director configuration updated"
}
```

`400` `{"success": false, "error": "REMOTE inference is not available yet; use PRE_PARSED or OFF"}`,
or `{"success": false, "error": "Unknown inferenceMode X: use PRE_PARSED or OFF"}`.

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
  "success": true,
  "message": "Script loaded successfully",
  "scenes": 2,
  "totalCues": 7,
  "estimatedDuration": "0:08",
  "errors": []
}
```

`errors` lists the problems found, without refusing the script: the parser's
(lines in brackets that are not cues, unknown shot names, used as `MEDIUM`,
and extra cues on a line, of which only the first counts; at most 10 are
listed, then a count), scenes without cues, and shots this phone's lenses
can't frame. For example:

```json
["Line 4: not a cue: [TRANSITON: PUSH IN]", "Scene 'B' has no cues",
 "Scene at line 2: Close-up requested but telephoto/adequate zoom not available"]
```

**Errors** (the script loaded before stays loaded):
- `400` `{"error": "script field is required"}`
- `400` `{"success": false, "error": "Script contains no valid scenes or cues; Line 1: not a cue: ..."}`
- `400` `{"success": false, "error": "The director is off"}`
- `409` `{"success": false, "error": "Stop the director before loading another script"}`
  while it runs or is paused

### POST /api/director/script/clear

Unload the script; a run stops and its take ends. The recorded takes stay.

**Response:**
```json
{
  "success": true,
  "message": "Script cleared"
}
```

### POST /api/director/start

Run the loaded script from the current cue (from the first scene after Stop).

**Response:**
```json
{
  "success": true,
  "message": "Execution started"
}
```

**Errors:**
- `409` while the phone is too hot (with the temperature last read, when there
  is one):
  ```json
  {"success": false, "error": "Too hot to run the director (57°C); it can start again below 50°C", "temperature": 57}
  ```
- `409` `{"success": false, "error": "The director is already running"}`
- `400` `{"success": false, "error": ...}` with `The director is off`,
  `No script loaded` or `Cannot start in state X`

### POST /api/director/stop

Stop the run; its take ends and the position goes back to the first scene. A
thermal hold stays until the phone cools.

**Response:**
```json
{
  "success": true,
  "message": "Execution stopped",
  "state": "READY"
}
```

### POST /api/director/pause

Pause the run where it is; the take stays open. Answers 200 in any state:
`state` says where the director is.

**Response:**
```json
{
  "success": true,
  "message": "Execution paused",
  "state": "PAUSED"
}
```

### POST /api/director/resume

Continue a paused run, in the same take; also starts a run held for heat once
the phone has cooled.

**Response:**
```json
{
  "success": true,
  "message": "Execution resumed"
}
```

The same errors as Start, plus `400` `{"success": false, "error": "Not paused"}`.

### POST /api/director/cue

Run one cue now, with or without a script. `[BEAT]` and `[HOLD]` succeed
without waiting; `[TAKE]` starts the next take.

**Request:**
```json
{
  "cue": "[SHOT: CLOSE-UP]"
}
```

**Response:**
```json
{
  "success": true,
  "cue": "[SHOT: CLOSE-UP]"
}
```

`400` `{"success": false, "cue": "...", "error": "Cue not recognised or could not be run"}`
(or `The director is off`); `400` `{"error": "cue field is required"}`.

### POST /api/director/advance

Go to the next cue (the first cue of the next scene with cues, at the end of a
scene). While a script runs, that cue runs at once, and going past the last cue
ends the run; otherwise only the position moves.

**Response:**
```json
{
  "success": true,
  "cue": { "type": "FOCUS", "rawText": "[FOCUS: FACE]", "lineNumber": 5 }
}
```

`lineNumber` counts from 0. At the end of the script:
`{"success": false, "message": "No more cues", "error": "No more cues"}` (200).

### POST /api/director/scene

Go to the start of a scene, by index from 0. While a script runs, a new take
starts and the scene's first cue runs at once.

**Request:**
```json
{
  "sceneIndex": 1
}
```

(`index` is taken too.)

**Response:**
```json
{
  "success": true,
  "sceneIndex": 1
}
```

`400` `{"success": false, "sceneIndex": 5, "error": "No scene 5 in the loaded script"}`
(or `No script loaded`, `The director is off`), and
`{"success": false, "error": "sceneIndex is required and must be >= 0"}`.

### GET /api/director/takes

All recorded takes.

**Response:**
```json
{
  "count": 1,
  "takes": [
    {
      "takeNumber": 1,
      "sceneId": "3f6b2a7e-1c4d-4e8f-9a0b-5c6d7e8f9a0b",
      "sceneLabel": "Interview Setup",
      "startTimeMs": 1794646800000,
      "endTimeMs": 1794646830000,
      "durationMs": 30000,
      "durationFormatted": "00:30.000",
      "filePath": "",
      "qualityScore": 7.8,
      "qualityFactors": {
        "focusLockPercent": 92,
        "exposureStability": 0.9,
        "motionStability": 0.8,
        "audioLevelOk": true,
        "cueTimingAccuracy": 0.95
      },
      "cuesExecuted": 4,
      "cuesFailed": 0,
      "manualMark": "GOOD",
      "suggested": true,
      "notes": ""
    }
  ]
}
```

`qualityScore` is 0-10; `focusLockPercent` is 0-100, the other factors 0-1.
`filePath` is the recording linked with `/takes/link` (`""` when none).

### POST /api/director/takes/mark

Mark a take.

**Request:**
```json
{
  "takeNumber": 1,
  "quality": "CIRCLE",
  "notes": "Best delivery"
}
```

`quality` (or `mark`): `UNMARKED`, `GOOD`, `BAD`, `CIRCLE`, `HOLD`; `notes` is
optional.

**Response:**
```json
{
  "takeNumber": 1,
  "quality": "CIRCLE",
  "success": true
}
```

`404` `{"takeNumber": 9, "quality": "CIRCLE", "success": false, "error": "Take 9 not found"}`;
`400` `{"error": "takeNumber is required"}` or
`{"error": "Invalid quality. Use: UNMARKED, GOOD, BAD, CIRCLE, HOLD"}`.

### GET /api/director/takes/best

The best take of each scene, keyed by scene id, for scenes whose best take
scores at least 5.

**Response:**
```json
{
  "count": 1,
  "bestTakes": {
    "3f6b2a7e-1c4d-4e8f-9a0b-5c6d7e8f9a0b": {
      "takeNumber": 3,
      "sceneLabel": "Interview Setup",
      "qualityScore": 9.1,
      "durationFormatted": "00:42.500"
    }
  }
}
```

### GET /api/director/takes/compare

The takes of the current scene, ranked by score.

**Response:**
```json
{
  "sceneId": "3f6b2a7e-1c4d-4e8f-9a0b-5c6d7e8f9a0b",
  "sceneLabel": "Interview Setup",
  "recommendation": "Take #2 recommended (excellent quality)",
  "bestTake": { "takeNumber": 2, "qualityScore": 9.1 },
  "rankings": [
    { "rank": 1, "takeNumber": 2, "qualityScore": 9.1 },
    { "rank": 2, "takeNumber": 1, "qualityScore": 7.8 }
  ]
}
```

`400` `{"error": "No active session"}` without a script.

### POST /api/director/takes/link

Link a take to a recording file, for post-production.

**Request:**
```json
{
  "takeNumber": 1,
  "filePath": "/storage/emulated/0/Android/data/com.lensdaemon/files/recordings/LensDaemon_Pixel_7_Pro_20261114_090000_seg000.mp4"
}
```

**Response:**
```json
{
  "success": true,
  "takeNumber": 1,
  "filePath": "/storage/emulated/0/Android/data/com.lensdaemon/files/recordings/LensDaemon_Pixel_7_Pro_20261114_090000_seg000.mp4"
}
```

`404` `{"success": false, "error": "Take 9 not found"}`; `400`
`{"error": "takeNumber and filePath are required"}`.

### GET /api/director/takes/markers

The start and end of each recorded take, for aligning takes with recordings.

**Response:**
```json
{
  "success": true,
  "count": 2,
  "markers": [
    {
      "type": "TAKE_START",
      "takeNumber": 1,
      "sceneId": "3f6b2a7e-1c4d-4e8f-9a0b-5c6d7e8f9a0b",
      "sceneLabel": "Interview Setup",
      "timestampMs": 1794646800000
    },
    {
      "type": "TAKE_END",
      "takeNumber": 1,
      "sceneId": "3f6b2a7e-1c4d-4e8f-9a0b-5c6d7e8f9a0b",
      "timestampMs": 1794646830000,
      "qualityScore": 7.8,
      "durationMs": 30000
    }
  ]
}
```

Marker types: `TAKE_START` and `TAKE_END` (none for a take still running).

### GET /api/director/session

The loaded script, the position and the session's take stats.

**Response:**
```json
{
  "hasSession": true,
  "script": { "scenes": 2, "totalCues": 7, "estimatedDuration": "0:08" },
  "currentSceneIndex": 0,
  "currentCueIndex": 3,
  "currentScene": "Interview Setup",
  "state": "RUNNING",
  "stats": {
    "totalTakes": 2,
    "uniqueScenes": 1,
    "avgQualityScore": 8.45,
    "bestTakeCount": 1,
    "circledTakes": 1,
    "totalDuration": "1:12",
    "cueSuccessRate": 1.0
  }
}
```

Without a script only `hasSession: false` and `stats` are present.
`bestTakeCount` counts the scenes with a best take (score 5 or more);
`cueSuccessRate` is a fraction (0-1).

### GET /api/director/events

Server-Sent Events: the director's events as they happen. Connect with an
`EventSource`; responses are `text/event-stream`, not compressed, not cached,
and carry no CORS header.

Each event is an unnamed `data:` frame holding a JSON object whose `type` says
what happened (listen with `onmessage`, not `addEventListener('cue_executed')`).
The first frame is the director's current state. After 15 seconds without an
event a `: keepalive` comment is sent, which `EventSource` ignores.

```
data: {"type":"state","enabled":true,"state":"READY","currentScene":"Interview Setup","currentCue":"[SHOT: WIDE]","currentTake":0}

data: {"type":"state_changed","state":"RUNNING"}

data: {"type":"take_started","takeNumber":1,"sceneId":"3f6b2a7e-1c4d-4e8f-9a0b-5c6d7e8f9a0b"}

data: {"type":"cue_executed","cueType":"SHOT","cueText":"[SHOT: WIDE]","success":true}

: keepalive

data: {"type":"take_ended","takeNumber":1,"qualityScore":7.8,"durationMs":30000}

```

| `type` | Fields |
|--------|--------|
| `state` | `enabled`, `state`, `currentScene`, `currentCue`, `currentTake` (always the first frame) |
| `state_changed` | `state` |
| `script_loaded` | `scenes`, `totalCues` |
| `cue_executed` | `cueType`, `cueText`, `success` |
| `take_started` | `takeNumber`, `sceneId` |
| `take_ended` | `takeNumber`, `qualityScore`, `durationMs` |
| `error` | `message` |
| `thermal_warning` | `temperature`, `action` (`Stopped until the phone cools below 50°C`) |

A client that reads too slowly misses newer events once 256 are waiting.

### GET /api/director/scripts

The script files saved on the phone (`.txt` files).

**Response:**
```json
{
  "success": true,
  "count": 1,
  "scripts": [
    {
      "name": "interview",
      "fileName": "interview.txt",
      "sizeBytes": 1024,
      "lastModified": 1794643200000
    }
  ]
}
```

### POST /api/director/scripts/save

Save a script as a file (without loading it). `.txt` is added to the name when
missing.

**Request:**
```json
{
  "fileName": "interview",
  "script": "[SCENE: Interview Setup]\n[SHOT: WIDE]"
}
```

**Response:**
```json
{
  "success": true,
  "fileName": "interview.txt",
  "message": "Script saved"
}
```

`400` `{"error": "fileName and script are required"}` or
`{"error": "Invalid file name"}` (names may hold letters, digits, spaces, `.`,
`_` and `-`); `500` `{"error": "Failed to save script"}`.

### GET /api/director/scripts/{fileName}

Open a saved script (`fileName` with its `.txt`) and load it.

**Response:**
```json
{
  "scenes": 2,
  "totalCues": 7,
  "estimatedDuration": "0:08",
  "errors": [],
  "success": true,
  "fileName": "interview.txt",
  "content": "[SCENE: Interview Setup]\n...",
  "loaded": true
}
```

Opening succeeds even when the director refuses to load the script (it is off,
running or paused, or the script has no cues): then `"loaded": false`, the
`content` is there, `loadError` gives the reason, and the script summary
fields are missing. `404` `{"error": "Script file not found"}`; `400`
`{"error": "Invalid file name"}`.

### DELETE /api/director/scripts/{fileName}

**Response:**
```json
{
  "success": true,
  "message": "Script deleted"
}
```

`404` `{"error": "Script file not found"}`.

### GET /api/director/scripts/export

The loaded script's text.

**Response:**
```json
{
  "success": true,
  "script": "[SCENE: Interview Setup]\n[SHOT: WIDE]\n...",
  "scenes": 2,
  "totalCues": 7
}
```

`404` `{"error": "No script currently loaded"}`.

### POST /api/director/scripts/import

Load a script, and save it as a file when asked.

**Request:**
```json
{
  "script": "[SCENE: Demo]\n[SHOT: WIDE]",
  "fileName": "demo.txt",
  "save": true
}
```

**Response:** as `POST /api/director/script`, with `saved`:
```json
{
  "scenes": 1,
  "totalCues": 1,
  "estimatedDuration": "0:01",
  "errors": [],
  "success": true,
  "message": "Script imported",
  "saved": true
}
```

The same `400` and `409` refusals as `POST /api/director/script`; a refused
script is not saved.

---

## MJPEG Preview

A live preview of the camera as Motion JPEG (`multipart/x-mixed-replace`),
at most 15 frames a second:

```
GET /mjpeg
GET /stream.mjpeg
```

```html
<img src="http://{device-ip}:8080/mjpeg" />
```

- Frames flow while the camera is capturing.
- Each connection counts against the API's rate limit (`429`). Once a token
  is set it needs one, like the API (`?token=` works in an `<img>`); `401`
  otherwise.
- At most 8 viewers at once. When 8 are watching, the one that has gone
  longest without a frame written to it makes room for the newcomer, if that
  is more than 3 seconds (it has left, is stuck, or watches a camera that sends
  nothing: a viewer that left is only noticed when a write to it fails).
  Otherwise the newcomer gets `503` `{"error": "Too many preview viewers"}`
  (`{"error": "Preview stopped"}` while the web server shuts down).
- A slow viewer skips frames. A viewer whose connection has not taken a frame
  for 5 seconds (a phone locked with the preview open, a laptop that left the
  Wi-Fi) is disconnected, and the connection closes when its stream ends.

---

## Script Cue Format Reference

One cue per line, in brackets. Lines without a cue are narration; a line that
starts with `[` but is not a cue is reported in the script's `errors`. Names
are not case-sensitive.

```
[SCENE: Label]                         Scene marker (a scene with no cues is skipped)
[SHOT: WIDE|MEDIUM|CLOSE-UP|...]       Shot type: lens and zoom
[CUT TO: CLOSE-UP]                     Hard cut to a shot type
[TRANSITION: PUSH IN|PULL BACK] - 2s   Animated zoom; the duration is optional
[FOCUS: FACE|HANDS|BACKGROUND|...]     Focus target
[EXPOSURE: AUTO|BRIGHT|DARK|...]       Exposure preset
[BEAT]                                 Wait defaultHoldDurationMs (2 s)
[HOLD: 3]                              Wait 3 seconds
[TAKE: 2]                              Start the next take (the number is not used)
```

- **Shots** (`[SHOT]`, `[CUT TO]`): `ESTABLISHING`, `WIDE`, `FULL`, `MEDIUM`,
  `MEDIUM CLOSE` (`MCU`), `CLOSE-UP`, `EXTREME CLOSE` (`ECU`), `OVER SHOULDER`
  (`OTS`). An unknown name is used as `MEDIUM` and reported. Wider shots take
  the wide lens, closer ones zoom in on the main lens or take the telephoto,
  within what the phone has.
- **Transitions**: `PUSH IN` multiplies the zoom by 1.5 and `PULL BACK` by
  0.67 (not below 1x), animated over the duration (default 1 s); `RACK FOCUS`
  switches the focus between face and background; `CUT` and `HOLD` keep the
  framing.
- **Focus**: `AUTO` and `FACE` are continuous autofocus (there is no face
  priority yet); `HANDS` focuses on the lower middle of the frame and `OBJECT`
  on its centre; `BACKGROUND` focuses at infinity (lenses with manual focus
  only); `MANUAL` locks the focus where it is.
- **Exposure**: `AUTO` 0, `BRIGHT` +1, `DARK` -1, `BACKLIT` +2 and
  `SILHOUETTE` -2 EV, in the lens's own steps and within its range. A preset
  other than `AUTO` stays in force across shots and lens changes until another
  `[EXPOSURE]` cue; while it does, an exposure set from the dashboard is
  replaced at the next director command. Scripts without `[EXPOSURE]` cues
  never touch the exposure.
- Plain lines that say "wide shot", "establishing shot", "master shot", "wide
  angle", "close-up", "tight shot", "detail shot", "medium shot", "waist shot"
  or "mid shot" are read as shot cues too.
- Only camera cues that fail count as failed cues; `[BEAT]`, `[HOLD]` and
  `[TAKE]` never fail.
