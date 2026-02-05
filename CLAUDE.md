# CLAUDE.md

This file provides guidance for Claude Code when working with this repository.

## Project Overview

LensDaemon is an Android application that transforms smartphones into dedicated video streaming appliances (streaming cameras, security monitors, or recording endpoints). It leverages the superior imaging hardware in modern phones while avoiding the thermal and battery issues of running a full Android OS.

**Status:** AI Director Phase 2 complete - Camera integration, remote LLM support, transition animations, and quality metrics collection.

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
8. **Director Manager** - AI-powered script-driven camera automation

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
│                                # - MJPEG stream endpoint (/mjpeg, /stream.mjpeg)
│                                # - MIME type detection
│                                # - Server statistics tracking
├── ApiRoutes.kt                 # REST API endpoint handlers
│                                # - GET /api/status - device info, temps, streaming state
│                                # - POST /api/stream/start|stop - encoding control
│                                # - POST /api/rtsp/start|stop - RTSP server control
│                                # - POST /api/lens/{wide|main|tele} - lens switching
│                                # - POST /api/zoom - set zoom level
│                                # - POST /api/focus - tap-to-focus coordinates
│                                # - POST /api/exposure - exposure compensation
│                                # - POST /api/snapshot - capture JPEG
│                                # - GET/PUT /api/config - encoder configuration
├── MjpegStreamer.kt             # MJPEG live preview streaming
│                                # - Motion JPEG multipart response
│                                # - Multi-client support with ConcurrentHashMap
│                                # - Frame rate limiting (configurable max FPS)
│                                # - JpegFrameConverter for YUV/Bitmap conversion
│                                # - Client connection/disconnection handling
└── WebServerService.kt          # Foreground web server service
                                 # - Service binding for activity integration
                                 # - CameraService binding for API access
                                 # - Notification management
                                 # - MJPEG frame pushing API

app/src/main/assets/web/
├── index.html                   # Dashboard HTML
│                                # - Live preview section with MJPEG display
│                                # - Stream and RTSP control buttons
│                                # - Lens selection (wide/main/tele)
│                                # - Zoom and exposure sliders
│                                # - Encoder settings panel
│                                # - Real-time statistics display
├── styles.css                   # Dashboard styles
│                                # - Dark theme with CSS variables
│                                # - Responsive grid layout
│                                # - Control panel styling
│                                # - Button states and animations
└── dashboard.js                 # Dashboard JavaScript
                                 # - API client functions
                                 # - Status polling (1-second interval)
                                 # - MJPEG preview start/stop
                                 # - Stream and RTSP control
                                 # - Lens and camera control handlers
                                 # - Configuration management
```

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
                                 # - Filename pattern: LensDaemon_{device}_{timestamp}.mp4
                                 # - Recording pause/resume support
                                 # - FileWriterFactory for common configs

app/src/main/java/com/lensdaemon/storage/
├── RetentionPolicy.kt           # Storage retention and cleanup
│                                # - RetentionType (KEEP_ALL, MAX_AGE, MAX_SIZE, MAX_AGE_AND_SIZE)
│                                # - RetentionConfig presets (24h, 7d, 30d, 5GB, 10GB)
│                                # - StorageStats with file enumeration
│                                # - Preview and enforce methods
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
                                 # - Automatic retention enforcement
                                 # - Combined streaming and recording
                                 # - StorageManagerBuilder for configuration

app/src/main/java/com/lensdaemon/camera/
└── CameraService.kt             # Updated with recording integration
                                 # - initializeRecording, startRecording, stopRecording
                                 # - pauseRecording, resumeRecording
                                 # - getRecordingStats, getStorageStatus
                                 # - listRecordings, deleteRecording
                                 # - startStreamingAndRecording convenience method

app/src/main/java/com/lensdaemon/web/
└── ApiRoutes.kt                 # Updated with recording API endpoints
                                 # - POST /api/recording/start|stop|pause|resume
                                 # - GET /api/recording/status
                                 # - GET /api/recordings, DELETE /api/recordings/{filename}
                                 # - GET /api/storage/status
                                 # - POST /api/storage/cleanup
```

## Phase 8 Files (Network Storage - S3 & SMB)

```
app/src/main/java/com/lensdaemon/storage/
├── CredentialStore.kt           # Encrypted credential storage
│                                # - S3Credentials with factory methods for AWS, B2, MinIO, R2
│                                # - SmbCredentials for SMB/CIFS shares
│                                # - StorageBackend enum (S3, BACKBLAZE_B2, MINIO, CLOUDFLARE_R2)
│                                # - Android Keystore for AES-256-GCM encryption
│                                # - Secure storage/retrieval/deletion
├── UploadQueue.kt               # Persistent upload queue management
│                                # - UploadTask, UploadStatus, UploadDestination
│                                # - UploadQueueStats with progress tracking
│                                # - UploadEventListener for callbacks
│                                # - JSON persistence for app restart recovery
│                                # - Exponential backoff retry (3 retries, 5s/15s/45s)
│                                # - Concurrent upload limit (default: 2)
├── S3Client.kt                  # S3-compatible storage client
│                                # - AWS Signature V4 authentication
│                                # - Multipart upload for files >5MB
│                                # - Progress callback support
│                                # - testConnection, uploadFile, deleteObject
│                                # - S3UploadResult with ETag and version
│                                # - Supports AWS S3, Backblaze B2, MinIO, Cloudflare R2
├── SmbClient.kt                 # SMB/CIFS network share client
│                                # - SMB2 protocol implementation
│                                # - NTLM authentication support
│                                # - SmbUploadResult with metadata
│                                # - uploadFile, testConnection, createDirectory
│                                # - deleteFile, listFiles operations
│                                # - Progress callback with chunked writes
└── UploadService.kt             # Foreground upload service
                                 # - Service binding with UploadBinder
                                 # - S3 and SMB client coordination
                                 # - Foreground notification with progress
                                 # - startUploads, stopUploads, enqueueFile
                                 # - configureS3, configureSmb with validation
                                 # - testS3Connection, testSmbConnection
                                 # - Credential management APIs

app/src/main/java/com/lensdaemon/web/
├── ApiRoutes.kt                 # Updated with upload API endpoints
│                                # - GET /api/upload/status - upload service state
│                                # - GET /api/upload/queue - pending/completed tasks
│                                # - POST /api/upload/start|stop - control processing
│                                # - POST /api/upload/enqueue - enqueue file
│                                # - DELETE /api/upload/task/{id} - cancel task
│                                # - POST /api/upload/retry - retry failed
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
│                                # - Time-in-state tracking
│                                # - JSON persistence for app restart recovery
│                                # - Graph data downsampling for dashboard
├── ThermalGovernor.kt           # Automatic throttling controller
│                                # - CPU > 45°C → Reduce bitrate 20%
│                                # - CPU > 50°C → Reduce resolution
│                                # - CPU > 55°C → Reduce framerate
│                                # - CPU > 60°C → Pause streaming
│                                # - Battery > 42°C → Disable charging
│                                # - Hysteresis to prevent oscillation
│                                # - Callbacks for encoder/camera integration
├── BatteryBypass.kt             # Battery charge limiting
│                                # - Target charge level holding (e.g., 50%)
│                                # - Resume charging below threshold
│                                # - Thermal-triggered charge disable
│                                # - Sysfs-based control (root required)
│                                # - Software-only fallback mode
│                                # - BypassState tracking
└── ThermalService.kt            # Foreground thermal service
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
│                                # - POST /api/thermal/battery/disable|enable
└── WebServerService.kt          # Updated with ThermalService binding
                                 # - ThermalService connection management
                                 # - thermalGovernor reference for ApiRoutes
```

## Phase 10 Files (Kiosk Mode & Reliability)

```
app/src/main/java/com/lensdaemon/kiosk/
├── KioskConfig.kt               # Kiosk configuration data classes
│                                # - KioskState enum (DISABLED, ENABLED, SETUP_REQUIRED, NOT_DEVICE_OWNER)
│                                # - ScreenMode enum (PREVIEW, OFF, DIM, BLACK)
│                                # - AutoStartConfig (delay, start streaming/RTSP/recording)
│                                # - ScreenConfig (mode, brightness, auto-off timeout)
│                                # - SecurityConfig (exit PIN, gesture, status bar lock)
│                                # - NetworkRecoveryConfig (auto-reconnect, retry attempts)
│                                # - CrashRecoveryConfig (auto-restart, max attempts)
│                                # - KioskConfig presets (APPLIANCE, INTERACTIVE)
│                                # - KioskConfigStore with JSON persistence
├── KioskManager.kt              # Device Owner API integration
│                                # - Device Owner detection and validation
│                                # - Lock Task Mode (no home/recents escape)
│                                # - setLockTaskPackages for allowlist
│                                # - System UI hiding (status bar, navigation bar)
│                                # - User restrictions (factory reset, safe boot, USB transfer)
│                                # - Kiosk state tracking via StateFlow
│                                # - Event logging for kiosk actions
│                                # - Configuration presets (APPLIANCE, INTERACTIVE)
├── ScreenController.kt          # Screen management
│                                # - Screen modes: PREVIEW, OFF, DIM, BLACK
│                                # - Brightness control via WindowManager
│                                # - Black overlay for display-on-but-black mode
│                                # - Screen lock via DevicePolicyManager
│                                # - Auto-off timeout with activity detection
│                                # - Wake lock management
│                                # - Screen state tracking
├── ButtonOverrideHandler.kt     # Physical button override
│                                # - Vol Up + Vol Down gesture detection
│                                # - Configurable hold duration (default 5s)
│                                # - Progress callbacks during gesture
│                                # - PinEntryHandler for secure exit
│                                # - Lockout after 5 failed PIN attempts
│                                # - Key event consumption to block volume changes
└── BootReceiver.kt              # Auto-start on boot
                                 # - ACTION_BOOT_COMPLETED handling
                                 # - QUICKBOOT_POWERON for some devices
                                 # - Configurable startup delay
                                 # - Auto-start streaming/RTSP/recording
                                 # - CrashRecoveryManager integration
                                 # - Uncaught exception handler
                                 # - NetworkRecoveryHandler for reconnection
                                 # - Boot event logging

app/src/main/java/com/lensdaemon/web/
└── ApiRoutes.kt                 # Updated with kiosk API endpoints
                                 # - GET /api/kiosk/status - state and config summary
                                 # - POST /api/kiosk/enable - enable kiosk mode
                                 # - POST /api/kiosk/disable - disable kiosk mode
                                 # - GET /api/kiosk/config - full configuration
                                 # - PUT /api/kiosk/config - update configuration
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

# Remove device owner (to allow normal device use)
adb shell dpm remove-active-admin com.lensdaemon/.AdminReceiver
```

## Kiosk Mode Presets

**APPLIANCE Preset** (24/7 unattended operation):
- Screen OFF to save power
- Auto-start streaming and RTSP on boot
- Lock all system UI
- Auto-restart on crash (up to 10 attempts)
- Network auto-reconnect enabled

**INTERACTIVE Preset** (kiosk with preview):
- Screen shows camera preview
- Keep screen always on
- Navigation bar accessible
- Vol+Vol gesture to exit enabled

## AI Director Module (Experimental)

```
app/src/main/java/com/lensdaemon/director/
├── DirectorConfig.kt            # Configuration and data classes
│                                # - DirectorState enum (DISABLED, IDLE, PARSING, READY, RUNNING, PAUSED, THERMAL_HOLD)
│                                # - InferenceMode enum (OFF, PRE_PARSED, REMOTE)
│                                # - ShotType enum (ESTABLISHING, WIDE, FULL_SHOT, MEDIUM, etc.)
│                                # - TransitionType enum (CUT, PUSH_IN, PULL_BACK, RACK_FOCUS, HOLD)
│                                # - FocusTarget enum (AUTO, FACE, HANDS, OBJECT, BACKGROUND, MANUAL)
│                                # - ExposurePreset enum (AUTO, BRIGHT, DARK, BACKLIT, SILHOUETTE)
│                                # - CueType enum (SCENE, SHOT, TRANSITION, FOCUS, EXPOSURE, etc.)
│                                # - TakeQuality enum (UNMARKED, GOOD, BAD, CIRCLE, HOLD)
│                                # - DirectorCue, ShotPreset, DirectorScene data classes
│                                # - RecordedTake, TakeQualityFactors, ParsedScript
│                                # - DirectorSession, DirectorStatus, LlmConfig
│                                # - DirectorConfig with thermal thresholds
│                                # - DirectorConfigStore for persistence
├── ScriptParser.kt              # Script/scene cue parser
│                                # - Regex patterns for cue detection
│                                # - SCENE, SHOT, TRANSITION, FOCUS, EXPOSURE, DOF, BEAT, TAKE, CUT patterns
│                                # - Natural language detection (wide shot, close-up, etc.)
│                                # - parseScript() for full script processing
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
├── TakeManager.kt               # Take recording and quality scoring
│                                # - ActiveTake with quality metric samples
│                                # - CueTiming for timing accuracy tracking
│                                # - Quality scoring weights (focus 30%, exposure 20%, etc.)
│                                # - startTake(), endTake() lifecycle
│                                # - recordFocusSample(), recordExposureSample(), etc.
│                                # - recordCueExecution() for timing accuracy
│                                # - calculateQualityFactors(), calculateOverallScore()
│                                # - markTake() for manual quality marking
│                                # - compareTakes() with recommendations
│                                # - getAllBestTakes() for post-production
│                                # - SessionStats for session overview
└── DirectorManager.kt           # Main coordinator
                                 # - CameraController interface for hardware control
                                 # - DirectorEvent sealed class for event emission
                                 # - enable(), disable() for inert state management
                                 # - loadScript() for script parsing
                                 # - startExecution(), pauseExecution(), stopExecution()
                                 # - executeCue(), executeCueText() for manual cues
                                 # - advanceCue(), advanceScene(), jumpToScene()
                                 # - Thermal monitoring integration
                                 # - Take management forwarding
                                 # - StateFlow for state observation
```

## AI Director API Endpoints

```
GET  /api/director/status        # Director state, current scene/cue
POST /api/director/enable        # Enable AI Director
POST /api/director/disable       # Disable (return to inert state)
GET  /api/director/config        # Get configuration
PUT  /api/director/config        # Update configuration
POST /api/director/script        # Load script text
POST /api/director/start         # Start script execution
POST /api/director/stop          # Stop execution
POST /api/director/pause         # Pause execution
POST /api/director/resume        # Resume execution
POST /api/director/cue           # Execute single cue
POST /api/director/advance       # Advance to next cue
POST /api/director/scene         # Jump to scene by index
GET  /api/director/takes         # Get all recorded takes
POST /api/director/takes/mark    # Mark take quality
GET  /api/director/takes/best    # Get best takes per scene
GET  /api/director/takes/compare # Compare takes for current scene
GET  /api/director/session       # Get session info and stats
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
[TAKE: 1]                      # Take boundary marker
[CUT TO: WIDE]                 # Hard cut to shot type
```

## AI Director Design Principles

- **Completely inert when disabled** - No background processing, zero thermal impact
- **Thermal-aware operation** - Auto-disables at configurable temperature thresholds
- **Clean separation** - Director does not directly manipulate camera hardware
- **Observable state** - All state changes via StateFlow for reactive UIs
- **Quality-driven** - Automatic take scoring helps identify best footage

## AI Director Phase 2 Files (Camera Integration)

```
app/src/main/java/com/lensdaemon/director/
├── CameraControllerAdapter.kt   # Bridge between DirectorManager and CameraService
│                                # - Implements DirectorManager.CameraController interface
│                                # - switchLens(), setZoom(), setFocusMode(), setExposurePreset()
│                                # - getCurrentTemperature() for thermal monitoring
│                                # - getCameraCapabilities() for shot mapper
│                                # - State tracking for lens, zoom, focus
├── RemoteLlmClient.kt           # External LLM API integration
│                                # - Supports OpenAI, Anthropic (Claude), Ollama
│                                # - Auto-detects provider from endpoint URL
│                                # - LlmProvider enum (OPENAI, ANTHROPIC, OLLAMA, GENERIC_OPENAI)
│                                # - interpretScript(), interpretLine() for cue generation
│                                # - testConnection() for validation
│                                # - Configurable timeout and token limits
├── TransitionAnimator.kt        # Smooth camera transition animations
│                                # - animateZoom() with easing curves
│                                # - animateFocusDistance() for rack focus
│                                # - animateExposure() for exposure changes
│                                # - pushIn(), pullBack() convenience methods
│                                # - hold() for timed pauses
│                                # - TransitionCallback interface for camera control
│                                # - pause(), resume(), cancelAll() controls
└── QualityMetricsCollector.kt   # Real-time quality metrics collection
                                 # - MetricsSource interface (from camera)
                                 # - MetricsSink interface (to TakeManager)
                                 # - Collects focus lock, exposure, motion, audio
                                 # - Configurable sample interval (default 100ms)
                                 # - CollectorStats for session analytics
                                 # - asMetricsSource(), asMetricsSink() extensions

app/src/main/java/com/lensdaemon/camera/
└── CameraService.kt             # Updated with Director integration methods
                                 # - animateZoom(targetZoom, durationMs)
                                 # - setAutoFocus(), enableFaceDetectionFocus()
                                 # - setFocusDistance() for manual focus
                                 # - getMaxZoom(), supportsFaceDetection()
                                 # - supportsManualFocus(), isFocusLocked()
                                 # - getNormalizedExposure(), getMotionShakiness()
                                 # - getCurrentCpuTemperature()

app/src/main/java/com/lensdaemon/web/
└── WebServerService.kt          # Updated with Director lifecycle management
                                 # - DirectorManager creation on camera connect
                                 # - CameraControllerAdapter setup
                                 # - QualityMetricsCollector initialization
                                 # - setupDirectorIntegration(), cleanupDirectorIntegration()
                                 # - getDirectorManager(), getQualityMetricsCollector()
                                 # - startQualityMetricsCollection(), stopQualityMetricsCollection()
```

## Remote LLM Configuration

The AI Director supports external LLM APIs for dynamic script interpretation:

```json
{
  "llmConfig": {
    "endpoint": "https://api.openai.com",
    "apiKey": "sk-...",
    "model": "gpt-4",
    "maxTokens": 1000,
    "temperature": 0.3
  }
}
```

Supported providers:
- **OpenAI**: `api.openai.com` - GPT-4, GPT-3.5
- **Anthropic**: `api.anthropic.com` - Claude models
- **Ollama**: `localhost:11434` - Local LLM (no API key needed)
- **Generic**: Any OpenAI-compatible endpoint
