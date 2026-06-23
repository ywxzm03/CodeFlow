package com.codeflow.terminal;

import com.codeflow.config.Settings;
import com.codeflow.llm.LLMClient;
import com.codeflow.memory.TranscriptSession;
import com.codeflow.memory.TranscriptRecorder;
import com.codeflow.permissions.PermissionMode;
import com.codeflow.permissions.ToolPermission;
import com.codeflow.permissions.ToolPermissionManager;
import com.codeflow.skills.SkillRenderer;
import com.codeflow.skills.SkillStore;
import com.codeflow.core.QueryEngine;
import com.codeflow.core.Message;
import com.codeflow.tools.Tool;
import org.jline.reader.Candidate;
import org.jline.reader.LineReaderBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashCommandRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void slashMatchesAllCommands() {
        SlashCommandRegistry registry = registry();

        List<String> matches = registry.match("/").stream()
                .map(SlashCommand::displayName)
                .toList();

        assertEquals(List.of("/clear", "/compact", "/exit", "/help", "/hook", "/model", "/permissions", "/resume"), matches);
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

        assertEquals(List.of("/clear", "/compact", "/exit", "/help", "/hook", "/model", "/permissions", "/resume"), candidateValues(candidates));
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
    void completerFiltersHookCommandAsUserTypes() {
        SlashCommandRegistry registry = registry();
        SlashCommandCompleter completer = new SlashCommandCompleter(registry);
        List<Candidate> candidates = new ArrayList<>();
        var reader = LineReaderBuilder.builder().build();

        reader.getBuffer().write("/ho");
        completer.complete(reader, null, candidates);

        assertEquals(List.of("/hook"), candidateValues(candidates));
    }

    @Test
    void slashRegistryIncludesSkills() throws Exception {
        writeSkill(tempDir.resolve("skills/commit"));
        SlashCommandRegistry registry = new SlashCommandRegistry(List.of(
                new SlashCommand("help", "Show help", (context, arguments) -> SlashCommand.Result.CONTINUE)
        ), new SkillStore(tempDir.resolve("skills"), tempDir.resolve("project"))::list);

        List<String> matches = registry.match("/").stream()
                .map(SlashCommand::displayName)
                .toList();

        assertEquals(List.of("/commit", "/help"), matches);
    }

    @Test
    void slashRegistryDoesNotLetSkillOverrideBuiltInCommand() throws Exception {
        writeSkill(tempDir.resolve("skills/help"));
        SlashCommandRegistry registry = new SlashCommandRegistry(List.of(
                new SlashCommand("help", "Show help", (context, arguments) -> SlashCommand.Result.CONTINUE)
        ), new SkillStore(tempDir.resolve("skills"), tempDir.resolve("project"))::list);

        List<SlashCommand> commands = registry.commands();

        assertEquals(1, commands.size());
        assertEquals("/help", commands.getFirst().displayName());
        assertEquals("Show help", commands.getFirst().description());
    }

    @Test
    void completerListsSkillCommandsForSlashInput() throws Exception {
        writeSkill(tempDir.resolve("skills/commit"));
        SlashCommandRegistry registry = new SlashCommandRegistry(List.of(
                new SlashCommand("help", "Show help", (context, arguments) -> SlashCommand.Result.CONTINUE)
        ), new SkillStore(tempDir.resolve("skills"), tempDir.resolve("project"))::list);
        SlashCommandCompleter completer = new SlashCommandCompleter(registry);
        List<Candidate> candidates = new ArrayList<>();
        var reader = LineReaderBuilder.builder().build();

        reader.getBuffer().write("/co");
        completer.complete(reader, null, candidates);

        assertEquals(List.of("/commit"), candidateValues(candidates));
    }

    @Test
    void terminalRendersSkillSlashInput() throws Exception {
        writeSkill(tempDir.resolve("skills/commit"));
        SkillStore skillStore = new SkillStore(tempDir.resolve("skills"), tempDir.resolve("project"));
        TerminalSession session = new TerminalSession(
                new QueryEngine(new StaticStreamingClient(), List.of(), 1, ToolPermissionManager.askByDefault(), null, null),
                new StaticStreamingClient(),
                new com.codeflow.config.ConfigManager(),
                new Settings("key", "url", "A", Settings.defaults().resolvedModels(), 1000, 1, PermissionMode.ASK, Map.of(), Settings.Compaction.defaults()),
                ToolPermissionManager.askByDefault(),
                null,
                TranscriptRecorder.disabled(),
                null,
                skillStore,
                new SkillRenderer()
        );

        String rendered = session.renderSkillCommandInput("/commit add skills").orElseThrow();

        assertTrue(rendered.contains("<skill_invocation>"));
        assertTrue(rendered.contains("<name>commit</name>"));
        assertTrue(rendered.contains("<args>add skills</args>"));
        assertTrue(rendered.contains("<source>user</source>"));
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
        assertTrue(TerminalSession.PROMPT.contains("CodeFlow>"));
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

    @Test
    void formatConfiguredHooksShowsInternalHooksAndToolPermissions() {
        Settings settings = new Settings(
                "key",
                "url",
                "A",
                Settings.defaults().resolvedModels(),
                1000,
                5,
                PermissionMode.FULL_ACCESS,
                Map.of(
                        "Read", ToolPermission.ALLOW,
                        "Bash", ToolPermission.DENY
                ),
                Settings.Compaction.defaults()
        );

        String formatted = TerminalSession.formatConfiguredHooks(settings, tempDir.resolve("settings.json"));

        assertTrue(formatted.contains("Configured hooks:"));
        assertTrue(formatted.contains("PreToolUse"));
        assertTrue(formatted.contains("Handler: settings tool permissions"));
        assertTrue(formatted.contains("Source: " + tempDir.resolve("settings.json")));
        assertTrue(formatted.contains("Permission mode: full_access"));
        assertTrue(formatted.contains("Bash: deny"));
        assertTrue(formatted.contains("Read: allow"));
        assertTrue(formatted.contains("Stop"));
        assertTrue(formatted.contains("Handler: disabled"));
    }

    private SlashCommandRegistry registry() {
        return new SlashCommandRegistry(List.of(
                new SlashCommand("help", "Show help", (context, arguments) -> SlashCommand.Result.CONTINUE),
                new SlashCommand("hook", "Show hooks", (context, arguments) -> SlashCommand.Result.CONTINUE),
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

    private void writeSkill(Path skillDir) throws Exception {
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                description: Write commit message
                argument_hint: <change summary>
                ---
                Use conventional commits.
                """);
    }

    private static final class StaticStreamingClient implements LLMClient {
        @Override
        public LLMResponse call(String systemPrompt, List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<StreamEvent> callStreaming(String systemPrompt, List<Message> messages, List<Tool> tools) {
            return Flux.just(new StreamEvent.TextDelta("done"));
        }

        @Override
        public void setModel(String model) {
        }
    }
}
