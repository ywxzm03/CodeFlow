package com.codeflow.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutionContextTest {

    @TempDir
    Path tempDir;

    @Test
    void filesystemToolsResolveRelativePathsInsideContextCwd() throws Exception {
        ToolExecutionContext context = new ToolExecutionContext(tempDir, tempDir, null, "agent-a", "batch-a");
        WriteTool write = new WriteTool();
        ReadTool read = new ReadTool();

        Tool.ToolExecutionResult writeResult = write.execute("""
                {"file_path":"src/App.java","content":"class App {}"}
                """, context);
        Tool.ToolExecutionResult readResult = read.execute("""
                {"file_path":"src/App.java"}
                """, context);

        assertFalse(writeResult.isError());
        assertFalse(readResult.isError());
        assertTrue(Files.exists(tempDir.resolve("src/App.java")));
        assertTrue(readResult.content().contains("class App"));
    }

    @Test
    void filesystemToolsRejectPathsOutsideAllowedRoot() {
        Path outside = tempDir.getParent().resolve("outside.txt");
        ToolExecutionContext context = new ToolExecutionContext(tempDir, tempDir, null, "agent-a", "batch-a");
        WriteTool write = new WriteTool();

        Tool.ToolExecutionResult result = write.execute(
                "{\"file_path\":\"" + outside + "\",\"content\":\"bad\"}",
                context
        );

        assertTrue(result.isError());
        assertTrue(result.content().contains("escapes allowed root"));
    }

    @Test
    void bashRunsInsideContextCwd() {
        ToolExecutionContext context = new ToolExecutionContext(tempDir, tempDir, null, "agent-a", "batch-a");
        BashTool bash = new BashTool();

        Tool.ToolExecutionResult result = bash.execute("""
                {"command":"pwd"}
                """, context);

        assertFalse(result.isError());
        assertTrue(result.content().contains(tempDir.toAbsolutePath().normalize().toString()));
    }

    @Test
    void batchWorkerRejectsPushAndPrCommands() {
        ToolExecutionContext context = ToolExecutionContext.batchWorker(tempDir, "agent-a", "batch-a");
        BashTool bash = new BashTool();

        Tool.ToolExecutionResult push = bash.execute("""
                {"command":"git push origin HEAD"}
                """, context);
        Tool.ToolExecutionResult pr = bash.execute("""
                {"command":"gh pr create --title test"}
                """, context);

        assertTrue(push.isError());
        assertTrue(push.content().contains("git push"));
        assertTrue(pr.isError());
        assertTrue(pr.content().contains("pull requests"));
    }
}
