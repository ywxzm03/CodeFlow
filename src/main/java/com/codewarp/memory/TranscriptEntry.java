package com.codewarp.memory;

import com.codewarp.core.Message;

public record TranscriptEntry(
        String uuid,
        String parentUuid,
        String sessionId,
        String timestamp,
        String cwd,
        Message message
) {
}
