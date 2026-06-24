package com.codeflow.agents;

/**
 * 一次 subagent 调用请求。
 */
public record AgentInvocation(
        AgentDefinition agent,
        String batchId,
        String unitId,
        String prompt,
        String description,
        boolean runInBackground,
        String isolation,
        String targetAgentId
) {
    public AgentInvocation {
        if (agent == null) {
            throw new IllegalArgumentException("agent must not be null");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        batchId = batchId == null ? "" : batchId;
        unitId = unitId == null ? "" : unitId;
        description = description == null ? "" : description;
        isolation = isolation == null ? "" : isolation;
        targetAgentId = targetAgentId == null ? "" : targetAgentId;
    }

    /**
     * 手动调用时使用 agent 默认前后台和隔离策略。
     */
    public AgentInvocation(
            AgentDefinition agent,
            String batchId,
            String unitId,
            String prompt,
            String description
    ) {
        this(agent, batchId, unitId, prompt, description, agent.background(), agent.worktreeIsolation() ? "worktree" : "", "");
    }

    /**
     * 终端和任务列表展示用名称。
     */
    public String displayName() {
        if (description != null && !description.isBlank()) {
            return description;
        }
        return agent.type();
    }
}
