package com.csd.occ.planning.advisory.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DecideAdvisoryGroupRequest(@NotEmpty List<@NotNull UUID> proposalIds,
        @io.swagger.v3.oas.annotations.media.Schema(description = "Optional map from reviewed proposal ID to the active movement instruction ID explicitly approved for replacement")
        Map<UUID, @NotNull UUID> replacements) {
    public DecideAdvisoryGroupRequest {
        if (replacements == null) replacements = Map.of();
    }
}
