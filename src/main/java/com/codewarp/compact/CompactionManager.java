package com.codewarp.compact;

import com.codewarp.core.WorkingMemory;
import com.codewarp.tools.Tool;

import java.util.List;

/**
 * 三层压缩协调器。
 */
public final class CompactionManager {

    private final SnipCompactor snipCompactor;
    private final AutoCompactor autoCompactor;

    public CompactionManager(SnipCompactor snipCompactor, AutoCompactor autoCompactor) {
        this.snipCompactor = snipCompactor;
        this.autoCompactor = autoCompactor;
    }

    public BeforeCallResult beforeModelCall(String systemPrompt, WorkingMemory workingMemory, List<Tool> tools) {
        long tokensFreed = 0;
        if (snipCompactor != null) {
            tokensFreed = snipCompactor.compact(workingMemory).tokensFreed();
        }
        AutoCompactor.Result autoResult = autoCompactor == null
                ? AutoCompactor.Result.notCompacted()
                : autoCompactor.compactIfNeeded(systemPrompt, workingMemory, tools, tokensFreed);
        return new BeforeCallResult(tokensFreed, autoResult.compacted());
    }

    public record BeforeCallResult(long tokensFreed, boolean autoCompacted) {
    }
}
