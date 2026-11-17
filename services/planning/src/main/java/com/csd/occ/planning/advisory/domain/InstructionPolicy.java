package com.csd.occ.planning.advisory.domain;

import java.util.Locale;

public final class InstructionPolicy {
    public String message(AdvisoryCandidate candidate, AdvisoryContextPort.Bus bus,
            AdvisoryContextPort.Hazard hazard, AdvisoryContextPort.Limits limits) {
        if (candidate == null || candidate.type() == null || candidate.parameters() == null
                || candidate.rationale() == null || candidate.rationale().isBlank())
            throw new IllegalArgumentException("Instruction type, parameters and rationale required");
        var p = candidate.parameters();
        if (bus.progress() == null) throw new IllegalArgumentException("Synthetic progress unavailable");
        var exposures = hazard.downstreamExposures(bus).toList();
        if (exposures.isEmpty())
            throw new IllegalArgumentException("Bus has no current downstream exposure");
        if (candidate.type() != InstructionType.REDUCE_SPEED) {
            if (p.stopCode() == null || p.stopName() == null || p.stopSequence() == null
                    || exposures.stream().noneMatch(e -> e.stopCode().equals(p.stopCode())
                    && e.stopSequence() == p.stopSequence() && e.stopName().equals(p.stopName())))
                throw new IllegalArgumentException("Target stop occurrence is not in current downstream exposure");
        }
        switch (candidate.type()) {
            case BE_CAUTIOUS -> { }
            case REDUCE_SPEED -> {
                if (p.speedReductionKmh() == null || p.speedReductionKmh() < 1 || p.speedReductionKmh() > limits.maxSpeedReductionKmh()
                        || p.durationSeconds() == null || p.durationSeconds() < 60 || p.durationSeconds() % 60 != 0
                        || p.durationSeconds() > limits.maxDurationSeconds()
                        || !bus.progress().allowedSpeedReductionsKmh().contains(p.speedReductionKmh())
                        || !bus.progress().allowedDurationsSeconds().contains(p.durationSeconds()))
                    throw new IllegalArgumentException("Speed advice must use permitted synthetic values and limits");
            }
            case SLOW_DOWN_NEAR_STOPS -> {
                if (p.subsequentStopCount() == null || p.subsequentStopCount() < 0 || p.subsequentStopCount() > limits.maxSubsequentStops())
                    throw new IllegalArgumentException("Invalid subsequent stop count");
                for (int offset = 0; offset <= p.subsequentStopCount(); offset++) {
                    int sequence = p.stopSequence() + offset;
                    if (exposures.stream().noneMatch(e -> e.stopSequence() == sequence))
                        throw new IllegalArgumentException("Instruction range exceeds current downstream affected stops");
                }
            }
            case HOLD, DWELL_ADJUSTMENT, SKIP_STOP -> throw new IllegalArgumentException("Use the validated bunching recommendation for movement instructions");
        }
        var sourceInstruction = hazard.facts().instruction();
        if (candidate.type() != InstructionType.BE_CAUTIOUS && sourceInstruction != null && !sourceInstruction.isBlank())
            throw new IllegalArgumentException("Source restrictions require a caution instruction");
        return wording(candidate, hazard);
    }

    // A pending proposal already carries its validated candidate and captured hazard.
    static String wording(AdvisoryCandidate candidate, AdvisoryContextPort.Hazard hazard) {
        var p = candidate.parameters();
        var sourceInstruction = hazard.facts().instruction();
        String report = switch (hazard.kind()) {
            case "FLOOD" -> "Flood alert";
            case "RAIL_DISRUPTION" -> "Rail disruption reported";
            case "ROADWORKS" -> "Roadworks reported";
            case "TRAFFIC_INCIDENT" -> trafficReport(hazard.facts().title());
            case null, default -> "Disruption reported";
        };
        return switch (candidate.type()) {
            case BE_CAUTIOUS -> report + (SimulationCautionPolicy.appliesTo(hazard.classification(), hazard.kind())
                    ? " along your route through " : " near ") + p.stopName() + ". "
                    + (sourceInstruction == null || sourceInstruction.isBlank() ? "Be cautious." : sourceInstruction.strip());
            case REDUCE_SPEED -> report + " along your route. Reduce speed by " + p.speedReductionKmh()
                    + " km/h for " + p.durationSeconds() / 60 + " min.";
            case SLOW_DOWN_NEAR_STOPS -> report + " near " + p.stopName() + ". Slow down as you approach"
                    + (p.subsequentStopCount() == 0 ? "." : " and for the next " + p.subsequentStopCount() + " stops.");
            case HOLD, DWELL_ADJUSTMENT, SKIP_STOP -> throw new IllegalArgumentException("Unsupported hazard instruction");
        };
    }

    private static String trafficReport(String incidentType) {
        return switch (incidentType == null ? "" : incidentType.strip().toLowerCase(Locale.ROOT)) {
            case "accident" -> "Accident reported";
            case "vehicle breakdown" -> "Vehicle breakdown reported";
            case "heavy traffic" -> "Heavy traffic reported";
            case "road closure" -> "Road closure reported";
            case "roadwork", "road work", "roadworks", "road works" -> "Roadworks reported";
            case "obstacle", "obstacle on the road" -> "Road obstruction reported";
            default -> "Traffic incident reported";
        };
    }
}
