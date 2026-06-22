package com.codeflow.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void initializesDefaultMemoryFiles() throws Exception {
        MemoryStore store = new MemoryStore(tempDir.resolve("memory"));

        store.initialize();

        assertTrue(Files.isRegularFile(tempDir.resolve("memory/L0/memory_rules.md")));
        assertTrue(Files.isRegularFile(tempDir.resolve("memory/L1/index.txt")));
        assertTrue(Files.isDirectory(tempDir.resolve("memory/L2")));
        assertTrue(Files.isDirectory(tempDir.resolve("memory/L3")));
        assertTrue(store.readRules().contains("CodeFlow Memory Rules"));
        assertTrue(store.readIndex().contains("CodeFlow Memory Index"));
    }

    @Test
    void appendsMemoryAndIndexEntry() throws Exception {
        MemoryStore store = new MemoryStore(tempDir.resolve("memory"));
        store.initialize();

        store.applyUpdate(new MemoryUpdate(
                MemoryLayer.L2,
                "user_preferences.txt",
                "[2026-06-19] prefers concise explanations",
                "stable user preference",
                "L2/user_preferences.txt: 用户偏好"
        ));

        String memory = store.readMemory("L2/user_preferences.txt");
        String index = store.readIndex();
        assertTrue(memory.contains("prefers concise explanations"));
        assertTrue(index.contains("L2/user_preferences.txt: 用户偏好"));
    }

    @Test
    void rejectsUnsafeReadPaths() throws Exception {
        MemoryStore store = new MemoryStore(tempDir.resolve("memory"));
        store.initialize();

        assertThrows(IllegalArgumentException.class, () -> store.validateReadableMemoryPath("../x.txt"));
        assertThrows(IllegalArgumentException.class, () -> store.validateReadableMemoryPath("/tmp/x.txt"));
        assertThrows(IllegalArgumentException.class, () -> store.validateReadableMemoryPath("L0/memory_rules.md"));
        assertThrows(IllegalArgumentException.class, () -> store.validateReadableMemoryPath("L1/index.txt"));
        assertThrows(IllegalArgumentException.class, () -> store.validateReadableMemoryPath("L2/bad.md"));
        assertThrows(IllegalArgumentException.class, () -> store.validateReadableMemoryPath("L3/bad.txt"));
    }

    @Test
    void rejectsUnsafeWriteTargets() throws Exception {
        MemoryStore store = new MemoryStore(tempDir.resolve("memory"));
        store.initialize();

        assertThrows(IllegalArgumentException.class, () -> store.applyUpdate(new MemoryUpdate(
                MemoryLayer.L2,
                "../bad.txt",
                "content",
                "reason",
                ""
        )));
        assertThrows(IllegalArgumentException.class, () -> store.applyUpdate(new MemoryUpdate(
                MemoryLayer.L3,
                "bad.txt",
                "content",
                "reason",
                ""
        )));
    }
}
