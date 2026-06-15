package com.codewarp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 读取文件工具
 */
public class ReadTool implements Tool {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "Read";
    }

    @Override
    public String description() {
        return "Read the contents of a file from the filesystem";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "file_path": {
                      "type": "string",
                      "description": "The absolute path to the file to read"
                    }
                  },
                  "required": ["file_path"]
                }
                """;
    }

    @Override
    public ToolExecutionResult execute(String input) {
        try {
            // 解析输入
            JsonNode inputNode = objectMapper.readTree(input);
            String filePath = inputNode.get("file_path").asText();

            // 读取文件
            Path path = Paths.get(filePath);

            if (!Files.exists(path)) {
                return ToolExecutionResult.error("文件不存在: " + filePath);
            }

            if (!Files.isRegularFile(path)) {
                return ToolExecutionResult.error("不是一个文件: " + filePath);
            }

            String content = Files.readString(path);

            // 添加行号
            String[] lines = content.split("\n");
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                result.append(String.format("%4d\t%s\n", i + 1, lines[i]));
            }

            return ToolExecutionResult.success(result.toString());

        } catch (Exception e) {
            return ToolExecutionResult.error("读取文件失败: " + e.getMessage());
        }
    }
}
