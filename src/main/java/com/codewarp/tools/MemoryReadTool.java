package com.codewarp.tools;

import com.codewarp.memory.MemoryStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * Read long-term memory files from L2/L3.
 */
public class MemoryReadTool implements Tool {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final MemoryStore memoryStore;

    public MemoryReadTool(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Override
    public String name() {
        return "MemoryRead";
    }

    @Override
    public String description() {
        return "Read a CodeWarp memory file from L2 or L3 when the L1 index indicates relevant long-term facts or pitfall experience";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string",
                      "description": "Relative memory path, such as L2/user_preferences.txt or L3/terminal_completion_pitfalls.md"
                    }
                  },
                  "required": ["path"]
                }
                """;
    }

    @Override
    public boolean isConcurrencySafe() {
        return true;
    }

    @Override
    public ToolExecutionResult execute(String input) {
        try {
            JsonNode inputNode = objectMapper.readTree(input);
            String path = inputNode.get("path").asText();
            return ToolExecutionResult.success(memoryStore.readMemory(path));
        } catch (Exception e) {
            return ToolExecutionResult.error("读取记忆失败: " + e.getMessage());
        }
    }

    @Override
    public ValidationResult validateInput(String input) {
        try {
            JsonNode inputNode = ToolInputValidator.parseObject(input);
            ToolInputValidator.rejectUnknownFields(inputNode, Set.of("path"));
            ToolInputValidator.requireText(inputNode, "path");
            memoryStore.validateReadableMemoryPath(inputNode.get("path").asText());
            return ValidationResult.valid();
        } catch (IllegalArgumentException e) {
            return ValidationResult.invalid(e.getMessage());
        }
    }
}
