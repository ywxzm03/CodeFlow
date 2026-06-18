package com.codewarp.terminal;

import org.jline.reader.impl.LineReaderImpl;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;

import java.util.HashMap;

/**
 * LineReader extension used only to clear JLine's internal completion post area.
 */
final class SlashLineReader extends LineReaderImpl {
    private boolean slashSuggestionsVisible;

    SlashLineReader(Terminal terminal) {
        super(terminal, terminal.getName(), new HashMap<>());
        setHistory(new DefaultHistory());
    }

    void markSlashSuggestionsVisible() {
        slashSuggestionsVisible = true;
    }

    void clearSlashSuggestions() {
        if (!slashSuggestionsVisible) {
            return;
        }
        slashSuggestionsVisible = false;
        post = null;
        redrawLine();
        redisplay();
    }
}
