package com.codewarp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码搜索工具 - 在文件中搜索匹配的内容
 */
public class GrepTool implements Tool {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "Grep";
    }

    @Override
    public String description() {
        return """
                A powerful search tool for finding patterns in files.

                Usage:
                - Supports full regex syntax (e.g., "log.*Error", "function\\\\s+\\\\w+")
                - Filter files with glob parameter (e.g., "*.js", "**/*.tsx")
                - Output modes: "content" shows matching lines, "files" shows only file paths, "count" shows match counts
                - Use this instead of running grep/rg as a Bash command
                """;
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "pattern": {
                      "type": "string",
                      "description": "The regex pattern to search for"
                    },
                    "glob": {
                      "type": "string",
                      "description": "Glob pattern to filter files (e.g., '**/*.java')"
                    },
                    "output_mode": {
                      "type": "string",
                      "enum": ["content", "files", "count"],
                      "description": "Output mode: 'content' shows lines, 'files' shows paths, 'count' shows counts"
                    },
                    "case_sensitive": {
                      "type": "boolean",
                      "description": "Case sensitive matching (default: true)"
                    },
                    "root": {
                      "type": "string",
                      "description": "Root directory to search from (default: current directory)"
                    }
                  },
                  "required": ["pattern"]
                }
                """;
    }

    @Override
    public ToolExecutionResult execute(String input) {
        try {
            // 解析输入
            JsonNode inputNode = objectMapper.readTree(input);
            String patternStr = inputNode.get("pattern").asText();
            String globPattern = inputNode.has("glob") ? inputNode.get("glob").asText() : "**/*";
            String outputMode = inputNode.has("output_mode") ? inputNode.get("output_mode").asText() : "files";
            boolean caseSensitive = !inputNode.has("case_sensitive") || inputNode.get("case_sensitive").asBoolean();
            String rootPath = inputNode.has("root") ? inputNode.get("root").asText() : System.getProperty("user.dir");

            // 编译正则表达式
            Pattern pattern;
            try {
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
                pattern = Pattern.compile(patternStr, flags);
            } catch (Exception e) {
                return ToolExecutionResult.error("无效的正则表达式: " + e.getMessage());
            }

            Path root = Paths.get(rootPath);
            if (!Files.exists(root)) {
                return ToolExecutionResult.error("根目录不存在: " + rootPath);
            }

            // 搜索文件
            PathMatcher fileMatcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);
            List<SearchResult> results = new ArrayList<>();

            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path relativePath = root.relativize(file);

                    // 检查文件是否匹配 glob
                    if (!fileMatcher.matches(relativePath)) {
                        return FileVisitResult.CONTINUE;
                    }

                    // 跳过二进制文件（简单检查）
                    if (isBinaryFile(file)) {
                        return FileVisitResult.CONTINUE;
                    }

                    try {
                        List<String> lines = Files.readAllLines(file);
                        List<MatchedLine> matchedLines = new ArrayList<>();

                        for (int i = 0; i < lines.size(); i++) {
                            String line = lines.get(i);
                            Matcher matcher = pattern.matcher(line);
                            if (matcher.find()) {
                                matchedLines.add(new MatchedLine(i + 1, line));
                            }
                        }

                        if (!matchedLines.isEmpty()) {
                            results.add(new SearchResult(file.toString(), matchedLines));
                        }

                    } catch (IOException e) {
                        // 忽略无法读取的文件
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });

            // 格式化输出
            return formatOutput(results, outputMode);

        } catch (Exception e) {
            return ToolExecutionResult.error("搜索失败: " + e.getMessage());
        }
    }

    private ToolExecutionResult formatOutput(List<SearchResult> results, String outputMode) {
        if (results.isEmpty()) {
            return ToolExecutionResult.success("未找到匹配的内容");
        }

        StringBuilder output = new StringBuilder();

        switch (outputMode) {
            case "files" -> {
                output.append(String.format("找到 %d 个匹配的文件:\n\n", results.size()));
                for (SearchResult result : results) {
                    output.append(result.filePath).append("\n");
                }
            }
            case "count" -> {
                output.append(String.format("找到 %d 个匹配的文件:\n\n", results.size()));
                for (SearchResult result : results) {
                    output.append(String.format("%s: %d 个匹配\n", result.filePath, result.matchedLines.size()));
                }
            }
            case "content" -> {
                output.append(String.format("找到 %d 个匹配的文件:\n\n", results.size()));
                int fileCount = 0;
                for (SearchResult result : results) {
                    output.append(result.filePath).append(":\n");
                    for (MatchedLine line : result.matchedLines) {
                        output.append(String.format("%4d: %s\n", line.lineNumber, line.content));
                    }
                    output.append("\n");

                    fileCount++;
                    if (fileCount >= 50) {
                        output.append(String.format("... 还有 %d 个文件未显示，使用 output_mode='files' 查看所有文件",
                                results.size() - 50));
                        break;
                    }
                }
            }
        }

        return ToolExecutionResult.success(output.toString());
    }

    private boolean isBinaryFile(Path file) {
        // 简单的二进制文件检查：检查文件扩展名
        String fileName = file.getFileName().toString().toLowerCase();
        return fileName.endsWith(".jar") ||
               fileName.endsWith(".class") ||
               fileName.endsWith(".zip") ||
               fileName.endsWith(".tar") ||
               fileName.endsWith(".gz") ||
               fileName.endsWith(".png") ||
               fileName.endsWith(".jpg") ||
               fileName.endsWith(".jpeg") ||
               fileName.endsWith(".gif") ||
               fileName.endsWith(".pdf") ||
               fileName.endsWith(".exe") ||
               fileName.endsWith(".dll") ||
               fileName.endsWith(".so");
    }

    private record SearchResult(String filePath, List<MatchedLine> matchedLines) {}
    private record MatchedLine(int lineNumber, String content) {}
}
