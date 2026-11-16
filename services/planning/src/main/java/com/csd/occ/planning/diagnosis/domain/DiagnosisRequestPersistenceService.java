package com.csd.occ.planning.diagnosis.domain;

import com.csd.occ.planning.advisory.domain.SimulationCautionPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiagnosisRequestPersistenceService {
    private final DiagnosisRequestRepository requests;
    private final SnapshotCodecPort snapshots;
    private final Clock clock;

    public DiagnosisRequestPersistenceService(DiagnosisRequestRepository requests, SnapshotCodecPort snapshots,
            Clock clock) {
        this.requests = requests;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<DiagnosisRequest> findById(UUID id) {
        return requests.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<DiagnosisRequest> findByEvent(String eventType, String producer, UUID eventId,
            String schemaVersion) {
        return requests.findByEventTypeAndProducerAndEventIdAndSchemaVersion(eventType, producer, eventId,
                schemaVersion);
    }

    @Transactional
    public DiagnosisRequest enqueueProducer(ProducerEventSubmission submission, UUID id, String inputSnapshot) {
        DiagnosisRequest byId = requests.findById(id).orElse(null);
        if (byId != null) return retainProducerSnapshot(byId, submission, inputSnapshot);

        DiagnosisRequest byEvent = findProducerEvent(submission);
        if (byEvent != null) return retainProducerSnapshot(byEvent, submission, inputSnapshot);

        requests.insertIfAbsent(id, submission.eventType().name(), submission.producer(), submission.eventId(),
                DiagnosisRequestService.SCHEMA_VERSION, clock.instant());
        DiagnosisRequest createdById = requests.findById(id).orElse(null);
        if (createdById != null) return retainProducerSnapshot(createdById, submission, inputSnapshot);
        DiagnosisRequest createdByEvent = findProducerEvent(submission);
        if (createdByEvent != null) return retainProducerSnapshot(createdByEvent, submission, inputSnapshot);
        throw new DiagnosisRequestService.IdempotencyConflictException();
    }

    @Transactional
    public DiagnosisRequest enqueueLegacy(EventType eventType, String producer, UUID eventId, UUID id,
            String inputSnapshot) {
        DiagnosisRequest byId = requests.findById(id).orElse(null);
        if (byId != null) {
            if (!sameRequest(byId, eventType, producer, eventId))
                throw new DiagnosisRequestService.IdempotencyConflictException();
            if (byId.inputSnapshot() == null && inputSnapshot != null) byId.retainInput(inputSnapshot);
            return byId;
        }
        DiagnosisRequest byEvent = requests.findByEventTypeAndProducerAndEventIdAndSchemaVersion(
                eventType.name(), producer, eventId, DiagnosisRequestService.SCHEMA_VERSION).orElse(null);
        if (byEvent != null) {
            if (byEvent.inputSnapshot() == null && inputSnapshot != null) byEvent.retainInput(inputSnapshot);
            return byEvent;
        }
        requests.insertIfAbsent(id, eventType.name(), producer, eventId, DiagnosisRequestService.SCHEMA_VERSION,
                clock.instant());
        DiagnosisRequest createdById = requests.findById(id).orElse(null);
        if (createdById != null) {
            if (!sameRequest(createdById, eventType, producer, eventId))
                throw new DiagnosisRequestService.IdempotencyConflictException();
            if (createdById.inputSnapshot() == null && inputSnapshot != null) createdById.retainInput(inputSnapshot);
            return createdById;
        }
        return requests.findByEventTypeAndProducerAndEventIdAndSchemaVersion(eventType.name(), producer, eventId,
                DiagnosisRequestService.SCHEMA_VERSION).orElseThrow(DiagnosisRequestService.IdempotencyConflictException::new);
    }

    @Transactional
    public List<UUID> claimDueBunchingDiagnoses(int batchSize, Duration leaseDuration, boolean autoProcessBunching) {
        Instant now = clock.instant();
        return claim(requests.findClaimableBunchingDiagnoses(now, batchSize, autoProcessBunching), now, leaseDuration);
    }

    @Transactional
    public List<UUID> claimDueHazards(int batchSize, Duration leaseDuration) {
        Instant now = clock.instant();
        return claim(requests.findClaimableHazards(now, batchSize, SimulationCautionPolicy.KINDS), now, leaseDuration);
    }

    @Transactional
    public List<UUID> claimDueSimulationCautions(int batchSize, Duration leaseDuration, PlanningScope scope) {
        Instant now = clock.instant();
        var claimable = requests.findClaimableSimulationCautions(now, batchSize,
                Objects.toString(scope.demoRunId(), ""), SimulationCautionPolicy.KINDS);
        return claim(claimable, now, leaseDuration);
    }

    private List<UUID> claim(List<DiagnosisRequest> claimable, Instant now, Duration leaseDuration) {
        return claimable.stream().map(request -> {
            request.claim(now.plus(leaseDuration), now);
            return request.id();
        }).toList();
    }

    private DiagnosisRequest findProducerEvent(ProducerEventSubmission submission) {
        return requests.findByEventTypeAndProducerAndEventIdAndSchemaVersion(submission.eventType().name(),
                submission.producer(), submission.eventId(), DiagnosisRequestService.SCHEMA_VERSION).orElse(null);
    }

    private DiagnosisRequest retainProducerSnapshot(DiagnosisRequest existing, ProducerEventSubmission submission,
            String inputSnapshot) {
        if (!sameRequest(existing, submission.eventType(), submission.producer(), submission.eventId()))
            throw new DiagnosisRequestService.IdempotencyConflictException();
        if (existing.inputSnapshot() == null) {
            if (inputSnapshot == null) throw new DiagnosisRequestService.IdempotencyConflictException();
            existing.retainInput(inputSnapshot);
        } else if (inputSnapshot != null && !sameProducerFacts(existing.inputSnapshot(), submission)) {
            throw new DiagnosisRequestService.IdempotencyConflictException();
        }
        return existing;
    }

    private boolean sameProducerFacts(String inputSnapshot, ProducerEventSubmission submission) {
        try {
            if (submission.eventType() == EventType.BUS_BUNCHING)
                return snapshots.decode(inputSnapshot).event().equals(submission.eventSnapshot());
            return snapshots.decodeProducerEvent(inputSnapshot).equals(submission);
        } catch (RuntimeException malformedSnapshot) {
            return false;
        }
    }

    private boolean sameRequest(DiagnosisRequest existing, EventType eventType, String producer, UUID eventId) {
        return existing.eventId().equals(eventId) && existing.eventType().equals(eventType.name())
                && existing.producer().equals(producer)
                && existing.schemaVersion().equals(DiagnosisRequestService.SCHEMA_VERSION);
    }
}
