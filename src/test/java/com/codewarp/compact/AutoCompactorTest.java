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
