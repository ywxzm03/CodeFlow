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
    private final ReactiveCompactor reactiveCompactor;

    public CompactionManager(SnipCompactor snipCompactor, AutoCompactor autoCompactor, ReactiveCompactor reactiveCompactor) {
        this.snipCompactor = snipCompactor;
        this.autoCompactor = autoCompactor;
        this.reactiveCompactor = reactiveCompactor;
    }

    /**
     * 模型调用前：先 snip，再按阈值 auto。
     */
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

    /**
     * 手动触发 auto compact。
     */
    public AutoCompactor.ForceResult forceAutoCompact(String systemPrompt, WorkingMemory workingMemory, List<Tool> tools) {
        if (autoCompactor == null) {
            return AutoCompactor.ForceResult.unavailable("auto compact is unavailable");
        }
        return autoCompactor.forceCompact(systemPrompt, workingMemory, tools);
    }

    /**
     * 上下文超限后触发兜底压缩。
     */
    public ReactiveResult reactiveCompact(String systemPrompt, WorkingMemory workingMemory, List<Tool> tools, RuntimeException error, int retryCount) {
        if (!isContextOverflow(error) || reactiveCompactor == null) {
            return ReactiveResult.notCompacted();
        }
        ReactiveCompactor.Result result = reactiveCompactor.compact(systemPrompt, workingMemory, tools, retryCount);
        return new ReactiveResult(result.compacted(), result.boundaryUuid());
    }

    private boolean isContextOverflow(Throwable error) {
        if (error == null) {
            return false;
        }
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                if ((lower.contains("context") && (lower.contains("overflow") || lower.contains("too long") || lower.contains("exceed") || lower.contains("exceeded")))
                        || (lower.contains("prompt") && (lower.contains("too long") || lower.contains("exceed") || lower.contains("exceeded")))
                        || lower.contains("maximum context length")
                        || lower.contains("input is too long")
                        || lower.contains("too many tokens")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    public record BeforeCallResult(long tokensFreed, boolean autoCompacted) {
    }

    public record ReactiveResult(boolean compacted, String boundaryUuid) {
        public static ReactiveResult notCompacted() {
            return new ReactiveResult(false, null);
        }
    }
}
