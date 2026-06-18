package com.codewarp.core;

import com.codewarp.permissions.ToolPermission;
import com.codewarp.permissions.ToolPermissionConfig;
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
                new ToolPermissionConfig(Map.of("TestTool", ToolPermission.DENY))
        );

        executor.addTool(new Message.ToolUse("toolu_test", "TestTool", "{}"));
        List<StreamingToolExecutor.ToolResult> results = executor.getRemainingResults();
        executor.shutdown();

        assertFalse(executed.get());
        assertTrue(results.getFirst().isError());
        assertTrue(results.getFirst().content().contains("工具权限拒绝: TestTool"));
    }

    @Test
    void askToolDoesNotExecuteUntilConfirmationLayerExists() {
        AtomicBoolean executed = new AtomicBoolean(false);
        StreamingToolExecutor executor = new StreamingToolExecutor(
                List.of(testTool(executed)),
                new ToolPermissionConfig(Map.of("TestTool", ToolPermission.ASK))
        );

        executor.addTool(new Message.ToolUse("toolu_test", "TestTool", "{}"));
        List<StreamingToolExecutor.ToolResult> results = executor.getRemainingResults();
        executor.shutdown();

        assertFalse(executed.get());
        assertTrue(results.getFirst().isError());
        assertTrue(results.getFirst().content().contains("工具需要用户确认: TestTool"));
    }

    @Test
    void allowedToolExecutes() {
        AtomicBoolean executed = new AtomicBoolean(false);
        StreamingToolExecutor executor = new StreamingToolExecutor(
                List.of(testTool(executed)),
                new ToolPermissionConfig(Map.of("TestTool", ToolPermission.ALLOW))
        );

        executor.addTool(new Message.ToolUse("toolu_test", "TestTool", "{}"));
        List<StreamingToolExecutor.ToolResult> results = executor.getRemainingResults();
        executor.shutdown();

        assertTrue(executed.get());
        assertFalse(results.getFirst().isError());
        assertTrue(results.getFirst().content().contains("executed"));
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
