package com.codewarp.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrepToolTest {

    @TempDir
    Path tempDir;

    @Test
    void searchesSingleFileRoot() throws Exception {
        Path transcript = tempDir.resolve("session-a.jsonl");
        Files.writeString(transcript, """
                {"message":{"type":"user","content":"hello"}}
                {"message":{"type":"assistant","content":"permission mode selected"}}
                """);

        Tool.ToolExecutionResult result = new GrepTool().execute("""
                {"pattern": "permission", "root": "%s", "output_mode": "content"}
                """.formatted(transcript));

        assertFalse(result.isError());
        assertTrue(result.content().contains("permission mode selected"));
        assertTrue(result.content().contains(transcript.toString()));
    }
}
