# CLAUDE.md

This file provides guidance for Claude Code when working with this repository.

## Project Overview

LensDaemon is an Android application that transforms smartphones into dedicated video streaming appliances (streaming cameras, security monitors, or recording endpoints). It leverages the superior imaging hardware in modern phones while avoiding the thermal and battery issues of running a full Android OS.

**Status:** All 10 implementation phases done, followed by a post-evaluation remediation (security, CI/CD, MPEG-TS/UDP streaming) and a second review round that fixed thermal throttling, recording and storage, uploads, kiosk recovery and the AI Director (see Post-Evaluation Remediation).

## Tech Stack

- **Platform:** Android 10+ (API 29+)
- **Language:** Kotlin
- **Build System:** Gradle
- **Core APIs:** Camera2 API, MediaCodec, Device Owner APIs, BatteryManager
- **Streaming:** RTSP (port 8554), MPEG-TS/UDP (port 9000), MJPEG preview
- **Storage:** Local MP4; uploads to SMB shares and S3-compatible buckets (AWS S3, Backblaze B2, MinIO, Cloudflare R2)
- **Web Interface:** HTTP server on port 8080 with REST API

## Build Commands

```bash
# Build the project
./gradlew build

# Run tests
./gradlew test

# Static analysis (detekt)
./gradlew detekt

# Lint check
./gradlew lint

# Generate debug APK
./gradlew assembleDebug

# Generate release APK
./gradlew assembleRelease

# Install on connected device (installCoreDebug for the build without the AI Director)
./gradlew installFullDebug
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
│   ├── output/                   # RTSP, MPEG-TS/UDP, file writer
│   ├── storage/                  # SMB, S3, local storage
│   ├── thermal/                  # Temperature monitoring, governors
│   ├── web/                      # HTTP server, REST API, dashboard
│   ├── director/                 # CameraControllerAdapter (the director's camera bridge)
│   └── kiosk/                    # Device Owner lockdown
├── director/src/main/java/com/lensdaemon/director/
│                                 # :director module: AI Director (parser, mapper, takes)
├── docs/
└── README.md, LICENSE, spec.md
```

## Key Services Architecture

1. **Camera Service** - Camera2 pipeline, lens switching (wide/main/tele), frame processing
2. **Encoder Service** - H.264/H.265 hardware encoding, adaptive bitrate
3. **Output Manager** - RTSP server, MPEG-TS/UDP publisher, MP4 file writer
4. **Thermal Monitor** - CPU/battery temperature tracking, throttling governors
5. **Storage Manager** - Local recordings and retention; SMB and S3-compatible uploads
6. **Web Server** - Dashboard UI, REST API endpoints, rate limiting
7. **Kiosk Manager** - Device Owner mode, boot autostart
8. **Director Manager** - Script-driven camera automation (cues parsed on the phone)

## REST API Endpoints

```
GET  /api/status              # Device info, temps, streaming state
POST /api/stream/start        # Start the encoder only (no output)
POST /api/stream/stop         # Stop every output and the encoder
POST /api/lens/{wide|main|tele}  # Switch camera lens
POST /api/snapshot            # Capture JPEG snapshot
GET  /api/config              # Get configuration
PUT  /api/config              # Bitrate, zoom, exposure, stream defaults
POST /api/rtsp/start          # Start RTSP streaming
POST /api/rtsp/stop           # Stop RTSP streaming
GET  /api/rtsp/status         # RTSP server status
POST /api/mpegts/start        # Start MPEG-TS/UDP streaming
POST /api/mpegts/stop         # Stop MPEG-TS/UDP streaming
GET  /api/mpegts/status       # MPEG-TS/UDP publisher status
```

No API token is set by default (nothing in the UI or the API sets one), so anyone
on the network can use the API; browsers' cross-site POST/PUT/DELETE get 403.
docs/API.md documents every endpoint.

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
| 5 | RTSP Server | Complete |
| 6 | Web Interface | Complete |
| 7 | Local Recording | Complete |
| 8 | Network Storage | Complete |
| 9 | Thermal Management | Complete |
| 10 | Kiosk Mode | Complete |

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
│                                # - CameraLens, LensType, CaptureConfig (incl. AF/AE regions)
│                                # - FocusMode, WhiteBalanceMode, StabilizationMode
│                                # - CameraCapabilities, CameraState, CameraError
├── LensDaemonCameraManager.kt   # Camera2 API wrapper
│                                # - Camera enumeration and lens detection
│                                # - Multi-camera/physical camera support
│                                # - Session management and frame capture
│                                # - Zoom, focus, exposure, white balance control
│                                # - captureCallback: every frame's capture result, on the
│                                #   camera thread (CameraService feeds the focus and
│                                #   exposure controllers' AF/AE state from it)
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
│                                # - A tap keeps CONTINUOUS_VIDEO and sets an AF region;
│                                #   a lens without continuous video AF gets no tap-to-focus
│                                #   (isTapToFocusSupported; POST /api/focus answers 409;
│                                #   a tap on the phone still meters exposure there)
│                                # - focusState follows each frame's AF state
│                                #   (updateFromCaptureResult); countsAsInFocus() for take
│                                #   scores: continuous AF once focused, else always
│                                # - Manual focus distance control
│                                # - lockFocus holds the focus: AUTO, no AF trigger (the
│                                #   director's [FOCUS: MANUAL]); none on a lens without
│                                #   AUTO; a lens change or reopen goes back to continuous
├── ExposureController.kt        # Exposure and white balance control
│                                # - AE lock/unlock with state tracking
│                                # - Exposure compensation (EV adjustment, clamped)
│                                # - Spot metering at touch points (AE region)
│                                # - White balance modes and AWB lock
├── ZoomController.kt            # Smooth zoom and pinch-to-zoom
│                                # - Coroutine animation; callable from any thread
│                                # - Incremental pinch; non-finite values are ignored
├── MeteringRegions.kt           # Tap point -> AF/AE rectangle inside the sensor's
│                                # 16:9 output band, mapped through the zoom crop
├── CaptureRequestExtras.kt      # LensGeometry; applyZoom (CONTROL_ZOOM_RATIO on
│                                # Android 11+, centred SCALER_CROP_REGION on 10);
│                                # applyMeteringRegions; captureResultCallback
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
│                                # - onError for non-transient codec errors; fps and
│                                #   bitrate measured over the last 2 s
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
                                 # - setConfiguredBitrate: a lasting bitrate, clamped
                                 #   to the codec's range (PUT /api/config)

app/src/main/java/com/lensdaemon/camera/
└── CameraService.kt             # Updated with encoder integration
                                 # - EncoderService binding
                                 # - Streaming start/stop with encoding
                                 # - Encoded frame listeners
                                 # - Adaptive bitrate control APIs
```

## Phase 5 Files (RTSP Server Implementation)

```
app/src/main/java/com/lensdaemon/output/
├── RtspConstants.kt             # RTSP protocol constants
│                                # - Status codes and method constants
│                                # - RtspRequest parser
│                                # - RtspResponse builder
│                                # - TransportParams parser (UDP/TCP)
├── SdpGenerator.kt              # SDP (Session Description Protocol) generator
│                                # - H.264/H.265 SDP generation
│                                # - Profile-level-id calculation
│                                # - SPS/PPS base64 encoding for fmtp
│                                # - Local IP address detection
├── RtpPacketizer.kt             # RTP packet creation and fragmentation
│                                # - RtpPacket data class with serialization
│                                # - H.264 FU-A fragmentation (RFC 6184)
│                                # - H.265 FU fragmentation (RFC 7798)
│                                # - NAL unit packetization
│                                # - Timestamp and sequence handling
├── RtspSession.kt               # Individual client session handler
│                                # - RTSP command processing (OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN)
│                                # - UDP unicast and TCP interleaved transport
│                                # - Session state machine
│                                # - Frame distribution to clients
│                                # - Session statistics tracking
└── RtspServer.kt                # Main RTSP server implementation
                                 # - Multi-client support (up to 10 clients)
                                 # - Server socket accept loop
                                 # - Codec config management
                                 # - Server statistics and monitoring
                                 # - RtspServerBuilder for configuration

app/src/main/java/com/lensdaemon/camera/
└── CameraService.kt             # Updated with RTSP integration
                                 # - RTSP server lifecycle management
                                 # - startRtspStreaming/stopRtspStreaming convenience methods
                                 # - RTSP URL getter
                                 # - Client count and statistics
```

## Phase 6 Files (Web Interface & Dashboard)

```
app/src/main/java/com/lensdaemon/web/
├── WebServer.kt                 # NanoHTTPD-based HTTP server
│                                # - Static asset serving from assets/web/
│                                # - API routing to ApiRoutes handler
│                                # - MJPEG stream endpoint (/mjpeg, /stream.mjpeg), with
│                                #   the API's rate limit and token rule (ApiRoutes.admit)
│                                # - Never gzips text/event-stream (useGzipWhenAccepted)
│                                # - MIME type detection
│                                # - Server statistics tracking
├── ApiRoutes.kt                 # REST API endpoint handlers
│                                # - GET /api/status - device info, temps, streaming state
│                                # - POST /api/stream/start|stop - encoding control
│                                # - POST /api/rtsp/start|stop - RTSP server control
│                                # - POST /api/lens/{wide|main|tele} - lens switching
│                                # - POST /api/zoom - set zoom (answers the applied zoom)
│                                # - POST /api/focus - focus region at x, y, or {reset: true}
│                                #   (409 on a lens without tap-to-focus)
│                                # - POST /api/exposure - exposure compensation
│                                # - POST /api/snapshot - capture JPEG
│                                # - GET/PUT /api/config - bitrate, zoom, exposure and
│                                #   the phone's stream defaults
├── MjpegStreamer.kt             # MJPEG live preview streaming
│                                # - Motion JPEG multipart response
│                                # - pushFrame only fills each viewer's one-frame slot;
│                                #   each viewer's own connection thread writes
│                                # - At most 8 viewers: at the cap the one longest without
│                                #   a write (over 3 s) makes room, else 503; a viewer
│                                #   whose write is stuck for 5 s is closed
│                                # - Frame rate limiting (configurable max FPS)
│                                # - JpegFrameConverter for YUV/Bitmap conversion
└── WebServerService.kt          # Foreground web server service
                                 # - Service binding for activity integration
                                 # - CameraService binding for API access
                                 # - Notification management
                                 # - MJPEG frame pushing API

app/src/main/assets/web/
├── index.html                   # Dashboard HTML (two columns, then full-width rows)
│                                # - Left: live preview, Streaming (encoder + Stop All),
│                                #   RTSP, MPEG-TS over UDP, Recording (a recorder error,
│                                #   and why the last recording stopped: lastError)
│                                # - Right: Camera (the phone's own lenses, zoom up to the
│                                #   camera's max, exposure in EV), Encoder settings (with
│                                #   Save as phone default), Statistics, Temperature,
│                                #   Kiosk mode (settings and exit PIN)
│                                # - Full width: Recordings (storage space, download,
│                                #   upload to S3/SMB, delete, clean-up), Network storage
│                                #   (upload queue with failed uploads, S3 settings with an
│                                #   http:// endpoint for plain HTTP, SMB settings with the
│                                #   port, each with Test), AI Director
│                                # - Header link to the setup wizard (/setup)
├── styles.css                   # Dashboard styles (dark theme, 16px gutter on phones)
├── dashboard.js                 # Dashboard JavaScript
│                                # - apiCall(): JSON in/out, error text from {"error"}
│                                # - Polls /api/status (2s), thermal and upload (5s),
│                                #   kiosk (10s), recordings (30s); stops while hidden
│                                # - notify(): corner notices instead of alert()
├── setup.html, setup.js         # Setup wizard: Device Owner check (warns that only a
│                                # factory reset removes it from a normal install),
│                                # preset, S3/SMB
│                                # (tested as typed, saved on activation), RTSP test
│                                # stream with preview and temperatures, an optional exit
│                                # PIN (set before kiosk mode goes on), activation that
│                                # stops at the first failed step
```

The pages read the fields the handlers write. When an endpoint's JSON changes,
change the page with it; docs/API.md documents both.

## Phase 7 Files (Local Recording & Storage)

```
app/src/main/java/com/lensdaemon/output/
├── Mp4Muxer.kt                  # MediaMuxer wrapper for MP4 container
│                                # - MuxerConfig, MuxerState, MuxerStats
│                                # - Video track configuration from encoder format
│                                # - Frame writing with timestamp handling
│                                # - File finalization and cleanup
│                                # - Mp4MuxerBuilder for configuration
└── FileWriter.kt                # Local recording with segmentation
                                 # - RecordingState, RecordingStats, RecordingEvent
                                 # - SegmentDuration (1/5/15/30/60 min, continuous)
                                 # - File names: LensDaemon_{model}_{yyyyMMdd_HHmmss}_seg{NNN}.mp4
                                 #   (safeDeviceName; no _seg part for one continuous file)
                                 # - Recording pause/resume support
                                 # - FileWriterFactory for common configs

app/src/main/java/com/lensdaemon/storage/
├── RetentionPolicy.kt           # Storage retention and cleanup
│                                # - RetentionType (KEEP_ALL, MAX_AGE, MAX_SIZE, MAX_AGE_AND_SIZE)
│                                # - RetentionConfig presets (24h, 7d, 30d, 5GB, 10GB);
│                                #   the app uses DEFAULT only (7 days, 10 GB, keep 1 GB
│                                #   free), with no setting for it
│                                # - StorageStats with file enumeration
│                                # - Preview and enforce methods, with an exclude set
│                                #   (the segment being written is never deleted)
│                                # - RetentionPolicyBuilder for configuration
├── LocalStorage.kt              # Local storage operations
│                                # - StorageLocation (INTERNAL, EXTERNAL_APP, EXTERNAL_PUBLIC, CUSTOM)
│                                # - StorageSpaceInfo with disk monitoring
│                                # - RecordingFile with metadata
│                                # - Storage warning levels and events
│                                # - Recording enumeration and deletion
└── StorageManager.kt            # Storage coordination layer
                                 # - StorageManagerState, StorageStatus
                                 # - FileWriter lifecycle management
                                 # - LocalStorage and RetentionPolicy integration
                                 # - Retention at each recording start, hourly while
                                 #   recording, and when free space falls under 1 GB
                                 #   or 500 MB; no start under 500 MB free; a recording
                                 #   still under 500 MB after clean-up stops through
                                 #   onStorageFull
                                 # - Refuses a second start while RECORDING or PAUSED
                                 # - Combined streaming and recording
                                 # - StorageManagerBuilder for configuration

app/src/main/java/com/lensdaemon/camera/
├── RecordingCoordinator.kt      # Recording for CameraService: its own LocalStorage,
│                                # so list, delete and storage status work before any
│                                # recording; synchronous enforceRetention(); lastError
│                                # (last RecordingEvent.Error since the last start, kept
│                                # after the stop that follows it)
└── CameraService.kt             # Updated with recording integration
                                 # - initializeRecording, startRecording, stopRecording
                                 #   (a start while a recording runs or is paused changes
                                 #   nothing; one left in ERROR is replaced)
                                 # - pauseRecording, resumeRecording
                                 # - isRecordingOpen (also a failed recorder that still
                                 #   holds its file), getLastRecordingError
                                 # - getRecordingStats, getStorageStatus
                                 # - listRecordings, deleteRecording
                                 # - startStreamingAndRecording convenience method

app/src/main/java/com/lensdaemon/web/
└── ApiRoutes.kt                 # Updated with recording API endpoints
                                 # - POST /api/recording/start|stop|pause|resume
                                 #   (start answers alreadyRecording while one runs or
                                 #   is paused)
                                 # - GET /api/recording/status (recording = isRecordingOpen,
                                 #   so true while paused; lastError)
                                 # - GET /api/recordings, GET|DELETE /api/recordings/{name}
                                 #   (409 for the file being written)
                                 # - GET /api/storage/status
                                 # - POST /api/storage/cleanup (filesDeleted, bytesFreed)
```

## Phase 8 Files (Network Storage - S3 & SMB)

```
app/src/main/java/com/lensdaemon/storage/
├── CredentialStore.kt           # Encrypted credential storage
│                                # - S3Credentials with factory methods for AWS, B2, MinIO, R2
│                                # - SmbCredentials for SMB/CIFS shares
│                                # - StorageBackend enum (S3, BACKBLAZE_B2, MINIO, CLOUDFLARE_R2)
│                                # - Android Keystore for AES-256-GCM encryption
│                                # - Secure storage/retrieval/deletion; settings that can't
│                                #   be decrypted are removed (read as not set up)
│                                # - Kept out of Auto Backup and device transfer
├── UploadQueue.kt               # Persistent upload queue management
│                                # - UploadTask, UploadStatus, UploadDestination
│                                # - UploadQueueStats with progress tracking
│                                # - UploadEventListener for callbacks
│                                # - One upload at a time (a single processing loop)
│                                # - 3 attempts, 2 s and 4 s apart; then a failed list
│                                #   (retryFailed, cancelTask)
│                                # - Pending, in-flight, failed and the last 100 completed
│                                #   tasks persisted as JSON in SharedPreferences
├── S3Client.kt                  # S3-compatible storage client
│                                # - AWS Signature V4 (path-style, keys URI-encoded once,
│                                #   x-amz-content-sha256 always sent); S3SignerTest checks
│                                #   it against AWS's published examples
│                                # - Failures read "HTTP <code> <S3 error code>"
│                                # - Multipart upload for files >5MB
│                                # - Progress callback support
│                                # - testConnection, uploadFile, deleteObject
│                                # - S3UploadResult with ETag and version
│                                # - Supports AWS S3, Backblaze B2, MinIO, Cloudflare R2
├── SmbClient.kt                 # SMB/CIFS network share client (smbj, SMB 2/3)
│                                # - NTLM authentication
│                                # - smbPath() joins the path prefix and remote path
│                                # - Missing folders created before an upload and in
│                                #   testConnection, which also checks files can be created
│                                # - uploadFile (retried once on a fresh connection),
│                                #   testConnection, deleteFile, listFiles
│                                # - Progress callback with chunked writes
└── UploadService.kt             # Foreground upload service
                                 # - Service binding with UploadBinder
                                 # - S3 and SMB client coordination
                                 # - Foreground notification with progress
                                 # - startUploads, stopUploads (the current file
                                 #   finishes), enqueueFile (starts uploads)
                                 # - Queued uploads resume when the service starts
                                 # - Failed uploads retried when the default network
                                 #   comes back (kiosk networkRecovery.autoReconnect)
                                 # - configureS3, configureSmb with validation
                                 # - testS3Connection, testSmbConnection
                                 # - Credential management APIs

app/src/main/java/com/lensdaemon/web/
├── ApiRoutes.kt                 # Updated with upload API endpoints
│                                # - GET /api/upload/status - upload service state
│                                # - GET /api/upload/queue - pending/completed/failed tasks
│                                # - POST /api/upload/start|stop - control processing
│                                # - POST /api/upload/enqueue - a recording from the
│                                #   recordings list only (400 otherwise, 409 while open)
│                                # - DELETE /api/upload/task/{id} - cancel task
│                                # - POST /api/upload/retry - retry failed (answers retried)
│                                # - POST /api/upload/clear - clear pending
│                                # - GET/POST/DELETE /api/upload/s3/config
│                                # - POST /api/upload/s3/test - test connection
│                                # - GET/POST/DELETE /api/upload/smb/config
│                                # - POST /api/upload/smb/test - test connection
└── WebServerService.kt          # Updated with UploadService binding
                                 # - UploadService connection management
                                 # - uploadService reference for ApiRoutes
```

## Phase 9 Files (Thermal Management)

```
app/src/main/java/com/lensdaemon/thermal/
├── ThermalConfig.kt             # Thermal configuration and thresholds
│                                # - ThermalLevel enum (NORMAL, ELEVATED, WARNING, CRITICAL, EMERGENCY)
│                                # - ThrottleAction enum (REDUCE_BITRATE, REDUCE_RESOLUTION, etc.)
│                                # - CpuThermalConfig, BatteryThermalConfig thresholds
│                                # - BatteryBypassConfig for charge limiting
│                                # - ThermalStatus, ThermalStats data classes
│                                # - ThermalGovernorListener interface
│                                # - levelWithHysteresis(), throttlePlanFor() (pure, tested)
├── ThermalMonitor.kt            # Temperature monitoring service
│                                # - CPU temperature from /sys/class/thermal/
│                                # - Battery temperature from BatteryManager
│                                # - GPU temperature detection
│                                # - Android PowerManager thermal status (API 29+)
│                                # - Thermal zone discovery and classification
│                                # - StateFlow for reactive temperature updates
├── ThermalHistory.kt            # Temperature history logging
│                                # - 24-hour rolling history (minute granularity)
│                                # - ThermalEvent logging for state changes
│                                # - Statistics calculation (min/max/avg temps)
│                                # - Time-in-state tracking (steps capped, so downtime
│                                #   between app runs doesn't count)
│                                # - Levels taken from the governor (levelSource); events
│                                #   recorded by the governor only
│                                # - Saved every 10 entries and on release
│                                # - Graph data downsampling for dashboard
├── ThermalGovernor.kt           # Automatic throttling controller. Thresholds from the
│                                # device's profile or a saved override, applied in
│                                # initialize(); with the generic profile:
│                                # - CPU >= 50°C WARNING → bitrate -20%
│                                # - CPU >= 55°C CRITICAL → bitrate -40%, resolution one
│                                #   step down, <= 24 fps (encoder restarts, outputs kept)
│                                # - CPU >= 60°C or battery >= 48°C EMERGENCY → outputs
│                                #   paused, restarted once neither is at EMERGENCY
│                                # - Battery >= 42°C WARNING → charging held (thermal hold)
│                                # - Hysteresis 3°C (CPU) / 2°C (battery); the plan is
│                                #   worked out from both levels every 5 s cycle
│                                # - Callbacks on the main thread: onReduceBitrate,
│                                #   onEncoderLimits, onPauseStreaming, onResumeStreaming
│                                # - ALERT_USER only marks events in the history
├── BatteryBypass.kt             # Battery charge limiting
│                                # - Sysfs control when the node is writable by the app
│                                #   (no su); otherwise software-only: advice (unplug)
│                                #   and charging is never reported as blocked
│                                # - With control: hold 50%, charge again below 40%
│                                # - Manual hold (API) and thermal hold (governor), each
│                                #   lifted only by its own release
│                                # - BypassState tracking
└── ThermalService.kt            # Foreground thermal service
                                 # - Monitors as soon as it exists (binding is enough)
                                 # - Service binding with ThermalBinder
                                 # - ThermalGovernor lifecycle management
                                 # - Throttle callbacks for external systems
                                 # - Notification with thermal status
                                 # - Temperature-based notification colors

app/src/main/java/com/lensdaemon/web/
├── ApiRoutes.kt                 # Updated with thermal API endpoints
│                                # - GET /api/thermal/status - current temps and levels
│                                # - GET /api/thermal/history - graph data
│                                # - GET /api/thermal/stats - statistics
│                                # - GET /api/thermal/events - event log
│                                # - GET /api/thermal/battery - battery bypass status
│                                # - POST /api/thermal/battery/disable|enable (409 when
│                                #   the phone has no charging switch the app can write)
│                                # - GET/PUT/DELETE /api/thermal/profile (override),
│                                #   GET /api/thermal/profile/device, GET /api/thermal/profiles
│                                # - /api/thermal/stress-test answers 503 (not wired)
└── WebServerService.kt          # Updated with ThermalService binding
                                 # - ThermalService connection management
                                 # - thermalGovernor reference for ApiRoutes
                                 # - Governor callbacks run on one background thread:
                                 #   setThermalBitrateReduction, setThermalEncoderLimits,
                                 #   pauseForThermal, resumeAfterThermal
```

## Phase 10 Files (Kiosk Mode & Reliability)

```
app/src/main/java/com/lensdaemon/kiosk/
├── KioskConfig.kt               # Kiosk configuration data classes
│                                # - KioskState enum (DISABLED, ENABLED, SETUP_REQUIRED, NOT_DEVICE_OWNER)
│                                # - ScreenMode enum (PREVIEW, OFF, DIM, BLACK)
│                                # - AutoStartConfig (delay 0-120 s, startRtsp,
│                                #   startRecording; startStreaming alone starts nothing)
│                                # - ScreenConfig (mode, dim brightness, keepScreenOn;
│                                #   autoOffTimeoutSec and wakeOnMotion are not used)
│                                # - SecurityConfig (exit PIN of 4-12 digits, gesture, bars)
│                                # - NetworkRecoveryConfig (only autoReconnect is used:
│                                #   failed uploads retried when the network returns)
│                                # - CrashRecoveryConfig (autoRestart, maxRestartAttempts
│                                #   in restartWindowMinutes; restartDelayMs not used)
│                                # - KioskConfig presets (APPLIANCE, INTERACTIVE); withPreset
│                                #   keeps the exit PIN and on/off; exitPinError, parseScreenMode
│                                # - KioskConfigStore with JSON persistence
├── KioskManager.kt              # Device Owner API integration
│                                # - Device Owner detection and validation
│                                # - Lock Task Mode (no home/recents escape)
│                                # - setLockTaskPackages for allowlist
│                                # - System UI hiding (status bar, navigation bar)
│                                # - User restrictions (no factory reset, safe boot or USB
│                                #   file transfer): set when kiosk mode is turned on
│                                #   (pinned or not) and when pinned, cleared when it is
│                                #   turned off
│                                # - Kiosk state tracking via StateFlow
│                                # - Event log in memory (last 100), recordEvent();
│                                #   recordRestart() persists the restart count and time
│                                # - Configuration presets (APPLIANCE, INTERACTIVE)
├── ScreenController.kt          # Screen modes, applied by KioskActivityController
│                                # while pinned: DIM (brightness via WindowManager) and
│                                # BLACK (black overlay at dim brightness). OFF is left
│                                # to the phone's own screen timeout: its lockNow path
│                                # and the auto-off timer are not used
├── ButtonOverrideHandler.kt     # Physical button override
│                                # - Vol Up + Vol Down gesture detection
│                                # - Configurable hold duration (default 5s)
│                                # - Progress callbacks during gesture
│                                # - PinEntryHandler for secure exit
│                                # - 1-minute lockout after 5 failed PIN attempts
│                                # - Key event consumption to block volume changes
├── KioskActivityController.kt   # Applies kiosk mode to MainActivity: pins it (lock task)
│                                # while kiosk mode is on and the app is device owner,
│                                # unpins when it is turned off; Vol Up + Vol Down held
│                                # leaves kiosk mode (after the exit PIN, if set). Owns
│                                # FLAG_KEEP_SCREEN_ON and the screen mode; logs the exit
│                                # gesture and every PIN attempt
└── BootReceiver.kt              # Auto-start on boot (kiosk mode and autoStart on)
                                 # - ACTION_BOOT_COMPLETED, QUICKBOOT_POWERON
                                 # - Web server and thermal monitoring started at once
                                 # - After delaySeconds (0-120 s): MainActivity, then
                                 #   CameraService.autoStartIntent(rtsp, record)
                                 # - Camera skipped on Android 11+ without Device Owner
                                 # - Boot count in prefs; BOOT_COMPLETED and AUTO_START
                                 #   go into the in-memory kiosk event log

app/src/main/java/com/lensdaemon/web/
└── ApiRoutes.kt                 # Updated with kiosk API endpoints
                                 # - GET /api/kiosk/status - state and config summary
                                 # - POST /api/kiosk/enable - enable kiosk mode
                                 # - POST /api/kiosk/disable - disable kiosk mode
                                 # - GET /api/kiosk/config - full configuration
                                 # - PUT /api/kiosk/config - update configuration (400 for
                                 #   a bad exit PIN, screen mode or negative time)
                                 # - POST /api/kiosk/preset/appliance - 24/7 mode
                                 # - POST /api/kiosk/preset/interactive - preview mode
                                 # - GET /api/kiosk/events - event log
```

## Kiosk Mode Setup

```bash
# Set device owner (required for kiosk mode)
# Must be done on factory reset device or via ADB with no accounts
adb shell dpm set-device-owner com.lensdaemon/.AdminReceiver

# Verify device owner status
adb shell dumpsys device_policy | grep "Device Owner"

# Remove device owner: only if the app was a test-only build when
# set-device-owner ran (Android records the flag then)
adb shell dpm remove-active-admin com.lensdaemon/.AdminReceiver
```

`dpm remove-active-admin` refuses a non-test admin ("Attempt to remove non-test
admin"), and installing a test-only build later does not help. For an admin you
want to remove, install a test-only build before `set-device-owner`: Android
Studio's Run, or `./gradlew assembleCoreDebug -Pandroid.injected.testOnly=true`
installed with `adb install -t`. A normal install (`adb install`, the CI APK,
`./gradlew installCoreDebug`) stays device owner until a factory reset (turn
kiosk mode off first: it sets DISALLOW_FACTORY_RESET); the app never calls
`clearDeviceOwnerApp`. Don't put `android:testOnly` in the manifest: a plain
`adb install` would refuse the APK.

## Kiosk Mode Wiring

One `KioskManager` for the app (`LensDaemonApp.kioskManager`). The web API
(`/api/kiosk/enable|disable`, `PUT /api/kiosk/config`) changes the saved
`enabled` setting; `MainActivity`'s `KioskActivityController` follows it while
the activity is in the foreground, and also owns keep-screen-on and the screen
mode (DIM, BLACK; OFF is the phone's own screen timeout). `enabled` also gates
boot auto-start (`BootReceiver`). Presets change the settings but leave
`enabled` and the exit PIN as they were. The restrictions (no safe boot, factory
reset or USB file transfer) are set whenever `enabled` turns true (as device
owner, pinned or not) and when the phone is pinned, and cleared whenever
`enabled` turns false.

Crash recovery is Android's sticky restart: `CameraService.onStartCommand` gets
a null intent after the process died and, with kiosk mode, `autoStart` and
`crashRecovery.autoRestart` on and within `RestartBudget`, starts the auto-start
outputs with the saved stream defaults, starts `ThermalService`, reopens
`MainActivity` as device owner and calls `KioskManager.recordRestart()`.
Unattended starts (boot auto-start and this restart, `autoStartOutputs`) wait
out a thermal EMERGENCY (`ThermalHold` in CameraService.kt) and start when the
governor resumes, unless a user start or stop drops them first.
Network recovery is `UploadService` retrying failed uploads when the default
network comes back. Unattended starts need Device Owner (activity start from the
background, the camera on Android 11+) and a phone without a secure lock screen
(`BOOT_COMPLETED` arrives after the first unlock).

## Kiosk Mode Presets

Both presets keep the exit PIN and kiosk mode's on/off state, and are logged as
`PRESET_APPLIED`.

**APPLIANCE Preset** (24/7 unattended operation):
- Screen off through the phone's own screen timeout (keepScreenOn false)
- Starts the RTSP stream 10 s after boot
- Lock all system UI
- Restarts its outputs after a crash or kill (up to 10 times in 10 minutes)
- Failed uploads retried when the network returns

**INTERACTIVE Preset** (kiosk with preview):
- Screen shows the camera preview and stays on
- Starts the RTSP stream 3 s after boot; Start/Stop on the phone's screen
- Status bar hidden; Home and Recents blocked while pinned
- Vol+Vol gesture to exit enabled

## AI Director Module (Experimental)

The director lives in its own Gradle module, `:director` (plain Kotlin and
coroutines, unit-tested on the JVM); `CameraControllerAdapter` in `:app` connects
it to the camera. The `full` flavor enables it (`BuildConfig.ENABLE_DIRECTOR`);
in `core` builds `/api/director/*` answers 503, except the script file endpoints
(list, save, open, delete), which work without it (opening a script answers
`"loaded": false` with `loadError` "Director service not available"). Scripts
are parsed on the phone: no language model is involved. `RemoteLlmClient.kt`
(an Ollama-only client) and `TransitionAnimator.kt` are in the module but not
used: `REMOTE` inference is refused, and animated zooms go through
`CameraService.animateZoom`.

```
director/src/main/java/com/lensdaemon/director/
├── DirectorConfig.kt            # Configuration and data classes
│                                # - DirectorState enum (DISABLED, IDLE, PARSING, READY, RUNNING, PAUSED, THERMAL_HOLD)
│                                # - InferenceMode enum (OFF, PRE_PARSED; REMOTE is refused
│                                #   by the API and a saved REMOTE loads as PRE_PARSED)
│                                # - ShotType enum (ESTABLISHING, WIDE, FULL_SHOT, MEDIUM, etc.)
│                                # - TransitionType enum (CUT, PUSH_IN, PULL_BACK, RACK_FOCUS, HOLD)
│                                # - FocusTarget enum (AUTO, FACE, HANDS, OBJECT, BACKGROUND, MANUAL)
│                                # - ExposurePreset enum (AUTO, BRIGHT, DARK, BACKLIT, SILHOUETTE)
│                                # - CueType enum (SCENE, SHOT, TRANSITION, FOCUS, EXPOSURE, etc.)
│                                # - TakeQuality enum (UNMARKED, GOOD, BAD, CIRCLE, HOLD)
│                                # - DirectorCue, ShotPreset, DirectorScene data classes
│                                # - RecordedTake, TakeQualityFactors, ParsedScript
│                                # - DirectorSession, DirectorStatus (LlmConfig is unused)
│                                # - DirectorConfig with thermal thresholds
│                                # - DirectorConfigPersistence, DirectorConfigStore
├── ScriptParser.kt              # Script/scene cue parser
│                                # - Regex patterns for cue detection
│                                # - SCENE, SHOT, TRANSITION, FOCUS, EXPOSURE, BEAT/HOLD, TAKE, CUT TO patterns
│                                # - Natural language detection (wide shot, close-up, etc.)
│                                # - parseScript() for full script processing; problems
│                                #   (lines that aren't cues, unknown shot names, extra cues
│                                #   on a line) go into ParsedScript.errors
│                                # - parseCuesFromLine() for line-by-line parsing
│                                # - parseSingleCue() for ad-hoc cue execution
│                                # - validateScript() for script validation
├── ShotMapper.kt                # Shot-to-camera command mapping
│                                # - CameraCapabilities (lenses, zoom, face detection)
│                                # - CameraCommand (lens, zoom, focus, exposure, transition)
│                                # - MappingResult with warnings and fallbacks
│                                # - mapCue() for cue-to-command translation
│                                # - selectLensAndZoom() for optimal hardware selection
│                                # - Fallback handling when hardware unavailable
│                                # - validateMapping() for capability validation
│                                # - followCamera(Framing): TRANSITION, FOCUS and EXPOSURE
│                                #   cues start from the camera's lens and zoom
│                                # - forgetExposure(): preset back to AUTO, framing and
│                                #   focus target kept
├── TakeManager.kt               # Take recording and quality scoring
│                                # - ActiveTake with quality metric samples
│                                # - CueTiming for timing accuracy tracking
│                                # - Scores 0-10 (focus 30%, exposure 20%, motion 25%, cue
│                                #   timing 15% when a script cue ran, audio 10%); 5/6/8 are
│                                #   the suggested/good/excellent marks. Focus lock comes
│                                #   from the camera's AF state (CameraService.isFocusLocked);
│                                #   exposure and motion inputs are placeholders (motion a
│                                #   constant 0.7); audio isn't sampled. Top: ~9.25
│                                # - startTake(), endTake() lifecycle; currentTake StateFlow
│                                # - recordFocusSample(), recordExposureSample(), etc.
│                                #   (ignored outside a take or while paused)
│                                # - recordCueExecution() for timing accuracy (expected time
│                                #   from the run; null for cues run by hand)
│                                # - calculateQualityFactors(), calculateOverallScore()
│                                # - markTake() for manual quality marking
│                                # - compareTakes() with recommendations
│                                # - getAllBestTakes() for post-production (best take per
│                                #   scene scoring >= 5, marked suggested)
│                                # - SessionStats for session overview
└── DirectorManager.kt           # Main coordinator
                                 # - CameraController interface for hardware control
                                 #   (getFraming(): where the camera points, read before
                                 #   each TRANSITION, FOCUS and EXPOSURE cue)
                                 # - DirectorEvent sealed class for event emission
                                 # - enable(), disable() for inert state management
                                 # - loadScript() for script parsing
                                 # - startExecution(), pauseExecution(), stopExecution()
                                 # - executeCue(), executeCueText() for manual cues
                                 # - nextCue(), goToScene() (act at once while running)
                                 # - Stop and the end of a script rewind to scene 0; pause
                                 #   keeps the take and position
                                 # - Every [EXPOSURE] cue is sent (AUTO too); a preset
                                 #   other than AUTO goes with every command. Stop keeps
                                 #   the shot mapper; a run from the top forgets the
                                 #   preset (its first command sends AUTO in its place);
                                 #   load, clear and disable forget it and send nothing
                                 # - THERMAL_HOLD at thermalThresholdDisable: the run stops,
                                 #   the hold clears 5°C cooler without resuming
                                 # - Take management forwarding
                                 # - StateFlow for state observation
```

## AI Director API Endpoints

```
GET  /api/director/status        # Director state, current scene/cue
POST /api/director/enable        # Enable AI Director
POST /api/director/disable       # Disable (return to inert state)
GET  /api/director/config        # Get configuration
PUT  /api/director/config        # Partial update; REMOTE is refused (400)
POST /api/director/script        # Load script text (409 while running or paused)
POST /api/director/script/clear  # Unload the script (takes stay)
POST /api/director/start         # Start script execution (409 while too hot)
POST /api/director/stop          # Stop execution, back to the first scene
POST /api/director/pause         # Pause execution
POST /api/director/resume        # Resume execution
POST /api/director/cue           # Execute single cue
POST /api/director/advance       # Next cue (runs at once while running)
POST /api/director/scene         # Go to scene by index ({sceneIndex} or {index})
GET  /api/director/takes         # Get all recorded takes
POST /api/director/takes/mark    # Mark take quality ({quality} or {mark})
GET  /api/director/takes/best    # Get best takes per scene
GET  /api/director/takes/compare # Compare takes for current scene
GET  /api/director/session       # Get session info and stats
GET  /api/director/events        # SSE: unnamed data: frames with a "type"
```

## Script Cue Format

```
[SCENE: Scene Label]           # Scene marker for organization
[SHOT: WIDE|MEDIUM|CLOSE-UP]   # Shot type change
[TRANSITION: PUSH IN] - 2s     # Animated zoom with duration
[FOCUS: FACE|HANDS|BACKGROUND] # Focus target
[EXPOSURE: AUTO|BRIGHT|DARK]   # Exposure preset
[BEAT]                         # Timing marker (default hold)
[HOLD: 3]                      # Hold for 3 seconds
[TAKE: 1]                      # Start the next take (the number is not used)
[CUT TO: WIDE]                 # Hard cut to shot type
```

## AI Director Design Principles

- **Completely inert when disabled** - No background processing, zero thermal impact
- **Thermal-aware operation** - Stops in THERMAL_HOLD at thermalThresholdDisable
  (55°C by default) and can run again once the phone is 5°C cooler
- **Clean separation** - Director does not directly manipulate camera hardware
- **Observable state** - All state changes via StateFlow for reactive UIs
- **Quality-driven** - Automatic take scoring helps identify best footage

## AI Director Phase 2 Files (Camera Integration)

```
app/src/main/java/com/lensdaemon/director/
└── CameraControllerAdapter.kt   # Bridge between DirectorManager and CameraService
                                 # - Implements DirectorManager.CameraController interface
                                 # - switchLens(), setZoom(), setFocusMode(), setExposurePreset()
                                 # - getCurrentTemperature() for thermal monitoring
                                 # - getCapabilities(): lenses as listed at startup, max
                                 #   zoom from the main lens
                                 # - FACE = continuous video AF (no face priority yet);
                                 #   HANDS/OBJECT tap-to-focus; MANUAL = lockFocus (focus
                                 #   held in AUTO, no trigger); exposure presets in the
                                 #   lens's own steps, kept across lens changes
                                 # - State tracking for lens, zoom, focus; getFraming() is
                                 #   the lens family and zoom the camera has or is heading
                                 #   to; an accepted lens switch drops our zoom animation

director/src/main/java/com/lensdaemon/director/
└── QualityMetricsCollector.kt   # Real-time quality metrics collection
                                 # - MetricsSource interface (from camera)
                                 # - MetricsSink interface (to TakeManager)
                                 # - Collects focus lock (the camera's AF state),
                                 #   exposure, motion, audio
                                 # - Configurable sample interval (default 100ms)
                                 # - CollectorStats for session analytics
                                 # - asMetricsSource(), asMetricsSink() extensions

app/src/main/java/com/lensdaemon/camera/
└── CameraService.kt             # Updated with Director integration methods
                                 # - animateZoom(targetZoom, durationMs)
                                 # - setAutoFocus(), enableFaceDetectionFocus()
                                 # - setFocusDistance() for manual focus
                                 # - getMaxZoom(), supportsFaceDetection()
                                 # - supportsManualFocus(), isFocusLocked() (in focus:
                                 #   continuous AF reports focused, or AF that doesn't move
                                 #   the lens by itself), lockFocus() (focus hold)
                                 # - getNormalizedExposure(), getMotionShakiness()
                                 # - getCurrentCpuTemperature()

app/src/main/java/com/lensdaemon/web/
└── WebServerService.kt          # Updated with Director lifecycle management
                                 # - DirectorManager creation on camera connect
                                 # - CameraControllerAdapter setup
                                 # - QualityMetricsCollector initialization
                                 # - setupDirectorIntegration(), cleanupDirectorIntegration()
                                 # - getDirectorManager(), getQualityMetricsCollector()
                                 # - A take-watch job runs the QualityMetricsCollector while
                                 #   TakeManager.currentTake is set
```

## AI Director Phase 3 Files (Web Dashboard & UI)

```
app/src/main/assets/web/
├── index.html                   # Updated with AI Director section
│                                # - Toggle switch for enable/disable
│                                # - Director status display (state, scene, cue, take)
│                                # - Script textarea with cue format placeholder
│                                # - Load Script / Clear buttons
│                                # - Director controls (Start, Pause, Stop, Next Cue)
│                                # - Quick cue buttons (Wide, Medium, Close-up, Push In, Pull Back)
│                                # - Takes list with quality visualization
│                                # - Session stats (Total Takes, Avg Quality, Good Takes, Cue Success)
├── styles.css                   # Updated with Director styles
│                                # - Toggle switch styling
│                                # - Director panel disabled/enabled states
│                                # - State color classes (disabled, idle, running, paused, etc.)
│                                # - Script textarea styling
│                                # - Quick cue button grid
│                                # - Takes list with quality score badges (excellent, good, fair, poor)
│                                # - Director stats mini-dashboard
└── dashboard.js                 # Updated with Director functionality
                                 # - Director state management
                                 # - toggleDirector() with enable/disable API
                                 # - loadScript(), clearScript() handlers
                                 # - startDirector(), pauseDirector(), stopDirector()
                                 # - advanceDirector() for next cue
                                 # - executeQuickCue() for quick cue buttons
                                 # - fetchTakesList() and renderTakesList()
                                 # - updateDirectorStatus() from polling
                                 # - SSE event stream (startDirectorEventStream; unnamed
                                 #   data: frames, dispatched on their "type")
                                 # - Quality score color mapping (scores 0-10)

app/src/main/java/com/lensdaemon/web/
├── ApiRoutes.kt                 # Updated with new director endpoints
│                                # - Director status in main /api/status response
│                                # - GET /api/director/events - SSE endpoint, served by
│                                #   handlers/DirectorEventStream (state frame first, 15 s
│                                #   keepalive comment, no CORS header, never gzipped)
│                                # - POST /api/director/script/clear - clear loaded script
└── WebServerService.kt          # Already integrated from Phase 2

director/src/main/java/com/lensdaemon/director/
└── DirectorManager.kt           # Updated with new methods
                                 # - getSessionDuration(): seconds since the script loaded
                                 # - clearScript() unloads the script; recorded takes stay
```

## Director Dashboard Features

The web dashboard now includes a comprehensive AI Director control panel:

**Status Display:**
- Director state (DISABLED, IDLE, READY, RUNNING, PAUSED, THERMAL_HOLD)
- Current scene label
- Current cue being executed
- Take number

**Script Management:**
- Textarea with placeholder showing cue format examples
- Load Script button to parse and validate
- Clear button to reset

**Playback Controls:**
- Start/Resume - Begin script execution (refused while the phone is too hot)
- Pause - Temporarily halt execution; the take stays open
- Stop - End execution; back to the first scene
- Next Cue - Advance to the next cue (it runs at once while running)
- Go to scene - Scene picker, with a scene and cue progress line

**Quick Cues:**
- One-click buttons for common camera commands
- Wide, Medium, Close-up shot presets
- Push In, Pull Back transitions

**Takes List:**
- Live list of recorded takes
- Quality score with color-coded badge
- Scene and duration info
- Manual mark indicators

**Session Stats:**
- Total takes recorded
- Average quality score (0-10)
- Good takes count (scoring 6 or more, the director's "good")
- Cue success rate

## AI Director Script Files and Take Links

The director runs inside `WebServerService` (there is no separate director
service). Script files and take links are API features:

```
director/src/main/java/com/lensdaemon/director/
└── TakeManager.kt               # Updated with new methods
                                 # - linkTakeToFile() for recording association (false
                                 #   for an unknown take: the API answers 404)
                                 # - getTakesForScene() for scene filtering
                                 # - getTakesWithFiles() for file-linked takes
                                 # - getCurrentTakeNumber()

app/src/main/java/com/lensdaemon/web/handlers/
└── DirectorApiHandler.kt        # Script file and take endpoints
                                 # - GET /api/director/scripts - list saved scripts
                                 # - POST /api/director/scripts/save - save script file
                                 # - GET /api/director/scripts/{fileName} - open and load
                                 #   (loaded: false and loadError when refused)
                                 # - DELETE /api/director/scripts/{fileName} - delete script
                                 # - GET /api/director/scripts/export - current script (JSON)
                                 # - POST /api/director/scripts/import - import script
                                 # - POST /api/director/takes/link - link take to file
                                 # - GET /api/director/takes/markers - take start/end markers
```

## Script File Management API

```
GET  /api/director/scripts              # List saved script files
POST /api/director/scripts/save         # Save script to file
     body: { "fileName": "scene1.txt", "script": "[SCENE: ...]" }
GET  /api/director/scripts/{fileName}   # Load and parse script file
DELETE /api/director/scripts/{fileName} # Delete script file
GET  /api/director/scripts/export       # Current script: {script, scenes, totalCues}
POST /api/director/scripts/import       # Import script text (optionally save)
     body: { "script": "...", "fileName": "...", "save": true }
```

## Take-Recording Integration API

```
POST /api/director/takes/link    # Link take to recording file
     body: { "takeNumber": 1, "filePath": "/path/to/video.mp4" }
GET  /api/director/takes/markers # Get take markers for recording metadata
```

## Take Markers

`GET /api/director/takes/markers` builds markers from the recorded takes: a
`TAKE_START` for each take and a `TAKE_END` once it has ended (there are no cue
or scene-change markers):

```json
[
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
    "qualityScore": 8.5,
    "durationMs": 30000
  }
]
```

## Director Dashboard: Script Files and Scenes

```
app/src/main/assets/web/
├── index.html                   # Script file browser (saved scripts list, save as)
│                                # Scene progress line and "Go to scene" picker
├── styles.css                   # Script file browser styling (active file, delete)
└── dashboard.js                 # Script file management (fetch, render, load, save,
                                 # delete), scene picker (POST /api/director/scene),
                                 # file size/date formatting, HTML escaping
```

### Script File Browser
- List of saved script files with name, size, and date
- Click to open a script from saved files (it loads unless the director refuses)
- Save current script with custom filename
- Delete saved scripts with confirmation
- Active file highlighting

## Post-Evaluation Remediation

A comprehensive 5-phase remediation was performed after code evaluation,
followed by a second review round:

### Phase 1: Critical Security & Build (commit e49a645)
- Bearer token check for the API (and later the MJPEG preview), but no token can
  be set from the UI or the API yet, so it is off; cross-site writes are refused
  (CrossSiteGuard, added in the second review)
- Path traversal protection (sanitizeFileName, validateFileInDirectory)
- LLM endpoint validation against SSRF (the client now accepts only a localhost
  Ollama endpoint, and is not wired in)
- Credential safety: encrypted storage via Android Keystore
- Build fixes for Gradle/Kotlin compatibility

### Phase 2: Testing Infrastructure (commit ba03d21)
- Detekt static analysis integration with strict config (maxIssues: 0)
- Security test suite (ApiRoutesSecurityTest) for path traversal, auth, input validation
- Detekt baseline and custom rules

### Phase 3: Architecture Refactoring (commit 018bd0b)
- Split 2917-line ApiRoutes.kt into thin dispatcher + 5 per-module handlers
- Shared ApiHandlerUtils for sanitization and response helpers
- BatteryBypass software-only fallback with recommendation engine
- RecordingCoordinator and RtspCoordinator extracted from CameraService
- Fixed broken doc links, added API.md, DEVICES.md, CONTRIBUTING.md

### Phase 4: CI/CD & Polish (commit bcc4ba9)
- GitHub Actions CI/CD workflow (build, test, detekt, lint, APK artifact)
- Token bucket rate limiter with per-client IP tracking
- Device-agnostic thermal zone discovery (replaces hardcoded thermal_zone0)
- Magic number extraction to named constants
- IMPLEMENTATION_GUIDE.md fully checked off

### Phase 5: MPEG-TS/UDP Streaming
- MPEG-TS/UDP publisher with caller/listener modes
- MPEG-TS integration into CameraService and REST API

### Second review (round 2)
- Thermal: hysteresis, throttling worked out from CPU and battery together, the
  device profile applied at start, resolution/frame-rate limits applied by an
  encoder restart, a pause that restarts the outputs after cooling, battery
  endpoints that act (or answer 409), history that follows the governor
- Recording and storage: a paused recording counts as open, a second start is
  harmless, the chosen file length applies, the open file is never deleted or
  uploaded, list/status/clean-up work without a recording, storage-full stops
  through the camera
- Uploads: one loop, failed uploads kept and retried (also when the network
  returns), enqueue limited to recordings, S3 signing and SMB folders fixed
- Kiosk: screen modes applied while pinned, wake and Wi-Fi locks while encoding,
  sticky restart after a crash, saved stream defaults for boot and restarts,
  boot honours its flags, presets keep the exit PIN, PIN validated, restrictions
  follow kiosk mode
- Web: cross-site writes refused, /mjpeg behind the rate limit and token rule,
  the rate limit checked before the token, MJPEG never blocks the camera thread,
  SSE no longer gzipped, inputs validated (tap-to-focus refused with 409 where
  it can't apply), JSON built with JSONObject
- Director: live events reach the browser, real 0-10 take scores, thermal hold
  that stays until cool, run control that acts at once, script errors reported,
  partial config updates (REMOTE refused); DirectorService removed

## MPEG-TS/UDP Publisher Architecture

Note: Despite some naming references to "SRT", this is plain MPEG-TS over UDP
without SRT encryption, ARQ, or congestion control.

```
app/src/main/java/com/lensdaemon/output/
└── MpegTsUdpPublisher.kt       # MPEG-TS over UDP publisher
                                 # - MpegTsUdpConfig (port, mode, target, latency)
                                 # - MpegTsMode enum (CALLER, LISTENER)
                                 # - MpegTsUdpStats with StateFlow
                                 # - MPEG-TS packetization (PAT, PMT, PES)
                                 # - H.264/H.265 stream type support
                                 # - Per-PID continuity counters
                                 # - UDP datagram batching (7 TS packets)
                                 # - Access unit delimiter on every picture
```

## Streaming Pipeline (shared encoder)

One encoder feeds every output. `CameraService` starts it for the first
output (RTSP, MPEG-TS or recording) and shares it with the rest; stopping an
output stops the encoder only when nothing else uses it. `startStreaming(config)`
holds the encoder on its own and restarts it if the settings change (outputs
kept, but a codec change disconnects the RTSP viewers, whose SDP no longer
matches; a restart that fails stops every output);
`stopStreaming()` stops every output and the encoder. The phone's Start Stream
button starts RTSP; kiosk boot auto-start starts RTSP and/or a recording, each
on its own, as `autoStart.startRtsp` and `startRecording` say.

- Stream defaults (`camera/StreamDefaults.kt`, prefs `lensdaemon_stream`): codec,
  size, frame rate, bitrate, RTSP port and file length. Each successful start
  saves its requested settings; `PUT /api/config {"defaults": ...}` sets them;
  the phone's Start Stream, kiosk auto-start (`CameraService.autoStartIntent`)
  and the sticky restart use them, falling back once to 1080p30 on port 8554.
  API start requests take any setting they leave out (and the RTSP port and
  file length) from them. `StreamDefaults.BITRATE_RANGE` (100 kbps-50 Mbps) is
  the one bitrate range for the defaults, PUT /api/config and
  /api/encoder/capabilities.
- Thermal (`pauseForThermal`, `resumeAfterThermal`, `setThermalEncoderLimits`,
  `setThermalBitrateReduction`): called by WebServerService on one background
  thread. The bitrate cut is state, applied to every encoder that starts; the
  CRITICAL limits (`camera/ThermalEncoderLimits.kt`) restart the encoder with
  the outputs kept; the EMERGENCY pause records what ran and restarts exactly
  that after cooling, unless a user start or stop came in between. Until the
  resume, unattended starts (kiosk boot auto-start, sticky restart) wait too
  (`ThermalHold`), also when nothing ran to stop. The resume's starts leave the
  saved stream defaults alone (`keepSavedDefaults`). `/api/status` reports
  `camera.thermalPaused` (a pause or a waiting auto-start); the phone shows
  "Paused: the phone is too hot; outputs restart when it cools", hides Start
  and keeps Stop as "Stop all", which cancels the restart (`StreamControls.kt`).
- `setEncoderBitrate` (PUT /api/config) makes a bitrate the configured one,
  kept across encoder restarts.
- A partial wake lock and a Wi-Fi lock are held while the encoder runs (and the
  wake lock through a thermal pause).
- A runtime encoder error (or a CRITICAL restart that did not start) rebuilds
  the encoder after 1, 2, 5, 10 and 30 s, keeping the outputs (a cut-off
  recording continues in a new file); if no rebuild works, every output stops.
  docs/API.md "When the encoder fails" has what the API shows meanwhile.
- After a crash or kill, Android's sticky restart reaches `onStartCommand` with a
  null intent; within `camera/RestartBudget.kt` the kiosk auto-start outputs come
  back (see Kiosk Mode Wiring).

```
app/src/main/java/com/lensdaemon/output/
├── KeyframeAligner.kt           # Per-receiver stream start: holds pictures until a
│                                # keyframe, folds codec-config buffers into an
│                                # SPS/PPS/VPS cache, prefixes bare keyframes with it
│                                # (used by RtspSession and MpegTsUdpPublisher)
└── LocalNetwork.kt              # LAN IPv4 for stream URLs (WiFi/hotspot/USB over cellular)

app/src/main/java/com/lensdaemon/camera/
└── FrameRatePolicy.kt           # CONTROL_AE_TARGET_FPS_RANGE choice: fixed range at
                                 # the encoder frame rate so low light never drops fps
```

- Audio: `encoder/AudioEncoder.kt` captures the microphone (`MicrophonePcmSource`,
  camcorder source, 48 kHz, stereo or mono) and encodes AAC-LC on its own thread,
  stamped on the camera's clock (`MediaClock`, from SENSOR_INFO_TIMESTAMP_SOURCE).
  It starts and stops with the video encoder and feeds a second `FrameDistributor`:
  RTSP offers it as `trackID=1` (`AacRtpPacketizer`, RFC 3640) with RTCP sender
  reports per track (`RtcpSenderReport`, `RtspTrack`); MPEG-TS sends ADTS on PID
  257; recordings add an AAC track to each segment. `/api/audio` switches it.
- RTSP PLAY and new MPEG-TS receivers request a keyframe from the encoder.
- Lenses are typed the way phones label them (`camera/LensSelection.kt`): the
  ~24mm main camera is MAIN. "wide"/"main"/"tele" requests (phone buttons,
  `/api/lens/{name}`, the director) take the nearest lens of that family.
- `ThermalService` monitors as soon as it exists, so the web server's binding is
  enough; `MainActivity` and `BootReceiver` also start it, for its notification.
  Thermal bitrate throttling is a share of the configured bitrate.
- The phone shows the dashboard address and temperatures (`PhoneOverlays`).
- The capture session runs with or without the on-screen preview surface
  (`LensDaemonCameraManager.setPreviewSurface`), so streaming survives the
  screen turning off or the app going to the background.

### MPEG-TS/UDP API Endpoints

```
POST /api/mpegts/start  # Start MPEG-TS/UDP streaming
     body: { "port": 9000, "mode": "listener|caller",
             "targetHost": "...", "targetPort": 9000,
             "width": 1920, "height": 1080, "bitrate": 4000000 }
POST /api/mpegts/stop   # Stop MPEG-TS/UDP streaming
GET  /api/mpegts/status # MPEG-TS/UDP publisher statistics
```

## Web API Route Handlers

```
app/src/main/java/com/lensdaemon/web/
├── ApiRoutes.kt                 # Thin dispatcher: body, cross-site check, then admit
│                                # (rate limit, then token; also used for /mjpeg), routing
├── CrossSiteGuard.kt            # 403 for POST/PUT/DELETE whose Origin isn't this Host
├── ApiInputs.kt                 # Strict number parsing (finite, whole); PUT /api/config
│                                # checks (ConfigChange, stream defaults merge)
├── RequestBody.kt               # Reads POST/PUT JSON bodies itself: NanoHTTPD's parseBody
│                                # hands PUT bodies over as a temp file and decodes POST
│                                # bodies as US-ASCII; reads exactly Content-Length bytes
├── StatusReport.kt              # Sections of GET /api/status (camera incl. maxZoom and
│                                # exposure range/step, recording incl. error and
│                                # lastError, thermal, director progress)
├── RateLimiter.kt               # Token bucket per connection address (X-Forwarded-For
│                                # ignored), at most 1024 clients tracked
├── WebServer.kt                 # NanoHTTPD HTTP server (no gzip for text/event-stream)
├── MjpegStreamer.kt             # MJPEG live preview (per-viewer slot, 8 viewers max;
│                                # an idle viewer makes room at the cap)
├── WebServerService.kt          # Foreground service
└── handlers/
    ├── ApiHandlerUtils.kt       # Shared: sanitize, validate, response helpers
    ├── StreamApiHandler.kt      # /api/stream/*, /api/rtsp/*, /api/mpegts/*, /api/audio,
    │                            # /api/recording/*, /api/recordings, /api/storage/*
    ├── RecordingRequests.kt     # Recording names and paths from requests: only files
    │                            # the recordings list holds; Content-Disposition
    ├── UploadApiHandler.kt      # /api/upload/* (S3, SMB)
    ├── ThermalApiHandler.kt     # /api/thermal/*
    ├── KioskApiHandler.kt       # /api/kiosk/*
    ├── DirectorApiHandler.kt    # /api/director/*
    └── DirectorEventStream.kt   # /api/director/events body: queue-backed SSE stream
```

## CI/CD

GitHub Actions workflow at `.github/workflows/ci.yml`:
- Triggers on push to main, develop and claude/** branches, PRs to main, and by hand
- JDK 17, Android SDK, Gradle 8.4 via wrapper
- Steps: build → test → detekt → lint → assembleCoreDebug and assembleFullDebug
- Artifacts: debug APKs, test results, lint report, detekt report
- A second job runs the emulator tests (app/src/androidTest) on an API 29
  emulator: connectedFullDebugAndroidTest
