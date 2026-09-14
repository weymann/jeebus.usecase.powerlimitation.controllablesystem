/********************************************************************************
 * Copyright (c) 2026 Fraunhofer ISE
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/

package org.openmuc.jeebus.usecase.powerlimitation.controllablesystem.demo;


import org.openmuc.jeebus.ship.api.ShipNodeConfiguration;
import org.openmuc.jeebus.shipspine.ShipCommunication;
import org.openmuc.jeebus.spine.api.Device;
import org.openmuc.jeebus.spine.utils.datatypes.ScaledNumberWrapper;
import org.openmuc.jeebus.spine.xsd.v1.DeviceTypeEnumType;
import org.openmuc.jeebus.spine.xsd.v1.EntityTypeEnumType;
import org.openmuc.jeebus.usecase.powerlimitation.controllablesystem.ActiveLimit;
import org.openmuc.jeebus.usecase.powerlimitation.controllablesystem.SimpleLimitationConfig;
import org.openmuc.jeebus.usecase.powerlimitation.controllablesystem.lpc.LpcCs;
import org.openmuc.jeebus.usecase.powerlimitation.controllablesystem.lpp.LppCs;
import org.openmuc.jeebus.usecase.powerlimitation.controllablesystem.states.Event;
import org.openmuc.jeebus.usecase.powerlimitation.controllablesystem.states.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.openmuc.jeebus.shipspine.ShipCommunication.ConnectClientsTo.TRUSTED;

/**
 * <h2>Reproducing the dispose()/close() stall</h2>
 * This demo has been extended (compared to the "plain" jEEBus quick-start example
 * described in the project README) to make it self-contained enough to reproduce a
 * known issue: tearing down a {@link Device} that has an actively running outgoing
 * DeviceDiagnosis Heartbeat (i.e. one that has completed a real SPINE UseCasePartner
 * discovery with an "EnergyGuard" partner, per the LPC/LPP Use Case's Scenario 3) can
 * take far longer than expected - anywhere from several seconds to roughly a minute,
 * instead of returning near-instantly.
 * <p>
 * A ControllableSystem's own outgoing Heartbeat ({@code LimitationUseCaseImpl#startHeartbeat()})
 * is only started once a real partner has been found and subscribed to - see
 * {@code LimitationThread} in the abstract-controllablesystem module. To trigger that
 * without requiring an external EnergyGuard device/tool, this {@code main()} also
 * starts a second, local {@link Device} running {@link EnergyGuardStub} - a minimal,
 * deliberately incomplete stand-in that satisfies just enough of the SPINE discovery
 * requirements (see its class javadoc) for the ControllableSystem device below to
 * discover it, subscribe to its Heartbeat feature, and start its own outgoing
 * Heartbeat in response.
 * <p>
 * Once that has had time to happen, {@code main()} triggers a normal teardown of the
 * ControllableSystem device - {@code communication.disconnect()} followed by
 * {@code device.close()}, mirroring the two-step sequence real application code (e.g.
 * an openHAB Thing handler's {@code dispose()}) is expected to call - and logs how
 * long each step took. If the issue reproduces, the {@code device.close()} step in
 * particular will visibly stall.
 * <p>
 * Both {@link Device}s in this demo communicate using {@code ConnectClientsTo.TRUSTED},
 * with each device's own SKI read back right after constructing its
 * {@link ShipCommunication} and then mutually trusted by the other device - so the
 * two local devices pair with each other automatically, without requiring the user to
 * pre-generate/exchange keystores by hand, while still deliberately <strong>not</strong>
 * trusting (or attempting to connect to) any other SHIP/SPINE device that may happen
 * to be reachable via mDNS on the same network.
 * <p>
 * <strong>2026-09-14 note:</strong> an earlier version of this reproduction used
 * {@code ConnectClientsTo.ALL} (+ {@code withAutoAcceptMode(true)}) instead, purely to
 * avoid the SKI exchange below. On a network where other, unrelated SHIP/SPINE devices
 * are reachable via mDNS (e.g. a real EnergyGuard/EMS and the real controllable
 * devices it manages), {@code ALL} made both local devices in this demo
 * indiscriminately attempt discovery/connection against those unrelated real devices
 * too - observed to prevent the two local demo devices from ever completing pairing
 * with <em>each other</em>, and to make the whole run take minutes instead of seconds.
 * {@code TRUSTED} + the mutual SKI exchange below keeps this reproduction strictly
 * scoped to its own two local devices regardless of what else is reachable on the
 * network. If runs still hang after this change, note that mDNS <em>enumeration</em>
 * itself (i.e. simply seeing what is out there, independent of which peers get
 * trusted/connected to) can still be slow on hosts with many network interfaces (e.g.
 * a Docker host with many veth interfaces) - see {@link #DISCOVERY_SETTLE_TIME_MILLIS}.
 * <p>
 * <strong>2026-09-14 note (unchanged from before):</strong> written and verified
 * against jEEBus.SHIP 2.3.0 / jEEBus.SPINE (pre-4.1.1). This repo's dependencies have
 * since moved to SHIP 3.0.1 / SPINE 4.1.1 (a release the project's own memory already
 * flagged as containing breaking API changes) - re-applied here unchanged because the
 * environment used to re-verify it against current jeebus.ship/jeebus.spine source was
 * unavailable at the time, including for this revision. In particular,
 * {@code ShipCommunication#getOwnSki()} is now called before the owning {@link Device}
 * is built/connected (right after constructing the {@code ShipCommunication}); this
 * matches what was read from the SHIP 2.3.0-era source ({@code getOwnSki()} reads the
 * SKI from the certificate that {@code KeyStoreCertificateStorage} loads/generates
 * synchronously in the constructor, with no network dependency), but has not been
 * re-confirmed against 3.0.1. If this call throws or returns an unexpected value,
 * that is the first place to look.
 */
public class Main {
    private static final Logger LOG = LoggerFactory.getLogger(
        MethodHandles.lookup().lookupClass()
    );

    /**
     * How long to wait after starting both devices for mDNS discovery, SPINE
     * UseCasePartner discovery, and the resulting DeviceDiagnosis subscribe/read to
     * complete, before triggering the ControllableSystem device's teardown. Generous
     * on purpose: this all happens locally between two trusted devices, but mDNS
     * *enumeration* can still be slow on hosts with many network interfaces (observed:
     * tens of seconds on a Docker host with many veth interfaces). If the "beginning
     * Use Case execution" log line has not appeared by the time this elapses, the
     * teardown below will still run but the stall will not have anything to reproduce
     * (the ControllableSystem's own Heartbeat will not have been started yet) - try
     * raising this value further in that case.
     */
    private static final long DISCOVERY_SETTLE_TIME_MILLIS = 45_000;

    public static void main(String... args) throws URISyntaxException,
        InterruptedException
    {
        LOG.info(
            "Starting controllable system demo with args {}",
            Arrays.toString(args)
        );

        List<String> argList = Arrays.asList(args);

        String certPath = ClassLoader.getSystemResource("keystore.jks")
            .toURI()
            .getPath();

        LOG.debug("Path to keystore: {}", certPath);

        int csPort = argList.size() >= 2 ? Integer.parseInt(argList.get(1)) : 8080;

        // Any SKIs passed on the command line (e.g. a real external EnergyGuard) are
        // kept trusted in addition to the local EnergyGuardStub below.
        Set<String> cliTrustedSkis = argList.size() >= 3
            ? new HashSet<>(argList.subList(2, argList.size()))
            : new HashSet<>();

        ShipNodeConfiguration shipConfig = new ShipNodeConfiguration(
            argList.isEmpty() ? "0.0.0.0" : argList.get(0),
            csPort,
            "/ship/",
            true,
            "EXAMPLEBRAND-EEB01M3EU-001122334455",
            "local.",
            "Dishwasher ExampleCompany EEB01M4EU",
            "exampleAlias",
            // This keystore is just for reproducability of this demo.
            // NEVER use it in production systems!
            certPath,
            // ALWAYS use your own, strong passphrases in production!
            "CHANGEME".toCharArray(),
            "CHANGEME".toCharArray(),
            "CN=example name",
            3650
        );

        ShipCommunication shipCommunication = new ShipCommunication(
            shipConfig
        ).withConnectClientsTo(TRUSTED);
        if (!cliTrustedSkis.isEmpty()) {
            shipCommunication = shipCommunication.withTrustedSkis(cliTrustedSkis);
        }

        // Read back this device's own SKI *before* build()/connect() - see the
        // "2026-09-14 note" above - so it can be handed to the EnergyGuardStub device
        // below, which needs to trust it back.
        String csOwnSki = shipCommunication.getOwnSki();
        LOG.info("ControllableSystem device SKI: {}", csOwnSki);

        // --- Build the local EnergyGuardStub's ShipCommunication, trusting the
        //     ControllableSystem's SKI from the start. ---
        String stubKeystorePath = Path.of(
            System.getProperty("java.io.tmpdir"),
            "jeebus-demo-energyguard-stub-keystore.jks"
        ).toString();

        LOG.debug("Path to EnergyGuardStub keystore: {}", stubKeystorePath);

        ShipNodeConfiguration stubShipConfig = new ShipNodeConfiguration(
            argList.isEmpty() ? "0.0.0.0" : argList.get(0),
            csPort + 1,
            "/ship/",
            true,
            "EXAMPLEBRAND-EEB01M3EU-ENERGYGUARDSTUB-001",
            "local.",
            "EnergyGuardStub (demo helper - not a real device)",
            "energyGuardStubAlias",
            // Throwaway demo keystore, auto-generated on first run. NEVER reuse
            // this pattern in production systems!
            stubKeystorePath,
            "CHANGEME".toCharArray(),
            "CHANGEME".toCharArray(),
            "CN=demo energyguard stub",
            3650
        );

        ShipCommunication stubCommunication = new ShipCommunication(
            stubShipConfig
        ).withConnectClientsTo(TRUSTED).withTrustedSkis(Set.of(csOwnSki));

        String stubOwnSki = stubCommunication.getOwnSki();
        LOG.info("EnergyGuardStub device SKI: {}", stubOwnSki);

        // --- Now that the stub's SKI is known, finish trusting it on the
        //     ControllableSystem side too. withTrustedSkis() replaces the trusted set
        //     rather than merging into it, so the full desired set is passed here in
        //     one call. ---
        Set<String> csTrustedSkis = new HashSet<>(cliTrustedSkis);
        csTrustedSkis.add(stubOwnSki);
        shipCommunication = shipCommunication.withTrustedSkis(csTrustedSkis);

        ScaledNumberWrapper bigScaledNumber = new ScaledNumberWrapper(12, 6);

        String failsafeDuration = "PT2H";
        LpcCs lpcCs = new LpcCs(
            // TODO: here, you can define initial default values. The javadoc
            //  should explain the different parameters.
            new SimpleLimitationConfig(
                failsafeDuration,
                bigScaledNumber,
                bigScaledNumber,
                bigScaledNumber
        ));
        LppCs lppCs = new LppCs(
            new SimpleLimitationConfig(
                failsafeDuration,
                bigScaledNumber,
                /* For LPP, the LoadControl Limit is negative, but NominalMax
                 * and Failsafe are positive. */
                bigScaledNumber.negate(),
                bigScaledNumber
        ));

        lpcCs.addListener((trigger, state, limit) -> log(
            trigger,
            state,
            limit,
            lpcCs.getCharacteristicType()
        ));
        lppCs.addListener((trigger, state, limit) -> log(
            trigger,
            state,
            limit,
            lppCs.getCharacteristicType()
        ));

        lpcCs.addListener(((event, state, activeLimit) -> {
            // TODO: your listener goes here
        }));

        LOG.info("Initial Limit: {}", lpcCs.getActiveLimit());

        Device csDevice = Device
            .getBuilder()
            // Set the SPINE device type
            .withDeviceType(DeviceTypeEnumType.GENERIC)
            // Set SHIP as the communication protocol
            .withCommunication(shipCommunication)
            // Set the SPINE device ID
            .withId("d:_n:MinimalExample_123")
            /* Enable the automatic SPINE DetailedDiscovery + UseCaseDiscovery of
             * remote devices */
            .withDiscoverDevices(true)
            .addEntity()
            .setType(EntityTypeEnumType.CEM)
            .withUseCases(
                /* Here you can add supported EEBus Use Cases to the device.
                 * These must implement the UseCase interface. */
                lpcCs,
                lppCs
            )
            .applyToDevice()
            .build();

        Device energyGuardStubDevice = Device
            .getBuilder()
            .withDeviceType(DeviceTypeEnumType.GENERIC)
            .withCommunication(stubCommunication)
            .withId("d:_n:MinimalExample_EnergyGuardStub")
            .withDiscoverDevices(true)
            .addEntity()
            .setType(EntityTypeEnumType.CEM)
            .withUseCases(
                // Only registered for LPC - sufficient to trigger the shared
                // DeviceDiagnosis/Heartbeat feature on the ControllableSystem
                // device's entity; LPP would trigger the identical mechanism.
                new EnergyGuardStub("limitationOfPowerConsumption")
            )
            .applyToDevice()
            .build();

        LOG.info(
            "Both devices started. Waiting {} ms for discovery/subscribe to "
                + "complete before triggering the ControllableSystem's teardown...",
            DISCOVERY_SETTLE_TIME_MILLIS
        );
        Thread.sleep(DISCOVERY_SETTLE_TIME_MILLIS);

        LOG.info(
            "=== Triggering ControllableSystem teardown "
                + "(communication.disconnect() + device.close()) ==="
        );
        LOG.info(
            "If this reproduces the known issue, the following steps can take "
                + "many seconds - up to roughly a minute - to complete."
        );

        long teardownStart = System.nanoTime();

        shipCommunication.disconnect();
        long afterDisconnect = System.nanoTime();
        LOG.info(
            "--- communication.disconnect() returned after {} ms ---",
            (afterDisconnect - teardownStart) / 1_000_000
        );

        csDevice.close();
        long afterClose = System.nanoTime();
        LOG.info(
            "--- device.close() returned after {} ms ---",
            (afterClose - afterDisconnect) / 1_000_000
        );

        LOG.info(
            "=== Teardown finished. Total: {} ms ===",
            (afterClose - teardownStart) / 1_000_000
        );

        energyGuardStubDevice.close();

        System.exit(0);
    }

    private static void log(
        Event trigger,
        State state,
        ActiveLimit limit,
        String direction
    ) {
        LOG.info(
            "Event {} was fired resulting in State: {}; Active {} limit: {}",
            trigger.name(),
            state.name(),
            direction,
            limit
        );
    }
}
