package com.codeflow.memory;

/**
 * 可恢复会话的摘要信息。
 */
public record TranscriptSession(
        String sessionId,
        String lastModified,
        long messageCount
) {
}
