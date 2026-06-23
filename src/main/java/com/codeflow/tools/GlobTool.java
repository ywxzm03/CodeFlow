package com.codeflow.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 文件查找工具：使用 glob 模式查找文件。
 * 只读、并发安全——可与其他只读工具并行执行。
 */
public class GlobTool implements Tool {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "Glob";
    }

    @Override
    public boolean isConcurrencySafe() {
        return true;  // Glob 只读操作，并发安全
    }

    @Override
    public String description() {
        return """
                Fast file pattern matching tool.

                Usage:
                - Supports glob patterns like "**/*.java" or "src/**/*.ts"
                - Returns matching file paths sorted by modification time
                - Use ** for recursive directory matching
                - Use * for single segment matching
                """;
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "patterns": {
                      "type": "array",
                      "items": {"type": "string"},
                      "description": "Glob patterns to match (e.g., ['**/*.java', 'src/**/*.ts'])"
                    },
                    "exclude": {
                      "type": "array",
                      "items": {"type": "string"},
                      "description": "Patterns to exclude (e.g., ['**/test/**', '**/build/**'])"
                    },
                    "root": {
                      "type": "string",
                      "description": "Root directory to search from (default: current directory)"
                    }
                  },
                  "required": ["patterns"]
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
            List<String> patterns = new ArrayList<>();
            inputNode.get("patterns").forEach(node -> patterns.add(node.asText()));

            List<String> excludePatterns = new ArrayList<>();
            if (inputNode.has("exclude")) {
                inputNode.get("exclude").forEach(node -> excludePatterns.add(node.asText()));
            }

            String rootPath = inputNode.has("root")
                    ? inputNode.get("root").asText()
                    : System.getProperty("user.dir");

            Path root = inputNode.has("root") ? context.resolvePath(rootPath) : context.cwd();
            if (!Files.exists(root)) {
                return ToolExecutionResult.error("根目录不存在: " + rootPath);
            }

            // 查找匹配的文件
            List<FileMatch> matches = new ArrayList<>();

            for (String pattern : patterns) {
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        Path relativePath = root.relativize(file);

                        // 检查是否匹配模式
                        if (matcher.matches(relativePath)) {
                            // 检查是否被排除
                            boolean excluded = false;
                            for (String excludePattern : excludePatterns) {
                                PathMatcher excludeMatcher = FileSystems.getDefault()
                                        .getPathMatcher("glob:" + excludePattern);
                                if (excludeMatcher.matches(relativePath)) {
                                    excluded = true;
                                    break;
                                }
                            }

                            if (!excluded) {
                                try {
                                    matches.add(new FileMatch(
                                            file.toString(),
                                            Files.getLastModifiedTime(file).toMillis()
                                    ));
                                } catch (IOException e) {
                                    // 忽略无法读取的文件
                                }
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        // 忽略无法访问的文件
                        return FileVisitResult.CONTINUE;
                    }
                });
            }

            // 按修改时间排序（最新的在前）
            matches.sort((a, b) -> Long.compare(b.modifiedTime, a.modifiedTime));

            // 格式化输出
            if (matches.isEmpty()) {
                return ToolExecutionResult.success("未找到匹配的文件");
            }

            StringBuilder result = new StringBuilder();
            result.append(String.format("找到 %d 个文件:\n\n", matches.size()));

            int count = 0;
            for (FileMatch match : matches) {
                result.append(match.path).append("\n");
                count++;
                if (count >= 100) {
                    result.append(String.format("\n... 还有 %d 个文件未显示", matches.size() - 100));
                    break;
                }
            }

            return ToolExecutionResult.success(result.toString());

        } catch (Exception e) {
            return ToolExecutionResult.error("查找文件失败: " + e.getMessage());
        }
    }

    @Override
    public ValidationResult validateInput(String input) {
        try {
            JsonNode inputNode = ToolInputValidator.parseObject(input);
            ToolInputValidator.rejectUnknownFields(inputNode, Set.of("patterns", "exclude", "root"));
            ToolInputValidator.requireTextArray(inputNode, "patterns");
            ToolInputValidator.optionalTextArray(inputNode, "exclude");
            ToolInputValidator.optionalText(inputNode, "root");
            return ValidationResult.valid();
        } catch (IllegalArgumentException e) {
            return ValidationResult.invalid(e.getMessage());
        }
    }

    private record FileMatch(String path, long modifiedTime) {}
}
