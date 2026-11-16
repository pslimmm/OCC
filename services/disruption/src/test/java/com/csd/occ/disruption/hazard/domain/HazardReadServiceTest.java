package com.csd.occ.disruption.hazard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.csd.occ.disruption.bunching.domain.ConsumerSelectionService;
import com.csd.occ.disruption.bunching.domain.ConsumerState;
import com.csd.occ.disruption.bunching.domain.Disruption;
import com.csd.occ.disruption.bunching.domain.DisruptionRepository;
import com.csd.occ.disruption.bunching.domain.PlanningEventSubmissionPort;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentEvidencePort;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentEventRepository;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentSnapshot;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentStateRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HazardReadServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-25T04:00:00Z");

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void onlySimulationReferencesUseEmbeddedBp6Geometry(boolean simulation) {
        var hazards = mock(HazardStateRepository.class);
        var selection = mock(ConsumerSelectionService.class);
        var consumerState = mock(ConsumerState.class);
        var transit = mock(HazardTransitPort.class);
        var runId = UUID.randomUUID();
        when(selection.current()).thenReturn(consumerState);
        when(consumerState.demoRunId()).thenReturn(runId);
        when(transit.station("BP6")).thenReturn(Optional.empty());
        var latitude = new BigDecimal("1.3786000");
        var longitude = new BigDecimal("103.7618000");
        var station = new HazardPollPort.Location("STATION", "BP6", "Bukit Panjang", latitude, longitude,
                new BigDecimal("0.5"), "STATION_REFERENCE", "PROVIDER_SUPPLIED");
        var facts = new HazardPollPort.Fact("RAIL_DISRUPTION", "BPL|Both", "OBSERVED", NOW, null, null,
                "Rail alert", "Bus services near the station", null, null, List.of(),
                "BPL", "Both", "2", null, List.of(station));
        var observation = new HazardPollPort.Observation(UUID.randomUUID(), UUID.randomUUID(), facts, NOW);
        var poll = new HazardPollPort.Poll(UUID.randomUUID(), "rail", "network", "SUCCESS", true,
                simulation ? "SIMULATION" : "OBSERVATION_ONLY", simulation ? runId : null, NOW, List.of(observation));
        var state = new HazardState(poll, observation, NOW);
        when(hazards.findByDisruptionId(state.disruptionId())).thenReturn(Optional.of(state));

        var buses = List.of(
                new HazardTransitPort.Bus("demo-190-1", "trip-1", "SYNTHETIC", "190", 1, null, "UNASSIGNED", 10),
                new HazardTransitPort.Bus("demo-190-2", "trip-2", "SYNTHETIC", "190", 1, null, "UNASSIGNED", 11),
                new HazardTransitPort.Bus("demo-190-3", "trip-3", "SYNTHETIC", "190", 1, null, "UNASSIGNED", null));
        when(transit.fleet(runId, 0)).thenReturn(new HazardTransitPort.Fleet(runId, "ACTIVE", buses, 3, 0, 100));
        var stops = List.of(new HazardTransitPort.Stop("44241", "Demo Road", "Passed station stop", latitude, longitude, 9),
                new HazardTransitPort.Stop("44251", "Demo Road", "Upcoming station stop", latitude, longitude, 10),
                new HazardTransitPort.Stop("44261", "Distant Road", "Distant stop", latitude, new BigDecimal("103.77"), 11));
        when(transit.route("190")).thenReturn(new HazardTransitPort.Route("190", List.of(
                new HazardTransitPort.Operator("Operator", List.of(new HazardTransitPort.Direction(1, stops))))));
        when(transit.associationRadiusKm()).thenReturn(0.5);

        var service = new HazardReadService(hazards, selection, transit, Clock.fixed(NOW, ZoneOffset.UTC),
                mock(DisruptionRepository.class), mock(TrafficIncidentStateRepository.class),
                mock(TrafficIncidentEventRepository.class), mock(TrafficIncidentEvidencePort.class),
                mock(PlanningEventSubmissionPort.class), mock(HazardCheckpointRepository.class),
                mock(HazardFeedHealthRepository.class));

        var result = service.find(state.disruptionId());

        if (simulation) {
            assertThat(result.exposures()).singleElement().satisfies(exposure -> {
                assertThat(exposure.busId()).isEqualTo("demo-190-1");
                assertThat(exposure.stopCode()).isEqualTo("44251");
                assertThat(exposure.busProgressSequence()).isEqualTo(10);
                assertThat(exposure.basis()).isEqualTo("STATION_PROXIMITY");
            });
            assertThat(result.limitations()).doesNotContain("Unresolved station: BP6");
            assertThat(result.limitations()).contains("Route position unavailable for demo-190-3");
        } else {
            assertThat(result.exposures()).isEmpty();
            assertThat(result.limitations()).contains("Unresolved station: BP6");
        }
        verify(transit, times(simulation ? 0 : 1)).station("BP6");
    }

    @Test
    void retainedTrafficEpisodeRemainsReadableAfterSourceStateReopens() {
        var hazards = mock(HazardStateRepository.class);
        var selection = mock(ConsumerSelectionService.class);
        var state = mock(ConsumerState.class);
        when(selection.current()).thenReturn(state);
        when(state.demoRunId()).thenReturn(null);
        var disruptions = mock(DisruptionRepository.class);
        var traffic = mock(TrafficIncidentStateRepository.class);
        var events = mock(TrafficIncidentEventRepository.class);
        var evidence = mock(TrafficIncidentEvidencePort.class);
        var checkpoints = mock(HazardCheckpointRepository.class);
        var disruptionId = UUID.randomUUID();
        var observationId = UUID.randomUUID();
        var sourceEvidence = "retained event snapshot";
        var disruption = mock(Disruption.class);
        when(disruption.kind()).thenReturn("TRAFFIC_INCIDENT");
        when(disruption.status()).thenReturn("CLEARED");
        when(disruption.classification()).thenReturn("OBSERVATION_ONLY");
        when(disruption.updatedAt()).thenReturn(NOW);
        when(disruptions.findById(disruptionId)).thenReturn(Optional.of(disruption));
        when(traffic.findByDisruptionId(disruptionId)).thenReturn(Optional.empty());
        when(events.findLatestEvidenceByDisruptionId(disruptionId)).thenReturn(Optional.of(sourceEvidence));
        when(evidence.decode(sourceEvidence)).thenReturn(new TrafficIncidentSnapshot(observationId, 2,
                UUID.randomUUID(), "a".repeat(64), "Lane obstruction", "Blocked lane", new BigDecimal("1.38"),
                new BigDecimal("103.75"), NOW.minusSeconds(60), NOW.minusSeconds(30)));
        var service = new HazardReadService(hazards, selection, mock(HazardTransitPort.class),
                Clock.fixed(NOW, ZoneOffset.UTC), disruptions, traffic, events, evidence,
                mock(PlanningEventSubmissionPort.class), checkpoints, mock(HazardFeedHealthRepository.class));

        var result = service.find(disruptionId);

        assertThat(result.disruptionId()).isEqualTo(disruptionId);
        assertThat(result.status()).isEqualTo("CLEARED");
        assertThat(result.inactiveReason()).isEqualTo("NO_LONGER_REPORTED");
        assertThat(result.sourceObservationId()).isEqualTo(observationId);
        assertThat(result.sourceRevision()).isEqualTo(2L);
        assertThat(result.facts().description()).isEqualTo("Blocked lane");
    }

    @Test
    void retainedHazardEventsKeepClearedSourceReadableAfterOperationalStatePurges() {
        var hazards = mock(HazardStateRepository.class);
        var selection = mock(ConsumerSelectionService.class);
        var consumerState = mock(ConsumerState.class);
        when(selection.current()).thenReturn(consumerState);
        when(consumerState.demoRunId()).thenReturn(null);
        var disruptions = mock(DisruptionRepository.class);
        var traffic = mock(TrafficIncidentStateRepository.class);
        var trafficEvents = mock(TrafficIncidentEventRepository.class);
        var trafficEvidence = mock(TrafficIncidentEvidencePort.class);
        var eventCodec = mock(PlanningEventSubmissionPort.class);
        var checkpoints = mock(HazardCheckpointRepository.class);
        var disruptionId = UUID.randomUUID();
        var sourceObservationId = UUID.randomUUID();
        var disruption = mock(Disruption.class);
        when(disruption.kind()).thenReturn("FLOOD");
        when(disruption.status()).thenReturn("CLEARED");
        when(disruption.classification()).thenReturn("OBSERVATION_ONLY");
        when(disruption.updatedAt()).thenReturn(NOW);
        when(disruptions.findById(disruptionId)).thenReturn(Optional.of(disruption));
        when(traffic.findByDisruptionId(disruptionId)).thenReturn(Optional.empty());
        when(hazards.findLatestHazardEventEvidence(disruptionId)).thenReturn(Optional.of("event snapshot"));
        var facts = new HazardPollPort.Fact("FLOOD", "flood-1", "OBSERVED", NOW, null, null, "Flood", "Road flooded",
                null, null, List.of(), null, null, null, null, List.of());
        when(eventCodec.decodeHazard("event snapshot")).thenReturn(new HazardEventSnapshot(UUID.randomUUID(), disruptionId,
                "CLEARED", "SOURCE_CANCELLED", "OBSERVATION_ONLY", null, sourceObservationId, null,
                UUID.randomUUID(), null, NOW, facts, null));
        var service = new HazardReadService(hazards, selection, mock(HazardTransitPort.class),
                Clock.fixed(NOW, ZoneOffset.UTC), disruptions, traffic, trafficEvents, trafficEvidence, eventCodec, checkpoints,
                mock(HazardFeedHealthRepository.class));

        var result = service.find(disruptionId);

        assertThat(result.kind()).isEqualTo("FLOOD");
        assertThat(result.status()).isEqualTo("CLEARED");
        assertThat(result.inactiveReason()).isEqualTo("SOURCE_CANCELLED");
        assertThat(result.sourceObservationId()).isEqualTo(sourceObservationId);
        assertThat(result.facts().description()).isEqualTo("Road flooded");
    }
}
