package com.codeflow.permissions;

public class ToolPermissionManager {

    private final ToolPermissionConfig toolPermissionConfig;
    private volatile PermissionMode permissionMode;
    private volatile ToolPermissionConfirmer confirmer;

    public ToolPermissionManager(ToolPermissionConfig toolPermissionConfig, PermissionMode permissionMode) {
        this.toolPermissionConfig = toolPermissionConfig == null ? ToolPermissionConfig.empty() : toolPermissionConfig;
        this.permissionMode = permissionMode == null ? PermissionMode.ASK : permissionMode;
        this.confirmer = ToolPermissionConfirmer.denyByDefault();
    }

    public static ToolPermissionManager askByDefault() {
        return new ToolPermissionManager(ToolPermissionConfig.empty(), PermissionMode.ASK);
    }

    public ToolPermission permissionFor(String toolName) {
        ToolPermission configured = toolPermissionConfig.permissionFor(toolName);
        if (permissionMode == PermissionMode.FULL_ACCESS && configured != ToolPermission.DENY) {
            return ToolPermission.ALLOW;
        }
        return configured;
    }

    public PermissionMode permissionMode() {
        return permissionMode;
    }

    public void setPermissionMode(PermissionMode permissionMode) {
        this.permissionMode = permissionMode == null ? PermissionMode.ASK : permissionMode;
    }

    public void setConfirmer(ToolPermissionConfirmer confirmer) {
        this.confirmer = confirmer == null ? ToolPermissionConfirmer.denyByDefault() : confirmer;
    }

    public boolean confirm(String toolName, String input) {
        return confirmer.confirm(toolName, input);
    }
}
