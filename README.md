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
- **Battery bypass** that holds charge at 50% to prevent swelling during 24/7 operation.
- **Network-controlled** via web browser. No app on your main device needed.

One APK. Plug in power. Point at thing. Stream forever.

---

## Features

| Feature | Description |
|---------|-------------|
| **Dual Protocol Streaming** | RTSP for universal compatibility, SRT for low-latency production |
| **Multi-Lens Control** | Switch wide/main/tele from any browser on your network |
| **Network Storage** | Record to SMB/NFS shares or S3-compatible buckets |
| **Thermal Dashboard** | Real-time CPU/battery temps, throttle state, encoding stats |
| **Kiosk Mode** | Locks device to LensDaemon — survives reboots, no escape without override |
| **Web Control Panel** | Dashboard, live preview, full API at `http://{device-ip}:8080` |
| **Zero Cloud Dependency** | Your footage stays on your network unless you decide otherwise |
| **AI Director Mode** | Script-driven camera control — lens, zoom, focus respond to your scene cues |

---

## AI Director Mode (Experimental)

**Let an LLM be your camera operator.**

Feed LensDaemon a script or scene description, and the AI Director interprets your cues to control the camera in real-time:

```
[SCENE 1 - WIDE SHOT]
Two people enter the frame from the left.

[CUT TO: CLOSE-UP]
Focus on speaker's face. Shallow depth of field.

[PULL BACK - MEDIUM]
Both subjects in frame. Auto-exposure for backlight.
```

**What it does:**
- Parses scene/script markers to trigger camera actions
- Switches lenses (wide → main → telephoto) based on shot descriptions
- Adjusts zoom smoothly on cue ("PUSH IN", "PULL BACK")
- Sets focus mode and depth based on scene context
- Marks take boundaries automatically for post-production
- Suggests "best takes" based on technical quality (focus lock, exposure, stability)

**Thermal-Safe Design:**
- **Completely inert when disabled** — No background processing, no API calls, zero thermal impact
- AI processing runs on-demand only, not continuously
- Scene cues are parsed locally or via configurable LLM endpoint
- System automatically disables if thermal thresholds are exceeded

**Use Cases:**
- Solo content creators directing their own shots
- Multi-camera setups with synchronized scene changes
- Documentary/interview setups with intelligent reframing
- Live streaming with automated shot variety

See [AI Director Specification](spec.md#ai-director-mode) for implementation details.

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

### 3. Launch and Configure
Open LensDaemon on the device. Note the IP address displayed.

From any browser: `http://{device-ip}:8080`

### 4. Connect Your Software
```
RTSP: rtsp://{device-ip}:8554/live
SRT:  srt://{device-ip}:9000
```

Works with OBS, VLC, vMix, Blue Iris, Frigate, or any NVR that speaks RTSP.

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

# Start streaming
curl -X POST http://192.168.1.50:8080/api/stream/start

# Switch to telephoto lens
curl -X POST http://192.168.1.50:8080/api/lens/tele

# Take a snapshot
curl -X POST http://192.168.1.50:8080/api/snapshot
```

See [API.md](docs/API.md) for full reference.

---

## Roadmap

- [x] Core streaming (RTSP)
- [x] Web control panel
- [x] Lens switching
- [x] Local recording with segmentation
- [x] SMB/NFS network storage
- [x] S3-compatible upload (AWS, B2, MinIO, R2)
- [x] Thermal governors with adaptive throttling
- [x] Battery bypass charging
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
