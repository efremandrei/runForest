# Updates

## 0.5.0 build 5

- Reworked independent diagnosis from one latency sample per destination into per-target latency profiles.
- Runs one DNS check, five TCP/443 samples, and three HTTPS samples for each independent target.
- Fixes jitter methodology: temporal jitter is now calculated within the same target path, while cross-destination spread is reported separately.
- Splits HTTPS timing into DNS, TCP, TLS, time-to-first-byte, protocol, and remote-address details when available.
- Binds independent HTTPS requests to Android's active network when possible, then falls back through the system path.
- Adds Android link evidence for local addresses, routes, MTU, NAT64, HTTP proxy, and Private DNS hostname.
- Records approximate app RX/TX byte deltas for independent diagnosis using Android TrafficStats when supported.
- Expands unit tests for repeated probes, fall-through after failures, and corrected jitter/spread behavior.
- No paid runtime service, backend, Firebase, advertising, analytics, or paid API was added.

## 0.4.0 build 4

- Added a default diagnosis mode that works independently of M-Lab.
- Cross-checks three separately operated targets: Cloudflare, Google, and IETF.
- Runs DNS, TCP/443, and HTTPS for every target, continuing after individual failures.
- Uses a two-of-three HTTPS quorum and compares it with Android network validation.
- Reports independent latency and jitter without presenting unmeasured throughput as 0 Mbps.
- Keeps the full M-Lab NDT7 test as an optional, consent-gated mode.
- Added deterministic tests for provider outage, thrown probe exceptions, full fall-through, and contradictory evidence.
- Added `scripts/independent-network-check.ps1` to verify all live targets outside the Android implementation.
- Uses only public, no-key endpoints and no paid runtime services.

## 0.3.0 build 3

- Added an ordered fallback ladder for DNS, HTTPS Locate, candidate servers, address families, and NDT7 server selection.
- Active-network DNS falls back to the system resolver; TCP checks try every returned IPv6/IPv4 address.
- M-Lab discovery retries three times and evaluates up to three returned candidates before failing.
- A failed NDT7 candidate automatically advances to the next candidate, while every attempt remains visible in the live log.
- Cross-compares Android validation with active reachability, client connection time with server TCPInfo RTT, and client/server throughput accounting.
- Added High/Medium/Low evidence confidence and disagreement/fallback counts to the UI, technical details, logs, and JSON history.
- No additional runtime service was introduced; M-Lab remains the only external measurement infrastructure.

## 0.2.0 build 2

- Renamed the product to `runForest`; package ID remains `com.andre.speedtest` for update and local-data continuity.
- Reframed the app as an internet connection evaluator rather than only a speed test.
- Replaced invalid discovery-time latency and phase-duration jitter with repeated connection-time samples.
- Added idle latency, loaded latency, jitter, probe failures, richer Android network details, and M-Lab TCP telemetry capture.
- Added evidence-based issue diagnosis, a 0-100 score, cautious likely-cause language, and practical actions.
- Added an in-app auto-scrolling live diagnostic log plus timestamped text export.
- Updated history JSON export to include full evaluation diagnostics.
- Explicitly uses the original v0.1.0 signing key from ignored local configuration so build 2 installs as an in-place update.
- Runtime services remain M-Lab NDT7 only; no paid services, Firebase, advertising, analytics, or cloud sync.

## 0.1.0 build 1

- Initial Android implementation.
- Package: `com.andre.speedtest`.
- Version: `0.1.0`.
- Version code: `1`.
- ABI: `arm64-v8a`.
- Runtime services: M-Lab NDT7 only; no paid services, no Firebase, no cloud sync.
- Local data: Room database with future sync fields.
- Verification target: unit tests, lint, package, metadata, v2 signature, SHA-256.
