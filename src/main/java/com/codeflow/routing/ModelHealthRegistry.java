package com.codeflow.routing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ModelHealthRegistry {
    private final Map<String, ModelHealth> healthByKey = new LinkedHashMap<>();
    private final Duration unhealthyCooldown;
    private final Clock clock;

    public ModelHealthRegistry(Duration unhealthyCooldown) {
        this(unhealthyCooldown, Clock.systemUTC());
    }

    public ModelHealthRegistry(Duration unhealthyCooldown, Clock clock) {
        if (unhealthyCooldown == null || unhealthyCooldown.isZero() || unhealthyCooldown.isNegative()) {
            throw new IllegalArgumentException("Unhealthy cooldown must be positive");
        }
        this.unhealthyCooldown = unhealthyCooldown;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public synchronized ModelHealth health(String key) {
        ModelHealth health = healthByKey.get(key);
        if (health == null) {
            return ModelHealth.unknown();
        }
        if (health.status() == ModelHealthStatus.UNHEALTHY && cooldownExpired(health)) {
            ModelHealth unknown = ModelHealth.unknown();
            healthByKey.put(key, unknown);
            return unknown;
        }
        return health;
    }

    public synchronized Map<String, ModelHealth> snapshot() {
        Map<String, ModelHealth> snapshot = new LinkedHashMap<>();
        for (String key : healthByKey.keySet()) {
            snapshot.put(key, health(key));
        }
        return snapshot;
    }

    public synchronized void markHealthy(String key) {
        healthByKey.put(key, ModelHealth.healthy());
    }

    public synchronized void markUnhealthy(String key, String reason) {
        healthByKey.put(key, ModelHealth.unhealthy(reason, clock.instant().plus(unhealthyCooldown)));
    }

    public boolean isAvailableCandidate(String key) {
        return health(key).status() != ModelHealthStatus.UNHEALTHY;
    }

    private boolean cooldownExpired(ModelHealth health) {
        return health.unhealthyUntil() != null && !health.unhealthyUntil().isAfter(clock.instant());
    }
}
