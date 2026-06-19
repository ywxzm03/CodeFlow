package com.codewarp.core;

import com.codewarp.llm.LLMClient;
import com.codewarp.tools.Tool;
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
        QueryEngine queryEngine = new QueryEngine(
                new StaticStreamingClient(Flux.just(new LLMClient.StreamEvent.TextDelta("done"))),
                List.of(),
                3
        );
        WorkingMemory memory = new WorkingMemory();
        memory.append(new Message.User("old"));
        ConversationSession session = new ConversationSession(queryEngine, memory, reflected::set);

        QueryEngine.QueryResult result = session.handleUserInput("new");

        assertEquals(QueryEngine.QueryResult.StopReason.COMPLETED, result.stopReason());
        assertEquals(
                List.of(new Message.User("new"), new Message.Assistant("done", List.of())),
                reflected.get()
        );
    }

    @Test
    void doesNotReflectErrorResults() {
        AtomicBoolean reflected = new AtomicBoolean(false);
        QueryEngine queryEngine = new QueryEngine(
                new StaticStreamingClient(Flux.error(new RuntimeException("boom"))),
                List.of(),
                3
        );
        ConversationSession session = new ConversationSession(queryEngine, new WorkingMemory(), messages -> reflected.set(true));

        QueryEngine.QueryResult result = session.handleUserInput("new");

        assertEquals(QueryEngine.QueryResult.StopReason.ERROR, result.stopReason());
        assertFalse(reflected.get());
    }

    @Test
    void doesNotReflectMaxIterationResults() {
        AtomicBoolean reflected = new AtomicBoolean(false);
        QueryEngine queryEngine = new QueryEngine(new StaticStreamingClient(), List.of(), 0);
        ConversationSession session = new ConversationSession(queryEngine, new WorkingMemory(), messages -> reflected.set(true));

        QueryEngine.QueryResult result = session.handleUserInput("new");

        assertEquals(QueryEngine.QueryResult.StopReason.MAX_ITERATIONS, result.stopReason());
        assertFalse(reflected.get());
    }

    @Test
    void clearEmptiesWorkingMemory() {
        ConversationSession session = new ConversationSession(
                new QueryEngine(new StaticStreamingClient(), List.of(), 0),
                null
        );
        session.workingMemory().append(new Message.User("old"));

        session.clear();

        assertEquals(0, session.workingMemory().size());
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
}
