package com.codewarp.memory;

import com.codewarp.core.Message;
import com.codewarp.llm.LLMClient;
import com.codewarp.tools.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryReflectionTest {

    @TempDir
    Path tempDir;

    @Test
    void writesConfirmedMemoryUpdate() throws Exception {
        MemoryStore store = initializedStore();
        MemoryReflection reflection = new MemoryReflection(reflectionClient("""
                {
                  "memories": [
                    {
                      "layer": "L2",
                      "file": "user_preferences.txt",
                      "content": "[2026-06-19] 用户喜欢先解释概念",
                      "reason": "长期偏好",
                      "index_entry": "L2/user_preferences.txt: 用户偏好"
                    }
                  ]
                }
                """), store);
        reflection.setConfirmer(update -> true);

        reflection.reflect(List.of(new Message.User("先解释概念")));

        assertTrue(store.readMemory("L2/user_preferences.txt").contains("先解释概念"));
        assertTrue(store.readIndex().contains("L2/user_preferences.txt: 用户偏好"));
    }

    @Test
    void doesNotWriteRejectedMemoryUpdate() throws Exception {
        MemoryStore store = initializedStore();
        AtomicBoolean asked = new AtomicBoolean(false);
        MemoryReflection reflection = new MemoryReflection(reflectionClient("""
                {
                  "memories": [
                    {
                      "layer": "L3",
                      "file": "pitfall.md",
                      "content": "## pitfall",
                      "reason": "reusable pitfall",
                      "index_entry": "L3/pitfall.md: pitfall"
                    }
                  ]
                }
                """), store);
        reflection.setConfirmer(update -> {
            asked.set(true);
            return false;
        });

        reflection.reflect(List.of(new Message.User("task")));

        assertTrue(asked.get());
        assertFalse(store.readIndex().contains("L3/pitfall.md"));
    }

    @Test
    void doesNotAskWhenNoMemoryCandidates() throws Exception {
        MemoryStore store = initializedStore();
        AtomicBoolean asked = new AtomicBoolean(false);
        MemoryReflection reflection = new MemoryReflection(reflectionClient("""
                {"memories": []}
                """), store);
        reflection.setConfirmer(update -> {
            asked.set(true);
            return true;
        });

        reflection.reflect(List.of(new Message.User("task")));

        assertFalse(asked.get());
    }

    private MemoryStore initializedStore() throws Exception {
        MemoryStore store = new MemoryStore(tempDir.resolve("memory"));
        store.initialize();
        return store;
    }

    private LLMClient reflectionClient(String response) {
        return new LLMClient() {
            @Override
            public LLMResponse call(String systemPrompt, List<Message> messages, List<Tool> tools) {
                return new LLMResponse(response, List.of(), null);
            }

            @Override
            public Flux<StreamEvent> callStreaming(String systemPrompt, List<Message> messages, List<Tool> tools) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void setModel(String model) {
            }
        };
    }
}
