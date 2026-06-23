package com.codeflow.compact;

import com.codeflow.core.WorkingMemory;
import com.codeflow.core.CancellationToken;
import com.codeflow.llm.LLMClient;
import com.codeflow.memory.TranscriptRecorder;
import com.codeflow.memory.TranscriptStore;
import com.codeflow.tools.Tool;

import java.util.List;

/**
 * 上下文超限后的兜底压缩器。
 */
public final class ReactiveCompactor {

    private final CompactionPolicy policy;
    private final TokenEstimator tokenEstimator;
    private final LLMClient llmClient;
    private final TranscriptRecorder transcriptRecorder;
    private final TranscriptStore transcriptStore;

    public ReactiveCompactor(
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

    public Result compact(String systemPrompt, WorkingMemory workingMemory, List<Tool> tools, int retryCount) {
        return compact(systemPrompt, workingMemory, tools, retryCount, CancellationToken.none());
    }

    public Result compact(
            String systemPrompt,
            WorkingMemory workingMemory,
            List<Tool> tools,
            int retryCount,
            CancellationToken cancellationToken
    ) {
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        token.throwIfCancelled();
        if (!policy.enabled() || workingMemory == null || !transcriptRecorder.enabled() || transcriptStore == null) {
            return Result.notCompacted();
        }
        if (workingMemory.size() == 0) {
            return Result.notCompacted();
        }

        long estimatedTokens = tokenEstimator.estimate(systemPrompt, workingMemory.snapshot(), tools);
        // reactive：只保留最近热消息，压缩力度最大；冷数据为空也照常压缩。
        CompactionSupport.CompactionOutcome outcome = CompactionSupport.applyCompaction(
                workingMemory,
                transcriptRecorder,
                transcriptStore,
                llmClient,
                "reactive",
                "context_overflow_error",
                estimatedTokens,
                policy.reactiveCompactHotMessages(),
                retryCount,
                false,
                false,
                token
        );
        return new Result(outcome.compacted(), outcome.boundaryUuid(), outcome.messageCount());
    }

    public record Result(boolean compacted, String boundaryUuid, int messageCount) {
        public static Result notCompacted() {
            return new Result(false, null, 0);
        }
    }
}
