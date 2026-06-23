package com.codeflow.memory;

import com.codeflow.core.Message;
import com.codeflow.core.WorkingMemory;
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
                new Message.Assistant("done", List.of(new Message.ToolUse("toolu_1", "Read", "{}")), null),
                new Message.ToolResult("toolu_1", "content", false)
        ));

        assertEquals(
                List.of(
                        new Message.User("hello"),
                        new Message.Assistant("done", List.of(new Message.ToolUse("toolu_1", "Read", "{}")), null),
                        new Message.ToolResult("toolu_1", "content", false)
                ),
                store.loadMessages("session-a")
        );
        String transcript = Files.readString(tempDir.resolve("memory/L5/session-a.jsonl"));
        assertTrue(transcript.contains("\"parentUuid\""));
        assertTrue(transcript.contains("\"type\":\"user\""));
        assertTrue(transcript.contains("\"message\":{\"role\":\"user\",\"content\":\"hello\"}"));
        assertTrue(transcript.contains("\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\""));
        assertFalse(transcript.contains("\"type\":\"tool_result\",\"message\""));
        assertFalse(transcript.contains("\"message\":{\"type\":\"user\""));
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
                {"uuid":"root","parentUuid":null,"sessionId":"session-a","timestamp":"2026-06-20T00:00:00Z","cwd":"/tmp","type":"user","message":{"role":"user","content":"root"}}
                {"uuid":"main","parentUuid":"root","sessionId":"session-a","timestamp":"2026-06-20T00:00:01Z","cwd":"/tmp","type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"main"}]}}
                {"uuid":"branch","parentUuid":"root","sessionId":"session-a","timestamp":"2026-06-20T00:00:02Z","cwd":"/tmp","type":"user","message":{"role":"user","content":"branch"}}
                """);

        assertEquals(
                List.of(new Message.User("root"), new Message.User("branch")),
                resumeMessages(store, "session-a")
        );
    }

    @Test
    void loadMessagesForResumeRebuildsFromInlineBoundary() throws Exception {
        TranscriptStore store = initializedStore();
        List<String> uuids = store.append("session-a", List.of(
                new Message.User("old"),
                new Message.Assistant("old answer", List.of(), null)
        ));
        // 新格式：摘要内联进 boundary，preserved 用已存在的 uuid 引用，不再单独写 after 消息。
        store.appendCompactBoundary("session-a", new TranscriptRecord.CompactBoundary(
                "auto",
                "token_threshold",
                160000,
                5,
                0,
                "INLINE SUMMARY",
                List.of(uuids.get(1)),
                tempDir.resolve("memory/L5/session-a.jsonl").toString()
        ));
        // 压缩之后继续对话产生的新消息。
        store.append("session-a", List.of(new Message.User("hot")));

        assertEquals(
                List.of(
                        new Message.User("INLINE SUMMARY"),
                        new Message.Assistant("old answer", List.of(), null),
                        new Message.User("hot")
                ),
                resumeMessages(store, "session-a")
        );
    }

    @Test
    void compactBoundaryDoesNotEnterMessageEntries() throws Exception {
        TranscriptStore store = initializedStore();
        List<String> uuids = store.append("session-a", List.of(new Message.User("old")));
        store.appendCompactBoundary("session-a", new TranscriptRecord.CompactBoundary(
                "reactive",
                "context_overflow_error",
                180000,
                2,
                1,
                "REACTIVE SUMMARY",
                List.of(uuids.getFirst()),
                tempDir.resolve("memory/L5/session-a.jsonl").toString()
        ));

        assertEquals(List.of(new Message.User("old")), store.loadMessages("session-a"));
        List<TranscriptRecord> records = store.loadRecords("session-a");
        assertEquals(2, records.size());
        assertTrue(records.get(1).isCompactBoundary());
        assertEquals("reactive", records.get(1).compactBoundary().mode());
    }

    @Test
    void loadMessagesForResumeAppliesSnipMetadata() throws Exception {
        TranscriptStore store = initializedStore();
        List<String> uuids = store.append("session-a", List.of(
                new Message.ToolResult("toolu_1", "full content", false)
        ));
        store.appendSnipCompact("session-a", new TranscriptRecord.SnipCompact(
                uuids.getFirst(),
                "toolu_1",
                "tool_result_summary",
                8,
                12,
                7,
                2,
                "summary"
        ));

        assertEquals(
                List.of(new Message.ToolResult("toolu_1", "summary", false)),
                resumeMessages(store, "session-a")
        );
        assertEquals(
                List.of(new Message.ToolResult("toolu_1", "full content", false)),
                store.loadMessages("session-a")
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
    void hookSnapshotsDoNotEnterSessionList() throws Exception {
        TranscriptStore store = initializedStore();
        Path snapshot = store.writeHookSnapshot("session-a", "Stop", List.of(
                new Message.User("finish"),
                new Message.Assistant("done", List.of(), null)
        ));

        assertTrue(Files.exists(snapshot));
        assertTrue(snapshot.toString().contains(".hooks"));
        assertTrue(Files.readString(snapshot).contains("\"text\":\"done\""));
        assertTrue(store.listSessions().isEmpty());
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

    @Test
    void resumeFallsBackToOriginalWhenBoundaryLineIsTorn() throws Exception {
        TranscriptStore store = initializedStore();
        store.append("session-a", List.of(new Message.User("a"), new Message.User("b")));
        // 模拟 boundary 写到一半被撕裂：半截坏 JSON。
        Files.writeString(
                tempDir.resolve("memory/L5/session-a.jsonl"),
                "{\"uuid\":\"x\",\"type\":\"compact_boundary\",\"compact\":{\"mode\":\"auto\"",
                java.nio.file.StandardOpenOption.APPEND
        );

        // 坏行被跳过 → 无有效 boundary → 回退到完整原文。
        assertEquals(
                List.of(new Message.User("a"), new Message.User("b")),
                resumeMessages(store, "session-a")
        );
    }

    @Test
    void resumeAppliesSnipToPreservedReference() throws Exception {
        TranscriptStore store = initializedStore();
        List<String> uuids = store.append("session-a", List.of(
                new Message.ToolResult("toolu_1", "full preserved content", false)
        ));
        String preservedUuid = uuids.getFirst();
        // snip 元数据落在 boundary 之前（preserved 是更早的原文）。
        store.appendSnipCompact("session-a", new TranscriptRecord.SnipCompact(
                preservedUuid, "toolu_1", "tool_result_summary", 8, 22, 7, 2, "snipped"
        ));
        store.appendCompactBoundary("session-a", new TranscriptRecord.CompactBoundary(
                "auto", "token_threshold", 160000, 5, 0,
                "SUMMARY", List.of(preservedUuid),
                tempDir.resolve("memory/L5/session-a.jsonl").toString()
        ));

        assertEquals(
                List.of(
                        new Message.User("SUMMARY"),
                        new Message.ToolResult("toolu_1", "snipped", false)
                ),
                resumeMessages(store, "session-a")
        );
    }

    @Test
    void resumeSkipsPreservedWhoseOriginalLineIsTorn() throws Exception {
        // 已知局限存档：被引用的 preserved 原文行若早先被撕裂，该条 resume 缺失，
        // 但摘要与其余 preserved 不受影响。
        TranscriptStore store = initializedStore();
        Files.writeString(tempDir.resolve("memory/L5/session-a.jsonl"), """
                {"uuid":"p1","parentUuid":null,"sessionId":"session-a","timestamp":"t","cwd":"/tmp","type":"user","message":{"role":"user","content":"kept"}}
                {"uuid":"p2","parentUuid":"p1","sessionId":"session-a","timestamp":"t","cwd":"/tmp","type":"user","message":{"role":"user","content":"tor
                {"uuid":"b","parentUuid":"p2","sessionId":"session-a","timestamp":"t","cwd":"/tmp","type":"compact_boundary","compact":{"mode":"auto","reason":"token_threshold","estimated_tokens_before":1,"hot_message_count":2,"retry_count":0,"summary":"S","preserved_uuids":["p1","p2"],"transcript_path":"/tmp/x"}}
                """);

        assertEquals(
                List.of(new Message.User("S"), new Message.User("kept")),
                resumeMessages(store, "session-a")
        );
    }

    private TranscriptStore initializedStore() throws Exception {
        TranscriptStore store = new TranscriptStore(tempDir.resolve("memory/L5"));
        store.initialize();
        return store;
    }

    private List<Message> resumeMessages(TranscriptStore store, String sessionId) throws Exception {
        return store.loadWorkingMemoryEntriesForResume(sessionId).stream()
                .map(WorkingMemory.Entry::message)
                .toList();
    }
}
