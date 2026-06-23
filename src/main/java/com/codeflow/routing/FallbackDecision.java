package com.codeflow.routing;

public record FallbackDecision(boolean fallbackable, String reason) {
    public static FallbackDecision fallbackable(String reason) {
        return new FallbackDecision(true, reason);
    }

    public static FallbackDecision terminal(String reason) {
        return new FallbackDecision(false, reason);
    }
}
