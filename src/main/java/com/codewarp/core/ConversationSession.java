package com.codewarp.core;

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

    public ConversationSession(
            QueryEngine queryEngine,
            MemoryReflection memoryReflection,
            TranscriptRecorder transcriptRecorder
    ) {
        this.queryEngine = Objects.requireNonNull(queryEngine, "queryEngine must not be null");
        this.workingMemory = new WorkingMemory();
        this.memoryReflection = memoryReflection;
        this.transcriptRecorder = Objects.requireNonNull(transcriptRecorder, "transcriptRecorder must not be null");
    }

    public QueryEngine.QueryResult handleUserInput(String input) {
        int startIndex = workingMemory.size();
        QueryEngine.QueryResult result = queryEngine.query(input, workingMemory);
        transcriptRecorder.recordUnpersisted(workingMemory);
        if (result.stopReason() == QueryEngine.QueryResult.StopReason.COMPLETED && memoryReflection != null) {
            memoryReflection.reflect(result.turnMessages());
        }
        return result;
    }

    public void resume(String sessionId, List<WorkingMemory.Entry> entries) {
        transcriptRecorder.switchSession(sessionId);
        workingMemory.restore(entries);
    }

    public void clear() {
        workingMemory.clear();
    }

    public QueryEngine.CompactResult compact() {
        return queryEngine.compact(workingMemory);
    }

    public WorkingMemory workingMemory() {
        return workingMemory;
    }

    public String transcriptSessionId() {
        return transcriptRecorder.sessionId();
    }
}
