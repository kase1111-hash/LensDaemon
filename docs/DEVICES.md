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

- **Aggressive thermal throttling.** Samsung devices have vendor-level thermal management that can throttle the CPU and GPU independently of Android's thermal API. LensDaemon may not detect these throttle events through standard `PowerManager` thermal callbacks. The `AGGRESSIVE` thresholds (see [Thermal Profiles](#thermal-profiles)) are recommended for Samsung devices in warm environments.
- **Exynos vs. Snapdragon.** Exynos variants (common in Europe/Asia) tend to run hotter than Snapdragon variants under sustained encoding workloads. Check your chipset with `adb shell getprop ro.hardware`.
- **Camera switching delay.** Samsung's Camera2 implementation adds a noticeable delay (~200-500ms) when switching between rear camera lenses compared to Pixel devices.

### Google Pixel

- **USB camera switching.** When a USB camera or accessory is connected, some Pixel devices may re-enumerate camera IDs, causing LensDaemon to lose its reference to the active camera. Disconnect USB accessories before starting a streaming session, or restart the camera service after connecting.
- **Tensor thermal behavior.** Pixel 6 and newer use Google Tensor chips, which run warmer than Qualcomm equivalents under sustained H.265 encoding. The bundled Pixel 6, 7 and 8 profiles (applied automatically) already set lower CPU thresholds than the generic ones; consider the `AGGRESSIVE` thresholds for 4K encoding sessions.
- **Night Sight interference.** Pixel's computational photography pipeline is not used by LensDaemon (which accesses Camera2 directly), but some background camera processes may briefly interfere with session startup.

### OnePlus

- **OxygenOS camera HAL.** Some OxygenOS versions expose additional camera modes that appear as separate camera IDs. LensDaemon filters these during enumeration, but if unexpected cameras appear, check the camera list in the web dashboard.
- **AWB color cast.** OnePlus 9 series devices with Hasselblad color tuning may produce slightly different white balance results compared to stock Android. Use manual white balance mode if color consistency is critical.

## Thermal Profiles

LensDaemon reads the CPU and battery temperatures every 5 seconds and throttles
in steps. The thresholds come from a thermal profile:

- **Bundled profiles** for the Pixel 5, 6, 7 and 8, Galaxy S22, S23, S24+ and A54,
  OnePlus 11 and Xiaomi 13 (`app/src/main/assets/thermal_profiles.json`). The one
  for your phone is picked by its model name, device name or chip when the app
  starts.
- **Generic** thresholds (the DEFAULT set below) on every other phone.
- **Your own thresholds**, saved as an override with `PUT /api/thermal/profile`.
  They apply at once and after restarts, until `DELETE /api/thermal/profile`
  goes back to the bundled or generic profile.

See which profile is in force, and the thresholds in use:

```bash
curl http://{device-ip}:8080/api/thermal/profile
```

### What happens at each level

| Level | What LensDaemon does |
|-------|----------------------|
| Elevated (5°C below CPU Warning, 4°C below battery Warning) | Nothing |
| CPU Warning | Bitrate cut by 20% |
| CPU Critical | Bitrate cut by 40%; the encoder restarts one resolution step down (4K to 1080p, 1080p to 720p) at no more than 24 fps, keeping the outputs connected |
| Battery Warning or above | Charging held, on phones whose charging switch the app can write; elsewhere the dashboard and API advise unplugging the charger |
| CPU or battery Emergency | Every output (RTSP, MPEG-TS, recording) stops, and starts again by itself once neither is at Emergency |

A level is left only 3°C (CPU) or 2°C (battery) below its threshold, so the
throttling does not switch on and off around a threshold: with the DEFAULT
thresholds, outputs paused at 60°C restart once the CPU is below 57°C. The
event log (`GET /api/thermal/events`) marks rises to Critical and Emergency
with `ALERT_USER`; that is a log marker only, no alert is posted.

### Battery charge limiting

On a phone whose kernel offers a charging switch the app itself may write (no
root), LensDaemon holds the battery at 50%: it stops charging there and charges
again below 40%. Most phones have no such switch; there LensDaemon cannot stop
charging and tells you instead when to unplug the charger (the dashboard's
temperature panel, and `recommendation` in `GET /api/thermal/battery`). The
50%/40% levels are not part of a thermal profile.

### DEFAULT

The generic thresholds, balanced for most indoor environments.

| Threshold | CPU | Battery |
|-----------|-----|---------|
| Warning | 50°C | 42°C |
| Critical | 55°C | 45°C |
| Emergency | 60°C | 48°C |

### AGGRESSIVE

Lower thresholds for hot environments, direct sunlight, or devices with poor
thermal dissipation (e.g., Samsung Exynos variants).

| Threshold | CPU | Battery |
|-----------|-----|---------|
| Warning | 45°C | 38°C |
| Critical | 50°C | 42°C |
| Emergency | 55°C | 45°C |

Save it as an override:

```bash
curl -X PUT http://{device-ip}:8080/api/thermal/profile \
  -H 'Content-Type: application/json' \
  -d '{"displayName":"Hot room","cpuWarnC":45,"cpuCriticalC":50,"cpuEmergencyC":55,"batteryWarnC":38,"batteryCriticalC":42,"batteryEmergencyC":45}'
```

### RELAXED

Higher thresholds for devices with active cooling (fans, heatsinks) or cooled
enclosures.

| Threshold | CPU | Battery |
|-----------|-----|---------|
| Warning | 55°C | 45°C |
| Critical | 60°C | 48°C |
| Emergency | 65°C | 52°C |

```bash
curl -X PUT http://{device-ip}:8080/api/thermal/profile \
  -H 'Content-Type: application/json' \
  -d '{"displayName":"Cooled enclosure","cpuWarnC":55,"cpuCriticalC":60,"cpuEmergencyC":65,"batteryWarnC":45,"batteryCriticalC":48,"batteryEmergencyC":52}'
```

Send all six thresholds: one left out takes the DEFAULT value, not the one in
force. Each set must rise from Warning to Critical to Emergency. Go back to the
bundled or generic profile with:

```bash
curl -X DELETE http://{device-ip}:8080/api/thermal/profile
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
   - Whether thermal throttling occurred, and at which levels (`GET /api/thermal/events`)
   - Which thresholds worked best
7. **Known quirks** -- Any camera switching delays, encoder issues, or unexpected behavior
8. **Recommended thresholds** for a bundled profile: CPU and battery Warning, Critical and Emergency, plus the bitrate, resolution and frame rate the phone sustains (the fields of an entry in `app/src/main/assets/thermal_profiles.json`)
9. **Chip model**, from `GET /api/thermal/profile/device`

### Gathering Thermal Data

Start a streaming session and monitor temperatures via the REST API:

```bash
# Check current temperatures
curl http://{device-ip}:8080/api/thermal/status

# Get temperature history (one entry a minute, last 24 hours)
curl http://{device-ip}:8080/api/thermal/history

# Get statistics (min/max/average, time at each level)
curl http://{device-ip}:8080/api/thermal/stats

# Get the level changes and the throttling they caused
curl http://{device-ip}:8080/api/thermal/events
```

The history and the events are kept on the phone across app restarts.

Or view the thermal data in real time on the web dashboard at `http://{device-ip}:8080`.
