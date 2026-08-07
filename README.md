# NunaRecorder

NunaRecorder is an Android research app for receiving Opus audio from Nuna-compatible Bluetooth recorders, including the original Nuna protocol and the XIAO nRF52840 Sense firmware developed for the Audio-RAG/lifelog workflow.

> Beta software for research evaluation. Do not record other people without their knowledge and any consent required by law.

## Download

Download the signed APK from [GitHub Releases](https://github.com/JadyXuan/NunaRecorder/releases).

Android 8.0 or newer is required. Because the APK is distributed outside Google Play, Android may ask you to allow the browser or file manager to install unknown apps.

## Device compatibility

- Original Nuna handshake, control and Opus streaming characteristics (`A001`–`A003`).
- XIAO nRF52840 Sense firmware using the same protocol.
- Optional standard Bluetooth Battery Service (`180F/2A19`).
- Optional extended XIAO power telemetry (`A004`) with voltage, USB and charging state.

Devices without a battery characteristic remain fully usable; the app reports that battery information is unavailable and continues the normal handshake.

## Basic use

1. Power on the recorder and grant the requested Bluetooth permissions.
2. Scan for and select the Nuna-compatible device.
3. Tap **连接 + 握手** and wait for **设备已就绪**.
4. Tap **开始录制**. The app stores incoming Opus data in segmented recording sessions.
5. Stop the session from the device page. Recordings can then be played, shared, processed or uploaded.

The public APK does not contain a default server or shared credentials. On a fresh install, the server URL and user ID are empty, server-backed features are disabled, and the app makes no application-server requests. Configure a trusted server explicitly in Settings when needed; automatic audio upload remains opt-in.

To connect a self-hosted backend, see the [server integration guide](docs/SERVER_INTEGRATION.md). The guide documents authentication, raw Opus framing, upload endpoints, full-session sync, timelines, diaries and annotation APIs.

## Build from source

Prerequisites:

- Android Studio or JDK 17
- Android SDK 36

Run:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Local pilot builds may define the following ignored entries in `local.properties`:

```properties
pilot.basicAuthUsername=your-local-username
pilot.basicAuthPassword=your-local-password
```

These values are used only by debug builds. Release builds force both values to empty strings.

## Privacy and diagnostics

Read [PRIVACY.md](PRIVACY.md) before collecting data. The app can export privacy-filtered diagnostic logs from Settings for troubleshooting long-running Bluetooth sessions.

## Release process

Tags matching `v*` trigger the signed release workflow. Signing material is supplied through GitHub Actions Secrets and is never committed to the repository.
