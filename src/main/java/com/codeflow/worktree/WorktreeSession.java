package com.codeflow.worktree;

import java.nio.file.Path;

/**
 * 一次 worktree 创建结果。
 */
public record WorktreeSession(
        String agentId,
        String slug,
        String branchName,
        Path worktreePath,
        Path gitRoot,
        String baseCommit
) {
}
