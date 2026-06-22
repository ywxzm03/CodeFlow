package com.codeflow.terminal;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

/**
 * Tab completion for slash commands. Live filtering is handled by TerminalSession.
 */
public final class SlashCommandCompleter implements Completer {
    private final SlashCommandRegistry registry;

    public SlashCommandCompleter(SlashCommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = reader.getBuffer().toString();
        if (!registry.isSlashCommandInput(buffer)) {
            return;
        }

        for (SlashCommand command : registry.match(buffer)) {
            candidates.add(new Candidate(
                    command.displayName(),
                    command.displayName(),
                    "slash commands",
                    command.description(),
                    null,
                    null,
                    true
            ));
        }
    }
}
