package com.codewarp.core;

import com.codewarp.memory.MemoryReflection;

import java.util.List;
import java.util.function.Consumer;

/**
 * Coordinates one terminal conversation session around L4 working memory.
 */
public final class ConversationSession {

    private final QueryEngine queryEngine;
    private final WorkingMemory workingMemory;
    private final Consumer<List<Message>> memoryReflector;

    public ConversationSession(QueryEngine queryEngine, MemoryReflection memoryReflection) {
        this(
                queryEngine,
                new WorkingMemory(),
                memoryReflection == null ? messages -> { } : memoryReflection::reflect
        );
    }

    public ConversationSession(
            QueryEngine queryEngine,
            WorkingMemory workingMemory,
            Consumer<List<Message>> memoryReflector
    ) {
        if (queryEngine == null) {
            throw new IllegalArgumentException("queryEngine must not be null");
        }
        this.queryEngine = queryEngine;
        this.workingMemory = workingMemory == null ? new WorkingMemory() : workingMemory;
        this.memoryReflector = memoryReflector == null ? messages -> { } : memoryReflector;
    }

    public QueryEngine.QueryResult handleUserInput(String input) {
        QueryEngine.QueryResult result = queryEngine.query(input, workingMemory);
        if (result.stopReason() == QueryEngine.QueryResult.StopReason.COMPLETED) {
            memoryReflector.accept(result.turnMessages());
        }
        return result;
    }

    public void clear() {
        workingMemory.clear();
    }

    public WorkingMemory workingMemory() {
        return workingMemory;
    }
}
