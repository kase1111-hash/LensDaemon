# LensDaemon Remediation Plan

**Date:** 2026-02-24
**Based on:** VIBE_CHECK_AUDIT.md (Vibe-Code Detection Audit v2.0, score 40.3%)
**Goal:** Address all findings to bring Vibe-Code Confidence below 20% (AI-Assisted range)

---

## Overview

The remediation is organized into 4 phases, ordered by severity and dependency. Each phase is independently shippable and testable. Estimated scope: ~15 files modified, ~2 files deleted, ~200 lines added, ~50 lines removed.

| Phase | Focus | Severity | Files Changed |
|-------|-------|----------|---------------|
| 1 | Security: Wire auth, rate limiting, fix path traversal | HIGH | 3 files |
| 2 | Safety: Wire thermal throttling, fix data races | HIGH | 3 files |
| 3 | Correctness: Fix SMB auth, SSE endpoint, runBlocking | HIGH/MEDIUM | 3 files |
| 4 | Cleanup: Remove dead code, phantom deps, fix docs | LOW | 5+ files |

---

## Phase 1: Security Hardening (Wire Auth + Rate Limiting + Path Traversal)

These are the most critical findings -- the API is completely open on port 8080 with no access control.

### 1.1 Wire RateLimiter and API Token in WebServerService

**File:** `app/src/main/java/com/lensdaemon/web/WebServerService.kt`
**Location:** `onCreate()`, after line 172 (`mjpegStreamer = MjpegStreamer()`)

**Changes:**

```kotlin
// After line 172, add:
apiRoutes?.rateLimiter = RateLimiter()  // defaults: 60 burst, 10/sec refill

// Load API token from SharedPreferences (null = auth disabled for first-boot setup)
val prefs = getSharedPreferences("lensdaemon_security", MODE_PRIVATE)
apiRoutes?.apiToken = prefs.getString("api_token", null)
```

**Add a new method** to allow setting the token via the setup flow:

```kotlin
fun setApiToken(token: String?) {
    val prefs = getSharedPreferences("lensdaemon_security", MODE_PRIVATE)
    if (token.isNullOrEmpty()) {
        prefs.edit().remove("api_token").apply()
    } else {
        prefs.edit().putString("api_token", token).apply()
    }
    apiRoutes?.apiToken = token
}
```

**Add import:** `import com.lensdaemon.web.RateLimiter`

**Rationale:** `RateLimiter()` zero-arg constructor is confirmed working (tested in `RateLimiterTest.kt:125`). The token starts as `null` (auth disabled) for first-boot, allowing the user to set it via the setup wizard or `PUT /api/config`. Once set, all non-public endpoints require `Authorization: Bearer <token>`.

**No changes needed to ApiRoutes.kt** -- the `checkAuth()` and `checkRateLimit()` methods are already complete; they just need non-null values.

### 1.2 Fix Path Traversal in deleteRecording()

**File:** `app/src/main/java/com/lensdaemon/web/handlers/StreamApiHandler.kt`
**Location:** `deleteRecording()` method, line 437

**Current code (line 437):**
```kotlin
val filename = uri.substringAfterLast("/")
```

**Replace with:**
```kotlin
val rawFilename = uri.substringAfterLast("/")
val filename = ApiHandlerUtils.sanitizeFileName(rawFilename)
    ?: return ApiHandlerUtils.errorJson(
        NanoHTTPD.Response.Status.BAD_REQUEST,
        "Invalid filename"
    )
```

**Also fix the JSON response** at line 457 to use `JSONObject` instead of string interpolation (prevents JSON injection):
```kotlin
// Replace: """{"success": $success, "filename": "$filename"}"""
// With:
JSONObject().apply {
    put("success", success)
    put("filename", filename)
}.toString()
```

### 1.3 Add @Volatile to ApiRoutes Service References

**File:** `app/src/main/java/com/lensdaemon/web/ApiRoutes.kt`
**Location:** Lines 60-88

**Change:** Add `@Volatile` to all mutable service references that are set from the main thread and read from NanoHTTPD threads:

```kotlin
@Volatile var cameraService: CameraService? = null
@Volatile var uploadService: UploadService? = null
@Volatile var thermalGovernor: ThermalGovernor? = null
@Volatile var kioskManager: KioskManager? = null
@Volatile var directorManager: DirectorManager? = null
@Volatile var apiToken: String? = null
@Volatile var rateLimiter: RateLimiter? = null
```

---

## Phase 2: Safety (Thermal Throttling + Data Races)

The thermal system detects dangerous temperatures but cannot act on them. Fixing this is critical for an always-on streaming appliance.

### 2.1 Wire Thermal Throttle Callbacks

**File:** `app/src/main/java/com/lensdaemon/web/WebServerService.kt`
**Location:** `thermalConnection.onServiceConnected()`, after line 145

**Add callback wiring:**

```kotlin
// Wire thermal throttle callbacks to camera service
thermalService?.onReduceBitrate = { percent ->
    val camera = cameraService ?: return@onReduceBitrate
    val currentBitrate = camera.getEncoderStats()?.bitrateBps ?: 4_000_000
    val newBitrate = (currentBitrate * (100 - percent) / 100).coerceAtLeast(500_000)
    camera.updateEncoderBitrate(newBitrate)
    Timber.w("$TAG: Thermal throttle: reduced bitrate by $percent% to $newBitrate bps")
}

thermalService?.onPauseStreaming = {
    cameraService?.stopStreaming()
    Timber.w("$TAG: Thermal throttle: paused streaming")
}

thermalService?.onResumeStreaming = {
    Timber.i("$TAG: Thermal throttle: resume streaming (requires manual restart)")
}

thermalService?.onRestoreSettings = {
    Timber.i("$TAG: Thermal throttle: settings restored to normal")
}
```

**Also wire cleanup in `thermalConnection.onServiceDisconnected()`**, after line 153:

```kotlin
thermalService?.onReduceBitrate = null
thermalService?.onPauseStreaming = null
thermalService?.onResumeStreaming = null
thermalService?.onRestoreSettings = null
```

**Rationale:** `onReduceBitrate` is the most critical callback (prevents thermal runaway). `onPauseStreaming` is the emergency stop. `onReduceResolution` and `onReduceFramerate` are skipped for now because CameraService lacks `setFramerate()` and runtime resolution changes require re-creating the encoder (risky mid-stream). These can be added in a follow-up.

### 2.2 Fix Data Races in CameraService

**File:** `app/src/main/java/com/lensdaemon/camera/CameraService.kt`
**Location:** Lines 84-85, 96-98

**Change:** Add `@Volatile` to all fields read from HTTP threads:

```kotlin
@Volatile private var isPreviewActive = false
@Volatile private var isStreamingActive = false
@Volatile private var encoderService: EncoderService? = null
@Volatile private var encoderBound = false
```

**Rationale:** `@Volatile` is the minimal fix that guarantees visibility across threads. Converting to `MutableStateFlow<Boolean>` would be more idiomatic but touches many more call sites -- save that for a larger refactor.

---

## Phase 3: Correctness (SMB Auth + SSE + runBlocking)

### 3.1 Replace Hand-Rolled SmbClient with smbj Library

**File:** `app/src/main/java/com/lensdaemon/storage/SmbClient.kt`

**Problem:** The hand-rolled SMB2/NTLM implementation has two fatal bugs:
1. `calculateNtResponse()` (line 670) returns 24 zero bytes (placeholder)
2. `md4Hash()` (line 675) uses MD5 instead of MD4

**Fix:** Rewrite `SmbClient.kt` to use the `smbj` library that is already declared in `build.gradle.kts`. This converts a phantom dependency into an actual dependency and fixes authentication in one step.

**New implementation structure:**

```kotlin
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare

class SmbClient(private val credentials: SmbCredentials) {
    private val client = SMBClient()

    suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            client.connect(credentials.server).use { connection ->
                val authContext = AuthenticationContext(
                    credentials.username,
                    credentials.password.toCharArray(),
                    credentials.domain
                )
                connection.authenticate(authContext).use { session ->
                    session.connectShare(credentials.share).use { share ->
                        (share as DiskShare).list("").isNotEmpty()
                    }
                }
            }
        }
    }

    suspend fun uploadFile(localFile: File, remotePath: String,
                          onProgress: ((Long, Long) -> Unit)? = null): Result<SmbUploadResult> =
        withContext(Dispatchers.IO) {
            // Use smbj's DiskShare.openFile() + write with progress tracking
            ...
        }
}
```

**Keep:** The existing `SmbCredentials` data class, `SmbUploadResult`, and the public API surface.
**Remove:** All hand-rolled SMB2 protocol code (SMB2 header construction, NTLM authenticate, packet framing -- ~500 lines).

### 3.2 Fix SSE Endpoint to Use Chunked Streaming

**File:** `app/src/main/java/com/lensdaemon/web/handlers/DirectorApiHandler.kt`
**Location:** `getDirectorEvents()` method, lines 434-451

**Replace** the entire method with a proper SSE implementation using the `PipedInputStream`/`PipedOutputStream` pattern already proven in `MjpegStreamer.kt`:

```kotlin
private fun getDirectorEvents(): NanoHTTPD.Response {
    val director = directorManager
        ?: return ApiHandlerUtils.serviceUnavailable("Director service")

    val pipedOutput = PipedOutputStream()
    val pipedInput = PipedInputStream(pipedOutput)

    // Launch coroutine to push events
    CoroutineScope(Dispatchers.IO).launch {
        try {
            // Send initial state snapshot
            val status = director.getStatus()
            val initial = JSONObject().apply {
                put("type", "state")
                put("enabled", status.enabled)
                put("state", status.state.name)
                put("scene", status.currentScene)
                put("cue", status.currentCue)
                put("take", status.currentTake)
            }
            pipedOutput.write("data: $initial\n\n".toByteArray())
            pipedOutput.flush()

            // Collect ongoing events from DirectorManager
            director.events.collect { event ->
                val json = eventToJson(event)
                pipedOutput.write("data: $json\n\n".toByteArray())
                pipedOutput.flush()
            }
        } catch (e: Exception) {
            Timber.d("$TAG: SSE client disconnected")
        } finally {
            pipedOutput.close()
        }
    }

    return NanoHTTPD.newChunkedResponse(
        NanoHTTPD.Response.Status.OK,
        "text/event-stream",
        pipedInput
    ).apply {
        addHeader("Cache-Control", "no-cache")
        addHeader("Connection", "keep-alive")
        addHeader("Access-Control-Allow-Origin", "*")
    }
}
```

**Add helper** `eventToJson(event: DirectorEvent): JSONObject` to convert each event type.

### 3.3 Fix runBlocking in Upload API Handlers

**File:** `app/src/main/java/com/lensdaemon/web/handlers/UploadApiHandler.kt`
**Locations:** `testS3Connection()` (line 250), `testSmbConnection()` (line 312)

**Change:** Wrap with a timeout to prevent indefinite blocking of NanoHTTPD threads:

```kotlin
// Replace: return runBlocking { ... }
// With:
return runBlocking {
    withTimeout(15_000) {  // 15-second max for connection tests
        val result = upload.testS3Connection()
        // ... existing response building
    }
}
```

Apply to both `testS3Connection()` and `testSmbConnection()`.

**Add import:** `import kotlinx.coroutines.withTimeout`

**Rationale:** A full async pattern (202 Accepted + polling) would be ideal but is a larger change. Adding `withTimeout` prevents the worst case (30+ second thread blocking) while keeping the synchronous response pattern that the frontend expects.

---

## Phase 4: Cleanup (Dead Code + Phantom Deps + Docs)

### 4.1 Remove Dead Config System

**Delete these files:**
- `app/src/main/java/com/lensdaemon/config/ConfigManager.kt` (71 lines)
- `app/src/main/java/com/lensdaemon/config/AppConfig.kt` (172 lines)

**Verification:** Grep confirms zero imports of `com.lensdaemon.config` outside these files. Each service manages its own config independently.

### 4.2 Remove Phantom AWS SDK Dependency

**File:** `app/build.gradle.kts`
**Location:** Line 91

**Remove:**
```kotlin
// AWS S3 SDK (for S3-compatible storage)
implementation("com.amazonaws:aws-android-sdk-s3:2.73.0")
```

**Also remove** the AWS SDK ProGuard rules if any exist in `proguard-rules.pro`.

**Verification:** Zero imports of `com.amazonaws` in any `.kt` file. The hand-rolled `S3Client.kt` uses `java.net.HttpURLConnection` and `javax.crypto.Mac` exclusively.

### 4.3 Keep smbj Dependency (Now Actually Used)

After Phase 3.1 rewrites `SmbClient.kt` to use smbj, this dependency is no longer phantom. No change needed.

### 4.4 Fix Documentation-Code Drift

**File:** `CLAUDE.md`

**Changes:**
1. Replace all `/api/srt/*` references with `/api/mpegts/*` (lines 89-91, 1095-1100)
2. Replace `SrtPublisher.kt` with `MpegTsUdpPublisher.kt` (line 1082)
3. Update `StreamApiHandler.kt` comment from `/api/srt/*` to `/api/mpegts/*` (line 1129)
4. Add a note that MPEG-TS/UDP is not actual SRT (no encryption, ARQ, or congestion control)

### 4.5 Remove Unused Gson Dependency (Optional)

**File:** `app/build.gradle.kts`, line 88

Gson is used in only 2 files (`AppConfig.kt` and `ThermalProfile.kt`). After deleting `AppConfig.kt` in 4.1, check if `ThermalProfile.kt` can use `org.json.JSONObject` instead. If so, remove:
```kotlin
implementation("com.google.code.gson:gson:2.10.1")
```

---

## Validation Checklist

After each phase, verify:

- [ ] `./gradlew build` passes
- [ ] `./gradlew test` passes
- [ ] `./gradlew detekt` passes (maxIssues: 0)
- [ ] `./gradlew lint` passes

### Phase 1 Specific Validation
- [ ] API returns 429 when rate limit exceeded (test with rapid requests)
- [ ] API returns 401 when token is set and request lacks auth header
- [ ] `DELETE /api/recordings/../../etc/passwd` returns 400 Bad Request
- [ ] Public endpoints (`/api/status`, `/api/device`) remain accessible without auth

### Phase 2 Specific Validation
- [ ] `GET /api/thermal/status` shows temperature readings
- [ ] When CPU temp > warning threshold, encoder bitrate is actually reduced
- [ ] No stale reads of `isStreamingActive` from HTTP threads under concurrent load

### Phase 3 Specific Validation
- [ ] SMB connection test succeeds against a real SMB server with valid credentials
- [ ] SSE endpoint (`/api/director/events`) holds connection open and pushes events
- [ ] Upload connection test returns within 15 seconds even if server is unreachable

### Phase 4 Specific Validation
- [ ] Build succeeds after removing AWS SDK dependency
- [ ] No references to deleted config files
- [ ] CLAUDE.md `/api/mpegts/*` endpoints match actual implementation

---

## Expected Impact on Vibe-Code Score

| Domain | Current | Expected After |
|--------|---------|----------------|
| A: Surface Provenance | 42.9% | ~55% (phantom deps fixed, docs aligned) |
| B: Behavioral Integrity | 59.5% | ~78% (auth real, thermal wired, config cleaned, SMB works) |
| C: Interface Authenticity | 71.4% | ~82% (SSE works, security real, path traversal fixed) |
| **Weighted Authenticity** | **59.7%** | **~74%** |
| **Vibe-Code Confidence** | **40.3%** | **~26%** (AI-Assisted range) |

The biggest score improvement comes from Domain B (50% weight) where wiring auth, thermal callbacks, and fixing SMB converts "decorative infrastructure" into "functional infrastructure" -- the primary characteristic that separates AI-assisted code from vibe-coded code.
