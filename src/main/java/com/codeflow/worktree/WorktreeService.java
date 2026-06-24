package com.codeflow.worktree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Git worktree 管理服务，为 Coder 提供隔离工作目录。
 */
public final class WorktreeService {
    private static final Pattern SAFE_SLUG = Pattern.compile("[a-zA-Z0-9._-]+");
    private static final int MAX_SLUG_LENGTH = 64;

    private final Path projectRoot;
    private final Path worktreesRoot;

    public WorktreeService(Path projectRoot) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("projectRoot must not be null");
        }
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.worktreesRoot = this.projectRoot.resolve(".codeflow").resolve("worktrees").normalize();
    }

    /**
     * 基于当前 HEAD 创建 agent 专属 worktree 和分支。
     */
    public WorktreeSession createAgentWorktree(String agentId) throws IOException, InterruptedException {
        String normalizedAgentId = normalizeAgentId(agentId);
        String slug = "agent-" + normalizedAgentId;
        validateSlug(slug);
        Path gitRoot = requireGitRoot();
        String baseCommit = runGit(gitRoot, "rev-parse", "HEAD").strip();
        Path worktreePath = worktreesRoot.resolve(slug).normalize();
        String branchName = "codeflow-" + slug;

        Files.createDirectories(worktreesRoot);
        runGit(gitRoot, "worktree", "add", "-B", branchName, worktreePath.toString(), baseCommit);
        return new WorktreeSession(agentId, slug, branchName, worktreePath, gitRoot, baseCommit);
    }

    /**
     * 返回项目所在 git 根目录。
     */
    public Path requireGitRoot() throws IOException, InterruptedException {
        String root = runGit(projectRoot, "rev-parse", "--show-toplevel").strip();
        if (root.isBlank()) {
            throw new IllegalStateException("Not a git repository: " + projectRoot);
        }
        return Path.of(root).toAbsolutePath().normalize();
    }

    /**
     * 生成后台任务和 worktree 共用的 agent id。
     */
    public static String newAgentId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 限制 worktree 名称，防止路径穿越。
     */
    static void validateSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("worktree slug must not be blank");
        }
        if (slug.length() > MAX_SLUG_LENGTH) {
            throw new IllegalArgumentException("worktree slug is too long: " + slug.length());
        }
        if (slug.contains("/") || slug.contains("\\") || slug.equals(".") || slug.equals("..") || slug.contains("..")) {
            throw new IllegalArgumentException("worktree slug must not contain path traversal: " + slug);
        }
        if (!SAFE_SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("worktree slug contains unsafe characters: " + slug);
        }
    }

    /**
     * 将任意 agent id 收敛成短、安全的文件名片段。
     */
    private static String normalizeAgentId(String agentId) {
        String raw = agentId == null || agentId.isBlank() ? newAgentId() : agentId;
        String compact = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-f0-9]", "");
        if (compact.length() < 8) {
            compact = UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8))
                    .toString()
                    .replace("-", "")
                    .substring(0, 12);
        }
        return compact.substring(0, Math.min(12, compact.length()));
    }

    /**
     * 执行 git 命令并返回标准输出。
     */
    private static String runGit(Path cwd, String... args) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(buildGitCommand(args));
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean finished = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git command timed out: git " + String.join(" ", args));
        }
        if (process.exitValue() != 0) {
            throw new IOException("git command failed: git " + String.join(" ", args) + "\n" + output.strip());
        }
        return output;
    }

    private static List<String> buildGitCommand(String... args) {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        return command;
    }
}
