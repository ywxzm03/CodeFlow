package com.codewarp.memory;

@FunctionalInterface
public interface MemoryUpdateConfirmer {
    boolean confirm(MemoryUpdate update);

    static MemoryUpdateConfirmer denyByDefault() {
        return update -> false;
    }
}
