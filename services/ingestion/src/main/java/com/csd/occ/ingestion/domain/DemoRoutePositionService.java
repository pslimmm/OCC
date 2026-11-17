package com.csd.occ.ingestion.domain;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoRoutePositionService {
    private final DemoRunRepository runs;
    private final DemoVehicleAssignmentRepository assignments;
    private final ReferenceTopologyPort topology;
    private final Clock clock;

    public DemoRoutePositionService(DemoRunRepository runs, DemoVehicleAssignmentRepository assignments,
            ReferenceTopologyPort topology, Clock clock) {
        this.runs = runs;
        this.assignments = assignments;
        this.topology = topology;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RoutePositionSnapshot positions(UUID runId) {
        DemoRun run = runs.findById(runId).orElseThrow(() -> new DemoRunNotFoundException(runId));
        if (!List.of("RUNNING", "REPLAY_COMPLETE").contains(run.status()))
            throw new IllegalArgumentException("Simulation route positions are unavailable for this run");
        var version = topology.activeVersion().orElseThrow(() -> new ReferenceTopologyUnavailableException());
        var timeline = timeline(run, version).orElseThrow(() ->
                new IllegalArgumentException("Selected service, direction, or stop is unavailable"));
        var configuration = run.configuration();
        var buses = assignments.findByIdDemoRunIdOrderByBusId(runId, PageRequest.of(0, configuration.busCount()))
                .stream().map(assignment -> timeline.trips().get(assignment.id().tripId()))
                .filter(java.util.Objects::nonNull).map(TripPhase::position)
                .sorted(Comparator.comparingInt(RoutePositionBus::order)).toList();
        return new RoutePositionSnapshot(configuration.serviceId(), timeline.computedAt(), Optional.empty(),
                Optional.of(timeline.computedAt()), 1, 86400,
                List.of(new RoutePositionDirection(timeline.operator(), configuration.directionId(), timeline.routeLengthM(), buses)));
    }

    Optional<Timeline> timeline(DemoRun run) {
        return topology.activeVersion().flatMap(version -> timeline(run, version));
    }

    private Optional<Timeline> timeline(DemoRun run, ReferenceTopologyPort.ReferenceTopologyVersion version) {
        var configuration = run.configuration();
        return topology.busRoute(version.id(), configuration.serviceId()).stream()
                .flatMap(route -> route.operators().stream())
                .flatMap(operator -> operator.directions().stream()
                        .filter(direction -> direction.directionId() == configuration.directionId())
                        .map(direction -> timeline(run, configuration, version.id(), operator.operator(), direction.stops())))
                .findFirst().flatMap(value -> value);
    }

    private Optional<Timeline> timeline(DemoRun run, DemoRunConfiguration configuration, UUID referenceVersionId,
            String operator, List<ReferenceTopologyPort.BusRouteStop> stops) {
        List<StopAtDistance> stopDistances = new ArrayList<>(stops.size());
        double routeDistance = 0;
        for (int index = 0; index < stops.size(); index++) {
            var stop = stops.get(index);
            if (index > 0) routeDistance += distance(stops.get(index - 1), stop);
            stopDistances.add(new StopAtDistance(stop.stopCode(), stop.stopSequence(), routeDistance));
        }
        var selectedStop = stopDistances.stream().filter(stop -> configuration.monitoredStopCode().equals(stop.stopCode()))
                .findFirst();
        if (selectedStop.isEmpty()) return Optional.empty();

        double routeLength = Math.max(1, routeDistance);
        double cruisingSpeedMps = DemoReplayScenario.cruisingSpeedKmh(configuration.scenario()) / 3.6;
        Instant now = clock.instant();
        double elapsedSeconds = Math.max(0, Duration.between(run.startedAt(), now).toMillis()) / 1_000.0;
        var roadworks = DemoReplayScenario.roadworks(run);
        double approachDistance = roadworks == null ? 30
                : DemoReplayScenario.ROADWORKS_APPROACH_METRES + DemoReplayScenario.WORKSITE_TO_STOP_METRES;
        double startingDistance = Math.max(0, selectedStop.get().distanceM() - approachDistance);
        double leadDistance = startingDistance + cruisingSpeedMps * elapsedSeconds;
        long gapSeconds = 0;
        Map<String, TripPhase> trips = new HashMap<>();
        for (int number = 1; number <= configuration.busCount(); number++) {
            if (number > 1) gapSeconds += configuration.busGaps().get(number - 2).toSeconds();
            var delayed = number == 1 && roadworks != null;
            double delay = delayed ? roadworks.elapsedWaitSeconds(now) : 0;
            double distance = leadDistance - cruisingSpeedMps * (gapSeconds + delay);
            // A fictional bus begins another trip in the same direction after reaching the terminus.
            double tripDistance = distance % routeLength;
            var position = new RoutePositionBus(number, tripDistance < 0 ? tripDistance + routeLength : tripDistance,
                    20, delayed && roadworks.isWaiting(now) ? 0 : cruisingSpeedMps);
            trips.put(DemoVehicleAssignmentId.tripId(configuration.serviceId(), number),
                    new TripPhase(position, (long) Math.floor(distance / routeLength),
                            gapSeconds + (delayed ? roadworks.delaySeconds() : 0)));
        }
        boolean hasGeometry = routeDistance > 0 && stops.stream().allMatch(stop -> stop.latitude() != null && stop.longitude() != null);
        return Optional.of(new Timeline(operator, referenceVersionId, run.startedAt(), now, startingDistance, routeLength, cruisingSpeedMps,
                selectedStop.orElseThrow().distanceM(), hasGeometry ? List.copyOf(stopDistances) : List.of(), Map.copyOf(trips)));
    }

    private record StopAtDistance(String stopCode, int stopSequence, double distanceM) {}

    record TripPhase(RoutePositionBus position, long lap, long gapSeconds) {}

    record ControlForecast(UUID referenceVersionId, long lap, String stopCode, int stopSequence,
                           Instant computedAt, List<Instant> arrivalTimes) {}

    record Timeline(String operator, UUID referenceVersionId, Instant startedAt, Instant computedAt,
                    double startingDistanceM, double routeLengthM, double cruisingSpeedMps, double monitoredDistanceM,
                    List<StopAtDistance> stops, Map<String, TripPhase> trips) {
        Integer nextStopSequence(String tripId) {
            var trip = trips.get(tripId);
            return trip == null ? null : nextStop(trip).map(StopAtDistance::stopSequence).orElse(null);
        }

        Optional<ControlForecast> controlForecast(List<String> tripIds, int staleAfterSeconds) {
            if (tripIds.stream().anyMatch(java.util.Objects::isNull)) return Optional.empty();
            var group = tripIds.stream().map(trips::get).toList();
            if (group.size() < 2 || group.stream().anyMatch(java.util.Objects::isNull)) return Optional.empty();
            var trailing = group.getLast();
            long lap = trailing.lap();
            if (lap < 0 || (lap == 0 && trailing.position().distanceAlongRouteM() <= monitoredDistanceM))
                return Optional.empty();
            // A group split across the terminus cannot safely use the same stop occurrence and arrivals.
            int firstOrder = group.getFirst().position().order();
            for (int i = 0; i < group.size(); i++) {
                if (group.get(i).lap() != lap || group.get(i).position().order() != firstOrder + i)
                    return Optional.empty();
            }
            var next = nextStop(trailing);
            if (next.isEmpty()) return Optional.empty();
            var stop = next.orElseThrow();
            double travel = lap * routeLengthM + stop.distanceM() - startingDistanceM;
            // Anchor arrivals to the route clock so subsequent reads give the same instants for this occurrence.
            var leadArrival = startedAt.plusMillis(Math.round(travel / cruisingSpeedMps * 1_000.0));
            var arrivals = group.stream().map(trip -> leadArrival.plusSeconds(trip.gapSeconds())).toList();
            var lastArrival = arrivals.getLast();
            if (!lastArrival.isAfter(computedAt) || !lastArrival.isBefore(computedAt.plusSeconds(staleAfterSeconds)))
                return Optional.empty();
            return Optional.of(new ControlForecast(referenceVersionId, lap, stop.stopCode(), stop.stopSequence(),
                    computedAt, arrivals));
        }

        private Optional<StopAtDistance> nextStop(TripPhase trip) {
            return stops.stream().filter(stop -> stop.distanceM() >= trip.position().distanceAlongRouteM()).findFirst();
        }
    }

    static double distance(ReferenceTopologyPort.BusRouteStop first, ReferenceTopologyPort.BusRouteStop second) {
        BigDecimal firstLatitude = first.latitude(); BigDecimal firstLongitude = first.longitude();
        BigDecimal secondLatitude = second.latitude(); BigDecimal secondLongitude = second.longitude();
        if (firstLatitude == null || firstLongitude == null || secondLatitude == null || secondLongitude == null) return 1000;
        double latitude = Math.toRadians(secondLatitude.doubleValue() - firstLatitude.doubleValue());
        double longitude = Math.toRadians(secondLongitude.doubleValue() - firstLongitude.doubleValue());
        double a = Math.sin(latitude / 2) * Math.sin(latitude / 2)
                + Math.cos(Math.toRadians(firstLatitude.doubleValue())) * Math.cos(Math.toRadians(secondLatitude.doubleValue()))
                * Math.sin(longitude / 2) * Math.sin(longitude / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
