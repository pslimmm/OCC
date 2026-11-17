package com.csd.occ.planning.diagnosis.domain;

import java.time.Instant;
import java.util.UUID;

public record SimulationDelayEvidence(String hazardEvidenceId, UUID leadingObservationId, UUID followingObservationId,
                                      Instant baselineLeadingArrivalAt, int configuredDelaySeconds) {
    public SimulationDelayEvidence {
        EvidenceBounds.bounded(hazardEvidenceId, 200, "simulation-delay-hazard-evidence-id");
    }
}
