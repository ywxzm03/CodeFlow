package com.codeflow.core;

import com.codeflow.compact.AutoCompactor;
import com.codeflow.compact.CompactionManager;
import com.codeflow.compact.CompactionPolicy;
import com.codeflow.compact.ReactiveCompactor;
import com.codeflow.compact.SnipCompactor;
import com.codeflow.compact.TokenEstimator;
import com.codeflow.hooks.PreToolUseResult;
import com.codeflow.hooks.StopHookHandler;
import com.codeflow.hooks.StopHookInput;
import com.codeflow.hooks.StopHookResult;
import com.codeflow.llm.LLMClient;
import com.codeflow.memory.TranscriptRecorder;
import com.codeflow.memory.TranscriptStore;
import com.codeflow.permissions.PermissionMode;
import com.codeflow.permissions.ToolPermission;
import com.codeflow.permissions.ToolPermissionConfig;
import com.codeflow.permissions.ToolPermissionManager;
import com.codeflow.skills.SkillRenderer;
import com.codeflow.skills.SkillStore;
import com.codeflow.tools.SkillTool;
import com.codeflow.tools.Tool;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryEngineWorkingMemoryTest {

    @TempDir
    Path tempDir;

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
                        new Message.Assistant("first", List.of(), null),
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
                List.of(new Message.User("new"), new Message.Assistant("done", List.of(), null)),
                result.turnMessages()
        );
        assertEquals(3, result.messages().size());
    }

    @Test
    void stopHookFeedbackContinuesWithHiddenUserMessage() {
        RecordingStreamingClient client = new RecordingStreamingClient(
                Flux.just(new LLMClient.StreamEvent.TextDelta("missing validation")),
                Flux.just(new LLMClient.StreamEvent.TextDelta("validated"))
        );
        QueryEngine queryEngine = new QueryEngine(
                client,
                List.of(),
                3,
                ToolPermissionManager.askByDefault(),
                input -> PreToolUseResult.none(),
                input -> input.stopHookActive()
                        ? StopHookResult.allow()
                        : StopHookResult.block("请先补充验证。"),
                null,
                null
        );
        WorkingMemory memory = new WorkingMemory();

        QueryEngine.QueryResult result = queryEngine.query("finish", memory);

        assertEquals(QueryEngine.QueryResult.StopReason.COMPLETED, result.stopReason());
        assertEquals("validated", result.finalResponse());
        assertEquals(2, client.calls.size());
        assertTrue(client.calls.get(1).stream().anyMatch(message ->
                message instanceof Message.User user
                        && user.hidden()
                        && user.content().contains("Stop hook feedback:")
                        && user.content().contains("请先补充验证。")
        ));
        assertTrue(result.turnMessages().stream().noneMatch(message ->
                message instanceof Message.User user && user.hidden()
        ));
    }

    @Test
    void stopHookDoesNotRecurseWhenAlreadyActive() {
        RecordingStreamingClient client = new RecordingStreamingClient(
                Flux.just(new LLMClient.StreamEvent.TextDelta("first")),
                Flux.just(new LLMClient.StreamEvent.TextDelta("second"))
        );
        QueryEngine queryEngine = new QueryEngine(
                client,
                List.of(),
                2,
                ToolPermissionManager.askByDefault(),
                input -> PreToolUseResult.none(),
                input -> StopHookResult.block("again"),
                null,
                null
        );

        QueryEngine.QueryResult result = queryEngine.query("finish", new WorkingMemory());

        assertEquals(QueryEngine.QueryResult.StopReason.COMPLETED, result.stopReason());
        assertEquals("second", result.finalResponse());
        assertEquals(2, client.calls.size());
    }

    @Test
    void stopHookReceivesCurrentWorkingMemorySnapshot() {
        RecordingStreamingClient client = new RecordingStreamingClient(
                Flux.just(new LLMClient.StreamEvent.TextDelta("done"))
        );
        AtomicReference<StopHookInput> captured = new AtomicReference<>();
        QueryEngine queryEngine = new QueryEngine(
                client,
                List.of(),
                3,
                ToolPermissionManager.askByDefault(),
                input -> PreToolUseResult.none(),
                input -> {
                    captured.set(input);
                    return StopHookResult.allow();
                },
                null,
                null
        );

        queryEngine.query("finish", new WorkingMemory());

        assertEquals(
                List.of(new Message.User("finish"), new Message.Assistant("done", List.of(), null)),
                captured.get().messages()
        );
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
    void skillToolInvocationAppendsRenderedSkillMessage() throws Exception {
        Path userSkills = tempDir.resolve("skills");
        writeSkill(userSkills.resolve("commit"));
        SkillStore skillStore = new SkillStore(userSkills, tempDir.resolve("project-skills"));
        SkillTool skillTool = new SkillTool(skillStore, new SkillRenderer());
        RecordingStreamingClient client = new RecordingStreamingClient(
                Flux.just(new LLMClient.StreamEvent.ToolUse(new Message.ToolUse(
                        "toolu_skill",
                        "Skill",
                        "{\"skill\":\"commit\",\"args\":\"add skills\"}"
                ))),
                Flux.just(new LLMClient.StreamEvent.TextDelta("done"))
        );
        QueryEngine queryEngine = new QueryEngine(
                client,
                List.of(skillTool),
                3,
                new ToolPermissionManager(PermissionMode.ASK),
                input -> PreToolUseResult.allow("test"),
                StopHookHandler.none(),
                null,
                null,
                skillStore
        );
        WorkingMemory memory = new WorkingMemory();

        QueryEngine.QueryResult result = queryEngine.query("write commit", memory);

        assertEquals(QueryEngine.QueryResult.StopReason.COMPLETED, result.stopReason());
        assertTrue(memory.snapshot().stream().anyMatch(message ->
                message instanceof Message.ToolResult toolResult && toolResult.content().contains("Skill loaded: commit")
        ));
        assertTrue(memory.snapshot().stream().anyMatch(message ->
                message instanceof Message.User user && user.content().contains("<skill_invocation>")
                        && user.content().contains("<name>commit</name>")
                        && user.content().contains("Use conventional commits.")
        ));
        assertEquals(2, client.calls.size());
        assertTrue(client.calls.get(1).stream().anyMatch(message ->
                message instanceof Message.User user && user.content().contains("<skill_invocation>")
        ));
    }

    @Test
    void systemPromptIncludesSkillIndex() throws Exception {
        Path userSkills = tempDir.resolve("skills");
        writeSkill(userSkills.resolve("commit"));
        SkillStore skillStore = new SkillStore(userSkills, tempDir.resolve("project-skills"));
        RecordingStreamingClient client = new RecordingStreamingClient(Flux.just(new LLMClient.StreamEvent.TextDelta("done")));
        QueryEngine queryEngine = new QueryEngine(
                client,
                List.of(new SkillTool(skillStore, new SkillRenderer())),
                1,
                new ToolPermissionManager(PermissionMode.ASK),
                null,
                null,
                null,
                null,
                skillStore
        );

        queryEngine.query("hello", new WorkingMemory());

        assertTrue(client.systemPrompts.getFirst().contains("### Skills"));
        assertTrue(client.systemPrompts.getFirst().contains("- commit: Write commit message"));
        assertTrue(client.systemPrompts.getFirst().contains("Use the Skill tool"));
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

    @Test
    void streamingCancellationKeepsPartialAssistantAndInterruptMarker() {
        CancellationToken token = CancellationToken.create();
        QueryEngine queryEngine = queryEngine(
                new RecordingStreamingClient(Flux.concat(
                        Flux.just(new LLMClient.StreamEvent.TextDelta("partial")),
                        Flux.error(new UserCancelledException(CancellationToken.USER_CANCEL))
                )),
                List.of(),
                3
        );
        WorkingMemory memory = new WorkingMemory();

        QueryEngine.QueryResult result = queryEngine.query("new", memory, token);

        assertEquals(QueryEngine.QueryResult.StopReason.USER_CANCELLED, result.stopReason());
        assertEquals(
                List.of(
                        new Message.User("new"),
                        new Message.Assistant("partial", List.of(), null),
                        new Message.User(QueryEngine.INTERRUPT_MESSAGE)
                ),
                memory.snapshot()
        );
    }

    @Test
    void streamingCancellationAddsMissingToolResult() {
        CancellationToken token = CancellationToken.create();
        QueryEngine queryEngine = queryEngine(
                new RecordingStreamingClient(Flux.concat(
                        Flux.just(new LLMClient.StreamEvent.ToolUse(new Message.ToolUse("toolu_cancel", "TestTool", "{}"))),
                        Flux.error(new UserCancelledException(CancellationToken.USER_CANCEL))
                )),
                List.of(testTool()),
                3,
                new ToolPermissionConfig(Map.of("TestTool", ToolPermission.ALLOW))
        );
        WorkingMemory memory = new WorkingMemory();

        QueryEngine.QueryResult result = queryEngine.query("new", memory, token);

        assertEquals(QueryEngine.QueryResult.StopReason.USER_CANCELLED, result.stopReason());
        assertTrue(memory.snapshot().stream().anyMatch(message ->
                message instanceof Message.ToolResult toolResult
                        && "toolu_cancel".equals(toolResult.toolUseId())
                        && toolResult.isError()
        ));
        assertTrue(memory.snapshot().stream().anyMatch(message ->
                message instanceof Message.User user
                        && QueryEngine.INTERRUPT_MESSAGE.equals(user.content())
        ));
    }

    @Test
    void contextOverflowTriggersReactiveCompactAndRetries() throws Exception {
        TranscriptStore store = new TranscriptStore(tempDir.resolve("memory/L5"));
        store.initialize();
        TranscriptRecorder recorder = new TranscriptRecorder(store, "session-a");
        TokenEstimator estimator = new TokenEstimator();
        CompactionPolicy policy = new CompactionPolicy(true, 10_000, 8_000, 0.99, 5, 1);
        RetryingStreamingClient client = new RetryingStreamingClient();
        CompactionManager compactionManager = new CompactionManager(
                new SnipCompactor(policy.snipToolResultThresholdChars(), estimator, recorder, store),
                new AutoCompactor(policy, estimator, client, recorder, store),
                new ReactiveCompactor(policy, estimator, client, recorder, store)
        );
        QueryEngine queryEngine = new QueryEngine(
                client,
                List.of(),
                3,
                ToolPermissionManager.askByDefault(),
                null,
                compactionManager
        );
        WorkingMemory memory = new WorkingMemory();
        memory.append(new Message.User("old"));

        QueryEngine.QueryResult result = queryEngine.query("new", memory);

        assertEquals(QueryEngine.QueryResult.StopReason.COMPLETED, result.stopReason());
        assertEquals("done", result.finalResponse());
        assertEquals(2, client.streamingCalls);
        assertTrue(memory.snapshot().stream().anyMatch(message ->
                message instanceof Message.User user && user.content().contains("reactive summary")
        ));
        assertTrue(store.loadRecords("session-a").stream().anyMatch(record ->
                record.isCompactBoundary() && "reactive".equals(record.compactBoundary().mode())
        ));
    }

    @Test
    void compactReturnsNotNeededForEmptyWorkingMemory() {
        QueryEngine queryEngine = queryEngine(
                new RecordingStreamingClient(Flux.just(new LLMClient.StreamEvent.TextDelta("unused"))),
                List.of(),
                3
        );

        QueryEngine.CompactResult result = queryEngine.compact(new WorkingMemory());

        assertEquals(QueryEngine.CompactResult.Status.NOT_NEEDED, result.status());
    }

    @Test
    void compactReturnsUnavailableWhenCompactionManagerIsMissing() {
        QueryEngine queryEngine = queryEngine(
                new RecordingStreamingClient(Flux.just(new LLMClient.StreamEvent.TextDelta("unused"))),
                List.of(),
                3
        );
        WorkingMemory memory = new WorkingMemory();
        memory.append(new Message.User("old"));

        QueryEngine.CompactResult result = queryEngine.compact(memory);

        assertEquals(QueryEngine.CompactResult.Status.UNAVAILABLE, result.status());
    }

    @Test
    void compactForcesAutoCompaction() throws Exception {
        TranscriptStore store = new TranscriptStore(tempDir.resolve("manual-memory/L5"));
        store.initialize();
        TranscriptRecorder recorder = new TranscriptRecorder(store, "session-a");
        TokenEstimator estimator = new TokenEstimator();
        CompactionPolicy policy = new CompactionPolicy(true, 10_000, 8_000, 0.99, 1, 1);
        StaticSummaryClient client = new StaticSummaryClient("manual summary");
        CompactionManager compactionManager = new CompactionManager(
                new SnipCompactor(policy.snipToolResultThresholdChars(), estimator, recorder, store),
                new AutoCompactor(policy, estimator, client, recorder, store),
                new ReactiveCompactor(policy, estimator, client, recorder, store)
        );
        QueryEngine queryEngine = new QueryEngine(
                client,
                List.of(),
                3,
                ToolPermissionManager.askByDefault(),
                null,
                compactionManager
        );
        WorkingMemory memory = new WorkingMemory();
        memory.append(new Message.User("old"));
        memory.append(new Message.User("recent"));

        QueryEngine.CompactResult result = queryEngine.compact(memory);

        assertEquals(QueryEngine.CompactResult.Status.COMPACTED, result.status());
        assertEquals(2, result.beforeMessages());
        assertEquals(2, result.afterMessages());
        assertTrue(((Message.User) memory.snapshot().getFirst()).content().contains("manual summary"));
        assertTrue(store.loadRecords("session-a").stream().anyMatch(record ->
                record.isCompactBoundary() && "manual_command".equals(record.compactBoundary().reason())
        ));
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

    private void writeSkill(Path skillDir) throws Exception {
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                description: Write commit message
                when_to_use: User asks for commit guidance
                argument_hint: <change summary>
                ---
                Use conventional commits.
                """);
    }

    private QueryEngine queryEngine(LLMClient llmClient, List<Tool> tools, int maxIterations) {
        return new QueryEngine(llmClient, tools, maxIterations, ToolPermissionManager.askByDefault(), null, null);
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
                new ToolPermissionManager(PermissionMode.ASK),
                input -> toolPermissionConfig.configuredPermissionFor(input.toolName())
                        .map(permission -> switch (permission) {
                            case ALLOW -> PreToolUseResult.allow("test");
                            case ASK -> PreToolUseResult.ask("test");
                            case DENY -> PreToolUseResult.deny("test");
                        })
                        .orElseGet(PreToolUseResult::none),
                null,
                null
        );
    }

    private static final class RecordingStreamingClient implements LLMClient {
        private final List<Flux<StreamEvent>> responses;
        private final List<List<Message>> calls = new ArrayList<>();
        private final List<String> systemPrompts = new ArrayList<>();
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
            systemPrompts.add(systemPrompt);
            calls.add(List.copyOf(messages));
            return responses.get(Math.min(responseIndex++, responses.size() - 1));
        }

        @Override
        public void setModel(String model) {
        }
    }

    private static final class RetryingStreamingClient implements LLMClient {
        private int streamingCalls;

        @Override
        public LLMResponse call(String systemPrompt, List<Message> messages, List<Tool> tools) {
            return new LLMResponse("reactive summary", List.of(), null);
        }

        @Override
        public Flux<StreamEvent> callStreaming(String systemPrompt, List<Message> messages, List<Tool> tools) {
            streamingCalls++;
            if (streamingCalls == 1) {
                return Flux.error(new RuntimeException("context length exceeded"));
            }
            return Flux.just(new StreamEvent.TextDelta("done"));
        }

        @Override
        public void setModel(String model) {
        }
    }

    private static final class StaticSummaryClient implements LLMClient {
        private final String summary;

        private StaticSummaryClient(String summary) {
            this.summary = summary;
        }

        @Override
        public LLMResponse call(String systemPrompt, List<Message> messages, List<Tool> tools) {
            return new LLMResponse(summary, List.of(), null);
        }

        @Override
        public Flux<StreamEvent> callStreaming(String systemPrompt, List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setModel(String model) {
        }
    }
}
