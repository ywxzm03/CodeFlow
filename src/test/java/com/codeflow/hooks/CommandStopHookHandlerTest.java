package com.codeflow.hooks;

import com.codeflow.core.Message;
import com.codeflow.memory.TranscriptRecorder;
import com.codeflow.memory.TranscriptStore;
import com.codeflow.permissions.PermissionMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandStopHookHandlerTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void blocksWhenCommandReturnsDecisionBlockJson() throws Exception {
        Path script = script("""
                #!/bin/sh
                printf '{"decision":"block","reason":"需要先运行测试"}'
                """);
        CommandStopHookHandler handler = new CommandStopHookHandler(script.toString(), 5, TranscriptRecorder.disabled());

        StopHookResult result = handler.handle(input(false));

        assertTrue(result.blocked());
        assertTrue(result.feedback().contains("需要先运行测试"));
    }

    @Test
    void allowsWhenCommandReturnsApproveJson() throws Exception {
        Path script = script("""
                #!/bin/sh
                printf '{"decision":"approve"}'
                """);
        CommandStopHookHandler handler = new CommandStopHookHandler(script.toString(), 5, TranscriptRecorder.disabled());

        StopHookResult result = handler.handle(input(false));

        assertFalse(result.blocked());
    }

    @Test
    void blocksWhenCommandExitsTwo() throws Exception {
        Path script = script("""
                #!/bin/sh
                echo '缺少验证' >&2
                exit 2
                """);
        CommandStopHookHandler handler = new CommandStopHookHandler(script.toString(), 5, TranscriptRecorder.disabled());

        StopHookResult result = handler.handle(input(false));

        assertTrue(result.blocked());
        assertTrue(result.feedback().contains("缺少验证"));
    }

    @Test
    void sendsStopHookInputAsJson() throws Exception {
        Path captured = tempDir.resolve("input.json");
        Path storeRoot = tempDir.resolve("memory");
        TranscriptStore store = new TranscriptStore(storeRoot);
        store.initialize();
        TranscriptRecorder recorder = new TranscriptRecorder(store, "session-a");
        Path script = script("""
                #!/bin/sh
                cat > "$CAPTURED_INPUT"
                printf '{"decision":"approve"}'
                """);
        CommandStopHookHandler handler = new CommandStopHookHandler(
                "CAPTURED_INPUT='" + captured + "' " + script,
                5,
                recorder
        );

        StopHookResult result = handler.handle(input(true));

        assertFalse(result.blocked());
        String json = Files.readString(captured);
        assertTrue(json.contains("\"hook_event_name\":\"Stop\""));
        assertTrue(json.contains("\"session_id\":\"session-a\""));
        JsonNode root = objectMapper.readTree(json);
        String transcriptPath = root.get("transcript_path").asText();
        assertTrue(transcriptPath.contains(".hooks"));
        assertTrue(transcriptPath.endsWith(".jsonl"));
        assertTrue(Files.exists(Path.of(transcriptPath)));
        assertTrue(Files.readString(Path.of(transcriptPath)).contains("\"text\":\"final response\""));
        assertTrue(json.contains("\"permission_mode\":\"ask\""));
        assertTrue(json.contains("\"stop_hook_active\":true"));
        assertTrue(json.contains("\"last_assistant_message\":\"final response\""));
    }

    @Test
    void missingCommandAllows() {
        StopHookHandler handler = CommandStopHookHandler.fromSettings(null, TranscriptRecorder.disabled());

        StopHookResult result = handler.handle(input(false));

        assertFalse(result.blocked());
    }

    private StopHookInput input(boolean stopHookActive) {
        return new StopHookInput(
                "final response",
                tempDir.toString(),
                PermissionMode.ASK,
                stopHookActive,
                List.of(
                        new Message.User("finish"),
                        new Message.Assistant("final response", List.of(), null)
                )
        );
    }

    private Path script(String content) throws Exception {
        Path script = Files.createTempFile(tempDir, "stop-hook-", ".sh");
        Files.writeString(script, content);
        script.toFile().setExecutable(true);
        return script;
    }
}
