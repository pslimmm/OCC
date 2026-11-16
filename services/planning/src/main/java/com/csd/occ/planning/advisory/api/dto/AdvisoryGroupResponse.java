package com.csd.occ.planning.advisory.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.csd.occ.planning.advisory.domain.AdvisoryCandidate;
import com.csd.occ.planning.advisory.domain.AdvisoryGroup;
import com.csd.occ.planning.advisory.domain.InstructionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AdvisoryGroupResponse(
        UUID id,
        UUID disruptionId,
        UUID demoRunId,
        String serviceId,
        int directionId,
        String hazardTitle,
        String hazardDescription,
        String hazardKind,
        String hazardClassification,
        Double latitude,
        Double longitude,
        String hazardLocation,
        @Schema(description = "Name of the advisory's targeted stop, or an associated stop for route-level advice.")
        String affectedStopName,
        @Schema(description = "Code of the advisory's targeted stop, or an associated stop for route-level advice.")
        String affectedStopCode,
        @Schema(description = "Shared draft. Omitted when directives differ between recipients.")
        @JsonInclude(JsonInclude.Include.NON_NULL) AdvisoryCandidate candidate,
        @Schema(description = "Shared message. Omitted when directives differ between recipients.")
        @JsonInclude(JsonInclude.Include.NON_NULL) String preview,
        List<AdvisoryResponse> members,
        List<MovementConflictResponse> movementConflicts) {

    public static AdvisoryGroupResponse from(AdvisoryGroup group) {
        var first = group.members().getFirst();
        var facts = first.context().facts();
        String description = facts != null && facts.description() != null ? facts.description()
                : facts != null ? facts.title() : "";
        String kind = facts != null && facts.kind() != null ? facts.kind() : "";
        Double latitude = null;
        Double longitude = null;
        String hazardLocation = null;
        if (facts != null && facts.locations() != null) {
            var loc = facts.locations().stream()
                    .filter(l -> l.latitude() != null && l.longitude() != null)
                    .findFirst();
            if (loc.isPresent()) {
                latitude = loc.get().latitude().doubleValue();
                longitude = loc.get().longitude().doubleValue();
                hazardLocation = loc.get().description();
            }
        }
        boolean sharedDirective = group.members().stream().allMatch(member -> member.candidate().type() == first.candidate().type()
                && Objects.equals(member.candidate().parameters(), first.candidate().parameters())
                && Objects.equals(member.preview(), first.preview()));
        var shared = sharedDirective ? first.candidate() : null;
        var target = shared != null && (shared.type() == InstructionType.BE_CAUTIOUS
                || shared.type() == InstructionType.SLOW_DOWN_NEAR_STOPS) ? shared.parameters() : null;
        var exposures = first.context().exposures();
        var firstExposure = exposures == null ? null : exposures.stream()
                .filter(exposure -> group.key().serviceId().equals(exposure.serviceId())
                        && group.key().directionId() == exposure.directionId())
                .findFirst().orElse(null);
        String affectedStopName = target != null ? target.stopName() : firstExposure != null ? firstExposure.stopName() : null;
        String affectedStopCode = target != null ? target.stopCode() : firstExposure != null ? firstExposure.stopCode() : null;

        return new AdvisoryGroupResponse(
                first.id(),
                group.key().disruptionId(),
                group.key().demoRunId(),
                group.key().serviceId(),
                group.key().directionId(),
                facts != null ? facts.title() : "",
                description,
                kind,
                first.context().classification(),
                latitude,
                longitude,
                hazardLocation,
                affectedStopName,
                affectedStopCode,
                shared,
                sharedDirective ? first.preview() : null,
                group.members().stream().map(AdvisoryResponse::from).toList(),
                group.movementConflicts().stream().map(conflict -> new MovementConflictResponse(
                        conflict.proposalId(), conflict.busId(), conflict.instructionId(), conflict.message())).toList());
    }

    public record MovementConflictResponse(UUID proposalId, String busId, UUID instructionId, String message) {}

    @Schema(name = "AdvisoryGroupPageResponse")
    public record PageResponse(List<AdvisoryGroupResponse> items, int page, int size, long totalItems, int totalPages) {}
}
