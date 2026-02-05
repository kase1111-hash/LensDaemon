# Device Compatibility Guide

This document tracks device compatibility, known issues, and thermal profiles for LensDaemon.

## Minimum Requirements

| Requirement | Details |
|-------------|---------|
| Android version | 10+ (API 29) |
| Camera2 support | FULL or LEVEL_3 |
| Hardware encoder | H.264 (H.265 recommended) |
| RAM | 3 GB minimum |
| Wi-Fi | Required for streaming and web interface |

### Checking Camera2 Hardware Level

Connect your device via ADB and run:

```bash
adb shell dumpsys media.camera | grep "Hardware Level"
```

Acceptable values are `FULL` or `LEVEL_3`. Devices reporting `LIMITED` or `LEGACY` lack the required Camera2 capabilities (manual exposure, per-frame control, RAW output on LEVEL_3).

You can also check programmatically:

```kotlin
val characteristics = cameraManager.getCameraCharacteristics(cameraId)
val level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
// Must be CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL or LEVEL_3
```

## Tested Devices

The following devices have been tested with LensDaemon by the community. Results may vary with OS updates and regional hardware variants.

### Google Pixel

| Device | Android | Camera2 Level | H.265 | Multi-Lens | Notes | Status |
|--------|---------|---------------|-------|------------|-------|--------|
| Pixel 5 | 13-14 | FULL | Yes | Wide + Ultrawide | Stable at 1080p30 | Community tested |
| Pixel 6 | 13-14 | LEVEL_3 | Yes | Wide + Ultrawide | Tensor chip runs warm; monitor thermals | Community tested |
| Pixel 7 | 13-14 | LEVEL_3 | Yes | Wide + Ultrawide | Good thermal performance | Community tested |
| Pixel 8 | 14 | LEVEL_3 | Yes | Wide + Ultrawide + Macro | Best Pixel thermal behavior | Community tested |

### Samsung Galaxy S

| Device | Android | Camera2 Level | H.265 | Multi-Lens | Notes | Status |
|--------|---------|---------------|-------|------------|-------|--------|
| Galaxy S20 | 13 | FULL | Yes | Wide + Ultrawide + Tele | Aggressive vendor thermal throttling | Community tested |
| Galaxy S21 | 13-14 | FULL | Yes | Wide + Ultrawide + Tele | Similar throttling to S20 | Community tested |
| Galaxy S22 | 13-14 | FULL | Yes | Wide + Ultrawide + Tele | Exynos variant runs hotter than Snapdragon | Community tested |
| Galaxy S23 | 14 | FULL | Yes | Wide + Ultrawide + Tele | Best Samsung thermal performance | Community tested |

### OnePlus

| Device | Android | Camera2 Level | H.265 | Multi-Lens | Notes | Status |
|--------|---------|---------------|-------|------------|-------|--------|
| OnePlus 8 | 13 | FULL | Yes | Wide + Ultrawide + Macro | OxygenOS thermal management is moderate | Community tested |
| OnePlus 9 | 13-14 | FULL | Yes | Wide + Ultrawide | Hasselblad tuning may affect AWB behavior | Community tested |
| OnePlus 10 Pro | 13-14 | FULL | Yes | Wide + Ultrawide + Tele | Good thermal dissipation | Community tested |
| OnePlus 11 | 14 | FULL | Yes | Wide + Ultrawide + Tele | Strong encoder performance | Community tested |

## Known Issues and Manufacturer Quirks

### Samsung

- **Aggressive thermal throttling.** Samsung devices have vendor-level thermal management that can throttle the CPU and GPU independently of Android's thermal API. LensDaemon may not detect these throttle events through standard `PowerManager` thermal callbacks. The `AGGRESSIVE` thermal preset is recommended for Samsung devices in warm environments.
- **Exynos vs. Snapdragon.** Exynos variants (common in Europe/Asia) tend to run hotter than Snapdragon variants under sustained encoding workloads. Check your chipset with `adb shell getprop ro.hardware`.
- **Camera switching delay.** Samsung's Camera2 implementation adds a noticeable delay (~200-500ms) when switching between rear camera lenses compared to Pixel devices.

### Google Pixel

- **USB camera switching.** When a USB camera or accessory is connected, some Pixel devices may re-enumerate camera IDs, causing LensDaemon to lose its reference to the active camera. Disconnect USB accessories before starting a streaming session, or restart the camera service after connecting.
- **Tensor thermal behavior.** Pixel 6 and newer use Google Tensor chips, which run warmer than Qualcomm equivalents under sustained H.265 encoding. The `DEFAULT` thermal profile works well, but consider `AGGRESSIVE` for 4K encoding sessions.
- **Night Sight interference.** Pixel's computational photography pipeline is not used by LensDaemon (which accesses Camera2 directly), but some background camera processes may briefly interfere with session startup.

### OnePlus

- **OxygenOS camera HAL.** Some OxygenOS versions expose additional camera modes that appear as separate camera IDs. LensDaemon filters these during enumeration, but if unexpected cameras appear, check the camera list in the web dashboard.
- **AWB color cast.** OnePlus 9 series devices with Hasselblad color tuning may produce slightly different white balance results compared to stock Android. Use manual white balance mode if color consistency is critical.

## Thermal Profiles

LensDaemon includes three built-in thermal presets in `ThermalConfig.kt`. Choose a preset based on your deployment environment:

### DEFAULT

Balanced settings suitable for most indoor environments.

| Parameter | CPU | Battery |
|-----------|-----|---------|
| Normal max | 45C | 38C |
| Warning | 50C | 42C |
| Critical | 55C | 45C |
| Emergency | 60C | 48C |
| Battery target charge | 50% | |

Actions at each level:
- **Warning** -- Reduce bitrate by 20%; disable charging
- **Critical** -- Reduce resolution and framerate; alert user
- **Emergency** -- Pause streaming; alert user

### AGGRESSIVE

Lower thresholds for hot environments, direct sunlight, or devices with poor thermal dissipation (e.g., Samsung Exynos variants).

| Parameter | CPU | Battery |
|-----------|-----|---------|
| Normal max | 40C | 35C |
| Warning | 45C | 38C |
| Critical | 50C | 42C |
| Emergency | 55C | 45C |
| Battery target charge | 40% | |

### RELAXED

Higher thresholds for devices with active cooling (fans, heatsinks) or cooled enclosures.

| Parameter | CPU | Battery |
|-----------|-----|---------|
| Normal max | 50C | 42C |
| Warning | 55C | 45C |
| Critical | 60C | 48C |
| Emergency | 65C | 52C |
| Battery target charge | 60% | |

Set the thermal profile via the REST API:

```bash
curl -X PUT http://{device-ip}:8080/api/thermal/config \
  -H "Content-Type: application/json" \
  -d '{"preset": "AGGRESSIVE"}'
```

## Contributing a Device Profile

If you test LensDaemon on a device not listed above, please submit a PR or open an issue with the following information:

1. **Device model and variant** (e.g., Samsung Galaxy S23 Ultra, Snapdragon, SM-S918U)
2. **Android version** and security patch level
3. **Camera2 hardware level** (output of `adb shell dumpsys media.camera | grep "Hardware Level"`)
4. **Available lenses** (wide, ultrawide, telephoto, macro)
5. **H.265 support** (yes/no)
6. **Thermal behavior** -- Run a 30-minute 1080p30 streaming session and note:
   - Peak CPU temperature
   - Peak battery temperature
   - Whether thermal throttling occurred
   - Which thermal preset worked best
7. **Known quirks** -- Any camera switching delays, encoder issues, or unexpected behavior
8. **Recommended thermal preset** (DEFAULT, AGGRESSIVE, or RELAXED)

### Gathering Thermal Data

Start a streaming session and monitor temperatures via the REST API:

```bash
# Check current temperatures
curl http://{device-ip}:8080/api/thermal/status

# Get temperature history (after a session)
curl http://{device-ip}:8080/api/thermal/history

# Get statistics (min/max/average)
curl http://{device-ip}:8080/api/thermal/stats
```

Or view the thermal data in real time on the web dashboard at `http://{device-ip}:8080`.
