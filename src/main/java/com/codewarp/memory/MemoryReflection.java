package com.codewarp.memory;

import com.codewarp.core.Message;
import com.codewarp.llm.LLMClient;
import com.codewarp.util.Console;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MemoryReflection {

    private static final String SYSTEM_PROMPT = """
            You are CodeWarp's memory reflection step.

            Decide whether the completed task contains information worth saving into long-term memory.
            Follow L0 strictly.

            Return only JSON in this format:
            {
              "memories": [
                {
                  "layer": "L2",
                  "file": "user_preferences.txt",
                  "content": "[2026-06-19] ...",
                  "reason": "Why this is long-lived and useful.",
                  "index_entry": "L2/user_preferences.txt: ..."
                }
              ]
            }

            Use L2 for stable facts, user preferences, project facts, and durable constraints.
            Use L3 for verified pitfall experience and reusable lessons learned from mistakes.
            Return {"memories": []} when nothing is worth saving.
            """;

    private final LLMClient llmClient;
    private final MemoryStore memoryStore;
    private final ObjectMapper objectMapper;
    private volatile MemoryUpdateConfirmer confirmer;

    public MemoryReflection(LLMClient llmClient, MemoryStore memoryStore) {
        this.llmClient = llmClient;
        this.memoryStore = memoryStore;
        this.objectMapper = new ObjectMapper();
        this.confirmer = MemoryUpdateConfirmer.denyByDefault();
    }

    public void setConfirmer(MemoryUpdateConfirmer confirmer) {
        this.confirmer = confirmer == null ? MemoryUpdateConfirmer.denyByDefault() : confirmer;
    }

    public void reflect(List<Message> messages) {
        if (llmClient == null || memoryStore == null || messages == null || messages.isEmpty()) {
            return;
        }

        try {
            String prompt = buildReflectionPrompt(messages);
            LLMClient.LLMResponse response = llmClient.call(SYSTEM_PROMPT, List.of(new Message.User(prompt)), List.of());
            List<MemoryUpdate> updates = parseUpdates(response.content());
            for (MemoryUpdate update : updates) {
                if (!confirmer.confirm(update)) {
                    continue;
                }
                try {
                    memoryStore.applyUpdate(update);
                    Console.info("[Memory] 已写入: " + update.relativePath());
                } catch (IOException | IllegalArgumentException e) {
                    Console.warn("[Memory] 写入失败: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Console.warn("[Memory] 记忆反思失败，已跳过: " + e.getMessage());
        }
    }

    List<MemoryUpdate> parseUpdates(String content) throws IOException {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        JsonNode root = objectMapper.readTree(extractJson(content));
        JsonNode memories = root.get("memories");
        if (memories == null || !memories.isArray() || memories.isEmpty()) {
            return List.of();
        }

        List<MemoryUpdate> updates = new ArrayList<>();
        for (JsonNode memory : memories) {
            updates.add(parseUpdate(memory));
        }
        return updates;
    }

    private MemoryUpdate parseUpdate(JsonNode memory) {
        String layerText = requiredText(memory, "layer");
        MemoryLayer layer = MemoryLayer.fromDirectoryName(layerText);
        String fileName = normalizeFileName(layer, requiredText(memory, "file"));
        String content = requiredText(memory, "content");
        String reason = requiredText(memory, "reason");
        String indexEntry = optionalText(memory, "index_entry");
        return new MemoryUpdate(layer, fileName, content, reason, indexEntry);
    }

    private String buildReflectionPrompt(List<Message> messages) throws IOException {
        return """
                L0 rules:
                %s

                L1 index:
                %s

                Completed task messages:
                %s

                Inspect only this completed task. Propose memory updates only when the information is verified, long-lived, and likely useful in future tasks.
                """.formatted(
                memoryStore.readRules().strip(),
                memoryStore.readIndex().strip(),
                formatMessages(messages)
        );
    }

    private String formatMessages(List<Message> messages) {
        StringBuilder builder = new StringBuilder();
        for (Message message : messages) {
            switch (message) {
                case Message.User user -> builder
                        .append("[USER] ")
                        .append(user.content())
                        .append('\n');
                case Message.Assistant assistant -> {
                    builder.append("[ASSISTANT] ");
                    if (assistant.content() != null && !assistant.content().isBlank()) {
                        builder.append(assistant.content());
                    }
                    if (assistant.hasToolUses()) {
                        builder.append(" tool_uses=").append(assistant.toolUses());
                    }
                    builder.append('\n');
                }
                case Message.ToolResult toolResult -> builder
                        .append("[TOOL_RESULT] ")
                        .append(toolResult.toolUseId())
                        .append(" error=")
                        .append(toolResult.isError())
                        .append(" content=")
                        .append(toolResult.content())
                        .append('\n');
            }
        }
        return builder.toString();
    }

    private String extractJson(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("模型未返回 JSON 对象");
        }
        return trimmed.substring(start, end + 1);
    }

    private static String normalizeFileName(MemoryLayer layer, String file) {
        String prefix = layer.directoryName() + "/";
        return file.startsWith(prefix) ? file.substring(prefix.length()) : file;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少记忆字段: " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("记忆字段必须是字符串: " + field);
        }
        return value.asText();
    }
}
