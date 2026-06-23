package com.codeflow.routing;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record RoutingSnapshot(
        boolean enabled,
        ModelRoute activeModel,
        List<ModelRoute> fallbackCandidates,
        boolean retryCurrentModelOnce,
        Duration unhealthyCooldown,
        Map<String, ModelHealth> health
) {
    public static RoutingSnapshot disabled() {
        return new RoutingSnapshot(false, null, List.of(), false, Duration.ZERO, Map.of());
    }
}
