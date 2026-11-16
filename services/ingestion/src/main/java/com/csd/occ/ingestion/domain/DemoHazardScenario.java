package com.csd.occ.ingestion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Deterministic hazards for the simulation replay. Regular runs place roadworks at the monitored stop and
 * report separate hazards at Bangkit LRT and Blk 270. Flood and roadworks expire one hour after the run starts.
 */
public final class DemoHazardScenario {
    static final String ROADWORKS_STOP_CODE = "44141";
    static final String ROADWORKS_SOURCE_KEY = "demo-roadworks";

    private DemoHazardScenario() {}

    public static List<HazardFeedPort.Fact> observations(int step, Instant startedAt, Instant now) {
        return observations(DemoScenario.BUNCHING, step, startedAt, now, DemoRunConfiguration.defaults().monitoredStopCode(), null);
    }

    static List<HazardFeedPort.Fact> observations(DemoScenario scenario, int step, Instant startedAt, Instant now) {
        return observations(scenario, step, startedAt, now, DemoRunConfiguration.defaults().monitoredStopCode(), null);
    }

    static List<HazardFeedPort.Fact> observations(DemoScenario scenario, int step, Instant startedAt, Instant now,
            String monitoredStopCode, ReferenceTopologyPort.BusStop monitoredStop) {
        if (step < 0 || step >= DemoReplayScenario.STEP_COUNT) throw new IllegalArgumentException("Invalid scenario step");
        boolean historicalRoadworksPreset = scenario == DemoScenario.ROADWORKS_BUNCHING;
        var road = historicalRoadworksPreset
                ? new HazardLocation("ROAD", ROADWORKS_STOP_CODE, "Choa Chu Kang Rd near Phoenix Stn",
                        new BigDecimal("1.3783560"), new BigDecimal("103.7577368"), new BigDecimal("0.08"),
                        "WORKSITE", "PROVIDER_SUPPLIED")
                : new HazardLocation("ROAD", monitoredStopCode,
                        monitoredStop == null ? "Bus stop " + monitoredStopCode : monitoredStop.roadName(),
                        monitoredStop == null ? null : monitoredStop.latitude(), monitoredStop == null ? null : monitoredStop.longitude(),
                        new BigDecimal("0.08"), "WORKSITE", "PROVIDER_SUPPLIED");
        var coverage = historicalRoadworksPreset
                ? new HazardLocation("AREA", "demo-flood", "Opp Lompang Rd bus stop", new BigDecimal("1.3811744"),
                        new BigDecimal("103.7658610"), new BigDecimal("0.12"), "BROADCAST_COVERAGE", "PROVIDER_SUPPLIED")
                : new HazardLocation("AREA", "demo-flood", "Blk 270 bus stop", new BigDecimal("1.3760822"),
                        new BigDecimal("103.7742778"), new BigDecimal("0.05"), "BROADCAST_COVERAGE", "PROVIDER_SUPPLIED");
        var station = historicalRoadworksPreset
                ? new HazardLocation("STATION", "BP6", "Bukit Panjang", new BigDecimal("1.3786000"),
                        new BigDecimal("103.7618000"), new BigDecimal("0.25"), "STATION_REFERENCE", "PROVIDER_SUPPLIED")
                : new HazardLocation("STATION", "BP9", "Bangkit", new BigDecimal("1.3798533"),
                        new BigDecimal("103.7725906"), new BigDecimal("0.40"), "STATION_REFERENCE", "PROVIDER_SUPPLIED");
        var expiresAt = startedAt.plusSeconds(3600);
        return List.of(
                new HazardFeedPort.Fact(HazardKind.FLOOD, "demo-flood", "OBSERVED", now, null, expiresAt,
                        "DEMO: Flood alert",
                        "Synthetic flooding near " + coverage.description(),
                        "Severe", "Avoid the affected area", List.of(), null, null, "Actual", null, List.of(coverage)),
                new HazardFeedPort.Fact(HazardKind.RAIL_DISRUPTION, "BPL|Both", "OBSERVED",
                        now, null, null, "DEMO: BPL disruption", "Synthetic rail disruption near connecting bus services",
                        null, null, List.of(), "BPL", "Both", "2", null, List.of(station)),
                new HazardFeedPort.Fact(HazardKind.ROADWORKS, ROADWORKS_SOURCE_KEY, "OBSERVED", null,
                        startedAt, expiresAt, "DEMO: Approved works",
                        historicalRoadworksPreset ? "Synthetic temporary traffic restriction near Phoenix Stn"
                                : "Synthetic approved roadworks; road closure not established", null, null, List.of(),
                        null, null, null, "DEMO department", List.of(road)));
    }
}
