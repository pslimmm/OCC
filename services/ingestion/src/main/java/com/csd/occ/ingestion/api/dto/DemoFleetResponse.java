package com.csd.occ.ingestion.api.dto;

import com.csd.occ.ingestion.domain.DemoFleetSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Simulation-only identities, immutable account assignments, replay predictions, and current route progress.")
public record DemoFleetResponse(UUID demoRunId, String status, String classification, List<VehicleResponse> items,
                               long totalElements, int page, int size) {
    public static DemoFleetResponse from(DemoFleetSnapshot value) {
        return new DemoFleetResponse(value.demoRunId(), value.status(), "SIMULATION", value.items().stream()
                .map(bus -> new VehicleResponse(bus.busId(), bus.tripId(), bus.registrationNumber(), bus.serviceId(),
                        bus.directionId(), bus.captainUserId().orElse(null), bus.assignmentStatus(),
                        bus.progress().map(progress -> new ProgressResponse(progress.stopCode(), progress.stopSequence(),
                                progress.observedAt(), progress.assumedSpeedKmh(), progress.allowedSpeedReductionsKmh(),
                                progress.allowedDurationsSeconds(), progress.estimatedArrivalAt(), progress.freshUntil(),
                                progress.boardingUntil())).orElse(null), bus.nextStopSequence(), bus.assignmentRevision())).toList(),
                value.totalElements(), value.page(), value.size());
    }
    public record VehicleResponse(String busId, String tripId, String registrationNumber, String serviceId,
                                  int directionId, UUID captainUserId, String assignmentStatus, ProgressResponse progress,
                                  @Schema(nullable = true, description = "Next route stop sequence at or ahead of the simulated bus. Resets when the bus starts another trip and is null when unavailable.")
                                  Integer nextStopSequence,
                                  long assignmentRevision) {}
    @Schema(description = "Captured replay prediction and permitted demo speed advice.")
    public record ProgressResponse(String stopCode, int stopSequence, Instant observedAt, int assumedSpeedKmh,
                                   List<Integer> allowedSpeedReductionsKmh, List<Integer> allowedDurationsSeconds,
                                   @Schema(nullable = true) Instant estimatedArrivalAt,
                                   @Schema(nullable = true) Instant freshUntil,
                                   @Schema(nullable = true, description = "Exclusive end of synthetic boarding, which starts at estimatedArrivalAt")
                                   Instant boardingUntil) {}
}
