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

        assertEquals(List.of("Read", "Grep", "Glob"), client.lastToolNames);
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
        assertTrue(Files.isDirectory(snapshot.worktreePath()));
        assertTrue(snapshot.branchName().startsWith("codeflow-agent-"));
    }

    private static List<Tool> allTools() {
        return List.of(
                new ReadTool(),
                new WriteTool(),
                new EditTool(),
                new BashTool(),
                new GrepTool(),
                new GlobTool()
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

        private CapturingClient(String response) {
            this.response = response;
        }

        @Override
        public LLMResponse call(String systemPrompt, List<Message> messages, List<Tool> tools) {
            lastToolNames = tools.stream().map(Tool::name).toList();
            return new LLMResponse(response, List.of(), null);
        }

        @Override
        public Flux<StreamEvent> callStreaming(String systemPrompt, List<Message> messages, List<Tool> tools) {
            lastToolNames = tools.stream().map(Tool::name).toList();
            return Flux.just(new StreamEvent.TextDelta(response));
        }

        @Override
        public void setModel(String model) {
        }
    }
}
