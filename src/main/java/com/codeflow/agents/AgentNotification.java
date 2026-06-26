package com.codeflow.agents;

import com.codeflow.tasks.BackgroundAgentTask;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 后台 subagent 完成后交给主会话的结构化通知。
 */
public record AgentNotification(
        String agentId,
        String agentType,
        String displayName,
        String batchId,
        String unitId,
        String description,
        BackgroundAgentTask.Status status,
        String resultSummary,
        String testSummary,
        String verdict,
        String failureReason,
        Path worktreePath,
        String branchName,
        String commitSha,
        Path logPath,
        Instant completedAt
) {
    public AgentNotification {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        agentType = valueOr(agentType, "Agent");
        displayName = valueOr(displayName, agentType);
        batchId = valueOr(batchId, "manual");
        unitId = valueOr(unitId, "manual");
        description = valueOr(description, "");
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        resultSummary = valueOr(resultSummary, "");
        testSummary = valueOr(testSummary, "");
        verdict = valueOr(verdict, "");
        failureReason = valueOr(failureReason, "");
        branchName = valueOr(branchName, "");
        commitSha = valueOr(commitSha, "");
    }

    public static AgentNotification fromSnapshot(BackgroundAgentTask.Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        return new AgentNotification(
                snapshot.agentId(),
                snapshot.agentType(),
                snapshot.displayName(),
                snapshot.batchId(),
                snapshot.unitId(),
                snapshot.description(),
                snapshot.status(),
                snapshot.resultSummary(),
                snapshot.testSummary(),
                snapshot.verdict(),
                snapshot.failureReason(),
                snapshot.worktreePath(),
                snapshot.branchName(),
                snapshot.commitSha(),
                snapshot.logPath(),
                snapshot.completedAt()
        );
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
