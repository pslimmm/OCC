package com.csd.occ.ingestion.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RootCauseContextService {
    private final DemoRunRepository runs;
    private final BusArrivalObservationRepository arrivals;
    private final TrafficIncidentObservationRepository incidents;
    private final HazardObservationRepository hazards;
    private final ReferenceTopologyPort topology;
    private final PollingConfigurationPort configuration;
    private final java.time.Clock clock;

    public RootCauseContextService(DemoRunRepository runs, BusArrivalObservationRepository arrivals,
            TrafficIncidentObservationRepository incidents, HazardObservationRepository hazards,
            ReferenceTopologyPort topology, PollingConfigurationPort configuration, java.time.Clock clock) {
        this.runs = runs; this.arrivals = arrivals; this.incidents = incidents; this.hazards = hazards; this.topology = topology;
        this.configuration = configuration; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RootCauseContextSnapshot find(UUID runId, Instant at, String serviceId, String stopCode,
                                         Integer directionId, List<UUID> observationIds) {
        return findScoped(runId, at, serviceId, stopCode, directionId, observationIds);
    }

    @Transactional(readOnly = true)
    public RootCauseContextSnapshot findLive(Instant at, String serviceId, String stopCode,
            Integer directionId, List<UUID> observationIds) {
        return findScoped(null, at, serviceId, stopCode, directionId, observationIds);
    }

    private RootCauseContextSnapshot findScoped(UUID runId, Instant at, String serviceId, String stopCode,
            Integer directionId, List<UUID> observationIds) {
        String classification = runId == null ? "OBSERVATION_ONLY" : "SIMULATION";
        if (directionId == null || directionId < 1 || at == null || serviceId == null || serviceId.isBlank() || stopCode == null || stopCode.isBlank())
            throw new IllegalArgumentException("at, serviceId and stopCode are required");
        if (observationIds == null || observationIds.size() < 2 || observationIds.size() > 10)
            throw new IllegalArgumentException("arrivalObservationIds must contain between 2 and 10 IDs");
        if (observationIds.stream().distinct().count() != observationIds.size())
            throw new IllegalArgumentException("arrivalObservationIds must be unique");
        Instant cutoff = clock.instant().minus(configuration.retention());
        if (runId == null && (at.isBefore(cutoff) || at.isAfter(clock.instant())))
            throw new IllegalArgumentException("Evidence time is outside the retained observation window");
        DemoRun run = runId == null ? null : runs.findById(runId).orElseThrow(() -> new DemoRunNotFoundException(runId));
        if (run != null && run.expiredAt(cutoff)) throw new DemoRunNotFoundException(runId);
        List<BusArrivalObservation> found = arrivals.findAllById(observationIds);
        if (found.size() != observationIds.size()) throw new IllegalArgumentException("One or more arrival observations do not exist");
        if (found.stream().anyMatch(arrival -> !classification.equals(arrival.getClassification())
                || !Objects.equals(runId, arrival.getDemoRunId())))
            throw new IllegalArgumentException("Arrival observations must belong to the requested simulation run");
        if (found.stream().anyMatch(arrival -> !serviceId.equals(arrival.getServiceId())
                || !stopCode.equals(arrival.getStopCode()) || !Objects.equals(directionId, arrival.getDirectionId())
                || arrival.getFetchedAt() == null || arrival.getFetchedAt().isAfter(at) || runId == null && arrival.getFetchedAt().isBefore(cutoff)))
            throw new IllegalArgumentException("Arrival observations must match the confirmed event and its time window");

        List<String> missing = new ArrayList<>();
        List<RootCauseContextSnapshot.RootCauseArrival> arrivalEvidence = found.stream().map(arrival -> {
            String evidenceId = "bus-arrival:" + arrival.getId();
            if (arrival.getLatitude() == null || arrival.getLongitude() == null) missing.add("arrival-location:" + arrival.getId());
            return new RootCauseContextSnapshot.RootCauseArrival(evidenceId, arrival.getId(), arrival.getRawObservationId(),
                    arrival.getPollRunId(), arrival.getServiceId(), arrival.getStopCode(), arrival.getDirectionId(),
                    arrival.getEstimatedArrivalAt(), arrival.getFetchedAt(), arrival.getLatitude(), arrival.getLongitude(),
                    arrival.getLoadCategory(), arrival.getVehicleType(),
                    arrival.getClassification(), arrival.getDemoRunId(), arrival.getReferenceVersionId());
        }).toList();

        List<UUID> referenceVersions = arrivalEvidence.stream()
                .map(RootCauseContextSnapshot.RootCauseArrival::referenceVersionId)
                .filter(Objects::nonNull).distinct().toList();
        UUID reference = referenceVersions.size() == 1 ? referenceVersions.getFirst() : null;
        if (reference == null) missing.add("reference-version");
        if (referenceVersions.size() > 1) missing.add("mixed-reference-versions");

        var scenarioHazards = run != null && reference != null
                ? scenarioHazards(run, at, reference, serviceId, directionId, stopCode)
                : List.<ScenarioIncident>of();
        List<TrafficIncidentObservation> scopedIncidents = runId == null
                ? incidents.findByClassificationAndDemoRunIdIsNullAndFetchedAtBetweenOrderByFetchedAtDesc(
                        classification, cutoff, at, org.springframework.data.domain.PageRequest.of(0, 50))
                : incidents.findByDemoRunIdOrderByFetchedAtDesc(runId);
        var trafficIncidents = scopedIncidents.stream()
                .filter(incident -> classification.equals(incident.classification()))
                .filter(incident -> incident.observedAt() == null || !incident.observedAt().isAfter(at))
                .filter(incident -> !incident.fetchedAt().isAfter(at))
                .map(incident -> {
                    if (!Objects.equals(runId, incident.demoRunId()))
                        throw new IllegalArgumentException("Traffic incidents must belong to the requested simulation run");
                    if (incident.latitude() == null || incident.longitude() == null)
                        missing.add("incident-location:" + incident.id());
                    return new RootCauseContextSnapshot.RootCauseIncident("traffic-incident:" + incident.id(),
                            incident.id(), incident.incidentType(), incident.message(), incident.latitude(), incident.longitude(),
                            incident.observedAt(), incident.fetchedAt(), incident.classification(), incident.demoRunId());
                });
        var incidentEvidence = Stream.concat(scenarioHazards.stream().map(ScenarioIncident::evidence), trafficIncidents).limit(50).toList();
        if (incidentEvidence.isEmpty()) missing.add("traffic-incidents");
        var worksite = scenarioHazards.stream().filter(ScenarioIncident::worksite).map(ScenarioIncident::evidence)
                .findFirst().orElse(null);
        return new RootCauseContextSnapshot(classification, runId, reference, arrivalEvidence, incidentEvidence, missing,
                simulationDelay(run, found, worksite));
    }

    private List<ScenarioIncident> scenarioHazards(DemoRun run, Instant at,
            UUID referenceVersionId, String serviceId, int directionId, String stopCode) {
        var observations = hazards.findByDemoRunIdAndClassificationAndFetchedAtLessThanEqualOrderByFetchedAtDescIdAsc(
                run.id(), "SIMULATION", at);
        if (observations.isEmpty()) return List.of();
        boolean legacyRoadworks = run.configuration().scenario() == DemoScenario.ROADWORKS_BUNCHING;
        var routeStops = topology.busRoute(referenceVersionId, serviceId).stream()
                .flatMap(route -> route.operators().stream())
                .flatMap(operator -> operator.directions().stream())
                .filter(direction -> direction.directionId() == directionId)
                .flatMap(direction -> direction.stops().stream())
                .filter(stop -> legacyRoadworks || stopCode.equals(stop.stopCode()))
                .toList();
        var seen = new HashSet<String>();
        return observations.stream()
                .filter(observation -> observation.facts().reportedAt() == null || !observation.facts().reportedAt().isAfter(at))
                .filter(observation -> seen.add(observation.sourceIncidentKey()))
                .filter(observation -> isActiveAt(observation.facts(), at))
                .flatMap(observation -> observation.facts().locations().stream()
                        .filter(location -> routeStops.stream().anyMatch(stop -> matches(location, stop)))
                        .limit(1)
                        .map(location -> new ScenarioIncident(scenarioEvidence(observation, location),
                                observation.facts().kind() == HazardKind.ROADWORKS
                                && DemoHazardScenario.ROADWORKS_SOURCE_KEY.equals(observation.facts().sourceEventKey())
                                && "WORKSITE".equals(location.geometryRole()) && stopCode.equals(location.sourceReference()))))
                .toList();
    }

    private RootCauseContextSnapshot.SimulationDelay simulationDelay(DemoRun run, List<BusArrivalObservation> confirmed,
            RootCauseContextSnapshot.RootCauseIncident worksite) {
        if (run == null || worksite == null) return null;
        var roads = DemoReplayScenario.roadworks(run);
        if (roads == null) return null;
        var lead = confirmed.stream().filter(arrival -> belongsToBus(run, arrival, 1)
                        && !arrival.getFetchedAt().isBefore(roads.reachesWorksiteAt())
                        && !arrival.getFetchedAt().isBefore(worksite.fetchedAt()))
                .max(Comparator.comparing(BusArrivalObservation::getFetchedAt)).orElse(null);
        if (lead == null) return null;
        var follower = confirmed.stream().filter(arrival -> belongsToBus(run, arrival, 2)
                        && lead.getPollRunId() != null && lead.getPollRunId().equals(arrival.getPollRunId())
                        && Objects.equals(lead.getFetchedAt(), arrival.getFetchedAt()))
                .findFirst().orElse(null);
        if (follower == null || follower.getEstimatedArrivalAt() == null || lead.getEstimatedArrivalAt() == null
                || !Duration.between(lead.getEstimatedArrivalAt(), follower.getEstimatedArrivalAt())
                        .equals(Duration.ofSeconds(DemoReplayScenario.ROADWORKS_HEADWAY_SECONDS))) return null;

        var baseline = arrivals.findDemoBaselinePair(run.id(), "demo:" + run.id() + ":0").stream()
                .filter(arrival -> Objects.equals(lead.getReferenceVersionId(), arrival.getReferenceVersionId())
                        && arrival.getFetchedAt() != null && arrival.getFetchedAt().isBefore(roads.reachesWorksiteAt()))
                .toList();
        boolean measuredChange = baseline.size() == 2 && baseline.stream().anyMatch(arrival -> belongsToBus(run, arrival, 1)
                        && arrival.getEstimatedArrivalAt() != null && lead.getEstimatedArrivalAt().equals(
                                arrival.getEstimatedArrivalAt().plusSeconds(roads.delaySeconds())))
                && baseline.stream().anyMatch(arrival -> belongsToBus(run, arrival, 2)
                        && follower.getEstimatedArrivalAt().equals(arrival.getEstimatedArrivalAt()));
        return measuredChange ? new RootCauseContextSnapshot.SimulationDelay(worksite.evidenceId(), lead.getId(),
                follower.getId(), lead.getEstimatedArrivalAt().minusSeconds(roads.delaySeconds()), Math.toIntExact(roads.delaySeconds())) : null;
    }

    private boolean belongsToBus(DemoRun run, BusArrivalObservation arrival, int busNumber) {
        var configuration = run.configuration();
        return Objects.equals(arrival.getVisitOrder(), busNumber) && configuration.serviceId().equals(arrival.getServiceId())
                && configuration.monitoredStopCode().equals(arrival.getStopCode())
                && Objects.equals(configuration.directionId(), arrival.getDirectionId())
                && DemoVehicleAssignmentId.tripId(configuration.serviceId(), busNumber).equals(arrival.getTripId());
    }

    private boolean isActiveAt(HazardFeedPort.Fact fact, Instant at) {
        return !"CANCEL".equals(fact.sourceAction()) && !"RECOVERED".equals(fact.sourceAction())
                && (fact.effectiveFrom() == null || !fact.effectiveFrom().isAfter(at))
                && (fact.effectiveUntil() == null || fact.effectiveUntil().isAfter(at));
    }

    private boolean matches(HazardLocation location, ReferenceTopologyPort.BusRouteStop stop) {
        if ("WORKSITE".equals(location.geometryRole())) return stop.stopCode().equals(location.sourceReference());
        if ("ROAD_NAME".equals(location.geometryRole()))
            return location.description() != null && stop.roadName() != null && !location.description().isBlank()
                    && roadName(location.description()).equals(roadName(stop.roadName()));
        if (location.radiusKm() == null || location.latitude() == null || location.longitude() == null
                || stop.latitude() == null || stop.longitude() == null) return false;
        double latitude = Math.toRadians(location.latitude().doubleValue());
        double stopLatitude = Math.toRadians(stop.latitude().doubleValue());
        double longitudeDelta = Math.toRadians(stop.longitude().subtract(location.longitude()).doubleValue());
        double haversine = Math.pow(Math.sin((stopLatitude - latitude) / 2), 2)
                + Math.cos(latitude) * Math.cos(stopLatitude) * Math.pow(Math.sin(longitudeDelta / 2), 2);
        double distanceKm = 6_371 * 2 * Math.asin(Math.sqrt(Math.clamp(haversine, 0, 1)));
        return distanceKm <= location.radiusKm().doubleValue();
    }

    private String roadName(String value) {
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private RootCauseContextSnapshot.RootCauseIncident scenarioEvidence(HazardObservation observation, HazardLocation location) {
        var fact = observation.facts();
        String message = fact.title() + (location.description() == null ? "" : " (" + location.description() + ")")
                + (fact.description() == null ? "" : ". " + fact.description());
        return new RootCauseContextSnapshot.RootCauseIncident("hazard-observation:" + observation.id(),
                observation.id(), fact.kind().name(), message, location.latitude(), location.longitude(),
                fact.reportedAt(), observation.fetchedAt(), observation.classification(), observation.demoRunId());
    }

    private record ScenarioIncident(RootCauseContextSnapshot.RootCauseIncident evidence, boolean worksite) {}
}
