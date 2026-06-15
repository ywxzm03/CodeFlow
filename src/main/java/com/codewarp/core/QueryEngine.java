package com.codewarp.core;

import com.codewarp.llm.LLMClient;
import com.codewarp.tools.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 查询引擎 - 实现"用户输入 → LLM调用 → 工具调用"的主循环
 */
public class QueryEngine {

    private static final String SYSTEM_PROMPT = """
            You are CodeWarp, an AI coding assistant.

            You help developers write code, debug issues, and answer questions.
            When you need to perform actions, use the available tools.

            Always use tools when you need to:
            - Read file contents
            - Write or edit files
            - Execute shell commands

            Respond concisely and helpfully.
            """;

    private final LLMClient llmClient;
    private final List<Tool> tools;
    private final int maxIterations;

    public QueryEngine(LLMClient llmClient, List<Tool> tools, int maxIterations) {
        this.llmClient = llmClient;
        this.tools = tools;
        this.maxIterations = maxIterations;
    }

    /**
     * 主循环：处理用户输入并返回最终响应
     */
    public QueryResult query(String userInput) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message.User(userInput));

        int iteration = 0;

        while (iteration < maxIterations) {
            iteration++;

            // 1. 调用LLM
            System.out.println("\n[Iteration " + iteration + "] 调用LLM...");
            LLMClient.LLMResponse response = llmClient.call(SYSTEM_PROMPT, messages, tools);

            // 2. 输出LLM的文本响应
            if (response.content() != null && !response.content().isEmpty()) {
                System.out.println("[LLM响应] " + response.content());
            }

            // 3. 添加助手消息
            messages.add(new Message.Assistant(response.content(), response.toolUses()));

            // 4. 检查是否有工具调用
            if (!response.hasToolUses()) {
                // 没有工具调用，循环结束
                System.out.println("\n[完成] 没有更多工具调用，对话结束");
                return new QueryResult(
                    response.content(),
                    messages,
                    iteration,
                    QueryResult.StopReason.COMPLETED
                );
            }

            // 5. 执行工具
            System.out.println("\n[工具执行] 执行 " + response.toolUses().size() + " 个工具...");
            for (Message.ToolUse toolUse : response.toolUses()) {
                Tool.ToolExecutionResult result = executeTool(toolUse);

                // 添加工具结果到消息列表
                messages.add(new Message.ToolResult(
                    toolUse.id(),
                    result.content(),
                    result.isError()
                ));
            }

            // 6. 继续下一轮循环
        }

        // 达到最大迭代次数
        System.out.println("\n[警告] 达到最大迭代次数: " + maxIterations);
        return new QueryResult(
            "达到最大迭代次数限制",
            messages,
            iteration,
            QueryResult.StopReason.MAX_ITERATIONS
        );
    }

    /**
     * 执行单个工具
     */
    private Tool.ToolExecutionResult executeTool(Message.ToolUse toolUse) {
        System.out.println("  - 工具: " + toolUse.name() + " (id: " + toolUse.id() + ")");

        // 查找工具
        Tool tool = tools.stream()
                .filter(t -> t.name().equals(toolUse.name()))
                .findFirst()
                .orElse(null);

        if (tool == null) {
            String error = "未找到工具: " + toolUse.name();
            System.out.println("    [错误] " + error);
            return Tool.ToolExecutionResult.error(error);
        }

        // 执行工具
        try {
            Tool.ToolExecutionResult result = tool.execute(toolUse.input());
            System.out.println("    [结果] " + truncate(result.content(), 100));
            return result;
        } catch (Exception e) {
            String error = "工具执行失败: " + e.getMessage();
            System.out.println("    [错误] " + error);
            return Tool.ToolExecutionResult.error(error);
        }
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "null";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }

    /**
     * 查询结果
     */
    public record QueryResult(
            String finalResponse,
            List<Message> messages,
            int iterations,
            StopReason stopReason
    ) {
        public enum StopReason {
            COMPLETED,
            MAX_ITERATIONS,
            ERROR
        }
    }
}
