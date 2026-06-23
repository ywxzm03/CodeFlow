package com.codeflow.batch;

import com.codeflow.agents.AgentDefinition;
import com.codeflow.agents.AgentInvocation;
import com.codeflow.agents.SubagentRunner;
import com.codeflow.core.CancellationToken;
import com.codeflow.core.QueryEngine;
import com.codeflow.tasks.BackgroundAgentTask;
import com.codeflow.tasks.BackgroundTaskRegistry;
import com.codeflow.worktree.WorktreeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

public final class BatchCoordinator {
    private final SubagentRunner subagentRunner;
    private final BackgroundTaskRegistry taskRegistry;
    private final WorktreeService worktreeService;
    private final ExecutorService executorService;
    private final Path projectRoot;
    private final Map<String, BatchPlan> plans = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public BatchCoordinator(
            SubagentRunner subagentRunner,
            BackgroundTaskRegistry taskRegistry,
            WorktreeService worktreeService,
            ExecutorService executorService,
            Path projectRoot
    ) {
        this.subagentRunner = subagentRunner;
        this.taskRegistry = taskRegistry;
        this.worktreeService = worktreeService;
        this.executorService = executorService;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public BatchPlan preparePlan(String instruction, CancellationToken cancellationToken) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("Provide an instruction for /batch.");
        }
        try {
            worktreeService.requireGitRoot();
        } catch (Exception e) {
            throw new IllegalStateException("/batch requires a git repository: " + e.getMessage(), e);
        }

        QueryEngine.QueryResult result = subagentRunner.runPlanner(plannerPrompt(instruction), cancellationToken);
        BatchPlan plan = BatchPlan.parsePlannerResponse(result.finalResponse(), instruction);
        plans.put(plan.batchId(), plan);
        persistPlan(plan);
        return plan;
    }

    public List<BackgroundAgentTask.Snapshot> launchWorkers(BatchPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("plan must not be null");
        }
        plans.put(plan.batchId(), plan);
        persistPlan(plan);
        for (BatchWorkUnit unit : plan.workUnits()) {
            AgentInvocation coderInvocation = new AgentInvocation(
                    AgentDefinition.CODER,
                    plan.batchId(),
                    unit.unitId(),
                    coderPrompt(plan, unit),
                    unit.title()
            );
            AgentInvocation verifierInvocation = new AgentInvocation(
                    AgentDefinition.VERIFIER,
                    plan.batchId(),
                    unit.unitId(),
                    verifierPrompt(plan, unit),
                    "Verify " + unit.title(),
                    true,
                    "",
                    ""
            );
            subagentRunner.launchCoderWithVerifier(
                    coderInvocation,
                    verifierInvocation,
                    taskRegistry,
                    worktreeService,
                    executorService
            );
        }
        return taskRegistry.listBatch(plan.batchId());
    }

    public String formatRunningAgents() {
        List<BackgroundAgentTask.Snapshot> running = taskRegistry.listRunning();
        if (running.isEmpty()) {
            return "No running agents.";
        }
        StringBuilder builder = new StringBuilder("Running agents:\n\n");
        for (BackgroundAgentTask.Snapshot task : running) {
            builder.append("- ").append(task.displayName()).append('\n');
        }
        return builder.toString().stripTrailing();
    }

    public String formatStatus(String batchId) {
        if (batchId != null && !batchId.isBlank()) {
            return formatSingleBatchStatus(batchId.trim());
        }

        Map<String, List<BackgroundAgentTask.Snapshot>> byBatch = new LinkedHashMap<>();
        for (BackgroundAgentTask.Snapshot snapshot : taskRegistry.listAll()) {
            byBatch.computeIfAbsent(snapshot.batchId(), ignored -> new java.util.ArrayList<>()).add(snapshot);
        }
        if (byBatch.isEmpty()) {
            return "No batch tasks found.";
        }

        StringBuilder builder = new StringBuilder("Batches:\n\n");
        for (Map.Entry<String, List<BackgroundAgentTask.Snapshot>> entry : byBatch.entrySet()) {
            builder.append(formatBatchHeader(entry.getKey(), entry.getValue())).append('\n');
            builder.append(formatWorkerTable(entry.getValue())).append("\n\n");
        }
        return builder.toString().stripTrailing();
    }

    public void shutdown() {
        executorService.shutdownNow();
    }

    private String formatSingleBatchStatus(String batchId) {
        List<BackgroundAgentTask.Snapshot> tasks = taskRegistry.listBatch(batchId);
        if (tasks.isEmpty()) {
            return "Batch not found: " + batchId;
        }
        return formatBatchHeader(batchId, tasks) + "\n" + formatWorkerTable(tasks);
    }

    private String formatBatchHeader(String batchId, List<BackgroundAgentTask.Snapshot> tasks) {
        BatchPlan plan = plans.get(batchId);
        long success = tasks.stream().filter(t -> t.status() == BackgroundAgentTask.Status.SUCCESS).count();
        long failed = tasks.stream().filter(t -> t.status() == BackgroundAgentTask.Status.FAILED).count();
        long running = tasks.stream().filter(t -> t.status() == BackgroundAgentTask.Status.RUNNING || t.status() == BackgroundAgentTask.Status.QUEUED).count();
        return "%s  %d/%d success, %d failed, %d running\nGoal: %s".formatted(
                batchId,
                success,
                tasks.size(),
                failed,
                running,
                plan == null ? "-" : plan.overallGoal()
        );
    }

    private String formatWorkerTable(List<BackgroundAgentTask.Snapshot> tasks) {
        StringBuilder builder = new StringBuilder();
        builder.append("# | Unit | Agent | Status | Branch | Commit | Verdict | Tests | Worktree\n");
        builder.append("--|------|-------|--------|--------|--------|---------|-------|---------\n");
        List<BackgroundAgentTask.Snapshot> sorted = tasks.stream()
                .sorted(Comparator.comparing(BackgroundAgentTask.Snapshot::unitId)
                        .thenComparing(BackgroundAgentTask.Snapshot::agentType))
                .toList();
        for (int i = 0; i < sorted.size(); i++) {
            BackgroundAgentTask.Snapshot task = sorted.get(i);
            builder.append(i + 1).append(" | ")
                    .append(task.unitId()).append(" | ")
                    .append(task.agentType()).append(" | ")
                    .append(task.status()).append(" | ")
                    .append(value(task.branchName())).append(" | ")
                    .append(value(task.commitSha())).append(" | ")
                    .append(value(task.verdict())).append(" | ")
                    .append(value(task.testSummary())).append(" | ")
                    .append(task.worktreePath() == null ? "-" : task.worktreePath())
                    .append('\n');
        }
        return builder.toString().stripTrailing();
    }

    private void persistPlan(BatchPlan plan) {
        try {
            Path dir = projectRoot.resolve(".codeflow").resolve("tasks").resolve("batches");
            Files.createDirectories(dir);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(plan.batchId() + ".json").toFile(), plan);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist batch plan " + plan.batchId(), e);
        }
    }

    private static String plannerPrompt(String instruction) {
        return """
                You are Planner, a foreground synchronous planning subagent for CodeFlow.
                Research the codebase using only read/search tools. Do not modify files.

                User batch instruction:
                %s

                Return only one JSON object with this shape:
                {
                  "overallGoal": "...",
                  "findings": "...",
                  "validationRecipe": "...",
                  "workerPromptTemplate": "...",
                  "workUnits": [
                    {
                      "unitId": "unit-1",
                      "title": "...",
                      "description": "...",
                      "targetFilesOrDirectories": ["..."],
                      "expectedChange": "...",
                      "validationInstructions": "..."
                    }
                  ]
                }

                Split into independently implementable work units. Prefer 2-8 units unless the task is tiny.
                """.formatted(instruction);
    }

    private static String coderPrompt(BatchPlan plan, BatchWorkUnit unit) {
        return """
                You are Coder, a background coding subagent running in an isolated git worktree.

                Overall batch goal:
                %s

                Assigned unit:
                %s (%s)

                Unit description:
                %s

                Target files or directories:
                %s

                Expected change:
                %s

                Validation:
                %s

                Rules:
                - Work only in this worktree.
                - Re-read files before editing.
                - Run validation commands.
                - If validation passes, commit your changes locally.
                - Do not push.
                - Do not create a PR.
                - Do not merge or cherry-pick.

                End with exactly these fields:
                STATUS: success|failed
                BRANCH: <branch name>
                COMMIT: <sha or none>
                TESTS: <commands and results>
                SUMMARY: <short change summary>
                FAILURE_REASON: <reason or none>
                """.formatted(
                plan.overallGoal(),
                unit.title(),
                unit.unitId(),
                unit.description(),
                unit.targetFilesOrDirectories().isEmpty() ? "-" : String.join(", ", unit.targetFilesOrDirectories()),
                unit.expectedChange(),
                unit.validationInstructions().isBlank() ? plan.validationRecipe() : unit.validationInstructions()
        );
    }

    private static String verifierPrompt(BatchPlan plan, BatchWorkUnit unit) {
        return """
                You are Verifier, an independent verification subagent for CodeFlow.

                Verify the completed Coder work for this batch unit. You are running in the Coder worktree.
                Do not modify project source files. Do not commit, push, create PRs, merge, or cherry-pick.
                You may run build, test, lint, typecheck, and read-only inspection commands.

                Overall batch goal:
                %s

                Unit:
                %s (%s)

                Expected change:
                %s

                Validation recipe:
                %s

                Run concrete verification commands and inspect the results. Try at least one edge or regression-oriented check when practical.

                End with exactly these fields:
                STATUS: success|failed
                VERDICT: PASS|FAIL|PARTIAL
                COMMANDS: <commands run>
                EVIDENCE: <important outputs>
                SUMMARY: <short verification summary>
                FAILURE_REASON: <reason or none>
                """.formatted(
                plan.overallGoal(),
                unit.title(),
                unit.unitId(),
                unit.expectedChange(),
                unit.validationInstructions().isBlank() ? plan.validationRecipe() : unit.validationInstructions()
        );
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
