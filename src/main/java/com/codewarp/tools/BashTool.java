package com.codewarp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * 执行Shell命令工具
 */
public class BashTool implements Tool {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int TIMEOUT_SECONDS = 60;

    @Override
    public String name() {
        return "Bash";
    }

    @Override
    public String description() {
        return "Execute a bash command and return its output";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "command": {
                      "type": "string",
                      "description": "The bash command to execute"
                    }
                  },
                  "required": ["command"]
                }
                """;
    }

    @Override
    public ToolExecutionResult execute(String input) {
        try {
            // 解析输入
            JsonNode inputNode = objectMapper.readTree(input);
            String command = inputNode.get("command").asText();

            // 执行命令
            ProcessBuilder processBuilder = new ProcessBuilder("/bin/bash", "-c", command);
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();

            // 读取输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            // 等待命令完成
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return ToolExecutionResult.error("命令执行超时（" + TIMEOUT_SECONDS + "秒）");
            }

            int exitCode = process.exitValue();

            String result = String.format(
                    "Exit code: %d\n\nOutput:\n%s",
                    exitCode,
                    output.toString()
            );

            if (exitCode != 0) {
                return ToolExecutionResult.error(result);
            }

            return ToolExecutionResult.success(result);

        } catch (Exception e) {
            return ToolExecutionResult.error("执行命令失败: " + e.getMessage());
        }
    }
}
