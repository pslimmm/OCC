package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.SimulationBunchingGroupResponse;
import com.csd.occ.ingestion.domain.DemoRunService;
import com.csd.occ.ingestion.domain.CurrentSimulationBunchingGroupService;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.Instant;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/ingestion/demo-runs/{runId}/bunching-groups")
public class SimulationBunchingGroupController {
    private final DemoRunService runs;
    private final CurrentSimulationBunchingGroupService current;

    public SimulationBunchingGroupController(DemoRunService runs, CurrentSimulationBunchingGroupService current) {
        this.runs = runs; this.current = current;
    }

    @GetMapping("/{eventId}")
    @Operation(operationId = "getSimulationBunchingGroup", summary = "Get confirmed simulation bus identities in arrival order")
    public SimulationBunchingGroupResponse group(@PathVariable UUID runId, @PathVariable UUID eventId) {
        var alert = runs.bunchingGroup(runId, eventId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation bunching group not found"));
        var affected = alert.affectedVehicles().stream().map(value -> value.busId()).toList();
        var following = alert.followingVehicle() == null ? null : alert.followingVehicle().busId();
        return new SimulationBunchingGroupResponse(runId, eventId, alert.serviceId(), alert.directionId(),
                alert.stopCode(), affected, following);
    }

    @GetMapping("/{eventId}/current")
    @Operation(operationId = "getCurrentSimulationBunchingGroup",
            summary = "Resolve the latest simulation bunching observations to bus identities",
            description = "Requires the planning peer capability. Returns 409 when current detector evidence is stale, "
                    + "not the newest replay snapshot, insufficient or cannot be resolved uniquely. "
                    + "A downstream control prediction may accompany the original detection stop.")
    @ApiResponse(responseCode = "200", description = "Current affected buses in predicted arrival order")
    @ApiResponse(responseCode = "409", description = "Current bunching group cannot be established")
    public CurrentGroupResponse current(@PathVariable UUID runId, @PathVariable UUID eventId) {
        var group = current.find(runId, eventId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "Current simulation bunching group unavailable"));
        return response(group);
    }

    @PostMapping("/{eventId}/current")
    @Operation(operationId = "refreshCurrentSimulationBunchingGroup",
            summary = "Review current evidence or request new arrivals for an expired simulation snapshot",
            description = "Requires the planning peer capability. Repeated requests reuse the same pending snapshot until "
                    + "Disruption evaluates it. A verified downstream control prediction may accompany the original detection stop.",
            security = @SecurityRequirement(name = "OCCPeerCapability"))
    @ApiResponse(responseCode = "200", description = "Current affected buses in predicted arrival order")
    @ApiResponse(responseCode = "202", description = "New simulation predictions are awaiting detector evaluation", content = @Content)
    @ApiResponse(responseCode = "401", description = "Missing or invalid Planning peer token", content = @Content)
    @ApiResponse(responseCode = "409", description = "Current evidence is unavailable and the simulation cannot regenerate it",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<CurrentGroupResponse> refresh(@PathVariable UUID runId, @PathVariable UUID eventId) {
        var group = current.find(runId, eventId);
        if (group.isPresent()) return ResponseEntity.ok(response(group.orElseThrow()));
        if (current.requestFreshPredictions(runId, eventId)) return ResponseEntity.accepted().build();
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Current simulation predictions cannot be refreshed");
    }

    private static CurrentGroupResponse response(CurrentSimulationBunchingGroupService.Group group) {
        var prediction = group.controlPrediction();
        return new CurrentGroupResponse(group.demoRunId(), group.bunchingEventId(), group.serviceId(), group.stopCode(),
                group.directionId(), group.rawObservationId(), group.fetchedAt(), group.validUntil(), group.affectedBusReferences(),
                group.adjacentGapSeconds(), group.consecutivePairs(), prediction == null ? null : new ControlPredictionResponse(
                        prediction.snapshotId(), prediction.stopCode(), prediction.stopSequence(), prediction.computedAt(),
                        prediction.arrivals().stream().map(arrival -> new ControlArrivalResponse(arrival.busReference(),
                                arrival.arrivalAt())).toList()));
    }

    public record CurrentGroupResponse(UUID demoRunId, UUID bunchingEventId, String serviceId,
            @Schema(description = "Original detection stop code") String stopCode,
            int directionId, UUID rawObservationId, Instant fetchedAt, Instant validUntil, List<String> affectedBusReferences,
            @Schema(description = "Prediction gaps in seconds between successive affected buses, in the same order; other non-affected buses may lie between them")
            List<Long> adjacentGapSeconds,
            @Schema(description = "Whether each gap connects consecutive buses in the full simulation snapshot")
            List<Boolean> consecutivePairs,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Current route-motion forecast for the trailing bus's verified next stop after the group clears the detection stop")
            ControlPredictionResponse controlPrediction) {}

    public record ControlPredictionResponse(
            @Schema(description = "Stable identity for the source evidence, participating buses and current trip's control stop occurrence")
            UUID snapshotId, String stopCode, int stopSequence, Instant computedAt, List<ControlArrivalResponse> arrivals) {}

    public record ControlArrivalResponse(String busReference,
            @Schema(description = "Modeled arrival on the current trip. This can be in the past for buses that have already reached this stop.")
            Instant arrivalAt) {}
}
