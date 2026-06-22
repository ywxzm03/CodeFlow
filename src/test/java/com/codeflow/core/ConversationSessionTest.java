package com.codeflow.core;

import com.codeflow.llm.LLMClient;
import com.codeflow.memory.MemoryReflection;
import com.codeflow.memory.TranscriptRecorder;
import com.codeflow.permissions.ToolPermissionManager;
import com.codeflow.tools.Tool;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConversationSessionTest {

    @Test
    void reflectsOnlyCompletedTurnMessages() {
        AtomicReference<List<Message>> reflected = new AtomicReference<>();
        QueryEngine queryEngine = queryEngine(
                new StaticStreamingClient(Flux.just(new LLMClient.StreamEvent.TextDelta("done"))),
                List.of(),
                3
        );
        TestMemoryReflection memoryReflection = new TestMemoryReflection(reflected::set);
        ConversationSession session = new ConversationSession(queryEngine, memoryReflection, TranscriptRecorder.disabled());
        session.workingMemory().append(new Message.User("old"));

        QueryEngine.QueryResult result = session.handleUserInput("new");

        assertEquals(QueryEngine.QueryResult.StopReason.COMPLETED, result.stopReason());
        assertEquals(
                List.of(new Message.User("new"), new Message.Assistant("done", List.of(), null)),
                reflected.get()
        );
    }

    @Test
    void doesNotReflectErrorResults() {
        AtomicBoolean reflected = new AtomicBoolean(false);
        QueryEngine queryEngine = queryEngine(
                new StaticStreamingClient(Flux.error(new RuntimeException("boom"))),
                List.of(),
                3
        );
        TestMemoryReflection memoryReflection = new TestMemoryReflection(messages -> reflected.set(true));
        ConversationSession session = new ConversationSession(queryEngine, memoryReflection, TranscriptRecorder.disabled());

        QueryEngine.QueryResult result = session.handleUserInput("new");

        assertEquals(QueryEngine.QueryResult.StopReason.ERROR, result.stopReason());
        assertFalse(reflected.get());
    }

    @Test
    void doesNotReflectMaxIterationResults() {
        AtomicBoolean reflected = new AtomicBoolean(false);
        QueryEngine queryEngine = queryEngine(new StaticStreamingClient(), List.of(), 0);
        TestMemoryReflection memoryReflection = new TestMemoryReflection(messages -> reflected.set(true));
        ConversationSession session = new ConversationSession(queryEngine, memoryReflection, TranscriptRecorder.disabled());

        QueryEngine.QueryResult result = session.handleUserInput("new");

        assertEquals(QueryEngine.QueryResult.StopReason.MAX_ITERATIONS, result.stopReason());
        assertFalse(reflected.get());
    }

    @Test
    void clearEmptiesWorkingMemory() {
        ConversationSession session = new ConversationSession(
                queryEngine(new StaticStreamingClient(), List.of(), 0),
                null,
                TranscriptRecorder.disabled()
        );
        session.workingMemory().append(new Message.User("old"));

        session.clear();

        assertEquals(0, session.workingMemory().size());
    }

    private QueryEngine queryEngine(LLMClient llmClient, List<Tool> tools, int maxIterations) {
        return new QueryEngine(llmClient, tools, maxIterations, ToolPermissionManager.askByDefault(), null, null);
    }

    private static final class StaticStreamingClient implements LLMClient {
        private final List<Flux<StreamEvent>> responses;
        private int responseIndex;

        @SafeVarargs
        private StaticStreamingClient(Flux<StreamEvent>... responses) {
            this.responses = new ArrayList<>(List.of(responses));
        }

        @Override
        public LLMResponse call(String systemPrompt, List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<StreamEvent> callStreaming(String systemPrompt, List<Message> messages, List<Tool> tools) {
            if (responses.isEmpty()) {
                return Flux.empty();
            }
            return responses.get(Math.min(responseIndex++, responses.size() - 1));
        }

        @Override
        public void setModel(String model) {
        }
    }

    private static final class TestMemoryReflection extends MemoryReflection {
        private final java.util.function.Consumer<List<Message>> delegate;

        private TestMemoryReflection(java.util.function.Consumer<List<Message>> delegate) {
            super(null, null);
            this.delegate = delegate;
        }

        @Override
        public void reflect(List<Message> messages) {
            delegate.accept(messages);
        }
    }
}
