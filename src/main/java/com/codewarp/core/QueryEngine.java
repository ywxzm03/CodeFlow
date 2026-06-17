package com.codewarp.core;

import com.codewarp.llm.LLMClient;
import com.codewarp.tools.Tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询引擎 - 实现"用户输入 → LLM调用 → 工具调用"的主循环
 *
 * 支持流式工具执行：边接收 LLM 响应边执行工具
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

            System.out.println("\n[Iteration " + iteration + "] 调用LLM...");

            // 流式执行模式
            QueryResult result = executeStreamingIteration(messages, iteration);
            if (result != null) {
                return result;
            }
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
     * 流式执行模式的迭代
     */
    private QueryResult executeStreamingIteration(List<Message> messages, int iteration) {
        // 创建流式工具执行器
        StreamingToolExecutor executor = new StreamingToolExecutor(tools);

        try {
            StringBuilder contentBuilder = new StringBuilder();
            List<Message.ToolUse> allToolUses = new ArrayList<>();


            try {
                llmClient.callStreaming(SYSTEM_PROMPT, messages, tools)
                        .doOnNext(event -> {
                            switch (event) {
                                case LLMClient.StreamEvent.TextDelta delta -> contentBuilder.append(delta.text());
                                case LLMClient.StreamEvent.ToolUse toolUse -> {
                                    Message.ToolUse tu = toolUse.toolUse();
                                    allToolUses.add(tu);
                                    System.out.println("  [入队] " + tu.name() + " (id: " + tu.id() + ")");
                                    executor.addTool(tu);
                                }
                            }
                        })
                        .blockLast();
            } catch (RuntimeException streamError) {
                // 流式中断：取消已启动的工具，丢弃本轮所有副作用，不写入半截消息
                executor.discard();
                System.out.println("\n[错误] 流式中断，已丢弃本轮工具执行: " + streamError.getMessage());
                return new QueryResult(
                    "流式调用失败: " + streamError.getMessage(),
                    messages,
                    iteration,
                    QueryResult.StopReason.ERROR
                );
            }

            String content = contentBuilder.toString();
            if (!content.isEmpty()) {
                System.out.println("[LLM响应] " + content);
            }

            // 先添加 assistant 消息（含全部 tool_use），保证 tool result 跟在它之后
            messages.add(new Message.Assistant(content, allToolUses));

            // 没有工具调用 -> 对话结束
            if (allToolUses.isEmpty()) {
                System.out.println("\n[完成] 没有更多工具调用，对话结束");
                return new QueryResult(content, messages, iteration, QueryResult.StopReason.COMPLETED);
            }

            // 等待所有工具完成，按 tool_use 的原始顺序回填结果
            System.out.println("\n[等待] 等待工具完成...");
            Map<String, StreamingToolExecutor.ToolResult> resultsById = new HashMap<>();
            for (StreamingToolExecutor.ToolResult result : executor.getRemainingResults()) {
                resultsById.put(result.toolUseId(), result);
            }
            for (Message.ToolUse tu : allToolUses) {
                StreamingToolExecutor.ToolResult result = resultsById.get(tu.id());
                if (result != null) {
                    messages.add(new Message.ToolResult(result.toolUseId(), result.content(), result.isError()));
                }
            }

            // 继续下一轮
            return null;

        } finally {
            executor.shutdown();
        }
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
