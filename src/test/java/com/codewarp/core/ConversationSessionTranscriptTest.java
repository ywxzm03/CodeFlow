package com.codewarp.core;

import com.codewarp.llm.LLMClient;
import com.codewarp.memory.TranscriptRecorder;
import com.codewarp.memory.TranscriptStore;
import com.codewarp.permissions.ToolPermissionManager;
import com.codewarp.tools.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConversationSessionTranscriptTest {

    @TempDir
    Path tempDir;

    @Test
    void recordsTurnMessagesAfterQuery() throws Exception {
        TranscriptStore store = initializedStore();
        TranscriptRecorder recorder = new TranscriptRecorder(store, "session-a");
        ConversationSession session = new ConversationSession(
                queryEngine(new StaticStreamingClient(Flux.just(new LLMClient.StreamEvent.TextDelta("done"))), List.of(), 3),
                null,
                recorder
        );

        session.handleUserInput("hello");

        assertEquals(
                List.of(new Message.User("hello"), new Message.Assistant("done", List.of())),
                store.loadMessages("session-a")
        );
    }

    @Test
    void doesNotRecordStreamingErrorTurn() throws Exception {
        TranscriptStore store = initializedStore();
        TranscriptRecorder recorder = new TranscriptRecorder(store, "session-a");
        ConversationSession session = new ConversationSession(
                queryEngine(new StaticStreamingClient(Flux.error(new RuntimeException("boom"))), List.of(), 3),
                null,
                recorder
        );

        session.handleUserInput("hello");

        assertFalse(Files.exists(tempDir.resolve("memory/L5/session-a.jsonl")));
    }

    @Test
    void resumeReplacesWorkingMemoryAndContinuesSameSession() throws Exception {
        TranscriptStore store = initializedStore();
        store.append("old-session", List.of(new Message.User("old")));
        TranscriptRecorder recorder = new TranscriptRecorder(store, "new-session");
        ConversationSession session = new ConversationSession(
                queryEngine(new StaticStreamingClient(Flux.just(new LLMClient.StreamEvent.TextDelta("done"))), List.of(), 3),
                null,
                recorder
        );
        session.workingMemory().append(new Message.User("current"));

        session.resume("old-session", store.loadMessages("old-session"));
        session.handleUserInput("new");

        assertEquals(
                List.of(
                        new Message.User("old"),
                        new Message.User("new"),
                        new Message.Assistant("done", List.of())
                ),
                store.loadMessages("old-session")
        );
        assertEquals(
                List.of(
                        new Message.User("old"),
                        new Message.User("new"),
                        new Message.Assistant("done", List.of())
                ),
                session.workingMemory().snapshot()
        );
    }

    @Test
    void transcriptWriteFailureDoesNotBlockQuery() throws Exception {
        Path transcriptRoot = tempDir.resolve("not-directory");
        Files.writeString(transcriptRoot, "not a directory");
        TranscriptStore store = new TranscriptStore(transcriptRoot);
        TranscriptRecorder recorder = new TranscriptRecorder(store, "session-a");
        ConversationSession session = new ConversationSession(
                queryEngine(new StaticStreamingClient(Flux.just(new LLMClient.StreamEvent.TextDelta("done"))), List.of(), 3),
                null,
                recorder
        );

        QueryEngine.QueryResult result = session.handleUserInput("hello");

        assertEquals(QueryEngine.QueryResult.StopReason.COMPLETED, result.stopReason());
    }

    private TranscriptStore initializedStore() throws Exception {
        TranscriptStore store = new TranscriptStore(tempDir.resolve("memory/L5"));
        store.initialize();
        return store;
    }

    private QueryEngine queryEngine(LLMClient llmClient, List<Tool> tools, int maxIterations) {
        return new QueryEngine(llmClient, tools, maxIterations, ToolPermissionManager.askByDefault(), null);
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
