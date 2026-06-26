package com.codeflow.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public record BatchPlan(
        String batchId,
        String overallGoal,
        String findings,
        String validationRecipe,
        List<BatchWorkUnit> workUnits,
        Instant createdAt
) {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    public BatchPlan {
        if (batchId == null || batchId.isBlank()) {
            batchId = "batch-" + ID_TIME.format(Instant.now());
        }
        overallGoal = overallGoal == null ? "" : overallGoal;
        findings = findings == null ? "" : findings;
        validationRecipe = validationRecipe == null ? "" : validationRecipe;
        workUnits = workUnits == null ? List.of() : List.copyOf(workUnits);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public static BatchPlan parsePlannerResponse(String plannerResponse, String fallbackGoal) {
        try {
            String json = extractJsonObject(plannerResponse);
            JsonNode root = objectMapper.readTree(json);
            List<BatchWorkUnit> units = parseWorkUnits(root);
            if (units.isEmpty()) {
                throw new IllegalArgumentException("Planner returned no workUnits");
            }
            return new BatchPlan(
                    textAny(root, "batchId", "batch_id"),
                    textAnyOr(root, fallbackGoal, "overallGoal", "overall_goal"),
                    textAny(root, "findings"),
                    textAny(root, "validationRecipe", "validation_recipe"),
                    units,
                    Instant.now()
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("Planner did not return a valid batch plan JSON: " + e.getMessage(), e);
        }
    }

    public String renderForApproval() {
        StringBuilder builder = new StringBuilder();
        builder.append("Batch plan: ").append(batchId).append('\n');
        builder.append("Goal: ").append(overallGoal).append("\n\n");
        if (!findings.isBlank()) {
            builder.append("Findings:\n").append(findings).append("\n\n");
        }
        builder.append("Work units:\n");
        for (int i = 0; i < workUnits.size(); i++) {
            BatchWorkUnit unit = workUnits.get(i);
            builder.append(i + 1).append(". ").append(unit.title()).append(" (").append(unit.unitId()).append(")\n");
            if (!unit.description().isBlank()) {
                builder.append("   ").append(unit.description()).append('\n');
            }
            if (!unit.targetFilesOrDirectories().isEmpty()) {
                builder.append("   files: ").append(String.join(", ", unit.targetFilesOrDirectories())).append('\n');
            }
        }
        if (!validationRecipe.isBlank()) {
            builder.append("\nValidation:\n").append(validationRecipe).append('\n');
        }
        return builder.toString().stripTrailing();
    }

    private static List<BatchWorkUnit> parseWorkUnits(JsonNode root) {
        JsonNode unitsNode = first(root, "workUnits", "work_units");
        List<BatchWorkUnit> units = new ArrayList<>();
        if (unitsNode == null || !unitsNode.isArray()) {
            return units;
        }
        int index = 1;
        for (JsonNode node : unitsNode) {
            String unitId = textAny(node, "unitId", "unit_id");
            if (unitId.isBlank()) {
                unitId = "unit-" + index;
            }
            units.add(new BatchWorkUnit(
                    unitId,
                    textOr(node, "title", unitId),
                    textAny(node, "description"),
                    textArray(node, "targetFilesOrDirectories", "target_files_or_directories", "files"),
                    textAny(node, "expectedChange", "expected_change"),
                    textAny(node, "validationInstructions", "validation_instructions")
            ));
            index++;
        }
        return units;
    }

    private static String extractJsonObject(String text) {
        if (text == null) {
            throw new IllegalArgumentException("empty response");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("no JSON object found");
        }
        return text.substring(start, end + 1);
    }

    private static String textOr(JsonNode node, String name, String fallback) {
        JsonNode value = node.get(name);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    private static String textAny(JsonNode node, String... names) {
        JsonNode value = first(node, names);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    private static String textAnyOr(JsonNode node, String fallback, String... names) {
        JsonNode value = first(node, names);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    private static List<String> textArray(JsonNode node, String... names) {
        JsonNode value = first(node, names);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (item.isTextual() && !item.asText().isBlank()) {
                result.add(item.asText());
            }
        }
        return result;
    }

    private static JsonNode first(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }
}
