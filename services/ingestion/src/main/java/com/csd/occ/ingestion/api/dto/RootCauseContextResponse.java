package com.csd.occ.ingestion.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RootCauseContextResponse(String classification, UUID demoRunId, UUID referenceVersionId,
                                       List<ArrivalEvidenceResponse> arrivals,
                                       List<IncidentEvidenceResponse> incidents, List<String> missingFields,
                                       @JsonInclude(JsonInclude.Include.NON_NULL) SimulationDelayResponse simulationDelay) {
    public RootCauseContextResponse {
        arrivals = List.copyOf(arrivals); incidents = List.copyOf(incidents); missingFields = List.copyOf(missingFields);
    }
    public record ArrivalEvidenceResponse(String evidenceId, UUID observationId, UUID rawObservationId, UUID pollRunId,
            String serviceId, String stopCode, Integer directionId, Instant estimatedArrivalAt, Instant fetchedAt,
            java.math.BigDecimal latitude, java.math.BigDecimal longitude, String loadCategory, String vehicleType,
            String classification, UUID demoRunId, UUID referenceVersionId) { }
    public record IncidentEvidenceResponse(String evidenceId, UUID incidentId, String incidentType, String message,
            java.math.BigDecimal latitude, java.math.BigDecimal longitude, Instant observedAt, Instant fetchedAt,
            String classification, UUID demoRunId) { }
    @Schema(description = "Recorded before/after forecasts for the same pair and targeted simulation roadworks")
    public record SimulationDelayResponse(String hazardEvidenceId, UUID leadingObservationId, UUID followingObservationId,
            Instant baselineLeadingArrivalAt, int configuredDelaySeconds) { }
}
