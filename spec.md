# LensDaemon

**Transmute any Android phone into a dedicated video appliance.**

---

## Vision

Modern smartphones contain imaging hardware that surpasses purpose-built webcams and security cameras by every metric: larger sensors, multiple lenses, optical stabilization, computational photography, and hardware encoding. Yet they remain underutilized because the full Android OS creates thermal throttling, battery degradation, and reliability issues during sustained video operation.

LensDaemon solves this by transforming any Android phone into a single-purpose video appliance — streaming, recording, and serving video with the reliability of dedicated hardware and the quality of flagship optics.

---

## Core Principles

1. **Dedicated operation** — The phone does one thing. No notifications, no background sync, no distractions.
2. **Thermal sustainability** — Continuous operation without throttling or shutdown.
3. **Network sovereignty** — User controls where video goes. No cloud dependency.
4. **Zero-touch reliability** — Survives power cycles, network drops, and errors without intervention.

---

## MVP Feature Set

### 1. Streaming Engine

Dual-protocol output supporting professional and universal workflows.

| Protocol | Port | Use Case |
|----------|------|----------|
| RTSP | 8554 | Universal compatibility (VLC, OBS, Blue Iris, NVRs) |
| SRT | 9000 | Low-latency, lossy-network-tolerant (remote production, cellular backhaul) |

**Encoding parameters:**
- Codec: H.264 (baseline), H.265 (optional, device-dependent)
- Resolution: 1080p default, 4K where thermal envelope allows
- Bitrate: Adaptive, user-configurable ceiling (default: 6 Mbps for 1080p, 20 Mbps for 4K)
- Framerate: 30fps default, 60fps optional
- Keyframe interval: 2 seconds (configurable)

**Stream behavior:**
- Starts automatically on boot (if configured)
- Reconnects on network drop
- Graceful bitrate reduction under thermal pressure

---

### 2. Storage Targets

Two storage backends for MVP, covering local network and cloud-compatible workflows.

#### 2.1 SMB/NFS Network Share

Primary target for self-hosted deployments.

**Configuration:**
```
storage.network.type = "smb" | "nfs"
storage.network.host = "192.168.1.100"
storage.network.share = "cameras/front-door"
storage.network.credentials = (encrypted local storage)
```

**Behavior:**
- Writes segmented MP4 files (configurable duration: 1/5/15/60 min)
- Filename pattern: `LensDaemon_{device-id}_{timestamp}.mp4`
- On network failure: buffer to local storage, sync when reconnected
- Retention policy: configurable max age or max size

#### 2.2 S3-Compatible Object Storage

For cloud backup, remote access, or integration with existing infrastructure.

**Supported backends:**
- AWS S3
- Backblaze B2
- MinIO (self-hosted)
- Cloudflare R2

**Configuration:**
```
storage.s3.endpoint = "s3.amazonaws.com" | "s3.us-west-000.backblazeb2.com" | custom
storage.s3.bucket = "lensdaemon-footage"
storage.s3.prefix = "front-door/"
storage.s3.access_key = (encrypted)
storage.s3.secret_key = (encrypted)
```

**Behavior:**
- Upload completed segments immediately or on schedule
- Optional: delete local copy after confirmed upload
- Multipart upload for large segments

---

### 3. Web Control Interface

Self-hosted control panel served directly from the device.

**Access:** `http://{device-ip}:8080`

**Authentication:** 
- Optional PIN or password
- Local network only by default (configurable)

#### 3.1 Dashboard View

```
┌─────────────────────────────────────────────────────┐
│  LensDaemon v0.1.0          Device: Pixel 7 Pro    │
├─────────────────────────────────────────────────────┤
│                                                     │
│  ┌─────────────────────────────────────────────┐   │
│  │                                             │   │
│  │            Live Preview                     │   │
│  │            (MJPEG low-res)                  │   │
│  │                                             │   │
│  └─────────────────────────────────────────────┘   │
│                                                     │
│  Status: STREAMING                                  │
│  Uptime: 4d 12h 33m                                │
│                                                     │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐           │
│  │   Wide   │ │   Main   │ │   Tele   │           │
│  │   0.5x   │ │    1x    │ │    3x    │           │
│  └──────────┘ └──────────┘ └──────────┘           │
│       ○            ●            ○                  │
│                                                     │
│  Stream URLs:                                       │
│  RTSP: rtsp://192.168.1.50:8554/live              │
│  SRT:  srt://192.168.1.50:9000                    │
│                                                     │
└─────────────────────────────────────────────────────┘
```

#### 3.2 Control Actions

| Action | Endpoint | Method |
|--------|----------|--------|
| Start stream | `/api/stream/start` | POST |
| Stop stream | `/api/stream/stop` | POST |
| Switch lens | `/api/lens/{wide\|main\|tele}` | POST |
| Get status | `/api/status` | GET |
| Get config | `/api/config` | GET |
| Set config | `/api/config` | PUT |
| Trigger snapshot | `/api/snapshot` | POST |

#### 3.3 Settings Panel

- Resolution / framerate / bitrate
- Storage target configuration
- Stream auto-start on boot
- Screen behavior (off / dim / preview)
- Network settings (static IP, WiFi priority)
- Security (PIN, allowed IPs)

---

### 4. Lens Control

Expose full camera array to network control.

**Capabilities (device-dependent):**
| Control | Implementation |
|---------|----------------|
| Lens selection | Wide / Main / Telephoto via Camera2 API |
| Digital zoom | Smooth interpolation between optical steps |
| Focus mode | Auto / Manual (tap-to-focus via preview coordinates) |
| Exposure | Auto / Manual EV compensation |
| White balance | Auto / Preset / Manual Kelvin |

**API Example:**
```json
POST /api/lens/main
{
  "zoom": 1.5,
  "focus": "auto",
  "exposure_compensation": -0.5,
  "white_balance": "daylight"
}
```

**Future (v2):** PTZ emulation for NVR compatibility via ONVIF Profile S.

---

### 5. Thermal Management

Sustained operation requires active thermal awareness.

#### 5.1 Monitoring

| Metric | Source | Dashboard Display |
|--------|--------|-------------------|
| CPU temperature | `/sys/class/thermal/` | Gauge (°C) |
| Battery temperature | Android BatteryManager | Gauge (°C) |
| Thermal throttle state | PowerManager | Status indicator |
| Encoder load | MediaCodec stats | Percentage |

#### 5.2 Thermal Dashboard

```
┌─────────────────────────────────────────────────────┐
│  THERMAL STATUS                                     │
├─────────────────────────────────────────────────────┤
│                                                     │
│  CPU Temperature         Battery Temperature        │
│  ┌────────────────┐      ┌────────────────┐        │
│  │      42°C      │      │      38°C      │        │
│  │   [||||||||  ] │      │   [|||||||   ] │        │
│  │   Safe Zone    │      │   Safe Zone    │        │
│  └────────────────┘      └────────────────┘        │
│                                                     │
│  Throttle State: NONE                               │
│  Encoding: 1080p @ 6.2 Mbps (target: 6.0)          │
│                                                     │
│  ┌─────────────────────────────────────────────┐   │
│  │ Temperature History (24h)                   │   │
│  │    50°C ┤                                   │   │
│  │    40°C ┤ ───────────────────────────────   │   │
│  │    30°C ┤                                   │   │
│  │         └───────────────────────────────    │   │
│  │          0h        12h        24h           │   │
│  └─────────────────────────────────────────────┘   │
│                                                     │
└─────────────────────────────────────────────────────┘
```

#### 5.3 Thermal Governors

Automatic response to thermal pressure:

| Threshold | Action |
|-----------|--------|
| CPU > 45°C | Reduce bitrate by 20% |
| CPU > 50°C | Reduce resolution to 1080p (if 4K) |
| CPU > 55°C | Reduce framerate to 24fps |
| CPU > 60°C | Pause streaming, show warning, resume when cooled |
| Battery > 42°C | Disable charging (USB power bypass) |
| Battery > 45°C | Alert user, recommend physical cooling |

#### 5.4 Battery Bypass Mode

When connected to USB power:
- Charge until 50% (configurable threshold)
- Hold at threshold, run directly from USB power
- Prevents long-term battery swelling
- Resume charging if drops below threshold - 10%

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      LensDaemon App                         │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │   Camera    │  │   Encoder   │  │   Output Manager    │ │
│  │   Service   │  │   Service   │  │                     │ │
│  │             │  │             │  │  ┌───────────────┐  │ │
│  │  Camera2    │→ │  MediaCodec │→ │  │ RTSP Server   │  │ │
│  │  API        │  │  H.264/265  │  │  ├───────────────┤  │ │
│  │             │  │             │  │  │ SRT Publisher │  │ │
│  │  Lens Ctrl  │  │  Adaptive   │  │  ├───────────────┤  │ │
│  │  Focus/Exp  │  │  Bitrate    │  │  │ File Writer   │  │ │
│  └─────────────┘  └─────────────┘  │  └───────────────┘  │ │
│                                     └─────────────────────┘ │
│                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │  Thermal    │  │   Storage   │  │   Web Server        │ │
│  │  Monitor    │  │   Manager   │  │                     │ │
│  │             │  │             │  │  HTTP :8080         │ │
│  │  CPU/Batt   │  │  SMB Client │  │  Dashboard + API    │ │
│  │  Governor   │  │  S3 Client  │  │  Live Preview       │ │
│  │  Charging   │  │  Local Ring │  │  REST Control       │ │
│  └─────────────┘  └─────────────┘  └─────────────────────┘ │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                  Kiosk Manager                       │   │
│  │   Device Owner | Screen Control | Boot Receiver      │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## Kiosk Mode Implementation

Using Android Device Owner APIs for app-level lockdown without root.

**Setup (one-time):**
```bash
adb shell dpm set-device-owner com.lensdaemon/.AdminReceiver
```

**Capabilities:**
- Lock task mode (no home/recent escape)
- Disable status bar pull-down
- Control screen timeout and brightness
- Survive reboots (persistent device owner)
- Block app installations
- Disable USB debugging after setup (optional)

**User override:** Physical button combo (Vol Up + Vol Down + Power, 5 sec) to exit kiosk mode for configuration.

---

## Hardware Requirements

**Minimum:**
- Android 10 (API 29)
- Camera2 API Level 3 (FULL or LEVEL_3)
- H.264 hardware encoder
- 3GB RAM
- WiFi

**Recommended:**
- Android 12+
- Multi-lens camera array
- H.265 hardware encoder
- 6GB+ RAM
- USB-C with power delivery

**Optimal devices (known good):**
- Google Pixel 5/6/7/8 series
- Samsung Galaxy S20/S21/S22/S23 series
- OnePlus 8/9/10/11 series

---

## File Structure

```
com.lensdaemon/
├── app/
│   ├── src/main/
│   │   ├── java/com/lensdaemon/
│   │   │   ├── LensDaemonApp.kt
│   │   │   ├── MainActivity.kt
│   │   │   ├── AdminReceiver.kt
│   │   │   ├── camera/
│   │   │   │   ├── CameraService.kt
│   │   │   │   ├── LensController.kt
│   │   │   │   └── FrameProcessor.kt
│   │   │   ├── encoder/
│   │   │   │   ├── EncoderService.kt
│   │   │   │   └── AdaptiveBitrate.kt
│   │   │   ├── output/
│   │   │   │   ├── RtspServer.kt
│   │   │   │   ├── SrtPublisher.kt
│   │   │   │   └── FileWriter.kt
│   │   │   ├── storage/
│   │   │   │   ├── StorageManager.kt
│   │   │   │   ├── SmbClient.kt
│   │   │   │   └── S3Client.kt
│   │   │   ├── thermal/
│   │   │   │   ├── ThermalMonitor.kt
│   │   │   │   ├── ThermalGovernor.kt
│   │   │   │   └── BatteryBypass.kt
│   │   │   ├── web/
│   │   │   │   ├── WebServer.kt
│   │   │   │   ├── ApiRoutes.kt
│   │   │   │   └── assets/ (dashboard HTML/JS/CSS)
│   │   │   └── kiosk/
│   │   │       ├── KioskManager.kt
│   │   │       └── BootReceiver.kt
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── docs/
│   ├── SETUP.md
│   ├── API.md
│   └── DEVICES.md
├── README.md
├── LICENSE
└── build.gradle.kts
```

---

## Development Phases

### Phase 1: Core Streaming (MVP)
- [ ] Camera2 capture pipeline
- [ ] H.264 hardware encoding
- [ ] RTSP server (basic)
- [ ] Minimal web UI (start/stop, preview)
- [ ] Lens switching

### Phase 2: Storage + Reliability
- [ ] Local file recording
- [ ] SMB network share upload
- [ ] S3-compatible upload
- [ ] Thermal monitoring + dashboard
- [ ] Adaptive bitrate

### Phase 3: Appliance Mode
- [ ] Device Owner kiosk mode
- [ ] Boot autostart
- [ ] Battery bypass charging
- [ ] Full thermal governors
- [ ] SRT protocol support

### Phase 4: Polish
- [ ] Setup wizard
- [ ] Device compatibility database
- [ ] OTA config updates
- [ ] Multi-device management (optional desktop app)

---

## API Reference

### Status
```
GET /api/status

Response:
{
  "device": "Pixel 7 Pro",
  "version": "0.1.0",
  "uptime_seconds": 390180,
  "streaming": true,
  "recording": true,
  "lens": "main",
  "resolution": "1920x1080",
  "bitrate_current": 6200000,
  "bitrate_target": 6000000,
  "framerate": 30,
  "thermal": {
    "cpu_temp_c": 42,
    "battery_temp_c": 38,
    "throttle_state": "none"
  },
  "storage": {
    "local_free_mb": 12400,
    "network_connected": true,
    "last_upload": "2025-02-04T10:30:00Z"
  },
  "streams": {
    "rtsp": "rtsp://192.168.1.50:8554/live",
    "srt": "srt://192.168.1.50:9000"
  }
}
```

### Stream Control
```
POST /api/stream/start
POST /api/stream/stop

Response:
{
  "success": true,
  "streaming": true
}
```

### Lens Control
```
POST /api/lens/{wide|main|tele}

Body (optional):
{
  "zoom": 1.5,
  "focus": "auto",
  "exposure_compensation": 0,
  "white_balance": "auto"
}

Response:
{
  "success": true,
  "lens": "main",
  "zoom": 1.5,
  "capabilities": {
    "zoom_range": [1.0, 8.0],
    "has_ois": true
  }
}
```

### Snapshot
```
POST /api/snapshot

Response:
{
  "success": true,
  "path": "/snapshots/2025-02-04_103045.jpg",
  "url": "/api/snapshots/2025-02-04_103045.jpg"
}
```

### Configuration
```
GET /api/config
PUT /api/config

Body:
{
  "stream": {
    "resolution": "1920x1080",
    "framerate": 30,
    "bitrate": 6000000,
    "codec": "h264",
    "autostart": true
  },
  "storage": {
    "local_enabled": true,
    "network_type": "smb",
    "network_host": "192.168.1.100",
    "network_share": "cameras",
    "segment_minutes": 5
  },
  "thermal": {
    "battery_charge_limit": 50,
    "cpu_throttle_threshold": 45
  },
  "display": {
    "screen_mode": "off",
    "osd_enabled": true
  }
}
```

---

## License

MIT License — Use it, fork it, sell it, build on it.

---

## Project Links

- Repository: `github.com/[tbd]/lensdaemon`
- Issues: `github.com/[tbd]/lensdaemon/issues`
- Discussions: `github.com/[tbd]/lensdaemon/discussions`

---

*LensDaemon: Your phone forgot it was a phone.*
