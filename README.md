# Speed Test

Android speed test app for real, informative network checks using M-Lab NDT7.

## What is implemented

- Real M-Lab Locate API v2 server selection.
- NDT7 WebSocket download and upload phases.
- First-run M-Lab consent notice.
- Local Room history with future cloud-sync fields, but no cloud service in v1.
- Technical diagnostics panel with network, device, server, phase, byte, and error details.
- JSON history export to the app external files directory.
- Dark-first theme with top Moon/Sun controls and saved preference.
- About popup with Andrei Efremuahkin, `andrei.efr@gmail.com`, version, build, and GitHub placeholder.
- ARM64-only APK packaging for Samsung-compatible phones.

## No paid services

This v1 uses no paid runtime services. It does not use Firebase, paid analytics, paid API keys, a paid cloud backend, or hosted speed-test servers. M-Lab has rate limits and privacy/data-publication requirements, so the app requires consent before running tests.

The user's own mobile/Wi-Fi data can still be consumed by a speed test.

## Build and verify

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat packageDebugApks
.\scripts\doctor.ps1
```

APK output:

```text
artifacts\SpeedTest-v0.1.0-build-1-arm64-v8a-debug.apk
```

