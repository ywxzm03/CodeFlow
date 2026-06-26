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

/**
 * Agent 工具入口，负责校验参数并启动对应 subagent。
 */
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
        return "Launch an Explorer, Planner, Coder, or Verifier subagent. All can run foreground or background; Coder uses an isolated git worktree.";
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
                      "enum": ["Explorer", "Planner", "Coder", "Verifier"],
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
                      "description": "Run the agent in the background. Defaults: Planner foreground, Explorer/Coder/Verifier background"
                    },
                    "isolation": {
                      "type": "string",
                      "enum": ["worktree"],
                      "description": "Required for Coder; rejected for Explorer, Planner, and Verifier"
                    },
                    "target_agent_id": {
                      "type": "string",
                      "description": "Verifier target Coder agent id. If omitted, Verifier checks the main project directory"
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

    /**
     * 启动前台 agent 时同步等待；后台 agent 只返回任务 id。
     */
    @Override
    public ToolExecutionResult execute(String input, CancellationToken cancellationToken) {
        try {
            JsonNode node = objectMapper.readTree(input);
            String prompt = node.get("prompt").asText();
            String type = node.has("subagent_type") ? node.get("subagent_type").asText() : AgentDefinition.CODER.type();
            AgentDefinition agent = AgentDefinition.byType(type);
            String description = node.has("description") ? node.get("description").asText() : "";
            String batchId = node.has("batch_id") ? node.get("batch_id").asText() : "manual";
            String unitId = node.has("unit_id") ? node.get("unit_id").asText() : "manual";
            boolean background = node.has("run_in_background") ? node.get("run_in_background").asBoolean() : agent.background();
            String isolation = node.has("isolation") ? node.get("isolation").asText() : (agent.worktreeIsolation() ? "worktree" : "");
            String targetAgentId = node.has("target_agent_id") ? node.get("target_agent_id").asText() : "";
            validateCombination(agent, isolation, targetAgentId);

            // 参数校验后统一封装，避免前后台路径解析出不同默认值。
            AgentInvocation invocation = new AgentInvocation(
                    agent,
                    batchId,
                    unitId,
                    prompt,
                    description,
                    background,
                    isolation,
                    targetAgentId
            );

            if (!background) {
                var result = runner.runForeground(invocation, registry, worktreeService, cancellationToken);
                return ToolExecutionResult.success(result.finalResponse());
            }

            BackgroundAgentTask task = runner.launchBackground(
                    invocation,
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
                    "isolation",
                    "target_agent_id"
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
            optionalText(node, "target_agent_id");
            optionalBoolean(node, "run_in_background");
            optionalEnum(node, "subagent_type", Set.of("Explorer", "Planner", "Coder", "Verifier"));
            optionalEnum(node, "isolation", Set.of("worktree"));
            String type = node.has("subagent_type") ? node.get("subagent_type").asText() : AgentDefinition.CODER.type();
            AgentDefinition agent = AgentDefinition.byType(type);
            String isolation = node.has("isolation") ? node.get("isolation").asText() : (agent.worktreeIsolation() ? "worktree" : "");
            String targetAgentId = node.has("target_agent_id") ? node.get("target_agent_id").asText() : "";
            validateCombination(agent, isolation, targetAgentId);
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

    /**
     * 校验 agent 类型、隔离模式、目标 agent 的组合约束。
     */
    private static void validateCombination(AgentDefinition agent, String isolation, String targetAgentId) {
        if (AgentDefinition.CODER.type().equals(agent.type())) {
            if (!"worktree".equals(isolation)) {
                throw new IllegalArgumentException("Coder requires isolation=\"worktree\"");
            }
            if (targetAgentId != null && !targetAgentId.isBlank()) {
                throw new IllegalArgumentException("target_agent_id is only valid for Verifier");
            }
            return;
        }
        if (isolation != null && !isolation.isBlank()) {
            throw new IllegalArgumentException(agent.type() + " does not support isolation=\"worktree\"");
        }
        if (!AgentDefinition.VERIFIER.type().equals(agent.type()) && targetAgentId != null && !targetAgentId.isBlank()) {
            throw new IllegalArgumentException("target_agent_id is only valid for Verifier");
        }
    }
}
