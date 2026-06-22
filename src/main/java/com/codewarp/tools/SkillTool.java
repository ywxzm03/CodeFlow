package com.codewarp.tools;

import com.codewarp.skills.SkillDefinition;
import com.codewarp.skills.SkillRenderer;
import com.codewarp.skills.SkillStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.Set;

/**
 * 按需加载 skill 的工具。
 */
public class SkillTool implements Tool {

    public static final String TOOL_NAME = "Skill";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final SkillStore skillStore;
    private final SkillRenderer skillRenderer;

    public SkillTool(SkillStore skillStore, SkillRenderer skillRenderer) {
        this.skillStore = skillStore;
        this.skillRenderer = skillRenderer;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return "Load a CodeWarp skill by name when the available skills list matches the current task";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "skill": {
                      "type": "string",
                      "description": "Skill name, such as commit or review"
                    },
                    "args": {
                      "type": "string",
                      "description": "Optional arguments for the skill"
                    }
                  },
                  "required": ["skill"]
                }
                """;
    }

    @Override
    public boolean isConcurrencySafe() {
        return true;
    }

    @Override
    public ToolExecutionResult execute(String input) {
        try {
            SkillInvocation invocation = parseInvocation(input);
            Optional<SkillDefinition> skill = skillStore.find(invocation.skill());
            if (skill.isEmpty()) {
                return ToolExecutionResult.error("Unknown skill: " + invocation.skill());
            }
            return ToolExecutionResult.success("Skill loaded: " + skill.get().name());
        } catch (Exception e) {
            return ToolExecutionResult.error("加载 skill 失败: " + e.getMessage());
        }
    }

    @Override
    public ValidationResult validateInput(String input) {
        try {
            JsonNode inputNode = ToolInputValidator.parseObject(input);
            ToolInputValidator.rejectUnknownFields(inputNode, Set.of("skill", "args"));
            ToolInputValidator.requireText(inputNode, "skill");
            ToolInputValidator.optionalText(inputNode, "args");
            return ValidationResult.valid();
        } catch (IllegalArgumentException e) {
            return ValidationResult.invalid(e.getMessage());
        }
    }

    public Optional<String> renderInvocation(String input, String trigger) {
        SkillInvocation invocation = parseInvocation(input);
        return skillStore.find(invocation.skill())
                .map(skill -> skillRenderer.render(skill, invocation.args(), trigger));
    }

    public SkillStore skillStore() {
        return skillStore;
    }

    private static SkillInvocation parseInvocation(String input) {
        try {
            JsonNode inputNode = objectMapper.readTree(input);
            String skill = inputNode.get("skill").asText().strip();
            JsonNode argsNode = inputNode.get("args");
            String args = argsNode == null || argsNode.isNull() ? "" : argsNode.asText();
            return new SkillInvocation(skill, args);
        } catch (Exception e) {
            throw new IllegalArgumentException("Skill input is invalid: " + e.getMessage(), e);
        }
    }

    private record SkillInvocation(String skill, String args) {
    }
}
