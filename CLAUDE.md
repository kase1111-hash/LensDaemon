# CLAUDE.md

This file provides guidance for Claude Code when working with this repository.

## Project Overview

LensDaemon is an Android application that transforms smartphones into dedicated video streaming appliances (streaming cameras, security monitors, or recording endpoints). It leverages the superior imaging hardware in modern phones while avoiding the thermal and battery issues of running a full Android OS.

**Status:** Early specification phase - no source code committed yet.

## Tech Stack

- **Platform:** Android 10+ (API 29+)
- **Language:** Kotlin
- **Build System:** Gradle
- **Core APIs:** Camera2 API, MediaCodec, Device Owner APIs, BatteryManager
- **Streaming:** RTSP (port 8554), SRT (port 9000), MJPEG preview
- **Storage:** Local MP4, SMB/NFS, S3-compatible (AWS S3, Backblaze B2, MinIO, Cloudflare R2)
- **Web Interface:** HTTP server on port 8080 with REST API

## Build Commands (Planned)

```bash
# Build the project
./gradlew build

# Run tests
./gradlew test

# Lint check
./gradlew lint

# Generate debug APK
./gradlew assembleDebug

# Generate release APK
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug
```

## Project Structure (Planned)

```
com.lensdaemon/
├── app/src/main/java/com/lensdaemon/
│   ├── LensDaemonApp.kt          # Application entry
│   ├── MainActivity.kt           # Main activity
│   ├── AdminReceiver.kt          # Device admin receiver
│   ├── camera/                   # Camera2 pipeline, lens control
│   ├── encoder/                  # MediaCodec, adaptive bitrate
│   ├── output/                   # RTSP, SRT, file writer
│   ├── storage/                  # SMB, S3, local storage
│   ├── thermal/                  # Temperature monitoring, governors
│   ├── web/                      # HTTP server, REST API, dashboard
│   └── kiosk/                    # Device Owner lockdown
├── docs/
└── README.md, LICENSE, spec.md
```

## Key Services Architecture

1. **Camera Service** - Camera2 pipeline, lens switching (wide/main/tele), frame processing
2. **Encoder Service** - H.264/H.265 hardware encoding, adaptive bitrate
3. **Output Manager** - RTSP server, SRT publisher, MP4 file writer
4. **Thermal Monitor** - CPU/battery temperature tracking, throttling governors
5. **Storage Manager** - Local, SMB/NFS, S3-compatible uploads
6. **Web Server** - Dashboard UI, REST API endpoints
7. **Kiosk Manager** - Device Owner mode, boot autostart

## REST API Endpoints

```
GET  /api/status              # Device info, temps, streaming state
POST /api/stream/start        # Start streaming
POST /api/stream/stop         # Stop streaming
POST /api/lens/{wide|main|tele}  # Switch camera lens
POST /api/snapshot            # Capture JPEG snapshot
GET  /api/config              # Get configuration
PUT  /api/config              # Update configuration
```

## Development Setup

### Device Requirements
- Android 10+ (API 29)
- Camera2 API Level 3 (FULL or LEVEL_3)
- H.264 hardware encoder
- WiFi connectivity

### Testing Commands
```bash
# Enable kiosk/Device Owner mode
adb shell dpm set-device-owner com.lensdaemon/.AdminReceiver

# View logs
adb logcat -s LensDaemon

# Test RTSP stream (requires VLC or similar)
rtsp://{device-ip}:8554/stream

# Test web interface
http://{device-ip}:8080
```

## Development Phases

1. **Phase 1 (MVP):** Camera2 capture, H.264 encoding, basic RTSP, minimal web UI
2. **Phase 2:** Local recording, SMB/S3 upload, thermal dashboard, adaptive bitrate
3. **Phase 3:** Kiosk mode, boot autostart, battery bypass, SRT support
4. **Phase 4:** Setup wizard, device profiles, OTA updates, multi-device management

## Contribution Areas

- **Device profiles** - Test on specific hardware, document quirks
- **Thermal tuning** - Find sustainable encoding settings per device
- **Protocol support** - ONVIF, NDI, WebRTC
- **Storage backends** - Additional cloud/network targets

## Key Files

- `README.md` - Project overview and quick start
- `spec.md` - Detailed technical specification
- `LICENSE` - MIT license
