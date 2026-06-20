package com.codewarp.memory;

import com.codewarp.core.Message;

/**
 * L5 jsonl 中的一条消息记录。
 */
public record TranscriptEntry(
        String uuid,
        String parentUuid,
        String sessionId,
        String timestamp,
        String cwd,
        Message message
) {
}
