package com.codeflow.agents;

import com.codeflow.core.CancellationToken;
import com.codeflow.core.QueryEngine;
import com.codeflow.core.WorkingMemory;
import com.codeflow.hooks.PreToolUseHandler;
import com.codeflow.hooks.StopHookHandler;
import com.codeflow.llm.LLMClient;
import com.codeflow.permissions.PermissionMode;
import com.codeflow.permissions.ToolPermissionManager;
import com.codeflow.skills.SkillStore;
import com.codeflow.tasks.BackgroundAgentTask;
import com.codeflow.tasks.BackgroundTaskRegistry;
import com.codeflow.tools.Tool;
import com.codeflow.tools.ToolExecutionContext;
import com.codeflow.worktree.WorktreeService;
import com.codeflow.worktree.WorktreeSession;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * subagent 执行协调器，负责工具隔离、worktree 和后台任务状态。
 */
public final class SubagentRunner {
    private static final Set<String> READ_ONLY_TOOLS = Set.of("Read", "Grep", "Glob", "Bash");
    private static final Set<String> VERIFIER_TOOLS = Set.of("Read", "Grep", "Glob", "Bash");

    private final LLMClient llmClient;
    private final List<Tool> tools;
    private final int maxIterations;
    private final SkillStore skillStore;
    private final Path projectRoot;

    public SubagentRunner(
            LLMClient llmClient,
            List<Tool> tools,
            int maxIterations,
            SkillStore skillStore
    ) {
        this(llmClient, tools, maxIterations, skillStore, Path.of(System.getProperty("user.dir")));
    }

    public SubagentRunner(
            LLMClient llmClient,
            List<Tool> tools,
            int maxIterations,
            SkillStore skillStore,
            Path projectRoot
    ) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
        this.tools = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
        this.maxIterations = maxIterations;
        this.skillStore = skillStore;
        this.projectRoot = (projectRoot == null ? Path.of(System.getProperty("user.dir")) : projectRoot)
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Planner 使用只读工具在前台运行。
     */
    public QueryEngine.QueryResult runPlanner(String prompt, CancellationToken cancellationToken) {
        return runReadOnlyForeground(AgentDefinition.PLANNER, prompt, cancellationToken);
    }

    /**
     * Explorer 使用只读工具在前台运行。
     */
    public QueryEngine.QueryResult runExplorer(String prompt, CancellationToken cancellationToken) {
        return runReadOnlyForeground(AgentDefinition.EXPLORER, prompt, cancellationToken);
    }

    public QueryEngine.QueryResult runForeground(
            AgentInvocation invocation,
            BackgroundTaskRegistry registry,
            WorktreeService worktreeService,
            CancellationToken cancellationToken
    ) {
        // 前台/后台只影响等待方式，不改变安全边界。
        // Coder 仍然走 worktree，只读 agent 仍然只拿只读工具。
        return switch (invocation.agent().type()) {
            case "Explorer", "Planner" -> runReadOnlyForeground(invocation.agent(), invocation.prompt(), cancellationToken);
            case "Coder" -> runCoderForeground(invocation, worktreeService, cancellationToken);
            case "Verifier" -> runVerifierForeground(invocation, registry, cancellationToken);
            default -> throw new IllegalArgumentException("Unknown subagent_type: " + invocation.agent().type());
        };
    }

    public BackgroundAgentTask launchCoder(
            AgentInvocation invocation,
            BackgroundTaskRegistry registry,
            WorktreeService worktreeService,
            ExecutorService executorService
    ) {
        return launchBackground(invocation, registry, worktreeService, executorService);
    }

    /**
     * 后台启动，返回任务句柄并异步执行。
     */
    public BackgroundAgentTask launchBackground(
            AgentInvocation invocation,
            BackgroundTaskRegistry registry,
            WorktreeService worktreeService,
            ExecutorService executorService
    ) {
        String agentId = WorktreeService.newAgentId();
        // 先注册再执行，让 /agent 和 /batch status 能立即看到排队任务。
        BackgroundAgentTask task = registry.register(
                valueOr(invocation.batchId(), "manual"),
                agentId,
                invocation.agent().type(),
                invocation.displayName(),
                valueOr(invocation.unitId(), "manual"),
                invocation.targetAgentId(),
                invocation.description()
        );
        executorService.submit(() -> runBackground(invocation, task, registry, worktreeService, null));
        return task;
    }

    /**
     * /batch 使用的 Coder + Verifier 串联入口。
     */
    public BackgroundAgentTask launchCoderWithVerifier(
            AgentInvocation coderInvocation,
            AgentInvocation verifierInvocation,
            BackgroundTaskRegistry registry,
            WorktreeService worktreeService,
            ExecutorService executorService
    ) {
        String agentId = WorktreeService.newAgentId();
        // /batch 使用成对启动：Coder 成功后自动把 worktree 交给 Verifier。
        BackgroundAgentTask task = registry.register(
                valueOr(coderInvocation.batchId(), "manual"),
                agentId,
                AgentDefinition.CODER.type(),
                coderInvocation.displayName(),
                valueOr(coderInvocation.unitId(), "manual"),
                "",
                coderInvocation.description()
        );
        executorService.submit(() -> runBackground(coderInvocation, task, registry, worktreeService, verifierInvocation));
        return task;
    }

    private QueryEngine.QueryResult runReadOnlyForeground(
            AgentDefinition agent,
            String prompt,
            CancellationToken cancellationToken
    ) {
        ToolExecutionContext context = ToolExecutionContext.subagentReadOnly(projectRoot, null, null, agent.type());
        return query(prompt, readOnlyTools(), context, cancellationToken);
    }

    private QueryEngine.QueryResult runCoderForeground(
            AgentInvocation invocation,
            WorktreeService worktreeService,
            CancellationToken cancellationToken
    ) {
        try {
            // 前台 Coder 也不直接改主目录，写入仍限制在临时 worktree。
            String agentId = WorktreeService.newAgentId();
            WorktreeSession worktreeSession = worktreeService.createAgentWorktree(agentId);
            ToolExecutionContext context = ToolExecutionContext.subagentCoder(
                    worktreeSession.worktreePath(),
                    agentId,
                    valueOr(invocation.batchId(), "manual")
            );
            return query(invocation.prompt(), tools, context, cancellationToken);
        } catch (Exception e) {
            throw new IllegalStateException("Coder foreground run failed: " + e.getMessage(), e);
        }
    }

    private QueryEngine.QueryResult runVerifierForeground(
            AgentInvocation invocation,
            BackgroundTaskRegistry registry,
            CancellationToken cancellationToken
    ) {
        VerifierTarget target = verifierTarget(invocation, registry);
        ToolExecutionContext context = ToolExecutionContext.subagentVerifier(
                target.cwd(),
                target.cwd(),
                null,
                valueOr(invocation.batchId(), "manual"),
                invocation.targetAgentId()
        );
        return query(invocation.prompt(), verifierTools(), context, cancellationToken);
    }

    private void runBackground(
            AgentInvocation invocation,
            BackgroundAgentTask task,
            BackgroundTaskRegistry registry,
            WorktreeService worktreeService,
            AgentInvocation verifierInvocation
    ) {
        switch (invocation.agent().type()) {
            case "Explorer", "Planner" -> runReadOnlyBackground(invocation, task, registry);
            case "Coder" -> runCoder(invocation, task, registry, worktreeService, verifierInvocation);
            case "Verifier" -> runVerifier(invocation, task, registry);
            default -> {
                task.markFailed("Unknown subagent_type: " + invocation.agent().type());
                registry.persist(task);
            }
        }
    }

    private void runReadOnlyBackground(
            AgentInvocation invocation,
            BackgroundAgentTask task,
            BackgroundTaskRegistry registry
    ) {
        try {
            task.markRunning(null, null);
            registry.persist(task);
            ToolExecutionContext context = ToolExecutionContext.subagentReadOnly(
                    projectRoot,
                    task.agentId(),
                    task.batchId(),
                    invocation.agent().type()
            );
            QueryEngine.QueryResult queryResult = query(
                    invocation.prompt(),
                    readOnlyTools(),
                    context,
                    task.cancellationToken()
            );
            registry.appendLog(task.agentId(), queryResult.finalResponse() + System.lineSeparator());
            if (queryResult.stopReason() == QueryEngine.QueryResult.StopReason.USER_CANCELLED || task.cancellationToken().isCancelled()) {
                task.cancel();
                registry.persist(task);
                return;
            }
            AgentResult result = AgentResultParser.parseGeneric(
                    invocation.agent(),
                    task.agentId(),
                    task.batchId(),
                    task.unitId(),
                    queryResult.finalResponse(),
                    projectRoot
            );
            if (result.status() == AgentResult.Status.SUCCESS) {
                task.markSuccess("", result.resultSummary(), result.testSummary(), result.verdict());
            } else {
                task.markFailed(result.failureReason());
            }
            registry.persist(task);
        } catch (Exception e) {
            task.markFailed(e.getMessage());
            registry.appendLog(task.agentId(), "ERROR: " + e.getMessage() + System.lineSeparator());
            registry.persist(task);
        }
    }

    private void runCoder(
            AgentInvocation invocation,
            BackgroundAgentTask task,
            BackgroundTaskRegistry registry,
            WorktreeService worktreeService,
            AgentInvocation verifierInvocation
    ) {
        WorktreeSession worktreeSession = null;
        try {
            worktreeSession = worktreeService.createAgentWorktree(task.agentId());
            task.markRunning(worktreeSession.worktreePath(), worktreeSession.branchName());
            registry.persist(task);

            ToolExecutionContext context = ToolExecutionContext.batchWorker(
                    worktreeSession.worktreePath(),
                    task.agentId(),
                    task.batchId()
            );
            QueryEngine.QueryResult queryResult = query(
                    invocation.prompt(),
                    tools,
                    context,
                    task.cancellationToken()
            );
            registry.appendLog(task.agentId(), queryResult.finalResponse() + System.lineSeparator());

            if (queryResult.stopReason() == QueryEngine.QueryResult.StopReason.USER_CANCELLED || task.cancellationToken().isCancelled()) {
                task.cancel();
                registry.persist(task);
                return;
            }

            AgentResult result = AgentResultParser.parse(
                    task.agentId(),
                    task.batchId(),
                    task.unitId(),
                    queryResult.finalResponse(),
                    worktreeSession.branchName(),
                    worktreeSession.worktreePath()
            );
            if (result.status() == AgentResult.Status.SUCCESS) {
                task.markSuccess(result.commitSha(), result.resultSummary(), result.testSummary());
                registry.persist(task);
                if (verifierInvocation != null) {
                    // Verifier 通过 Coder 的 agentId 定位 worktree，并在其中验证。
                    AgentInvocation targetedVerifier = new AgentInvocation(
                            AgentDefinition.VERIFIER,
                            task.batchId(),
                            task.unitId(),
                            verifierInvocation.prompt(),
                            verifierInvocation.description(),
                            true,
                            "",
                            task.agentId()
                    );
                    BackgroundAgentTask verifierTask = registry.register(
                            task.batchId(),
                            WorktreeService.newAgentId(),
                            AgentDefinition.VERIFIER.type(),
                            targetedVerifier.displayName(),
                            task.unitId(),
                            task.agentId(),
                            targetedVerifier.description()
                    );
                    runVerifier(targetedVerifier, verifierTask, registry);
                    return;
                }
            } else {
                task.markFailed(result.failureReason());
            }
            registry.persist(task);
        } catch (Exception e) {
            task.markFailed(e.getMessage());
            registry.appendLog(task.agentId(), "ERROR: " + e.getMessage() + System.lineSeparator());
            registry.persist(task);
        }
    }

    private void runVerifier(
            AgentInvocation invocation,
            BackgroundAgentTask task,
            BackgroundTaskRegistry registry
    ) {
        try {
            VerifierTarget target = verifierTarget(invocation, registry);
            task.markRunning(target.cwd(), null);
            registry.persist(task);
            ToolExecutionContext context = ToolExecutionContext.subagentVerifier(
                    target.cwd(),
                    target.cwd(),
                    task.agentId(),
                    task.batchId(),
                    invocation.targetAgentId()
            );
            QueryEngine.QueryResult queryResult = query(
                    invocation.prompt(),
                    verifierTools(),
                    context,
                    task.cancellationToken()
            );
            registry.appendLog(task.agentId(), queryResult.finalResponse() + System.lineSeparator());
            if (queryResult.stopReason() == QueryEngine.QueryResult.StopReason.USER_CANCELLED || task.cancellationToken().isCancelled()) {
                task.cancel();
                registry.persist(task);
                return;
            }
            AgentResult result = AgentResultParser.parseGeneric(
                    AgentDefinition.VERIFIER,
                    task.agentId(),
                    task.batchId(),
                    task.unitId(),
                    queryResult.finalResponse(),
                    target.cwd()
            );
            if (result.status() == AgentResult.Status.SUCCESS) {
                task.markSuccess("", result.resultSummary(), result.testSummary(), result.verdict());
            } else {
                task.markFailed(result.failureReason());
            }
            registry.persist(task);
        } catch (Exception e) {
            task.markFailed(e.getMessage());
            registry.appendLog(task.agentId(), "ERROR: " + e.getMessage() + System.lineSeparator());
            registry.persist(task);
        }
    }

    private QueryEngine.QueryResult query(
            String prompt,
            List<Tool> selectedTools,
            ToolExecutionContext context,
            CancellationToken cancellationToken
    ) {
        // Subagent 复用 QueryEngine；独立 WorkingMemory 避免共享主会话全文。
        QueryEngine queryEngine = new QueryEngine(
                llmClient,
                selectedTools,
                maxIterations,
                new ToolPermissionManager(context.permissionMode()),
                PreToolUseHandler.none(),
                StopHookHandler.none(),
                null,
                null,
                skillStore,
                context
        );
        return queryEngine.query(prompt, new WorkingMemory(), cancellationToken);
    }

    /**
     * 只读 agent 只能使用搜索、读取和 Bash。
     */
    private List<Tool> readOnlyTools() {
        return tools.stream()
                .filter(tool -> READ_ONLY_TOOLS.contains(tool.name()))
                .sorted(Comparator.comparingInt(tool -> toolOrder(tool.name())))
                .toList();
    }

    /**
     * Verifier 只验证，不修改源码。
     */
    private List<Tool> verifierTools() {
        return tools.stream()
                .filter(tool -> VERIFIER_TOOLS.contains(tool.name()))
                .sorted(Comparator.comparingInt(tool -> toolOrder(tool.name())))
                .toList();
    }

    private VerifierTarget verifierTarget(AgentInvocation invocation, BackgroundTaskRegistry registry) {
        if (invocation.targetAgentId().isBlank()) {
            return new VerifierTarget(projectRoot);
        }
        // /batch 的 Verifier 通过 target_agent_id 精确指向 Coder worktree。
        if (registry == null) {
            throw new IllegalArgumentException("target_agent_id requires background task registry");
        }
        BackgroundAgentTask.Snapshot target = registry.find(invocation.targetAgentId())
                .map(BackgroundAgentTask::snapshot)
                .orElseThrow(() -> new IllegalArgumentException("Target agent not found: " + invocation.targetAgentId()));
        if (!AgentDefinition.CODER.type().equals(target.agentType())) {
            throw new IllegalArgumentException("target_agent_id must reference a Coder task");
        }
        if (target.worktreePath() == null) {
            throw new IllegalArgumentException("Target Coder has no worktree yet: " + invocation.targetAgentId());
        }
        return new VerifierTarget(target.worktreePath());
    }

    private static int toolOrder(String name) {
        return switch (name) {
            case "Read" -> 0;
            case "Grep" -> 1;
            case "Glob" -> 2;
            case "Bash" -> 3;
            default -> 10;
        };
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record VerifierTarget(Path cwd) {
    }
}
