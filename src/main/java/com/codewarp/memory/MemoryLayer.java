package com.codewarp.memory;

/**
 * 可写入的长期记忆层。
 */
public enum MemoryLayer {
    L2("L2", ".txt"),
    L3("L3", ".md");

    private final String directoryName;
    private final String fileExtension;

    MemoryLayer(String directoryName, String fileExtension) {
        this.directoryName = directoryName;
        this.fileExtension = fileExtension;
    }

    public String directoryName() {
        return directoryName;
    }

    public String fileExtension() {
        return fileExtension;
    }

    public static MemoryLayer fromDirectoryName(String value) {
        for (MemoryLayer layer : values()) {
            if (layer.directoryName.equals(value)) {
                return layer;
            }
        }
        throw new IllegalArgumentException("记忆层级只能是 L2 或 L3: " + value);
    }
}
