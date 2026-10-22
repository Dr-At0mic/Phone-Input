Use your Android phone as a microphone for your Windows or Linux computer, over Wi-Fi, USB or Bluetooth.

## What's new

- **Windows `.exe` installer.** Download `PhoneInput-*.exe` if you're not sure what an `.msi` is. Both install the same app, so use either one.
- **Start Menu and desktop shortcuts.** The Windows installer now adds PhoneInput to the Start Menu and the desktop. Earlier versions put it only in `C:\Program Files\PhoneInput`.
- **Start with Windows.** Tick it in the tray menu, and PhoneInput starts hidden in the tray when you sign in.
- Updating from v1.1.0 works as usual (**Restart to update**). Coming from v1.0.0? Install this version by hand once.

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
