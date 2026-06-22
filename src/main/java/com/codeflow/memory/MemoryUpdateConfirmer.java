package com.codeflow.memory;

/**
 * 记忆写入确认器。
 */
@FunctionalInterface
public interface MemoryUpdateConfirmer {
    boolean confirm(MemoryUpdate update);

    static MemoryUpdateConfirmer denyByDefault() {
        return update -> false;
    }
}
