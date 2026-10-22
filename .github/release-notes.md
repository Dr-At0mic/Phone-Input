Use your Android phone as a microphone for your Windows or Linux computer, over Wi-Fi, USB or Bluetooth.

## What's new

- **PhoneInput icon on Windows and Linux.** The installer, Start Menu and desktop shortcuts, and the Linux app menu now show the PhoneInput icon. v1.1.1 showed the Kotlin logo there.
- Windows remembers shortcut icons. If the old icon still shows after updating, sign out and back in.
- Updating from v1.1.0 or v1.1.1 works as usual (**Restart to update**). Coming from v1.0.0? Install this version by hand once.

## Downloads

| Platform | File | Status |
|---|---|---|
| Android 8.0+ | `PhoneInput-*.apk` | Stable |
| Windows 10/11 | `PhoneInput-*.exe` or `PhoneInput-*.msi` | Stable |
| Linux (Debian / Ubuntu) | `phoneinput_*_amd64.deb` | **Beta** |
| Linux (Fedora / openSUSE) | `phoneinput-*.x86_64.rpm` | **Beta** |

- **Windows** also needs the free [VB-CABLE](https://vb-audio.com/Cable/) driver; PhoneInput offers to install it. Windows may show "Windows protected your PC" because the installer isn't signed yet: click **More info → Run anyway**.
- **Linux (beta)** works with PipeWire (default on Ubuntu 22.10+ and Fedora) or PulseAudio. Choose **PhoneInput Microphone** as the input in other apps. It hasn't been tested on real hardware yet, so please [report issues](https://github.com/Dr-At0mic/Phone-Input/issues).
- Allow UDP ports 5823 and 5824 through the firewall.

See the [README](https://github.com/Dr-At0mic/Phone-Input#readme) for setup and connection options. Verify downloads with `SHA256SUMS.txt`.
