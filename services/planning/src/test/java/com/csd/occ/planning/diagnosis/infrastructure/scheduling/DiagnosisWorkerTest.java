package com.csd.occ.planning.diagnosis.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.csd.occ.planning.advisory.domain.HazardAdvisoryProcessor;
import com.csd.occ.planning.diagnosis.domain.DeliveryClaim;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestService;
import com.csd.occ.planning.diagnosis.domain.PlanningScope;
import com.csd.occ.planning.diagnosis.domain.PlanningScopePort;
import com.csd.occ.planning.diagnosis.domain.RecommendationDeliveryService;
import com.csd.occ.planning.diagnosis.domain.RecommendationSubmissionPort;
import com.csd.occ.planning.diagnosis.domain.RootCauseDiagnosisProcessor;
import com.csd.occ.planning.diagnosis.infrastructure.config.DiagnosisConfig;
import com.csd.occ.planning.diagnosis.infrastructure.config.DiagnosisProperties;
import com.csd.occ.planning.infrastructure.security.PeerClientProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.support.TestPropertySourceUtils;

class DiagnosisWorkerTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void eachWorkerUsesItsOwnClaimsAndProcessor(boolean autoProcessBunching) {
        var requests = mock(DiagnosisRequestService.class);
        var bunching = mock(RootCauseDiagnosisProcessor.class);
        var hazards = mock(HazardAdvisoryProcessor.class);
        var deliveries = mock(RecommendationDeliveryService.class);
        var properties = mock(DiagnosisProperties.class);
        var hazardId = UUID.randomUUID();
        var bunchingId = UUID.randomUUID();
        var lease = Duration.ofMinutes(5);
        when(properties.leaseDuration()).thenReturn(lease);
        when(properties.workerBatchSize()).thenReturn(10);
        when(properties.autoProcessBunching()).thenReturn(autoProcessBunching);
        when(requests.claimDueHazards(1, lease)).thenReturn(List.of(hazardId));
        when(requests.claimDueBunchingDiagnoses(1, lease, autoProcessBunching)).thenReturn(List.of(bunchingId));
        var worker = new DiagnosisWorker(requests, bunching, deliveries, mock(RecommendationSubmissionPort.class),
                properties, Clock.systemUTC(), hazards, mock(PlanningScopePort.class));

        worker.processDueHazards();

        verify(hazards).processIfHazard(hazardId);
        verifyNoInteractions(bunching, deliveries);

        worker.processDueBunchingDiagnoses();

        verify(bunching).process(bunchingId);
        verify(deliveries).claimDue(10, lease);
        verifyNoMoreInteractions(hazards);
    }

    @Test
    void bunchingDiagnosisAndSimulationCautionsProcessWhileRoadworksDraftingIsBlocked() throws InterruptedException {
        var requests = mock(DiagnosisRequestService.class);
        var hazards = mock(HazardAdvisoryProcessor.class);
        var bunching = mock(RootCauseDiagnosisProcessor.class);
        var deliveries = mock(RecommendationDeliveryService.class);
        var recommendation = mock(RecommendationSubmissionPort.class);
        var properties = mock(DiagnosisProperties.class);
        var scope = new PlanningScope("DEMO", UUID.randomUUID());
        var roadworksId = UUID.randomUUID();
        var bunchingId = UUID.randomUUID();
        var cautionId = UUID.randomUUID();
        var delivery = new DeliveryClaim(UUID.randomUUID(), "{}", 1);
        var roadworksStarted = new CountDownLatch(1);
        var releaseRoadworks = new CountDownLatch(1);
        var roadworksFinished = new CountDownLatch(1);
        var diagnosisCompleted = new CountDownLatch(1);
        var recommendationDelivered = new CountDownLatch(1);
        var cautionCompleted = new CountDownLatch(1);
        var lease = Duration.ofMinutes(5);
        when(properties.leaseDuration()).thenReturn(lease);
        when(properties.workerBatchSize()).thenReturn(10);
        when(properties.requestTimeout()).thenReturn(Duration.ofSeconds(1));
        when(properties.peerResponseMaxBytes()).thenReturn(16_384L);
        when(properties.ingestionInternalUrl()).thenReturn(URI.create("http://localhost:8001"));
        when(properties.disruptionInternalUrl()).thenReturn(URI.create("http://localhost:8002"));
        when(requests.claimDueHazards(1, lease)).thenReturn(List.of(roadworksId), List.of());
        when(requests.claimDueBunchingDiagnoses(1, lease, false)).thenAnswer(call ->
                roadworksStarted.getCount() == 0 && diagnosisCompleted.getCount() > 0 ? List.of(bunchingId) : List.of());
        when(requests.claimDueSimulationCautions(1, lease, scope)).thenAnswer(call ->
                roadworksStarted.getCount() == 0 && cautionCompleted.getCount() > 0 ? List.of(cautionId) : List.of());
        when(hazards.processIfHazard(roadworksId)).thenAnswer(call -> {
            roadworksStarted.countDown();
            releaseRoadworks.await(20, TimeUnit.SECONDS);
            roadworksFinished.countDown();
            return true;
        });
        doAnswer(call -> { diagnosisCompleted.countDown(); return null; }).when(bunching).process(bunchingId);
        when(deliveries.claimDue(10, lease)).thenAnswer(call -> diagnosisCompleted.getCount() == 0
                && recommendationDelivered.getCount() > 0 ? List.of(delivery) : List.of());
        when(recommendation.submit(delivery.deliveryId(), delivery.payloadJson()))
                .thenReturn(new RecommendationSubmissionPort.DeliveryResult(200, "{}"));
        doAnswer(call -> { recommendationDelivered.countDown(); return null; })
                .when(deliveries).delivered(delivery.deliveryId(), 200, "{}");
        when(hazards.processIfHazard(cautionId)).thenAnswer(call -> { cautionCompleted.countDown(); return true; });

        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "occ.diagnosis.worker-interval=20ms");
            context.registerBean(DiagnosisProperties.class, () -> properties);
            context.registerBean(PeerClientProperties.class, () -> new PeerClientProperties("ingestion-test", "disruption-test"));
            context.registerBean(ThreadPoolTaskSchedulerBuilder.class, () -> new ThreadPoolTaskSchedulerBuilder().poolSize(1));
            context.registerBean(DiagnosisRequestService.class, () -> requests);
            context.registerBean(HazardAdvisoryProcessor.class, () -> hazards);
            context.registerBean(PlanningScopePort.class, () -> () -> scope);
            context.registerBean(RootCauseDiagnosisProcessor.class, () -> bunching);
            context.registerBean(RecommendationDeliveryService.class, () -> deliveries);
            context.registerBean(RecommendationSubmissionPort.class, () -> recommendation);
            context.register(EnableWorkerScheduling.class, DiagnosisConfig.class, DiagnosisWorker.class);
            try {
                context.refresh();
                assertThat(roadworksStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(diagnosisCompleted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(recommendationDelivered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(cautionCompleted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(roadworksFinished.getCount()).isEqualTo(1);
            } finally {
                releaseRoadworks.countDown();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class EnableWorkerScheduling { }
}
