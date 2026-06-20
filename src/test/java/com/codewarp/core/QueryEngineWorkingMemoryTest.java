package com.codewarp.core;

import com.codewarp.llm.LLMClient;
import com.codewarp.permissions.PermissionMode;
import com.codewarp.permissions.ToolPermission;
import com.codewarp.permissions.ToolPermissionConfig;
import com.codewarp.permissions.ToolPermissionManager;
import com.codewarp.tools.Tool;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryEngineWorkingMemoryTest {

    @Test
    void subsequentQueriesUsePreviousWorkingMemory() {
        RecordingStreamingClient client = new RecordingStreamingClient(
                Flux.just(new LLMClient.StreamEvent.TextDelta("first")),
                Flux.just(new LLMClient.StreamEvent.TextDelta("second"))
        );
        QueryEngine queryEngine = queryEngine(client, List.of(), 3);
        WorkingMemory memory = new WorkingMemory();

        queryEngine.query("hello", memory);
        queryEngine.query("continue", memory);

        assertEquals(2, client.calls.size());
        assertEquals(List.of(new Message.User("hello")), client.calls.get(0));
        assertEquals(
                List.of(
                        new Message.User("hello"),
                        new Message.Assistant("first", List.of()),
                        new Message.User("continue")
                ),
                client.calls.get(1)
        );
    }

    @Test
    void completedQueryReturnsOnlyTurnMessages() {
        QueryEngine queryEngine = queryEngine(
                new RecordingStreamingClient(Flux.just(new LLMClient.StreamEvent.TextDelta("done"))),
                List.of(),
                3
        );
        WorkingMemory memory = new WorkingMemory();
        memory.append(new Message.User("old"));

        QueryEngine.QueryResult result = queryEngine.query("new", memory);

        assertEquals(QueryEngine.QueryResult.StopReason.COMPLETED, result.stopReason());
        assertEquals(
                List.of(new Message.User("new"), new Message.Assistant("done", List.of())),
                result.turnMessages()
        );
        assertEquals(3, result.messages().size());
    }

    @Test
    void toolResultsAreAppendedToWorkingMemory() {
        RecordingStreamingClient client = new RecordingStreamingClient(
                Flux.just(new LLMClient.StreamEvent.ToolUse(new Message.ToolUse("toolu_test", "TestTool", "{}"))),
                Flux.just(new LLMClient.StreamEvent.TextDelta("final"))
        );
        QueryEngine queryEngine = queryEngine(
                client,
                List.of(testTool()),
                3,
                new ToolPermissionConfig(Map.of("TestTool", ToolPermission.ALLOW))
        );
        WorkingMemory memory = new WorkingMemory();

        QueryEngine.QueryResult result = queryEngine.query("use tool", memory);

        assertEquals(QueryEngine.QueryResult.StopReason.COMPLETED, result.stopReason());
        assertTrue(memory.snapshot().stream().anyMatch(message ->
                message instanceof Message.ToolResult toolResult && toolResult.content().contains("tool result")
        ));
    }

    @Test
    void streamingErrorRollsBackCurrentTurn() {
        QueryEngine queryEngine = queryEngine(
                new RecordingStreamingClient(Flux.error(new RuntimeException("boom"))),
                List.of(),
                3
        );
        WorkingMemory memory = new WorkingMemory();
        memory.append(new Message.User("old"));

        QueryEngine.QueryResult result = queryEngine.query("new", memory);

        assertEquals(QueryEngine.QueryResult.StopReason.ERROR, result.stopReason());
        assertEquals(List.of(new Message.User("old")), memory.snapshot());
        assertTrue(result.turnMessages().isEmpty());
    }

    private Tool testTool() {
        return new Tool() {
            @Override
            public String name() {
                return "TestTool";
            }

            @Override
            public String description() {
                return "test";
            }

            @Override
            public String inputSchema() {
                return "{}";
            }

            @Override
            public ToolExecutionResult execute(String input) {
                return ToolExecutionResult.success("tool result");
            }
        };
    }

    private QueryEngine queryEngine(LLMClient llmClient, List<Tool> tools, int maxIterations) {
        return new QueryEngine(llmClient, tools, maxIterations, ToolPermissionManager.askByDefault(), null);
    }

    private QueryEngine queryEngine(
            LLMClient llmClient,
            List<Tool> tools,
            int maxIterations,
            ToolPermissionConfig toolPermissionConfig
    ) {
        return new QueryEngine(
                llmClient,
                tools,
                maxIterations,
                new ToolPermissionManager(toolPermissionConfig, PermissionMode.ASK),
                null
        );
    }

    private static final class RecordingStreamingClient implements LLMClient {
        private final List<Flux<StreamEvent>> responses;
        private final List<List<Message>> calls = new ArrayList<>();
        private int responseIndex;

        @SafeVarargs
        private RecordingStreamingClient(Flux<StreamEvent>... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public LLMResponse call(String systemPrompt, List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<StreamEvent> callStreaming(String systemPrompt, List<Message> messages, List<Tool> tools) {
            calls.add(List.copyOf(messages));
            return responses.get(Math.min(responseIndex++, responses.size() - 1));
        }

        @Override
        public void setModel(String model) {
        }
    }
}
