package com.codeflow.agents;

import com.codeflow.tasks.BackgroundAgentTask;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentNotificationQueueTest {

    @Test
    void drainsQueuedNotificationsInOrder() {
        AgentNotificationQueue queue = new AgentNotificationQueue();
        AgentNotification first = notification("agent-a");
        AgentNotification second = notification("agent-b");

        queue.enqueue(first);
        queue.enqueue(second);

        assertTrue(queue.hasPending());
        assertEquals(List.of(first, second), queue.drainReady());
        assertFalse(queue.hasPending());
        assertEquals(List.of(), queue.drainReady());
    }

    @Test
    void ignoresDuplicateAgentId() {
        AgentNotificationQueue queue = new AgentNotificationQueue();

        queue.enqueue(notification("agent-a"));
        queue.enqueue(notification("agent-a"));

        assertEquals(1, queue.drainReady().size());
    }

    @Test
    void handlesConcurrentEnqueue() throws Exception {
        AgentNotificationQueue queue = new AgentNotificationQueue();
        int count = 40;
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(8);

        for (int index = 0; index < count; index++) {
            int agentIndex = index;
            executor.submit(() -> {
                start.await();
                queue.enqueue(notification("agent-" + agentIndex));
                return null;
            });
        }

        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(count, queue.drainReady().size());
    }

    @Test
    void createsNotificationFromTaskSnapshot() {
        BackgroundAgentTask task = new BackgroundAgentTask(
                "task-1",
                "batch-1",
                "agent-a",
                "Coder",
                "Unit 1",
                "unit-1",
                "",
                "Do work",
                Path.of("agent-a.jsonl")
        );
        task.markRunning(Path.of("/tmp/worktree"), "codeflow-agent-a");
        task.markSuccess("abc123", "changed code", "tests pass", "PASS");

        AgentNotification notification = AgentNotification.fromSnapshot(task.snapshot());

        assertEquals("agent-a", notification.agentId());
        assertEquals("Coder", notification.agentType());
        assertEquals("Unit 1", notification.displayName());
        assertEquals("batch-1", notification.batchId());
        assertEquals("unit-1", notification.unitId());
        assertEquals(BackgroundAgentTask.Status.SUCCESS, notification.status());
        assertEquals("changed code", notification.resultSummary());
        assertEquals("tests pass", notification.testSummary());
        assertEquals("PASS", notification.verdict());
        assertEquals("abc123", notification.commitSha());
        assertEquals(Path.of("/tmp/worktree"), notification.worktreePath());
        assertEquals("codeflow-agent-a", notification.branchName());
        assertEquals(Path.of("agent-a.jsonl"), notification.logPath());
        assertTrue(notification.completedAt().isBefore(Instant.now().plusSeconds(1)));
    }

    private static AgentNotification notification(String agentId) {
        return new AgentNotification(
                agentId,
                "Coder",
                "Unit",
                "batch",
                "unit",
                "description",
                BackgroundAgentTask.Status.SUCCESS,
                "summary",
                "tests",
                "PASS",
                "",
                null,
                "",
                "",
                null,
                Instant.now()
        );
    }
}
