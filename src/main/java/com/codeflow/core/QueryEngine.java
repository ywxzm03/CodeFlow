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
import com.codeflow.tools.ToolExecutionContext;
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

    static final String INTERRUPT_MESSAGE = "[Request interrupted by user]";
    static final String INTERRUPT_MESSAGE_FOR_TOOL_USE = "[Request interrupted by user for tool use]";

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
    private final ToolExecutionContext toolExecutionContext;

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
        this(llmClient, tools, maxIterations, toolPermissionManager, preToolUseHandler, stopHookHandler, memoryContextProvider, compactionManager, skillStore, ToolExecutionContext.defaultContext());
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
            SkillStore skillStore,
            ToolExecutionContext toolExecutionContext
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
        this.toolExecutionContext = toolExecutionContext == null ? ToolExecutionContext.defaultContext() : toolExecutionContext;
    }

    /**
     * 主循环：处理用户输入并写入会话级工作记忆。
     */
    public QueryResult query(String userInput, WorkingMemory workingMemory) {
        return query(userInput, workingMemory, CancellationToken.none());
    }

    public QueryResult query(String userInput, WorkingMemory workingMemory, CancellationToken cancellationToken) {
        if (workingMemory == null) {
            throw new IllegalArgumentException("workingMemory must not be null");
        }
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;

        // 记录本轮起点，后续中断或流式失败时只回滚/返回当前 turn。
        int startIndex = workingMemory.size();
        Message.User userMessage = new Message.User(userInput);
        workingMemory.append(userMessage);
        TurnState turnState = new TurnState(startIndex, userMessage);

        int iteration = 0;
        boolean stopHookActive = false;

        while (iteration < maxIterations) {
            iteration++;
            if (token.isCancelled()) {
                // 模型调用前已被取消时，写入一条合成用户消息，保留可解释的会话轨迹。
                workingMemory.append(new Message.User(INTERRUPT_MESSAGE));
                return cancelledResult(INTERRUPT_MESSAGE, workingMemory, turnState, iteration);
            }

            Console.info("\n[Iteration " + iteration + "] 调用LLM...");

            // 流式执行模式
            QueryResult result = executeStreamingIteration(workingMemory, turnState, iteration, stopHookActive, token);
            if (result != null) {
                return result;
            }
            // Stop hook 拒绝结束时会追加隐藏反馈，下一轮需要标记为 hook 重入。
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
        return compact(workingMemory, CancellationToken.none());
    }

    public CompactResult compact(WorkingMemory workingMemory, CancellationToken cancellationToken) {
        if (workingMemory == null) {
            throw new IllegalArgumentException("workingMemory must not be null");
        }
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        if (workingMemory.size() == 0) {
            return CompactResult.notNeeded();
        }
        if (compactionManager == null) {
            return CompactResult.unavailable("compaction is unavailable");
        }

        int beforeMessages = workingMemory.size();
        try {
            token.throwIfCancelled();
            com.codeflow.compact.AutoCompactor.ForceResult result = compactionManager.forceAutoCompact(
                    systemPrompt(),
                    workingMemory,
                    tools,
                    token
            );
            token.throwIfCancelled();
            return switch (result.status()) {
                case COMPACTED -> CompactResult.compacted(beforeMessages, workingMemory.size());
                case NOT_NEEDED -> CompactResult.notNeeded();
                case UNAVAILABLE -> CompactResult.unavailable(result.reason());
            };
        } catch (UserCancelledException e) {
            return CompactResult.cancelled();
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
            boolean stopHookActive,
            CancellationToken cancellationToken
    ) {
        String systemPrompt = systemPrompt();
        if (compactionManager != null) {
            compactionManager.beforeModelCall(systemPrompt, workingMemory, tools, cancellationToken);
        }

        return executeStreamingAttempt(workingMemory, turnState, iteration, systemPrompt, true, stopHookActive, cancellationToken);
    }

    private QueryResult executeStreamingAttempt(
            WorkingMemory workingMemory,
            TurnState turnState,
            int iteration,
            String systemPrompt,
            boolean allowReactiveRetry,
            boolean stopHookActive,
            CancellationToken cancellationToken
    ) {
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        // 创建流式工具执行器
        StreamingToolExecutor executor = new StreamingToolExecutor(
                tools,
                new DefaultToolAdmissionPolicy(toolPermissionManager, preToolUseHandler, toolExecutionContext),
                token,
                toolExecutionContext
        );
        try {
            StringBuilder contentBuilder = new StringBuilder();
            List<Message.ToolUse> allToolUses = new ArrayList<>();
            AtomicReference<Message.Usage> usage = new AtomicReference<>();


            try {
                llmClient.callStreaming(systemPrompt, workingMemory.snapshot(), tools, token)
                        .doOnNext(event -> {
                            token.throwIfCancelled();
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
                if (isUserCancelled(streamError) || token.isCancelled()) {
                    // 流式响应中断时，保留已经收到的 assistant/tool_use，并补齐取消结果。
                    return handleUserCancelledDuringStreaming(
                            workingMemory,
                            turnState,
                            iteration,
                            contentBuilder.toString(),
                            allToolUses,
                            usage.get(),
                            executor
                    );
                }
                if (allowReactiveRetry && compactionManager != null) {
                    executor.discard();
                    CompactionManager.ReactiveResult reactiveResult = compactionManager.reactiveCompact(
                            systemPrompt,
                            workingMemory,
                            tools,
                            streamError,
                            1,
                            token
                    );
                    if (reactiveResult.compacted()) {
                        Console.warn("\n[Compact] 上下文超限，已触发 reactive compact 并重试本轮模型调用");
                        return executeStreamingAttempt(workingMemory, turnState, iteration, systemPrompt, false, stopHookActive, token);
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

            if (token.isCancelled()) {
                // assistant 消息已写入后再中断，需要给所有 tool_use 补 tool_result。
                executor.cancel("Request interrupted by user");
                appendToolResultsInOrder(workingMemory, allToolUses, executor.getRemainingResults(), "Request interrupted by user");
                workingMemory.append(new Message.User(allToolUses.isEmpty() ? INTERRUPT_MESSAGE : INTERRUPT_MESSAGE_FOR_TOOL_USE));
                return cancelledResult(allToolUses.isEmpty() ? INTERRUPT_MESSAGE : INTERRUPT_MESSAGE_FOR_TOOL_USE, workingMemory, turnState, iteration);
            }

            // 没有工具调用 -> 对话结束
            if (allToolUses.isEmpty()) {
                StopHookResult stopHookResult = runStopHook(content, stopHookActive, workingMemory.snapshot());
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
            if (token.isCancelled()) {
                executor.cancel("Request interrupted by user");
            }
            // 即使工具被取消，也按原始 tool_use 顺序回填，避免消息结构不完整。
            appendToolResultsInOrder(workingMemory, allToolUses, executor.getRemainingResults(), "Request interrupted by user");
            if (token.isCancelled()) {
                workingMemory.append(new Message.User(INTERRUPT_MESSAGE_FOR_TOOL_USE));
                return cancelledResult(INTERRUPT_MESSAGE_FOR_TOOL_USE, workingMemory, turnState, iteration);
            }

            // 继续下一轮
            return null;

        } finally {
            executor.shutdown();
        }
    }

    private QueryResult handleUserCancelledDuringStreaming(
            WorkingMemory workingMemory,
            TurnState turnState,
            int iteration,
            String content,
            List<Message.ToolUse> allToolUses,
            Message.Usage usage,
            StreamingToolExecutor executor
    ) {
        // 中断不是普通错误：尽量保留已接收内容，并把未完成工具标记为取消。
        executor.cancel("Request interrupted by user");
        if (!content.isEmpty() || !allToolUses.isEmpty()) {
            workingMemory.append(new Message.Assistant(content, allToolUses, usage));
            appendToolResultsInOrder(workingMemory, allToolUses, executor.getRemainingResults(), "Request interrupted by user");
        }
        workingMemory.append(new Message.User(INTERRUPT_MESSAGE));
        return cancelledResult(INTERRUPT_MESSAGE, workingMemory, turnState, iteration);
    }

    private void appendToolResultsInOrder(
            WorkingMemory workingMemory,
            List<Message.ToolUse> toolUses,
            List<StreamingToolExecutor.ToolResult> results,
            String missingResultMessage
    ) {
        Map<String, StreamingToolExecutor.ToolResult> resultsById = new HashMap<>();
        for (StreamingToolExecutor.ToolResult result : results) {
            resultsById.put(result.toolUseId(), result);
        }
        for (Message.ToolUse tu : toolUses) {
            StreamingToolExecutor.ToolResult result = resultsById.get(tu.id());
            if (result == null) {
                // 模型发出的每个 tool_use 都必须有结果；缺失时补一条错误结果。
                workingMemory.append(new Message.ToolResult(tu.id(), missingResultMessage, true));
                continue;
            }
            workingMemory.append(new Message.ToolResult(result.toolUseId(), result.content(), result.isError()));
            if (!result.isError() && SkillTool.TOOL_NAME.equals(result.toolName())) {
                findSkillTool()
                        .flatMap(tool -> tool.renderInvocation(result.input(), "model"))
                        .ifPresent(renderedSkill -> workingMemory.append(new Message.User(renderedSkill)));
            }
        }
    }

    private QueryResult cancelledResult(String finalResponse, WorkingMemory workingMemory, TurnState turnState, int iteration) {
        return new QueryResult(
                finalResponse,
                workingMemory.snapshot(),
                sliceCurrentTurn(workingMemory, turnState),
                iteration,
                QueryResult.StopReason.USER_CANCELLED
        );
    }

    private boolean isUserCancelled(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof UserCancelledException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private StopHookResult runStopHook(String lastAssistantMessage, boolean stopHookActive, List<Message> messages) {
        StopHookResult result = stopHookHandler.handle(new StopHookInput(
                lastAssistantMessage,
                toolExecutionContext.cwd().toString(),
                toolPermissionManager.permissionMode(),
                stopHookActive,
                messages
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
            USER_CANCELLED,
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

        public static CompactResult cancelled() {
            return new CompactResult(Status.CANCELLED, 0, 0, "cancelled by user");
        }

        public enum Status {
            COMPACTED,
            NOT_NEEDED,
            UNAVAILABLE,
            CANCELLED,
            FAILED
        }
    }
}
