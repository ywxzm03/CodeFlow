package com.codewarp.tools;

import com.codewarp.memory.MemoryLayer;
import com.codewarp.memory.MemoryStore;
import com.codewarp.memory.MemoryUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryReadToolTest {

    @TempDir
    Path tempDir;

    @Test
    void readsL2AndL3MemoryFiles() throws Exception {
        MemoryStore store = new MemoryStore(tempDir.resolve("memory"));
        store.initialize();
        store.applyUpdate(new MemoryUpdate(MemoryLayer.L2, "facts.txt", "project fact", "reason", ""));
        store.applyUpdate(new MemoryUpdate(MemoryLayer.L3, "pitfall.md", "pitfall note", "reason", ""));
        MemoryReadTool tool = new MemoryReadTool(store);

        Tool.ToolExecutionResult l2 = tool.execute("""
                {"path": "L2/facts.txt"}
                """);
        Tool.ToolExecutionResult l3 = tool.execute("""
                {"path": "L3/pitfall.md"}
                """);

        assertFalse(l2.isError());
        assertTrue(l2.content().contains("project fact"));
        assertFalse(l3.isError());
        assertTrue(l3.content().contains("pitfall note"));
    }

    @Test
    void validatesMemoryReadPath() throws Exception {
        MemoryStore store = new MemoryStore(tempDir.resolve("memory"));
        store.initialize();
        MemoryReadTool tool = new MemoryReadTool(store);

        assertFalse(tool.validateInput("""
                {"path": "../x.txt"}
                """).allowed());
        assertFalse(tool.validateInput("""
                {"path": "/tmp/x.txt"}
                """).allowed());
        assertFalse(tool.validateInput("""
                {"path": "L0/memory_rules.md"}
                """).allowed());
        assertFalse(tool.validateInput("""
                {"path": "L2/facts.md"}
                """).allowed());
    }
}
