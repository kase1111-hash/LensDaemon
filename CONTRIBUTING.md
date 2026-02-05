# Contributing to LensDaemon

Thank you for your interest in contributing to LensDaemon. This guide covers everything you need to get started.

## Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or newer
- **JDK 17** (required by the Kotlin/Gradle toolchain)
- **Android SDK 34** (compileSdk and targetSdk)
- **Android device** running Android 10+ (API 29) with Camera2 FULL or LEVEL_3 support
- **ADB** configured and device connected via USB or Wi-Fi

Verify your device's Camera2 support level:

```bash
adb shell dumpsys media.camera | grep "Hardware Level"
```

The output must show `FULL` or `LEVEL_3`. Devices reporting `LIMITED` or `LEGACY` are not supported.

## Building the Project

```bash
# Full build (compile + test + lint)
./gradlew build

# Debug APK only
./gradlew assembleDebug

# Release APK
./gradlew assembleRelease

# Install debug build on connected device
./gradlew installDebug
```

## Testing

### Running Tests

```bash
# Run all unit tests
./gradlew test

# Run tests for a specific module
./gradlew :app:testDebugUnitTest
```

### Test Structure

Tests live under `app/src/test/java/com/lensdaemon/` and mirror the main source tree:

```
app/src/test/java/com/lensdaemon/
├── director/
│   ├── RemoteLlmClientTest.kt
│   ├── ScriptParserTest.kt
│   └── ShotMapperTest.kt
├── encoder/
│   └── NalUnitParserTest.kt
├── output/
│   └── RtpPacketizerTest.kt
├── storage/
│   ├── RetentionPolicyTest.kt
│   └── UploadTaskTest.kt
├── thermal/
│   └── ThermalConfigTest.kt
└── web/
    └── ApiRoutesSecurityTest.kt
```

### Adding New Tests

1. Place test files in the corresponding package under `app/src/test/java/com/lensdaemon/`.
2. Name test classes with a `Test` suffix (e.g., `MyFeatureTest.kt`).
3. Use JUnit 4 annotations (`@Test`, `@Before`, `@After`).
4. Keep tests focused -- one logical assertion per test method where practical.

## Code Style

LensDaemon follows the official [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).

### Linting with detekt

The project uses [detekt](https://detekt.dev/) for static analysis. Configuration lives at `config/detekt/detekt.yml`.

```bash
# Run detekt
./gradlew detekt
```

All code must pass detekt before merging. Fix any reported issues or, if a rule is genuinely inapplicable, suppress it with a comment explaining why.

### General Guidelines

- Use meaningful names; avoid abbreviations except for well-known terms (e.g., `SPS`, `NAL`, `RTSP`).
- Prefer `val` over `var`. Use immutable data classes for configuration and state snapshots.
- Use Kotlin coroutines and `StateFlow` for asynchronous work and state observation.
- Keep classes focused on a single responsibility.

## Architecture Overview

LensDaemon is built around independent services that communicate through well-defined interfaces:

| Service | Package | Responsibility |
|---------|---------|----------------|
| Camera | `camera/` | Camera2 pipeline, lens switching, focus/exposure/zoom control |
| Encoder | `encoder/` | H.264/H.265 hardware encoding, adaptive bitrate |
| Output | `output/` | RTSP server, RTP packetization, MP4 muxing, file writer |
| Storage | `storage/` | Local storage, S3 uploads, SMB shares, retention policies |
| Thermal | `thermal/` | Temperature monitoring, throttling governors, battery bypass |
| Web | `web/` | HTTP server (port 8080), REST API, MJPEG preview, dashboard |
| Kiosk | `kiosk/` | Device Owner lockdown, screen control, boot autostart |
| Director | `director/` | AI-powered script-driven camera automation |

Each service runs as an Android foreground service with its own lifecycle. The `WebServerService` acts as the coordination layer, binding to other services and exposing their functionality through the REST API and web dashboard.

## Pull Request Guidelines

1. **Describe what and why.** Summarize the change and the motivation behind it. Link to any relevant issues.
2. **Include tests.** New features and bug fixes should come with unit tests. If your change touches camera or encoding logic that is hard to unit-test, document what you tested manually.
3. **Pass detekt.** Run `./gradlew detekt` before pushing. PRs that fail static analysis will not be merged.
4. **Pass the full build.** Run `./gradlew build` to verify compilation, tests, and lint all pass.
5. **Keep PRs focused.** One logical change per PR. Split large features into incremental PRs when possible.
6. **Follow existing patterns.** Look at nearby code for conventions around data classes, builders, StateFlow usage, and service structure.

## Priority Contribution Areas

We especially welcome contributions in the following areas:

### Device Profiles
Test LensDaemon on specific hardware and document thermal behavior, encoder quirks, and Camera2 capabilities. See `docs/DEVICES.md` for the device compatibility guide.

### Thermal Tuning
Help find sustainable encoding settings for specific devices. The thermal system supports `AGGRESSIVE`, `DEFAULT`, and `RELAXED` presets in `ThermalConfig.kt` -- real-world data from long-running sessions is valuable.

### Protocol Support
- **ONVIF** -- Compatibility with IP camera management systems
- **NDI** -- NewTek NDI for local network video production
- **WebRTC** -- Browser-based low-latency preview and control

### Storage Backends
Additional cloud or network storage targets beyond the current S3-compatible and SMB/CIFS support.

## Device Owner Setup for Kiosk Mode Testing

Kiosk mode requires Device Owner privileges. This must be set up on a factory-reset device or one with no Google accounts configured:

```bash
# Set device owner (required for kiosk mode)
adb shell dpm set-device-owner com.lensdaemon/.AdminReceiver

# Verify device owner status
adb shell dumpsys device_policy | grep "Device Owner"

# Remove device owner (to restore normal device use)
adb shell dpm remove-active-admin com.lensdaemon/.AdminReceiver
```

**Important:** Setting device owner will fail if any accounts (Google, Samsung, etc.) are registered on the device. Either factory-reset or remove all accounts first.

## Questions?

Open an issue on the repository if you have questions about the codebase, need help getting set up, or want to discuss a proposed change before starting work.
