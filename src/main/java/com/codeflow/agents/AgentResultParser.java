package com.codeflow.agents;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class AgentResultParser {
    private AgentResultParser() {
    }

    static AgentResult parse(
            String agentId,
            String batchId,
            String unitId,
            String finalResponse,
            String expectedBranch,
            Path worktreePath
    ) {
        return parseCoder(agentId, batchId, unitId, finalResponse, expectedBranch, worktreePath);
    }

    static AgentResult parseCoder(
            String agentId,
            String batchId,
            String unitId,
            String finalResponse,
            String expectedBranch,
            Path worktreePath
    ) {
        Map<String, String> fields = fields(finalResponse);
        String statusText = fields.getOrDefault("STATUS", "failed").trim().toLowerCase(Locale.ROOT);
        String commit = fields.getOrDefault("COMMIT", "none").trim();
        String summary = fields.getOrDefault("SUMMARY", "").trim();
        String tests = fields.getOrDefault("TESTS", "").trim();
        String failureReason = fields.getOrDefault("FAILURE_REASON", "").trim();
        String branch = fields.getOrDefault("BRANCH", expectedBranch).trim();

        boolean success = "success".equals(statusText)
                && !commit.isBlank()
                && !"none".equalsIgnoreCase(commit)
                && !"null".equalsIgnoreCase(commit);
        AgentResult.Status status = success ? AgentResult.Status.SUCCESS : AgentResult.Status.FAILED;
        if (!success && failureReason.isBlank()) {
            failureReason = "Coder did not report success with a commit";
        }

        return new AgentResult(
                agentId,
                AgentDefinition.CODER.type(),
                batchId,
                unitId,
                status,
                finalResponse == null ? "" : finalResponse,
                branch,
                commit,
                tests,
                "",
                summary,
                failureReason,
                worktreePath
        );
    }

    static AgentResult parseGeneric(
            AgentDefinition agent,
            String agentId,
            String batchId,
            String unitId,
            String finalResponse,
            Path cwd
    ) {
        Map<String, String> fields = fields(finalResponse);
        String statusText = fields.getOrDefault("STATUS", "success").trim().toLowerCase(Locale.ROOT);
        String failureReason = fields.getOrDefault("FAILURE_REASON", "").trim();
        String verdict = fields.getOrDefault("VERDICT", "").trim().toUpperCase(Locale.ROOT);
        String summary = fields.getOrDefault("SUMMARY", "").trim();
        if (summary.isBlank()) {
            summary = switch (agent.type()) {
                case "Explorer" -> fields.getOrDefault("FINDINGS", "").trim();
                case "Planner" -> fields.getOrDefault("PLAN", "").trim();
                case "Verifier" -> fields.getOrDefault("EVIDENCE", "").trim();
                default -> "";
            };
        }
        String tests = fields.getOrDefault("COMMANDS", "").trim();
        if (tests.isBlank()) {
            tests = fields.getOrDefault("TESTS", "").trim();
        }

        boolean success = "success".equals(statusText);
        if (AgentDefinition.VERIFIER.type().equals(agent.type())) {
            success = success && ("PASS".equals(verdict) || "FAIL".equals(verdict) || "PARTIAL".equals(verdict));
            if (!success && failureReason.isBlank()) {
                failureReason = "Verifier did not report VERDICT: PASS, FAIL, or PARTIAL";
            }
        } else if (!success && failureReason.isBlank()) {
            failureReason = agent.type() + " reported failure";
        }

        return new AgentResult(
                agentId,
                agent.type(),
                batchId,
                unitId,
                success ? AgentResult.Status.SUCCESS : AgentResult.Status.FAILED,
                finalResponse == null ? "" : finalResponse,
                "",
                "",
                tests,
                verdict,
                summary,
                failureReason,
                cwd
        );
    }

    private static Map<String, String> fields(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return fields;
        }
        for (String line : text.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toUpperCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (key.matches("[A-Z_]+")) {
                fields.putIfAbsent(key, value);
            }
        }
        return fields;
    }
}
