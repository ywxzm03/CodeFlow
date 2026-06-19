package com.codewarp.tools;

import com.codewarp.memory.TranscriptSearchResult;
import com.codewarp.memory.TranscriptStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

public class TranscriptSearchTool implements Tool {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final TranscriptStore transcriptStore;

    public TranscriptSearchTool(TranscriptStore transcriptStore) {
        this.transcriptStore = transcriptStore;
    }

    @Override
    public String name() {
        return "TranscriptSearch";
    }

    @Override
    public String description() {
        return "Search full L5 session transcripts by keyword when current working memory lacks historical details";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "session_id": {
                      "type": "string",
                      "description": "Transcript session id to search"
                    },
                    "keyword": {
                      "type": "string",
                      "description": "Required keyword to search in transcript messages"
                    },
                    "limit": {
                      "type": "integer",
                      "description": "Maximum results to return, default 20, max 100"
                    }
                  },
                  "required": ["session_id", "keyword"]
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
            JsonNode inputNode = objectMapper.readTree(input);
            String sessionId = inputNode.get("session_id").asText();
            String keyword = inputNode.get("keyword").asText();
            int limit = limit(inputNode);
            List<TranscriptSearchResult> results = transcriptStore.search(sessionId, keyword, limit);
            return ToolExecutionResult.success(formatResults(sessionId, keyword, results));
        } catch (Exception e) {
            return ToolExecutionResult.error("搜索 transcript 失败: " + e.getMessage());
        }
    }

    @Override
    public ValidationResult validateInput(String input) {
        try {
            JsonNode inputNode = ToolInputValidator.parseObject(input);
            ToolInputValidator.rejectUnknownFields(inputNode, Set.of("session_id", "keyword", "limit"));
            ToolInputValidator.requireText(inputNode, "session_id");
            ToolInputValidator.requireText(inputNode, "keyword");
            transcriptStore.validateSessionId(inputNode.get("session_id").asText());
            limit(inputNode);
            return ValidationResult.valid();
        } catch (IllegalArgumentException e) {
            return ValidationResult.invalid(e.getMessage());
        }
    }

    private static int limit(JsonNode inputNode) {
        JsonNode value = inputNode.get("limit");
        if (value == null || value.isNull()) {
            return DEFAULT_LIMIT;
        }
        if (!value.isInt()) {
            throw new IllegalArgumentException("参数必须是整数: limit");
        }
        int limit = value.asInt();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit 必须在 1 到 " + MAX_LIMIT + " 之间");
        }
        return limit;
    }

    private static String formatResults(String sessionId, String keyword, List<TranscriptSearchResult> results) {
        if (results.isEmpty()) {
            return "No transcript matches for session " + sessionId + " and keyword: " + keyword;
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Transcript matches for session ")
                .append(sessionId)
                .append(" and keyword ")
                .append(keyword)
                .append(':')
                .append(System.lineSeparator());
        for (TranscriptSearchResult result : results) {
            builder.append("- [")
                    .append(result.timestamp())
                    .append("] ")
                    .append(result.role())
                    .append(" ")
                    .append(result.uuid())
                    .append(": ")
                    .append(result.content())
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }
}
