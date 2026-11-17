package com.csd.occ.planning.advisory.domain;

import java.util.List;
import java.util.UUID;

public record AdvisoryGroup(Key key, List<AdvisoryProposal> members, List<MovementConflict> movementConflicts) {
    public record MovementConflict(UUID proposalId, String busId, UUID instructionId, String message) {}

    public record Key(UUID demoRunId, UUID disruptionId, String sourceRevisionKey, String serviceId,
                      int directionId) {
        static Key from(AdvisoryProposal proposal) {
            return new Key(proposal.demoRunId(), proposal.disruptionId(), proposal.sourceRevisionKey(),
                    proposal.bus().serviceId(), proposal.bus().directionId());
        }
    }
}
