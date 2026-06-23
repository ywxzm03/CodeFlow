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

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;

public final class SubagentRunner {
    private static final Set<String> PLANNER_TOOLS = Set.of("Read", "Grep", "Glob");

    private final LLMClient llmClient;
    private final List<Tool> tools;
    private final int maxIterations;
    private final SkillStore skillStore;

    public SubagentRunner(
            LLMClient llmClient,
            List<Tool> tools,
            int maxIterations,
            SkillStore skillStore
    ) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
        this.tools = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
        this.maxIterations = maxIterations;
        this.skillStore = skillStore;
    }

    public QueryEngine.QueryResult runPlanner(String prompt, CancellationToken cancellationToken) {
        QueryEngine queryEngine = new QueryEngine(
                llmClient,
                readOnlyTools(),
                maxIterations,
                new ToolPermissionManager(PermissionMode.FULL_ACCESS),
                null,
                null,
                skillStore
        );
        return queryEngine.query(prompt, new WorkingMemory(), cancellationToken);
    }

    public BackgroundAgentTask launchCoder(
            AgentInvocation invocation,
            BackgroundTaskRegistry registry,
            WorktreeService worktreeService,
            ExecutorService executorService
    ) {
        String agentId = WorktreeService.newAgentId();
        BackgroundAgentTask task = registry.register(
                invocation.batchId(),
                agentId,
                invocation.unitId(),
                invocation.description()
        );
        executorService.submit(() -> runCoder(invocation, task, registry, worktreeService));
        return task;
    }

    private void runCoder(
            AgentInvocation invocation,
            BackgroundAgentTask task,
            BackgroundTaskRegistry registry,
            WorktreeService worktreeService
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
            QueryEngine queryEngine = new QueryEngine(
                    llmClient,
                    tools,
                    maxIterations,
                    new ToolPermissionManager(PermissionMode.BATCH_WORKER),
                    PreToolUseHandler.none(),
                    StopHookHandler.none(),
                    null,
                    null,
                    skillStore,
                    context
            );
            QueryEngine.QueryResult queryResult = queryEngine.query(
                    invocation.prompt(),
                    new WorkingMemory(),
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

    private List<Tool> readOnlyTools() {
        return tools.stream()
                .filter(tool -> PLANNER_TOOLS.contains(tool.name()))
                .toList();
    }
}
