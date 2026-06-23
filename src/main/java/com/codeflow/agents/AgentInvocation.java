package com.codeflow.agents;

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

    public AgentInvocation(
            AgentDefinition agent,
            String batchId,
            String unitId,
            String prompt,
            String description
    ) {
        this(agent, batchId, unitId, prompt, description, agent.background(), agent.worktreeIsolation() ? "worktree" : "", "");
    }

    public String displayName() {
        if (description != null && !description.isBlank()) {
            return description;
        }
        return agent.type();
    }
}
