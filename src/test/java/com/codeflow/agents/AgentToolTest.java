package com.codeflow.agents;

import com.codeflow.core.Message;
import com.codeflow.llm.LLMClient;
import com.codeflow.tasks.BackgroundTaskRegistry;
import com.codeflow.tools.BashTool;
import com.codeflow.tools.GlobTool;
import com.codeflow.tools.GrepTool;
import com.codeflow.tools.ReadTool;
import com.codeflow.tools.Tool;
import com.codeflow.worktree.WorktreeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolTest {

    @TempDir
    Path tempDir;

    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void shutdownExecutors() throws Exception {
        for (ExecutorService executor : executors) {
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void acceptsAllBuiltInAgentTypes() {
        AgentTool tool = agentTool("STATUS: success\nSUMMARY: ok\nFAILURE_REASON: none");

        assertTrue(tool.validateInput("""
                {"subagent_type":"Explorer","prompt":"search"}
                """).allowed());
        assertTrue(tool.validateInput("""
                {"subagent_type":"Planner","prompt":"plan"}
                """).allowed());
        assertTrue(tool.validateInput("""
                {"subagent_type":"Coder","prompt":"code","isolation":"worktree"}
                """).allowed());
        assertTrue(tool.validateInput("""
                {"subagent_type":"Verifier","prompt":"verify","target_agent_id":"agent-a"}
                """).allowed());
    }

    @Test
    void rejectsInvalidAgentCombinations() {
        AgentTool tool = agentTool("STATUS: success\nSUMMARY: ok\nFAILURE_REASON: none");

        assertFalse(tool.validateInput("""
                {"subagent_type":"Explorer","prompt":"search","isolation":"worktree"}
                """).allowed());
        assertFalse(tool.validateInput("""
                {"subagent_type":"Planner","prompt":"plan","target_agent_id":"agent-a"}
                """).allowed());
    }

    @Test
    void explorerDefaultsToBackground() {
        AgentTool tool = agentTool("""
                STATUS: success
                SUMMARY: searched
                FINDINGS: done
                FAILURE_REASON: none
                """);

        Tool.ToolExecutionResult result = tool.execute("""
                {"subagent_type":"Explorer","prompt":"search"}
                """);

        assertFalse(result.isError());
        assertTrue(result.content().contains("status: async_launched"));
    }

    @Test
    void foregroundExplorerCanBeRequestedExplicitly() {
        AgentTool tool = agentTool("""
                STATUS: success
                SUMMARY: searched
                FINDINGS: done
                FAILURE_REASON: none
                """);

        Tool.ToolExecutionResult result = tool.execute("""
                {"subagent_type":"Explorer","prompt":"search","run_in_background":false}
                """);

        assertFalse(result.isError());
        assertTrue(result.content().contains("FINDINGS: done"));
    }

    @Test
    void verifierDefaultsToBackground() {
        AgentTool tool = agentTool("""
                STATUS: success
                VERDICT: PASS
                COMMANDS: ./gradlew test
                SUMMARY: passed
                FAILURE_REASON: none
                """);

        Tool.ToolExecutionResult result = tool.execute("""
                {"subagent_type":"Verifier","prompt":"verify"}
                """);

        assertFalse(result.isError());
        assertTrue(result.content().contains("status: async_launched"));
    }

    private AgentTool agentTool(String response) {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry(tempDir);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        return new AgentTool(
                new SubagentRunner(new StubClient(response), tools(), 1, null, tempDir),
                registry,
                new WorktreeService(tempDir),
                executor
        );
    }

    private static List<Tool> tools() {
        return List.of(
                new ReadTool(),
                new BashTool(),
                new GrepTool(),
                new GlobTool()
        );
    }

    private static final class StubClient implements LLMClient {
        private final String response;

        private StubClient(String response) {
            this.response = response;
        }

        @Override
        public LLMResponse call(String systemPrompt, List<Message> messages, List<Tool> tools) {
            return new LLMResponse(response, List.of(), null);
        }

        @Override
        public Flux<StreamEvent> callStreaming(String systemPrompt, List<Message> messages, List<Tool> tools) {
            return Flux.just(new StreamEvent.TextDelta(response));
        }

        @Override
        public void setModel(String model) {
        }
    }
}
