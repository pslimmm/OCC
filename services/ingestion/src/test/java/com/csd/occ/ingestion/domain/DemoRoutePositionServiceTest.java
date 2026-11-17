package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class DemoRoutePositionServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private static final double DEMO_SPEED_MPS = 10.0 / 3.6;
    private final DemoRunRepository runs = mock(DemoRunRepository.class);
    private final DemoVehicleAssignmentRepository assignments = mock(DemoVehicleAssignmentRepository.class);
    private final ReferenceTopologyPort topology = mock(ReferenceTopologyPort.class);

    @Test
    void returnsTheConfiguredFleetAtItsExactGapsAndMovesEveryBusAtTenKilometresPerHour() {
        var run = startRun(Duration.ofSeconds(120), Duration.ofSeconds(60));
        var snapshot = positions(run, 0);

        assertThat(snapshot.serviceId()).isEqualTo("972");
        assertThat(snapshot.sourceObservedAt()).contains(NOW);
        assertThat(snapshot.directions()).singleElement().satisfies(direction -> {
            assertThat(direction.directionId()).isEqualTo(2);
            assertThat(direction.buses()).hasSize(3);
            assertThat(direction.buses()).extracting(RoutePositionBus::speedMps).containsOnly(DEMO_SPEED_MPS);
            assertThat(direction.buses().getFirst().distanceAlongRouteM()).isBetween(1_500.0, 1_600.0);
            assertThat(direction.buses().get(0).distanceAlongRouteM() - direction.buses().get(1).distanceAlongRouteM())
                    .isCloseTo(DEMO_SPEED_MPS * 120, offset(0.01));
            assertThat(direction.buses().get(1).distanceAlongRouteM() - direction.buses().get(2).distanceAlongRouteM())
                    .isCloseTo(DEMO_SPEED_MPS * 60, offset(0.01));
        });

        var later = positions(run, 30);
        for (int index = 0; index < 3; index++) {
            assertThat(later.directions().getFirst().buses().get(index).distanceAlongRouteM()
                    - snapshot.directions().getFirst().buses().get(index).distanceAlongRouteM())
                    .isCloseTo(DEMO_SPEED_MPS * 30, offset(0.01));
        }
    }

    @Test
    void busesKeepMovingAfterReplayAndStartAnotherTripAtTheTerminus() {
        var run = startRun(Duration.ofSeconds(120), Duration.ofSeconds(60));
        var duringReplay = positions(run, 30).directions().getFirst();
        run.complete(NOW.plusSeconds(30));
        var afterReplay = positions(run, 90).directions().getFirst();
        assertThat(afterReplay.buses().getFirst().distanceAlongRouteM() - duringReplay.buses().getFirst().distanceAlongRouteM())
                .isCloseTo(DEMO_SPEED_MPS * 60, offset(0.01));

        var beforeTerminus = positions(run, 570).directions().getFirst();
        var nextTrip = positions(run, 590).directions().getFirst();
        double firstDistance = beforeTerminus.buses().getFirst().distanceAlongRouteM();
        double wrappedDistance = nextTrip.buses().getFirst().distanceAlongRouteM();
        assertThat(wrappedDistance).isLessThan(firstDistance);
        assertThat(nextTrip.routeLengthM() - firstDistance + wrappedDistance).isCloseTo(DEMO_SPEED_MPS * 20, offset(0.01));
        assertThat(nextTrip.buses()).extracting(RoutePositionBus::order).containsExactly(1, 2, 3);
    }

    @Test
    void roadsDelayOnlyTheLeadBusAndThePairLeavesTheWorksiteFifteenSecondsApart() {
        var run = startRun(List.of(Duration.ofSeconds(120), Duration.ofSeconds(120)), DemoScenario.ROADWORKS_BUNCHING);
        var approaching = positions(run, 42).directions().getFirst().buses();
        var waiting = positions(run, 50).directions().getFirst().buses();
        var stillWaiting = positions(run, 140).directions().getFirst().buses();
        var released = positions(run, 165).directions().getFirst().buses();

        double roadworksSpeed = 5.0 / 3.6;
        assertThat(approaching.getFirst().speedMps()).isEqualTo(roadworksSpeed);
        assertThat(waiting.getFirst().speedMps()).isZero();
        assertThat(stillWaiting.getFirst().distanceAlongRouteM()).isCloseTo(waiting.getFirst().distanceAlongRouteM(), offset(0.01));
        assertThat(waiting.get(1).distanceAlongRouteM()).isGreaterThan(approaching.get(1).distanceAlongRouteM());
        assertThat(released.getFirst().distanceAlongRouteM()).isGreaterThan(stillWaiting.getFirst().distanceAlongRouteM());
        assertThat(released.getFirst().distanceAlongRouteM() - released.get(1).distanceAlongRouteM())
                .isCloseTo(roadworksSpeed * 15, offset(0.01));
        assertThat(released.get(1).distanceAlongRouteM() - released.getLast().distanceAlongRouteM())
                .isCloseTo(roadworksSpeed * 120, offset(0.01));
    }

    @Test
    void nextStopAdvancesWhenTheBusPassesTheInjectionStopAndResetsAfterTheTerminus() {
        var run = startRun(Duration.ofSeconds(120), Duration.ofSeconds(60));
        assertThat(positionsAt(0).timeline(run).orElseThrow().nextStopSequence("demo-972-1")).isEqualTo(2);
        assertThat(positionsAt(12).timeline(run).orElseThrow().nextStopSequence("demo-972-1")).isEqualTo(3);
        assertThat(positionsAt(12).timeline(run).orElseThrow().nextStopSequence("demo-972-2")).isEqualTo(2);

        run.complete(NOW.plusSeconds(30));
        assertThat(positionsAt(570).timeline(run).orElseThrow().nextStopSequence("demo-972-1")).isEqualTo(3);
        assertThat(positionsAt(590).timeline(run).orElseThrow().nextStopSequence("demo-972-1")).isEqualTo(2);
    }

    @Test
    void mapPositionsFollowNumericTripOrderForDoubleDigitBusNumbers() {
        var run = startRun(java.util.Collections.nCopies(9, Duration.ofSeconds(15)));
        var buses = positions(run, 0).directions().getFirst().buses();

        assertThat(buses).extracting(RoutePositionBus::order).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(buses.getFirst().distanceAlongRouteM() - buses.getLast().distanceAlongRouteM())
                .isCloseTo(DEMO_SPEED_MPS * 15 * 9, offset(0.01));
        var timeline = positionsAt(5).timeline(run).orElseThrow();
        assertThat(timeline.nextStopSequence("demo-972-10")).isEqualTo(2);
        assertThat(timeline.trips().get("demo-972-10").position().distanceAlongRouteM())
                .isCloseTo(buses.getLast().distanceAlongRouteM() + DEMO_SPEED_MPS * 5, offset(0.01));
    }

    @Test
    void predictsAtTheTrailingBusNextStopAfterTheGroupClearsTheOriginalStop() {
        var run = startRun(Duration.ofSeconds(15), Duration.ofSeconds(45));
        var group = List.of("demo-972-1", "demo-972-2", "demo-972-3");
        assertThat(positionsAt(70).timeline(run).orElseThrow().controlForecast(group, 600)).isEmpty();

        var forecast = positionsAt(72).timeline(run).orElseThrow().controlForecast(group, 600).orElseThrow();
        var nextRead = positionsAt(82).timeline(run).orElseThrow().controlForecast(group, 600).orElseThrow();
        assertThat(forecast.stopCode()).isEqualTo("10003");
        assertThat(forecast.stopSequence()).isEqualTo(3);
        assertThat(forecast.arrivalTimes()).hasSize(3).isEqualTo(nextRead.arrivalTimes());
        assertThat(Duration.between(forecast.arrivalTimes().get(0), forecast.arrivalTimes().get(1))).isEqualTo(Duration.ofSeconds(15));
        assertThat(Duration.between(forecast.arrivalTimes().get(1), forecast.arrivalTimes().get(2))).isEqualTo(Duration.ofSeconds(45));
        var direction = positions(run, 72).directions().getFirst();
        double metresToTarget = direction.routeLengthM() - direction.buses().getLast().distanceAlongRouteM();
        long remainingMillis = Duration.between(forecast.computedAt(), forecast.arrivalTimes().getLast()).toMillis();
        assertThat(metresToTarget).isCloseTo(DEMO_SPEED_MPS * remainingMillis / 1_000.0, offset(0.01));
        assertThat(positionsAt(72).timeline(run).orElseThrow().controlForecast(group, 60)).isEmpty();
        assertThat(positionsAt(72).timeline(run).orElseThrow()
                .controlForecast(List.of("demo-972-1", "demo-972-3"), 600)).isEmpty();
    }

    @Test
    void refusesATerminusSplitAndUsesTheCurrentTripAfterAllBusesWrap() {
        var run = startRun(Duration.ofSeconds(15), Duration.ofSeconds(45));
        var group = List.of("demo-972-1", "demo-972-2", "demo-972-3");
        assertThat(positionsAt(590).timeline(run).orElseThrow().controlForecast(group, 600)).isEmpty();

        var forecast = positionsAt(1_170).timeline(run).orElseThrow().controlForecast(group, 600).orElseThrow();
        assertThat(forecast.lap()).isEqualTo(1);
        assertThat(forecast.stopSequence()).isEqualTo(2);
        assertThat(forecast.arrivalTimes().getFirst()).isBefore(forecast.computedAt());
        assertThat(forecast.arrivalTimes().getLast()).isAfter(forecast.computedAt());
    }

    @Test
    void freshDetectorEvidenceCanSupplyANewControlForecastAfterTheOriginalPredictionsPass() {
        var run = startRun(Duration.ofSeconds(15), Duration.ofSeconds(45));
        var raw = mock(RawObservationRepository.class);
        var arrivals = mock(BusArrivalObservationRepository.class);
        var detector = mock(BunchingControlPort.class);
        var polling = mock(PollingConfigurationPort.class);
        UUID eventId = UUID.randomUUID();
        var observation = new RawObservation();
        observation.setId(UUID.randomUUID());
        observation.setFetchedAt(NOW.plusSeconds(1_070));
        when(raw.findLatestDemoBunchingSnapshot(run.id(), "demo:" + run.id() + ":stop:10002", PageRequest.of(0, 1)))
                .thenReturn(List.of(observation));
        var found = IntStream.rangeClosed(1, 3).mapToObj(order -> {
            var arrival = new BusArrivalObservation();
            arrival.setId(UUID.randomUUID());
            arrival.setRawObservationId(observation.getId());
            arrival.setDemoRunId(run.id());
            arrival.setClassification("SIMULATION");
            arrival.setServiceId("972");
            arrival.setDirectionId(2);
            arrival.setStopCode("10002");
            arrival.setVisitOrder(order);
            arrival.setTripId("demo-972-" + order);
            arrival.setEstimatedArrivalAt(observation.getFetchedAt().plusSeconds(5L * order));
            when(arrivals.findSimulationArrivalDetails(run.id(), arrival.getId())).thenReturn(Optional.of(
                    new DemoSimulationArrivalDetails("REG-" + order, "DEMO", "DD", "CAP-" + order,
                            arrival.getEstimatedArrivalAt(), arrival.getTripId())));
            return arrival;
        }).toList();
        var ids = found.stream().map(BusArrivalObservation::getId).toList();
        when(arrivals.findAllById(ids)).thenReturn(found);
        when(detector.currentEvidence(run.id(), eventId)).thenReturn(Optional.of(new BunchingControlPort.CurrentEvidence(
                run.id(), eventId, "972", "10002", 2, "BUNCHING", observation.getId(), observation.getFetchedAt(),
                NOW.plusSeconds(1_270), true, ids)));
        when(polling.staleAfterSeconds()).thenReturn(600);

        var service = new CurrentSimulationBunchingGroupService(runs, raw, arrivals, detector,
                Clock.fixed(NOW.plusSeconds(1_170), ZoneOffset.UTC), mock(DemoReplayService.class),
                mock(DemoModeService.class), positionsAt(1_170), polling);
        var group = service.find(run.id(), eventId).orElseThrow();
        assertThat(group.stopCode()).isEqualTo("10002");
        assertThat(group.rawObservationId()).isEqualTo(observation.getId());
        assertThat(group.adjacentGapSeconds()).containsExactly(5L, 5L);
        assertThat(group.controlPrediction().arrivals())
                .extracting(CurrentSimulationBunchingGroupService.ControlArrival::busReference)
                .containsExactly("demo-972-1", "demo-972-2", "demo-972-3");
        assertThat(Duration.between(group.controlPrediction().arrivals().getFirst().arrivalAt(),
                group.controlPrediction().arrivals().getLast().arrivalAt())).isEqualTo(Duration.ofSeconds(60));

        var later = new CurrentSimulationBunchingGroupService(runs, raw, arrivals, detector,
                Clock.fixed(NOW.plusSeconds(1_171), ZoneOffset.UTC), mock(DemoReplayService.class),
                mock(DemoModeService.class), positionsAt(1_171), polling).find(run.id(), eventId).orElseThrow();
        assertThat(later.controlPrediction().snapshotId()).isEqualTo(group.controlPrediction().snapshotId());
        assertThat(later.controlPrediction().arrivals()).isEqualTo(group.controlPrediction().arrivals());

        when(polling.staleAfterSeconds()).thenReturn(1);
        assertThat(service.find(run.id(), eventId)).isEmpty();
    }

    @Test
    void gapsLongerThanTheRouteDoNotLeaveWaitingBusesAtTheOrigin() {
        var run = startRun(Duration.ofMinutes(15), Duration.ofMinutes(15));
        var initial = positions(run, 0).directions().getFirst();
        var later = positions(run, 1).directions().getFirst();
        for (int index = 0; index < 3; index++) {
            double distance = initial.buses().get(index).distanceAlongRouteM();
            assertThat(distance).isPositive().isLessThan(initial.routeLengthM());
            assertThat(later.buses().get(index).distanceAlongRouteM() - distance).isCloseTo(DEMO_SPEED_MPS, offset(0.01));
        }
    }

    @Test
    void routePositionsAreUnavailableAfterTheRunCloses() {
        var run = startRun(Duration.ofSeconds(120), Duration.ofSeconds(60));
        run.close(NOW.plusSeconds(30));
        assertThatThrownBy(() -> positions(run, 90)).isInstanceOf(IllegalArgumentException.class);
    }

    private DemoRun startRun(Duration firstGap, Duration secondGap) {
        return startRun(List.of(firstGap, secondGap));
    }

    private DemoRun startRun(List<Duration> gaps) {
        return startRun(gaps, DemoScenario.NORMAL);
    }

    private DemoRun startRun(List<Duration> gaps, DemoScenario scenario) {
        UUID runId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        int busCount = gaps.size() + 1;
        var configuration = new DemoRunConfiguration("972", 2, busCount, gaps.getFirst(), gaps, "10002", scenario);
        var run = new DemoRun(runId, NOW, "multi-route-bunching-v2", 190, configuration);
        run.startReplay();
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(topology.activeVersion()).thenReturn(Optional.of(new ReferenceTopologyPort.ReferenceTopologyVersion(versionId, "checksum")));
        var route = new ReferenceTopologyPort.BusRoute("972", List.of(new ReferenceTopologyPort.BusRouteOperator("SMRT", "DD", List.of(
                new ReferenceTopologyPort.BusRouteDirection(2, List.of(
                        new ReferenceTopologyPort.BusRouteStop("10001", "Road", "Start", new BigDecimal("1.3000000"), new BigDecimal("103.8000000"), 1, BigDecimal.ZERO),
                        new ReferenceTopologyPort.BusRouteStop("10002", "Road", "Middle", new BigDecimal("1.3100000"), new BigDecimal("103.8100000"), 2, BigDecimal.ONE),
                        new ReferenceTopologyPort.BusRouteStop("10003", "Road", "End", new BigDecimal("1.3200000"), new BigDecimal("103.8200000"), 3, new BigDecimal("2"))))))));
        when(topology.busRoute(versionId, "972")).thenReturn(Optional.of(route));
        var fleet = IntStream.rangeClosed(1, busCount).mapToObj(number -> assignment(runId, "demo-972-" + number, number))
                .sorted(Comparator.comparing(DemoVehicleAssignment::busId)).toList();
        when(assignments.findByIdDemoRunIdOrderByBusId(runId, PageRequest.of(0, busCount))).thenReturn(new PageImpl<>(fleet));
        return run;
    }

    private RoutePositionSnapshot positions(DemoRun run, long elapsedSeconds) {
        return positionsAt(elapsedSeconds).positions(run.id());
    }

    private DemoRoutePositionService positionsAt(long elapsedSeconds) {
        return new DemoRoutePositionService(runs, assignments, topology,
                Clock.fixed(NOW.plusSeconds(elapsedSeconds), ZoneOffset.UTC));
    }

    private DemoVehicleAssignment assignment(UUID runId, String tripId, int number) {
        return new DemoVehicleAssignment(new DemoVehicleAssignmentId(runId, tripId), "972", 2,
                "DEMO-972-" + String.format("%02d", number), "DEMO-OPERATOR", "DD", "CAPTAIN-" + number);
    }
}
