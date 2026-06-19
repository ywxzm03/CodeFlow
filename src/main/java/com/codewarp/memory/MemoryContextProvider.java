package com.codewarp.memory;

import com.codewarp.util.Console;

import java.io.IOException;
import java.util.function.Supplier;

public class MemoryContextProvider {

    private final MemoryStore memoryStore;
    private final Supplier<String> transcriptSessionIdSupplier;

    public MemoryContextProvider(MemoryStore memoryStore) {
        this(memoryStore, null);
    }

    public MemoryContextProvider(MemoryStore memoryStore, Supplier<String> transcriptSessionIdSupplier) {
        this.memoryStore = memoryStore;
        this.transcriptSessionIdSupplier = transcriptSessionIdSupplier;
    }

    public String buildSystemPrompt(String baseSystemPrompt) {
        if (memoryStore == null) {
            return baseSystemPrompt;
        }

        try {
            String rules = memoryStore.readRules();
            String index = memoryStore.readIndex();
            return baseSystemPrompt + """

                    ### Memory

                    CodeWarp has a layered memory system.

                    L0 memory rules:
                    %s

                    L1 memory index:
                    %s

                    L1 is only an index. Do not assume it contains full memory details.
                    When a task needs long-term facts or pitfall experience, use the MemoryRead tool to read the relevant L2 or L3 file.
                    %s
                    """.formatted(rules.strip(), index.strip(), transcriptContext());
        } catch (IOException e) {
            Console.warn("[Memory] 读取 L0/L1 失败，本轮不注入记忆上下文: " + e.getMessage());
            return baseSystemPrompt;
        }
    }

    private String transcriptContext() {
        String sessionId = currentTranscriptSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return "";
        }
        return """

                L5 stores full session transcripts as jsonl files.
                Current transcript session id: %s
                Do not assume L4 contains all historical details after resume or future compaction.
                When historical details are needed, use TranscriptSearch with a session_id and keyword.
                """.formatted(sessionId);
    }

    private String currentTranscriptSessionId() {
        if (transcriptSessionIdSupplier == null) {
            return null;
        }
        try {
            return transcriptSessionIdSupplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
