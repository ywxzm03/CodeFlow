package com.codewarp.util;

/**
 * Central console output switch.
 */
public final class Console {
    private static final boolean QUIET = Boolean.parseBoolean(System.getProperty("codewarp.quiet", "true"));

    private Console() {
    }

    public static boolean isQuiet() {
        return QUIET;
    }

    public static void info(String message) {
        if (!QUIET) {
            System.out.println(message);
        }
    }

    public static void warn(String message) {
        if (!QUIET) {
            System.err.println(message);
        }
    }
}
