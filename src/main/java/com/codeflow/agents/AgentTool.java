package com.codeflow.agents;

import com.codeflow.core.CancellationToken;
import com.codeflow.tasks.BackgroundAgentTask;
import com.codeflow.tasks.BackgroundTaskRegistry;
import com.codeflow.tools.Tool;
import com.codeflow.worktree.WorktreeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.concurrent.ExecutorService;

public final class AgentTool implements Tool {
    public static final String TOOL_NAME = "Agent";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final SubagentRunner runner;
    private final BackgroundTaskRegistry registry;
    private final WorktreeService worktreeService;
    private final ExecutorService executorService;

    public AgentTool(
            SubagentRunner runner,
            BackgroundTaskRegistry registry,
            WorktreeService worktreeService,
            ExecutorService executorService
    ) {
        this.runner = runner;
        this.registry = registry;
        this.worktreeService = worktreeService;
        this.executorService = executorService;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return "Launch a Planner or Coder subagent. Coder runs in the background inside an isolated git worktree.";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "prompt": {
                      "type": "string",
                      "description": "Self-contained instructions for the subagent"
                    },
                    "subagent_type": {
                      "type": "string",
                      "enum": ["Planner", "Coder"],
                      "description": "Subagent type"
                    },
                    "description": {
                      "type": "string",
                      "description": "Short task description"
                    },
                    "batch_id": {
                      "type": "string",
                      "description": "Batch id when launched by /batch"
                    },
                    "unit_id": {
                      "type": "string",
                      "description": "Work unit id when launched by /batch"
                    },
                    "run_in_background": {
                      "type": "boolean",
                      "description": "Must be true for Coder"
                    },
                    "isolation": {
                      "type": "string",
                      "enum": ["worktree"],
                      "description": "Must be worktree for Coder"
                    }
                  },
                  "required": ["prompt"]
                }
                """;
    }

    @Override
    public ToolExecutionResult execute(String input) {
        return execute(input, CancellationToken.none());
    }

    @Override
    public ToolExecutionResult execute(String input, CancellationToken cancellationToken) {
        try {
            JsonNode node = objectMapper.readTree(input);
            String prompt = node.get("prompt").asText();
            String type = node.has("subagent_type") ? node.get("subagent_type").asText() : AgentDefinition.CODER.type();
            String description = node.has("description") ? node.get("description").asText() : "";
            String batchId = node.has("batch_id") ? node.get("batch_id").asText() : "manual";
            String unitId = node.has("unit_id") ? node.get("unit_id").asText() : "manual";

            if (AgentDefinition.PLANNER.type().equals(type)) {
                var result = runner.runPlanner(prompt, cancellationToken);
                return ToolExecutionResult.success(result.finalResponse());
            }

            if (!AgentDefinition.CODER.type().equals(type)) {
                return ToolExecutionResult.error("Unknown subagent_type: " + type);
            }
            boolean background = node.has("run_in_background") && node.get("run_in_background").asBoolean();
            String isolation = node.has("isolation") ? node.get("isolation").asText() : "";
            if (!background || !"worktree".equals(isolation)) {
                return ToolExecutionResult.error("Coder requires run_in_background=true and isolation=\"worktree\"");
            }

            BackgroundAgentTask task = runner.launchCoder(
                    new AgentInvocation(AgentDefinition.CODER, batchId, unitId, prompt, description),
                    registry,
                    worktreeService,
                    executorService
            );
            return ToolExecutionResult.success("""
                    Async agent launched successfully.
                    agentId: %s
                    status: async_launched
                    """.formatted(task.agentId()).strip());
        } catch (Exception e) {
            return ToolExecutionResult.error("Agent launch failed: " + e.getMessage());
        }
    }

    @Override
    public ValidationResult validateInput(String input) {
        try {
            JsonNode node = objectMapper.readTree(input);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("input must be a JSON object");
            }
            Set<String> allowed = Set.of(
                    "prompt",
                    "subagent_type",
                    "description",
                    "batch_id",
                    "unit_id",
                    "run_in_background",
                    "isolation"
            );
            for (String field : iterable(node.fieldNames())) {
                if (!allowed.contains(field)) {
                    throw new IllegalArgumentException("unknown parameter: " + field);
                }
            }
            requireText(node, "prompt");
            optionalText(node, "description");
            optionalText(node, "batch_id");
            optionalText(node, "unit_id");
            optionalBoolean(node, "run_in_background");
            optionalEnum(node, "subagent_type", Set.of("Planner", "Coder"));
            optionalEnum(node, "isolation", Set.of("worktree"));
            return ValidationResult.valid();
        } catch (Exception e) {
            return ValidationResult.invalid(e.getMessage());
        }
    }

    private static Iterable<String> iterable(java.util.Iterator<String> iterator) {
        return () -> iterator;
    }

    private static void requireText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("missing or invalid text parameter: " + field);
        }
    }

    private static void optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && !value.isTextual()) {
            throw new IllegalArgumentException("invalid text parameter: " + field);
        }
    }

    private static void optionalBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && !value.isBoolean()) {
            throw new IllegalArgumentException("invalid boolean parameter: " + field);
        }
    }

    private static void optionalEnum(JsonNode node, String field, Set<String> allowed) {
        JsonNode value = node.get(field);
        if (value == null) {
            return;
        }
        if (!value.isTextual() || !allowed.contains(value.asText())) {
            throw new IllegalArgumentException("invalid enum parameter: " + field);
        }
    }
}
