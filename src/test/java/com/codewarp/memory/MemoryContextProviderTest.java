package com.codewarp.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryContextProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void injectsOnlyRulesAndIndex() throws Exception {
        Path memoryRoot = tempDir.resolve("memory");
        MemoryStore store = new MemoryStore(memoryRoot);
        store.initialize();
        Files.writeString(memoryRoot.resolve("L2/user_preferences.txt"), "hidden preference body");

        String prompt = new MemoryContextProvider(store).buildSystemPrompt("base prompt");

        assertTrue(prompt.contains("base prompt"));
        assertTrue(prompt.contains("L0 memory rules"));
        assertTrue(prompt.contains("L1 memory index"));
        assertFalse(prompt.contains("hidden preference body"));
    }

    @Test
    void injectsTranscriptSessionWhenAvailable() throws Exception {
        MemoryStore store = new MemoryStore(tempDir.resolve("memory"));
        store.initialize();

        String prompt = new MemoryContextProvider(store, () -> "session-a").buildSystemPrompt("base prompt");

        assertTrue(prompt.contains("Current transcript session id: session-a"));
        assertTrue(prompt.contains("TranscriptSearch"));
    }

    @Test
    void fallsBackWhenRulesOrIndexCannotBeRead() {
        MemoryStore store = new MemoryStore(tempDir.resolve("missing"));

        String prompt = new MemoryContextProvider(store).buildSystemPrompt("base prompt");

        assertEquals("base prompt", prompt);
    }
}
