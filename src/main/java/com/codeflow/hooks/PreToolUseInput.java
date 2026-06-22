package com.codeflow.hooks;

import com.codeflow.permissions.PermissionMode;

public record PreToolUseInput(
        String toolUseId,
        String toolName,
        String toolInput,
        String cwd,
        PermissionMode permissionMode
) {
}
