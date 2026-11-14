# PhoneInput privacy policy

_Last updated: 14 November 2026_

PhoneInput is a free, open-source app (GPL-3.0) that lets you use your Android phone as a microphone for your own computer. It has no accounts, no ads, no analytics and no trackers. The developer does not collect, receive or store any of your data.

## Microphone audio

- The phone app records the microphone only while you are streaming. Android shows its microphone indicator and a "Streaming to …" notification the whole time.
- The audio goes **directly from your phone to the computer you connected to**, over your own Wi-Fi network, a USB cable or Bluetooth. It is not sent to the developer or to any server.
- The audio is not recorded or saved on the phone or the computer. The computer app passes it to a virtual microphone as it arrives.
- If you turn on "Play computer audio here", your computer's sound is streamed back to the phone the same way, directly over your own connection, and is not saved.

## Camera (QR code)

The "Scan QR" button uses the Google Play services code scanner. Google Play services shows the camera view and returns only the text of the QR code to PhoneInput. PhoneInput never receives camera images. The QR code holds your computer's name, local network address and port.

## Data kept on your devices

- **Phone:** your settings and the list of computers you have connected to (the name and local network address of each). This stays on the phone. If Android's own backup is turned on for your Google account, Android may include these settings in your device backup. You can remove saved computers in the app, or clear everything by uninstalling it.
- **Computer:** the app's settings and a local log file used for troubleshooting. These never leave your computer unless you choose to share them, for example when reporting a bug.

## Network connections

- Between your phone and your computer, as described above.
- **Version from GitHub only:** the phone app downloaded from GitHub checks `api.github.com` for new releases and downloads updates from GitHub. GitHub receives the usual request information, such as your IP address, under [GitHub's privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement). The **Google Play version does not do this**: Google Play delivers its updates.
- **Computer app:** it checks GitHub for updates. When you choose to install a helper, it downloads the VB-Audio virtual cable from vb-audio.com or Google's Android platform-tools (for USB) from Google.

## Children

PhoneInput is not directed at children and collects no personal information from anyone.

## Changes

Changes to this policy are published in this file. Its full history is visible in the repository.

## Contact

Questions or concerns: open an issue at <https://github.com/Dr-At0mic/Phone-Input/issues>.
