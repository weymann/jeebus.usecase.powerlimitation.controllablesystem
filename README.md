# jEEBus.PowerLimitation.ControllableSystem

[![maven-central](https://img.shields.io/maven-central/v/org.openmuc.jeebus.usecase.powerlimitation/abstract-controllablesystem?logo=apachemaven)](https://central.sonatype.com/namespace/org.openmuc.jeebus.usecase.powerlimitation)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/openmuc/jeebus.usecase.powerlimitation.controllablesystem)

This repository contains Java implementations of the Controllable System (CS) Actors
of the following EEBus Use Cases:

- Limitation of Power Consumption (LPC) according to specification version 1.0.0
   - UC Implementation Guideline specification version 1.0.0
- Limitation of Power Production (LPP) according to specification version 1.0.0

LPC enables devices like EV wallboxes, heat pumps or energy storage systems to have
their power consumption controlled by a control box or EMS. It is a technical
implementation for controllable devices ("Steuerbare Verbrauchseinrichtungen")
compliant to
[German regulation §14a EnGW](https://www.gesetze-im-internet.de/enwg_2005/__14a.html).

LPP offers the same features for energy producers like PV inverters. This enables
compliance to
[§9 EEG in Germany](https://www.gesetze-im-internet.de/eeg_2014/__9.html).

## Project Setup

The implementations are based on our
Libraries [jEEBus.SHIP](https://github.com/openmuc/jeebus.ship)
and [jEEBus.SPINE](https://github.com/openmuc/jeebus.spine).

This is a Gradle Project that comes with a packaged Gradle Wrapper (use `./gradlew`
on Linux and `gradlew.bat` on Windows systems). You can run the following command to
download all necessary dependencies and build the project:

```bash
./gradlew clean build
```

To run all contained unit tests, run

```bash
./gradlew test
```

There are four subprojects in the `projects` folder:
- `abstract-controllablesystem` contains the common parts of the LPC and LPP CS.
- `lpc-controllablesystem` contains the parts of the CS specific to LPC.
- `lpp-controllablesystem` contains the parts of the CS specific to LPP.
- `demo` contains a minimal example application showing how jEEBus can be configured
   and used.

You can start the `demo` application by simply running
```bash
./gradlew run
```
or pass the desired IP address, port and trusted SKIs like so:
```bash
./gradlew run --args="localhost 8080 e268fabdcbb076e13d5f2ea7df6b2d7c382a967f"
```

## Reproducing the dispose()/close() stall

The `demo` application also serves as a self-contained reproduction case for a
known issue: tearing down a `Device` (`communication.disconnect()` followed by
`device.close()`) can take far longer than expected - anywhere from several
seconds to roughly a minute - once that device has an actively running outgoing
DeviceDiagnosis Heartbeat (i.e. it has completed a real SPINE UseCasePartner
discovery with an "EnergyGuard" partner).

To make this reproducible without any external hardware or a second tool, the
demo now also starts a second, local `Device` running `EnergyGuardStub`
(`projects/demo/.../demo/EnergyGuardStub.java`) - a minimal, deliberately
incomplete stand-in for a real EnergyGuard actor that satisfies just enough of
the SPINE discovery requirements for the ControllableSystem device to find it,
subscribe to its Heartbeat feature, and start its own outgoing Heartbeat.

Run it with no arguments:
```bash
./gradlew run
```

What to expect in the log:
1. Both devices start; each reads back its own SKI and logs it
   (`ControllableSystem device SKI: ...` / `EnergyGuardStub device SKI: ...`),
   then mutually trusts the other via `ConnectClientsTo.TRUSTED` - see the
   `Main` class javadoc for why this is *not* `ConnectClientsTo.ALL`.
2. `EnergyGuardStub ready at ...` (from `EnergyGuardStub#setup()`) confirms the
   stub device came up.
3. A SPINE UseCasePartner match / "beginning Use Case execution"-style log
   line from the ControllableSystem side confirms it discovered the stub and
   started its own outgoing Heartbeat.
4. After a fixed settle time, the demo logs
   `=== Triggering ControllableSystem teardown (communication.disconnect() +
   device.close()) ===` and calls `communication.disconnect()` then
   `device.close()`, timing each step.
5. If the issue reproduces, the `device.close()` step's timing log will show a
   stall of several seconds up to roughly a minute before the demo exits,
   instead of returning near-instantly.

This reproduction intentionally only demonstrates the symptom; the demo's log
output and the library's own logging around this call path should be
sufficient starting points for investigating the cause.

> **2026-09-14 note:** an earlier version of this reproduction used
> `ConnectClientsTo.ALL` instead of `TRUSTED`, purely to avoid a manual SKI
> exchange. On a network where other, unrelated SHIP/SPINE devices are
> reachable via mDNS, that made both local demo devices indiscriminately
> attempt discovery/connection against those unrelated real devices too -
> observed to prevent the two demo devices from ever completing pairing with
> *each other*, and to make the run take minutes instead of seconds. If runs
> still don't reach step 3 after this change, mDNS *enumeration* itself
> (independent of trust mode) can still be slow on hosts with many network
> interfaces (e.g. a Docker host with many veth interfaces) - try raising
> `DISCOVERY_SETTLE_TIME_MILLIS` in `Main.java` further.
>
> Written and verified against jEEBus.SHIP 2.3.0 / jEEBus.SPINE (pre-4.1.1).
> This repo's dependencies have since moved to SHIP 3.0.1 / SPINE 4.1.1
> (already flagged elsewhere as containing breaking API changes) - this
> reproduction, including the `TRUSTED`/mutual-SKI change above, has not yet
> been re-verified to still build against those versions.

## Quick Start

What sets our implementation apart from other EEBus stacks is that we also
implemented the high level Use Case specific logic present in the specification. This
includes the complete Controllable System state machine as well as automated
monitoring of the connection to the Energy Guard using heartbeats.

That means you can have a basic instance of an LPC/LPP Controllable System running
in your application by configuring and initializing jEEBus.SHIP, jEEBus.SPINE and
the desired Use Case. Then, register your own custom listener that is automatically
called whenever the active power limit of your device changes as defined in the
specification:

```java
lpcCs.addListener(((event, state, activeLimit) -> {
    // TODO: your listener goes here
}));
```

For the complete, executable example application, see the `demo` subproject.

## Contributing

We are currently working out the contribution process.

If you would like to contribute to this project, please get in touch with the
development team here on GitHub or use
[the contact form on our website](https://www.openmuc.org/contact/).

## License

Copyright (c) 2026 Fraunhofer ISE

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
https://www.eclipse.org/legal/epl-2.0 or the provided `LICENSE` file.

SPDX-License-Identifier: `EPL-2.0`
