package com.codewarp.memory;

import com.codewarp.core.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void initializesL5Directory() throws Exception {
        TranscriptStore store = new TranscriptStore(tempDir.resolve("memory/L5"));

        store.initialize();

        assertTrue(Files.isDirectory(tempDir.resolve("memory/L5")));
    }

    @Test
    void appendsAndLoadsMessages() throws Exception {
        TranscriptStore store = initializedStore();

        store.append("session-a", List.of(
                new Message.User("hello"),
                new Message.Assistant("done", List.of(new Message.ToolUse("toolu_1", "Read", "{}"))),
                new Message.ToolResult("toolu_1", "content", false)
        ));

        assertEquals(
                List.of(
                        new Message.User("hello"),
                        new Message.Assistant("done", List.of(new Message.ToolUse("toolu_1", "Read", "{}"))),
                        new Message.ToolResult("toolu_1", "content", false)
                ),
                store.loadMessages("session-a")
        );
        assertTrue(Files.readString(tempDir.resolve("memory/L5/session-a.jsonl")).contains("\"parentUuid\""));
    }

    @Test
    void linksNewAppendsToPreviousEntry() throws Exception {
        TranscriptStore store = initializedStore();

        store.append("session-a", List.of(new Message.User("first")));
        store.append("session-a", List.of(new Message.User("second")));

        List<TranscriptEntry> entries = store.loadEntries("session-a");
        assertEquals(entries.get(0).uuid(), entries.get(1).parentUuid());
    }

    @Test
    void loadMessagesForResumeFollowsLastParentChain() throws Exception {
        TranscriptStore store = initializedStore();
        Files.writeString(tempDir.resolve("memory/L5/session-a.jsonl"), """
                {"uuid":"root","parentUuid":null,"sessionId":"session-a","timestamp":"2026-06-20T00:00:00Z","cwd":"/tmp","message":{"type":"user","content":"root"}}
                {"uuid":"main","parentUuid":"root","sessionId":"session-a","timestamp":"2026-06-20T00:00:01Z","cwd":"/tmp","message":{"type":"assistant","content":"main","toolUses":[]}}
                {"uuid":"branch","parentUuid":"root","sessionId":"session-a","timestamp":"2026-06-20T00:00:02Z","cwd":"/tmp","message":{"type":"user","content":"branch"}}
                """);

        assertEquals(
                List.of(new Message.User("root"), new Message.User("branch")),
                store.loadMessagesForResume("session-a")
        );
    }

    @Test
    void listsSessions() throws Exception {
        TranscriptStore store = initializedStore();
        store.append("session-a", List.of(new Message.User("hello")));

        List<TranscriptSession> sessions = store.listSessions();

        assertEquals(1, sessions.size());
        assertEquals("session-a", sessions.getFirst().sessionId());
        assertEquals(1, sessions.getFirst().messageCount());
    }

    @Test
    void rejectsUnsafeSessionIds() throws Exception {
        TranscriptStore store = initializedStore();

        assertThrows(IllegalArgumentException.class, () -> store.append("../bad", List.of(new Message.User("x"))));
        assertThrows(IllegalArgumentException.class, () -> store.loadMessages("/tmp/bad"));
        assertThrows(IllegalArgumentException.class, () -> store.transcriptPath(".."));
    }

    @Test
    void resolvesTranscriptPathUnderL5Root() throws Exception {
        TranscriptStore store = initializedStore();

        assertEquals(tempDir.resolve("memory/L5/session-a.jsonl"), store.transcriptPath("session-a"));
    }

    @Test
    void skipsDamagedJsonlLines() throws Exception {
        TranscriptStore store = initializedStore();
        store.append("session-a", List.of(new Message.User("hello")));
        Files.writeString(
                tempDir.resolve("memory/L5/session-a.jsonl"),
                "{bad json" + System.lineSeparator(),
                java.nio.file.StandardOpenOption.APPEND
        );

        List<Message> messages = store.loadMessages("session-a");

        assertFalse(messages.isEmpty());
        assertEquals(new Message.User("hello"), messages.getFirst());
    }

    private TranscriptStore initializedStore() throws Exception {
        TranscriptStore store = new TranscriptStore(tempDir.resolve("memory/L5"));
        store.initialize();
        return store;
    }
}
