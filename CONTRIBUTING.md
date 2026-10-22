# Contributing to PhoneInput

Thanks for helping out. This guide covers setting up, making a change and getting it merged.

## Setup

1. Install JDK 17+ and the Android SDK (Android Studio is the easiest way).
2. Point `local.properties` at the SDK: `sdk.dir=/path/to/Android/sdk`.
3. Build and test:

   ```bash
   ./gradlew :composeApp:desktopTest :composeApp:assembleDebug
   ```

4. Run the desktop app with `./gradlew :composeApp:run` and install the phone app with `./gradlew :composeApp:installDebug`.

To test end to end you need the virtual mic driver (VB-CABLE on Windows, PulseAudio/PipeWire with `pactl` on Linux). See [README.md](README.md#virtual-microphone-setup).

## Project structure

Everything lives in one Kotlin Multiplatform module, `composeApp`. Code shared by both apps (protocol, audio processing, theme, UI components) goes in `commonMain`; platform code goes in `androidMain` or `desktopMain`. The [README](README.md#project-layout) maps the source sets.

Toolchain constraints:

- Kotlin 2.3, Compose Multiplatform 1.11, AGP 8.13, minSdk 26 / compileSdk 36.
- Do **not** bump Compose Multiplatform to 1.12+ on its own: it needs AGP 9.1, compileSdk 37 and the Android app split into its own module.

## Making changes

- **Keep diffs small and focused.** One fix or feature per pull request; avoid drive-by rewrites.
- **Match the surrounding code**: naming, comment density and idioms. Kotlin follows the [official style](https://kotlinlang.org/docs/coding-conventions.html).
- **Never block the audio path.** Mic capture, link writers and the desktop pump run on their own threads; don't do I/O, logging bursts or allocation-heavy work inside them.
- **Protocol changes must stay compatible.** Older builds must keep working (see how the optional codec byte in `HelloAck` falls back to PCM). If you can't stay compatible, bump the protocol version in `protocol/Protocol.kt` and update the Protocol section of the README.
- **No hardcoded business values.** Ports, rates and limits belong in named constants or configuration.
- **UI**: use the theme tokens and shared components in `commonMain/ui` rather than one-off colors and sizes.
- **Third-party code**: check the license first. VB-CABLE, for example, may not be bundled; PhoneInput only downloads VB-Audio's own setup for the user to run.

## Tests

- Add or update tests in `commonTest` (shared logic) or `desktopTest` (desktop networking and audio) for any logic change.
- Tests must be deterministic and must not need real audio hardware, a phone or network access beyond loopback. Hardware probes are fine while debugging but don't commit them.
- Before opening a pull request, this must pass:

  ```bash
  ./gradlew :composeApp:desktopTest :composeApp:assembleDebug
  ```

- For audio or link changes, also say in the pull request what you tested by hand (OS, phone model, link type).

## Pull requests

1. Fork and create a branch from `main` (`fix/usb-reconnect`, `feat/mute-hotkey`).
2. Write clear commit messages: a short imperative summary line, then the why.
3. Open the pull request with what changed, why, and how you verified it. Link any related issue.
4. Update the README when behavior, setup, ports or the protocol change.

## Reporting bugs

Open an issue with:

- OS and version, phone model and Android version, link type (Wi-Fi / USB / Bluetooth).
- Steps to reproduce, and what you expected.
- Logs: desktop `%LOCALAPPDATA%\PhoneInput\logs\phoneinput.log` (Linux: `~/.local/state/phoneinput/phoneinput.log`) and `adb logcat -s PhoneInput` from the phone.
