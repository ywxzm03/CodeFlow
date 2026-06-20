package com.codewarp.core;

import com.codewarp.permissions.PermissionMode;
import com.codewarp.permissions.ToolPermission;
import com.codewarp.permissions.ToolPermissionConfig;
import com.codewarp.permissions.ToolPermissionManager;
import com.codewarp.tools.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingToolPermissionTest {

    @Test
    void deniedToolDoesNotExecute() {
        AtomicBoolean executed = new AtomicBoolean(false);
        StreamingToolExecutor executor = new StreamingToolExecutor(
                List.of(testTool(executed)),
                new ToolPermissionManager(
                        new ToolPermissionConfig(Map.of("TestTool", ToolPermission.DENY)),
                        PermissionMode.ASK
                )
        );

        executor.addTool(new Message.ToolUse("toolu_test", "TestTool", "{}"));
        List<StreamingToolExecutor.ToolResult> results = executor.getRemainingResults();
        executor.shutdown();

        assertFalse(executed.get());
        assertTrue(results.getFirst().isError());
        assertTrue(results.getFirst().content().contains("工具权限拒绝: TestTool"));
    }

    @Test
    void askToolExecutesWhenUserConfirms() {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolPermissionManager permissionManager = new ToolPermissionManager(
                new ToolPermissionConfig(Map.of("TestTool", ToolPermission.ASK)),
                PermissionMode.ASK
        );
        permissionManager.setConfirmer((toolName, input) -> true);
        StreamingToolExecutor executor = new StreamingToolExecutor(
                List.of(testTool(executed)),
                permissionManager
        );

        executor.addTool(new Message.ToolUse("toolu_test", "TestTool", "{}"));
        List<StreamingToolExecutor.ToolResult> results = executor.getRemainingResults();
        executor.shutdown();

        assertTrue(executed.get());
        assertFalse(results.getFirst().isError());
        assertTrue(results.getFirst().content().contains("executed"));
    }

    @Test
    void askToolDoesNotExecuteWhenUserRejects() {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolPermissionManager permissionManager = new ToolPermissionManager(
                new ToolPermissionConfig(Map.of("TestTool", ToolPermission.ASK)),
                PermissionMode.ASK
        );
        permissionManager.setConfirmer((toolName, input) -> false);
        StreamingToolExecutor executor = new StreamingToolExecutor(
                List.of(testTool(executed)),
                permissionManager
        );

        executor.addTool(new Message.ToolUse("toolu_test", "TestTool", "{}"));
        List<StreamingToolExecutor.ToolResult> results = executor.getRemainingResults();
        executor.shutdown();

        assertFalse(executed.get());
        assertTrue(results.getFirst().isError());
        assertTrue(results.getFirst().content().contains("工具未获得用户确认: TestTool"));
    }

    @Test
    void allowedToolExecutes() {
        AtomicBoolean executed = new AtomicBoolean(false);
        StreamingToolExecutor executor = new StreamingToolExecutor(
                List.of(testTool(executed)),
                new ToolPermissionManager(
                        new ToolPermissionConfig(Map.of("TestTool", ToolPermission.ALLOW)),
                        PermissionMode.ASK
                )
        );

        executor.addTool(new Message.ToolUse("toolu_test", "TestTool", "{}"));
        List<StreamingToolExecutor.ToolResult> results = executor.getRemainingResults();
        executor.shutdown();

        assertTrue(executed.get());
        assertFalse(results.getFirst().isError());
        assertTrue(results.getFirst().content().contains("executed"));
    }

    @Test
    void fullAccessAllowsAskToolWithoutConfirmation() {
        AtomicBoolean executed = new AtomicBoolean(false);
        AtomicBoolean asked = new AtomicBoolean(false);
        ToolPermissionManager permissionManager = new ToolPermissionManager(
                new ToolPermissionConfig(Map.of("TestTool", ToolPermission.ASK)),
                PermissionMode.FULL_ACCESS
        );
        permissionManager.setConfirmer((toolName, input) -> {
            asked.set(true);
            return false;
        });
        StreamingToolExecutor executor = new StreamingToolExecutor(
                List.of(testTool(executed)),
                permissionManager
        );

        executor.addTool(new Message.ToolUse("toolu_test", "TestTool", "{}"));
        List<StreamingToolExecutor.ToolResult> results = executor.getRemainingResults();
        executor.shutdown();

        assertTrue(executed.get());
        assertFalse(asked.get());
        assertFalse(results.getFirst().isError());
        assertTrue(results.getFirst().content().contains("executed"));
    }

    @Test
    void fullAccessStillDeniesDeniedTool() {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolPermissionManager permissionManager = new ToolPermissionManager(
                new ToolPermissionConfig(Map.of("TestTool", ToolPermission.DENY)),
                PermissionMode.FULL_ACCESS
        );
        StreamingToolExecutor executor = new StreamingToolExecutor(
                List.of(testTool(executed)),
                permissionManager
        );

        executor.addTool(new Message.ToolUse("toolu_test", "TestTool", "{}"));
        List<StreamingToolExecutor.ToolResult> results = executor.getRemainingResults();
        executor.shutdown();

        assertFalse(executed.get());
        assertTrue(results.getFirst().isError());
        assertTrue(results.getFirst().content().contains("工具权限拒绝: TestTool"));
    }

    private Tool testTool(AtomicBoolean executed) {
        return new Tool() {
            @Override
            public String name() {
                return "TestTool";
            }

            @Override
            public String description() {
                return "test";
            }

            @Override
            public String inputSchema() {
                return "{}";
            }

            @Override
            public ToolExecutionResult execute(String input) {
                executed.set(true);
                return ToolExecutionResult.success("executed");
            }
        };
    }
}
