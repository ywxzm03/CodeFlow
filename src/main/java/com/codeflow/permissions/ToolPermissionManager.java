package com.codeflow.permissions;

public class ToolPermissionManager {

    private volatile PermissionMode permissionMode;
    private volatile ToolPermissionConfirmer confirmer;

    public ToolPermissionManager(ToolPermissionConfig toolPermissionConfig, PermissionMode permissionMode) {
        this(permissionMode);
    }

    public ToolPermissionManager(PermissionMode permissionMode) {
        this.permissionMode = permissionMode == null ? PermissionMode.ASK : permissionMode;
        this.confirmer = ToolPermissionConfirmer.denyByDefault();
    }

    public static ToolPermissionManager askByDefault() {
        return new ToolPermissionManager(ToolPermissionConfig.empty(), PermissionMode.ASK);
    }

    public ToolPermission permissionFor(String toolName) {
        if (permissionMode == PermissionMode.FULL_ACCESS) {
            return ToolPermission.ALLOW;
        }
        return ToolPermission.ASK;
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
