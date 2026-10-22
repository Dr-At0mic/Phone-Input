# PhoneInput

Use your Android phone as a microphone for your Windows or Linux (beta) computer, over Wi-Fi, USB or Bluetooth.

The phone app captures the mic and streams it to the desktop app, which plays it into a virtual microphone that Discord, Zoom, OBS and other apps can select. The phone can also play the computer's audio back ("phone as speaker").

## Features

- **Three links**: Wi-Fi (auto-discovery or QR code), USB (via `adb reverse`) and Bluetooth (RFCOMM).
- **Multiple phones**: up to 8 connected at once; pick the active one from the sidebar, tray menu or Ctrl+1…9.
- **Opus compression** (96 kbit/s, low-delay) with PCM fallback, and an adaptive jitter buffer with packet-loss concealment.
- **On-phone audio processing**: noise suppression (Off / Light / Strong) that runs inside PhoneInput, plus the phone's echo cancellation and automatic gain.
- **Phone as speaker**: stream the computer's audio to the phone in stereo Opus.
- **No echo in screen shares (Windows)**: the virtual mic bypasses the Windows mixer, so Discord Go Live / OBS desktop audio do not replay your voice.
- **Guided VB-CABLE install** on Windows (downloads VB-Audio's official setup; you confirm it).

## Project layout

One Kotlin Multiplatform module, `composeApp` (Compose Multiplatform 1.11, Kotlin 2.3, AGP 8.13):

| Source set | Contents |
|---|---|
| `commonMain` | Wire protocol (`protocol/`), level metering (`audio/`), theme tokens, icons and shared UI components (`ui/`) |
| `commonTest` | Protocol, pairing, jitter buffer and noise suppressor tests |
| `androidMain` | `MainActivity`, `AppSettings`, `QrPairing`; `ui/` (Connect / Live / Settings screens, Bluetooth panel); `stream/` (`StreamService` foreground service, `StreamEngine` AudioRecord → link, `Links` Wi-Fi/USB/Bluetooth transports, `SpeakerPlayer`, `DiscoveryClient`) |
| `desktopMain` | `Main.kt` (window + tray), `DesktopController`; `net/` (`StreamServer`, `DiscoveryResponder`, `AdbBridge`, `BluetoothBridge` + `bluetooth/` RFCOMM per OS, `SpeakerStreamer`); `audio/` (`AudioPump`, `VirtualMic`, `WasapiOutput`, `WasapiLoopback`, `PulseMonitorCapture`, `VbCableInstaller`); `win/` (JNA COM / Core Audio helpers); `ui/` |
| `desktopTest` | UDP loopback stream server and frame assembler tests |

## Download

Get the latest build from [Releases](https://github.com/Dr-At0mic/Phone-Input/releases):

| Platform | File |
|---|---|
| Android 8.0+ | `PhoneInput-<version>.apk` |
| Windows 10/11 | `PhoneInput-<version>.msi` |
| Linux (Debian / Ubuntu) — beta | `phoneinput_<version>_amd64.deb` |
| Linux (Fedora / openSUSE) — beta | `phoneinput-<version>.x86_64.rpm` |

Linux support is **beta**: it has not yet been tested end to end on real hardware, so please report any issues. macOS and iOS are planned.

## Build & run

Requires JDK 17+ and the Android SDK (`local.properties` → `sdk.dir`).

```bash
./gradlew :composeApp:desktopTest        # protocol, jitter buffer and UDP loopback tests
./gradlew :composeApp:run                # desktop app
./gradlew :composeApp:assembleDebug      # Android APK → composeApp/build/outputs/apk/debug/
./gradlew :composeApp:installDebug       # install on a connected phone
./gradlew :composeApp:packageDistributionForCurrentOS   # MSI (needs WiX) / DEB / RPM
```

## Virtual microphone setup

- **Windows**: install the free [VB-CABLE](https://vb-audio.com/Cable/) driver (PhoneInput offers to download and launch VB-Audio's setup if it is missing). PhoneInput plays into *CABLE Input*; choose **CABLE Output (VB-Audio Virtual Cable)** as the microphone in other apps.
- **Linux**: works out of the box on PipeWire desktops (Ubuntu 22.10+, Fedora, etc.) using the `pw-loopback`/`pw-cat` tools from `pipewire-bin`; on PulseAudio it uses `pactl`/`pacat` (package `pulseaudio-utils`). PhoneInput creates a **PhoneInput Microphone** source on start and removes it on exit.

Allow PhoneInput through the firewall: UDP **5823** (audio) and **5824** (discovery). TCP 5825 (USB) listens on 127.0.0.1 only. Windows asks the first time the app starts.

## Protocol (v2)

UDP; every packet starts with `P I <version=2> <type>`. Audio is 48 kHz mono s16le in 10 ms frames (960 bytes + 12-byte header). The phone broadcasts `Discover` to port 5824; desktops reply `Announce`. On port 5823: `Hello` → `HelloAck`, then `Audio` frames, `Ping`/`Pong` every second, `Bye` to end. Up to 8 devices can be connected; the desktop plays one (the active device, chosen in the sidebar, tray menu or with Ctrl+1…9) and tells each phone its role with `State` (active / standby). A session ends after 3 s of silence. See `composeApp/src/commonMain/kotlin/app/phoneinput/protocol/Protocol.kt`.

## Connecting

- **QR**: the desktop shows a QR code (empty state, or sidebar › Show QR code). In the phone app tap **Scan QR**; it encodes `phoneinput://connect?n=<name>&h=<ips>&p=5823&os=<code>` and also opens PhoneInput from the phone's camera app. Scanning uses Google's code scanner (Play services), so the app needs no camera permission.
- **Wi-Fi list**: computers on the same network appear automatically (UDP broadcast on 5824).
- **USB**: turn on USB debugging on the phone and plug it in. The desktop finds `adb` (PATH, Android SDK, or downloads Google's platform-tools on request) and keeps `adb reverse tcp:5825 tcp:5825` in place; in the app choose **USB › Connect over USB**. The phone then talks to `127.0.0.1:5825`, carried over the cable as TCP with `<u16 length><packet>` framing (adb only forwards TCP). This also works over Wi-Fi debugging.
- **Bluetooth**: pair the phone with the computer in the OS Bluetooth settings first. The desktop keeps an RFCOMM server open (JNA: Winsock `AF_BTH` on Windows, BlueZ sockets on Linux) and retries every 5 s while Bluetooth is off; in the app choose **Bluetooth** and pick the paired computer. Same `<u16 length><packet>` framing as USB. Windows publishes an SDP record for service UUID `5f1d6c2a-8b3e-4a7d-9c55-2e7b1f0a9d43`; BlueZ only takes SDP records over D-Bus, so on Linux the desktop binds the first free channel of 22–25 and the phone tries those. Bandwidth is ~100 KB/s (48 kHz mono PCM), which classic Bluetooth handles but with less headroom than Wi-Fi/USB.
- **Audio path**: the phone offers Opus (Concentus, pure Java, low-delay CELT mode, 96 kbit/s) in Hello and the desktop picks it in HelloAck (optional trailing codec byte, so older builds fall back to PCM); toggle under Settings › Compression. USB/Bluetooth links write from a queue on their own thread (never blocking the mic), batch the backlog after a stall and drop the oldest past ~300 ms. The desktop jitter buffer adapts its depth to the measured arrival jitter (6 s window, 30–250 ms), trims back when the link calms, fills short Opus gaps with Opus concealment and fades out instead of cutting to silence.
- **Phone as speaker**: on the phone's Live screen, **Play computer audio here** asks the computer (SpeakerRequest, repeated every second) to send what it is playing. The desktop captures its default output only while some phone wants it — Windows: WASAPI loopback via JNA COM, following default-device changes; Linux: `parec` on `@DEFAULT_MONITOR@` — paces it at exactly one 10 ms frame (silence when idle), encodes stereo Opus 128 kbit/s once and sends it to each listening phone over its existing link. The phone decodes into the shared adaptive `JitterBuffer` and plays through a low-latency AudioTrack (media volume). The PC keeps playing through its own speakers too.
- **No echo through screen sharing (Windows)**: the virtual mic is written to CABLE Input in WASAPI **exclusive** mode (24-bit stereo, ~40 ms buffer), which bypasses the Windows mixer, so loopback captures such as Discord Go Live / OBS desktop audio cannot pick up the voice and replay it to viewers. Falls back to shared Java Sound (logged) if another app holds CABLE Input or exclusive mode is disabled. "Play computer audio here" captures with process loopback of every app **except PhoneInput** (Windows 10 2004+; older Windows falls back to default-device loopback), so the phone never receives its own voice or the "Listen on this computer" monitor.

## Logs

- Desktop: `%LOCALAPPDATA%\PhoneInput\logs\phoneinput.log` (Linux: `~/.local/state/phoneinput/phoneinput.log`)
- Android: `adb logcat -s PhoneInput`

## Troubleshooting

- **Phone doesn't see the computer**: both must be on the same network and subnet; check the firewall rules above, or connect by QR / USB instead.
- **Echo of your voice**: if the computer plays out loud, turn on Settings › Echo cancellation on the phone or use headphones. "Listen on this computer" plays through the speakers, so screen shares will include it.
- **No sound in other apps (Windows)**: select *CABLE Output* as the microphone. PhoneInput holds CABLE Input in exclusive mode, so other apps cannot play into it while it runs.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## Not yet implemented

a custom Windows driver, global mute hotkey, iOS app, light theme, bundled Inter / JetBrains Mono fonts.

## License

[GPL-3.0](LICENSE)
