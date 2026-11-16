package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DemoHazardScenarioTest {
    @Test
    void everyStepKeepsTheSameHazardsActiveUntilTheirOneHourExpiry() {
        var startedAt = Instant.EPOCH;
        var steps = IntStream.range(0, DemoReplayScenario.STEP_COUNT)
                .mapToObj(step -> DemoHazardScenario.observations(step, startedAt, startedAt.plusSeconds(5L * step)))
                .toList();
        var first = steps.getFirst();
        assertThat(first).extracting(HazardFeedPort.Fact::kind)
                .containsExactly(HazardKind.FLOOD, HazardKind.RAIL_DISRUPTION, HazardKind.ROADWORKS);
        var expiresAt = startedAt.plusSeconds(3600);
        for (var facts : steps) {
            assertThat(facts).extracting(HazardFeedPort.Fact::sourceEventKey)
                    .containsExactlyElementsOf(first.stream().map(HazardFeedPort.Fact::sourceEventKey).toList());
            assertThat(facts).extracting(HazardFeedPort.Fact::sourceAction).containsOnly("OBSERVED");
            assertThat(facts).allSatisfy(fact -> assertThat(fact.references()).isEmpty());
            assertThat(facts.getFirst().effectiveUntil()).isEqualTo(expiresAt);
            assertThat(facts.getLast().effectiveUntil()).isEqualTo(expiresAt);
        }
    }

    @Test
    void roadworksScenarioPlacesHazardsAtThreeSeparatePartsOfTheRoute() {
        var facts = DemoHazardScenario.observations(DemoScenario.ROADWORKS_BUNCHING, 0, Instant.EPOCH, Instant.EPOCH);
        var flood = facts.getFirst().locations().getFirst();
        var station = facts.get(1).locations().getFirst();
        var works = facts.getLast().locations().getFirst();

        assertThat(works.sourceReference()).isEqualTo("44141");
        assertThat(works.geometryRole()).isEqualTo("WORKSITE");
        assertThat(works.radiusKm()).isEqualByComparingTo("0.08");
        assertThat(station.sourceReference()).isEqualTo("BP6");
        assertThat(station.radiusKm()).isEqualByComparingTo("0.25");
        assertThat(flood.description()).contains("Opp Lompang Rd");
        assertThat(flood.radiusKm()).isEqualByComparingTo("0.12");
        // On this route the worksite, station, and flood are ordered west to east.
        assertThat(works.longitude()).isLessThan(station.longitude());
        assertThat(station.longitude()).isLessThan(flood.longitude());
    }
}
