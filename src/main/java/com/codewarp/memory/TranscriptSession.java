package com.codewarp.memory;

public record TranscriptSession(
        String sessionId,
        String lastModified,
        long messageCount
) {
}
