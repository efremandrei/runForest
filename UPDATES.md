# Updates

## 0.2.0 build 2

- Renamed the product to `runForest`; package ID remains `com.andre.speedtest` for update and local-data continuity.
- Reframed the app as an internet connection evaluator rather than only a speed test.
- Replaced invalid discovery-time latency and phase-duration jitter with repeated connection-time samples.
- Added idle latency, loaded latency, jitter, probe failures, richer Android network details, and M-Lab TCP telemetry capture.
- Added evidence-based issue diagnosis, a 0-100 score, cautious likely-cause language, and practical actions.
- Added an in-app auto-scrolling live diagnostic log plus timestamped text export.
- Updated history JSON export to include full evaluation diagnostics.
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
