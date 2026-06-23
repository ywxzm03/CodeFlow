package com.codeflow.hooks;

import com.codeflow.config.Settings;
import com.codeflow.memory.TranscriptRecorder;
import com.codeflow.util.Console;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class CommandStopHookHandler implements StopHookHandler {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String command;
    private final int timeoutSeconds;
    private final TranscriptRecorder transcriptRecorder;

    public CommandStopHookHandler(
            String command,
            int timeoutSeconds,
            TranscriptRecorder transcriptRecorder
    ) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        this.command = command;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 30 : timeoutSeconds;
        this.transcriptRecorder = transcriptRecorder == null ? TranscriptRecorder.disabled() : transcriptRecorder;
    }

    public static StopHookHandler fromSettings(
            Settings.CommandHook hook,
            TranscriptRecorder transcriptRecorder
    ) {
        if (hook == null || !hook.enabled()) {
            return StopHookHandler.none();
        }
        return new CommandStopHookHandler(
                hook.command(),
                hook.resolvedTimeoutSeconds(),
                transcriptRecorder
        );
    }

    @Override
    public StopHookResult handle(StopHookInput input) {
        if (input == null) {
            return StopHookResult.allow();
        }

        try {
            Process process = new ProcessBuilder("/bin/bash", "-c", command)
                    .redirectErrorStream(false)
                    .start();

            process.getOutputStream().write(toJson(input).getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                Console.warn("[Hooks] Stop command timed out after " + timeoutSeconds + "s: " + command);
                return StopHookResult.allow();
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            Optional<StopHookResult> jsonResult = parseJsonResult(stdout);
            if (jsonResult.isPresent()) {
                return jsonResult.get();
            }

            if (process.exitValue() == 2) {
                return StopHookResult.block(stderr.isBlank() ? "No stderr output" : stderr.strip());
            }

            if (process.exitValue() != 0) {
                Console.warn("[Hooks] Stop command failed with exit code " + process.exitValue() + ": " +
                        (stderr.isBlank() ? command : stderr.strip()));
            }
            return StopHookResult.allow();
        } catch (IOException e) {
            Console.warn("[Hooks] Stop command failed to run: " + e.getMessage());
            return StopHookResult.allow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Console.warn("[Hooks] Stop command interrupted: " + command);
            return StopHookResult.allow();
        }
    }

    private String toJson(StopHookInput input) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        String transcriptPath = transcriptRecorder.recordHookSnapshot("Stop", input.messages());
        if (transcriptPath.isBlank()) {
            transcriptPath = transcriptRecorder.transcriptPath();
        }
        root.put("hook_event_name", "Stop");
        root.put("session_id", nullToEmpty(transcriptRecorder.sessionId()));
        root.put("transcript_path", nullToEmpty(transcriptPath));
        root.put("cwd", nullToEmpty(input.cwd()));
        root.put("permission_mode", input.permissionMode() == null ? "" : input.permissionMode().configValue());
        root.put("stop_hook_active", input.stopHookActive());
        root.put("last_assistant_message", nullToEmpty(input.lastAssistantMessage()));
        return objectMapper.writeValueAsString(root);
    }

    private Optional<StopHookResult> parseJsonResult(String stdout) {
        String trimmed = stdout == null ? "" : stdout.strip();
        if (trimmed.isBlank()) {
            return Optional.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(trimmed);
            JsonNode decision = root.get("decision");
            if (decision == null || !decision.isTextual()) {
                return Optional.empty();
            }
            return switch (decision.asText()) {
                case "block" -> Optional.of(StopHookResult.block(reason(root)));
                case "approve" -> Optional.of(StopHookResult.allow());
                default -> Optional.empty();
            };
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private String reason(JsonNode root) {
        JsonNode reason = root.get("reason");
        if (reason != null && reason.isTextual() && !reason.asText().isBlank()) {
            return reason.asText();
        }
        return "Blocked by Stop hook";
    }

    private String nullToEmpty(String value) {
        return Objects.toString(value, "");
    }
}
