# Changelog

All notable changes to this project are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versioning: [SemVer](https://semver.org/).

## [Unreleased]

## [2.0.0] - 2026-09-22

### Upgrade notes
- The application ID is now `com.lef.pmaaobd`: 2.0 installs next to 1.x instead of replacing it.
  Export your dashboards from 1.x, uninstall it, then import them in 2.0.
- Release signing secrets are renamed `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.

### Added
- Support for the most common Bluetooth OBD-II adapters: Bluetooth classic (SPP) adapters such as OBDLink MX+/LX, vLinker MC+/FS, Vgate iCar and ELM327 clones, and other Bluetooth LE adapters (FFF0, FFE0, 18F0 and Nordic UART layouts, automatic detection).
- Fallbacks for ELM327 clones: no response count suffix, single PID requests, NUL padding, missing CCCD descriptor.
- "Show all Bluetooth devices" option in the adapter search.
- Redesigned OBD adapter screen: status indicator, device picker with progress, quick test, ELM327 console, copyable log.
- Status banner on the car screen driven by the real adapter state: no adapter, Bluetooth off, missing permission, connecting, waiting for the engine.
- Default dashboard with RPM, speed and throttle gauges plus coolant, battery voltage, intake air temperature and engine load.
- Automatic migration of dashboards saved by 1.x.
- English and French translations for the adapter screen, connection states and PID names.
- New application icon (adaptive, themed icon ready).
- Unit tests for PID identifiers.

### Changed
- Installation documented through AAEnabler.
- New name: Performance Monitor AAOBD.
- English is now the base language of the app and the documentation (French kept as translation).
- Credits screen rewritten.

### Fixed
- Status banner stuck on "Connecting to ECU" forever.
- Default gauges never received data (PID identifiers in the old Torque format).
- Values equal to zero, such as speed when stopped, were never displayed.
- Corrupted settings file not detected.

### Removed
- Crash reports e-mailed to a third party.
- Leftover online update code and the install, delete package, internet and storage permissions.

## [1.0.0] - 2026-09-18

### Added
- Direct OBDLink CX connection over Bluetooth LE, without any third-party app.
- OBD adapter screen: search, pairing, live state, log, test, ELM327 console.
- Grouped reads of up to 6 PIDs per request (CAN) with automatic fallback.
- Virtual PIDs: boost pressure (MAP - Baro), battery voltage (ATRV).
- Unit tests for OBD decoding.
- GitHub Actions CI and signed release on tag.

### Removed
- Dependency on the Torque app.

[Unreleased]: https://github.com/Le-F-Sur-GitH/Performance-Monitor-AAOBD/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/Le-F-Sur-GitH/Performance-Monitor-AAOBD/releases/tag/v2.0.0
