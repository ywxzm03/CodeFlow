package com.codeflow.routing;

import java.time.Instant;

public record ModelHealth(
        ModelHealthStatus status,
        String reason,
        Instant unhealthyUntil
) {
    public ModelHealth {
        status = status == null ? ModelHealthStatus.UNKNOWN : status;
        reason = reason == null ? "" : reason;
    }

    public static ModelHealth unknown() {
        return new ModelHealth(ModelHealthStatus.UNKNOWN, "", null);
    }

    public static ModelHealth healthy() {
        return new ModelHealth(ModelHealthStatus.HEALTHY, "", null);
    }

    public static ModelHealth unhealthy(String reason, Instant unhealthyUntil) {
        return new ModelHealth(ModelHealthStatus.UNHEALTHY, reason, unhealthyUntil);
    }
}
