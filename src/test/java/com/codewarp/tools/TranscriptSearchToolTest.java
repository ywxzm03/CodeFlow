package com.codewarp.tools;

import com.codewarp.core.Message;
import com.codewarp.memory.TranscriptStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptSearchToolTest {

    @TempDir
    Path tempDir;

    @Test
    void searchesTranscript() throws Exception {
        TranscriptStore store = initializedStore();
        store.append("session-a", List.of(
                new Message.User("permission mode"),
                new Message.Assistant("unrelated", List.of())
        ));
        TranscriptSearchTool tool = new TranscriptSearchTool(store);

        Tool.ToolExecutionResult result = tool.execute("""
                {"session_id": "session-a", "keyword": "permission", "limit": 20}
                """);

        assertFalse(result.isError());
        assertTrue(result.content().contains("permission mode"));
    }

    @Test
    void usesDefaultLimit() throws Exception {
        TranscriptStore store = initializedStore();
        store.append("session-a", List.of(new Message.User("hello")));
        TranscriptSearchTool tool = new TranscriptSearchTool(store);

        Tool.ValidationResult result = tool.validateInput("""
                {"session_id": "session-a", "keyword": "hello"}
                """);

        assertTrue(result.allowed());
    }

    @Test
    void validatesInput() throws Exception {
        TranscriptStore store = initializedStore();
        TranscriptSearchTool tool = new TranscriptSearchTool(store);

        assertFalse(tool.validateInput("""
                {"session_id": "../bad", "keyword": "hello"}
                """).allowed());
        assertFalse(tool.validateInput("""
                {"session_id": "session-a", "keyword": ""}
                """).allowed());
        assertFalse(tool.validateInput("""
                {"session_id": "session-a", "keyword": "hello", "limit": 101}
                """).allowed());
        assertFalse(tool.validateInput("""
                {"session_id": "session-a", "keyword": "hello", "extra": true}
                """).allowed());
    }

    @Test
    void returnsToolErrorForMissingSession() throws Exception {
        TranscriptSearchTool tool = new TranscriptSearchTool(initializedStore());

        Tool.ToolExecutionResult result = tool.execute("""
                {"session_id": "missing", "keyword": "hello"}
                """);

        assertTrue(result.isError());
    }

    @Test
    void returnsEmptyResultMessageWhenNoMatches() throws Exception {
        TranscriptStore store = initializedStore();
        store.append("session-a", List.of(new Message.User("hello")));
        TranscriptSearchTool tool = new TranscriptSearchTool(store);

        Tool.ToolExecutionResult result = tool.execute("""
                {"session_id": "session-a", "keyword": "missing"}
                """);

        assertFalse(result.isError());
        assertTrue(result.content().contains("No transcript matches"));
    }

    private TranscriptStore initializedStore() throws Exception {
        TranscriptStore store = new TranscriptStore(tempDir.resolve("memory/L5"));
        store.initialize();
        return store;
    }
}
