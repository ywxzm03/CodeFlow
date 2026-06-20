package com.codewarp.tools;

import com.codewarp.core.Message;
import com.codewarp.core.StreamingToolExecutor;
import com.codewarp.permissions.ToolPermissionManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolInputValidationTest {

    @Test
    void rejectsMalformedJson() {
        Tool.ValidationResult result = new ReadTool().validateInput("{");

        assertFalse(result.allowed());
        assertTrue(result.message().contains("输入不是合法 JSON"));
    }

    @Test
    void rejectsMissingRequiredField() {
        Tool.ValidationResult result = new BashTool().validateInput("{}");

        assertFalse(result.allowed());
        assertTrue(result.message().contains("缺少必填参数: command"));
    }

    @Test
    void rejectsWrongFieldType() {
        Tool.ValidationResult result = new WriteTool().validateInput("""
                {"file_path": "/tmp/a.txt", "content": 123}
                """);

        assertFalse(result.allowed());
        assertTrue(result.message().contains("参数必须是字符串: content"));
    }

    @Test
    void rejectsUnknownFields() {
        Tool.ValidationResult result = new ReadTool().validateInput("""
                {"file_path": "/tmp/a.txt", "extra": true}
                """);

        assertFalse(result.allowed());
        assertTrue(result.message().contains("包含未知参数"));
    }

    @Test
    void rejectsInvalidEnumValue() {
        Tool.ValidationResult result = new GrepTool().validateInput("""
                {"pattern": "class", "output_mode": "summary"}
                """);

        assertFalse(result.allowed());
        assertTrue(result.message().contains("参数取值非法: output_mode"));
    }

    @Test
    void rejectsEmptyRequiredArray() {
        Tool.ValidationResult result = new GlobTool().validateInput("""
                {"patterns": []}
                """);

        assertFalse(result.allowed());
        assertTrue(result.message().contains("参数不能为空数组: patterns"));
    }

    @Test
    void acceptsValidToolInputs() {
        assertTrue(new ReadTool().validateInput("""
                {"file_path": "/tmp/a.txt"}
                """).allowed());
        assertTrue(new WriteTool().validateInput("""
                {"file_path": "/tmp/a.txt", "content": ""}
                """).allowed());
        assertTrue(new EditTool().validateInput("""
                {"file_path": "/tmp/a.txt", "old_string": "old", "new_string": "", "replace_all": true}
                """).allowed());
        assertTrue(new BashTool().validateInput("""
                {"command": "pwd"}
                """).allowed());
        assertTrue(new GrepTool().validateInput("""
                {"pattern": "class", "output_mode": "files", "case_sensitive": false}
                """).allowed());
        assertTrue(new GlobTool().validateInput("""
                {"patterns": ["**/*.java"], "exclude": ["**/build/**"]}
                """).allowed());
    }

    @Test
    void executorDoesNotRunToolWhenInputIsInvalid() {
        AtomicBoolean executed = new AtomicBoolean(false);
        Tool tool = new Tool() {
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

            @Override
            public ValidationResult validateInput(String input) {
                return ValidationResult.invalid("bad input");
            }
        };
        StreamingToolExecutor executor = new StreamingToolExecutor(List.of(tool), ToolPermissionManager.askByDefault());

        executor.addTool(new Message.ToolUse("toolu_test", "TestTool", "{}"));
        List<StreamingToolExecutor.ToolResult> results = executor.getRemainingResults();
        executor.shutdown();

        assertFalse(executed.get());
        assertTrue(results.getFirst().isError());
        assertTrue(results.getFirst().content().contains("工具参数无效: bad input"));
    }
}
