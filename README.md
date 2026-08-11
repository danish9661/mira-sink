# Mira Sink — Miracast Receiver for Android

Native Android (Kotlin) app that turns a phone/tablet into a **Wi-Fi Display (Miracast) sink**:
Windows 10/11 PCs can cast their screen to it (`Win + P` → *Connect to a wireless display* →
**Extend**), with low-latency H.264/H.265 video and UIBC touch backchannel so you can
touch-control the Windows PC from the device.

## Architecture

```
Windows PC (source)                          Android device (sink)
┌───────────────────┐   Wi-Fi Direct P2P    ┌────────────────────────┐
│ Miracast (WMVD)   │ ───── DIRECT-Mira ───▶│ P2pController (Group   │
│  └ RTSP client    │   (phone = Group      │  Owner, 5 GHz band)    │
│  └ RTP sender     │    Owner, p2p0 net)   └────────────────────────┘
└───────────────────┘                        ┌────────────────────────┐
                                             │ RtspServer :7236       │
                                             │  M1–M7 state machine,  │
                                             │  WFD capabilities      │
                                             │  (H.264 + H.265 advert)│
                                             └────────────────────────┘
                                             ┌────────────────────────┐
   UDP RTP ── H.264/HEVC/MPEG-TS ───────────▶│ RtpReceiver :1550      │
                                             │  Depacketizer          │
                                             │  (FU-A/STAP/AP/TS demux)│
                                             └──────────┬─────────────┘
                                                        ▼
                                             ┌────────────────────────┐
                                             │ VideoDecoder           │
                                             │ MediaCodec + Surface,  │
                                             │ low-latency keys       │
                                             └──────────┬─────────────┘
                                                        ▼
                                             ┌────────────────────────┐
       TCP ── UIBC input/events :7237 ─────▶│ UibcServer             │
                                             │ TouchOverlayView       │
                                             └────────────────────────┘
```

### Components

| File | Role |
|---|---|
| `p2p/P2pController.kt` | Wi-Fi Direct Group Owner creation (5 GHz via `setGroupOperatingBand`, 2.4 GHz fallback), WFD info best-effort via reflection, broadcast receiver |
| `rtsp/RtspServer.kt` | TCP accept loop on port **7236** |
| `rtsp/RtspConnection.kt` | M1–M7 negotiation: OPTIONS ⇄, GET_PARAMETER capability answers, M4 `wfd_client_rtp_ports`, M5 trigger/play, M6 SETUP, M7 PLAY |
| `rtsp/Capabilities.kt` | WFD format strings (H.264 + H.265 advert, LPCM audio, UIBC capability) |
| `udp/RtpReceiver.kt` | UDP sockets on RTP port (default 1550) + RTCP discard channel |
| `udp/Depacketizer.kt` | RTP payload parsing: H.264 (single NAL / STAP-A / FU-A), HEVC (RFC 7798 single / AP / FU), MPEG-TS 188-byte demux (PAT→PMT→PES) |
| `codec/VideoDecoder.kt` | MediaCodec surface output, `KEY_LOW_LATENCY`, `KEY_OPERATING_RATE`, adaptive playback; auto-switches H.264↔H.265 from stream |
| `codec/SpsParser.kt` | H.264 / HEVC SPS bit-parsers for real stream resolution |
| `uibc/UibcServer.kt` | TCP backchannel on **7237**, generic-touch packets (DPU/MPE/UP), UIBC commands |
| `uibc/TouchOverlayView.kt` | Full-screen touch interceptor, normalizes coordinates → UIBC |
| `MiracastService.kt` | Foreground orchestrator, wake lock, session lifecycle/reset |
| `MainActivity.kt` | Minimal full-screen UI: status dot + SurfaceView + overlay + Reset |

## Build

Requires (already present on this machine):
- JDK 17+ (Android Studio JBR 21 at `/opt/android-studio/jbr`)
- Android SDK: `platforms;android-36.1`, `build-tools;36.0.0`, `platform-tools`
- Gradle 9.2.1 (wrapper included)

```bash
export JAVA_HOME=/opt/android-studio/jbr
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

# install on a connected device (enable USB debugging)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## testsource (dev-only module)

`testsource` is a synthetic **fake Miracast source** used for loopback testing without a real PC:

- Runs a full RTSP M1–M7 handshake against the sink (ports 7236/7237), negotiates H.264 or
  H.265, then streams RTP (payload 96) of a MediaCodec-encoded animated gradient test pattern
  to the sink's RTP port (default 1550).
- Also acts as a UIBC client (sends touch UP/MOVE/DOWN events over the backchannel).
- Writes the raw encoder output to `files/encoded.h265` (or `.h264`) for offline verification.

```bash
./gradlew :testsource:assembleDebug
adb install -r testsource/build/outputs/apk/debug/testsource-debug.apk
# launch sink first, then the testsource on the same device (loopback) or a second device
```

> **Heads-up**: on the emulator, the sink only works in local mode (`localMode=true` intent
> extra) since Wi-Fi Direct cannot create a group there. Always
> `adb shell am force-stop com.mira.sink` before relaunching for a clean run.

## Known bug fixed (HEVC FU packetization)

A stall where the decoder silently stopped after ~5 frames turned out to be **duplicated NAL
headers** in RTP: large HEVC NALs (> ~1350 bytes) were fragmented as FUs *including* the
original 2-byte NAL header, and the depacketizer rebuilt its own — so every IDR/P frame
started with `26 01 26 01`. Both sides were fixed to match RFC 7798:

- `testsource` `sendHevcNal`: FU payload starts at `offset = 2` (skip NAL header).
- `app` `Depacketizer` (case 49): preserves the real TID byte from the FU indicator instead
  of a hardcoded `0x01`.

Verified end-to-end: RTP-captured stream is byte-exact against encoder output (341/341 NALs),
and the live loopback decodes + renders continuously (~240 frames, 30 fps).

## Usage

1. Phone and PC must be capable of Miracast. Run the app and grant Wi-Fi/notification
   permissions.
2. The app creates a Wi-Fi Direct group **DIRECT-Mira** (phone = Group Owner, 5 GHz preferred).
   Status shows SSID + key.
3. On Windows: `Win + P` → **Connect to a wireless display** → select the device
   (may appear as *DIRECT-Mira* or by the phone's P2P name) → **Extend** (or Duplicate).
4. The RTSP session negotiates (logs in logcat with tag `MiraRTSP`), video decodes onto the
   SurfaceView, and **touching the screen drives the Windows cursor** via UIBC.

Reset button tears down the group/session and recreates it.

## Interop notes / known limitations

- **Discovery**: Windows only lists devices that advertise a WFD Information Element in their
  P2P probe response. A third-party app cannot set the system WFD IE on all OEM builds —
  `P2pController.attachWfdInfo()` tries reflection (`WifiP2pConfig.setWfdInfo`) as a
  best-effort. If the phone does not appear in the Windows list, the group creation still
  works; pairing depends on OEM support.
- **Codec choice**: we advertise H.264 *and* H.265; Windows often picks H.264 automatic
  bitrate. The decoder auto-detects from the incoming NAL stream and reconfigures.
- **Audio**: advertised for negotiation compatibility only; audio RTP is not decoded (video +
  touch pipeline).
- **UIBC**: generic touch advertised (`event=00000018` = single + multi touch), triggered
  method; the sink sends `Input_Ready` when the PC connects to port 7237. Event byte
  values follow the WFD spec (DOWN=2, MOVE=3, UP=1) — if a specific Windows build does not
  react to touch, check the `event=` mask and method in `Capabilities.kt`/`UibcServer.kt`.
- Each factory-reset of the P2P layer runs on Android 13+ `NEARBY_WIFI_DEVICES`; on older
  devices `ACCESS_FINE_LOCATION` is required for Wi-Fi Direct.
- Some TVs/OEMs restrict concurrent Wi-Fi + P2P on the 5 GHz band; the 2.4 GHz fallback
  path is exercised automatically when 5 GHz group creation fails.

## Debugging

```bash
adb logcat -s MiraP2P:MiraRTSP:MiraUDP:MiraCodec:MiraUIBC:MiraService
```

RTSP handshake, codec setup, UDP ports and UIBC state are all logged under those tags.