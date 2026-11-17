package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RootCauseContextServiceTest {
    private final Instant now = Instant.parse("2026-10-24T00:00:00Z");
    private final DemoRunRepository runs = mock(DemoRunRepository.class);
    private final BusArrivalObservationRepository arrivals = mock(BusArrivalObservationRepository.class);
    private final TrafficIncidentObservationRepository incidents = mock(TrafficIncidentObservationRepository.class);
    private final HazardObservationRepository hazards = mock(HazardObservationRepository.class);
    private final ReferenceTopologyPort topology = mock(ReferenceTopologyPort.class);
    private final PollingConfigurationPort settings = mock(PollingConfigurationPort.class);
    private final RootCauseContextService service = new RootCauseContextService(runs,
            arrivals, incidents, hazards, topology, settings, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void liveContextRetainsExactObservationIdsAndDoesNotReadDemoIncidents() {
        when(settings.retention()).thenReturn(Duration.ofHours(12));
        var first = arrival("OBSERVATION_ONLY", null);
        var second = arrival("OBSERVATION_ONLY", null);
        var ids = List.of(first.getId(), second.getId());
        when(arrivals.findAllById(ids)).thenReturn(List.of(first, second));
        when(incidents.findByClassificationAndDemoRunIdIsNullAndFetchedAtBetweenOrderByFetchedAtDesc(any(), any(), any(), any()))
                .thenReturn(List.of());
        var context = service.findLive(now, "190", "44009", 1, ids);
        assertThat(context.classification()).isEqualTo("OBSERVATION_ONLY");
        assertThat(context.demoRunId()).isNull();
        assertThat(context.arrivals()).extracting(RootCauseContextSnapshot.RootCauseArrival::observationId)
                .containsExactlyElementsOf(ids);
        verify(incidents, never()).findByDemoRunIdOrderByFetchedAtDesc(any());
        verifyNoInteractions(hazards, topology);
    }

    @Test
    void liveContextRejectsAMixedSimulationObservation() {
        when(settings.retention()).thenReturn(Duration.ofHours(12));
        var first = arrival("OBSERVATION_ONLY", null);
        var second = arrival("SIMULATION", UUID.randomUUID());
        var ids = List.of(first.getId(), second.getId());
        when(arrivals.findAllById(ids)).thenReturn(List.of(first, second));
        assertThatThrownBy(() -> service.findLive(now, "190", "44009", 1, ids))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void regularSimulationContextIncludesOnlyLatestHazardsAtTheMonitoredStop() {
        Instant at = now.minusSeconds(5);
        UUID runId = UUID.randomUUID();
        UUID reference = UUID.randomUUID();
        when(settings.retention()).thenReturn(Duration.ofHours(12));
        when(runs.findById(runId)).thenReturn(Optional.of(new DemoRun(runId, now.minusSeconds(100), "bunching-v1", 1)));
        var first = arrival("SIMULATION", runId);
        var second = arrival("SIMULATION", runId);
        when(first.getStopCode()).thenReturn("44251");
        when(second.getStopCode()).thenReturn("44251");
        when(first.getReferenceVersionId()).thenReturn(reference);
        when(second.getReferenceVersionId()).thenReturn(reference);
        var ids = List.of(first.getId(), second.getId());
        when(arrivals.findAllById(ids)).thenReturn(List.of(first, second));

        var monitoredStop = new ReferenceTopologyPort.BusStop("44251", "Bt Panjang Rd", "Bt Panjang Stn/Blk 604",
                new BigDecimal("1.3803008"), new BigDecimal("103.7623167"));
        var facts = DemoHazardScenario.observations(DemoScenario.BUNCHING, 1, now.minusSeconds(100), at,
                monitoredStop.stopCode(), monitoredStop);
        var flood = hazard(runId, facts.get(0), at);
        var rail = hazard(runId, facts.get(1), at);
        var works = hazard(runId, facts.get(2), at);
        var previousWorks = hazard(runId, facts.getLast(), at.minusSeconds(30));
        var expired = hazard(runId, new HazardFeedPort.Fact(HazardKind.ROADWORKS, "expired", "OBSERVED", null,
                at.minusSeconds(20), at, "Expired works", null, null, null, List.of(),
                null, null, null, null, facts.get(2).locations()), at.minusSeconds(20));
        var otherDirection = hazard(runId, new HazardFeedPort.Fact(HazardKind.ROADWORKS, "elsewhere", "OBSERVED", null,
                null, null, "Unrelated works", null, null, null, List.of(), null, null, null, null,
                List.of(new HazardLocation("ROAD", "Other Road", "Other Road", null, null, null, "ROAD_NAME", "PROVIDER_SUPPLIED"))), at);
        when(hazards.findByDemoRunIdAndClassificationAndFetchedAtLessThanEqualOrderByFetchedAtDescIdAsc(runId, "SIMULATION", at))
                .thenReturn(List.of(flood, rail, works, otherDirection, expired, previousWorks));

        var selectedDirection = new ReferenceTopologyPort.BusRouteDirection(1, List.of(
                new ReferenceTopologyPort.BusRouteStop(monitoredStop.stopCode(), monitoredStop.roadName(),
                        monitoredStop.description(), monitoredStop.latitude(), monitoredStop.longitude(), 8, new BigDecimal("3.10")),
                new ReferenceTopologyPort.BusRouteStop("44231", "Bt Panjang Rd", "Zhenghua Pr Sch", new BigDecimal("1.3791669"),
                        new BigDecimal("103.7693349"), 10, new BigDecimal("4.00")),
                new ReferenceTopologyPort.BusRouteStop("44261", "Bt Panjang Rd", "Blk 270", new BigDecimal("1.3760822"),
                        new BigDecimal("103.7742778"), 11, new BigDecimal("4.70"))));
        var oppositeDirection = new ReferenceTopologyPort.BusRouteDirection(2, List.of(
                new ReferenceTopologyPort.BusRouteStop("44011", "Other Road", "Other Stop", BigDecimal.ZERO, BigDecimal.ZERO,
                        1, BigDecimal.ZERO)));
        when(topology.busRoute(reference, "190")).thenReturn(Optional.of(new ReferenceTopologyPort.BusRoute("190",
                List.of(new ReferenceTopologyPort.BusRouteOperator("SMRT", "TRUNK", List.of(selectedDirection, oppositeDirection))))));

        var context = service.find(runId, at, "190", monitoredStop.stopCode(), 1, ids);

        assertThat(context.missingFields()).doesNotContain("traffic-incidents");
        assertThat(context.incidents()).extracting(RootCauseContextSnapshot.RootCauseIncident::evidenceId)
                .containsExactly("hazard-observation:" + works.id());
        assertThat(context.incidents()).extracting(RootCauseContextSnapshot.RootCauseIncident::incidentType)
                .containsExactly("ROADWORKS");
        assertThat(context.incidents().getLast().observedAt()).isNull();
        assertThat(context.incidents().getLast().message()).contains("Bt Panjang Rd");
        assertThat(context.simulationDelay()).isNull();
    }

    @Test
    void roadworksDelayRequiresTheMatchingWorksiteAndAnObservedChangeInTheSamePair() {
        var started = now.minusSeconds(60);
        var runId = UUID.randomUUID();
        var reference = UUID.randomUUID();
        var gap = Duration.ofSeconds(120);
        var run = new DemoRun(runId, started, "bunching-v1", 1, new DemoRunConfiguration("190", 1, 2, gap,
                List.of(gap), "44141", DemoScenario.ROADWORKS_BUNCHING));
        var baselineLeadEta = started.plusMillis(64_800);
        var baselineFollowerEta = baselineLeadEta.plus(gap);
        var baselinePoll = UUID.randomUUID();
        var impactPoll = UUID.randomUUID();
        var baselineLead = roadworksArrival(runId, reference, baselinePoll, started.plusSeconds(5), 1, baselineLeadEta);
        var baselineFollower = roadworksArrival(runId, reference, baselinePoll, started.plusSeconds(5), 2, baselineFollowerEta);
        var lateLead = roadworksArrival(runId, reference, impactPoll, now, 1, baselineLeadEta.plusSeconds(105));
        var unchangedFollower = roadworksArrival(runId, reference, impactPoll, now, 2, baselineFollowerEta);
        var ids = List.of(lateLead.getId(), unchangedFollower.getId());
        when(settings.retention()).thenReturn(Duration.ofHours(12));
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(arrivals.findAllById(ids)).thenReturn(List.of(lateLead, unchangedFollower));
        when(arrivals.findDemoBaselinePair(runId, "demo:" + runId + ":0")).thenReturn(List.of(baselineLead, baselineFollower));
        var roadworks = hazard(runId, DemoHazardScenario.observations(DemoScenario.ROADWORKS_BUNCHING, 2, started, now)
                .stream().filter(fact -> fact.kind() == HazardKind.ROADWORKS).findFirst().orElseThrow(), now);
        when(hazards.findByDemoRunIdAndClassificationAndFetchedAtLessThanEqualOrderByFetchedAtDescIdAsc(runId, "SIMULATION", now))
                .thenReturn(List.of(roadworks));
        var stop = new ReferenceTopologyPort.BusRouteStop("44141", "Choa Chu Kang Rd", "Phoenix Stn", null, null, 7, null);
        var direction = new ReferenceTopologyPort.BusRouteDirection(1, List.of(stop));
        when(topology.busRoute(reference, "190")).thenReturn(Optional.of(new ReferenceTopologyPort.BusRoute("190",
                List.of(new ReferenceTopologyPort.BusRouteOperator("SMRT", "TRUNK", List.of(direction))))));

        assertThat(service.find(runId, now, "190", "44141", 1, ids).simulationDelay())
                .isEqualTo(new RootCauseContextSnapshot.SimulationDelay("hazard-observation:" + roadworks.id(),
                        lateLead.getId(), unchangedFollower.getId(), baselineLeadEta, 105));

        baselineFollower.setEstimatedArrivalAt(baselineFollowerEta.minusSeconds(3));
        assertThat(service.find(runId, now, "190", "44141", 1, ids).simulationDelay()).isNull();
    }

    private BusArrivalObservation roadworksArrival(UUID runId, UUID version, UUID poll, Instant fetchedAt, int bus, Instant eta) {
        var row = new BusArrivalObservation();
        row.setId(UUID.randomUUID());
        row.setClassification("SIMULATION");
        row.setDemoRunId(runId);
        row.setReferenceVersionId(version);
        row.setPollRunId(poll);
        row.setServiceId("190");
        row.setDirectionId(1);
        row.setStopCode("44141");
        row.setTripId("demo-190-" + bus);
        row.setVisitOrder(bus);
        row.setEstimatedArrivalAt(eta);
        row.setFetchedAt(fetchedAt);
        return row;
    }

    private HazardObservation hazard(UUID runId, HazardFeedPort.Fact fact, Instant fetchedAt) {
        return new HazardObservation(UUID.randomUUID(), UUID.randomUUID(), runId,
                fact.kind() + ":" + fact.sourceEventKey(), fact, fetchedAt);
    }

    private BusArrivalObservation arrival(String classification, UUID runId) {
        var row = mock(BusArrivalObservation.class);
        when(row.getId()).thenReturn(UUID.randomUUID());
        when(row.getClassification()).thenReturn(classification);
        when(row.getDemoRunId()).thenReturn(runId);
        when(row.getServiceId()).thenReturn("190");
        when(row.getStopCode()).thenReturn("44009");
        when(row.getDirectionId()).thenReturn(1);
        when(row.getFetchedAt()).thenReturn(now.minusSeconds(5));
        return row;
    }
}
