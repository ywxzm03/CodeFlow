package com.codeflow.compact;

import com.codeflow.core.Message;
import com.codeflow.core.WorkingMemory;
import com.codeflow.llm.LLMClient;
import com.codeflow.memory.TranscriptRecord;
import com.codeflow.memory.TranscriptRecorder;
import com.codeflow.memory.TranscriptStore;
import com.codeflow.tools.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactiveCompactorTest {

    @TempDir
    Path tempDir;

    @Test
    void compactsAggressivelyAfterContextOverflow() throws Exception {
        TranscriptStore store = new TranscriptStore(tempDir.resolve("memory/L5"));
        store.initialize();
        TranscriptRecorder recorder = new TranscriptRecorder(store, "session-a");
        CompactionPolicy policy = new CompactionPolicy(true, 10_000, 8_000, 0.8, 1, 1);
        ReactiveCompactor compactor = new ReactiveCompactor(policy, new TokenEstimator(), new StaticClient("reactive summary"), recorder, store);
        WorkingMemory memory = new WorkingMemory();
        memory.append(new Message.User("old"));
        memory.append(new Message.User("recent"));

        ReactiveCompactor.Result result = compactor.compact("system", memory, List.of(), 1);

        assertTrue(result.compacted());
        assertEquals(2, memory.snapshot().size());
        assertTrue(((Message.User) memory.snapshot().getFirst()).content().contains("reactive summary"));
        assertEquals(new Message.User("recent"), memory.snapshot().get(1));
        List<TranscriptRecord> records = store.loadRecords("session-a");
        assertTrue(records.stream().anyMatch(record ->
                record.isCompactBoundary() && "reactive".equals(record.compactBoundary().mode())
        ));
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
