# runForest

`runForest` is an Android internet-connection evaluation app. It measures speed, but its main job is to explain connection quality and expose the evidence behind each conclusion.

## Evaluation model

- Android network state: validated internet, captive portal, metered/roaming status, VPN, interface, DNS, Private DNS, estimated link bandwidth, and available Wi-Fi radio details.
- DNS and M-Lab reachability timing.
- Five idle TCP connection-time samples to the selected M-Lab server.
- Real M-Lab NDT7 download and upload throughput.
- Repeated connection-time samples while download/upload traffic is active, used to identify possible queueing under load.
- Connection-time jitter, failed probe count, and M-Lab TCP telemetry when the server reports it.
- Evidence-based findings with an action for weak Wi-Fi, high or variable latency, queueing under load, low capacity, failed probes, VPN, and metered connections.

The diagnosis is intentionally cautious. A phone-side test can identify symptoms and likely causes, but it cannot prove whether every bottleneck is in Wi-Fi, the router, the provider, or the wider route from a single run.

Research basis:

- Android `NetworkCapabilities` and `LinkProperties`: https://developer.android.com/develop/connectivity/network-ops/reading-network-state
- Android `WifiInfo`: https://developer.android.com/reference/android/net/wifi/WifiInfo
- M-Lab NDT7 protocol and TCP metrics: https://www.measurementlab.net/tests/ndt/ndt7/
- IETF discussion of network quality and latency under load: https://www.rfc-editor.org/rfc/rfc9318.html

## Live diagnostics and export

The in-app live log updates during DNS, server selection, idle probes, loaded probes, NDT7 phase changes, progress, failures, and final evaluation. It can be cleared or exported as a timestamped text file through Android's share sheet. Evaluation history exports as JSON through the same share flow and includes detailed findings.

## No paid services

This app uses no paid runtime services, paid API keys, Firebase, advertising, paid analytics, app-owned cloud backend, or cloud sync. M-Lab is public infrastructure with privacy/data-publication requirements, so consent is required before testing. The user's own mobile or Wi-Fi data can still be consumed.

## Build and verify

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat packageDebugApks
.\scripts\doctor.ps1
```

APK output:

```text
artifacts\runForest-v0.2.0-build-2-arm64-v8a-debug.apk
```
