package com.codewarp.terminal;

import com.codewarp.config.Settings;
import com.codewarp.memory.TranscriptSession;
import com.codewarp.permissions.PermissionMode;
import org.jline.reader.Candidate;
import org.jline.reader.LineReaderBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashCommandRegistryTest {

    @Test
    void slashMatchesAllCommands() {
        SlashCommandRegistry registry = registry();

        List<String> matches = registry.match("/").stream()
                .map(SlashCommand::displayName)
                .toList();

        assertEquals(List.of("/clear", "/compact", "/exit", "/help", "/model", "/permissions", "/resume"), matches);
    }

    @Test
    void slashPrefixFiltersCommands() {
        SlashCommandRegistry registry = registry();

        List<String> matches = registry.match("/mo").stream()
                .map(SlashCommand::displayName)
                .toList();

        assertEquals(List.of("/model"), matches);
    }

    @Test
    void nonSlashInputDoesNotMatch() {
        SlashCommandRegistry registry = registry();

        assertTrue(registry.match("mo").isEmpty());
        assertFalse(registry.isSlashCommandInput("mo"));
    }

    @Test
    void completerListsAllSlashCommandsForSlashInput() {
        SlashCommandRegistry registry = registry();
        SlashCommandCompleter completer = new SlashCommandCompleter(registry);
        List<Candidate> candidates = new ArrayList<>();
        var reader = LineReaderBuilder.builder().build();

        reader.getBuffer().write("/");
        completer.complete(reader, null, candidates);

        assertEquals(List.of("/clear", "/compact", "/exit", "/help", "/model", "/permissions", "/resume"), candidateValues(candidates));
    }

    @Test
    void completerFiltersSlashCommandsAsUserTypes() {
        SlashCommandRegistry registry = registry();
        SlashCommandCompleter completer = new SlashCommandCompleter(registry);
        List<Candidate> candidates = new ArrayList<>();
        var reader = LineReaderBuilder.builder().build();

        reader.getBuffer().write("/mo");
        completer.complete(reader, null, candidates);

        assertEquals(List.of("/model"), candidateValues(candidates));
    }

    @Test
    void completerFiltersCompactCommandAsUserTypes() {
        SlashCommandRegistry registry = registry();
        SlashCommandCompleter completer = new SlashCommandCompleter(registry);
        List<Candidate> candidates = new ArrayList<>();
        var reader = LineReaderBuilder.builder().build();

        reader.getBuffer().write("/co");
        completer.complete(reader, null, candidates);

        assertEquals(List.of("/compact"), candidateValues(candidates));
    }

    @Test
    void completerFiltersPermissionsCommandAsUserTypes() {
        SlashCommandRegistry registry = registry();
        SlashCommandCompleter completer = new SlashCommandCompleter(registry);
        List<Candidate> candidates = new ArrayList<>();
        var reader = LineReaderBuilder.builder().build();

        reader.getBuffer().write("/pe");
        completer.complete(reader, null, candidates);

        assertEquals(List.of("/permissions"), candidateValues(candidates));
    }

    @Test
    void completerFiltersResumeCommandAsUserTypes() {
        SlashCommandRegistry registry = registry();
        SlashCommandCompleter completer = new SlashCommandCompleter(registry);
        List<Candidate> candidates = new ArrayList<>();
        var reader = LineReaderBuilder.builder().build();

        reader.getBuffer().write("/re");
        completer.complete(reader, null, candidates);

        assertEquals(List.of("/resume"), candidateValues(candidates));
    }

    @Test
    void completerIgnoresNonSlashInput() {
        SlashCommandRegistry registry = registry();
        SlashCommandCompleter completer = new SlashCommandCompleter(registry);
        List<Candidate> candidates = new ArrayList<>();
        var reader = LineReaderBuilder.builder().build();

        reader.getBuffer().write("hel");
        completer.complete(reader, null, candidates);

        assertTrue(candidates.isEmpty());
    }

    @Test
    void promptUsesBlueCodeWrapPrefix() {
        assertTrue(TerminalSession.PROMPT.contains("CodeWrap>"));
        assertTrue(TerminalSession.PROMPT.startsWith("\u001B[34m"));
    }

    @Test
    void modelOptionsUseConfiguredABCMapping() {
        Map<String, String> models = new LinkedHashMap<>();
        models.put("A", "model-a-id");
        models.put("B", "model-b-id");
        models.put("C", "model-c-id");
        Settings settings = new Settings("key", "url", "A", models, 1000, 5, PermissionMode.ASK, Map.of(), Settings.Compaction.defaults());

        List<TerminalSession.ModelOption> options = TerminalSession.modelOptions(settings);

        assertEquals(List.of("model A", "model B", "model C"), options.stream()
                .map(TerminalSession.ModelOption::label)
                .toList());
        assertEquals(List.of("model-a-id", "model-b-id", "model-c-id"), options.stream()
                .map(TerminalSession.ModelOption::model)
                .toList());
    }

    @Test
    void oldSingleModelConfigIsRejected() {
        Settings settings = new Settings("key", "url", "legacy-model", null, 1000, 5, PermissionMode.ASK, Map.of(), Settings.Compaction.defaults());

        assertFalse(settings.validate().valid());
    }

    @Test
    void permissionModeOptionsContainAskAndFullAccess() {
        List<TerminalSession.PermissionModeOption> options = TerminalSession.permissionModeOptions();

        assertEquals(List.of("Ask", "Full Access"), options.stream()
                .map(TerminalSession.PermissionModeOption::label)
                .toList());
        assertEquals(List.of(PermissionMode.ASK, PermissionMode.FULL_ACCESS), options.stream()
                .map(TerminalSession.PermissionModeOption::mode)
                .toList());
    }

    @Test
    void transcriptSessionOptionsUseSessionIdAndMessageCount() {
        List<TerminalSession.TranscriptSessionOption> options = TerminalSession.transcriptSessionOptions(List.of(
                new TranscriptSession("session-a", "time", 3)
        ));

        assertEquals(List.of("session-a"), options.stream()
                .map(TerminalSession.TranscriptSessionOption::sessionId)
                .toList());
        assertEquals(List.of("session-a (3 messages)"), options.stream()
                .map(TerminalSession.TranscriptSessionOption::label)
                .toList());
    }

    private SlashCommandRegistry registry() {
        return new SlashCommandRegistry(List.of(
                new SlashCommand("help", "Show help", (context, arguments) -> SlashCommand.Result.CONTINUE),
                new SlashCommand("model", "Show model", (context, arguments) -> SlashCommand.Result.CONTINUE),
                new SlashCommand("permissions", "Select permission mode", (context, arguments) -> SlashCommand.Result.CONTINUE),
                new SlashCommand("resume", "Resume session", (context, arguments) -> SlashCommand.Result.CONTINUE),
                new SlashCommand("clear", "Clear working memory", (context, arguments) -> SlashCommand.Result.CONTINUE),
                new SlashCommand("compact", "Compact working memory", (context, arguments) -> SlashCommand.Result.CONTINUE),
                new SlashCommand("exit", "Exit", (context, arguments) -> SlashCommand.Result.EXIT)
        ));
    }

    private List<String> candidateValues(List<Candidate> candidates) {
        return candidates.stream()
                .map(Candidate::value)
                .toList();
    }
}
