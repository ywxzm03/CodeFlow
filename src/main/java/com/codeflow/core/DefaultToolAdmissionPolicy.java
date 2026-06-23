package com.codeflow.core;

import com.codeflow.hooks.HookDecision;
import com.codeflow.hooks.PreToolUseHandler;
import com.codeflow.hooks.PreToolUseInput;
import com.codeflow.hooks.PreToolUseResult;
import com.codeflow.permissions.ToolPermission;
import com.codeflow.permissions.ToolPermissionManager;

import java.util.Objects;

public class DefaultToolAdmissionPolicy implements ToolAdmissionPolicy {

    private final ToolPermissionManager toolPermissionManager;
    private final PreToolUseHandler preToolUseHandler;

    public DefaultToolAdmissionPolicy(
            ToolPermissionManager toolPermissionManager,
            PreToolUseHandler preToolUseHandler
    ) {
        this.toolPermissionManager = Objects.requireNonNull(toolPermissionManager, "toolPermissionManager must not be null");
        this.preToolUseHandler = preToolUseHandler == null ? PreToolUseHandler.none() : preToolUseHandler;
    }

    @Override
    public ToolAdmissionResult authorize(Message.ToolUse toolUse) {
        ToolPermission permission = permissionFor(toolUse);
        if (permission == ToolPermission.DENY) {
            return ToolAdmissionResult.deny("工具权限拒绝: " + toolUse.name());
        }

        if (permission == ToolPermission.ASK && !toolPermissionManager.confirm(toolUse.name(), toolUse.input())) {
            return ToolAdmissionResult.deny("工具未获得用户确认: " + toolUse.name());
        }

        return ToolAdmissionResult.allow();
    }

    private ToolPermission permissionFor(Message.ToolUse toolUse) {
        PreToolUseResult preToolUseResult = preToolUseHandler.handle(new PreToolUseInput(
                toolUse.id(),
                toolUse.name(),
                toolUse.input(),
                System.getProperty("user.dir"),
                toolPermissionManager.permissionMode()
        ));
        HookDecision decision = preToolUseResult == null ? HookDecision.NONE : preToolUseResult.decision();
        return switch (decision) {
            case ALLOW -> ToolPermission.ALLOW;
            case ASK -> ToolPermission.ASK;
            case DENY -> ToolPermission.DENY;
            case NONE -> toolPermissionManager.permissionFor(toolUse.name());
        };
    }
}
