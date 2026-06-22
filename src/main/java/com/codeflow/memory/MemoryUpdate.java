package com.codeflow.memory;

/**
 * 待确认的 L2/L3 记忆写入。
 */
public record MemoryUpdate(
        MemoryLayer layer,
        String fileName,
        String content,
        String reason,
        String indexEntry
) {
    public String relativePath() {
        return layer.directoryName() + "/" + fileName;
    }
}
