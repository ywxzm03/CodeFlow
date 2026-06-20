package com.codewarp.compact;

/**
 * 压缩策略配置。
 */
public record CompactionPolicy(
        boolean enabled,
        long contextWindowTokens,
        int snipToolResultThresholdChars,
        double autoCompactThresholdRatio,
        int autoCompactHotMessages,
        int reactiveCompactHotMessages
) {
    public static CompactionPolicy defaults() {
        return new CompactionPolicy(true, 200_000, 8_000, 0.8, 5, 2);
    }

    public long autoCompactThresholdTokens() {
        return Math.round(contextWindowTokens * autoCompactThresholdRatio);
    }
}
