package com.codewarp.core;

import com.codewarp.compact.SnipCompactor;
import com.codewarp.memory.MemoryReflection;
import com.codewarp.memory.TranscriptRecorder;

import java.util.List;
import java.util.Objects;

/**
 * 终端会话协调器，维护 L4 工作记忆。
 * 同时负责触发 L5 记录和 L2/L3 反思。
 */
public final class ConversationSession {

    private final QueryEngine queryEngine;
    private final WorkingMemory workingMemory;
    private final MemoryReflection memoryReflection;
    private final TranscriptRecorder transcriptRecorder;
    private final SnipCompactor snipCompactor;

    public ConversationSession(
            QueryEngine queryEngine,
            MemoryReflection memoryReflection,
            TranscriptRecorder transcriptRecorder,
            SnipCompactor snipCompactor
    ) {
        this.queryEngine = Objects.requireNonNull(queryEngine, "queryEngine must not be null");
        this.workingMemory = new WorkingMemory();
        this.memoryReflection = memoryReflection;
        this.transcriptRecorder = Objects.requireNonNull(transcriptRecorder, "transcriptRecorder must not be null");
        this.snipCompactor = snipCompactor;
    }

    public QueryEngine.QueryResult handleUserInput(String input) {
        if (snipCompactor != null) {
            snipCompactor.compact(workingMemory);
        }
        int startIndex = workingMemory.size();
        QueryEngine.QueryResult result = queryEngine.query(input, workingMemory);
        List<String> uuids = transcriptRecorder.recordWithUuids(result.turnMessages());
        for (int i = 0; i < uuids.size() && i < result.turnMessages().size(); i++) {
            workingMemory.markTranscriptUuid(startIndex + i, uuids.get(i));
        }
        if (result.stopReason() == QueryEngine.QueryResult.StopReason.COMPLETED && memoryReflection != null) {
            memoryReflection.reflect(result.turnMessages());
        }
        return result;
    }

    public void resume(String sessionId, List<Message> messages) {
        transcriptRecorder.switchSession(sessionId);
        workingMemory.clear();
        if (messages == null) {
            return;
        }
        for (Message message : messages) {
            workingMemory.append(message);
        }
    }

    public void clear() {
        workingMemory.clear();
    }

    public WorkingMemory workingMemory() {
        return workingMemory;
    }

    public String transcriptSessionId() {
        return transcriptRecorder.sessionId();
    }
}
