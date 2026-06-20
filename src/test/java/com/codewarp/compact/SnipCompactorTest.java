package com.codewarp.compact;

import com.codewarp.core.Message;
import com.codewarp.core.WorkingMemory;
import com.codewarp.memory.TranscriptRecorder;
import com.codewarp.memory.TranscriptRecord;
import com.codewarp.memory.TranscriptStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnipCompactorTest {

    @TempDir
    Path tempDir;

    @Test
    void compactsLongPersistedToolResultAndWritesMetadata() throws Exception {
        TranscriptStore store = new TranscriptStore(tempDir.resolve("memory/L5"));
        store.initialize();
        TranscriptRecorder recorder = new TranscriptRecorder(store, "session-a");
        WorkingMemory memory = new WorkingMemory();
        Message.ToolResult original = new Message.ToolResult("toolu_1", "x".repeat(120), false);
        memory.append(original);
        List<String> uuids = recorder.recordWithUuids(List.of(original));
        memory.markTranscriptUuid(0, uuids.getFirst());

        SnipCompactor.Result result = new SnipCompactor(40, new TokenEstimator(), recorder, store).compact(memory);

        assertEquals(1, result.compactedCount());
        Message.ToolResult compacted = (Message.ToolResult) memory.snapshot().getFirst();
        assertEquals("toolu_1", compacted.toolUseId());
        assertEquals(false, compacted.isError());
        assertTrue(compacted.content().startsWith("[CodeWarp snip compact]"));
        List<TranscriptRecord> records = store.loadRecords("session-a");
        assertEquals(2, records.size());
        assertTrue(records.get(1).isSnipCompact());
        assertEquals(uuids.getFirst(), records.get(1).snipCompact().targetUuid());
    }

    @Test
    void persistsAndCompactsToolResultWithoutTranscriptUuid() throws Exception {
        TranscriptStore store = new TranscriptStore(tempDir.resolve("memory/L5"));
        store.initialize();
        TranscriptRecorder recorder = new TranscriptRecorder(store, "session-a");
        WorkingMemory memory = new WorkingMemory();
        memory.append(new Message.ToolResult("toolu_1", "x".repeat(120), false));

        SnipCompactor.Result result = new SnipCompactor(40, new TokenEstimator(), recorder, store).compact(memory);

        assertEquals(1, result.compactedCount());
        assertTrue(((Message.ToolResult) memory.snapshot().getFirst()).content().startsWith("[CodeWarp snip compact]"));
        List<TranscriptRecord> records = store.loadRecords("session-a");
        assertEquals(2, records.size());
        assertTrue(records.get(0).isMessage());
        assertTrue(records.get(1).isSnipCompact());
        assertEquals(records.get(0).uuid(), records.get(1).snipCompact().targetUuid());
    }
}
