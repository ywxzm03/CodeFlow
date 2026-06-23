package com.codeflow.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * 写入文件工具：覆盖写入（不存在则创建，含父目录）。
 * 有副作用，非并发安全——作为执行屏障串行执行。
 */
public class WriteTool implements Tool {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "Write";
    }

    @Override
    public boolean isConcurrencySafe() {
        return false;  // Write 修改文件，不能并发
    }

    @Override
    public String description() {
        return "Write content to a file, creating it if it doesn't exist or overwriting if it does";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "file_path": {
                      "type": "string",
                      "description": "The absolute path to the file to write"
                    },
                    "content": {
                      "type": "string",
                      "description": "The content to write to the file"
                    }
                  },
                  "required": ["file_path", "content"]
                }
                """;
    }

    @Override
    public ToolExecutionResult execute(String input) {
        return execute(input, ToolExecutionContext.defaultContext());
    }

    @Override
    public ToolExecutionResult execute(String input, ToolExecutionContext context) {
        try {
            // 解析输入
            JsonNode inputNode = objectMapper.readTree(input);
            String filePath = inputNode.get("file_path").asText();
            String content = inputNode.get("content").asText();

            // 写入文件
            Path path = context.resolvePath(filePath);

            // 创建父目录（如果不存在）
            Path parentDir = path.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            Files.writeString(path, content);

            return ToolExecutionResult.success("文件已写入: " + filePath);

        } catch (Exception e) {
            return ToolExecutionResult.error("写入文件失败: " + e.getMessage());
        }
    }

    @Override
    public ValidationResult validateInput(String input) {
        try {
            JsonNode inputNode = ToolInputValidator.parseObject(input);
            ToolInputValidator.rejectUnknownFields(inputNode, Set.of("file_path", "content"));
            ToolInputValidator.requireText(inputNode, "file_path");
            ToolInputValidator.requireTextAllowEmpty(inputNode, "content");
            return ValidationResult.valid();
        } catch (IllegalArgumentException e) {
            return ValidationResult.invalid(e.getMessage());
        }
    }
}
