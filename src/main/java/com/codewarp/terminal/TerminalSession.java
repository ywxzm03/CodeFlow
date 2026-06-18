package com.codewarp.terminal;

import com.codewarp.config.ConfigManager;
import com.codewarp.config.Settings;
import com.codewarp.core.QueryEngine;
import com.codewarp.llm.LLMClient;
import com.codewarp.permissions.PermissionMode;
import com.codewarp.permissions.ToolPermissionManager;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Interactive terminal session with live slash command suggestions.
 */
public final class TerminalSession implements AutoCloseable {
    static final String PROMPT = "\u001B[34mCodeWrap>\u001B[0m ";
    private static final String BLUE = "\u001B[34;1m";
    private static final String RESET = "\u001B[0m";

    private final QueryEngine queryEngine;
    private final LLMClient llmClient;
    private final ConfigManager configManager;
    private final SlashCommandRegistry slashCommands;
    private final ToolPermissionManager toolPermissionManager;
    private final AtomicBoolean exitRequested = new AtomicBoolean(false);
    private Settings settings;

    private Terminal terminal;
    private SlashLineReader reader;

    public TerminalSession(QueryEngine queryEngine, Settings settings) {
        this(queryEngine, null, null, settings, SlashCommandRegistry.defaults(settings.resolvedModel()), null);
    }

    public TerminalSession(QueryEngine queryEngine, LLMClient llmClient, ConfigManager configManager, Settings settings) {
        this(queryEngine, llmClient, configManager, settings, SlashCommandRegistry.defaults(settings.resolvedModel()), null);
    }

    public TerminalSession(
            QueryEngine queryEngine,
            LLMClient llmClient,
            ConfigManager configManager,
            Settings settings,
            ToolPermissionManager toolPermissionManager
    ) {
        this(queryEngine, llmClient, configManager, settings, SlashCommandRegistry.defaults(settings.resolvedModel()), toolPermissionManager);
    }

    TerminalSession(QueryEngine queryEngine, SlashCommandRegistry slashCommands) {
        this(queryEngine, null, null, Settings.defaults(), slashCommands, null);
    }

    TerminalSession(
            QueryEngine queryEngine,
            LLMClient llmClient,
            ConfigManager configManager,
            Settings settings,
            SlashCommandRegistry slashCommands,
            ToolPermissionManager toolPermissionManager
    ) {
        this.queryEngine = queryEngine;
        this.llmClient = llmClient;
        this.configManager = configManager;
        this.settings = settings;
        this.slashCommands = slashCommands;
        this.toolPermissionManager = toolPermissionManager;
        if (this.toolPermissionManager != null) {
            this.toolPermissionManager.setConfirmer(this::confirmToolUse);
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
            QueryEngine.QueryResult result = queryEngine.query(input);
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

        if ("/model".equals(input)) {
            handleModelCommand();
            return;
        }

        if ("/permissions".equals(input)) {
            handlePermissionsCommand();
            return;
        }

        slashCommands.exact(input).ifPresentOrElse(command -> {
            String arguments = slashArguments(input);
            SlashCommand.Result result = command.handler().handle(new TerminalCommandContext(), arguments);
            if (result == SlashCommand.Result.EXIT) {
                exitRequested.set(true);
            }
        }, () -> {
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
        if (llmClient != null) {
            llmClient.setModel(option.model());
        }
        if (configManager != null) {
            configManager.save(settings);
        }
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
        if (toolPermissionManager != null) {
            toolPermissionManager.setPermissionMode(option.mode());
        }
        if (configManager != null) {
            configManager.save(settings);
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

    private int selectedModelIndex(List<ModelOption> options) {
        String selected = settings.model();
        String resolved = settings.resolvedModel();
        for (int i = 0; i < options.size(); i++) {
            ModelOption option = options.get(i);
            if (option.key().equals(selected) || option.model().equals(selected) || option.model().equals(resolved)) {
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

    private enum ModelSelectionKey {
        UP,
        DOWN,
        ACCEPT,
        CANCEL,
        IGNORED
    }
}
