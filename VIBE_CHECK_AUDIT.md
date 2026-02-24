# Vibe-Code Detection Audit v2.0 -- LensDaemon

**Date:** 2026-02-24
**Auditor:** Claude Opus 4.6 (automated)
**Methodology:** [Vibe-Code Detection Audit v2.0](https://github.com/kase1111-hash/Claude-prompts/blob/main/vibe-checkV2.md)
**Codebase:** 28,854 lines of Kotlin across 88 source files, 15 test files, 5 web assets

---

## Executive Summary

| Metric | Value |
|--------|-------|
| **Vibe-Code Confidence** | **40.3%** |
| **Classification** | **Substantially Vibe-Coded** |
| Weighted Authenticity | 59.7% |
| Domain A (Surface Provenance) | 42.9% authenticity |
| Domain B (Behavioral Integrity) | 59.5% authenticity |
| Domain C (Interface Authenticity) | 71.4% authenticity |

**Interpretation:** LensDaemon is a transparently AI-generated codebase (100% of implementation commits are attributed to Claude) that nonetheless contains substantial genuine engineering in its core protocol implementations. The project exhibits a characteristic pattern: strong implementations of well-specified protocols (RTSP, RTP, MPEG-TS, AWS SigV4) coexisting with decorative infrastructure (disabled auth, dead config system, phantom dependencies) that was generated but never integrated. The gap between what is documented and what actually functions is the primary vibe-code indicator.

---

## Scoring Calculation

```
Domain A (Surface Provenance):  9.0 / 21 = 42.9% authenticity  x 0.20 =  8.6%
Domain B (Behavioral Integrity): 12.5 / 21 = 59.5% authenticity  x 0.50 = 29.8%
Domain C (Interface Authenticity): 15.0 / 21 = 71.4% authenticity  x 0.30 = 21.4%
                                                                          ------
Weighted Authenticity:                                                      59.7%
Vibe-Code Confidence:  100% - 59.7% =                                      40.3%
```

**Classification bands:**
- 0-15%: Human-Authored
- 16-35%: AI-Assisted
- **36-60%: Substantially Vibe-Coded** <-- LensDaemon falls here
- 61-85%: Predominantly Vibe-Coded
- 86-100%: Almost Certainly AI-Generated

---

## Domain A: Surface Provenance (20% weight)

**Domain Score: 9/21 (42.9% authenticity)**

| # | Criterion | Score | Evidence Summary |
|---|-----------|-------|-----------------|
| A1 | Commit History Patterns | 1/3 | 30/30 implementation commits by `Claude <noreply@anthropic.com>`. Human author contributed only initial commit, spec.md, LICENSE, README. All branches named `claude/*`. Zero reverts, WIPs, fixups, or frustration markers. 10-phase app built in ~5.5 hours. |
| A2 | Comment Archaeology | 1/3 | Zero TODO/FIXME/HACK markers across 65+ files. 189 `// ========` section dividers across 21 files. Comments are descriptive-only (WHAT, never WHY). SmbClient contains self-aware notes: "in production, use proper NTLM library." |
| A3 | Test Quality Signals | 2/3 | 694 `@Test` methods across 16 files. Protocol tests (NalUnitParser, RtpPacketizer) verify real binary parsing. Security tests cover path traversal vectors. But ThermalConfigTest has ~60 tests mostly verifying default constructors. No mocking framework, no parameterized tests, no integration tests. |
| A4 | Import & Dependency Hygiene | 1/3 | Two phantom dependencies: AWS SDK (`com.amazonaws:aws-android-sdk-s3`) and smbj (`com.hierynomus:smbj`) are declared in build.gradle.kts but have **zero imports** -- both S3Client and SmbClient are hand-rolled. Wildcard `kotlinx.coroutines.*` imports in 12+ files. Gson declared but barely used (2 files; rest uses `org.json.JSONObject`). |
| A5 | Naming Consistency | 1/3 | Zero naming deviations across 65+ files -- perfect PascalCase classes, camelCase methods, SCREAMING_SNAKE constants, `_privateFlow`/`publicFlow` pattern uniformly. No typos, no abbreviation drift, no synonym variation. This degree of uniformity is a strong AI signal. |
| A6 | Documentation vs. Reality | 2/3 | Most documented features are genuinely implemented (RTSP, recording, web dashboard, kiosk). However, CLAUDE.md documents `SrtPublisher.kt` and `/api/srt/*` endpoints that don't exist -- the actual implementation is `MpegTsUdpPublisher.kt` with `/api/mpegts/*` routes. README marks SRT as unchecked but CLAUDE.md describes it as complete. |
| A7 | Dependency Utilization | 1/3 | NanoHTTPD and Timber are deeply integrated. AWS SDK and smbj are phantom (declared, never imported). Gson is minimally used. Material Design is XML-only. The AI declared libraries in build.gradle, then hand-rolled the implementations from scratch, producing incomplete protocol stacks (SMB NTLM uses MD5 where MD4 is required). |

### Key A-Domain Findings

**A4/A7 Detail -- Phantom Dependencies:**

```
// app/build.gradle.kts line 91 -- declared but ZERO imports in codebase
implementation("com.amazonaws:aws-android-sdk-s3:2.73.0")

// app/build.gradle.kts line 94 -- declared but ZERO imports in codebase
implementation("com.hierynomus:smbj:0.12.2")
```

The `S3Client.kt` (599 lines) manually implements AWS Signature V4 using `javax.crypto.Mac` and `java.net.HttpURLConnection`. The `SmbClient.kt` (774 lines) manually implements SMB2 protocol using raw `java.nio.ByteBuffer` and `java.net.Socket`, with a broken NTLM authentication that uses MD5 instead of MD4 (`SmbClient.kt:675`).

**Remediation:** Remove unused AWS SDK and smbj dependencies from build.gradle.kts to reduce APK size. Either integrate the declared libraries properly or document that the hand-rolled implementations are intentional.

---

## Domain B: Behavioral Integrity (50% weight)

**Domain Score: 12.5/21 (59.5% authenticity)**

Two passes were performed: a problem-focused pass cataloguing all issues, and an execution-tracing pass following 5 features end-to-end.

| # | Criterion | Score | Evidence Summary |
|---|-----------|-------|-----------------|
| B1 | Error Handling Authenticity | 1.5/3 | 146 generic `catch (e: Exception)` blocks vs. only 13 typed catches (11:1 ratio). No custom exception classes thrown. Sealed error enums exist (`CameraError`, `EncoderError`) but aren't used as exceptions. Pattern is uniformly catch-log-swallow via Timber. |
| B2 | Configuration Actually Used | 1/3 | `config/AppConfig.kt` (172 lines) and `config/ConfigManager.kt` (71 lines) are 100% dead code -- never instantiated anywhere. ~30 config fields (`streamConfig.resolution`, `thermalConfig.cpuThrottleThreshold`, `securityConfig.pin`, etc.) are never read. Actual config comes from API request bodies and per-module defaults. |
| B3 | Call Chain Completeness | 2/3 | 4/5 traced features complete: RTSP (API to RTP packets), S3 upload (API to signed HTTP), MPEG-TS (API to UDP datagrams), recording (API to MP4 files). **Thermal throttling is broken**: ThermalGovernor detects temps and invokes `onReduceBitrate?.invoke()` but the callback is permanently null -- never wired by any consumer. |
| B4 | Async Correctness | 2/3 | Proper `CoroutineScope(SupervisorJob())` in all services with cancellation in `onDestroy()`. `AtomicBoolean` for concurrent flags. But: `CameraService.isPreviewActive`/`isStreamingActive` are unprotected `var` read from HTTP threads; `runBlocking` in `UploadApiHandler` blocks NanoHTTPD threads; `ThermalGovernor.listeners` unsynchronized. |
| B5 | State Management Coherence | 2/3 | Extensive `StateFlow` usage for observable state across services. Coordinator pattern (`RtspCoordinator`, `RecordingCoordinator`, `MpegTsCoordinator`) properly encapsulates state. But: dual-state issue with plain `var` booleans alongside StateFlows; `ApiRoutes` service references are unprotected `var` accessed cross-thread. |
| B6 | Security Implementation Depth | 1.5/3 | **Auth is decorative**: `apiToken` is permanently null, `checkAuth()` always bypasses. **Rate limiting is decorative**: `rateLimiter` is permanently null. Credential encryption via Android Keystore is real and correct. Path traversal protection is implemented but missing from `StreamApiHandler.deleteRecording()`. |
| B7 | Resource Management | 2.5/3 | Systematic cleanup: `CameraService.onDestroy()` releases all subsystems. `RtspSession.close()` uses `compareAndSet` for idempotent close. `MediaCodec`/`MediaMuxer` properly released. Minor gap: UDP socket not cleaned up if RTCP bind fails in `RtspSession.setupUdpTransport()`. |

### Key B-Domain Findings

**B2 Detail -- Dead Config System:**

```kotlin
// config/ConfigManager.kt -- NEVER instantiated or called
class ConfigManager private constructor(context: Context) {
    companion object {
        fun getInstance(context: Context): ConfigManager { ... }
    }
}

// config/AppConfig.kt -- 30+ fields, ALL dead
data class StreamConfig(
    val resolution: String = "1080p",    // never read
    val bitrate: Int = 4_000_000,        // never read
    val codec: String = "h264",          // never read
    // ...
)
```

Grep for `ConfigManager.getInstance`: 0 results outside ConfigManager.kt itself.

**B3 Detail -- Broken Thermal Throttling Chain:**

```
ThermalMonitor (detects temp)
    -> ThermalGovernor.evaluateThermalState() (decides action)
        -> applyBitrateReduction()
            -> onReduceBitrate?.invoke(percent)  // ALWAYS NULL
                -> ThermalService.onReduceBitrate  // var = null, never assigned
                    -> [DEAD END -- no consumer wires this callback]
```

The thermal governor correctly detects CPU temperatures from `/sys/class/thermal/`, evaluates levels with hysteresis, and decides to throttle -- but the final step of actually reducing the encoder bitrate never happens because no code assigns the callback. The thermal data is observable via `GET /api/thermal/status` but never actionable.

**B6 Detail -- Decorative Security:**

```kotlin
// ApiRoutes.kt line 94 -- declared null, never assigned
var apiToken: String? = null

// ApiRoutes.kt line 110 -- always returns null (bypass)
private fun checkAuth(session: IHTTPSession): NanoHTTPD.Response? {
    val token = apiToken ?: return null  // <-- always takes this path
    // ... auth logic below is never reached
}
```

The auth code is structurally complete (Bearer token parsing, query param fallback, 401 response with hint message) but permanently disabled. Same pattern for `rateLimiter`. The HTTP API on port 8080 is completely open.

**Remediation:** Wire `apiToken` and `rateLimiter` initialization in `WebServerService` or `LensDaemonApp`. Wire thermal throttle callbacks in `WebServerService` when both `ThermalService` and `CameraService` are bound.

### Execution Trace Results

| Feature Chain | Verdict | Notes |
|--------------|---------|-------|
| RTSP: API -> RtspServer -> RtspSession -> RTP packets | **COMPLETE** | Full RTSP protocol with SDP, transport negotiation, FU-A fragmentation |
| S3: API -> S3Client -> AWS SigV4 signed HTTP | **COMPLETE** | Real signature chain, multipart upload, streaming I/O |
| Thermal: Monitor -> Governor -> Encoder bitrate | **BROKEN** | Detection works; callback chain dead-ends at null |
| MPEG-TS: API -> MpegTsUdpPublisher -> UDP | **COMPLETE** | Real PAT/PMT/PES/TS packetization (not actual SRT despite naming) |
| Recording: API -> FileWriter -> Mp4Muxer -> MP4 | **COMPLETE** | Real MediaMuxer integration with timed segment rotation |

---

## Domain C: Interface Authenticity (30% weight)

**Domain Score: 15/21 (71.4% authenticity)**

| # | Criterion | Score | Evidence Summary |
|---|-----------|-------|-----------------|
| C1 | API Design Consistency | 2/3 | Clean `/{domain}/{action}` URL pattern. Shared `ApiHandlerUtils` enforces response shapes. Consistent JSON body parsing. Minor inconsistencies: enable/disable vs start/stop verbs; CLAUDE.md says `/api/srt/*` but code uses `/api/mpegts/*`; connection test returns 200 on failure. |
| C2 | UI Implementation Depth | 3/3 | Fully functional dashboard. Every button/slider calls real API endpoints via unified `apiCall()` function. MJPEG preview works. Snapshot triggers real blob download with generated filename. Status polling updates all UI sections. Not decorative HTML. |
| C3 | State Management (Frontend) | 2/3 | Global variables with 2-second polling interval. `fetchStatus()` updates all relevant UI elements. Cross-section synchronization works (stream state affects multiple control groups). No store pattern or reactive framework, but functional for a device dashboard. |
| C4 | Security Infrastructure (Web) | 2/3 | Auth mechanism exists (Bearer token, query param). Rate limiter class is correct (token bucket, per-client). Path traversal blocked in director scripts. Gaps: no CSRF tokens, no security headers (CSP, X-Frame-Options), no Content-Type validation, partial XSS (takes list uses raw `innerHTML` for scene names). |
| C5 | SSE/Event Stream | 1/3 | Frontend `EventSource` implementation is correct (connect, parse, reconnect on error). **Backend is broken**: uses `newFixedLengthResponse()` which sends one event then closes the connection. SSE degenerates into a 5-second reconnect poll. The right ceremony with wrong execution. |
| C6 | Error UX | 2/3 | Backend errors are specific and actionable (e.g., kiosk handler returns the exact ADB command needed). Frontend surfaces API error messages but relies entirely on `alert()` dialogs -- no inline feedback, no toast system. Graceful degradation present (director section hidden when unavailable). |
| C7 | Logging & Observability | 3/3 | 639 Timber call sites across 62 files. Consistent `TAG = "ClassName"` convention. Proper level usage (verbose for high-frequency, info for lifecycle, error for failures). Debug/release tree separation in `LensDaemonApp`. No raw `android.util.Log` in application code. |

### Key C-Domain Findings

**C5 Detail -- Broken SSE Endpoint:**

```kotlin
// DirectorApiHandler.kt line 434-451
private fun getDirectorEvents(): NanoHTTPD.Response {
    val sseData = "data: ${initialEvent.toString()}\n\n"
    // WRONG: Fixed-length response closes immediately
    return NanoHTTPD.newFixedLengthResponse(Status.OK, "text/event-stream", sseData)
}
// Should use newChunkedResponse() with PipedInputStream to hold connection open
```

The frontend correctly implements `EventSource` with reconnection logic (5-second delay). The backend has the right content type (`text/event-stream`) and headers (`Cache-Control: no-cache`, `Connection: keep-alive`) but fundamentally cannot stream because it uses a fixed-length response. This pattern -- correct ceremony, broken mechanism -- is a characteristic vibe-code signature.

**C2 Detail -- Dashboard is Genuinely Functional:**

The web dashboard (`dashboard.js`, 880+ lines) contains a real API client layer with:
- `apiCall()` wrapper with proper fetch/JSON/error handling (lines 172-191)
- Streaming start that reads actual form field values for resolution/bitrate/codec (line 315)
- Snapshot capture that creates a blob URL, generates a timestamped filename, triggers download, and revokes the URL (lines 386-407)
- Director script management with load/save/delete lifecycle
- 2-second status polling that updates connection indicators, button states, statistics, and slider positions

This is working code, not a decorative skeleton.

---

## Critical Issues Requiring Remediation

### Severity: HIGH

1. **Authentication permanently disabled** (`ApiRoutes.kt:94`)
   - `apiToken` is null and never set
   - All API endpoints are accessible without credentials on port 8080
   - Fix: Initialize `apiToken` from config/environment in `WebServerService`

2. **Rate limiting permanently disabled** (`ApiRoutes.kt:97`)
   - `rateLimiter` is null and never instantiated
   - API is vulnerable to abuse
   - Fix: Create `RateLimiter()` instance in `WebServerService.onCreate()`

3. **Thermal throttling chain broken** (`ThermalService.kt:107`)
   - Governor detects temperatures but cannot act on encoder
   - `onReduceBitrate`, `onReduceResolution`, `onReduceFramerate`, `onPauseStreaming` callbacks are all null
   - Fix: Wire callbacks in `WebServerService` when both thermal and camera services are bound

4. **Phantom dependencies inflating APK** (`app/build.gradle.kts:91,94`)
   - AWS Android SDK S3 and smbj are declared but have zero imports
   - Hand-rolled S3Client and SmbClient are used instead
   - Fix: Remove unused dependencies or replace hand-rolled code with library calls

### Severity: MEDIUM

5. **Dead config system** (`config/AppConfig.kt`, `config/ConfigManager.kt`)
   - 243 lines of configuration code that nothing uses
   - Fix: Either integrate with services or delete

6. **Data races in CameraService** (`CameraService.kt:84-85`)
   - `isPreviewActive` and `isStreamingActive` are plain `var` read from NanoHTTPD threads
   - Fix: Use `@Volatile` or `AtomicBoolean`

7. **SSE endpoint non-functional** (`DirectorApiHandler.kt:434`)
   - Uses `newFixedLengthResponse` instead of `newChunkedResponse`
   - Fix: Use `PipedInputStream` with `newChunkedResponse` for persistent connection

8. **Path traversal gap in recording deletion** (`StreamApiHandler.deleteRecording()`)
   - Filename from URI path not sanitized
   - Fix: Apply `ApiHandlerUtils.sanitizeFileName()` before use

9. **SMB NTLM auth non-functional** (`SmbClient.kt:675`)
   - Uses MD5 where MD4 is required for NTLM
   - NT response returns zeroed 24-byte placeholder instead of DES-encrypted challenge
   - Fix: Use smbj library (already declared as dependency) or implement correct NTLM

10. **`runBlocking` in HTTP handler threads** (`UploadApiHandler.kt:250,312`)
    - Blocks NanoHTTPD threads during S3/SMB connection tests
    - Fix: Use async response or increase NanoHTTPD thread pool

### Severity: LOW

11. **Documentation-code drift**: CLAUDE.md references `SrtPublisher.kt` and `/api/srt/*` that don't exist
12. **Gson underutilized**: Declared but used in only 2 of 65+ files; rest uses `org.json.JSONObject`
13. **146 generic exception catches**: Should differentiate `IOException`, `SecurityException`, etc.
14. **ThermalGovernor.listeners unsynchronized**: Mutable list accessed from multiple contexts
15. **XSS in takes list**: Scene names rendered via raw `innerHTML` without escaping

---

## Vibe-Code Signature Analysis

### Strongest AI-Generation Indicators

1. **Explicit attribution**: 30/30 implementation commits by `Claude <noreply@anthropic.com>`, all branches named `claude/*`
2. **Phantom dependencies**: Libraries declared then re-implemented from scratch -- the AI generated build.gradle first, then wrote implementations without using the declared libraries
3. **Decorative infrastructure**: Auth, rate limiting, and config systems are structurally complete but permanently disabled -- generated as part of a "security hardening phase" without integration testing
4. **Zero TODOs**: 65+ source files with zero TODO/FIXME markers is a strong AI signal; human developers invariably leave breadcrumbs
5. **Perfect uniformity**: Zero naming deviations, typos, or convention drift across the entire codebase
6. **Formulaic commits**: Sequential "Phase N:" pattern with zero reverts, WIPs, or iteration markers

### Evidence of Genuine Engineering

1. **Protocol implementations are real**: RTSP/RTP packetization with FU-A fragmentation, MPEG-TS with proper PAT/PMT/PES/CRC32, AWS SigV4 with HMAC chain -- these are not stubs
2. **Resource management is thorough**: Systematic cleanup in `onDestroy()` chains, `compareAndSet` for idempotent close, `synchronized` muxer access
3. **Test protocol coverage**: NalUnitParser and RtpPacketizer tests verify actual binary protocol correctness with specific byte arrays
4. **Security tests are adversarial**: ApiRoutesSecurityTest covers `..\` traversal, null bytes, URL encoding, unicode normalization
5. **Logging is comprehensive**: 639 Timber call sites with proper debug/release separation

### The Pattern

LensDaemon exhibits a characteristic AI-generation pattern: **deep competence in well-specified domains** (streaming protocols, binary formats, cryptographic signing) combined with **shallow competence in integration concerns** (wiring services together, enabling security controls, connecting config to behavior). The AI excels at implementing individual classes from specifications but struggles with the cross-cutting wiring that makes a system work as a whole.

---

## Appendix: Raw Scores

### Domain A Detail (20% weight)
```
A1. Commit History:       1/3 (100% Claude commits, zero human iteration)
A2. Comment Archaeology:  1/3 (zero TODOs, 189 section dividers, no WHY comments)
A3. Test Quality:         2/3 (694 tests, good protocol tests, weak config tests)
A4. Import Hygiene:       1/3 (2 phantom deps, wildcard imports, Gson barely used)
A5. Naming Consistency:   1/3 (zero deviations = suspiciously uniform)
A6. Docs vs Reality:      2/3 (mostly accurate, SrtPublisher.kt doesn't exist)
A7. Dep Utilization:      1/3 (NanoHTTPD/Timber deep, AWS/smbj phantom)
                         ----
                         9/21 = 42.9% authenticity
```

### Domain B Detail (50% weight)
```
B1. Error Handling:       1.5/3 (146 generic catches, 13 typed, sealed enums unused)
B2. Config Used:          1/3   (ConfigManager/AppConfig 100% dead code)
B3. Call Chains:          2/3   (4/5 complete, thermal broken)
B4. Async Correctness:    2/3   (good scopes, data races in booleans, runBlocking)
B5. State Management:     2/3   (StateFlow good, dual-state issue with plain vars)
B6. Security Depth:       1.5/3 (auth/rate limit decorative, credential store real)
B7. Resource Management:  2.5/3 (systematic cleanup, minor socket gaps)
                         ------
                         12.5/21 = 59.5% authenticity
```

### Domain C Detail (30% weight)
```
C1. API Consistency:      2/3 (clean patterns, doc-code drift, verb inconsistency)
C2. UI Depth:             3/3 (fully functional dashboard, real API calls)
C3. Frontend State:       2/3 (purposeful globals, polling works, no store pattern)
C4. Security Infra:       2/3 (auth+ratelimit+path-traversal exist, no CSRF/headers)
C5. SSE Implementation:   1/3 (frontend correct, backend uses wrong response type)
C6. Error UX:             2/3 (specific backend errors, alert()-only frontend)
C7. Logging:              3/3 (639 call sites, Timber everywhere, debug/release split)
                         ----
                         15/21 = 71.4% authenticity
```

### Final Calculation
```
Weighted Authenticity = (42.9% x 0.20) + (59.5% x 0.50) + (71.4% x 0.30)
                      = 8.6% + 29.8% + 21.4%
                      = 59.7%

Vibe-Code Confidence  = 100% - 59.7% = 40.3%

Classification: Substantially Vibe-Coded (36-60% band)
```
