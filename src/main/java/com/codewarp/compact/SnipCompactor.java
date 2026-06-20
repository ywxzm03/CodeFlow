package com.codewarp.compact;

import com.codewarp.core.Message;
import com.codewarp.core.WorkingMemory;
import com.codewarp.memory.TranscriptRecord;
import com.codewarp.memory.TranscriptRecorder;
import com.codewarp.memory.TranscriptStore;
import com.codewarp.util.Console;

import java.io.IOException;
import java.util.List;

/**
 * 工具结果 snip 压缩器。
 */
public final class SnipCompactor {

    private static final int DEFAULT_SUMMARY_LIMIT = 1200;

    private final int thresholdChars;
    private final TokenEstimator tokenEstimator;
    private final TranscriptRecorder transcriptRecorder;
    private final TranscriptStore transcriptStore;

    public SnipCompactor(
            int thresholdChars,
            TokenEstimator tokenEstimator,
            TranscriptRecorder transcriptRecorder,
            TranscriptStore transcriptStore
    ) {
        this.thresholdChars = thresholdChars;
        this.tokenEstimator = tokenEstimator;
        this.transcriptRecorder = transcriptRecorder;
        this.transcriptStore = transcriptStore;
    }

    public Result compact(WorkingMemory workingMemory) {
        if (workingMemory == null || transcriptStore == null || transcriptRecorder == null || !transcriptRecorder.enabled()) {
            return Result.empty();
        }

        long tokensFreed = 0;
        int compacted = 0;
        List<Message> messages = workingMemory.snapshot();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (!(message instanceof Message.ToolResult toolResult)) {
                continue;
            }
            String targetUuid = workingMemory.transcriptUuidAt(i);
            if (targetUuid == null || targetUuid.isBlank()) {
                continue;
            }
            if (toolResultLength(toolResult) <= thresholdChars || isSnipSummary(toolResult.content())) {
                continue;
            }

            String summary = summarize(toolResult, targetUuid);
            long freed = Math.max(0, tokenEstimator.roughText(toolResult.content()) - tokenEstimator.roughText(summary));
            TranscriptRecord.SnipCompact snip = new TranscriptRecord.SnipCompact(
                    targetUuid,
                    toolResult.toolUseId(),
                    "tool_result_summary",
                    thresholdChars,
                    toolResult.content().length(),
                    summary.length(),
                    freed,
                    summary
            );

            try {
                transcriptStore.appendSnipCompact(transcriptRecorder.sessionId(), snip);
                workingMemory.replace(i, new Message.ToolResult(toolResult.toolUseId(), summary, toolResult.isError()));
                tokensFreed += freed;
                compacted++;
            } catch (IOException | IllegalArgumentException e) {
                Console.warn("[Compact] 写入 snip metadata 失败，已保留原始工具结果: " + e.getMessage());
            }
        }
        return new Result(compacted, tokensFreed);
    }

    private int toolResultLength(Message.ToolResult toolResult) {
        return toolResult.toolUseId().length()
                + Boolean.toString(toolResult.isError()).length()
                + toolResult.content().length();
    }

    private boolean isSnipSummary(String content) {
        return content != null && content.startsWith("[CodeWarp snip compact]");
    }

    private String summarize(Message.ToolResult toolResult, String targetUuid) {
        String content = toolResult.content() == null ? "" : toolResult.content();
        String excerpt = content.length() <= DEFAULT_SUMMARY_LIMIT
                ? content
                : content.substring(0, DEFAULT_SUMMARY_LIMIT);
        return """
                [CodeWarp snip compact]
                This tool_result was summarized because it exceeded the %d-character threshold.
                target_uuid: %s
                tool_use_id: %s
                is_error: %s
                original_chars: %d
                full_content_transcript: %s

                Summary:
                %s
                """.formatted(
                thresholdChars,
                targetUuid,
                toolResult.toolUseId(),
                toolResult.isError(),
                content.length(),
                transcriptRecorder.transcriptPath(),
                excerpt
        ).strip();
    }

    public record Result(int compactedCount, long tokensFreed) {
        public static Result empty() {
            return new Result(0, 0);
        }
    }
}
