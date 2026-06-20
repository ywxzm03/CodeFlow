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
import java.util.List;

/**
 * 自动上下文压缩器。
 */
public final class AutoCompactor {

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

        // snip 已释放的部分不再计入 auto 触发阈值。
        long estimatedTokens = Math.max(0, tokenEstimator.estimate(systemPrompt, workingMemory.snapshot(), tools) - tokensFreed);
        if (estimatedTokens < policy.autoCompactThresholdTokens()) {
            return Result.notCompacted();
        }
        return compact(workingMemory, estimatedTokens);
    }

    /**
     * 手动触发 auto compact，不检查 token 阈值。
     */
    public ForceResult forceCompact(String systemPrompt, WorkingMemory workingMemory, List<Tool> tools) {
        if (!policy.enabled()) {
            return ForceResult.unavailable("compaction is disabled");
        }
        if (workingMemory == null) {
            return ForceResult.unavailable("working memory is unavailable");
        }
        if (!transcriptRecorder.enabled() || transcriptStore == null) {
            return ForceResult.unavailable("transcript is disabled");
        }
        if (workingMemory.size() == 0) {
            return ForceResult.notNeeded();
        }

        long estimatedTokens = tokenEstimator.estimate(systemPrompt, workingMemory.snapshot(), tools);
        Result result = compact(workingMemory, estimatedTokens, "manual_command");
        if (!result.compacted()) {
            return ForceResult.notNeeded();
        }
        return ForceResult.compacted(result.boundaryUuid(), result.messageCount());
    }

    private Result compact(WorkingMemory workingMemory, long estimatedTokensBefore) {
        return compact(workingMemory, estimatedTokensBefore, "token_threshold");
    }

    private Result compact(WorkingMemory workingMemory, long estimatedTokensBefore, String reason) {
        List<Message> before = workingMemory.snapshot();
        if (before.isEmpty()) {
            return Result.notCompacted();
        }
        // auto 保留热消息和关键消息，其余冷数据写成摘要。
        List<Message> preserved = CompactionSupport.preservedMessages(before, policy.autoCompactHotMessages(), true);
        List<Message> cold = before.stream()
                .filter(message -> !preserved.contains(message))
                .toList();
        if (cold.isEmpty()) {
            return Result.notCompacted();
        }

        // 保证压缩前的完整 L4 已写入 L5。
        transcriptRecorder.recordUnpersisted(workingMemory);

        String rawSummary = CompactionSupport.summarize(llmClient, cold);
        Message.User summaryMessage = new Message.User(CompactionSupport.summaryContent(rawSummary, transcriptRecorder.transcriptPath()));
        List<Message> after = new ArrayList<>();
        after.add(summaryMessage);
        after.addAll(preserved);

        List<WorkingMemory.Entry> originalEntries = workingMemory.snapshotEntries();
        workingMemory.rollbackTo(0);
        after.forEach(workingMemory::append);
        try {
            // boundary 之后的消息就是 resume 需要恢复的新 L4。
            String boundaryUuid = transcriptStore.appendCompactBoundary(
                    transcriptRecorder.sessionId(),
                    new TranscriptRecord.CompactBoundary(
                            "auto",
                            reason,
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
        return CompactionSupport.preservedMessages(messages, hotMessageCount, keepKeywordMessages);
    }

    public record Result(boolean compacted, String boundaryUuid, int messageCount) {
        public static Result notCompacted() {
            return new Result(false, null, 0);
        }
    }

    public record ForceResult(Status status, String reason, String boundaryUuid, int messageCount) {
        public static ForceResult compacted(String boundaryUuid, int messageCount) {
            return new ForceResult(Status.COMPACTED, "", boundaryUuid, messageCount);
        }

        public static ForceResult notNeeded() {
            return new ForceResult(Status.NOT_NEEDED, "", null, 0);
        }

        public static ForceResult unavailable(String reason) {
            return new ForceResult(Status.UNAVAILABLE, reason, null, 0);
        }
    }

    public enum Status {
        COMPACTED,
        NOT_NEEDED,
        UNAVAILABLE
    }
}
