package com.csd.occ.ingestion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Deterministic hazards for the simulation replay. The roadworks scenario places its temporary restriction at
 * Phoenix Stn, followed by the BP6 station disruption and flooding near Opp Lompang Rd. Every replay step reports
 * the hazards as active; flood and roadworks expire one hour after the run starts.
 */
public final class DemoHazardScenario {
    static final String ROADWORKS_STOP_CODE = "44141";
    static final String ROADWORKS_SOURCE_KEY = "demo-roadworks";

    private DemoHazardScenario() {}

    public static List<HazardFeedPort.Fact> observations(int step, Instant startedAt, Instant now) {
        return observations(DemoScenario.BUNCHING, step, startedAt, now);
    }

    static List<HazardFeedPort.Fact> observations(DemoScenario scenario, int step, Instant startedAt, Instant now) {
        if (step < 0 || step >= DemoReplayScenario.STEP_COUNT) throw new IllegalArgumentException("Invalid scenario step");
        boolean localWorksite = scenario == DemoScenario.ROADWORKS_BUNCHING;
        var road = localWorksite
                ? new HazardLocation("ROAD", ROADWORKS_STOP_CODE, "Choa Chu Kang Rd near Phoenix Stn",
                        new BigDecimal("1.3783560"), new BigDecimal("103.7577368"), new BigDecimal("0.08"),
                        "WORKSITE", "PROVIDER_SUPPLIED")
                : new HazardLocation("ROAD", "Bt Panjang Rd", "Bt Panjang Rd", new BigDecimal("1.3803000"),
                        new BigDecimal("103.7623000"), new BigDecimal("0.5"), "ROAD_NAME", "PROVIDER_SUPPLIED");
        var coverage = localWorksite
                ? new HazardLocation("AREA", "demo-flood", "Opp Lompang Rd bus stop", new BigDecimal("1.3811744"),
                        new BigDecimal("103.7658610"), new BigDecimal("0.12"), "BROADCAST_COVERAGE", "PROVIDER_SUPPLIED")
                : new HazardLocation("AREA", "demo-flood", "Bt Panjang / Teck Whye", new BigDecimal("1.3802550"),
                        new BigDecimal("103.7550750"), new BigDecimal("1.0"), "BROADCAST_COVERAGE", "PROVIDER_SUPPLIED");
        var station = new HazardLocation("STATION", "BP6", "Bukit Panjang", new BigDecimal("1.3786000"),
                new BigDecimal("103.7618000"), new BigDecimal(localWorksite ? "0.25" : "0.5"), "STATION_REFERENCE", "PROVIDER_SUPPLIED");
        var expiresAt = startedAt.plusSeconds(3600);
        return List.of(
                new HazardFeedPort.Fact(HazardKind.FLOOD, "demo-flood", "OBSERVED", now, null, expiresAt,
                        "DEMO: Flood alert",
                        step == 0 || localWorksite ? "Synthetic flooding near " + coverage.description() : "Synthetic flood update",
                        "Severe", "Avoid the affected area", List.of(), null, null, "Actual", null, List.of(coverage)),
                new HazardFeedPort.Fact(HazardKind.RAIL_DISRUPTION, "BPL|Both", "OBSERVED",
                        now, null, null, "DEMO: BPL disruption", "Synthetic rail disruption near connecting bus services",
                        null, null, List.of(), "BPL", "Both", "2", null, List.of(station)),
                new HazardFeedPort.Fact(HazardKind.ROADWORKS, ROADWORKS_SOURCE_KEY, "OBSERVED", null,
                        startedAt, expiresAt, "DEMO: Approved works",
                        localWorksite ? "Synthetic temporary traffic restriction near Phoenix Stn"
                                : "Synthetic approved roadworks; road closure not established", null, null, List.of(),
                        null, null, null, "DEMO department", List.of(road)));
    }
}
