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
        String batchId,
        String agentType,
        String targetAgentId
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
                null,
                null,
                null
        );
    }

    public static ToolExecutionContext batchWorker(Path worktreePath, String agentId, String batchId) {
        return subagentCoder(worktreePath, agentId, batchId);
    }

    public static ToolExecutionContext subagentReadOnly(Path cwd, String agentId, String batchId, String agentType) {
        return new ToolExecutionContext(
                cwd,
                null,
                PermissionMode.SUBAGENT_READ_ONLY,
                agentId,
                batchId,
                agentType,
                null
        );
    }

    public static ToolExecutionContext subagentCoder(Path worktreePath, String agentId, String batchId) {
        return new ToolExecutionContext(
                worktreePath,
                worktreePath,
                PermissionMode.SUBAGENT_CODER,
                agentId,
                batchId,
                "Coder",
                null
        );
    }

    public static ToolExecutionContext subagentVerifier(Path cwd, Path allowedRoot, String agentId, String batchId, String targetAgentId) {
        return new ToolExecutionContext(
                cwd,
                allowedRoot,
                PermissionMode.SUBAGENT_VERIFIER,
                agentId,
                batchId,
                "Verifier",
                targetAgentId
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
        return permissionMode == PermissionMode.BATCH_WORKER || permissionMode == PermissionMode.SUBAGENT_CODER;
    }

    public boolean isSubagentReadOnly() {
        return permissionMode == PermissionMode.SUBAGENT_READ_ONLY;
    }

    public boolean isSubagentVerifier() {
        return permissionMode == PermissionMode.SUBAGENT_VERIFIER;
    }

    public boolean isSubagentMode() {
        return isBatchWorker() || isSubagentReadOnly() || isSubagentVerifier();
    }

    public void validateBashCommand(String command) {
        if (!isSubagentMode()) {
            return;
        }
        String normalized = command == null ? "" : command.toLowerCase(Locale.ROOT);
        if (normalized.matches("(?s).*\\bgit\\s+push\\b.*")) {
            throw new IllegalArgumentException("Subagents cannot run git push");
        }
        if (normalized.matches("(?s).*\\bgh\\s+pr\\s+create\\b.*")) {
            throw new IllegalArgumentException("Subagents cannot create pull requests");
        }
        if (normalized.matches("(?s).*\\bgit\\s+(merge|cherry-pick)\\b.*")) {
            throw new IllegalArgumentException("Subagents cannot merge or cherry-pick");
        }
        if ((isSubagentReadOnly() || isSubagentVerifier()) && normalized.matches("(?s).*\\bgit\\s+(add|commit|reset|checkout|switch|branch|tag|stash|rebase|am|apply)\\b.*")) {
            throw new IllegalArgumentException("Read-only subagents cannot run git write operations");
        }
        if (isSubagentReadOnly() && mutatesFilesystem(normalized)) {
            throw new IllegalArgumentException("Read-only subagents cannot run filesystem mutation commands");
        }
        if (isSubagentVerifier() && mutatesSourceFilesystem(normalized)) {
            throw new IllegalArgumentException("Verifier cannot run source mutation commands");
        }
    }

    private static boolean mutatesFilesystem(String normalizedCommand) {
        return normalizedCommand.matches("(?s).*(^|[;&|]\\s*)\\s*(mkdir|touch|rm|rmdir|mv|cp|install|npm\\s+install|pnpm\\s+install|yarn\\s+add|pip\\s+install|mvn\\s+install)\\b.*")
                || normalizedCommand.contains(">")
                || normalizedCommand.contains("<<");
    }

    private static boolean mutatesSourceFilesystem(String normalizedCommand) {
        return normalizedCommand.matches("(?s).*(^|[;&|]\\s*)\\s*(rm|rmdir|mv|cp|touch|mkdir)\\b.*")
                || normalizedCommand.contains(">")
                || normalizedCommand.contains("<<");
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    public ToolExecutionContext(
            Path cwd,
            Path allowedRoot,
            PermissionMode permissionMode,
            String agentId,
            String batchId
    ) {
        this(cwd, allowedRoot, permissionMode, agentId, batchId, null, null);
    }
}
