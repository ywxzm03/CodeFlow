package com.codeflow.tools;

import com.codeflow.core.CancellationToken;
import com.codeflow.core.UserCancelledException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 执行 Shell 命令工具。
 * 有副作用，非并发安全——作为执行屏障串行执行；失败时会触发后续工具取消（依赖链已断）。
 * execute 响应线程中断：被取消时销毁子进程，避免命令在被放弃后继续运行。
 */
public class BashTool implements Tool {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int TIMEOUT_SECONDS = 60;

    @Override
    public String name() {
        return "Bash";
    }

    @Override
    public boolean isConcurrencySafe() {
        return false;  // Bash 执行命令，可能有依赖关系，不能并发
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
        return execute(input, ToolExecutionContext.defaultContext(), CancellationToken.none());
    }

    @Override
    public ToolExecutionResult execute(String input, CancellationToken cancellationToken) {
        return execute(input, ToolExecutionContext.defaultContext(), cancellationToken);
    }

    @Override
    public ToolExecutionResult execute(String input, ToolExecutionContext context) {
        return execute(input, context, CancellationToken.none());
    }

    @Override
    public ToolExecutionResult execute(
            String input,
            ToolExecutionContext context,
            CancellationToken cancellationToken
    ) {
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        ToolExecutionContext executionContext = context == null ? ToolExecutionContext.defaultContext() : context;
        Process process = null;
        try {
            token.throwIfCancelled();
            // 解析输入
            JsonNode inputNode = objectMapper.readTree(input);
            String command = inputNode.get("command").asText();
            executionContext.validateBashCommand(command);

            // 执行命令
            ProcessBuilder processBuilder = new ProcessBuilder("/bin/bash", "-c", command);
            processBuilder.directory(executionContext.cwd().toFile());
            processBuilder.redirectErrorStream(true);

            process = processBuilder.start();
            Process runningProcess = process;
            token.onCancel(runningProcess::destroyForcibly);

            // 读取输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    token.throwIfCancelled();
                    // 响应中断（如 sibling Bash 失败触发的取消）：销毁子进程并退出
                    if (Thread.currentThread().isInterrupted()) {
                        process.destroyForcibly();
                        return ToolExecutionResult.error("命令被取消");
                    }
                    output.append(line).append("\n");
                }
            }

            // 等待命令完成
            token.throwIfCancelled();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            token.throwIfCancelled();

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

        } catch (UserCancelledException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return ToolExecutionResult.error("Request interrupted by user");
        } catch (InterruptedException e) {
            // waitFor 被中断：销毁子进程，恢复中断状态
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return ToolExecutionResult.error("命令被取消");
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return ToolExecutionResult.error("执行命令失败: " + e.getMessage());
        }
    }

    @Override
    public ValidationResult validateInput(String input) {
        try {
            JsonNode inputNode = ToolInputValidator.parseObject(input);
            ToolInputValidator.rejectUnknownFields(inputNode, Set.of("command"));
            ToolInputValidator.requireText(inputNode, "command");
            return ValidationResult.valid();
        } catch (IllegalArgumentException e) {
            return ValidationResult.invalid(e.getMessage());
        }
    }
}
