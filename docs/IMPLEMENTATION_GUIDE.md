# LensDaemon Implementation Guide

A 10-phase roadmap for building LensDaemon in manageable, codable sections.

---

## Phase 1: Project Foundation

**Goal:** Create the Android project skeleton with all required dependencies and permissions.

**Deliverables:**
- [ ] Gradle project structure (root + app module)
- [ ] `settings.gradle.kts` and `build.gradle.kts` files
- [ ] `AndroidManifest.xml` with all required permissions
- [ ] `LensDaemonApp.kt` - Application class
- [ ] `MainActivity.kt` - Main entry point with basic UI
- [ ] `AdminReceiver.kt` - Device admin receiver stub
- [ ] Base package structure for all modules

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
- [ ] `CameraService.kt` - Foreground service for camera operations
- [ ] Camera enumeration (list available cameras/lenses)
- [ ] Camera session management (open/close/configure)
- [ ] SurfaceView/TextureView for preview display
- [ ] Basic frame callback infrastructure
- [ ] Camera lifecycle handling (pause/resume)

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
- [ ] `LensController.kt` - Multi-lens detection and switching
- [ ] Camera capability detection (zoom ranges, OIS, etc.)
- [ ] Zoom control (optical + digital)
- [ ] Focus control (auto/manual/tap-to-focus)
- [ ] Exposure compensation control
- [ ] White balance control
- [ ] Lens switch without stream interruption

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
- [ ] `EncoderService.kt` - MediaCodec-based encoder
- [ ] `FrameProcessor.kt` - Camera → Encoder bridge
- [ ] H.264 baseline profile encoding
- [ ] H.265 encoding (device-dependent)
- [ ] Configurable resolution/bitrate/framerate
- [ ] Keyframe interval control
- [ ] NAL unit extraction for streaming

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
- [ ] `RtspServer.kt` - RTSP protocol implementation
- [ ] RTSP session handling (DESCRIBE, SETUP, PLAY, TEARDOWN)
- [ ] SDP generation for stream description
- [ ] RTP packetization for H.264/H.265
- [ ] Multi-client support
- [ ] RTSP interleaved (TCP) and UDP modes
- [ ] Connection timeout handling

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
- [ ] `WebServer.kt` - NanoHTTPD-based HTTP server
- [ ] `ApiRoutes.kt` - REST endpoint handlers
- [ ] `/api/status` - Device and stream status
- [ ] `/api/stream/start|stop` - Stream control
- [ ] `/api/lens/{lens}` - Lens control
- [ ] `/api/config` - Configuration get/set
- [ ] `/api/snapshot` - JPEG snapshot capture
- [ ] MJPEG live preview endpoint
- [ ] Dashboard HTML/CSS/JS (single-page app)

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
- [ ] `FileWriter.kt` - MP4 muxer for local recording
- [ ] `StorageManager.kt` - Storage coordination
- [ ] Configurable segment duration (1/5/15/60 min)
- [ ] Filename pattern: `LensDaemon_{device}_{timestamp}.mp4`
- [ ] Disk space monitoring
- [ ] Retention policy (max age/size)
- [ ] Recording state management (start/stop/pause)

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
- [ ] `SmbClient.kt` - SMB/CIFS share client
- [ ] `S3Client.kt` - S3-compatible upload client
- [ ] Background upload service
- [ ] Upload queue with retry logic
- [ ] Network failure buffering
- [ ] Multipart upload for large files
- [ ] Credential storage (encrypted)
- [ ] Upload progress reporting

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
- [ ] `ThermalMonitor.kt` - CPU/battery temperature reading
- [ ] `ThermalGovernor.kt` - Automatic throttling logic
- [ ] `BatteryBypass.kt` - Charge limiting for longevity
- [ ] `AdaptiveBitrate.kt` - Dynamic bitrate adjustment
- [ ] Thermal history logging (24-hour)
- [ ] Dashboard thermal display
- [ ] Configurable thresholds
- [ ] Emergency shutdown protection

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
- [ ] `KioskManager.kt` - Device Owner API integration
- [ ] `BootReceiver.kt` - Auto-start on boot
- [ ] Lock task mode (no home/recent escape)
- [ ] Status bar/navigation bar hiding
- [ ] Physical button override (Vol+Vol+Power)
- [ ] Screen control (off/dim/preview)
- [ ] Crash recovery and auto-restart
- [ ] Network reconnection handling
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
