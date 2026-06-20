package com.codewarp.memory;

import com.codewarp.core.Message;

/**
 * L5 jsonl 中的一条通用记录。
 */
public record TranscriptRecord(
        String uuid,
        String parentUuid,
        String sessionId,
        String timestamp,
        String cwd,
        String type,
        Message message,
        CompactBoundary compactBoundary
) {
    public boolean isMessage() {
        return message != null;
    }

    public boolean isCompactBoundary() {
        return compactBoundary != null;
    }

    /**
     * L4 压缩边界。
     */
    public record CompactBoundary(
            String mode,
            String reason,
            long estimatedTokensBefore,
            int hotMessageCount,
            int retryCount,
            String summaryMessageUuid,
            String transcriptPath
    ) {
    }
}
