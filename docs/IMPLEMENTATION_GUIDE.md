# LensDaemon Implementation Guide

A 10-phase roadmap for building LensDaemon in manageable, codable sections.

---

## Phase 1: Project Foundation

**Goal:** Create the Android project skeleton with all required dependencies and permissions.

**Deliverables:**
- [x] Gradle project structure (root + app module)
- [x] `settings.gradle.kts` and `build.gradle.kts` files
- [x] `AndroidManifest.xml` with all required permissions
- [x] `LensDaemonApp.kt` - Application class
- [x] `MainActivity.kt` - Main entry point with basic UI
- [x] `AdminReceiver.kt` - Device admin receiver stub
- [x] Base package structure for all modules

**Key Dependencies:**
- Kotlin 1.9+
- AndroidX Core/AppCompat
- Coroutines for async operations
- NanoHTTPD for embedded web server
- Timber for logging

**Files Created:**
```
app/
├── build.gradle.kts
├── src/main/
│   ├── AndroidManifest.xml
│   └── java/com/lensdaemon/
│       ├── LensDaemonApp.kt
│       ├── MainActivity.kt
│       └── AdminReceiver.kt
build.gradle.kts (root)
settings.gradle.kts
gradle.properties
```

---

## Phase 2: Camera2 Pipeline Foundation

**Goal:** Establish the Camera2 capture pipeline with preview capability.

**Deliverables:**
- [x] `CameraService.kt` - Foreground service for camera operations
- [x] Camera enumeration (list available cameras/lenses)
- [x] Camera session management (open/close/configure)
- [x] SurfaceView/TextureView for preview display
- [x] Basic frame callback infrastructure
- [x] Camera lifecycle handling (pause/resume)

**Key APIs:**
- `CameraManager`, `CameraDevice`, `CameraCaptureSession`
- `ImageReader` for frame access
- `SurfaceTexture` for preview

**Files Created:**
```
camera/
├── CameraService.kt
├── CameraManager.kt (wrapper)
├── CameraConfig.kt (data classes)
└── PreviewSurfaceProvider.kt
```

---

## Phase 3: Lens Control & Camera Configuration

**Goal:** Full camera control including multi-lens switching and manual parameters.

**Deliverables:**
- [x] `LensController.kt` - Multi-lens detection and switching
- [x] Camera capability detection (zoom ranges, OIS, etc.)
- [x] Zoom control (optical + digital)
- [x] Focus control (auto/manual/tap-to-focus)
- [x] Exposure compensation control
- [x] White balance control
- [x] Lens switch without stream interruption

**Key APIs:**
- `CameraCharacteristics` for capability queries
- `CaptureRequest.Builder` for parameter control
- Logical camera → physical camera mapping

**Files Created:**
```
camera/
├── LensController.kt
├── CameraCapabilities.kt
├── FocusController.kt
└── ExposureController.kt
```

---

## Phase 4: Hardware Video Encoding

**Goal:** Encode camera frames to H.264/H.265 using hardware MediaCodec.

**Deliverables:**
- [x] `EncoderService.kt` - MediaCodec-based encoder
- [x] `FrameProcessor.kt` - Camera → Encoder bridge
- [x] H.264 baseline profile encoding
- [x] H.265 encoding (device-dependent)
- [x] Configurable resolution/bitrate/framerate
- [x] Keyframe interval control
- [x] NAL unit extraction for streaming

**Key APIs:**
- `MediaCodec` (encoder mode)
- `MediaFormat` for codec configuration
- Surface-to-encoder pipeline

**Files Created:**
```
encoder/
├── EncoderService.kt
├── EncoderConfig.kt
├── FrameProcessor.kt
├── H264Encoder.kt
└── NalUnitParser.kt
```

---

## Phase 5: RTSP Server Implementation

**Goal:** Serve encoded video stream over RTSP protocol.

**Deliverables:**
- [x] `RtspServer.kt` - RTSP protocol implementation
- [x] RTSP session handling (DESCRIBE, SETUP, PLAY, TEARDOWN)
- [x] SDP generation for stream description
- [x] RTP packetization for H.264/H.265
- [x] Multi-client support
- [x] RTSP interleaved (TCP) and UDP modes
- [x] Connection timeout handling

**Protocol Details:**
- Port: 8554
- Path: `/live` or `/stream`
- Authentication: optional digest auth

**Files Created:**
```
output/
├── RtspServer.kt
├── RtspSession.kt
├── RtpPacketizer.kt
├── SdpGenerator.kt
└── RtspConstants.kt
```

---

## Phase 6: Web Server & Dashboard

**Goal:** HTTP-based control interface with live preview and REST API.

**Deliverables:**
- [x] `WebServer.kt` - NanoHTTPD-based HTTP server
- [x] `ApiRoutes.kt` - REST endpoint handlers
- [x] `/api/status` - Device and stream status
- [x] `/api/stream/start|stop` - Stream control
- [x] `/api/lens/{lens}` - Lens control
- [x] `/api/config` - Configuration get/set
- [x] `/api/snapshot` - JPEG snapshot capture
- [x] MJPEG live preview endpoint
- [x] Dashboard HTML/CSS/JS (single-page app)

**Web Interface Features:**
- Live MJPEG preview
- Stream control buttons
- Lens selector
- Status display
- Settings panel

**Files Created:**
```
web/
├── WebServer.kt
├── ApiRoutes.kt
├── MjpegStreamer.kt
├── assets/
│   ├── index.html
│   ├── dashboard.js
│   └── styles.css
```

---

## Phase 7: Local Recording & Storage

**Goal:** Record video to local MP4 files with segmentation.

**Deliverables:**
- [x] `FileWriter.kt` - MP4 muxer for local recording
- [x] `StorageManager.kt` - Storage coordination
- [x] Configurable segment duration (1/5/15/60 min)
- [x] Filename pattern: `LensDaemon_{device}_{timestamp}.mp4`
- [x] Disk space monitoring
- [x] Retention policy (max age/size)
- [x] Recording state management (start/stop/pause)

**Key APIs:**
- `MediaMuxer` for MP4 container
- `StorageManager` for disk queries

**Files Created:**
```
output/
├── FileWriter.kt
├── Mp4Muxer.kt
storage/
├── StorageManager.kt
├── LocalStorage.kt
└── RetentionPolicy.kt
```

---

## Phase 8: Network Storage (SMB/S3)

**Goal:** Upload recordings to network shares and cloud storage.

**Deliverables:**
- [x] `SmbClient.kt` - SMB/CIFS share client
- [x] `S3Client.kt` - S3-compatible upload client
- [x] Background upload service
- [x] Upload queue with retry logic
- [x] Network failure buffering
- [x] Multipart upload for large files
- [x] Credential storage (encrypted)
- [x] Upload progress reporting

**Supported Backends:**
- SMB/NFS network shares
- AWS S3, Backblaze B2, MinIO, Cloudflare R2

**Files Created:**
```
storage/
├── SmbClient.kt
├── S3Client.kt
├── UploadService.kt
├── UploadQueue.kt
└── CredentialStore.kt
```

---

## Phase 9: Thermal Management

**Goal:** Monitor device thermals and implement protective measures.

**Deliverables:**
- [x] `ThermalMonitor.kt` - CPU/battery temperature reading
- [x] `ThermalGovernor.kt` - Automatic throttling logic
- [x] `BatteryBypass.kt` - Charge limiting for longevity
- [x] `AdaptiveBitrate.kt` - Dynamic bitrate adjustment
- [x] Thermal history logging (24-hour)
- [x] Dashboard thermal display
- [x] Configurable thresholds
- [x] Emergency shutdown protection

**Thermal Thresholds:**
- CPU > 45°C → Reduce bitrate 20%
- CPU > 50°C → Reduce resolution
- CPU > 55°C → Reduce framerate
- CPU > 60°C → Pause streaming
- Battery > 42°C → Disable charging

**Files Created:**
```
thermal/
├── ThermalMonitor.kt
├── ThermalGovernor.kt
├── BatteryBypass.kt
├── ThermalHistory.kt
└── ThermalConfig.kt
encoder/
└── AdaptiveBitrate.kt
```

---

## Phase 10: Kiosk Mode & Reliability

**Goal:** Lock device to single-purpose appliance mode with boot persistence.

**Deliverables:**
- [x] `KioskManager.kt` - Device Owner API integration
- [x] `BootReceiver.kt` - Auto-start on boot
- [x] Lock task mode (no home/recent escape)
- [x] Status bar/navigation bar hiding
- [x] Physical button override (Vol+Vol+Power)
- [x] Screen control (off/dim/preview)
- [x] Crash recovery and auto-restart
- [x] Network reconnection handling
- [ ] Setup wizard for initial configuration

**Device Owner Setup:**
```bash
adb shell dpm set-device-owner com.lensdaemon/.AdminReceiver
```

**Files Created:**
```
kiosk/
├── KioskManager.kt
├── BootReceiver.kt
├── ScreenController.kt
└── ButtonOverrideHandler.kt
```

---

## Summary Table

| Phase | Focus Area | Key Components |
|-------|------------|----------------|
| 1 | Project Foundation | Gradle, Manifest, App skeleton |
| 2 | Camera2 Pipeline | Camera service, preview, frame capture |
| 3 | Lens Control | Multi-lens, zoom, focus, exposure |
| 4 | Video Encoding | MediaCodec H.264/H.265, NAL parsing |
| 5 | RTSP Server | Protocol handling, RTP packetization |
| 6 | Web Interface | HTTP server, REST API, dashboard |
| 7 | Local Recording | MP4 muxing, segmentation, retention |
| 8 | Network Storage | SMB/S3 upload, queue, retry |
| 9 | Thermal Management | Monitoring, governors, battery bypass |
| 10 | Kiosk Mode | Device Owner, boot, reliability |

---

## Development Notes

- Each phase builds on previous phases
- Test thoroughly before moving to next phase
- Phases 1-5 form the core streaming MVP
- Phases 6-7 add usability and storage
- Phases 8-10 add enterprise/appliance features
- Target devices: Pixel 5+, Samsung S20+, OnePlus 8+
