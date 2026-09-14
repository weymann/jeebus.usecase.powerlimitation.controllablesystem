# Changelog

## [Unreleased]

### Added

- `demo`: a local `EnergyGuardStub` use case plus a triggered, timed
  `communication.disconnect()`/`device.close()` teardown in `Main`, turning the
  demo into a self-contained reproduction case for the dispose()/close() stall
  described in the README's new "Reproducing the dispose()/close() stall"
  section. The two local demo devices pair via `ConnectClientsTo.TRUSTED` with
  a mutually-exchanged SKI (not `ConnectClientsTo.ALL`), so the reproduction
  stays scoped to itself even when other real SHIP/SPINE devices are reachable
  via mDNS on the same network. Not yet re-verified against this repo's
  current jEEBus.SHIP 3.0.1/SPINE 4.1.1 dependencies (written against older
  versions).

## [1.1.0] - 2026-09-09

### Changed

- update jEEBus.SHIP dependency to 3.0.1
- update jEEBus.SPINE dependency to 4.1.1
- improve `ActiveLimit#toString`

## [1.0.0] - 2026-04-24

_Initial Release._

### Added

- implement Limitation of Power **Consumption**
  - Actor Controllable System
  - Scenarios 1, 2, 3, 4
  - Specification Version 1.0.0
- implement Limitation of Power **Production**
  - Actor Controllable System
  - Scenarios 1, 2, 3, 4
  - Specification Version 1.0.0
- implement complete Use Case Logic
  - including the Controllable System state machine
  - automated power limit handling
  - automated connectivity monitoring with heartbeats
