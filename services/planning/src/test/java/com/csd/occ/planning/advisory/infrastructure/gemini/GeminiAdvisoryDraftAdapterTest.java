package com.csd.occ.planning.advisory.infrastructure.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.csd.occ.planning.advisory.domain.AdvisoryContextPort;
import com.csd.occ.planning.advisory.domain.HazardEventFacts;
import com.csd.occ.planning.advisory.domain.InstructionPolicy;
import com.csd.occ.planning.advisory.domain.InstructionType;
import com.csd.occ.planning.domain.audit.ApplicationAuditCapturePort;
import com.csd.occ.planning.recommendation.infrastructure.config.RecommendationAiProperties;
import com.csd.occ.planning.recommendation.infrastructure.config.RecommendationInputProperties;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;

class GeminiAdvisoryDraftAdapterTest {
    private final ApplicationAuditCapturePort audit = mock(ApplicationAuditCapturePort.class);
    private static final AdvisoryContextPort.Limits LIMITS = new AdvisoryContextPort.Limits(10, 900, 10,
            Duration.ofMinutes(5), Duration.ofHours(12));
    private static final String VALID = """
            {"title":"Flood update","message":"Water reported. Use caution.","services":["190"],"locations":["Example Stop"]}
            """;

    @Test
    void syntheticRoadworksDraftPassesThroughTheGeminiClientWithOptionalParameters() throws Exception {
        var json = JsonMapper.builder().build();
        String candidate = """
                {"type":"BE_CAUTIOUS","parameters":{"stopCode":"44251","stopName":"Example Stop","stopSequence":10},
                 "rationale":"Use caution near the reported roadworks."}
                """;
        byte[] response = json.writeValueAsBytes(Map.of("modelVersion", "synthetic-model", "candidates", List.of(
                Map.of("content", Map.of("role", "model", "parts", List.of(Map.of("text", candidate))), "finishReason", "STOP"))));
        var request = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/", exchange -> {
                try (exchange) {
                    request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                }
            });
            server.start();
            try (var client = Client.builder().apiKey("synthetic-key").httpOptions(HttpOptions.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).timeout(3000)
                    .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build()).build()) {
                var model = GoogleGenAiChatModel.builder().genAiClient(client)
                        .options(GoogleGenAiChatOptions.builder().model("synthetic-model").build()).build();
                var clients = new StaticListableBeanFactory();
                clients.addBean("model", ChatClient.create(model));
                var adapter = new GeminiAdvisoryDraftAdapter(clients.getBeanProvider(ChatClient.class),
                        new RecommendationAiProperties(true), new RecommendationInputProperties(8192), json, audit);
                var result = adapter.propose(simulatedHazard("ROADWORKS", null), simulatedBus("private-bus-id"), LIMITS);

                var config = json.readTree(request.get()).path("generationConfig");
                var parameters = config.path("responseJsonSchema").path("properties").path("parameters");
                assertThat(config.path("responseMimeType").asString()).isEqualTo("application/json");
                assertThat(parameters.path("properties").path("durationSeconds").path("type").asString())
                        .isEqualToIgnoringCase("integer");
                assertThat(result.candidate().type()).isEqualTo(InstructionType.BE_CAUTIOUS);
                assertThat(result.model()).isEqualTo("synthetic-model");
                assertThat(result.candidate().parameters().stopCode()).isEqualTo("44251");
                assertThat(result.candidate().parameters().durationSeconds()).isNull();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sourceRestrictionProducesCautionForEveryAffectedBusWithoutGemini() {
        var model = mock(ChatModel.class);
        var adapter = adapter(model, true);
        var flood = simulatedHazard("FLOOD", "Avoid the affected area.");
        var policy = new InstructionPolicy();

        for (String id : List.of("private-bus-id", "bus-2", "bus-3")) {
            var bus = simulatedBus(id);
            var result = adapter.propose(flood, bus, LIMITS);
            assertThat(result.model()).isEqualTo("DETERMINISTIC_CAUTION");
            assertThat(policy.message(result.candidate(), bus, flood, LIMITS))
                    .isEqualTo("Flood alert along your route through Last Affected Stop. Avoid the affected area.");
            verify(audit).recordAi(eq("AI_ADVISORY_DRAFT_FALLBACK"), eq("FALLBACK_NOT_ATTEMPTED"),
                    eq(flood.disruptionId()), eq(flood.demoRunId()), eq(id), eq("ADVISORY_DRAFT"),
                    eq("DETERMINISTIC_CAUTION"), eq("SOURCE_RESTRICTION"), eq("{}"), anyString());
        }
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void simulatedRailCautionStaysApplicableThroughTheFinalAffectedStopWithoutGemini() {
        var model = mock(ChatModel.class);
        var rail = simulatedHazard("RAIL_DISRUPTION", null);
        var adapter = adapter(model, true);
        var policy = new InstructionPolicy();
        var bus = simulatedBus("private-bus-id");
        var result = adapter.propose(rail, bus, LIMITS);

        assertThat(result.model()).isEqualTo("DETERMINISTIC_CAUTION");
        assertThat(result.candidate().parameters().stopSequence()).isEqualTo(11);
        var atLastStop = new AdvisoryContextPort.Bus(bus.busId(), bus.tripId(), bus.registrationNumber(),
                bus.serviceId(), bus.directionId(), bus.captainUserId(), bus.assignmentStatus(), bus.progress(), 0, 11);
        assertThat(policy.message(result.candidate(), atLastStop, rail, LIMITS))
                .isEqualTo("Rail disruption reported along your route through Last Affected Stop. Be cautious.");
        var afterLastStop = new AdvisoryContextPort.Bus(bus.busId(), bus.tripId(), bus.registrationNumber(),
                bus.serviceId(), bus.directionId(), bus.captainUserId(), bus.assignmentStatus(), bus.progress(), 0, 12);
        assertThatThrownBy(() -> policy.message(result.candidate(), afterLastStop, rail, LIMITS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no current downstream exposure");
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void providerFailureUsesCautionForThatBusAndKeepsTheNextValidDraft() {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class)))
                .thenThrow(new IllegalStateException("Synthetic provider unavailable"))
                .thenReturn(response("""
                        {"type":"REDUCE_SPEED","parameters":{"speedReductionKmh":5,"durationSeconds":60},
                         "rationale":"Reported water may affect the route."}
                        """));
        var adapter = adapter(model, true);
        var roadworks = simulatedHazard("ROADWORKS", null);
        var first = adapter.propose(roadworks, simulatedBus("private-bus-id"), LIMITS);
        var second = adapter.propose(roadworks, simulatedBus("bus-2"), LIMITS);

        assertThat(first.model()).isEqualTo("DETERMINISTIC_CAUTION");
        assertThat(first.candidate().type()).isEqualTo(InstructionType.BE_CAUTIOUS);
        assertThat(first.candidate().parameters().stopSequence()).isEqualTo(10);
        assertThat(second.model()).isEqualTo("synthetic-model");
        assertThat(second.candidate().type()).isEqualTo(InstructionType.REDUCE_SPEED);
        verifyProviderFallback(roadworks, "IllegalStateException");
    }

    @ParameterizedTest
    @MethodSource("unusableAdvisoryResponses")
    void emptyMalformedAndInvalidAdvisoriesFallBackToDownstreamCaution(String output, String reason) {
        var roadworks = simulatedHazard("ROADWORKS", null);
        var bus = simulatedBus("private-bus-id");
        var result = adapter(modelReturning(output), true).propose(roadworks, bus, LIMITS);

        assertThat(result.model()).isEqualTo("DETERMINISTIC_CAUTION");
        assertThat(new InstructionPolicy().message(result.candidate(), bus, roadworks, LIMITS))
                .isEqualTo("Roadworks reported near Example Stop. Be cautious.");
        verifyProviderFallback(roadworks, reason);
    }

    static Stream<Arguments> unusableAdvisoryResponses() {
        return Stream.of(Arguments.of(" ", "EMPTY_RESPONSE"), Arguments.of("{not-json", "MALFORMED_RESPONSE"),
                Arguments.of("null", "OUTPUT_POLICY_REJECTED"), Arguments.of("""
                        {"type":"REDUCE_SPEED","parameters":{"speedReductionKmh":20,"durationSeconds":60},"rationale":"Caution"}
                        """, "OUTPUT_POLICY_REJECTED"));
    }

    private void verifyProviderFallback(AdvisoryContextPort.Hazard hazard, String reason) {
        verify(audit).recordAi(eq("AI_ADVISORY_DRAFT_FALLBACK"), eq("FALLBACK_USED"), eq(hazard.disruptionId()),
                eq(hazard.demoRunId()), eq("private-bus-id"), eq("ADVISORY_DRAFT"), eq("DETERMINISTIC_CAUTION"),
                eq(reason), eq("{}"), anyString());
    }

    @Test
    void disabledAiUsesSourceFactsAndDistinctAffectedServicesAndStopsWithoutCallingTheModel() {
        var model = mock(ChatModel.class);
        var suggestion = adapter(model, false).suggestNotice(hazard(true, "Water reported."));
        assertThat(suggestion.title()).isEqualTo("Flood alert");
        assertThat(suggestion.message()).isEqualTo("Water reported.\nUse caution.");
        assertThat(suggestion.services()).containsExactly("190");
        assertThat(suggestion.locations()).containsExactly("Example Stop");
        assertThat(suggestion.generator()).isEqualTo("SOURCE_FACTS");
        verifyNoInteractions(model);
        verify(audit).recordAi(eq("AI_NOTICE_SUGGESTION_FALLBACK"), eq("FALLBACK_NOT_ATTEMPTED"), any(), any(),
                isNull(), eq("NOTICE_SUGGESTION"), eq("SOURCE_FACTS"), isNull(), anyString(), anyString());
    }

    @Test
    void sourceLocationsRemainAvailableWithoutFleetExposure() {
        var model = mock(ChatModel.class);
        var suggestion = adapter(model, false).suggestNotice(hazard(false, "Water reported."));
        assertThat(suggestion.services()).isEmpty();
        assertThat(suggestion.locations()).containsExactly("Reported Road");
        assertThat(suggestion.message()).contains("Use caution.");
        verifyNoInteractions(model);
    }

    @Test
    void enabledAiReturnsEditableTextAndReceivesOnlyPassengerFacts() {
        var model = modelReturning(VALID);
        var source = hazard(true, "Water reported.");
        var suggestion = adapter(model, true).suggestNotice(source);
        assertThat(suggestion.title()).isEqualTo("Flood update");
        assertThat(suggestion.message()).isEqualTo("Water reported. Use caution.");
        assertThat(suggestion.services()).containsExactly("190");
        assertThat(suggestion.locations()).containsExactly("Example Stop");
        assertThat(suggestion.generator()).isEqualTo("synthetic-model");
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getUserMessage().getText())
                .contains("Water reported.", "Use caution.", "190", "Example Stop")
                .doesNotContain(source.disruptionId().toString(), source.sourceObservationId().toString(),
                        "private-bus-id", "private-source-reference");
        assertThat(prompt.getValue().getOptions()).isInstanceOfSatisfying(GoogleGenAiChatOptions.class,
                options -> assertThat(options.getResponseMimeType()).isEqualTo("application/json"));
    }

    @ParameterizedTest
    @MethodSource("invalidSuggestions")
    void unsupportedModelOutputCannotBecomePassengerWording(String output) {
        var suggestion = adapter(modelReturning(output), true).suggestNotice(hazard(false, "Water reported."));
        assertThat(suggestion.generator()).isEqualTo("SOURCE_FACTS");
        assertThat(suggestion.message()).contains("Water reported.", "Use caution.");
    }

    static Stream<String> invalidSuggestions() {
        return Stream.of("null", VALID.replace("Flood update", " "), VALID.replace("Flood update", "x".repeat(161)),
                VALID.replace("Water reported. Use caution.", "x".repeat(2001)),
                VALID.replace("Use caution.", "Safe to travel."), VALID.replace("190", "999"),
                VALID.replace("Example Stop", "Invented Stop"), VALID.replace("[\"190\"]", "null"),
                VALID.replace("[\"Example Stop\"]", "null"));
    }

    @Test
    void malformedOrEmptyModelResponsesAreNotAccepted() {
        assertThatThrownBy(() -> adapter(modelReturning("{not-json"), true).suggestNotice(hazard(true, "Water reported.")))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
        verify(audit).recordAi(eq("AI_NOTICE_SUGGESTION_OUTPUT"), eq("RESPONSE_RECEIVED"), any(), any(),
                isNull(), eq("NOTICE_SUGGESTION"), eq("synthetic-model"), isNull(), anyString(), eq("{not-json"),
                argThat(metrics -> "notice-prompt-v1".equals(metrics.get("promptVersion"))
                        && metrics.containsKey("latencyMillis") && metrics.containsKey("promptTokens")));
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of()));
        assertThatThrownBy(() -> adapter(model, true).suggestNotice(hazard(true, "Water reported.")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Empty notice model response");
    }

    @Test
    void oversizedSourceIsRejectedBeforeSendingAnythingToTheModel() {
        var model = mock(ChatModel.class);
        assertThatThrownBy(() -> adapter(model, true).suggestNotice(hazard(true, "水".repeat(4000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("input exceeds");
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void failedProviderCallDoesNotFabricateASuggestion() {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("Synthetic provider unavailable"));
        assertThatThrownBy(() -> adapter(model, true).suggestNotice(hazard(true, "Water reported.")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("provider unavailable");
        verify(audit).recordAi(eq("AI_NOTICE_SUGGESTION_FAILURE"), eq("FAILURE"), any(), any(), isNull(),
                eq("NOTICE_SUGGESTION"), isNull(), eq("IllegalStateException"), anyString(), isNull(),
                argThat(metrics -> "notice-prompt-v1".equals(metrics.get("promptVersion"))
                        && "notice-schema-v1".equals(metrics.get("schemaVersion"))
                        && metrics.containsKey("latencyMillis") && !metrics.containsKey("promptTokens")));
    }

    private GeminiAdvisoryDraftAdapter adapter(ChatModel model, boolean enabled) {
        var clients = new StaticListableBeanFactory();
        if (enabled) {
            when(model.getOptions()).thenReturn(GoogleGenAiChatOptions.builder().model("synthetic-model").build());
            clients.addBean("model", ChatClient.create(model));
        }
        return new GeminiAdvisoryDraftAdapter(clients.getBeanProvider(ChatClient.class),
                new RecommendationAiProperties(enabled), new RecommendationInputProperties(8192), JsonMapper.builder().build(), audit);
    }

    private ChatModel modelReturning(String output) {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(output));
        return model;
    }

    private ChatResponse response(String output) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(output))),
                ChatResponseMetadata.builder().model("synthetic-model").build());
    }

    private AdvisoryContextPort.Hazard simulatedHazard(String kind, String instruction) {
        var facts = new HazardEventFacts.Fact(kind, "synthetic-hazard", "OBSERVED", Instant.EPOCH, null, null,
                "Reported hazard", "Synthetic report.", "Severe", instruction, List.of(), null, null, null, null, List.of());
        var exposures = List.of(
                new AdvisoryContextPort.Exposure("private-bus-id", "190", 1, "44248", "Passed Stop", 8, "SYNTHETIC", null),
                new AdvisoryContextPort.Exposure("private-bus-id", "190", 1, "44251", "Example Stop", 10, "SYNTHETIC", null),
                new AdvisoryContextPort.Exposure("bus-2", "190", 1, "44251", "Example Stop", 10, "SYNTHETIC", null),
                new AdvisoryContextPort.Exposure("bus-3", "190", 1, "44251", "Example Stop", 10, "SYNTHETIC", null),
                new AdvisoryContextPort.Exposure("private-bus-id", "190", 1, "44261", "Last Affected Stop", 11, "SYNTHETIC", null),
                new AdvisoryContextPort.Exposure("bus-2", "190", 1, "44261", "Last Affected Stop", 11, "SYNTHETIC", null),
                new AdvisoryContextPort.Exposure("bus-3", "190", 1, "44261", "Last Affected Stop", 11, "SYNTHETIC", null));
        return new AdvisoryContextPort.Hazard(UUID.randomUUID(), kind, "ACTIVE", null, "SIMULATION", UUID.randomUUID(),
                UUID.randomUUID(), Instant.EPOCH, facts, exposures, List.of());
    }

    private AdvisoryContextPort.Bus simulatedBus(String busId) {
        return new AdvisoryContextPort.Bus(busId, "trip", "registration", "190", 1, null, "UNASSIGNED",
                new AdvisoryContextPort.Progress("44240", 7, Instant.EPOCH, 30, List.of(5), List.of(60)), 0, 9);
    }

    private AdvisoryContextPort.Hazard hazard(boolean exposed, String description) {
        var facts = new HazardEventFacts.Fact("FLOOD", "private-source-reference", "OBSERVED", Instant.EPOCH,
                null, null, "Flood alert", description, "Severe", "Use caution.", List.of(), null, null, null, null,
                List.of(new HazardEventFacts.Location("ROAD", "private-location-id", "Reported Road", null, null, null, null, null)));
        var exposure = new AdvisoryContextPort.Exposure("private-bus-id", "190", 1, "44251", "Example Stop", 10, "SYNTHETIC", "Unverified");
        return new AdvisoryContextPort.Hazard(UUID.randomUUID(), "FLOOD", "ACTIVE", null, "OBSERVATION_ONLY", null,
                UUID.randomUUID(), Instant.EPOCH, facts, exposed ? List.of(exposure, exposure) : List.of(), List.of());
    }
}
