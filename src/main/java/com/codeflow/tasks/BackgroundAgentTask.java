package com.codeflow.tasks;

import com.codeflow.core.CancellationToken;

import java.nio.file.Path;
import java.time.Instant;

public final class BackgroundAgentTask {
    private final String id;
    private final String batchId;
    private final String agentId;
    private final String unitId;
    private final String description;
    private final CancellationToken cancellationToken;

    private volatile Status status;
    private volatile Path worktreePath;
    private volatile String branchName;
    private volatile String commitSha;
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile Path logPath;
    private volatile String resultSummary;
    private volatile String testSummary;
    private volatile String failureReason;

    public BackgroundAgentTask(String id, String batchId, String agentId, String unitId, String description, Path logPath) {
        this.id = requireText(id, "id");
        this.batchId = requireText(batchId, "batchId");
        this.agentId = requireText(agentId, "agentId");
        this.unitId = requireText(unitId, "unitId");
        this.description = description == null ? "" : description;
        this.logPath = logPath;
        this.cancellationToken = CancellationToken.create();
        this.status = Status.QUEUED;
    }

    public String id() {
        return id;
    }

    public String batchId() {
        return batchId;
    }

    public String agentId() {
        return agentId;
    }

    public String unitId() {
        return unitId;
    }

    public String description() {
        return description;
    }

    public CancellationToken cancellationToken() {
        return cancellationToken;
    }

    public synchronized void markRunning(Path worktreePath, String branchName) {
        if (status == Status.CANCELLED) {
            return;
        }
        this.status = Status.RUNNING;
        this.startedAt = Instant.now();
        this.worktreePath = worktreePath;
        this.branchName = branchName;
    }

    public synchronized void markSuccess(String commitSha, String resultSummary, String testSummary) {
        this.status = Status.SUCCESS;
        this.commitSha = commitSha;
        this.resultSummary = resultSummary;
        this.testSummary = testSummary;
        this.completedAt = Instant.now();
    }

    public synchronized void markFailed(String failureReason) {
        this.status = Status.FAILED;
        this.failureReason = failureReason == null ? "" : failureReason;
        this.completedAt = Instant.now();
    }

    public synchronized void cancel() {
        if (status == Status.SUCCESS || status == Status.FAILED || status == Status.CANCELLED) {
            return;
        }
        cancellationToken.cancel("Cancelled by user");
        this.status = Status.CANCELLED;
        this.completedAt = Instant.now();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                id,
                batchId,
                agentId,
                unitId,
                description,
                status,
                worktreePath,
                branchName,
                commitSha,
                startedAt,
                completedAt,
                logPath,
                resultSummary,
                testSummary,
                failureReason
        );
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public enum Status {
        QUEUED,
        RUNNING,
        SUCCESS,
        FAILED,
        CANCELLED
    }

    public record Snapshot(
            String id,
            String batchId,
            String agentId,
            String unitId,
            String description,
            Status status,
            Path worktreePath,
            String branchName,
            String commitSha,
            Instant startedAt,
            Instant completedAt,
            Path logPath,
            String resultSummary,
            String testSummary,
            String failureReason
    ) {
    }
}
