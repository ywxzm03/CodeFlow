package com.codeflow.compact;

import com.codeflow.core.WorkingMemory;
import com.codeflow.core.CancellationToken;
import com.codeflow.llm.LLMClient;
import com.codeflow.memory.TranscriptRecorder;
import com.codeflow.memory.TranscriptStore;
import com.codeflow.tools.Tool;

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
        return compactIfNeeded(systemPrompt, workingMemory, tools, tokensFreed, CancellationToken.none());
    }

    public Result compactIfNeeded(
            String systemPrompt,
            WorkingMemory workingMemory,
            List<Tool> tools,
            long tokensFreed,
            CancellationToken cancellationToken
    ) {
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        token.throwIfCancelled();
        if (!policy.enabled() || workingMemory == null || !transcriptRecorder.enabled() || transcriptStore == null) {
            return Result.notCompacted();
        }

        // snip 已释放的部分不再计入 auto 触发阈值。
        long estimatedTokens = Math.max(0, tokenEstimator.estimate(systemPrompt, workingMemory.snapshot(), tools) - tokensFreed);
        if (estimatedTokens < policy.autoCompactThresholdTokens()) {
            return Result.notCompacted();
        }
        return compact(workingMemory, estimatedTokens, "token_threshold", token);
    }

    /**
     * 手动触发 auto compact，不检查 token 阈值。
     */
    public ForceResult forceCompact(String systemPrompt, WorkingMemory workingMemory, List<Tool> tools) {
        return forceCompact(systemPrompt, workingMemory, tools, CancellationToken.none());
    }

    public ForceResult forceCompact(
            String systemPrompt,
            WorkingMemory workingMemory,
            List<Tool> tools,
            CancellationToken cancellationToken
    ) {
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        token.throwIfCancelled();
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
        Result result = compact(workingMemory, estimatedTokens, "manual_command", token);
        if (result.ioFailed()) {
            return ForceResult.unavailable("transcript 写入失败");
        }
        if (!result.compacted()) {
            return ForceResult.notNeeded();
        }
        return ForceResult.compacted(result.boundaryUuid(), result.messageCount());
    }

    private Result compact(
            WorkingMemory workingMemory,
            long estimatedTokensBefore,
            String reason,
            CancellationToken cancellationToken
    ) {
        // auto：保留关键消息 + 热消息；冷数据为空则不压缩。
        CompactionSupport.CompactionOutcome outcome = CompactionSupport.applyCompaction(
                workingMemory,
                transcriptRecorder,
                transcriptStore,
                llmClient,
                "auto",
                reason,
                estimatedTokensBefore,
                policy.autoCompactHotMessages(),
                0,
                true,
                true,
                cancellationToken
        );
        return new Result(outcome.compacted(), outcome.boundaryUuid(), outcome.messageCount(), outcome.ioFailed());
    }

    public record Result(boolean compacted, String boundaryUuid, int messageCount, boolean ioFailed) {
        public static Result notCompacted() {
            return new Result(false, null, 0, false);
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
