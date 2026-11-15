# LensDaemon

**Your phone forgot it was a phone.**

Transform any Android device into a dedicated video appliance — streaming camera, security monitor, or recording endpoint — with better optics than any purpose-built webcam on the market.

---

## The Problem

You own a $200 webcam with a tiny sensor, fixed lens, and mediocre low-light performance.

You also have an old phone in a drawer with:
- A sensor 5x larger than that webcam
- Multiple lenses (wide, main, telephoto)
- Optical image stabilization
- Computational photography that destroys IR-based security cams in low light
- Hardware H.264/H.265 encoding
- A battery backup built in

The phone is objectively better hardware. But using it as a camera is janky — it overheats, the battery bloats, apps crash, and there's 200ms of latency because Android is busy checking notifications.

## The Solution

LensDaemon strips away everything that makes a phone a phone and leaves you with a dedicated video appliance.

- **No notifications.** No background sync. No distractions.
- **Thermal-aware encoding** that throttles bitrate before the CPU throttles itself.
- **Battery care** for 24/7 operation: holds the charge at 50% on phones whose charging switch the app can write; elsewhere it tells you when to unplug the charger.
- **Network-controlled** via web browser. No app on your main device needed.

One APK. Plug in power. Point at thing. Stream forever.

---

## Features

| Feature | Description |
|---------|-------------|
| **Dual Protocol Streaming** | RTSP for universal compatibility, MPEG-TS over UDP for low-latency ingest |
| **Multi-Lens Control** | Switch wide/main/tele from any browser on your network |
| **Network Storage** | Upload recordings to SMB shares or S3-compatible buckets, from the dashboard |
| **Thermal Dashboard** | Real-time CPU/battery temps, throttle state, encoding stats |
| **Kiosk Mode** | Locks the phone to LensDaemon (Device Owner) — comes back after reboots and crashes, exit gesture with optional PIN |
| **Web Control Panel** | Dashboard, live preview, full API at `http://{device-ip}:8080` |
| **Zero Cloud Dependency** | Your footage stays on your network unless you decide otherwise |
| **AI Director Mode** | Script-driven camera control — lens, zoom, focus respond to your scene cues |

---

## AI Director Mode (Experimental)

**Script your shots; the phone runs them.**

Write a script with scene cues, load it from the dashboard, and the AI Director runs it on the phone, cue by cue:

```
[SCENE: Arrival]
[SHOT: WIDE]
[HOLD: 3]
[CUT TO: CLOSE-UP]
[FOCUS: FACE]
[HOLD: 2]
[TRANSITION: PULL BACK] - 2s
```

**What it does:**
- Parses scene and shot cues, and reports the lines it could not use
- Switches lenses (wide → main → telephoto) and zooms for each shot type
- Zooms smoothly on cue ("PUSH IN", "PULL BACK")
- Sets the focus target and exposure presets
- Marks take boundaries automatically, one take per scene, for post-production
- Scores each take and suggests the best take of each scene

**Thermal-Safe Design:**
- **Completely inert when disabled** — No background processing, zero thermal impact
- Cues are parsed on the phone when the script loads; no cloud service or language model is involved
- The director stops at its temperature threshold (55°C by default) and waits for the phone to cool before it runs again

**Use Cases:**
- Solo content creators directing their own shots
- Interview setups with scripted shot changes
- Live streaming with automated shot variety

See the [API reference](docs/API.md#ai-director) for the cue format and the director's controls.

---

## Quick Start

### 1. Install
```bash
adb install lensdaemon.apk
```

### 2. Enable Kiosk Mode (optional but recommended)
```bash
adb shell dpm set-device-owner com.lensdaemon/.AdminReceiver
```
This makes LensDaemon the device owner, which lets it lock the phone to its own
screen and start by itself after a reboot. Kiosk mode itself is switched on later,
from the setup wizard or the dashboard. Android starts apps only after the phone's
first unlock, so for unattended restarts (after a power cut) use a phone without a
PIN, pattern or password lock.

### 3. Launch and Configure
Open LensDaemon on the device. The phone shows the dashboard's address under its
status, for example `Dashboard: http://192.168.1.50:8080`.

From any browser: `http://{device-ip}:8080`. The dashboard has the live preview,
RTSP, MPEG-TS and recording controls, lens, zoom and exposure, the encoder
settings, temperatures and throttling, the recordings on the phone (download,
upload, delete), network storage, kiosk mode and the AI Director.

For an unattended setup, `http://{device-ip}:8080/setup` walks through Device
Owner, a kiosk preset, network storage, a test stream and turning kiosk mode on.
In kiosk mode, hold **Volume Up + Volume Down** for 5 seconds on the phone to leave it
(then enter the exit PIN, if one is set).

### 4. Start the Stream
Tap **Start Stream** on the phone, or **Start RTSP** in the web dashboard. The
phone's overlay shows the RTSP URL, the resolution and frame rate, and how many
viewers are watching. The phone's button (and kiosk auto-start) uses the phone's
saved stream settings: 1080p30 H.264 at 4 Mbps until others are saved. Each start
from the dashboard saves its settings, and the dashboard's encoder settings have
**Save as phone default**.

```
RTSP:        rtsp://{device-ip}:8554/stream
MPEG-TS/UDP: pushed to udp://{your-pc}:9000 (dashboard: "MPEG-TS over UDP")
```

Works with OBS, VLC, vMix, Blue Iris, Frigate, or any NVR that speaks RTSP.

### 5. Add It to OBS
1. **Sources → + → Media Source**, untick **Local File**.
2. **Input:** the RTSP URL, or `udp://@:9000` if you started MPEG-TS from the dashboard (it pushes to the computer the dashboard is open on).
3. Untick **Restart playback when source becomes active**, and set **Network Buffering** to 0–1 MB for low latency.
4. On WiFi with packet loss, use RTSP and enter `rtsp_transport=tcp` under **FFmpeg Options**: MPEG-TS over UDP has the lowest latency but no loss recovery.

**Audio:** the phone's microphone goes out with the video as AAC (48 kHz, stereo
where the phone has the microphones for it) on RTSP, MPEG-TS and recordings, timed
on the camera's clock so it stays in sync. Allow the microphone when the app asks.
If you use your own mic in OBS, untick **Phone microphone** in the dashboard.

The stream keeps running with the phone's screen off or the app in the background.

---

## Use Cases

**Pro Webcam** — Streamers, remote workers, content creators. Better than a Logitech Brio at half the cost.

**Security Camera** — 4K recording, on-device AI detection (roadmap), cellular fallback if someone cuts your internet, no subscription.

**Job Site Monitoring** — Time-lapse, live feeds, incident documentation. Runs on phones your crew already broke the screens on.

**Baby Monitor / Pet Cam** — Night mode computational photography actually works in the dark.

**Conference Rooms** — IT departments: turn that drawer of old corporate phones into standardized streaming endpoints.

---

## The Math

| Spec | Logitech Brio 4K | Used Pixel 7 |
|------|------------------|--------------|
| Price | ~$200 | ~$150 |
| Sensor | 1/4" | 1/1.31" |
| Aperture | f/2.0 | f/1.85 |
| Lenses | 1 | 3 |
| Stabilization | None | OIS + EIS |
| Low-light | Mediocre | Computational |
| Encoder | USB-limited | Hardware H.265 |
| Battery Backup | No | Yes |
| Cellular Fallback | No | Yes |

The webcam loses on every metric except "doesn't require setup."

LensDaemon is the setup.

---

## Supported Devices

**Tested and recommended:**
- Google Pixel 5, 6, 7, 8 series
- Samsung Galaxy S20, S21, S22, S23 series
- OnePlus 8, 9, 10, 11 series

**Minimum requirements:**
- Android 10+
- Camera2 API FULL or LEVEL_3
- H.264 hardware encoder
- 3GB RAM

**Check your device:**
```bash
adb shell dumpsys media.camera | grep "Hardware Level"
```
Look for `FULL` or `LEVEL_3`.

---

## API

Full REST API for integration and automation.

```bash
# Get status
curl http://192.168.1.50:8080/api/status

# Start the RTSP stream, then play rtsp://192.168.1.50:8554/stream
curl -X POST http://192.168.1.50:8080/api/rtsp/start

# Switch to telephoto lens
curl -X POST http://192.168.1.50:8080/api/lens/tele

# Take a snapshot
curl -X POST http://192.168.1.50:8080/api/snapshot -o snapshot.jpg
```

`POST /api/stream/start` starts only the encoder; RTSP, MPEG-TS and recording
have their own start endpoints. The API, the preview and the streams have no
authentication by default, so keep the phone on a network you trust.

See [API.md](docs/API.md) for full reference.

---

## Roadmap

- [x] Core streaming (RTSP)
- [x] Web control panel
- [x] Lens switching
- [x] Local recording with segmentation
- [x] SMB network storage (uploads started from the dashboard)
- [x] S3-compatible upload (AWS, B2, MinIO, R2)
- [x] Thermal governors with adaptive throttling
- [x] Battery charge limiting (on phones whose charging switch the app can write)
- [ ] Automatic upload of finished recordings
- [x] Kiosk mode with Device Owner APIs
- [ ] SRT protocol
- [ ] On-device motion detection
- [ ] ONVIF compatibility
- [ ] Multi-device management
- [x] **AI Director Mode** — Script-driven camera automation

---

## Philosophy

Phones are video transceivers that happen to run apps. Strip the apps, keep the transceiver.

LensDaemon is part of a broader idea: **hardware alchemy**. Consumer devices contain capable hardware trapped under general-purpose software. When you free the hardware to do one thing well, it outperforms purpose-built alternatives.

Your drawer full of old phones isn't e-waste. It's inventory.

---

## Contributing

PRs welcome. Priority areas:

1. **Device profiles** — Test on your hardware, document quirks
2. **Thermal tuning** — Help find sustainable encoding settings per device
3. **Protocol support** — ONVIF, NDI, WebRTC
4. **Storage backends** — Additional targets beyond SMB/S3

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

---

## License

MIT — Use it, fork it, sell it, build on it.

---

## Links

- [Full Specification](spec.md)
- [API Reference](docs/API.md)
- [Device Compatibility](docs/DEVICES.md)
- [Implementation Guide](docs/IMPLEMENTATION_GUIDE.md)

---

*LensDaemon: Transmute any phone into a dedicated video appliance.*
