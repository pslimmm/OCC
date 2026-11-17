package com.csd.occ.ingestion.domain;

import java.time.Duration;
import java.time.Instant;

/** Stable route-190 demo timing pattern; headways scale with the detector threshold. */
public final class DemoReplayScenario {
    public static final int STEP_COUNT = 3;
    private static final int LEGACY_SPEED_KMH = 10;
    private static final int ROADWORKS_SPEED_KMH = 5;
    private static final double ROADWORKS_SPEED_MPS = ROADWORKS_SPEED_KMH / 3.6;
    static final int ROADWORKS_HEADWAY_SECONDS = 15;
    static final double ROADWORKS_APPROACH_METRES = 60;
    static final double WORKSITE_TO_STOP_METRES = 30;

    private DemoReplayScenario() {}

    static int cruisingSpeedKmh(DemoScenario scenario) {
        return scenario == DemoScenario.ROADWORKS_BUNCHING ? ROADWORKS_SPEED_KMH : LEGACY_SPEED_KMH;
    }

    /** Simulated boarding starts at arrival and lasts one minute, bounded by source freshness. */
    public static Instant boardingUntil(Instant arrivalAt, Instant freshUntil) {
        Instant end = arrivalAt.plusSeconds(60);
        return end.isBefore(freshUntil) ? end : freshUntil;
    }

    public static Duration headway(int step, Duration threshold) {
        return headway(step, threshold.multipliedBy(10).dividedBy(3), threshold, DemoScenario.BUNCHING);
    }

    public static Duration headway(int step, Duration dispatchGap, Duration threshold, DemoScenario scenario) {
        if (step < 0 || step >= STEP_COUNT) throw new IllegalArgumentException("demo step is out of range");
        if (threshold == null || threshold.compareTo(Duration.ofNanos(2)) < 0)
            throw new IllegalArgumentException("bunching threshold must be at least 2ns");
        if (scenario != DemoScenario.BUNCHING) return dispatchGap;
        return step == 0 ? dispatchGap : threshold.dividedBy(2);
    }

    static Roadworks roadworks(DemoRun run) {
        var configuration = run.configuration();
        return configuration.scenario() == DemoScenario.ROADWORKS_BUNCHING
                ? new Roadworks(run.startedAt(), configuration.busGaps().getFirst().toSeconds() - ROADWORKS_HEADWAY_SECONDS)
                : null;
    }

    /** The first bus waits once in the work zone. Its departure delay then carries forward on the route clock. */
    record Roadworks(Instant startedAt, long delaySeconds) {
        Instant reachesWorksiteAt() {
            return startedAt.plusMillis(Math.round(ROADWORKS_APPROACH_METRES / ROADWORKS_SPEED_MPS * 1_000));
        }

        Instant baselineLeadArrival() {
            return startedAt.plusMillis(Math.round((ROADWORKS_APPROACH_METRES + WORKSITE_TO_STOP_METRES)
                    / ROADWORKS_SPEED_MPS * 1_000));
        }

        double elapsedWaitSeconds(Instant now) {
            double secondsSinceReachingWorksite = Duration.between(reachesWorksiteAt(), now).toMillis() / 1_000.0;
            return Math.clamp(secondsSinceReachingWorksite, 0, delaySeconds);
        }

        boolean isWaiting(Instant now) {
            return !now.isBefore(reachesWorksiteAt()) && now.isBefore(reachesWorksiteAt().plusSeconds(delaySeconds));
        }
    }
}
