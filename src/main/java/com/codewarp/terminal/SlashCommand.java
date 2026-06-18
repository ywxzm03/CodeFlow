package com.codewarp.terminal;

/**
 * Local terminal command that is handled before user input is sent to the LLM.
 */
public record SlashCommand(
        String name,
        String description,
        Handler handler
) {
    public SlashCommand {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Slash command name must not be blank");
        }
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        if (description == null) {
            description = "";
        }
    }

    public String displayName() {
        return "/" + name;
    }

    @FunctionalInterface
    public interface Handler {
        Result handle(Context context, String arguments);
    }

    public interface Context {
        void print(String text);

        void clearScreen();

        void requestExit();
    }

    public enum Result {
        CONTINUE,
        EXIT
    }
}
