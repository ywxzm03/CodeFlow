package com.codewarp.core;

import com.codewarp.llm.LLMClient;
import com.codewarp.memory.MemoryContextProvider;
import com.codewarp.memory.MemoryStore;
import com.codewarp.permissions.ToolPermissionManager;
import com.codewarp.tools.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryEngineMemoryContextTest {

    @TempDir
    Path tempDir;

    @Test
    void injectsMemoryContextIntoStreamingCall() throws Exception {
        MemoryStore store = new MemoryStore(tempDir.resolve("memory"));
        store.initialize();
        AtomicReference<String> systemPrompt = new AtomicReference<>();
        LLMClient llmClient = streamingClient(systemPrompt);
        QueryEngine queryEngine = new QueryEngine(
                llmClient,
                List.of(),
                1,
                ToolPermissionManager.askByDefault(),
                new MemoryContextProvider(store),
                null
        );

        QueryEngine.QueryResult result = queryEngine.query("hello", new WorkingMemory());

        assertEquals(QueryEngine.QueryResult.StopReason.COMPLETED, result.stopReason());
        assertTrue(systemPrompt.get().contains("### Memory"));
        assertTrue(systemPrompt.get().contains("L0 memory rules"));
        assertTrue(systemPrompt.get().contains("L1 memory index"));
    }

    private LLMClient streamingClient(AtomicReference<String> systemPrompt) {
        return new LLMClient() {
            @Override
            public LLMResponse call(String systemPrompt, List<Message> messages, List<Tool> tools) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<StreamEvent> callStreaming(String prompt, List<Message> messages, List<Tool> tools) {
                systemPrompt.set(prompt);
                return Flux.just(new StreamEvent.TextDelta("done"));
            }

            @Override
            public void setModel(String model) {
            }
        };
    }
}
