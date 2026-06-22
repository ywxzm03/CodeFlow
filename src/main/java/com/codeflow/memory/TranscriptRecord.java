package com.codeflow.memory;

import com.codeflow.core.Message;

import java.util.List;

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
        CompactBoundary compactBoundary,
        SnipCompact snipCompact
) {
    public boolean isMessage() {
        return message != null;
    }

    public boolean isCompactBoundary() {
        return compactBoundary != null;
    }

    public boolean isSnipCompact() {
        return snipCompact != null;
    }

    /**
     * L4 压缩边界。
     *
     * <p>摘要内容内联在 {@code summary} 中，被保留的热消息以 {@code preservedUuids}
     * 引用 boundary 之前已存在的原文记录。整条 boundary 作为单条原子记录写入，
     * 不再追加独立的 after 消息，从而消除「boundary 在、载荷不在」的中间态。
     */
    public record CompactBoundary(
            String mode,
            String reason,
            long estimatedTokensBefore,
            int hotMessageCount,
            int retryCount,
            String summary,
            List<String> preservedUuids,
            String transcriptPath
    ) {
    }

    /**
     * 工具结果 snip 压缩元数据。
     */
    public record SnipCompact(
            String targetUuid,
            String toolUseId,
            String strategy,
            int thresholdChars,
            int originalChars,
            int summaryChars,
            long tokensFreed,
            String summary
    ) {
    }
}
