package com.codeflow.agents;

public record AgentDefinition(
        String type,
        String description,
        boolean background,
        boolean worktreeIsolation
) {
    public static final AgentDefinition PLANNER = new AgentDefinition(
            "Planner",
            "Foreground planning subagent that researches and produces a batch plan",
            false,
            false
    );

    public static final AgentDefinition CODER = new AgentDefinition(
            "Coder",
            "Background coding subagent that works inside an isolated git worktree",
            true,
            true
    );
}
