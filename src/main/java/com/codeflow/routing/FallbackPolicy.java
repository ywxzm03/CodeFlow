package com.codeflow.routing;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.NoCredentialsException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

public final class FallbackPolicy {
    private final boolean retryCurrentModelOnce;
    private final Duration unhealthyCooldown;

    public FallbackPolicy(boolean retryCurrentModelOnce, Duration unhealthyCooldown) {
        if (unhealthyCooldown == null || unhealthyCooldown.isZero() || unhealthyCooldown.isNegative()) {
            throw new IllegalArgumentException("Unhealthy cooldown must be positive");
        }
        this.retryCurrentModelOnce = retryCurrentModelOnce;
        this.unhealthyCooldown = unhealthyCooldown;
    }

    public boolean retryCurrentModelOnce() {
        return retryCurrentModelOnce;
    }

    public Duration unhealthyCooldown() {
        return unhealthyCooldown;
    }

    public FallbackDecision classify(Throwable error) {
        if (error == null) {
            return FallbackDecision.terminal("unknown error");
        }
        if (error instanceof NoCredentialsException) {
            return FallbackDecision.terminal("missing api key");
        }
        if (contains(error, UnknownHostException.class)) {
            return FallbackDecision.terminal("base_url host cannot be resolved");
        }
        if (contains(error, IllegalArgumentException.class)) {
            return FallbackDecision.terminal("invalid request configuration");
        }
        if (error instanceof AnthropicIoException) {
            return FallbackDecision.fallbackable("anthropic io error");
        }
        if (contains(error, SocketTimeoutException.class)
                || contains(error, ConnectException.class)
                || contains(error, NoRouteToHostException.class)
                || contains(error, TimeoutException.class)) {
            return FallbackDecision.fallbackable("network or timeout error");
        }
        if (error instanceof AnthropicServiceException serviceException) {
            return classifyStatusCode(serviceException.statusCode());
        }
        return FallbackDecision.terminal("non fallbackable error");
    }

    private FallbackDecision classifyStatusCode(int statusCode) {
        if (statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode >= 500) {
            return FallbackDecision.fallbackable("HTTP " + statusCode);
        }
        if (statusCode == 401 || statusCode == 403) {
            return FallbackDecision.terminal("HTTP " + statusCode);
        }
        if (statusCode >= 400 && statusCode < 500) {
            return FallbackDecision.terminal("HTTP " + statusCode);
        }
        return FallbackDecision.terminal("HTTP " + statusCode);
    }

    private boolean contains(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
