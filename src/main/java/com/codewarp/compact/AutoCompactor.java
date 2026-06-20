package com.codewarp.compact;

import com.codewarp.core.Message;
import com.codewarp.core.WorkingMemory;
import com.codewarp.llm.LLMClient;
import com.codewarp.memory.TranscriptRecord;
import com.codewarp.memory.TranscriptRecorder;
import com.codewarp.memory.TranscriptStore;
import com.codewarp.tools.Tool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 自动上下文压缩器。
 */
public final class AutoCompactor {

    private static final String SYSTEM_PROMPT = """
            You summarize previous CodeWarp conversation context for continuation.
            Produce a concise but useful summary. Preserve decisions, constraints, file paths, commands, errors, fixes, tests, and pending tasks.
            Do not call tools.
            """;
    private static final List<String> KEYWORDS = List.of(
            "决定", "确认", "承诺", "必须", "不要", "以后", "默认", "配置", "路径", "key", "权限", "策略"
    );

    private final CompactionPolicy policy;
    private final TokenEstimator tokenEstimator;
    private final LLMClient llmClient;
    private final TranscriptRecorder transcriptRecorder;
    private final TranscriptStore transcriptStore;

    public AutoCompactor(
            CompactionPolicy policy,
            TokenEstimator tokenEstimator,
            LLMClient llmClient,
            TranscriptRecorder transcriptRecorder,
            TranscriptStore transcriptStore
    ) {
        this.policy = policy;
        this.tokenEstimator = tokenEstimator;
        this.llmClient = llmClient;
        this.transcriptRecorder = transcriptRecorder;
        this.transcriptStore = transcriptStore;
    }

    public Result compactIfNeeded(String systemPrompt, WorkingMemory workingMemory, List<Tool> tools, long tokensFreed) {
        if (!policy.enabled() || workingMemory == null || !transcriptRecorder.enabled() || transcriptStore == null) {
            return Result.notCompacted();
        }

        long estimatedTokens = Math.max(0, tokenEstimator.estimate(systemPrompt, workingMemory.snapshot(), tools) - tokensFreed);
        if (estimatedTokens < policy.autoCompactThresholdTokens()) {
            return Result.notCompacted();
        }
        return compact(workingMemory, estimatedTokens);
    }

    private Result compact(WorkingMemory workingMemory, long estimatedTokensBefore) {
        List<Message> before = workingMemory.snapshot();
        if (before.isEmpty()) {
            return Result.notCompacted();
        }
        transcriptRecorder.recordUnpersisted(workingMemory);

        List<Message> preserved = preservedMessages(before, policy.autoCompactHotMessages(), true);
        List<Message> cold = before.stream()
                .filter(message -> !preserved.contains(message))
                .toList();
        String rawSummary = summarize(cold);
        Message.User summaryMessage = new Message.User(summaryContent(rawSummary));
        List<Message> after = new ArrayList<>();
        after.add(summaryMessage);
        after.addAll(preserved);

        List<WorkingMemory.Entry> originalEntries = workingMemory.snapshotEntries();
        workingMemory.rollbackTo(0);
        after.forEach(workingMemory::append);
        try {
            String boundaryUuid = transcriptStore.appendCompactBoundary(
                    transcriptRecorder.sessionId(),
                    new TranscriptRecord.CompactBoundary(
                            "auto",
                            "token_threshold",
                            estimatedTokensBefore,
                            policy.autoCompactHotMessages(),
                            0,
                            null,
                            transcriptRecorder.transcriptPath()
                    )
            );
            List<String> uuids = transcriptStore.append(transcriptRecorder.sessionId(), after);
            for (int i = 0; i < uuids.size(); i++) {
                workingMemory.markTranscriptUuid(i, uuids.get(i));
            }
            return new Result(true, boundaryUuid, after.size());
        } catch (IOException | IllegalArgumentException e) {
            workingMemory.restore(originalEntries);
            throw new IllegalStateException("写入 auto compact transcript 失败: " + e.getMessage(), e);
        }
    }

    List<Message> preservedMessages(List<Message> messages, int hotMessageCount, boolean keepKeywordMessages) {
        Set<Message> preserved = new LinkedHashSet<>();
        if (keepKeywordMessages) {
            for (Message message : messages) {
                if (isKeyMessage(message)) {
                    preserved.add(message);
                }
            }
        }
        int start = Math.max(0, messages.size() - hotMessageCount);
        for (int i = start; i < messages.size(); i++) {
            preserved.add(messages.get(i));
        }
        return messages.stream().filter(preserved::contains).toList();
    }

    private boolean isKeyMessage(Message message) {
        String text = switch (message) {
            case Message.User user -> user.content();
            case Message.Assistant assistant -> assistant.content();
            case Message.ToolResult ignored -> "";
        };
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String keyword : KEYWORDS) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String summarize(List<Message> cold) {
        if (cold == null || cold.isEmpty()) {
            return "No older cold messages needed summarization.";
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("Summarize these earlier CodeWarp messages for future continuation.\n\n");
        for (Message message : cold) {
            prompt.append(formatMessage(message)).append("\n\n");
        }
        return llmClient.call(SYSTEM_PROMPT, List.of(new Message.User(prompt.toString())), List.of()).content();
    }

    private String summaryContent(String summary) {
        return """
                This session has been compacted. The summary below covers earlier conversation context.

                %s

                Full transcript path: %s
                If specific historical details are needed, search or read the transcript with narrow terms.
                """.formatted(summary, transcriptRecorder.transcriptPath()).strip();
    }

    private String formatMessage(Message message) {
        return switch (message) {
            case Message.User user -> "[user] " + user.content();
            case Message.Assistant assistant -> "[assistant] " + assistant.content() + formatToolUses(assistant.toolUses());
            case Message.ToolResult toolResult -> "[tool_result " + toolResult.toolUseId() + "] " + toolResult.content();
        };
    }

    private String formatToolUses(List<Message.ToolUse> toolUses) {
        if (toolUses == null || toolUses.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Message.ToolUse toolUse : toolUses) {
            builder.append("\n[tool_use ")
                    .append(toolUse.name())
                    .append("] ")
                    .append(toolUse.input());
        }
        return builder.toString();
    }

    public record Result(boolean compacted, String boundaryUuid, int messageCount) {
        public static Result notCompacted() {
            return new Result(false, null, 0);
        }
    }
}
