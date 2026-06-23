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
                batchId,
                unitId,
                status,
                finalResponse == null ? "" : finalResponse,
                branch,
                commit,
                tests,
                summary,
                failureReason,
                worktreePath
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
