package com.csd.occ.planning.advisory.domain;

import com.csd.occ.planning.diagnosis.domain.*;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class HazardAdvisoryProcessor {
    private final DiagnosisRequestRepository requests;
    private final SnapshotCodecPort snapshots;
    private final AdvisoryContextPort context;
    private final PlanningScopePort scopes;
    private final AdvisoryDraftPort drafts;
    private final AdvisoryProposalService proposals;
    private final DiagnosisCompletionService completion;
    private final DiagnosisPolicySettings settings;
    private final Clock clock;
    private final AdvisoryReconciliationService reconciliation;
    private final InstructionPolicy policy = new InstructionPolicy();
    public HazardAdvisoryProcessor(DiagnosisRequestRepository requests, SnapshotCodecPort snapshots,
            AdvisoryContextPort context, PlanningScopePort scopes, AdvisoryDraftPort drafts,
            AdvisoryProposalService proposals, DiagnosisCompletionService completion, DiagnosisPolicySettings settings, Clock clock,
            AdvisoryReconciliationService reconciliation) {
        this.requests = requests; this.snapshots = snapshots; this.context = context; this.scopes = scopes;
        this.drafts = drafts; this.proposals = proposals; this.completion = completion; this.settings = settings; this.clock = clock;
        this.reconciliation = reconciliation;
    }
    public boolean processIfHazard(UUID requestId) {
        var request = requests.findById(requestId).orElseThrow();
        if (!List.of("HAZARD", "TRAFFIC_INCIDENT").contains(request.eventType())) return false;
        try {
            var submission = snapshots.decodeProducerEvent(request.inputSnapshot());
            var submitted = submission.hazardSnapshot();
            UUID disruptionId = submitted == null ? submission.trafficSnapshot().disruptionId() : submitted.disruptionId();
            UUID sourceObservationId = submitted == null ? submission.trafficSnapshot().sourceObservationId() : submitted.sourceObservationId();
            Long sourceRevision = submitted == null ? submission.trafficSnapshot().sourceRevision() : submitted.sourceRevision();
            String targetBusId = submitted == null ? null : submitted.targetBusId();
            String action = submitted == null ? submission.trafficSnapshot().action() : submitted.action();
            if (action.equals("CLEARED")) {
                // A delayed clear must not withdraw instructions for a subsequently reopened episode.
                if ("CLEARED".equals(context.hazard(disruptionId).status()))
                    reconciliation.sourceCleared(disruptionId, submitted == null ? "NO_LONGER_REPORTED" : submitted.inactiveReason());
                proposals.record(requestId, List.of()); return true;
            }
            var scope = scopes.current();
            if (submitted != null && (submitted.targetRunId() != null && !submitted.targetRunId().equals(scope.demoRunId())
                    || submitted.demoRunId() != null && !submitted.demoRunId().equals(scope.demoRunId()))) {
                proposals.record(requestId, List.of());
                return true;
            }
            if (scope.demoRunId() == null) {
                proposals.record(requestId, List.of()); return true;
            }
            var hazard = context.hazard(disruptionId);
            if (!hazard.status().equals("ACTIVE") || !matchesEvidence(hazard, sourceObservationId, sourceRevision)) {
                proposals.record(requestId, List.of()); return true;
            }
            var fleet = context.fleet(scope.demoRunId());
            List<AdvisoryProposal> result = new ArrayList<>();
            if (List.of("RUNNING", "REPLAY_COMPLETE").contains(fleet.status())) for (var bus : fleet.items()) {
                if (targetBusId != null && !targetBusId.equals(bus.busId())) continue;
                if (hazard.downstreamExposures(bus).findAny().isEmpty()) continue;
                var limits = context.limits();
                var draft = drafts.propose(hazard, bus, limits);
                String preview = policy.message(draft.candidate(), bus, hazard, limits);
                result.add(new AdvisoryProposal(submission.eventId(), scope.demoRunId(), hazard, bus, draft, preview, clock.instant()));
            }
            proposals.record(requestId, result);
        } catch (IllegalArgumentException invalid) {
            completion.fail(requestId, "HAZARD_CANDIDATE_INVALID", false, false, clock.instant(), List.of(), null);
        } catch (RuntimeException unavailable) {
            completion.fail(requestId, "HAZARD_CONTEXT_OR_MODEL_UNAVAILABLE", true,
                    request.attemptCount() < settings.maxAttempts(), clock.instant().plus(settings.retryInitialDelay()), List.of(), null);
        }
        return true;
    }

    private static boolean matchesEvidence(AdvisoryContextPort.Hazard current, UUID sourceObservationId,
            Long sourceRevision) {
        if (SimulationCautionPolicy.appliesTo(current.classification(), current.kind()))
            return Objects.equals(sourceObservationId, current.sourceObservationId());
        return sourceRevision != null
                ? sourceRevision.equals(current.sourceRevision())
                : sourceObservationId.equals(current.sourceObservationId());
    }
}
