package com.codewarp.compact;

import com.codewarp.core.Message;
import com.codewarp.core.WorkingMemory;
import com.codewarp.llm.LLMClient;
import com.codewarp.memory.TranscriptRecorder;
import com.codewarp.memory.TranscriptRecord;
import com.codewarp.memory.TranscriptStore;
import com.codewarp.tools.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoCompactorTest {

    @TempDir
    Path tempDir;

    @Test
    void skipsWhenBelowThreshold() throws Exception {
        AutoCompactor compactor = compactor(new CompactionPolicy(true, 10_000, 8_000, 0.8, 5, 2), "summary");
        WorkingMemory memory = new WorkingMemory();
        memory.append(new Message.User("hello"));

        AutoCompactor.Result result = compactor.compactIfNeeded("system", memory, List.of(), 0);

        assertFalse(result.compacted());
        assertEquals(List.of(new Message.User("hello")), memory.snapshot());
    }

    @Test
    void compactsWhenThresholdIsReached() throws Exception {
        TranscriptStore store = initializedStore();
        TranscriptRecorder recorder = new TranscriptRecorder(store, "session-a");
        CompactionPolicy policy = new CompactionPolicy(true, 10, 8_000, 0.5, 2, 1);
        AutoCompactor compactor = new AutoCompactor(policy, new TokenEstimator(), new StaticClient("older summary"), recorder, store);
        WorkingMemory memory = new WorkingMemory();
        memory.append(new Message.User("old plain"));
        memory.append(new Message.User("必须保留这个决定"));
        memory.append(new Message.Assistant("recent answer", List.of(), new Message.Usage(100, 20, 0, 0)));
        memory.append(new Message.User("recent user"));

        AutoCompactor.Result result = compactor.compactIfNeeded("system", memory, List.of(), 0);

        assertTrue(result.compacted());
        assertEquals(4, memory.snapshot().size());
        assertTrue(((Message.User) memory.snapshot().getFirst()).content().contains("older summary"));
        assertTrue(memory.snapshot().contains(new Message.User("必须保留这个决定")));
        assertTrue(memory.snapshot().contains(new Message.Assistant("recent answer", List.of(), new Message.Usage(100, 20, 0, 0))));
        assertTrue(memory.snapshot().contains(new Message.User("recent user")));
        List<TranscriptRecord> records = store.loadRecords("session-a");
        assertTrue(records.stream().anyMatch(TranscriptRecord::isCompactBoundary));
    }

    @Test
    void forceCompactIgnoresThreshold() throws Exception {
        TranscriptStore store = initializedStore();
        TranscriptRecorder recorder = new TranscriptRecorder(store, "session-a");
        CompactionPolicy policy = new CompactionPolicy(true, 10_000, 8_000, 0.99, 1, 1);
        AutoCompactor compactor = new AutoCompactor(policy, new TokenEstimator(), new StaticClient("manual summary"), recorder, store);
        WorkingMemory memory = new WorkingMemory();
        memory.append(new Message.User("old"));
        memory.append(new Message.User("recent"));

        AutoCompactor.ForceResult result = compactor.forceCompact("system", memory, List.of());

        assertEquals(AutoCompactor.Status.COMPACTED, result.status());
        assertEquals(2, memory.snapshot().size());
        assertTrue(((Message.User) memory.snapshot().getFirst()).content().contains("manual summary"));
        assertEquals(new Message.User("recent"), memory.snapshot().get(1));
        List<TranscriptRecord> records = store.loadRecords("session-a");
        assertTrue(records.stream().anyMatch(record ->
                record.isCompactBoundary() && "manual_command".equals(record.compactBoundary().reason())
        ));
    }

    @Test
    void forceCompactSkipsEmptyColdMessages() throws Exception {
        AutoCompactor compactor = compactor(new CompactionPolicy(true, 10_000, 8_000, 0.99, 5, 1), "summary");
        WorkingMemory memory = new WorkingMemory();
        memory.append(new Message.User("recent"));

        AutoCompactor.ForceResult result = compactor.forceCompact("system", memory, List.of());

        assertEquals(AutoCompactor.Status.NOT_NEEDED, result.status());
        assertEquals(List.of(new Message.User("recent")), memory.snapshot());
    }

    @Test
    void compactedL4ResumesToSummaryPlusPreserved() throws Exception {
        TranscriptStore store = initializedStore();
        TranscriptRecorder recorder = new TranscriptRecorder(store, "session-a");
        CompactionPolicy policy = new CompactionPolicy(true, 10, 8_000, 0.5, 2, 1);
        AutoCompactor compactor = new AutoCompactor(policy, new TokenEstimator(), new StaticClient("cold summary"), recorder, store);
        WorkingMemory memory = new WorkingMemory();
        memory.append(new Message.User("cold one"));
        memory.append(new Message.User("cold two"));
        memory.append(new Message.Assistant("hot answer", List.of(), new Message.Usage(1, 1, 0, 0)));
        memory.append(new Message.User("hot user"));

        AutoCompactor.Result result = compactor.compactIfNeeded("system", memory, List.of(), 0);
        assertTrue(result.compacted());

        // 压缩后的 in-memory L4 应与 resume 重建出的 L4 完全一致。
        List<Message> resumed = store.loadWorkingMemoryEntriesForResume("session-a").stream()
                .map(WorkingMemory.Entry::message)
                .toList();
        assertEquals(memory.snapshot(), resumed);
        assertEquals(3, resumed.size());
        assertTrue(((Message.User) resumed.getFirst()).content().contains("cold summary"));
        assertEquals(new Message.Assistant("hot answer", List.of(), new Message.Usage(1, 1, 0, 0)), resumed.get(1));
        assertEquals(new Message.User("hot user"), resumed.get(2));
    }

    @Test
    void compactionWritesSingleBoundaryAndNoAfterMessages() throws Exception {
        TranscriptStore store = initializedStore();
        TranscriptRecorder recorder = new TranscriptRecorder(store, "session-a");
        CompactionPolicy policy = new CompactionPolicy(true, 10, 8_000, 0.99, 1, 1);
        AutoCompactor compactor = new AutoCompactor(policy, new TokenEstimator(), new StaticClient("s"), recorder, store);
        WorkingMemory memory = new WorkingMemory();
        memory.append(new Message.User("cold"));
        memory.append(new Message.User("hot"));

        // 用 forceCompact 绕开 token 阈值，只验证落盘形状。
        assertEquals(AutoCompactor.Status.COMPACTED, compactor.forceCompact("system", memory, List.of()).status());

        List<TranscriptRecord> records = store.loadRecords("session-a");
        long boundaries = records.stream().filter(TranscriptRecord::isCompactBoundary).count();
        long messages = records.stream().filter(TranscriptRecord::isMessage).count();
        // 仅 recordUnpersisted 写的 2 条原文 + 1 条 boundary；没有额外的 after 消息记录。
        assertEquals(1, boundaries);
        assertEquals(2, messages);
        assertEquals(3, records.size());
    }

    private AutoCompactor compactor(CompactionPolicy policy, String summary) throws Exception {
        TranscriptStore store = initializedStore();
        return new AutoCompactor(policy, new TokenEstimator(), new StaticClient(summary), new TranscriptRecorder(store, "session-a"), store);
    }

    private TranscriptStore initializedStore() throws Exception {
        TranscriptStore store = new TranscriptStore(tempDir.resolve("memory/L5"));
        store.initialize();
        return store;
    }

    private static final class StaticClient implements LLMClient {
        private final String summary;

        private StaticClient(String summary) {
            this.summary = summary;
        }

        @Override
        public LLMResponse call(String systemPrompt, List<Message> messages, List<Tool> tools) {
            return new LLMResponse(summary, List.of(), null);
        }

        @Override
        public Flux<StreamEvent> callStreaming(String systemPrompt, List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setModel(String model) {
        }
    }
}
