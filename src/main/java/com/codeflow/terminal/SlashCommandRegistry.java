package com.codeflow.terminal;

import com.codeflow.skills.SkillDefinition;
import com.codeflow.skills.SkillStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Registry and matcher for local slash commands.
 */
public final class SlashCommandRegistry {
    private final List<SlashCommand> commands;
    private final Supplier<List<SkillDefinition>> skillsSupplier;

    public SlashCommandRegistry(List<SlashCommand> commands) {
        this(commands, List::of);
    }

    public SlashCommandRegistry(List<SlashCommand> commands, Supplier<List<SkillDefinition>> skillsSupplier) {
        this.commands = commands.stream()
                .sorted(Comparator.comparing(SlashCommand::name))
                .toList();
        this.skillsSupplier = skillsSupplier == null ? List::of : skillsSupplier;
    }

    public static SlashCommandRegistry defaults(String modelName, SkillStore skillStore) {
        List<SlashCommand> commands = new ArrayList<>();
        commands.add(new SlashCommand("agent", "Show running agent names", (context, arguments) -> SlashCommand.Result.CONTINUE));
        commands.add(new SlashCommand("batch", "Plan and launch parallel worktree subagents", (context, arguments) -> SlashCommand.Result.CONTINUE));
        commands.add(new SlashCommand("clear", "Clear working memory and terminal screen", (context, arguments) -> SlashCommand.Result.CONTINUE));
        commands.add(new SlashCommand("compact", "Compact working memory", (context, arguments) -> SlashCommand.Result.CONTINUE));
        commands.add(new SlashCommand("exit", "Exit CodeFlow", (context, arguments) -> {
            context.requestExit();
            return SlashCommand.Result.EXIT;
        }));
        commands.add(new SlashCommand("help", "Show available slash commands", (context, arguments) -> {
            context.print(formatHelp(commands));
            return SlashCommand.Result.CONTINUE;
        }));
        commands.add(new SlashCommand("hook", "Show configured hooks", (context, arguments) -> SlashCommand.Result.CONTINUE));
        commands.add(new SlashCommand("model", "Select active model", (context, arguments) -> {
            context.print("Current model: " + modelName);
            return SlashCommand.Result.CONTINUE;
        }));
        commands.add(new SlashCommand("permissions", "Select permission mode", (context, arguments) -> SlashCommand.Result.CONTINUE));
        commands.add(new SlashCommand("resume", "Resume a transcript session", (context, arguments) -> SlashCommand.Result.CONTINUE));
        return new SlashCommandRegistry(commands, skillStore == null ? List::of : skillStore::list);
    }

    public List<SlashCommand> commands() {
        return mergedCommands();
    }

    public List<SlashCommand> match(String input) {
        if (input == null || !input.startsWith("/")) {
            return List.of();
        }

        String commandPrefix = commandPrefix(input);
        return mergedCommands().stream()
                .filter(command -> command.name().startsWith(commandPrefix))
                .toList();
    }

    public Optional<SlashCommand> exact(String input) {
        if (input == null || !input.startsWith("/")) {
            return Optional.empty();
        }

        String commandName = commandName(input);
        return commands.stream()
                .filter(command -> command.name().equals(commandName))
                .findFirst();
    }

    public boolean isSlashCommandInput(String input) {
        return input != null && input.startsWith("/");
    }

    private static String commandPrefix(String input) {
        String stripped = input.substring(1);
        int spaceIndex = stripped.indexOf(' ');
        return spaceIndex >= 0 ? stripped.substring(0, spaceIndex) : stripped;
    }

    private static String commandName(String input) {
        return commandPrefix(input.trim());
    }

    private List<SlashCommand> mergedCommands() {
        List<SlashCommand> merged = new ArrayList<>(commands);
        for (SkillDefinition skill : skillsSupplier.get()) {
            if (isBuiltInCommandName(skill.name())) {
                continue;
            }
            merged.add(new SlashCommand(
                    skill.name(),
                    skillDescription(skill),
                    (context, arguments) -> SlashCommand.Result.CONTINUE
            ));
        }
        return merged.stream()
                .sorted(Comparator.comparing(SlashCommand::name))
                .toList();
    }

    private boolean isBuiltInCommandName(String name) {
        return commands.stream().anyMatch(command -> command.name().equals(name));
    }

    private static String skillDescription(SkillDefinition skill) {
        StringBuilder builder = new StringBuilder(skill.description());
        if (skill.argumentHint() != null && !skill.argumentHint().isBlank()) {
            builder.append(" ").append(skill.argumentHint());
        }
        return builder.toString();
    }

    private static String formatHelp(List<SlashCommand> commands) {
        int width = commands.stream()
                .mapToInt(command -> command.displayName().length())
                .max()
                .orElse(0);

        StringBuilder builder = new StringBuilder();
        for (SlashCommand command : commands.stream()
                .sorted(Comparator.comparing(SlashCommand::name))
                .toList()) {
            builder.append("  ")
                    .append(command.displayName())
                    .append(" ".repeat(Math.max(1, width - command.displayName().length() + 2)))
                    .append(command.description())
                    .append('\n');
        }
        return builder.toString().stripTrailing();
    }
}
