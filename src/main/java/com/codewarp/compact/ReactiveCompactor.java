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
        if (!policy.enabled() || workingMemory == null || !transcriptRecorder.enabled() || transcriptStore == null) {
            return Result.notCompacted();
        }

        List<Message> before = workingMemory.snapshot();
        if (before.isEmpty()) {
            return Result.notCompacted();
        }
        transcriptRecorder.recordUnpersisted(workingMemory);

        long estimatedTokens = tokenEstimator.estimate(systemPrompt, before, tools);
        List<Message> preserved = CompactionSupport.preservedMessages(before, policy.reactiveCompactHotMessages(), false);
        List<Message> cold = before.stream()
                .filter(message -> !preserved.contains(message))
                .toList();
        String rawSummary = CompactionSupport.summarize(llmClient, cold);
        Message.User summaryMessage = new Message.User(CompactionSupport.summaryContent(rawSummary, transcriptRecorder.transcriptPath()));
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
                            "reactive",
                            "context_overflow_error",
                            estimatedTokens,
                            policy.reactiveCompactHotMessages(),
                            retryCount,
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
            throw new IllegalStateException("写入 reactive compact transcript 失败: " + e.getMessage(), e);
        }
    }

    public record Result(boolean compacted, String boundaryUuid, int messageCount) {
        public static Result notCompacted() {
            return new Result(false, null, 0);
        }
    }
}
