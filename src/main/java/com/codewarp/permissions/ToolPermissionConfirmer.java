package com.codewarp.permissions;

@FunctionalInterface
public interface ToolPermissionConfirmer {

    boolean confirm(String toolName, String input);

    static ToolPermissionConfirmer denyByDefault() {
        return (toolName, input) -> false;
    }
}
