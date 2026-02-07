# LensDaemon Refocus Plan

Actionable remediation plan derived from the [Concept-Execution Evaluation](EVALUATION.md). Six phases, ordered by severity and dependency. Each phase is self-contained and shippable.

---

## Phase 1: Critical Bug Fixes & Protocol Honesty

**Goal:** Fix production blockers and correct misleading naming before anything else ships.

**Estimated scope:** ~200 LOC changed across 8 files.

### 1A. Fix S3Client Memory Bug

**Problem:** `S3Client.kt:150` calls `localFile.readBytes()`, loading the entire file into a `ByteArray` before upload. For files approaching the 5MB `MULTIPART_THRESHOLD` (line 60), this risks OOM on memory-constrained Android devices running 24/7.

**Fix:**
- Replace `readBytes()` in `uploadSingle()` (line 150) with `FileInputStream` streaming
- Compute SHA256 incrementally via `MessageDigest.update()` during the stream read, instead of hashing the full byte array (line 151)
- Pipe `FileInputStream` directly to `HttpURLConnection.outputStream` in chunks
- The multipart path (lines 208-271) already uses chunked reads via `fillBuffer()` — apply the same pattern to single uploads

**Files:**
- `app/src/main/java/com/lensdaemon/storage/S3Client.kt` — rewrite `uploadSingle()` (lines 142-205)

**Validation:** Unit test uploading a 4.9MB file succeeds without allocating a 5MB byte array. Instrument with `Runtime.getRuntime().freeMemory()` before/after.

---

### 1B. Fix RTSP Null Pointer Crash (Client DoS)

**Problem:** `RtspSession.kt:363` — `rtpPacketizer!!.packetize()`. If a client sends PLAY before SETUP (a protocol violation), `rtpPacketizer` is null and the server crashes with NPE. Any malicious or buggy RTSP client can crash the streaming service.

**Fix:**
- Replace `rtpPacketizer!!` with a null check:
  ```kotlin
  val packetizer = rtpPacketizer ?: run {
      Timber.w("$TAG: Frame received but no packetizer (SETUP not called)")
      return
  }
  ```
- Add state validation in `handlePlay()` — reject PLAY if session state is not READY (SETUP completed)
- Audit and fix the other high-risk `!!` assertions:
  - `RtspSession.kt:94` (`inputStream!!`) — use `?.let` or early return
  - `RtspSession.kt:269` (`udpSocket!!.localPort`) — guard with null check
  - `RtspSession.kt:419` (`outputStream!!`) — guard in synchronized block
  - `VideoEncoder.kt:189,192,199` (`mediaCodec!!`) — wrap in safe calls
  - `FrameProcessor.kt:95,98,104,111` (`encoder!!`) — initialization race guard

**Files:**
- `app/src/main/java/com/lensdaemon/output/RtspSession.kt` — 4 fixes
- `app/src/main/java/com/lensdaemon/encoder/VideoEncoder.kt` — 3 fixes
- `app/src/main/java/com/lensdaemon/encoder/FrameProcessor.kt` — 4 fixes

**Validation:** Write a test that sends PLAY without SETUP and confirms the server returns 455 Method Not Valid in This State instead of crashing.

---

### 1C. Rename SRT to MPEG-TS/UDP

**Problem:** `SrtPublisher.kt` implements MPEG-TS over raw UDP datagrams. No SRT handshake, encryption, ARQ, or congestion control. The file header (lines 35-39) acknowledges this, but the API, config, UI, and docs all call it "SRT." This misleads users about protocol capabilities (no encryption, no packet loss recovery).

**Rename scope:**
| Location | Current | Renamed To |
|----------|---------|------------|
| Class name | `SrtPublisher` | `MpegTsUdpPublisher` |
| Config class | `SrtConfig` | `MpegTsUdpConfig` |
| Stats class | `SrtStats` | `MpegTsUdpStats` |
| API endpoints | `/api/srt/*` | `/api/mpegts/*` |
| Config fields | `srtEnabled`, `srtPort` | `mpegtsEnabled`, `mpegtsPort` |
| CameraService members | `srtPublisher`, `_srtRunning` | `mpegtsPublisher`, `_mpegtsRunning` |
| Dashboard labels | "SRT Streaming" | "MPEG-TS/UDP Streaming" |
| README / CLAUDE.md | "SRT" references | "MPEG-TS/UDP" with note on future SRT |

**Files (30+ references):**
- `app/src/main/java/com/lensdaemon/output/SrtPublisher.kt` — rename class/data classes
- `app/src/main/java/com/lensdaemon/camera/CameraService.kt` — rename members (~30 references)
- `app/src/main/java/com/lensdaemon/web/handlers/StreamApiHandler.kt` — rename endpoints (~11 references)
- `app/src/main/java/com/lensdaemon/web/ApiRoutes.kt` — update URI routing
- `app/src/main/java/com/lensdaemon/config/AppConfig.kt` — rename config fields
- `app/src/main/assets/web/index.html` — update UI labels
- `app/src/main/assets/web/dashboard.js` — update function names and API calls
- `README.md`, `CLAUDE.md` — documentation updates

**Backward compatibility:** Add a deprecation note in the API docs. Consider keeping `/api/srt/*` as aliases for one release cycle if external tools depend on the endpoints.

**Validation:** `./gradlew build` passes. Dashboard displays "MPEG-TS/UDP" everywhere. API endpoints respond at new paths.

---

### 1D. Add RTSP Client Timeout and Eviction

**Problem:** `RtspServer.kt:55` hardcodes `MAX_CLIENTS = 10` with no timeout or eviction. Stalled clients (connected but not sending commands) hold slots indefinitely. `RtspSession.kt` has a `READ_TIMEOUT_MS = 60_000` on the socket, but this only affects the request-reading loop — it doesn't evict inactive sessions from the server's session list.

**Fix:**
- Add `lastActivityTimestamp` to `RtspSession` — update on every RTSP command received and every frame sent
- Add a periodic eviction coroutine in `RtspServer` (every 30s) that removes sessions idle for > `sessionTimeoutMs` (configurable, default 120s)
- Make `MAX_CLIENTS` configurable via `RtspServer.Builder` or runtime setter
- On eviction, call `session.disconnect()` and log the event

**Files:**
- `app/src/main/java/com/lensdaemon/output/RtspSession.kt` — add timestamp tracking
- `app/src/main/java/com/lensdaemon/output/RtspServer.kt` — add eviction loop, configurable MAX_CLIENTS

**Validation:** Test with 11 concurrent connections — 11th should succeed after an idle session is evicted. Test that a connected-but-silent client gets evicted after timeout.

---

## Phase 2: CameraService Decomposition

**Goal:** Reduce CameraService from 1,559 lines to ~800 lines by integrating existing (but unused) coordinators and extracting new ones.

**Estimated scope:** ~700 LOC moved/deleted, ~200 LOC new coordinator code.

### 2A. Integrate Existing RecordingCoordinator (Dead Code Removal)

**Problem:** `RecordingCoordinator.kt` (162 lines) was created during Phase 3 remediation but never wired in. `CameraService.kt` still has its own recording logic at lines 1165-1419 (~260 lines) that duplicates every method in RecordingCoordinator.

**Fix:**
- Replace the recording methods in CameraService (lines 1165-1419) with delegation to `RecordingCoordinator`
- CameraService should hold a `RecordingCoordinator` reference and forward: `initializeRecording()`, `startRecording()`, `stopRecording()`, `pauseRecording()`, `resumeRecording()`, `getRecordingStats()`, `getStorageStatus()`, `listRecordings()`, `deleteRecording()`, `setSegmentDuration()`, `enforceRetention()`, `releaseStorageManager()`
- Remove the duplicated StorageManager lifecycle code from CameraService
- Keep the `recordingFrameListener` lambda but register it through the coordinator

**Files:**
- `app/src/main/java/com/lensdaemon/camera/CameraService.kt` — delete lines 1165-1419, replace with coordinator calls
- `app/src/main/java/com/lensdaemon/camera/RecordingCoordinator.kt` — verify all methods match the CameraService API surface, add any missing

**Net effect:** -250 lines from CameraService.

---

### 2B. Integrate Existing RtspCoordinator (Dead Code Removal)

**Problem:** `RtspCoordinator.kt` (77 lines) was created but never wired in. `CameraService.kt` has its own RTSP management at lines 939-1067 (~130 lines) that duplicates the coordinator.

**Fix:**
- Replace RTSP methods in CameraService (lines 939-1067) with delegation to `RtspCoordinator`
- Forward: `startRtspServer()`, `stopRtspServer()`, `getRtspUrl()`, `getRtspStats()`, `getRtspClientCount()`, `isRtspRunning()`, `updateRtspCodecConfig()`
- Add any missing convenience methods (e.g., `startRtspStreaming()` which combines encoder + RTSP start) — these stay in CameraService as orchestration methods, but the RTSP-specific logic lives in the coordinator
- Move `rtspFrameListener` registration through the coordinator

**Files:**
- `app/src/main/java/com/lensdaemon/camera/CameraService.kt` — delete lines 939-1067, replace with coordinator calls
- `app/src/main/java/com/lensdaemon/camera/RtspCoordinator.kt` — verify API coverage, add codec config callback

**Net effect:** -100 lines from CameraService.

---

### 2C. Extract New SrtCoordinator (Parallel to RTSP)

**Problem:** SRT/MPEG-TS publisher management (lines 1074-1157, ~85 lines) follows the same pattern as RTSP but has no coordinator.

**Fix:**
- Create `MpegTsCoordinator.kt` (following the rename from Phase 1C) with the same pattern as `RtspCoordinator`
- Methods: `startPublisher()`, `stopPublisher()`, `isRunning()`, `getStats()`
- Move `srtFrameListener` registration into the coordinator
- CameraService delegates to coordinator, keeps only the orchestration convenience methods (`startMpegTsStreaming()`, `stopMpegTsStreaming()`)

**Files:**
- `app/src/main/java/com/lensdaemon/camera/MpegTsCoordinator.kt` — new file, ~80 lines
- `app/src/main/java/com/lensdaemon/camera/CameraService.kt` — replace lines 1074-1157

**Net effect:** -60 lines from CameraService, +80 lines new coordinator.

---

### 2D. Extract FrameDistributor

**Problem:** CameraService manually manages 4 frame listener chains (`encodedFrameListeners`, `recordingFrameListener`, `rtspFrameListener`, `srtFrameListener`) with no prioritization, no backpressure handling, and no error isolation. If one listener throws, others in the list may be skipped.

**Fix:**
- Create `FrameDistributor.kt` — a single class that:
  - Holds the listener list
  - Dispatches frames to all listeners with per-listener try/catch
  - Provides `addListener()`, `removeListener()`, `removeAll()`
  - Optionally dispatches on a coroutine to avoid blocking the encoder callback
- Replace CameraService's manual listener management (lines 111, 124-141, 891-916) with FrameDistributor

**Files:**
- `app/src/main/java/com/lensdaemon/camera/FrameDistributor.kt` — new file, ~60 lines
- `app/src/main/java/com/lensdaemon/camera/CameraService.kt` — replace listener code

**Net effect:** -30 lines from CameraService, +60 lines new class. Better isolation.

---

### 2E. Remove Director-Specific Methods from CameraService

**Problem:** CameraService exposes 13 methods (lines 1424-1556, ~140 lines) that exist solely for the AI Director: `animateZoom()`, `setAutoFocus()`, `enableFaceDetectionFocus()`, `setFocusDistance()`, `getMaxZoom()`, `supportsFaceDetection()`, `supportsManualFocus()`, `isFocusLocked()`, `getNormalizedExposure()`, `getMotionShakiness()`, `getCurrentCpuTemperature()`. These are not used by any other consumer.

**Fix (depends on Phase 3):**
- Define a `CameraCapabilityProvider` interface in the `:director` module (or a shared `:camera-api` module) that exposes these methods
- `CameraControllerAdapter.kt` already bridges Director to CameraService — have the adapter call through to the existing controller methods directly (zoom controller, focus controller, etc.) instead of going through CameraService wrapper methods
- Remove the 13 wrapper methods from CameraService

**Files:**
- `app/src/main/java/com/lensdaemon/camera/CameraService.kt` — delete lines 1424-1556
- `app/src/main/java/com/lensdaemon/director/CameraControllerAdapter.kt` — call controllers directly instead of CameraService wrappers

**Net effect:** -140 lines from CameraService.

**After all Phase 2 steps:** CameraService drops from 1,559 to ~950 lines. Remaining responsibilities: camera lifecycle, encoder binding, preview management, controller setup, and high-level orchestration (start streaming + recording combos).

---

## Phase 3: Director Module Extraction

**Goal:** Move the AI Director from embedded code into a separate Gradle feature module with a compile-time flag. The core APK ships without it. A "LensDaemon Director" APK variant includes it.

**Estimated scope:** ~5,000 LOC moved, ~150 LOC new glue code, Gradle config changes.

### 3A. Create `:director` Gradle Module

**Structure:**
```
director/
├── build.gradle.kts             # Android library, depends on :app's camera API
├── src/main/java/com/lensdaemon/director/
│   ├── DirectorManager.kt       # (605 lines) — moved from app
│   ├── DirectorConfig.kt        # (408 lines)
│   ├── DirectorService.kt       # (588 lines)
│   ├── DirectorWatchdog.kt      # (481 lines)
│   ├── ScriptParser.kt          # (449 lines)
│   ├── ShotMapper.kt            # (476 lines)
│   ├── TakeManager.kt           # (534 lines)
│   ├── TransitionAnimator.kt    # (437 lines)
│   ├── QualityMetricsCollector.kt # (328 lines)
│   ├── CameraControllerAdapter.kt # (176 lines)
│   └── RemoteLlmClient.kt       # (493 lines)
└── src/test/java/com/lensdaemon/director/
    ├── ScriptParserTest.kt       # (1,184 lines) — moved from app
    ├── ShotMapperTest.kt
    └── RemoteLlmClientTest.kt
```

**Gradle setup:**
```kotlin
// settings.gradle.kts
include(":director")

// app/build.gradle.kts
dependencies {
    // Only include director in "full" build variant
    fullImplementation(project(":director"))
}

// director/build.gradle.kts
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("com.google.code.gson:gson:2.10.1")
}
```

**Build variants:**
```kotlin
// app/build.gradle.kts
productFlavors {
    create("core") {
        // Lean streaming appliance — no Director
        dimension = "feature"
    }
    create("full") {
        // Includes AI Director
        dimension = "feature"
        buildConfigField("boolean", "ENABLE_DIRECTOR", "true")
    }
}
```

**Files:**
- `settings.gradle.kts` — add `:director` include
- `director/build.gradle.kts` — new
- `app/build.gradle.kts` — add product flavors, conditional dependency
- Move 11 source files + 3 test files from `app/` to `director/`

---

### 3B. Define Camera API Interface Boundary

**Problem:** The Director module depends on `com.lensdaemon.camera.LensType` and calls CameraService methods via `CameraControllerAdapter`. This creates a compile-time dependency from `:director` to `:app`.

**Fix:** Extract a thin shared interface module or define the interface inside `:director`:

```kotlin
// In :director module
interface CameraController {
    fun switchLens(lens: String)          // "wide", "main", "tele" — no LensType import needed
    fun setZoom(level: Float, animated: Boolean = false, durationMs: Long = 0)
    fun setFocusMode(target: FocusTarget) // FocusTarget defined in :director
    fun setExposurePreset(preset: ExposurePreset)
    fun getCurrentTemperature(): Int
    fun getMaxZoom(): Float
    fun supportsFaceDetection(): Boolean
    fun supportsManualFocus(): Boolean
    fun isFocusLocked(): Boolean
    fun getNormalizedExposure(): Float
    fun getMotionShakiness(): Float
}
```

**Key change:** Replace `LensType` enum import with a String parameter. The `CameraControllerAdapter` (which lives in `:app` or `:director`) maps `"wide"` → `LensType.WIDE` at the boundary.

**Files:**
- `director/src/.../DirectorManager.kt` — update CameraController interface to use String for lens
- `app/src/.../CameraControllerAdapter.kt` — if moved to `:app`, implement the Director's interface and bridge to CameraService

---

### 3C. Gate Director Integration in WebServerService

**Current code** (`WebServerService.kt` lines 208-253) unconditionally creates DirectorManager, CameraControllerAdapter, and QualityMetricsCollector.

**Fix:**
```kotlin
private fun setupDirectorIntegration(camera: CameraService) {
    if (!BuildConfig.ENABLE_DIRECTOR) return
    // ... existing setup code
}
```

**Files:**
- `app/src/main/java/com/lensdaemon/web/WebServerService.kt` — wrap director code in build flag check
- `app/src/main/java/com/lensdaemon/web/ApiRoutes.kt` — skip DirectorApiHandler registration when disabled

---

### 3D. Split Dashboard Assets (Optional)

The dashboard is currently a single `index.html` + `styles.css` + `dashboard.js`. Director content accounts for ~48% of HTML, ~38% of CSS, ~33% of JS.

**Option A (recommended for now):** Keep combined but gate UI via JavaScript:
```javascript
// In dashboard.js init()
if (!window.DIRECTOR_ENABLED) {
    document.getElementById('director-section').style.display = 'none';
}
```
The `/api/status` response already includes director state — use its presence/absence as the feature flag.

**Option B (future):** Split into separate files:
- `director.js` (~500 lines) — loaded conditionally
- `director.css` (~350 lines) — loaded conditionally
- Director HTML section served from `:director` module's assets

**Recommendation:** Option A for Phase 3. Option B when/if the Director becomes a separate product.

---

### 3E. Update AndroidManifest

- Remove `DirectorService` from `app/src/main/AndroidManifest.xml` (line 93-97)
- Add it to `director/src/main/AndroidManifest.xml` (manifest merge will include it only in `full` variant)

---

### 3F. Move Director Tests

Move from `app/src/test/`:
- `ScriptParserTest.kt` (1,184 lines)
- `ShotMapperTest.kt`
- `RemoteLlmClientTest.kt`

To `director/src/test/`. Ensure `./gradlew :director:test` runs independently.

---

## Phase 4: Director Scope Reduction (Within Module)

**Goal:** Trim the Director module itself by cutting features that belong in a "studio production" tool, not a camera appliance add-on.

**Estimated scope:** ~1,200 LOC removed, ~100 LOC simplified.

### 4A. Remove DirectorWatchdog Autonomous Operation

**What it does:** `DirectorWatchdog.kt` (481 lines) — stall detection with auto-advance, error recovery with consecutive error tracking, LLM health checking with automatic PRE_PARSED fallback, thermal hold auto-resume, script queue for sequential execution.

**Why cut:** This is production-studio reliability tooling. A streaming appliance add-on should surface errors to the user via the web UI, not autonomously recover. If the director stalls, the user restarts it. The watchdog's 5 concurrent monitoring coroutines add thermal load even in "idle" state.

**Action:** Delete `DirectorWatchdog.kt`. Remove watchdog setup from `DirectorManager.kt`. Remove watchdog config from `DirectorConfig.kt`. Remove watchdog stats from status API.

**Files:**
- Delete `director/src/.../DirectorWatchdog.kt` (481 lines)
- `director/src/.../DirectorManager.kt` — remove watchdog references
- `director/src/.../DirectorConfig.kt` — remove WatchdogConfig
- `app/src/.../handlers/DirectorApiHandler.kt` — remove watchdog status fields

**Net effect:** -500 LOC.

---

### 4B. Simplify RemoteLlmClient to Ollama-Only

**What it does:** `RemoteLlmClient.kt` (493 lines) supports 4 LLM providers: OpenAI, Anthropic, Ollama, and generic OpenAI-compatible endpoints. Each has different auth headers, request formats, and response parsing.

**Why cut multi-provider:** The README advertises "Zero Cloud Dependency." Supporting OpenAI/Anthropic requires API keys, external network calls, and SSRF mitigation — all contrary to the core product identity. Ollama (local LLM) is the only provider that fits the appliance model.

**Action:**
- Remove `LlmProvider.OPENAI`, `ANTHROPIC`, `GENERIC_OPENAI` cases
- Remove API key management and external endpoint validation
- Remove SSRF prevention code (no longer needed if only localhost)
- Keep `LlmProvider.OLLAMA` and hardcode endpoint to `http://localhost:11434`
- Simplify from ~493 lines to ~150 lines

**Files:**
- `director/src/.../RemoteLlmClient.kt` — rewrite to Ollama-only
- `director/src/.../DirectorConfig.kt` — simplify LlmConfig (remove apiKey, provider fields)
- `app/src/.../handlers/DirectorApiHandler.kt` — simplify LLM config API
- `director/src/test/.../RemoteLlmClientTest.kt` — remove multi-provider tests

**Net effect:** -350 LOC.

---

### 4C. Remove Session Export Pipeline

**What it does:** Dashboard exports session data as JSON report, script text file, and takes CSV. Three export functions in `dashboard.js` (~60 lines), corresponding API endpoints.

**Why cut:** Post-production workflow tooling. A camera appliance doesn't need session export.

**Action:**
- Remove `exportSessionReport()`, `exportScript()`, `exportTakesCsv()` from `dashboard.js`
- Remove export buttons from `index.html`
- Remove export styles from `styles.css`
- Remove `/api/director/scripts/export` and related endpoints from `DirectorApiHandler.kt`

**Net effect:** -150 LOC across dashboard + handler.

---

### 4D. Simplify Timeline to Text-Only

**What it does:** Interactive timeline visualization with proportional scene blocks, animated playhead, scene navigation buttons, color-coded past/current/future states. ~200 lines of JS + 100 lines of CSS.

**Why simplify:** Over-engineered for an "experimental" add-on. A text status line ("Scene 2/5 - Cue 3/12") provides the same information.

**Action:**
- Replace timeline HTML with a single `<div id="director-progress">Scene 2/5 - Cue 3/12</div>`
- Remove `renderTimeline()`, `updateTimelinePlayhead()`, `updateTimelineScene()`, `updateTimelineProgress()`, `renderSceneNav()`, `jumpToScene()` from dashboard.js
- Keep `jumpToScene()` accessible via a simple dropdown/number input
- Remove timeline CSS

**Net effect:** -250 LOC across JS + CSS + HTML.

---

## Phase 5: Integration Testing & RTSP Hardening

**Goal:** Add device-level smoke tests and strengthen the primary streaming protocol.

**Estimated scope:** ~600 LOC new test code, ~100 LOC RTSP improvements.

### 5A. Add Instrumentation Test Suite

**Problem:** 10 test files exist, all unit tests. Zero instrumentation tests. Camera2, MediaCodec, and Device Owner APIs have device-specific behaviors that unit tests cannot catch.

**Tests to add:**
```
app/src/androidTest/java/com/lensdaemon/
├── CameraServiceSmokeTest.kt     # Start/stop preview, verify frames produced
├── EncoderServiceSmokeTest.kt    # Initialize encoder, verify output format
├── RtspServerSmokeTest.kt        # Start server, connect client, verify SDP
├── WebServerSmokeTest.kt         # Start server, GET /api/status, verify JSON
└── KioskModeSmokeTest.kt         # Verify Device Owner APIs (requires ADB setup)
```

**Each test:**
1. Starts the relevant service
2. Performs one core operation
3. Verifies the expected result
4. Tears down cleanly

**Files:**
- 5 new test files in `app/src/androidTest/`
- `app/build.gradle.kts` — add androidTest dependencies (Espresso, AndroidX Test Runner)

---

### 5B. Add RTCP Receiver Reports

**Problem:** RTSP server sends RTP packets but has no feedback loop. The server cannot detect packet loss, jitter, or one-way stalled connections at the RTP layer.

**Fix:**
- Add basic RTCP receiver report handling in `RtspSession.kt`
- Listen on RTP port + 1 (standard RTCP port) for receiver reports
- Parse RTCP SR/RR packets to detect: packet loss fraction, cumulative loss, jitter
- Use loss information to trigger adaptive bitrate reduction (integrate with encoder's `updateBitrate()`)
- Log RTCP stats to `RtspServerStats` for the dashboard

**Files:**
- `app/src/main/java/com/lensdaemon/output/RtspSession.kt` — add RTCP listener
- `app/src/main/java/com/lensdaemon/output/RtcpParser.kt` — new file, ~100 lines
- `app/src/main/java/com/lensdaemon/output/RtspServer.kt` — add RTCP stats to server stats

---

### 5C. Add CI Instrumentation Test Step

**Problem:** CI pipeline runs unit tests only.

**Fix:**
- Add Android emulator step to `.github/workflows/ci.yml` using `reactivecircus/android-emulator-runner@v2`
- Run instrumentation tests on API 29 emulator
- Upload test results as artifact

**Files:**
- `.github/workflows/ci.yml` — add emulator job after unit tests

---

## Phase 6: Thermal Profiling & Documentation

**Goal:** Turn the thermal management system (the actual killer feature) from a generic implementation into a device-specific tuning system.

**Estimated scope:** ~300 LOC new code, significant documentation.

### 6A. Device Thermal Profile System

**Problem:** `ThermalGovernor.kt` uses fixed thresholds (45/50/55/60 C) for all devices. Different SoCs throttle at different temperatures. A Snapdragon 888 hits thermal throttle at 42 C, a Snapdragon 855 is fine at 50 C.

**Fix:**
- Create `ThermalProfile` data class: device model → (cpuWarnTemp, cpuCriticalTemp, sustainableBitrate, sustainableResolution)
- Ship a default profile database in `assets/thermal_profiles.json` with entries for common SoCs
- Auto-detect device model via `Build.MODEL` / `Build.SOC_MODEL` and load matching profile
- Allow override via `/api/thermal/profile` API
- Fall back to current conservative defaults for unknown devices

**Files:**
- `app/src/main/java/com/lensdaemon/thermal/ThermalProfile.kt` — new, ~100 lines
- `app/src/main/java/com/lensdaemon/thermal/ThermalGovernor.kt` — use profile thresholds instead of hardcoded
- `app/src/main/assets/thermal_profiles.json` — device database
- `app/src/main/java/com/lensdaemon/web/handlers/ThermalApiHandler.kt` — add profile API

---

### 6B. Thermal Stress Test Tooling

**Fix:**
- Add `/api/thermal/stress-test` endpoint that runs a 5-minute encoding stress test at max resolution/bitrate
- Records temperature curve, throttle events, sustainable performance ceiling
- Outputs a recommended ThermalProfile for the current device
- Users can contribute profiles back to the project

**Files:**
- `app/src/main/java/com/lensdaemon/thermal/ThermalStressTest.kt` — new, ~150 lines
- `app/src/main/java/com/lensdaemon/web/handlers/ThermalApiHandler.kt` — add stress test endpoint

---

### 6C. Kiosk Setup Wizard

**Problem:** Kiosk mode setup requires factory reset + ADB command + web configuration. Easy to get wrong.

**Fix:**
- Add a setup wizard page at `/setup` in the web UI
- Step 1: Check Device Owner status, show ADB command if not set
- Step 2: Choose preset (APPLIANCE vs INTERACTIVE)
- Step 3: Configure network storage (optional)
- Step 4: Test stream, confirm thermal behavior
- Step 5: Enable kiosk mode

**Files:**
- `app/src/main/assets/web/setup.html` — new wizard page
- `app/src/main/assets/web/setup.js` — wizard logic
- `app/src/main/java/com/lensdaemon/web/WebServer.kt` — serve setup page

---

## Phase Dependency Graph

```
Phase 1 (Bug Fixes)
  ├── 1A: S3 memory fix          (independent)
  ├── 1B: RTSP NPE fix           (independent)
  ├── 1C: SRT rename             (independent)
  └── 1D: RTSP timeout           (independent)
          │
Phase 2 (CameraService Decomposition)
  ├── 2A: Recording coordinator   (depends on 1*)
  ├── 2B: RTSP coordinator        (depends on 1B, 1D)
  ├── 2C: MpegTs coordinator      (depends on 1C)
  ├── 2D: Frame distributor       (depends on 2A, 2B, 2C)
  └── 2E: Remove Director methods (depends on Phase 3)
          │
Phase 3 (Director Extraction)
  ├── 3A: Create Gradle module    (depends on 2*)
  ├── 3B: Camera API interface    (depends on 3A)
  ├── 3C: Gate WebServerService   (depends on 3A)
  ├── 3D: Dashboard gating        (depends on 3C)
  ├── 3E: Manifest update         (depends on 3A)
  └── 3F: Move tests              (depends on 3A)
          │
Phase 4 (Director Scope Reduction)
  ├── 4A: Remove Watchdog         (depends on 3A)
  ├── 4B: Ollama-only LLM        (depends on 3A)
  ├── 4C: Remove export pipeline  (depends on 3A)
  └── 4D: Simplify timeline       (depends on 3A)
          │
Phase 5 (Testing & RTSP)           ← Can run in parallel with Phase 4
  ├── 5A: Instrumentation tests   (depends on 2*)
  ├── 5B: RTCP receiver reports   (depends on 1D)
  └── 5C: CI emulator step        (depends on 5A)
          │
Phase 6 (Thermal & Docs)           ← Can run in parallel with Phase 4-5
  ├── 6A: Device thermal profiles (independent after Phase 1)
  ├── 6B: Stress test tooling     (depends on 6A)
  └── 6C: Kiosk setup wizard      (independent)
```

## Summary

| Phase | Focus | LOC Impact | Priority |
|-------|-------|-----------|----------|
| 1 | Critical bug fixes & naming | ~200 changed | **Immediate** |
| 2 | CameraService decomposition | -580, +220 new | **High** |
| 3 | Director module extraction | ~5,000 moved | **High** |
| 4 | Director scope reduction | -1,250 deleted | **Medium** |
| 5 | Integration testing & RTSP | +700 new tests | **Medium** |
| 6 | Thermal profiling & docs | +550 new | **Lower** |

**After all 6 phases:**
- Core APK is a lean streaming appliance (~23,000 LOC without Director)
- Director is an optional add-on module (~3,700 LOC after trimming)
- CameraService is under 800 lines
- RTSP server is hardened with timeouts, eviction, and RTCP
- S3 uploads won't OOM
- Protocol naming is honest
- Thermal system is device-aware
- Integration tests exist
