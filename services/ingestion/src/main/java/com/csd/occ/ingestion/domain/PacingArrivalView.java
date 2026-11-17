package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.UUID;

/** Read-only projection of one current predicted arrival used for pacing. */
public interface PacingArrivalView {
    String getClassification();
    String getStopCode();
    String getServiceId();
    Integer getDirectionId();
    Instant getEstimatedArrivalAt();
    Instant getFetchedAt();
    /** Null for live predictions: Bus Arrival does not identify vehicles. */
    String getVehicleReference();
    /** Null for live predictions. */
    UUID getDemoRunId();
    boolean isReplayComplete();
    boolean isRoadworksScenario();
}
