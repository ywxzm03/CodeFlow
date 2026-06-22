package com.codeflow.core;

import com.codeflow.compact.CompactionManager;
import com.codeflow.hooks.PreToolUseHandler;
import com.codeflow.hooks.StopHookHandler;
import com.codeflow.hooks.StopHookInput;
import com.codeflow.hooks.StopHookResult;
import com.codeflow.llm.LLMClient;
import com.codeflow.memory.MemoryContextProvider;
import com.codeflow.permissions.ToolPermissionManager;
import com.codeflow.skills.SkillStore;
import com.codeflow.tools.SkillTool;
import com.codeflow.tools.Tool;
import com.codeflow.util.Console;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 查询引擎 - 实现"用户输入 → LLM调用 → 工具调用"的主循环
 *
 * 支持流式工具执行：边接收 LLM 响应边执行工具
 */
public class QueryEngine {

    private static final String SYSTEM_PROMPT = """
            You are CodeFlow, an AI coding assistant.

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
    private final ToolPermissionManager toolPermissionManager;
    private final PreToolUseHandler preToolUseHandler;
    private final StopHookHandler stopHookHandler;
    private final MemoryContextProvider memoryContextProvider;
    private final CompactionManager compactionManager;
    private final SkillStore skillStore;

    public QueryEngine(
            LLMClient llmClient,
            List<Tool> tools,
            int maxIterations,
            ToolPermissionManager toolPermissionManager,
            MemoryContextProvider memoryContextProvider,
            CompactionManager compactionManager
    ) {
        this(llmClient, tools, maxIterations, toolPermissionManager, PreToolUseHandler.none(), StopHookHandler.none(), memoryContextProvider, compactionManager, null);
    }

    public QueryEngine(
            LLMClient llmClient,
            List<Tool> tools,
            int maxIterations,
            ToolPermissionManager toolPermissionManager,
            PreToolUseHandler preToolUseHandler,
            MemoryContextProvider memoryContextProvider,
            CompactionManager compactionManager
    ) {
        this(llmClient, tools, maxIterations, toolPermissionManager, preToolUseHandler, StopHookHandler.none(), memoryContextProvider, compactionManager, null);
    }

    public QueryEngine(
            LLMClient llmClient,
            List<Tool> tools,
            int maxIterations,
            ToolPermissionManager toolPermissionManager,
            PreToolUseHandler preToolUseHandler,
            StopHookHandler stopHookHandler,
            MemoryContextProvider memoryContextProvider,
            CompactionManager compactionManager
    ) {
        this(llmClient, tools, maxIterations, toolPermissionManager, preToolUseHandler, stopHookHandler, memoryContextProvider, compactionManager, null);
    }

    public QueryEngine(
            LLMClient llmClient,
            List<Tool> tools,
            int maxIterations,
            ToolPermissionManager toolPermissionManager,
            MemoryContextProvider memoryContextProvider,
            CompactionManager compactionManager,
            SkillStore skillStore
    ) {
        this(llmClient, tools, maxIterations, toolPermissionManager, PreToolUseHandler.none(), StopHookHandler.none(), memoryContextProvider, compactionManager, skillStore);
    }

    public QueryEngine(
            LLMClient llmClient,
            List<Tool> tools,
            int maxIterations,
            ToolPermissionManager toolPermissionManager,
            PreToolUseHandler preToolUseHandler,
            StopHookHandler stopHookHandler,
            MemoryContextProvider memoryContextProvider,
            CompactionManager compactionManager,
            SkillStore skillStore
    ) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
        this.tools = Objects.requireNonNull(tools, "tools must not be null");
        this.maxIterations = maxIterations;
        this.toolPermissionManager = Objects.requireNonNull(toolPermissionManager, "toolPermissionManager must not be null");
        this.preToolUseHandler = preToolUseHandler == null ? PreToolUseHandler.none() : preToolUseHandler;
        this.stopHookHandler = stopHookHandler == null ? StopHookHandler.none() : stopHookHandler;
        this.memoryContextProvider = memoryContextProvider;
        this.compactionManager = compactionManager;
        this.skillStore = skillStore;
    }

    /**
     * 主循环：处理用户输入并写入会话级工作记忆。
     */
    public QueryResult query(String userInput, WorkingMemory workingMemory) {
        if (workingMemory == null) {
            throw new IllegalArgumentException("workingMemory must not be null");
        }

        int startIndex = workingMemory.size();
        Message.User userMessage = new Message.User(userInput);
        workingMemory.append(userMessage);
        TurnState turnState = new TurnState(startIndex, userMessage);

        int iteration = 0;
        boolean stopHookActive = false;

        while (iteration < maxIterations) {
            iteration++;

            Console.info("\n[Iteration " + iteration + "] 调用LLM...");

            // 流式执行模式
            QueryResult result = executeStreamingIteration(workingMemory, turnState, iteration, stopHookActive);
            if (result != null) {
                return result;
            }
            stopHookActive = lastMessageIsHiddenStopFeedback(workingMemory);
        }

        // 达到最大迭代次数
        Console.warn("\n[警告] 达到最大迭代次数: " + maxIterations);
        List<Message> turnMessages = sliceCurrentTurn(workingMemory, turnState);
        return new QueryResult(
            "达到最大迭代次数限制",
            workingMemory.snapshot(),
            turnMessages,
            iteration,
            QueryResult.StopReason.MAX_ITERATIONS
        );
    }

    private String systemPrompt() {
        String prompt = memoryContextProvider == null
                ? SYSTEM_PROMPT
                : memoryContextProvider.buildSystemPrompt(SYSTEM_PROMPT);
        if (skillStore == null) {
            return prompt;
        }

        String skillIndex = skillStore.renderIndex();
        if (skillIndex.isBlank()) {
            return prompt;
        }
        return prompt + "\n\n### Skills\n" + skillIndex;
    }

    /**
     * 手动压缩当前工作记忆。
     */
    public CompactResult compact(WorkingMemory workingMemory) {
        if (workingMemory == null) {
            throw new IllegalArgumentException("workingMemory must not be null");
        }
        if (workingMemory.size() == 0) {
            return CompactResult.notNeeded();
        }
        if (compactionManager == null) {
            return CompactResult.unavailable("compaction is unavailable");
        }

        int beforeMessages = workingMemory.size();
        try {
            com.codeflow.compact.AutoCompactor.ForceResult result = compactionManager.forceAutoCompact(
                    systemPrompt(),
                    workingMemory,
                    tools
            );
            return switch (result.status()) {
                case COMPACTED -> CompactResult.compacted(beforeMessages, workingMemory.size());
                case NOT_NEEDED -> CompactResult.notNeeded();
                case UNAVAILABLE -> CompactResult.unavailable(result.reason());
            };
        } catch (RuntimeException e) {
            return CompactResult.failed(e.getMessage());
        }
    }

    /**
     * 流式执行模式的迭代
     */
    private QueryResult executeStreamingIteration(
            WorkingMemory workingMemory,
            TurnState turnState,
            int iteration,
            boolean stopHookActive
    ) {
        String systemPrompt = systemPrompt();
        if (compactionManager != null) {
            compactionManager.beforeModelCall(systemPrompt, workingMemory, tools);
        }

        return executeStreamingAttempt(workingMemory, turnState, iteration, systemPrompt, true, stopHookActive);
    }

    private QueryResult executeStreamingAttempt(
            WorkingMemory workingMemory,
            TurnState turnState,
            int iteration,
            String systemPrompt,
            boolean allowReactiveRetry,
            boolean stopHookActive
    ) {
        // 创建流式工具执行器
        StreamingToolExecutor executor = new StreamingToolExecutor(tools, toolPermissionManager, preToolUseHandler);
        try {
            StringBuilder contentBuilder = new StringBuilder();
            List<Message.ToolUse> allToolUses = new ArrayList<>();
            AtomicReference<Message.Usage> usage = new AtomicReference<>();


            try {
                llmClient.callStreaming(systemPrompt, workingMemory.snapshot(), tools)
                        .doOnNext(event -> {
                            switch (event) {
                                case LLMClient.StreamEvent.TextDelta delta -> contentBuilder.append(delta.text());
                                case LLMClient.StreamEvent.ToolUse toolUse -> {
                                    Message.ToolUse tu = toolUse.toolUse();
                                    allToolUses.add(tu);
                                    Console.info("  [入队] " + tu.name() + " (id: " + tu.id() + ")");
                                    executor.addTool(tu);
                                }
                                case LLMClient.StreamEvent.Usage usageEvent -> usage.set(usageEvent.usage());
                            }
                        })
                        .blockLast();
            } catch (RuntimeException streamError) {
                if (allowReactiveRetry && compactionManager != null) {
                    executor.discard();
                    CompactionManager.ReactiveResult reactiveResult = compactionManager.reactiveCompact(
                            systemPrompt,
                            workingMemory,
                            tools,
                            streamError,
                            1
                    );
                    if (reactiveResult.compacted()) {
                        Console.warn("\n[Compact] 上下文超限，已触发 reactive compact 并重试本轮模型调用");
                        return executeStreamingAttempt(workingMemory, turnState, iteration, systemPrompt, false, stopHookActive);
                    }
                }
                // 流式中断：取消已启动的工具，丢弃本轮所有副作用，不写入半截消息
                executor.discard();
                rollbackCurrentTurn(workingMemory, turnState);
                Console.warn("\n[错误] 流式中断，已丢弃本轮工具执行: " + streamError.getMessage());
                return new QueryResult(
                    "流式调用失败: " + streamError.getMessage(),
                    workingMemory.snapshot(),
                    List.of(),
                    iteration,
                    QueryResult.StopReason.ERROR
                );
            }

            String content = contentBuilder.toString();
            if (!content.isEmpty()) {
                Console.info("[LLM响应] " + content);
            }

            // 先添加 assistant 消息（含全部 tool_use），保证 tool result 跟在它之后
            workingMemory.append(new Message.Assistant(content, allToolUses, usage.get()));

            // 没有工具调用 -> 对话结束
            if (allToolUses.isEmpty()) {
                StopHookResult stopHookResult = runStopHook(content, stopHookActive);
                if (!stopHookActive && stopHookResult.blocked()) {
                    workingMemory.append(new Message.User(formatStopHookFeedback(stopHookResult.feedback()), true));
                    return null;
                }
                Console.info("\n[完成] 没有更多工具调用，对话结束");
                return new QueryResult(
                        content,
                        workingMemory.snapshot(),
                        sliceCurrentTurn(workingMemory, turnState),
                        iteration,
                        QueryResult.StopReason.COMPLETED
                );
            }

            // 等待所有工具完成，按 tool_use 的原始顺序回填结果
            Console.info("\n[等待] 等待工具完成...");
            Map<String, StreamingToolExecutor.ToolResult> resultsById = new HashMap<>();
            for (StreamingToolExecutor.ToolResult result : executor.getRemainingResults()) {
                resultsById.put(result.toolUseId(), result);
            }
            for (Message.ToolUse tu : allToolUses) {
                StreamingToolExecutor.ToolResult result = resultsById.get(tu.id());
                if (result != null) {
                    workingMemory.append(new Message.ToolResult(result.toolUseId(), result.content(), result.isError()));
                    if (!result.isError() && SkillTool.TOOL_NAME.equals(result.toolName())) {
                        findSkillTool()
                                .flatMap(tool -> tool.renderInvocation(result.input(), "model"))
                                .ifPresent(renderedSkill -> workingMemory.append(new Message.User(renderedSkill)));
                    }
                }
            }

            // 继续下一轮
            return null;

        } finally {
            executor.shutdown();
        }
    }

    private StopHookResult runStopHook(String lastAssistantMessage, boolean stopHookActive) {
        StopHookResult result = stopHookHandler.handle(new StopHookInput(
                lastAssistantMessage,
                System.getProperty("user.dir"),
                toolPermissionManager.permissionMode(),
                stopHookActive
        ));
        return result == null ? StopHookResult.allow() : result;
    }

    private String formatStopHookFeedback(String feedback) {
        return "Stop hook feedback:\n" + (feedback == null ? "" : feedback);
    }

    private boolean lastMessageIsHiddenStopFeedback(WorkingMemory workingMemory) {
        List<Message> messages = workingMemory.snapshot();
        if (messages.isEmpty()) {
            return false;
        }
        Message last = messages.getLast();
        return last instanceof Message.User user
                && user.hidden()
                && user.content().startsWith("Stop hook feedback:\n");
    }

    private List<Message> sliceCurrentTurn(WorkingMemory workingMemory, TurnState turnState) {
        List<Message> snapshot = workingMemory.snapshot();
        int startIndex = currentTurnStart(snapshot, turnState);
        return snapshot.subList(startIndex, snapshot.size()).stream()
                .filter(message -> !(message instanceof Message.User user && user.hidden()))
                .toList();
    }

    private void rollbackCurrentTurn(WorkingMemory workingMemory, TurnState turnState) {
        int startIndex = currentTurnStart(workingMemory.snapshot(), turnState);
        workingMemory.rollbackTo(startIndex);
    }

    private int currentTurnStart(List<Message> messages, TurnState turnState) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) == turnState.userMessage()) {
                return i;
            }
        }
        return Math.min(turnState.originalStartIndex(), messages.size());
    }

    private java.util.Optional<SkillTool> findSkillTool() {
        return tools.stream()
                .filter(SkillTool.class::isInstance)
                .map(SkillTool.class::cast)
                .findFirst();
    }

    /**
     * 查询结果
     */
    public record QueryResult(
            String finalResponse,
            List<Message> messages,
            List<Message> turnMessages,
            int iterations,
            StopReason stopReason
    ) {
        public enum StopReason {
            COMPLETED,
            MAX_ITERATIONS,
            ERROR
        }
    }

    private record TurnState(int originalStartIndex, Message.User userMessage) {
    }

    public record CompactResult(Status status, int beforeMessages, int afterMessages, String reason) {
        public static CompactResult compacted(int beforeMessages, int afterMessages) {
            return new CompactResult(Status.COMPACTED, beforeMessages, afterMessages, "");
        }

        public static CompactResult notNeeded() {
            return new CompactResult(Status.NOT_NEEDED, 0, 0, "");
        }

        public static CompactResult unavailable(String reason) {
            return new CompactResult(Status.UNAVAILABLE, 0, 0, reason);
        }

        public static CompactResult failed(String reason) {
            return new CompactResult(Status.FAILED, 0, 0, reason);
        }

        public enum Status {
            COMPACTED,
            NOT_NEEDED,
            UNAVAILABLE,
            FAILED
        }
    }
}
