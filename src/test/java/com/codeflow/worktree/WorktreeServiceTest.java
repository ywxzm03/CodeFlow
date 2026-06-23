package com.codeflow.worktree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorktreeServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createsAgentWorktreeFromCurrentHead() throws Exception {
        initRepo(tempDir);
        Files.writeString(tempDir.resolve("README.md"), "hello\n");
        git(tempDir, "add", "README.md");
        git(tempDir, "commit", "-m", "initial");

        WorktreeService service = new WorktreeService(tempDir);
        WorktreeSession session = service.createAgentWorktree("a8f31c92-4d6b-4a0e-bd12-9b01a771e223");

        assertEquals("agent-a8f31c924d6b", session.slug());
        assertEquals("codeflow-agent-a8f31c924d6b", session.branchName());
        assertTrue(Files.isDirectory(session.worktreePath()));
        assertTrue(Files.exists(session.worktreePath().resolve("README.md")));
        assertFalse(session.baseCommit().isBlank());
    }

    @Test
    void rejectsNonGitRepository() throws Exception {
        WorktreeService service = new WorktreeService(tempDir);

        try {
            service.requireGitRoot();
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("git command failed"));
            return;
        }

        throw new AssertionError("Expected non-git repository to fail");
    }

    private static void initRepo(Path dir) throws Exception {
        git(dir, "init");
        git(dir, "config", "user.email", "test@example.com");
        git(dir, "config", "user.name", "Test User");
    }

    private static String git(Path cwd, String... args) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(buildCommand(args));
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes());
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("git timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(output);
        }
        return output;
    }

    private static java.util.List<String> buildCommand(String... args) {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        return command;
    }
}
