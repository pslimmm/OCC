package com.csd.occ.planning.diagnosis.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

final class SimulationRoadworksDiagnosisPolicy {
    static final String CAUSE_CODE = "SIMULATED_ROADWORKS_DELAY";
    static final String VERSION = "simulation-roadworks-delay-v1";

    private SimulationRoadworksDiagnosisPolicy() { }

    static Optional<VerifiedDelay> verify(EvidenceSnapshot snapshot) {
        var context = snapshot.context();
        var event = snapshot.event();
        var delay = context.simulationDelay();
        if (delay == null || !"CONFIRMED".equals(event.status()) || !"SIMULATION".equals(snapshot.classification())
                || !"SIMULATION".equals(event.classification()) || !"SIMULATION".equals(context.classification())
                || event.demoRunId() == null || !event.demoRunId().equals(context.demoRunId())
                || event.confirmingObservationTimes().size() < 2 || delay.configuredDelaySeconds() <= 0
                || delay.baselineLeadingArrivalAt() == null || delay.hazardEvidenceId() == null
                || event.leadingObservationId() == null || event.followingObservationId() == null
                || event.leadingObservationId().equals(event.followingObservationId())
                || !event.leadingObservationId().equals(delay.leadingObservationId())
                || !event.followingObservationId().equals(delay.followingObservationId())) return Optional.empty();

        Instant confirmation = event.confirmingObservationTimes().getLast();
        var leading = arrival(snapshot, delay.leadingObservationId(), confirmation);
        var following = arrival(snapshot, delay.followingObservationId(), confirmation);
        if (leading == null || following == null || leading.pollRunId() == null
                || !leading.pollRunId().equals(following.pollRunId()) || leading.rawObservationId() == null
                || !leading.rawObservationId().equals(following.rawObservationId())) return Optional.empty();

        var roadworks = context.incidents().stream()
                .filter(incident -> delay.hazardEvidenceId().equals(incident.evidenceId()) && incident.isSimulationHazard()
                        && "ROADWORKS".equals(incident.incidentType()) && event.demoRunId().equals(incident.demoRunId())
                        && snapshot.allowedEvidenceIds().contains(incident.evidenceId())
                        && incident.fetchedAt() != null && !incident.fetchedAt().isAfter(confirmation)
                        && (incident.observedAt() == null || !incident.observedAt().isAfter(confirmation)))
                .findFirst().orElse(null);
        if (roadworks == null) return Optional.empty();

        Duration leadDelay = Duration.between(delay.baselineLeadingArrivalAt(), leading.estimatedArrivalAt());
        long observedGap = Duration.between(leading.estimatedArrivalAt(), following.estimatedArrivalAt()).toSeconds();
        if (!leadDelay.equals(Duration.ofSeconds(delay.configuredDelaySeconds())) || observedGap <= 0
                || observedGap != event.observedHeadwaySeconds().getLast()) return Optional.empty();

        long baselineGap = Duration.between(delay.baselineLeadingArrivalAt(), following.estimatedArrivalAt()).toSeconds();
        List<String> evidenceIds = Stream.concat(event.evidenceIds().stream(),
                Stream.of(roadworks.evidenceId(), leading.evidenceId(), following.evidenceId())).distinct().toList();
        return Optional.of(new VerifiedDelay(roadworks.evidenceId(), delay.configuredDelaySeconds(),
                baselineGap, observedGap, evidenceIds));
    }

    private static ArrivalEvidence arrival(EvidenceSnapshot snapshot, UUID id, Instant confirmation) {
        var event = snapshot.event();
        if (!event.confirmingObservationIds().contains(id)) return null;
        return snapshot.context().arrivals().stream().filter(arrival -> id.equals(arrival.observationId())
                && "SIMULATION".equals(arrival.classification()) && event.demoRunId().equals(arrival.demoRunId())
                && Objects.equals(event.serviceId(), arrival.serviceId()) && Objects.equals(event.monitoredStopCode(), arrival.stopCode())
                && Objects.equals(event.directionId(), arrival.directionId()) && confirmation.equals(arrival.fetchedAt())
                && arrival.estimatedArrivalAt() != null && arrival.evidenceId() != null
                && snapshot.allowedEvidenceIds().contains(arrival.evidenceId())).findFirst().orElse(null);
    }

    record VerifiedDelay(String hazardEvidenceId, int delaySeconds, long baselineGapSeconds,
                         long observedGapSeconds, List<String> supportingEvidenceIds) { }
}
