package com.csd.occ.disruption.hazard.domain;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HazardExposurePolicyTest {
    private final HazardExposurePolicy policy = new HazardExposurePolicy();
    private final HazardTransitPort.Route route = new HazardTransitPort.Route("190", List.of(
            new HazardTransitPort.Operator("SBST", List.of(new HazardTransitPort.Direction(1, List.of(
                    stop("44141", "1.3783560", "103.7577368", 7),
                    stop("44251", "1.3803008", "103.7623167", 8),
                    stop("44241", "1.3811744", "103.7658610", 9),
                    stop("44231", "1.3791669", "103.7693349", 10)))))));

    @Test
    void worksiteExposureEndsWhenTheBusPassesTheReferencedStop() {
        var worksite = location("STOP", "44141", "1.3783560", "103.7577368", "0.08", "WORKSITE");
        assertThat(policy.assess(bus(7), route, List.of(worksite), 0.5)).extracting(HazardExposurePolicy.Exposure::stopCode)
                .containsExactly("44141");
        assertThat(policy.assess(bus(8), route, List.of(worksite), 0.5)).isEmpty();
    }

    @Test
    void separatedStationAndFloodApplyOnlyUntilTheirOwnStopsArePassed() {
        var station = location("STATION", "BP6", "1.3786000", "103.7618000", "0.25", "STATION_PROXIMITY");
        var flood = location("AREA", "demo-flood", "1.3811744", "103.7658610", "0.12", "BROADCAST_COVERAGE");
        assertThat(policy.assess(bus(7), route, List.of(station, flood), 0.5)).extracting(HazardExposurePolicy.Exposure::stopCode)
                .containsExactly("44251", "44241");
        assertThat(policy.assess(bus(9), route, List.of(station, flood), 0.5)).extracting(HazardExposurePolicy.Exposure::stopCode)
                .containsExactly("44241");
        assertThat(policy.assess(bus(10), route, List.of(station, flood), 0.5)).isEmpty();
    }

    private HazardTransitPort.Bus bus(int sequence) {
        return new HazardTransitPort.Bus("demo-190-1", "trip-1", "demo-190-01", "190", 1, null, "ASSIGNED", sequence);
    }

    private HazardTransitPort.Stop stop(String code, String latitude, String longitude, int sequence) {
        return new HazardTransitPort.Stop(code, "Test Road", code, new BigDecimal(latitude), new BigDecimal(longitude), sequence);
    }

    private HazardPollPort.Location location(String type, String reference, String latitude, String longitude, String radius, String role) {
        return new HazardPollPort.Location(type, reference, "Test hazard", new BigDecimal(latitude), new BigDecimal(longitude),
                new BigDecimal(radius), role, "PROVIDER_SUPPLIED");
    }
}
