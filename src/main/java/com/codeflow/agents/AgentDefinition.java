package com.codeflow.agents;

public record AgentDefinition(
        String type,
        String description,
        boolean background,
        boolean worktreeIsolation
) {
    public static final AgentDefinition EXPLORER = new AgentDefinition(
            "Explorer",
            "Foreground by default read-only exploration subagent for searching and understanding code",
            false,
            false
    );

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

    public static final AgentDefinition VERIFIER = new AgentDefinition(
            "Verifier",
            "Background by default verification subagent that proves changes work without editing source files",
            true,
            false
    );

    public static AgentDefinition byType(String type) {
        if (type == null || type.isBlank()) {
            return CODER;
        }
        return switch (type) {
            case "Explorer" -> EXPLORER;
            case "Planner" -> PLANNER;
            case "Coder" -> CODER;
            case "Verifier" -> VERIFIER;
            default -> throw new IllegalArgumentException("Unknown subagent_type: " + type);
        };
    }
}
