package com.codeflow.hooks;

import com.codeflow.permissions.PermissionMode;

public record StopHookInput(
        String lastAssistantMessage,
        String cwd,
        PermissionMode permissionMode,
        boolean stopHookActive
) {
}
