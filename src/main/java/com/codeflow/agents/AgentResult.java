package com.codeflow.agents;

import java.nio.file.Path;

/**
 * subagent 执行结果的结构化摘要。
 */
public record AgentResult(
        String agentId,
        String agentType,
        String batchId,
        String unitId,
        Status status,
        String finalResponse,
        String branchName,
        String commitSha,
        String testSummary,
        String verdict,
        String resultSummary,
        String failureReason,
        Path worktreePath
) {
    /**
     * 结果状态只表达执行结论，不表达后台任务生命周期。
     */
    public enum Status {
        SUCCESS,
        FAILED,
        CANCELLED
    }
}
