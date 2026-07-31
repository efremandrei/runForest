# runForest

`runForest` is an Android internet-connection evaluation app. It measures speed, but its main job is to explain connection quality and expose the evidence behind each conclusion.

## Evaluation model

- Default independent diagnosis does not require M-Lab or consent. It checks Cloudflare, Google, and IETF targets separately using DNS, TCP/443, and HTTPS.
- Every target and method continues after failure. A two-of-three HTTPS quorum is cross-compared with Android's own validated/captive-portal state.
- The independent mode measures connection setup latency and jitter without pretending that a small diagnostic request is a throughput measurement.
- Android network state: validated internet, captive portal, metered/roaming status, VPN, interface, DNS, Private DNS, estimated link bandwidth, and available Wi-Fi radio details.
- Optional full speed mode uses M-Lab NDT7 after explicit consent.
- Five idle TCP connection-time samples to the selected M-Lab server.
- Real M-Lab NDT7 download and upload throughput.
- Repeated connection-time samples while download/upload traffic is active, used to identify possible queueing under load.
- Connection-time jitter, failed probe count, and M-Lab TCP telemetry when the server reports it.
- Evidence-based findings with an action for weak Wi-Fi, high or variable latency, queueing under load, low capacity, failed probes, VPN, and metered connections.
- Ordered investigation fallbacks: active-network DNS then system DNS, three HTTPS Locate attempts, up to three M-Lab server candidates, and every resolved IPv6/IPv4 endpoint before a probe is considered failed.
- Cross-comparison of Android validation with active HTTPS/NDT7 reachability, client TCP connection time with server TCPInfo RTT, and client byte timing with server AppInfo throughput.
- High/Medium/Low evidence confidence based on successful methods, recovered fallbacks, terminal failures, and cross-check disagreements.

The diagnosis is intentionally cautious. A phone-side test can identify symptoms and likely causes, but it cannot prove whether every bottleneck is in Wi-Fi, the router, the provider, or the wider route from a single run.

Research basis:

- Android `NetworkCapabilities` and `LinkProperties`: https://developer.android.com/develop/connectivity/network-ops/reading-network-state
- Android `WifiInfo`: https://developer.android.com/reference/android/net/wifi/WifiInfo
- Cloudflare's open-source network-quality engine and public endpoints: https://github.com/cloudflare/speedtest
- M-Lab NDT7 protocol and TCP metrics: https://www.measurementlab.net/tests/ndt/ndt7/
- IETF discussion of network quality and latency under load: https://www.rfc-editor.org/rfc/rfc9318.html

## Live diagnostics and export

The in-app live log updates during every primary method, fallback, independent target, DNS/server/address attempt, idle probe, loaded probe, NDT7 phase, cross-check, failure, and final evaluation. It can be cleared or exported as a timestamped text file through Android's share sheet. Evaluation history exports as JSON through the same share flow and includes confidence, detailed findings, and the complete evidence list.

## No paid services

This app uses no paid runtime services, paid API keys, Firebase, advertising, paid analytics, app-owned cloud backend, or cloud sync. Independent diagnosis makes small ordinary requests to public Cloudflare, Google, and IETF endpoints; those operators can observe normal request metadata. The optional M-Lab mode has privacy/data-publication requirements, so explicit consent is required. The user's own mobile or Wi-Fi data can still be consumed.

## Build and verify

Update-compatible builds read `runForest.signingStoreFile` from the ignored local `local.properties`. On the release machine it points to the same keystore used by v0.1.0. Keep that private key available and run the signer comparison before every publication; another debug keystore produces an APK that Android treats as a different application signer.

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat packageDebugApks
.\scripts\independent-network-check.ps1
.\scripts\doctor.ps1
```

APK output:

```text
artifacts\runForest-v0.4.0-build-4-arm64-v8a-debug.apk
```
