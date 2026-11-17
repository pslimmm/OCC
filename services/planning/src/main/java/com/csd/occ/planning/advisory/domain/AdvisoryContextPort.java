package com.csd.occ.planning.advisory.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public interface AdvisoryContextPort {
    Hazard hazard(UUID disruptionId);
    Fleet fleet(UUID runId);
    Route route(String serviceId);
    Limits limits();
    BunchingGroup bunchingGroup(UUID demoRunId, UUID bunchingEventId);
    CurrentBunchingGroup currentBunchingGroup(UUID demoRunId, UUID bunchingEventId);
    /** Empty while new simulation predictions are awaiting detector evaluation. */
    Optional<CurrentBunchingGroup> refreshBunchingGroup(UUID demoRunId, UUID bunchingEventId);
    record BunchingGroup(UUID demoRunId, UUID bunchingEventId, String serviceId, int directionId, String stopCode,
                         List<String> affectedBusReferences, String followingBusReference) {}
    record CurrentBunchingGroup(UUID demoRunId, UUID bunchingEventId, String serviceId, String stopCode, int directionId,
                                UUID rawObservationId, Instant fetchedAt, Instant validUntil, List<String> affectedBusReferences,
                                List<Long> adjacentGapSeconds, List<Boolean> consecutivePairs, ControlPrediction controlPrediction) {
        public boolean hasReliableGap(int index) {
            var gaps = controlGapSeconds();
            return gaps != null && consecutivePairs != null
                    && gaps.size() == affectedBusReferences.size() - 1
                    && consecutivePairs.size() == gaps.size()
                    && Boolean.TRUE.equals(consecutivePairs.get(index))
                    && gaps.get(index) != null && gaps.get(index) >= 0;
        }

        public boolean isFreshAt(Instant now) {
            return rawObservationId != null && fetchedAt != null && validUntil != null
                    && !fetchedAt.isAfter(now) && now.isBefore(validUntil)
                    && (controlPrediction == null || controlPrediction.isFreshFor(affectedBusReferences, now));
        }

        public String controlStopCode() {
            return controlPrediction == null ? stopCode : controlPrediction.stopCode();
        }

        public List<Long> controlGapSeconds() {
            if (controlPrediction == null) return adjacentGapSeconds;
            var arrivals = controlPrediction.arrivals();
            return java.util.stream.IntStream.range(1, arrivals.size()).mapToLong(index ->
                    Duration.between(arrivals.get(index - 1).arrivalAt(), arrivals.get(index).arrivalAt()).getSeconds())
                    .boxed().toList();
        }

        public Instant controlValidUntil() {
            if (controlPrediction == null) return validUntil;
            var lastArrival = controlPrediction.arrivals().getLast().arrivalAt();
            return lastArrival.isBefore(validUntil) ? lastArrival : validUntil;
        }

        public CurrentBunchingGroup(UUID demoRunId, UUID bunchingEventId, String serviceId, String stopCode, int directionId,
                UUID rawObservationId, Instant fetchedAt, Instant validUntil, List<String> affectedBusReferences,
                List<Long> adjacentGapSeconds, List<Boolean> consecutivePairs) {
            this(demoRunId, bunchingEventId, serviceId, stopCode, directionId, rawObservationId, fetchedAt, validUntil,
                    affectedBusReferences, adjacentGapSeconds, consecutivePairs, null);
        }

        public CurrentBunchingGroup(UUID demoRunId, UUID bunchingEventId, String serviceId, String stopCode, int directionId,
                UUID rawObservationId, Instant fetchedAt, Instant validUntil, List<String> affectedBusReferences) {
            this(demoRunId, bunchingEventId, serviceId, stopCode, directionId, rawObservationId, fetchedAt, validUntil,
                    affectedBusReferences, List.of(), List.of());
        }
    }
    record ControlPrediction(UUID snapshotId, String stopCode, int stopSequence, Instant computedAt,
                             List<ControlArrival> arrivals) {
        boolean isFreshFor(List<String> busReferences, Instant now) {
            if (snapshotId == null || stopCode == null || stopCode.isBlank() || stopSequence < 1 || computedAt == null
                    || computedAt.isAfter(now) || arrivals == null || busReferences == null
                    || arrivals.size() < 2 || arrivals.size() != busReferences.size()) return false;
            Instant precedingArrival = null;
            for (int index = 0; index < arrivals.size(); index++) {
                var arrival = arrivals.get(index);
                if (arrival == null || arrival.arrivalAt() == null || arrival.busReference() == null
                        || !arrival.busReference().equals(busReferences.get(index))
                        || precedingArrival != null && !arrival.arrivalAt().isAfter(precedingArrival)) return false;
                precedingArrival = arrival.arrivalAt();
            }
            return now.isBefore(precedingArrival);
        }

        public boolean isApproaching(Bus bus, Instant now) {
            return java.util.Objects.equals(bus.nextStopSequence(), stopSequence) && arrivals.stream()
                    .anyMatch(arrival -> arrival.busReference().equals(bus.busId()) && now.isBefore(arrival.arrivalAt()));
        }
    }
    record ControlArrival(String busReference, Instant arrivalAt) {}
    record Limits(int maxSpeedReductionKmh, int maxDurationSeconds, int maxSubsequentStops,
                  Duration acknowledgementWindow, Duration retention) {}
    record Hazard(UUID disruptionId, String kind, String status, String inactiveReason, String classification,
                  UUID demoRunId, UUID sourceObservationId, Long sourceRevision, Instant updatedAt,
                  HazardEventFacts.Fact facts, List<Exposure> exposures, List<String> limitations) {
        public Hazard(UUID disruptionId, String kind, String status, String inactiveReason, String classification,
                UUID demoRunId, UUID sourceObservationId, Instant updatedAt, HazardEventFacts.Fact facts,
                List<Exposure> exposures, List<String> limitations) {
            this(disruptionId, kind, status, inactiveReason, classification, demoRunId, sourceObservationId, null,
                    updatedAt, facts, exposures, limitations);
        }

        public boolean hasSameEvidenceAs(Hazard latest) {
            return sourceRevision != null
                    ? sourceRevision.equals(latest.sourceRevision)
                    : java.util.Objects.equals(sourceObservationId, latest.sourceObservationId);
        }

        public Stream<Exposure> downstreamExposures(Bus bus) {
            if (bus.progress() == null) return Stream.empty();
            return exposures.stream().filter(exposure -> exposure.busId().equals(bus.busId())
                    && exposure.serviceId().equals(bus.serviceId()) && exposure.directionId() == bus.directionId()
                    && exposure.stopSequence() >= (bus.nextStopSequence() == null
                            ? exposure.busProgressSequence() : bus.nextStopSequence()));
        }
    }
    record Exposure(String busId, String serviceId, int directionId, String stopCode, String stopName,
                    int busProgressSequence, int stopSequence, String basis, String limitation) {
        public Exposure(String busId, String serviceId, int directionId, String stopCode, String stopName,
                int stopSequence, String basis, String limitation) {
            this(busId, serviceId, directionId, stopCode, stopName, stopSequence, stopSequence, basis, limitation);
        }
    }
    record Fleet(UUID demoRunId, String status, List<Bus> items, long totalElements) {}
    record Bus(String busId, String tripId, String registrationNumber, String serviceId, int directionId,
               UUID captainUserId, String assignmentStatus, Progress progress, long assignmentRevision,
               Integer nextStopSequence) {
        public Bus(String busId, String tripId, String registrationNumber, String serviceId, int directionId,
                UUID captainUserId, String assignmentStatus, Progress progress, long assignmentRevision) {
            this(busId, tripId, registrationNumber, serviceId, directionId, captainUserId, assignmentStatus, progress,
                    assignmentRevision, null);
        }

        public Bus(String busId, String tripId, String registrationNumber, String serviceId, int directionId,
                UUID captainUserId, String assignmentStatus, Progress progress) {
            this(busId, tripId, registrationNumber, serviceId, directionId, captainUserId, assignmentStatus, progress, 0);
        }
    }
    record Progress(String stopCode, int stopSequence, Instant observedAt, int assumedSpeedKmh,
                    List<Integer> allowedSpeedReductionsKmh, List<Integer> allowedDurationsSeconds,
                    Instant estimatedArrivalAt, Instant freshUntil, Instant boardingUntil) {
        public Progress(String stopCode, int stopSequence, Instant observedAt, int assumedSpeedKmh,
                List<Integer> allowedSpeedReductionsKmh, List<Integer> allowedDurationsSeconds,
                Instant estimatedArrivalAt, Instant freshUntil) {
            this(stopCode, stopSequence, observedAt, assumedSpeedKmh, allowedSpeedReductionsKmh,
                    allowedDurationsSeconds, estimatedArrivalAt, freshUntil, null);
        }

        public Progress(String stopCode, int stopSequence, Instant observedAt, int assumedSpeedKmh,
                List<Integer> allowedSpeedReductionsKmh, List<Integer> allowedDurationsSeconds) {
            this(stopCode, stopSequence, observedAt, assumedSpeedKmh, allowedSpeedReductionsKmh,
                    allowedDurationsSeconds, null, null);
        }

        public boolean isApproachingAt(Instant now) {
            return isFreshAt(now) && estimatedArrivalAt != null && now.isBefore(estimatedArrivalAt);
        }

        public boolean isBoardingAt(Stop stop, CurrentBunchingGroup group, Instant now) {
            return stop.stopCode().equals(stopCode) && stop.stopSequence() == stopSequence
                    && group.fetchedAt().equals(observedAt) && group.isFreshAt(now) && isFreshAt(now)
                    && estimatedArrivalAt != null && !now.isBefore(estimatedArrivalAt)
                    && boardingUntil != null && now.isBefore(boardingUntil);
        }

        private boolean isFreshAt(Instant now) {
            return observedAt != null && !observedAt.isAfter(now) && freshUntil != null && now.isBefore(freshUntil);
        }
    }
    record Route(String serviceId, List<Operator> operators) {
        public Optional<Stop> upcomingStop(int directionId, String stopCode, int progressSequence) {
            return unique(matchingStops(directionId, stopCode).filter(stop -> stop.stopSequence() >= progressSequence));
        }

        public Optional<Stop> nextPredictedStop(Bus bus, String stopCode) {
            var progress = bus.progress();
            if (progress == null || !stopCode.equals(progress.stopCode())) return Optional.empty();
            return nextStop(bus, stopCode, progress.stopSequence());
        }

        public Optional<Stop> nextStop(Bus bus, String stopCode, int stopSequence) {
            if (!serviceId.equals(bus.serviceId()) || !java.util.Objects.equals(bus.nextStopSequence(), stopSequence))
                return Optional.empty();
            return unique(matchingStops(bus.directionId(), stopCode).filter(stop -> stop.stopSequence() == stopSequence));
        }

        private Stream<Stop> matchingStops(int directionId, String stopCode) {
            return operators.stream().flatMap(operator -> operator.directions().stream())
                    .filter(direction -> direction.directionId() == directionId).flatMap(direction -> direction.stops().stream())
                    .filter(stop -> stop.stopCode().equals(stopCode));
        }

        private Optional<Stop> unique(Stream<Stop> matches) {
            var stops = matches.limit(2).toList();
            return stops.size() == 1 ? Optional.of(stops.getFirst()) : Optional.empty();
        }
    }
    record Operator(String operator, String category, List<Direction> directions) {}
    record Direction(int directionId, List<Stop> stops) {}
    record Stop(String stopCode, String description, int stopSequence) {}
}
