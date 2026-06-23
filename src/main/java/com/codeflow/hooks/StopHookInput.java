package com.codeflow.hooks;

import com.codeflow.core.Message;
import com.codeflow.permissions.PermissionMode;

import java.util.List;

public record StopHookInput(
        String lastAssistantMessage,
        String cwd,
        PermissionMode permissionMode,
        boolean stopHookActive,
        List<Message> messages
) {
    public StopHookInput {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public StopHookInput(
            String lastAssistantMessage,
            String cwd,
            PermissionMode permissionMode,
            boolean stopHookActive
    ) {
        this(lastAssistantMessage, cwd, permissionMode, stopHookActive, List.of());
    }
}
