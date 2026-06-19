package com.codewarp.memory;

public record TranscriptSearchResult(
        String sessionId,
        String uuid,
        String timestamp,
        String role,
        String content
) {
}
