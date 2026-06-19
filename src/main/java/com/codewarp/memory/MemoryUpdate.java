package com.codewarp.memory;

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
