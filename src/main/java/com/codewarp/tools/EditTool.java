package com.codewarp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 编辑文件工具：精确字符串替换。
 * 默认要求 old_string 在文件中唯一（否则报错），replace_all 时替换全部。
 * 有副作用，非并发安全——作为执行屏障串行执行。
 */
public class EditTool implements Tool {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "Edit";
    }

    @Override
    public boolean isConcurrencySafe() {
        return false;  // Edit 修改文件，不能并发
    }

    @Override
    public String description() {
        return """
                Performs exact string replacement in a file.

                Usage:
                - You must Read the file first before editing
                - old_string must match the file content exactly (including indentation)
                - old_string must be unique in the file, unless replace_all is true
                - Use replace_all to replace all occurrences (e.g., renaming a variable)
                """;
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "file_path": {
                      "type": "string",
                      "description": "The absolute path to the file to edit"
                    },
                    "old_string": {
                      "type": "string",
                      "description": "The exact text to replace (must be unique in the file)"
                    },
                    "new_string": {
                      "type": "string",
                      "description": "The new text to replace it with"
                    },
                    "replace_all": {
                      "type": "boolean",
                      "description": "Replace all occurrences instead of just one (default: false)"
                    }
                  },
                  "required": ["file_path", "old_string", "new_string"]
                }
                """;
    }

    @Override
    public ToolExecutionResult execute(String input) {
        try {
            // 解析输入
            JsonNode inputNode = objectMapper.readTree(input);
            String filePath = inputNode.get("file_path").asText();
            String oldString = inputNode.get("old_string").asText();
            String newString = inputNode.get("new_string").asText();
            boolean replaceAll = inputNode.has("replace_all") && inputNode.get("replace_all").asBoolean();

            // 验证文件
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                return ToolExecutionResult.error("文件不存在: " + filePath);
            }

            // 读取文件内容
            String content = Files.readString(path);

            // 检查 old_string 是否存在
            if (!content.contains(oldString)) {
                return ToolExecutionResult.error(
                        "未找到要替换的文本。请确保 old_string 完全匹配文件内容（包括缩进）。"
                );
            }

            // 检查唯一性（如果不是 replace_all）
            if (!replaceAll) {
                int firstIndex = content.indexOf(oldString);
                int lastIndex = content.lastIndexOf(oldString);
                if (firstIndex != lastIndex) {
                    return ToolExecutionResult.error(
                            "old_string 在文件中不唯一，找到多个匹配。请提供更多上下文或使用 replace_all: true。"
                    );
                }
            }

            // 执行替换
            String newContent;
            int replacementCount;
            if (replaceAll) {
                replacementCount = countOccurrences(content, oldString);
                newContent = content.replace(oldString, newString);
            } else {
                replacementCount = 1;
                int index = content.indexOf(oldString);
                newContent = content.substring(0, index) + newString + content.substring(index + oldString.length());
            }

            // 写回文件
            Files.writeString(path, newContent);

            return ToolExecutionResult.success(
                    String.format("文件已更新: %s\n替换了 %d 处", filePath, replacementCount)
            );

        } catch (Exception e) {
            return ToolExecutionResult.error("编辑文件失败: " + e.getMessage());
        }
    }

    @Override
    public ValidationResult validateInput(String input) {
        try {
            JsonNode inputNode = ToolInputValidator.parseObject(input);
            ToolInputValidator.rejectUnknownFields(inputNode, Set.of("file_path", "old_string", "new_string", "replace_all"));
            ToolInputValidator.requireText(inputNode, "file_path");
            ToolInputValidator.requireText(inputNode, "old_string");
            ToolInputValidator.requireTextAllowEmpty(inputNode, "new_string");
            ToolInputValidator.optionalBoolean(inputNode, "replace_all");
            return ValidationResult.valid();
        } catch (IllegalArgumentException e) {
            return ValidationResult.invalid(e.getMessage());
        }
    }

    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }
}
