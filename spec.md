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

## AI Director Mode

An optional LLM-powered camera direction layer that interprets script cues and controls the camera accordingly. Designed for content creators who need intelligent shot automation without a dedicated camera operator.

### Core Philosophy

1. **Script-first workflow** — The scene description drives the camera, not manual intervention
2. **Completely inert when off** — Zero thermal impact, no background processing, no API calls
3. **Fail-safe defaults** — If AI processing fails, camera maintains last known good state
4. **Local-first option** — Can run with on-device inference or external LLM endpoint

### Input Format

The AI Director accepts scene/script input in a flexible markup format:

```
[SCENE: Kitchen - Morning]
Wide establishing shot. Natural light from window.

[SHOT: WIDE]
Sarah enters from the left, walks to counter.

[TRANSITION: PUSH IN - 3s]
Move to medium shot as she picks up the coffee.

[SHOT: CLOSE-UP]
Focus on hands. Shallow depth of field.
Expose for the steam rising from the cup.

[SHOT: OVER-SHOULDER]
Switch to telephoto. Sarah's perspective looking at phone.

[BEAT]
Hold for 2 seconds. Auto-adjust exposure.

[CUT TO: WIDE]
Return to establishing. End scene.
```

### Supported Cue Types

| Cue Type | Syntax | Camera Action |
|----------|--------|---------------|
| Shot Type | `[SHOT: WIDE\|MEDIUM\|CLOSE-UP\|ECU]` | Switch lens, set zoom level |
| Transition | `[TRANSITION: PUSH IN\|PULL BACK\|PAN\|TRACK] - {duration}` | Animated zoom/focus change |
| Focus | `[FOCUS: FACE\|HANDS\|OBJECT\|BACKGROUND]` | Tap-to-focus on detected region |
| Exposure | `[EXPOSURE: AUTO\|BRIGHT\|DARK\|BACKLIT]` | Adjust EV compensation |
| Depth | `[DOF: SHALLOW\|DEEP\|AUTO]` | Aperture hint (device-dependent) |
| Beat | `[BEAT]` or `[HOLD: {seconds}]` | Mark timing, maintain current state |
| Take | `[TAKE: {number}]` or auto-detected | Segment marker for post-production |
| Scene | `[SCENE: {description}]` | Metadata for organization |

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    AI Director Layer                         │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐  │
│  │ Script Parser │ → │ LLM Inference│ → │Camera Commands│  │
│  │              │    │              │    │              │  │
│  │ Cue Detection│    │ Local/Remote │    │ Lens Switch  │  │
│  │ Timing Marks │    │ Configurable │    │ Zoom Animate │  │
│  │ Scene Context│    │ Prompt Chain │    │ Focus Region │  │
│  └──────────────┘    └──────────────┘    │ Exposure Set │  │
│                                          └──────────────┘  │
│                                                 ↓           │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐  │
│  │  Take Marker │ ← │Quality Scorer│ ← │ Camera Output │  │
│  │              │    │              │    │              │  │
│  │ Auto-segment │    │ Focus Lock % │    │ From existing│  │
│  │ Best Take ID │    │ Stability    │    │ camera layer │  │
│  │ Metadata Tag │    │ Exposure OK  │    │              │  │
│  └──────────────┘    └──────────────┘    └──────────────┘  │
│                                                              │
└─────────────────────────────────────────────────────────────┘
                              ↓
              Outputs to: RTSP / File / Storage
              (existing LensDaemon pipeline)
```

### Shot Mapping

The AI Director translates abstract shot descriptions to concrete camera settings:

| Shot Description | Lens | Zoom | Focus Mode | Notes |
|------------------|------|------|------------|-------|
| ESTABLISHING / WIDE | Wide (0.5x) | 1.0 | Auto | Maximum coverage |
| FULL SHOT | Wide (0.5x) | 1.2 | Auto | Subject head-to-toe |
| MEDIUM / WAIST | Main (1x) | 1.0 | Auto | Standard conversation |
| MEDIUM CLOSE-UP | Main (1x) | 1.5 | Face detect | Chest-up framing |
| CLOSE-UP | Main (1x) | 2.0 | Face detect | Head and shoulders |
| EXTREME CLOSE-UP | Telephoto (3x) | 1.0 | Manual tap | Detail shots |
| OVER-SHOULDER | Telephoto (3x) | 1.2 | Auto | Perspective shots |

### Take Management

Automatic take separation and quality scoring:

```json
{
  "takes": [
    {
      "take_number": 1,
      "scene": "Kitchen - Morning",
      "start_time": "00:00:00.000",
      "end_time": "00:01:23.456",
      "quality_score": 0.87,
      "quality_factors": {
        "focus_lock_percent": 94,
        "exposure_stability": 0.91,
        "motion_stability": 0.82,
        "audio_level_ok": true
      },
      "cues_executed": 12,
      "cues_failed": 1,
      "suggested": true,
      "notes": "Best focus lock of all takes"
    },
    {
      "take_number": 2,
      "scene": "Kitchen - Morning",
      "start_time": "00:01:30.000",
      "end_time": "00:02:45.123",
      "quality_score": 0.72,
      "quality_factors": {
        "focus_lock_percent": 78,
        "exposure_stability": 0.85,
        "motion_stability": 0.65,
        "audio_level_ok": true
      },
      "cues_executed": 12,
      "cues_failed": 0,
      "suggested": false,
      "notes": "Motion blur on push-in"
    }
  ],
  "best_take": 1,
  "reason": "Highest overall quality score with best focus performance"
}
```

### Inference Options

| Mode | Description | Thermal Impact | Latency |
|------|-------------|----------------|---------|
| **Off** | AI Director completely disabled | Zero | N/A |
| **Pre-parsed** | Cues parsed once at script load, no runtime inference | Minimal | <10ms |
| **Local (future)** | On-device small model (TFLite/ONNX) | Moderate | 50-200ms |
| **Remote** | External LLM API (OpenAI, Anthropic, local Ollama) | Minimal on-device | 200-2000ms |

### Thermal Protection

The AI Director integrates with the existing thermal governor:

```kotlin
interface DirectorThermalPolicy {
    // When CPU > 50°C: Disable real-time inference, use pre-parsed only
    // When CPU > 55°C: Disable AI Director entirely, manual control only
    // When cooling: Re-enable after 60s below threshold

    fun onThermalWarning() {
        disableRealtimeInference()
        notifyUser("AI Director using cached cues only")
    }

    fun onThermalCritical() {
        disableDirector()
        notifyUser("AI Director disabled - thermal protection")
    }
}
```

### API Endpoints

```
POST /api/director/script
Body: { "script": "...", "parse_only": false }
Response: { "scenes": [...], "total_cues": 42, "estimated_duration": "3:45" }

POST /api/director/start
Body: { "from_scene": "Kitchen - Morning" }
Response: { "active": true, "current_scene": "...", "next_cue": "..." }

POST /api/director/stop
Response: { "active": false, "takes_recorded": 3 }

GET /api/director/status
Response: { "active": true, "current_cue": "CLOSE-UP", "next_cue": "BEAT", "take": 2 }

POST /api/director/cue
Body: { "cue": "[SHOT: WIDE]" }
Response: { "executed": true, "lens": "wide", "zoom": 1.0 }

GET /api/director/takes
Response: { "takes": [...], "best_take": 1 }

POST /api/director/mark-take
Body: { "quality": "good" | "bad" | "circle" }
Response: { "take": 3, "marked": "circle" }
```

### Configuration

```json
{
  "director": {
    "enabled": false,
    "inference_mode": "pre-parsed",
    "llm_endpoint": null,
    "llm_api_key": null,
    "auto_take_separation": true,
    "quality_scoring": true,
    "thermal_auto_disable": true,
    "thermal_threshold_inference": 50,
    "thermal_threshold_disable": 55,
    "default_transition_duration_ms": 1000,
    "shot_presets": {
      "wide": { "lens": "wide", "zoom": 1.0 },
      "medium": { "lens": "main", "zoom": 1.0 },
      "closeup": { "lens": "main", "zoom": 2.0 },
      "detail": { "lens": "telephoto", "zoom": 1.0 }
    }
  }
}
```

### Implementation Status

| Component | Status | Notes |
|-----------|--------|-------|
| Script parser | Planned | Regex + state machine for cue detection |
| Shot mapping | Planned | Direct translation to camera API |
| Transition animations | Planned | Leverage existing ZoomController |
| Take segmentation | Planned | Extend FileWriter with markers |
| Quality scoring | Planned | Analyze focus/exposure metadata |
| Local inference | Future | Requires small on-device model |
| Remote inference | Planned | HTTP client to LLM endpoints |

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

### Phase 5: AI Director
- [ ] Script parser with cue detection
- [ ] Shot-to-camera mapping engine
- [ ] Transition animations (zoom, focus)
- [ ] Automatic take segmentation
- [ ] Quality scoring (focus, exposure, stability)
- [ ] Best take suggestion algorithm
- [ ] Remote LLM integration (OpenAI, Anthropic, Ollama)
- [ ] Thermal-safe inert mode when disabled
- [ ] Local inference option (future)

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
