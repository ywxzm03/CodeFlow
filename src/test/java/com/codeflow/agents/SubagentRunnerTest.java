package com.codeflow.agents;

import com.codeflow.core.Message;
import com.codeflow.llm.LLMClient;
import com.codeflow.tasks.BackgroundAgentTask;
import com.codeflow.tasks.BackgroundTaskRegistry;
import com.codeflow.tools.BashTool;
import com.codeflow.tools.EditTool;
import com.codeflow.tools.GlobTool;
import com.codeflow.tools.GrepTool;
import com.codeflow.tools.ReadTool;
import com.codeflow.tools.Tool;
import com.codeflow.tools.WriteTool;
import com.codeflow.worktree.WorktreeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void plannerOnlyReceivesReadOnlyTools() {
        CapturingClient client = new CapturingClient("plan");
        SubagentRunner runner = new SubagentRunner(client, allTools(), 1, null);

        runner.runPlanner("make a plan", null);

        assertEquals(List.of("Read", "Grep", "Glob", "Bash", "Skill", "MemoryRead"), client.lastToolNames);
        assertTrue(client.lastSystemPrompt.contains("You are Planner"));
    }

    @Test
    void explorerOnlyReceivesReadOnlyTools() {
        CapturingClient client = new CapturingClient("findings");
        SubagentRunner runner = new SubagentRunner(client, allTools(), 1, null, tempDir);

        runner.runExplorer("search code", null);

        assertEquals(List.of("Read", "Grep", "Glob", "Bash", "Skill", "MemoryRead"), client.lastToolNames);
        assertTrue(client.lastSystemPrompt.contains("You are Explorer"));
    }

    @Test
    void verifierReceivesVerifierSystemPromptAndReadOnlyMemoryTools() {
        CapturingClient client = new CapturingClient("""
                STATUS: success
                VERDICT: PASS
                COMMANDS: ./gradlew test
                SUMMARY: ok
                FAILURE_REASON: none
                """);
        SubagentRunner runner = new SubagentRunner(client, allTools(), 1, null, tempDir);
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry(tempDir);

        runner.runForeground(
                new AgentInvocation(AgentDefinition.VERIFIER, "manual", "manual", "verify", "Verify", false, "", ""),
                registry,
                new WorktreeService(tempDir),
                null
        );

        assertEquals(List.of("Read", "Grep", "Glob", "Bash", "Skill", "MemoryRead"), client.lastToolNames);
        assertTrue(client.lastSystemPrompt.contains("You are Verifier"));
    }

    @Test
    void coderRunsInBackgroundWorktreeAndUpdatesTask() throws Exception {
        initRepo(tempDir);
        Files.writeString(tempDir.resolve("README.md"), "hello\n");
        git(tempDir, "add", "README.md");
        git(tempDir, "commit", "-m", "initial");

        CapturingClient client = new CapturingClient("""
                STATUS: success
                COMMIT: abc123
                VERDICT: PASS
                TESTS: ./gradlew test PASS
                SUMMARY: changed worker files
                FAILURE_REASON: none
                """);
        SubagentRunner runner = new SubagentRunner(client, allTools(), 1, null);
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry(tempDir);
        WorktreeService worktreeService = new WorktreeService(tempDir);
        var executor = Executors.newSingleThreadExecutor();

        BackgroundAgentTask task = runner.launchCoder(
                new AgentInvocation(AgentDefinition.CODER, "batch-1", "unit-1", "do work", "Unit 1"),
                registry,
                worktreeService,
                executor
        );

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        BackgroundAgentTask.Snapshot snapshot = registry.find(task.agentId()).orElseThrow().snapshot();

        assertEquals(BackgroundAgentTask.Status.SUCCESS, snapshot.status());
        assertEquals("abc123", snapshot.commitSha());
        assertEquals("PASS", snapshot.verdict());
        assertTrue(Files.isDirectory(snapshot.worktreePath()));
        assertTrue(snapshot.branchName().startsWith("codeflow-agent-"));
        assertTrue(client.lastSystemPrompt.contains("You are Coder"));
    }

    @Test
    void coderCompletionEnqueuesNotification() throws Exception {
        initRepo(tempDir);
        Files.writeString(tempDir.resolve("README.md"), "hello\n");
        git(tempDir, "add", "README.md");
        git(tempDir, "commit", "-m", "initial");

        CapturingClient client = new CapturingClient("""
                STATUS: success
                COMMIT: abc123
                VERDICT: PASS
                TESTS: ./gradlew test PASS
                SUMMARY: changed worker files
                FAILURE_REASON: none
                """);
        AgentNotificationQueue notificationQueue = new AgentNotificationQueue();
        SubagentRunner runner = new SubagentRunner(client, allTools(), 1, null, tempDir, notificationQueue);
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry(tempDir);
        WorktreeService worktreeService = new WorktreeService(tempDir);
        var executor = Executors.newSingleThreadExecutor();

        BackgroundAgentTask task = runner.launchCoder(
                new AgentInvocation(AgentDefinition.CODER, "batch-1", "unit-1", "do work", "Unit 1"),
                registry,
                worktreeService,
                executor
        );

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        List<AgentNotification> notifications = notificationQueue.drainReady();

        assertEquals(1, notifications.size());
        AgentNotification notification = notifications.getFirst();
        assertEquals(task.agentId(), notification.agentId());
        assertEquals("Coder", notification.agentType());
        assertEquals("batch-1", notification.batchId());
        assertEquals("unit-1", notification.unitId());
        assertEquals("Unit 1", notification.displayName());
        assertEquals(BackgroundAgentTask.Status.SUCCESS, notification.status());
        assertEquals("abc123", notification.commitSha());
        assertEquals("PASS", notification.verdict());
        assertEquals("changed worker files", notification.resultSummary());
        assertTrue(Files.isDirectory(notification.worktreePath()));
    }

    @Test
    void readOnlyBackgroundCompletionEnqueuesNotification() throws Exception {
        CapturingClient client = new CapturingClient("""
                STATUS: success
                FINDINGS: found entrypoint
                SUMMARY: found entrypoint
                FAILURE_REASON: none
                """);
        AgentNotificationQueue notificationQueue = new AgentNotificationQueue();
        SubagentRunner runner = new SubagentRunner(client, allTools(), 1, null, tempDir, notificationQueue);
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry(tempDir);
        var executor = Executors.newSingleThreadExecutor();

        BackgroundAgentTask task = runner.launchBackground(
                new AgentInvocation(AgentDefinition.EXPLORER, "manual", "manual", "search", "Search entrypoint", true, "", ""),
                registry,
                new WorktreeService(tempDir),
                executor
        );

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        List<AgentNotification> notifications = notificationQueue.drainReady();

        assertEquals(1, notifications.size());
        AgentNotification notification = notifications.getFirst();
        assertEquals(task.agentId(), notification.agentId());
        assertEquals("Explorer", notification.agentType());
        assertEquals("Search entrypoint", notification.displayName());
        assertEquals(BackgroundAgentTask.Status.SUCCESS, notification.status());
        assertEquals("found entrypoint", notification.resultSummary());
    }

    private static List<Tool> allTools() {
        return List.of(
                new ReadTool(),
                new WriteTool(),
                new EditTool(),
                new BashTool(),
                new GrepTool(),
                new GlobTool(),
                new NamedTool("Skill"),
                new NamedTool("MemoryRead")
        );
    }

    private static void initRepo(Path dir) throws Exception {
        git(dir, "init");
        git(dir, "config", "user.email", "test@example.com");
        git(dir, "config", "user.name", "Test User");
    }

    private static String git(Path cwd, String... args) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(buildCommand(args));
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes());
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("git timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(output);
        }
        return output;
    }

    private static java.util.List<String> buildCommand(String... args) {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        return command;
    }

    private static final class CapturingClient implements LLMClient {
        private final String response;
        private List<String> lastToolNames = List.of();
        private String lastSystemPrompt = "";

        private CapturingClient(String response) {
            this.response = response;
        }

        @Override
        public LLMResponse call(String systemPrompt, List<Message> messages, List<Tool> tools) {
            lastSystemPrompt = systemPrompt;
            lastToolNames = tools.stream().map(Tool::name).toList();
            return new LLMResponse(response, List.of(), null);
        }

        @Override
        public Flux<StreamEvent> callStreaming(String systemPrompt, List<Message> messages, List<Tool> tools) {
            lastSystemPrompt = systemPrompt;
            lastToolNames = tools.stream().map(Tool::name).toList();
            return Flux.just(new StreamEvent.TextDelta(response));
        }

        @Override
        public void setModel(String model) {
        }
    }

    private record NamedTool(String name) implements Tool {
        @Override
        public String description() {
            return name + " test tool";
        }

        @Override
        public String inputSchema() {
            return "{}";
        }

        @Override
        public ToolExecutionResult execute(String input) {
            return ToolExecutionResult.success("");
        }
    }
}
