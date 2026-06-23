package com.codeflow.worktree;

import java.nio.file.Path;

public record WorktreeSession(
        String agentId,
        String slug,
        String branchName,
        Path worktreePath,
        Path gitRoot,
        String baseCommit
) {
}
