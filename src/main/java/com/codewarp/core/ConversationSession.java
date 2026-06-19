package com.codewarp.core;

import com.codewarp.memory.MemoryReflection;
import com.codewarp.memory.TranscriptRecorder;

import java.util.List;
import java.util.function.Consumer;

/**
 * Coordinates one terminal conversation session around L4 working memory.
 */
public final class ConversationSession {

    private final QueryEngine queryEngine;
    private final WorkingMemory workingMemory;
    private final Consumer<List<Message>> memoryReflector;
    private final TranscriptRecorder transcriptRecorder;

    public ConversationSession(QueryEngine queryEngine, MemoryReflection memoryReflection) {
        this(
                queryEngine,
                new WorkingMemory(),
                memoryReflection == null ? messages -> { } : memoryReflection::reflect,
                TranscriptRecorder.disabled()
        );
    }

    public ConversationSession(
            QueryEngine queryEngine,
            MemoryReflection memoryReflection,
            TranscriptRecorder transcriptRecorder
    ) {
        this(
                queryEngine,
                new WorkingMemory(),
                memoryReflection == null ? messages -> { } : memoryReflection::reflect,
                transcriptRecorder
        );
    }

    public ConversationSession(
            QueryEngine queryEngine,
            WorkingMemory workingMemory,
            Consumer<List<Message>> memoryReflector
    ) {
        this(queryEngine, workingMemory, memoryReflector, TranscriptRecorder.disabled());
    }

    public ConversationSession(
            QueryEngine queryEngine,
            WorkingMemory workingMemory,
            Consumer<List<Message>> memoryReflector,
            TranscriptRecorder transcriptRecorder
    ) {
        if (queryEngine == null) {
            throw new IllegalArgumentException("queryEngine must not be null");
        }
        this.queryEngine = queryEngine;
        this.workingMemory = workingMemory == null ? new WorkingMemory() : workingMemory;
        this.memoryReflector = memoryReflector == null ? messages -> { } : memoryReflector;
        this.transcriptRecorder = transcriptRecorder == null ? TranscriptRecorder.disabled() : transcriptRecorder;
    }

    public QueryEngine.QueryResult handleUserInput(String input) {
        QueryEngine.QueryResult result = queryEngine.query(input, workingMemory);
        transcriptRecorder.record(result.turnMessages());
        if (result.stopReason() == QueryEngine.QueryResult.StopReason.COMPLETED) {
            memoryReflector.accept(result.turnMessages());
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
