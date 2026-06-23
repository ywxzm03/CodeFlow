package com.codeflow.agents;

public record AgentInvocation(
        AgentDefinition agent,
        String batchId,
        String unitId,
        String prompt,
        String description
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
    }
}
