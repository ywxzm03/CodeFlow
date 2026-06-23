package com.codeflow.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundTaskRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void registersPersistsAndListsBatchTasks() {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry(tempDir);

        BackgroundAgentTask task = registry.register("batch-1", "agent-a", "unit-1", "Do work");
        task.markRunning(tempDir.resolve(".codeflow/worktrees/agent-a"), "codeflow-agent-a");
        task.markSuccess("abc1234", "changed files", "./gradlew test PASS");
        registry.persist(task);

        assertTrue(Files.exists(tempDir.resolve(".codeflow/tasks/agents/agent-a.json")));
        assertEquals(1, registry.listBatch("batch-1").size());
        BackgroundAgentTask.Snapshot snapshot = registry.listBatch("batch-1").getFirst();
        assertEquals(BackgroundAgentTask.Status.SUCCESS, snapshot.status());
        assertEquals("abc1234", snapshot.commitSha());
    }

    @Test
    void cancelsQueuedTaskAndKeepsMetadata() {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry(tempDir);
        registry.register("batch-1", "agent-a", "unit-1", "Do work");

        boolean cancelled = registry.cancel("agent-a");

        assertTrue(cancelled);
        assertEquals(BackgroundAgentTask.Status.CANCELLED, registry.find("agent-a").orElseThrow().snapshot().status());
        assertTrue(Files.exists(tempDir.resolve(".codeflow/tasks/agents/agent-a.json")));
    }

    @Test
    void cancelUnknownTaskReturnsFalse() {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry(tempDir);

        assertFalse(registry.cancel("missing"));
    }
}
