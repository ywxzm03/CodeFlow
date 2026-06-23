package com.codeflow.agents;

import java.nio.file.Path;

public record AgentResult(
        String agentId,
        String batchId,
        String unitId,
        Status status,
        String finalResponse,
        String branchName,
        String commitSha,
        String testSummary,
        String resultSummary,
        String failureReason,
        Path worktreePath
) {
    public enum Status {
        SUCCESS,
        FAILED,
        CANCELLED
    }
}
