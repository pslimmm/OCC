package com.csd.occ.planning.advisory.domain;

import java.util.List;

/** These demo hazards provide only area or proximity evidence, which is enough for a caution. */
public final class SimulationCautionPolicy {
    public static final List<String> KINDS = List.of("FLOOD", "RAIL_DISRUPTION");

    private SimulationCautionPolicy() { }

    public static boolean appliesTo(String classification, String kind) {
        return "SIMULATION".equals(classification) && kind != null && KINDS.contains(kind);
    }
}
