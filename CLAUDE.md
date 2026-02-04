# CLAUDE.md

This file provides guidance for Claude Code when working with this repository.

## Project Overview

LensDaemon is an Android application that transforms smartphones into dedicated video streaming appliances (streaming cameras, security monitors, or recording endpoints). It leverages the superior imaging hardware in modern phones while avoiding the thermal and battery issues of running a full Android OS.

**Status:** Phase 4 complete - Hardware video encoding with H.264/H.265 MediaCodec, NAL unit parsing, and adaptive bitrate.

## Tech Stack

- **Platform:** Android 10+ (API 29+)
- **Language:** Kotlin
- **Build System:** Gradle
- **Core APIs:** Camera2 API, MediaCodec, Device Owner APIs, BatteryManager
- **Streaming:** RTSP (port 8554), SRT (port 9000), MJPEG preview
- **Storage:** Local MP4, SMB/NFS, S3-compatible (AWS S3, Backblaze B2, MinIO, Cloudflare R2)
- **Web Interface:** HTTP server on port 8080 with REST API

## Build Commands

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

## Project Structure

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

## Development Phases (10-Phase Plan)

See `docs/IMPLEMENTATION_GUIDE.md` for the complete 10-phase implementation guide.

| Phase | Focus | Status |
|-------|-------|--------|
| 1 | Project Foundation | Complete |
| 2 | Camera2 Pipeline | Complete |
| 3 | Lens Control | Complete |
| 4 | Video Encoding | Complete |
| 5 | RTSP Server | Pending |
| 6 | Web Interface | Pending |
| 7 | Local Recording | Pending |
| 8 | Network Storage | Pending |
| 9 | Thermal Management | Pending |
| 10 | Kiosk Mode | Pending |

## Contribution Areas

- **Device profiles** - Test on specific hardware, document quirks
- **Thermal tuning** - Find sustainable encoding settings per device
- **Protocol support** - ONVIF, NDI, WebRTC
- **Storage backends** - Additional cloud/network targets

## Key Files

- `README.md` - Project overview and quick start
- `spec.md` - Detailed technical specification
- `docs/IMPLEMENTATION_GUIDE.md` - 10-phase implementation roadmap
- `LICENSE` - MIT license

## Phase 2 Files (Camera2 Pipeline)

```
app/src/main/java/com/lensdaemon/camera/
├── CameraConfig.kt              # Camera configuration data classes
│                                # - CameraLens, LensType, CaptureConfig
│                                # - FocusMode, WhiteBalanceMode, StabilizationMode
│                                # - CameraCapabilities, CameraState, CameraError
├── LensDaemonCameraManager.kt   # Camera2 API wrapper
│                                # - Camera enumeration and lens detection
│                                # - Multi-camera/physical camera support
│                                # - Session management and frame capture
│                                # - Zoom, focus, exposure, white balance control
├── PreviewSurfaceProvider.kt    # Preview surface lifecycle management
│                                # - SurfaceView and TextureView support
│                                # - Optimal size calculation
│                                # - Transform matrix for aspect ratio
└── CameraService.kt             # Foreground camera service (updated)
                                 # - Full Camera2 pipeline integration
                                 # - Preview start/stop, lens switching
                                 # - Camera state observation via StateFlow
```

## Phase 3 Files (Lens Control & Camera Configuration)

```
app/src/main/java/com/lensdaemon/camera/
├── LensController.kt            # Multi-lens camera management
│                                # - Automatic lens selection for zoom levels
│                                # - Zoom presets based on available lenses
│                                # - Effective zoom calculation (optical + digital)
│                                # - Smooth zoom animation support
├── FocusController.kt           # Focus control and tap-to-focus
│                                # - Tap-to-focus with metering regions
│                                # - Focus state tracking (inactive/scanning/success/failed)
│                                # - Manual focus distance control
│                                # - Focus lock/unlock operations
├── ExposureController.kt        # Exposure and white balance control
│                                # - AE lock/unlock with state tracking
│                                # - Exposure compensation (EV adjustment)
│                                # - Spot metering at touch points
│                                # - White balance modes and AWB lock
├── ZoomController.kt            # Smooth zoom and pinch-to-zoom
│                                # - ValueAnimator-based smooth zoom
│                                # - Pinch gesture handling
│                                # - Android 11+ CONTROL_ZOOM_RATIO support
│                                # - Legacy SCALER_CROP_REGION fallback
└── CameraService.kt             # Updated with all controllers integrated
                                 # - Public APIs for touch interactions
                                 # - Controller lifecycle management

app/src/main/res/
├── drawable/
│   ├── focus_indicator.xml      # Focus rectangle drawable
│   └── zoom_background.xml      # Zoom level display background
├── values/
│   ├── colors.xml               # Updated with focus state colors
│   └── dimens.xml               # UI dimensions for focus/zoom elements
└── layout/
    └── activity_main.xml        # Updated with focus indicator and zoom display
```

## Phase 4 Files (Hardware Video Encoding)

```
app/src/main/java/com/lensdaemon/encoder/
├── EncoderConfig.kt             # Encoding configuration data classes
│                                # - VideoCodec (H264, H265), BitrateMode (VBR, CBR, CQ)
│                                # - H264Profile, H265Profile enum classes
│                                # - EncoderConfig with presets (720p, 1080p, 4K)
│                                # - EncoderState, EncoderError, EncoderStats
│                                # - EncodedFrame data class with metadata
├── NalUnitParser.kt             # H.264/H.265 NAL unit extraction
│                                # - Start code detection (3-byte and 4-byte)
│                                # - NAL type parsing for AVC and HEVC
│                                # - SPS/PPS/VPS caching for streaming setup
│                                # - NalUnit data class with payload extraction
│                                # - NalUnitBuilder for creating NAL streams
├── VideoEncoder.kt              # MediaCodec-based video encoder
│                                # - Hardware encoder detection and selection
│                                # - Surface input mode for camera integration
│                                # - Async callback-based encoding
│                                # - Dynamic bitrate and keyframe control
│                                # - Encoder capabilities detection
├── FrameProcessor.kt            # Camera-to-encoder bridge
│                                # - Frame flow management with backpressure
│                                # - Encoded frame SharedFlow for consumers
│                                # - Adaptive bitrate support
│                                # - FrameProcessorFactory for common configs
└── EncoderService.kt            # Foreground encoder service
                                 # - Service binding for camera integration
                                 # - Notification management
                                 # - Frame listener dispatch
                                 # - Encoder lifecycle management

app/src/main/java/com/lensdaemon/camera/
└── CameraService.kt             # Updated with encoder integration
                                 # - EncoderService binding
                                 # - Streaming start/stop with encoding
                                 # - Encoded frame listeners
                                 # - Adaptive bitrate control APIs
```
