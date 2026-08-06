# Privacy notice

NunaRecorder is a research prototype for collecting audio from a user-controlled Bluetooth wearable.

## Data handled by the app

- Opus audio received from the selected Bluetooth device.
- Recording timestamps, device address, segment and frame-integrity metadata.
- Optional activity/location context when the user grants the corresponding Android permission.
- Server address, user ID and credentials entered in Settings.
- Local diagnostic events for Bluetooth connection and audio-stream troubleshooting.

## Storage and transmission

Recordings and diagnostics are stored in the app's private or app-scoped storage. Audio is not automatically uploaded by default. When the user enables automatic upload or manually uploads a recording, the selected files and metadata are sent to the server configured in Settings.

The public APK contains no shared server password. Credentials entered by a user are stored locally for subsequent requests. Users should only configure a server they trust.

## User control

Users can stop recording, disable automatic upload, delete recordings from the app, clear app storage through Android settings, or uninstall the app. Server-side deletion and retention depend on the configured service.

## Important notice

Audio recording can capture other people. The user is responsible for obtaining consent and complying with applicable privacy and recording laws. This beta is provided for research evaluation and should not be relied on for safety-critical use.
