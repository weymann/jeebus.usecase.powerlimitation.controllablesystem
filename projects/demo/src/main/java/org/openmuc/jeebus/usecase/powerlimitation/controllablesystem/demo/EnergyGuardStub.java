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

import org.openmuc.jeebus.spine.api.Entity;
import org.openmuc.jeebus.spine.spi.FeatureRequirement;
import org.openmuc.jeebus.spine.spi.Inject;
import org.openmuc.jeebus.spine.spi.UseCase;
import org.openmuc.jeebus.spine.utils.features.devicediagnosis.HeartbeatDataFunction;
import org.openmuc.jeebus.spine.xsd.v1.EntityTypeEnumType;
import org.openmuc.jeebus.spine.xsd.v1.FeatureAddressType;
import org.openmuc.jeebus.spine.xsd.v1.FeatureTypeEnumType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Set;

import static org.openmuc.jeebus.spine.xsd.v1.RoleType.SERVER;

/**
 * Deliberately minimal, INCOMPLETE stand-in for a real "EnergyGuard" actor. It exists
 * for exactly one purpose: to make {@link org.openmuc.jeebus.usecase.powerlimitation.controllablesystem.lpc.LpcCs}
 * (the ControllableSystem actor under test, see {@link Main}) complete a real SPINE
 * UseCasePartner discovery and start its own outgoing DeviceDiagnosis Heartbeat -
 * see {@code LimitationThread#startRuntimeScenarioCommunication()} in the
 * abstract-controllablesystem module, which calls
 * {@code LimitationUseCaseImpl#startHeartbeat()} only once such a partner has been
 * found and its DeviceDiagnosis feature has been subscribed to/read successfully.
 *
 * <p>
 * This is NOT a usable EnergyGuard implementation: it does not implement Scenario 1
 * (LoadControl limit writes) or any other part of the LPC/LPP protocol - it only hosts
 * a DeviceDiagnosis SERVER feature (with a HeartbeatDataFunction) and declares itself
 * as an "EnergyGuard" actor supporting "limitationOfPowerConsumption", which is all
 * that {@code UseCaseDiscoveryWrapper} (jeebus.spine) actually checks when matching a
 * partner for {@link org.openmuc.jeebus.usecase.powerlimitation.controllablesystem.LimitationUseCaseImpl}'s
 * {@code NodeManagement.addUseCaseListener(...)} call: actor + use case name, plus the
 * presence of the required DeviceDiagnosis/HeartbeatData function on the partner. See
 * README "Reproducing the dispose()/close() stall" for what this is used for.
 * </p>
 *
 * <p>
 * <strong>2026-09-14 note:</strong> written and verified against jEEBus.SHIP 2.3.0 /
 * jEEBus.SPINE (pre-4.1.1). This repo's dependencies have since moved to SHIP 3.0.1 /
 * SPINE 4.1.1 (a release the project's own memory already flagged as containing
 * breaking API changes) - re-applied here unchanged because the environment used to
 * re-verify it against current jeebus.ship/jeebus.spine source was unavailable at the
 * time. Re-check this file (and {@link Main}) against the current SPINE
 * {@code UseCase}/{@code FeatureRequirement}/{@code DeviceDiagnosisFeature}/
 * {@code HeartbeatDataFunction} APIs and the current SHIP
 * {@code ShipNodeConfiguration}/{@code ShipCommunication} APIs before relying on a
 * successful build.
 * </p>
 */
public class EnergyGuardStub implements UseCase {
    private static final Logger LOG = LoggerFactory.getLogger(
        MethodHandles.lookup().lookupClass()
    );

    private final String useCaseName;

    @Inject
    private Entity entity;

    private FeatureAddressType address;

    public EnergyGuardStub(String useCaseName) {
        this.useCaseName = useCaseName;
    }

    @Override
    public String getActor() {
        return "EnergyGuard";
    }

    @Override
    public String getName() {
        return useCaseName;
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public List<Long> getScenarioSupport() {
        // Only Scenario 3 (Heartbeat) - the only part of the protocol this stub
        // actually implements.
        return List.of(3L);
    }

    @Override
    public FeatureAddressType getAddress() {
        if (address == null) {
            throw new IllegalStateException("getAddress() called before setup()");
        }
        return address;
    }

    @Override
    public void setup() {
        address = new FeatureAddressType()
            .withDevice(entity.getStaticAddress().getDevice())
            .withEntity(entity.getStaticAddress().getEntity());

        LOG.info(
            "EnergyGuardStub ready at {} - hosting a DeviceDiagnosis/Heartbeat "
                + "SERVER feature for the ControllableSystem actor under test to "
                + "discover, subscribe to and read. This stub does not start its "
                + "own outgoing heartbeat and implements nothing else - see the "
                + "class javadoc.",
            address
        );
    }

    @Override
    public Set<FeatureRequirement> getFeatureRequirements(
        EntityTypeEnumType entityType
    ) {
        return Set.of(new FeatureRequirement(
            FeatureTypeEnumType.DEVICE_DIAGNOSIS,
            SERVER,
            HeartbeatDataFunction.class
        ));
    }

    @Override
    public void close() {
        // nothing to clean up - no threads/resources of our own were started.
    }
}
