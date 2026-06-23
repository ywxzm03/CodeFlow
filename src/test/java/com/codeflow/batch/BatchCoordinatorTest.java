package com.codeflow.batch;

import com.codeflow.agents.SubagentRunner;
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
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchCoordinatorTest {

    @TempDir
    Path tempDir;

    @Test
    void launchesVerifierAfterSuccessfulCoder() throws Exception {
        initRepo(tempDir);
        SequentialClient client = new SequentialClient(List.of(
                """
                        STATUS: success
                        BRANCH: codeflow-agent-a
                        COMMIT: abc123
                        TESTS: ./gradlew test PASS
                        SUMMARY: implemented unit
                        FAILURE_REASON: none
                        """,
                """
                        STATUS: success
                        VERDICT: PASS
                        COMMANDS: ./gradlew test
                        EVIDENCE: tests passed
                        SUMMARY: verified unit
                        FAILURE_REASON: none
                        """
        ));
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry(tempDir);
        var executor = Executors.newCachedThreadPool();
        BatchCoordinator coordinator = coordinator(client, registry, executor);

        coordinator.launchWorkers(plan());

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        List<BackgroundAgentTask.Snapshot> tasks = registry.listBatch("batch-test");
        assertEquals(2, tasks.size());

        BackgroundAgentTask.Snapshot coder = taskOfType(tasks, "Coder");
        BackgroundAgentTask.Snapshot verifier = taskOfType(tasks, "Verifier");
        assertEquals(BackgroundAgentTask.Status.SUCCESS, coder.status());
        assertEquals("abc123", coder.commitSha());
        assertEquals(BackgroundAgentTask.Status.SUCCESS, verifier.status());
        assertEquals("PASS", verifier.verdict());
        assertEquals(coder.agentId(), verifier.targetAgentId());
        assertEquals(coder.worktreePath(), verifier.worktreePath());
        assertTrue(coordinator.formatStatus("batch-test").contains("Verifier"));
        assertTrue(coordinator.formatStatus("batch-test").contains("PASS"));
    }

    @Test
    void skipsVerifierWhenCoderFails() throws Exception {
        initRepo(tempDir);
        SequentialClient client = new SequentialClient(List.of("""
                STATUS: failed
                COMMIT: none
                TESTS: ./gradlew test FAIL
                SUMMARY: failed unit
                FAILURE_REASON: tests failed
                """));
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry(tempDir);
        var executor = Executors.newCachedThreadPool();
        BatchCoordinator coordinator = coordinator(client, registry, executor);

        coordinator.launchWorkers(plan());

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        List<BackgroundAgentTask.Snapshot> tasks = registry.listBatch("batch-test");
        assertEquals(1, tasks.size());
        assertEquals("Coder", tasks.getFirst().agentType());
        assertEquals(BackgroundAgentTask.Status.FAILED, tasks.getFirst().status());
    }

    @Test
    void formatsRunningAgentNamesOnly() {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry(tempDir);
        registry.register("manual", "agent-a", "Explorer", "Search auth flow", "manual", "", "Search auth flow");
        BatchCoordinator coordinator = coordinator(new SequentialClient(List.of()), registry, Executors.newSingleThreadExecutor());

        String output = coordinator.formatRunningAgents();

        assertEquals("Running agents:\n\n- Search auth flow", output);
    }

    private BatchCoordinator coordinator(LLMClient client, BackgroundTaskRegistry registry, java.util.concurrent.ExecutorService executor) {
        return new BatchCoordinator(
                new SubagentRunner(client, tools(), 1, null, tempDir),
                registry,
                new WorktreeService(tempDir),
                executor,
                tempDir
        );
    }

    private static BatchPlan plan() {
        return new BatchPlan(
                "batch-test",
                "test batch",
                "findings",
                "./gradlew test",
                "",
                List.of(new BatchWorkUnit(
                        "unit-1",
                        "Unit 1",
                        "Do unit one",
                        List.of("src"),
                        "Change unit one",
                        "./gradlew test"
                )),
                null
        );
    }

    private static BackgroundAgentTask.Snapshot taskOfType(List<BackgroundAgentTask.Snapshot> tasks, String type) {
        return tasks.stream()
                .filter(task -> type.equals(task.agentType()))
                .findFirst()
                .orElseThrow();
    }

    private static List<Tool> tools() {
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
        Files.writeString(dir.resolve("README.md"), "hello\n");
        git(dir, "init");
        git(dir, "config", "user.email", "test@example.com");
        git(dir, "config", "user.name", "Test User");
        git(dir, "add", "README.md");
        git(dir, "commit", "-m", "initial");
    }

    private static void git(Path cwd, String... args) throws Exception {
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
    }

    private static java.util.List<String> buildCommand(String... args) {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        return command;
    }

    private static final class SequentialClient implements LLMClient {
        private final Queue<String> responses;

        private SequentialClient(List<String> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public LLMResponse call(String systemPrompt, List<Message> messages, List<Tool> tools) {
            return new LLMResponse(next(), List.of(), null);
        }

        @Override
        public Flux<StreamEvent> callStreaming(String systemPrompt, List<Message> messages, List<Tool> tools) {
            return Flux.just(new StreamEvent.TextDelta(next()));
        }

        @Override
        public void setModel(String model) {
        }

        private String next() {
            String response = responses.poll();
            assertNotNull(response, "No queued LLM response");
            return response;
        }
    }
}
