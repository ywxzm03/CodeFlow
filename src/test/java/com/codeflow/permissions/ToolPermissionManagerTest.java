package com.codeflow.permissions;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolPermissionManagerTest {

    @Test
    void askModeUsesConfiguredToolPermission() {
        ToolPermissionManager manager = new ToolPermissionManager(
                new ToolPermissionConfig(Map.of(
                        "Read", ToolPermission.ALLOW,
                        "Write", ToolPermission.ASK,
                        "Bash", ToolPermission.DENY
                )),
                PermissionMode.ASK
        );

        assertEquals(ToolPermission.ALLOW, manager.permissionFor("Read"));
        assertEquals(ToolPermission.ASK, manager.permissionFor("Write"));
        assertEquals(ToolPermission.DENY, manager.permissionFor("Bash"));
    }

    @Test
    void fullAccessAllowsAskAndAllowPermissions() {
        ToolPermissionManager manager = new ToolPermissionManager(
                new ToolPermissionConfig(Map.of(
                        "Read", ToolPermission.ALLOW,
                        "Write", ToolPermission.ASK
                )),
                PermissionMode.FULL_ACCESS
        );

        assertEquals(ToolPermission.ALLOW, manager.permissionFor("Read"));
        assertEquals(ToolPermission.ALLOW, manager.permissionFor("Write"));
    }

    @Test
    void fullAccessKeepsDenyPermission() {
        ToolPermissionManager manager = new ToolPermissionManager(
                new ToolPermissionConfig(Map.of("Bash", ToolPermission.DENY)),
                PermissionMode.FULL_ACCESS
        );

        assertEquals(ToolPermission.DENY, manager.permissionFor("Bash"));
    }

    @Test
    void missingToolDefaultsToAskInAskModeAndAllowInFullAccessMode() {
        ToolPermissionManager manager = new ToolPermissionManager(ToolPermissionConfig.empty(), PermissionMode.ASK);
        assertEquals(ToolPermission.ASK, manager.permissionFor("Read"));

        manager.setPermissionMode(PermissionMode.FULL_ACCESS);
        assertEquals(ToolPermission.ALLOW, manager.permissionFor("Read"));
    }
}
