package com.codeflow.tools;

import com.codeflow.permissions.PermissionMode;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;

/**
 * Runtime context for a tool execution.
 *
 * <p>The main session uses the process cwd with no path confinement. Batch workers
 * use a worktree cwd and an allowed root so filesystem tools cannot accidentally
 * operate on the parent working tree.
 */
public record ToolExecutionContext(
        Path cwd,
        Path allowedRoot,
        PermissionMode permissionMode,
        String agentId,
        String batchId
) {
    public ToolExecutionContext {
        cwd = normalize(cwd == null ? Paths.get(System.getProperty("user.dir")) : cwd);
        allowedRoot = allowedRoot == null ? null : normalize(allowedRoot);
        permissionMode = permissionMode == null ? PermissionMode.ASK : permissionMode;
    }

    public static ToolExecutionContext defaultContext() {
        return new ToolExecutionContext(
                Paths.get(System.getProperty("user.dir")),
                null,
                PermissionMode.ASK,
                null,
                null
        );
    }

    public static ToolExecutionContext batchWorker(Path worktreePath, String agentId, String batchId) {
        return new ToolExecutionContext(
                worktreePath,
                worktreePath,
                PermissionMode.BATCH_WORKER,
                agentId,
                batchId
        );
    }

    public Path resolvePath(String inputPath) {
        if (inputPath == null || inputPath.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        Path raw = Paths.get(inputPath);
        Path resolved = normalize(raw.isAbsolute() ? raw : cwd.resolve(raw));
        ensureAllowed(resolved);
        return resolved;
    }

    public void ensureAllowed(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        if (allowedRoot == null) {
            return;
        }
        Path resolved = normalize(path);
        if (!resolved.startsWith(allowedRoot)) {
            throw new IllegalArgumentException(
                    "path escapes allowed root: " + resolved + " (allowed root: " + allowedRoot + ")"
            );
        }
    }

    public boolean isBatchWorker() {
        return permissionMode == PermissionMode.BATCH_WORKER;
    }

    public void validateBashCommand(String command) {
        if (!isBatchWorker()) {
            return;
        }
        String normalized = command == null ? "" : command.toLowerCase(Locale.ROOT);
        if (normalized.matches("(?s).*\\bgit\\s+push\\b.*")) {
            throw new IllegalArgumentException("Batch workers cannot run git push");
        }
        if (normalized.matches("(?s).*\\bgh\\s+pr\\s+create\\b.*")) {
            throw new IllegalArgumentException("Batch workers cannot create pull requests");
        }
        if (normalized.matches("(?s).*\\bgit\\s+(merge|cherry-pick)\\b.*")) {
            throw new IllegalArgumentException("Batch workers cannot merge or cherry-pick");
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
