package com.codewarp.memory;

import com.codewarp.util.Console;

import java.io.IOException;

public class MemoryContextProvider {

    private final MemoryStore memoryStore;

    public MemoryContextProvider(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
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
                    """.formatted(rules.strip(), index.strip());
        } catch (IOException e) {
            Console.warn("[Memory] 读取 L0/L1 失败，本轮不注入记忆上下文: " + e.getMessage());
            return baseSystemPrompt;
        }
    }
}
