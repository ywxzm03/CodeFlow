package com.codeflow.terminal;

import com.codeflow.config.ConfigManager;
import com.codeflow.config.Settings;
import com.codeflow.core.ConversationSession;
import com.codeflow.core.Message;
import com.codeflow.core.QueryEngine;
import com.codeflow.llm.LLMClient;
import com.codeflow.memory.MemoryReflection;
import com.codeflow.memory.MemoryUpdate;
import com.codeflow.memory.TranscriptRecorder;
import com.codeflow.memory.TranscriptSession;
import com.codeflow.memory.TranscriptStore;
import com.codeflow.permissions.PermissionMode;
import com.codeflow.permissions.ToolPermission;
import com.codeflow.permissions.ToolPermissionManager;
import com.codeflow.skills.SkillDefinition;
import com.codeflow.skills.SkillRenderer;
import com.codeflow.skills.SkillStore;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Widget;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 终端交互会话，负责输入循环和 slash 命令。
 */
public final class TerminalSession implements AutoCloseable {
    static final String PROMPT = "\u001B[34mCodeFlow>\u001B[0m ";
    private static final String BLUE = "\u001B[34;1m";
    private static final String RESET = "\u001B[0m";

    private final ConversationSession conversationSession;
    private final LLMClient llmClient;
    private final ConfigManager configManager;
    private final SlashCommandRegistry slashCommands;
    private final ToolPermissionManager toolPermissionManager;
    private final TranscriptStore transcriptStore;
    private final SkillStore skillStore;
    private final SkillRenderer skillRenderer;
    private final AtomicBoolean exitRequested = new AtomicBoolean(false);
    private Settings settings;

    private Terminal terminal;
    private SlashLineReader reader;

    public TerminalSession(
            QueryEngine queryEngine,
            LLMClient llmClient,
            ConfigManager configManager,
            Settings settings,
            ToolPermissionManager toolPermissionManager,
            MemoryReflection memoryReflection,
            TranscriptRecorder transcriptRecorder,
            TranscriptStore transcriptStore,
            SkillStore skillStore,
            SkillRenderer skillRenderer
    ) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        TranscriptRecorder activeTranscriptRecorder = Objects.requireNonNull(transcriptRecorder, "transcriptRecorder must not be null");
        this.conversationSession = new ConversationSession(
                Objects.requireNonNull(queryEngine, "queryEngine must not be null"),
                memoryReflection,
                activeTranscriptRecorder
        );
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
        this.configManager = Objects.requireNonNull(configManager, "configManager must not be null");
        this.skillStore = skillStore;
        this.skillRenderer = skillRenderer;
        this.slashCommands = SlashCommandRegistry.defaults(this.settings.resolvedModel(), skillStore);
        this.toolPermissionManager = Objects.requireNonNull(toolPermissionManager, "toolPermissionManager must not be null");
        this.transcriptStore = transcriptStore;
        this.toolPermissionManager.setConfirmer(this::confirmToolUse);
        if (memoryReflection != null) {
            memoryReflection.setConfirmer(this::confirmMemoryUpdate);
        }
    }

    public void run() {
        try {
            terminal = TerminalBuilder.builder()
                    .system(true)
                    .build();
            reader = createReader(terminal);

            while (!exitRequested.get()) {
                String input;
                try {
                    input = reader.readLine(PROMPT);
                } catch (UserInterruptException e) {
                    terminal.writer().println("^C");
                    terminal.writer().flush();
                    continue;
                } catch (EndOfFileException e) {
                    break;
                }

                handleInput(input.trim());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start terminal session", e);
        } finally {
            close();
        }
    }

    private SlashLineReader createReader(Terminal terminal) {
        SlashLineReader lineReader = new SlashLineReader(terminal);
        lineReader.setCompleter(new SlashCommandCompleter(slashCommands));
        lineReader.setAutosuggestion(LineReader.SuggestionType.NONE);
        lineReader.option(LineReader.Option.AUTO_LIST, true);
        lineReader.option(LineReader.Option.AUTO_MENU_LIST, true);
        lineReader.setVariable(LineReader.COMPLETION_STYLE_LIST_BACKGROUND, "bg:default");
        lineReader.setVariable(LineReader.COMPLETION_STYLE_BACKGROUND, "bg:default");
        lineReader.setVariable(LineReader.COMPLETION_STYLE_LIST_SELECTION, "fg:blue");
        lineReader.setVariable(LineReader.COMPLETION_STYLE_SELECTION, "fg:blue");
        lineReader.setVariable(LineReader.COMPLETION_STYLE_LIST_STARTING, "fg:blue");
        lineReader.setVariable(LineReader.COMPLETION_STYLE_STARTING, "fg:blue");
        installSlashCompletionRefreshWidgets(lineReader);
        return lineReader;
    }

    void installSlashCompletionRefreshWidgets(LineReader lineReader) {
        wrapSlashCompletionWidget(lineReader, LineReader.SELF_INSERT);
        wrapSlashCompletionWidget(lineReader, LineReader.BACKWARD_DELETE_CHAR);
        wrapSlashCompletionWidget(lineReader, LineReader.DELETE_CHAR);
        wrapSlashCompletionWidget(lineReader, LineReader.KILL_LINE);
        wrapSlashCompletionWidget(lineReader, LineReader.KILL_WHOLE_LINE);
        wrapSlashCompletionWidget(lineReader, LineReader.BACKWARD_KILL_WORD);
    }

    private void wrapSlashCompletionWidget(LineReader lineReader, String widgetName) {
        Widget original = lineReader.getWidgets().get(widgetName);
        if (original == null) {
            return;
        }

        lineReader.getWidgets().put(widgetName, () -> {
            boolean result = original.apply();
            refreshSlashCompletion(lineReader);
            return result;
        });
    }

    private void refreshSlashCompletion(LineReader lineReader) {
        String buffer = lineReader.getBuffer().toString();
        if (buffer.startsWith("/")) {
            lineReader.setAutosuggestion(LineReader.SuggestionType.COMPLETER);
            if (lineReader instanceof SlashLineReader slashLineReader) {
                slashLineReader.markSlashSuggestionsVisible();
            }
        } else {
            lineReader.setAutosuggestion(LineReader.SuggestionType.NONE);
            if (lineReader instanceof SlashLineReader slashLineReader) {
                slashLineReader.clearSlashSuggestions();
            }
        }
    }

    private void handleInput(String input) {
        if (input.isEmpty()) {
            return;
        }

        if (slashCommands.isSlashCommandInput(input)) {
            handleSlashCommand(input);
            return;
        }

        if ("exit".equalsIgnoreCase(input)) {
            exitRequested.set(true);
            terminal.writer().println("Goodbye!");
            terminal.writer().flush();
            return;
        }

        try {
            QueryEngine.QueryResult result = conversationSession.handleUserInput(input);
            terminal.writer().println(result.finalResponse());
            terminal.writer().flush();
        } catch (Exception e) {
            terminal.writer().println();
            terminal.writer().println("Error: " + e.getMessage());
            e.printStackTrace(terminal.writer());
            terminal.writer().flush();
        }
    }

    private void handleSlashCommand(String input) {
        if ("/".equals(input)) {
            printSlashCommandList(slashCommands.commands());
            return;
        }

        if ("/clear".equals(input)) {
            handleClearCommand();
            return;
        }

        if ("/compact".equals(input)) {
            handleCompactCommand();
            return;
        }

        if ("/help".equals(input) || input.startsWith("/help ")) {
            printSlashCommandList(slashCommands.commands());
            return;
        }

        if ("/hook".equals(input) || input.startsWith("/hook ")) {
            handleHookCommand();
            return;
        }

        if ("/model".equals(input)) {
            handleModelCommand();
            return;
        }

        if ("/permissions".equals(input)) {
            handlePermissionsCommand();
            return;
        }

        if ("/resume".equals(input) || input.startsWith("/resume ")) {
            handleResumeCommand(slashArguments(input));
            return;
        }

        slashCommands.exact(input).ifPresentOrElse(command -> {
            String arguments = slashArguments(input);
            SlashCommand.Result result = command.handler().handle(new TerminalCommandContext(), arguments);
            if (result == SlashCommand.Result.EXIT) {
                exitRequested.set(true);
            }
        }, () -> {
            if (handleSkillCommand(input)) {
                return;
            }
            var matches = slashCommands.match(input);
            if (!matches.isEmpty()) {
                printSlashCommandList(matches);
                return;
            }
            terminal.writer().println("Unknown slash command: " + input);
            terminal.writer().println("Type / to see available commands.");
            terminal.writer().flush();
        });
    }

    private boolean handleSkillCommand(String input) {
        java.util.Optional<String> renderedSkill = renderSkillCommandInput(input);
        if (renderedSkill.isEmpty()) {
            return false;
        }

        try {
            QueryEngine.QueryResult result = conversationSession.handleUserInput(renderedSkill.get());
            terminal.writer().println(result.finalResponse());
            terminal.writer().flush();
        } catch (Exception e) {
            terminal.writer().println();
            terminal.writer().println("Error: " + e.getMessage());
            e.printStackTrace(terminal.writer());
            terminal.writer().flush();
        }
        return true;
    }

    java.util.Optional<String> renderSkillCommandInput(String input) {
        if (skillStore == null || skillRenderer == null) {
            return java.util.Optional.empty();
        }

        String commandName = slashCommandName(input);
        java.util.Optional<SkillDefinition> skill = skillStore.find(commandName);
        if (skill.isEmpty()) {
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(skillRenderer.render(skill.get(), slashArguments(input), "user"));
    }

    private void handleClearCommand() {
        conversationSession.clear();
        terminal.puts(InfoCmp.Capability.clear_screen);
        terminal.flush();
        terminal.writer().println("Working memory cleared.");
        terminal.writer().flush();
    }

    private void handleCompactCommand() {
        QueryEngine.CompactResult result = conversationSession.compact();
        switch (result.status()) {
            case COMPACTED -> terminal.writer().println(
                    "Compact completed. Working memory: " + result.beforeMessages() + " -> " + result.afterMessages() + " messages."
            );
            case NOT_NEEDED -> terminal.writer().println("Nothing to compact.");
            case UNAVAILABLE -> terminal.writer().println("Compact is unavailable: " + result.reason() + ".");
            case FAILED -> terminal.writer().println("Compact failed: " + result.reason());
        }
        terminal.writer().flush();
    }

    private void handleHookCommand() {
        terminal.writer().println(formatConfiguredHooks(settings, configManager.getConfigFilePath()));
        terminal.writer().flush();
    }

    private void handleModelCommand() {
        List<ModelOption> options = modelOptions(settings);
        if (options.isEmpty()) {
            terminal.writer().println("No models configured.");
            terminal.writer().flush();
            return;
        }

        try {
            ModelOption selected = chooseModel(options);
            if (selected != null) {
                applyModelSelection(selected);
            }
        } catch (IOException e) {
            terminal.writer().println("Failed to select model: " + e.getMessage());
            terminal.writer().flush();
        }
    }

    private ModelOption chooseModel(List<ModelOption> options) throws IOException {
        int selected = selectedModelIndex(options);
        Attributes originalAttributes = terminal.enterRawMode();
        try {
            hideCursor();
            renderModelOptions(options, selected, false);
            while (true) {
                switch (readModelSelectionKey()) {
                    case UP -> {
                        selected = selected == 0 ? options.size() - 1 : selected - 1;
                        renderModelOptions(options, selected, true);
                    }
                    case DOWN -> {
                        selected = selected == options.size() - 1 ? 0 : selected + 1;
                        renderModelOptions(options, selected, true);
                    }
                    case ACCEPT -> {
                        return options.get(selected);
                    }
                    case CANCEL -> {
                        return null;
                    }
                    case IGNORED -> {
                        // Ignore unrelated keys while the selector is open.
                    }
                }
            }
        } finally {
            clearModelOptions(options.size());
            showCursor();
            terminal.setAttributes(originalAttributes);
        }
    }

    private void applyModelSelection(ModelOption option) throws IOException {
        settings = settings.withModel(option.key());
        llmClient.setModel(option.model());
        configManager.save(settings);
    }

    private void handlePermissionsCommand() {
        List<PermissionModeOption> options = permissionModeOptions();
        try {
            PermissionModeOption selected = choosePermissionMode(options);
            if (selected != null) {
                applyPermissionModeSelection(selected);
            }
        } catch (IOException e) {
            terminal.writer().println("Failed to select permission mode: " + e.getMessage());
            terminal.writer().flush();
        }
    }

    private PermissionModeOption choosePermissionMode(List<PermissionModeOption> options) throws IOException {
        int selected = selectedPermissionModeIndex(options);
        Attributes originalAttributes = terminal.enterRawMode();
        try {
            hideCursor();
            renderPermissionModeOptions(options, selected, false);
            while (true) {
                switch (readModelSelectionKey()) {
                    case UP -> {
                        selected = selected == 0 ? options.size() - 1 : selected - 1;
                        renderPermissionModeOptions(options, selected, true);
                    }
                    case DOWN -> {
                        selected = selected == options.size() - 1 ? 0 : selected + 1;
                        renderPermissionModeOptions(options, selected, true);
                    }
                    case ACCEPT -> {
                        return options.get(selected);
                    }
                    case CANCEL -> {
                        return null;
                    }
                    case IGNORED -> {
                        // Ignore unrelated keys while the selector is open.
                    }
                }
            }
        } finally {
            clearModelOptions(options.size());
            showCursor();
            terminal.setAttributes(originalAttributes);
        }
    }

    private void applyPermissionModeSelection(PermissionModeOption option) throws IOException {
        settings = settings.withPermissionMode(option.mode());
        toolPermissionManager.setPermissionMode(option.mode());
        configManager.save(settings);
    }

    private void handleResumeCommand(String sessionIdArgument) {
        if (transcriptStore == null) {
            terminal.writer().println("Transcript is not enabled.");
            terminal.writer().flush();
            return;
        }

        try {
            String sessionId = sessionIdArgument == null || sessionIdArgument.isBlank()
                    ? chooseResumeSessionId()
                    : sessionIdArgument.trim();
            if (sessionId == null) {
                return;
            }

            conversationSession.resume(sessionId, transcriptStore.loadWorkingMemoryEntriesForResume(sessionId));
            terminal.writer().println("Resumed session: " + sessionId);
            terminal.writer().flush();
        } catch (IOException | IllegalArgumentException e) {
            terminal.writer().println("Failed to resume session: " + e.getMessage());
            terminal.writer().flush();
        }
    }

    private String chooseResumeSessionId() throws IOException {
        List<TranscriptSessionOption> options = transcriptSessionOptions(transcriptStore.listSessions());
        if (options.isEmpty()) {
            terminal.writer().println("No transcript sessions to resume.");
            terminal.writer().flush();
            return null;
        }

        TranscriptSessionOption selected = chooseTranscriptSession(options);
        return selected == null ? null : selected.sessionId();
    }

    private TranscriptSessionOption chooseTranscriptSession(List<TranscriptSessionOption> options) throws IOException {
        int selected = 0;
        Attributes originalAttributes = terminal.enterRawMode();
        try {
            hideCursor();
            renderTranscriptSessionOptions(options, selected, false);
            while (true) {
                switch (readModelSelectionKey()) {
                    case UP -> {
                        selected = selected == 0 ? options.size() - 1 : selected - 1;
                        renderTranscriptSessionOptions(options, selected, true);
                    }
                    case DOWN -> {
                        selected = selected == options.size() - 1 ? 0 : selected + 1;
                        renderTranscriptSessionOptions(options, selected, true);
                    }
                    case ACCEPT -> {
                        return options.get(selected);
                    }
                    case CANCEL -> {
                        return null;
                    }
                    case IGNORED -> {
                        // Ignore unrelated keys while the selector is open.
                    }
                }
            }
        } finally {
            clearModelOptions(options.size());
            showCursor();
            terminal.setAttributes(originalAttributes);
        }
    }

    private boolean confirmToolUse(String toolName, String input) {
        if (terminal == null || reader == null) {
            return false;
        }

        terminal.writer().println();
        terminal.writer().println("Tool requires permission: " + toolName);
        terminal.writer().println(formatToolInputForConfirmation(input));
        terminal.writer().flush();

        try {
            String answer = reader.readLine("Allow " + toolName + "? [y/N] ");
            return "y".equalsIgnoreCase(answer.trim()) || "yes".equalsIgnoreCase(answer.trim());
        } catch (UserInterruptException | EndOfFileException e) {
            terminal.writer().println();
            terminal.writer().flush();
            return false;
        }
    }

    private String formatToolInputForConfirmation(String input) {
        if (input == null || input.isBlank()) {
            return "{}";
        }

        int maxLength = 2000;
        if (input.length() <= maxLength) {
            return input;
        }
        return input.substring(0, maxLength) + "\n... truncated";
    }

    private boolean confirmMemoryUpdate(MemoryUpdate update) {
        if (terminal == null || reader == null) {
            return false;
        }

        terminal.writer().println();
        terminal.writer().println("Memory update requires approval:");
        terminal.writer().println("Layer: " + update.layer().directoryName());
        terminal.writer().println("File: " + update.relativePath());
        terminal.writer().println("Reason: " + update.reason());
        terminal.writer().println();
        terminal.writer().println("Content:");
        terminal.writer().println(formatMemoryContentForConfirmation(update.content()));
        if (update.indexEntry() != null && !update.indexEntry().isBlank()) {
            terminal.writer().println();
            terminal.writer().println("L1 index update:");
            terminal.writer().println(update.indexEntry());
        }
        terminal.writer().flush();

        try {
            String answer = reader.readLine("Update memory? [y/N] ");
            return "y".equalsIgnoreCase(answer.trim()) || "yes".equalsIgnoreCase(answer.trim());
        } catch (UserInterruptException | EndOfFileException e) {
            terminal.writer().println();
            terminal.writer().flush();
            return false;
        }
    }

    private String formatMemoryContentForConfirmation(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }

        int maxLength = 3000;
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "\n... truncated";
    }

    private int selectedPermissionModeIndex(List<PermissionModeOption> options) {
        PermissionMode selectedMode = settings.resolvedPermissionMode();
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).mode() == selectedMode) {
                return i;
            }
        }
        return 0;
    }

    private void renderPermissionModeOptions(List<PermissionModeOption> options, int selected, boolean redraw) {
        if (redraw) {
            terminal.writer().print("\u001B[" + options.size() + "A");
        }
        for (int i = 0; i < options.size(); i++) {
            terminal.writer().print("\r\u001B[2K");
            terminal.writer().print(formatPermissionModeOption(options.get(i), i == selected));
            terminal.writer().print("\r\n");
        }
        terminal.writer().flush();
    }

    private void renderTranscriptSessionOptions(List<TranscriptSessionOption> options, int selected, boolean redraw) {
        if (redraw) {
            terminal.writer().print("\u001B[" + options.size() + "A");
        }
        for (int i = 0; i < options.size(); i++) {
            terminal.writer().print("\r\u001B[2K");
            terminal.writer().print(formatTranscriptSessionOption(options.get(i), i == selected));
            terminal.writer().print("\r\n");
        }
        terminal.writer().flush();
    }

    private int selectedModelIndex(List<ModelOption> options) {
        String selected = settings.model();
        for (int i = 0; i < options.size(); i++) {
            ModelOption option = options.get(i);
            if (option.key().equals(selected)) {
                return i;
            }
        }
        return 0;
    }

    private void renderModelOptions(List<ModelOption> options, int selected, boolean redraw) {
        if (redraw) {
            terminal.writer().print("\u001B[" + options.size() + "A");
        }
        for (int i = 0; i < options.size(); i++) {
            terminal.writer().print("\r\u001B[2K");
            terminal.writer().print(formatModelOption(options.get(i), i == selected));
            terminal.writer().print("\r\n");
        }
        terminal.writer().flush();
    }

    private void clearModelOptions(int lines) {
        if (lines <= 0) {
            return;
        }
        terminal.writer().print("\u001B[" + lines + "A");
        terminal.puts(InfoCmp.Capability.clr_eos);
        terminal.flush();
    }

    private void hideCursor() {
        terminal.writer().print("\u001B[?25l");
        terminal.flush();
    }

    private void showCursor() {
        terminal.writer().print("\u001B[?25h");
        terminal.flush();
    }

    private String formatModelOption(ModelOption option, boolean selected) {
        if (selected) {
            return BLUE + "> " + option.label() + RESET;
        }
        return "  " + option.label();
    }

    private String formatPermissionModeOption(PermissionModeOption option, boolean selected) {
        if (selected) {
            return BLUE + "> " + option.label() + RESET;
        }
        return "  " + option.label();
    }

    private String formatTranscriptSessionOption(TranscriptSessionOption option, boolean selected) {
        if (selected) {
            return BLUE + "> " + option.label() + RESET;
        }
        return "  " + option.label();
    }

    private ModelSelectionKey readModelSelectionKey() throws IOException {
        NonBlockingReader input = terminal.reader();
        int character = input.read();
        if (character == NonBlockingReader.EOF || character == 3) {
            return ModelSelectionKey.CANCEL;
        }
        if (character == '\r' || character == '\n') {
            return ModelSelectionKey.ACCEPT;
        }
        if (character != 27) {
            return ModelSelectionKey.IGNORED;
        }

        int prefix = input.read(50L);
        if (prefix == NonBlockingReader.READ_EXPIRED) {
            return ModelSelectionKey.CANCEL;
        }
        if (prefix != '[' && prefix != 'O') {
            return ModelSelectionKey.IGNORED;
        }

        int code = input.read(50L);
        return switch (code) {
            case 'A' -> ModelSelectionKey.UP;
            case 'B' -> ModelSelectionKey.DOWN;
            default -> ModelSelectionKey.IGNORED;
        };
    }

    private String slashArguments(String input) {
        String trimmed = input.trim();
        int spaceIndex = trimmed.indexOf(' ');
        return spaceIndex >= 0 ? trimmed.substring(spaceIndex + 1).trim() : "";
    }

    private String slashCommandName(String input) {
        String trimmed = input.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        int spaceIndex = trimmed.indexOf(' ');
        return spaceIndex >= 0 ? trimmed.substring(0, spaceIndex) : trimmed;
    }

    private void printSlashCommandList(java.util.List<SlashCommand> commands) {
        terminal.writer().println("Available slash commands:");
        for (SlashCommand command : commands) {
            terminal.writer().printf("  %-10s %s%n", command.displayName(), command.description());
        }
        terminal.writer().flush();
    }

    static List<ModelOption> modelOptions(Settings settings) {
        Map<String, String> models = settings.resolvedModels();
        List<ModelOption> options = new ArrayList<>();
        for (String key : List.of("A", "B", "C")) {
            String model = models.get(key);
            if (model != null && !model.isBlank()) {
                options.add(new ModelOption(key, "model " + key, model));
            }
        }
        return options;
    }

    static List<PermissionModeOption> permissionModeOptions() {
        return List.of(
                new PermissionModeOption(PermissionMode.ASK, PermissionMode.ASK.displayName()),
                new PermissionModeOption(PermissionMode.FULL_ACCESS, PermissionMode.FULL_ACCESS.displayName())
        );
    }

    static List<TranscriptSessionOption> transcriptSessionOptions(List<TranscriptSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return List.of();
        }
        return sessions.stream()
                .map(session -> new TranscriptSessionOption(
                        session.sessionId(),
                        session.sessionId() + " (" + session.messageCount() + " messages)"
                ))
                .toList();
    }

    static String formatConfiguredHooks(Settings settings, Path settingsPath) {
        Settings resolved = settings == null ? Settings.defaults() : settings;
        StringBuilder builder = new StringBuilder();
        builder.append("Configured hooks:\n");
        builder.append("  PreToolUse\n");
        builder.append("    Handler: settings tool permissions\n");
        builder.append("    Source: ").append(settingsPath == null ? "settings.json" : settingsPath).append('\n');
        builder.append("    Permission mode: ").append(resolved.resolvedPermissionMode().configValue()).append('\n');

        Map<String, ToolPermission> permissions = resolved.resolvedToolPermissions();
        if (permissions.isEmpty()) {
            builder.append("    Tool permissions: none\n");
        } else {
            builder.append("    Tool permissions:\n");
            permissions.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> builder.append("      ")
                            .append(entry.getKey())
                            .append(": ")
                            .append(entry.getValue().configValue())
                            .append('\n'));
        }

        builder.append("  Stop\n");
        Settings.CommandHook stopHook = resolved.resolvedHooks().stop();
        if (stopHook == null || !stopHook.enabled()) {
            builder.append("    Handler: disabled\n");
        } else {
            builder.append("    Handler: command\n");
            builder.append("    Command: ").append(stopHook.command()).append('\n');
            builder.append("    Timeout: ").append(stopHook.resolvedTimeoutSeconds()).append("s\n");
        }
        return builder.toString();
    }

    @Override
    public void close() {
        if (terminal != null) {
            try {
                terminal.close();
            } catch (IOException ignored) {
                // Best-effort terminal cleanup.
            }
        }
    }

    private final class TerminalCommandContext implements SlashCommand.Context {
        @Override
        public void print(String text) {
            terminal.writer().println(text);
            terminal.writer().flush();
        }

        @Override
        public void clearScreen() {
            terminal.puts(InfoCmp.Capability.clear_screen);
            terminal.flush();
        }

        @Override
        public void requestExit() {
            exitRequested.set(true);
            terminal.writer().println("Goodbye!");
            terminal.writer().flush();
        }
    }

    record ModelOption(String key, String label, String model) {}

    record PermissionModeOption(PermissionMode mode, String label) {}

    record TranscriptSessionOption(String sessionId, String label) {}

    private enum ModelSelectionKey {
        UP,
        DOWN,
        ACCEPT,
        CANCEL,
        IGNORED
    }
}
