package com.codeflow.core;

import com.codeflow.permissions.ToolPermission;
import com.codeflow.permissions.ToolPermissionManager;
import com.codeflow.tools.Tool;
import com.codeflow.util.Console;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 流式工具执行器 - 边接收工具调用边执行，支持并发。
 *
 * <p>参考 Claude Code 的并发模型：
 * <ul>
 *   <li>并发安全（只读）工具可以与其他并发安全工具并行执行；</li>
 *   <li>非并发安全（有副作用）工具独占执行，并作为顺序屏障阻塞其后所有工具的启动；</li>
 *   <li>工具按到达顺序入队，屏障 + FIFO 天然保证执行顺序，无需显式依赖图。</li>
 * </ul>
 */
public class StreamingToolExecutor {

    private final List<Tool> toolDefinitions;
    private final ToolPermissionManager toolPermissionManager;
    private final List<TrackedTool> tools;
    private final ExecutorService executorService;
    private volatile boolean hasErrored;
    private volatile boolean discarded;

    public StreamingToolExecutor(List<Tool> toolDefinitions, ToolPermissionManager toolPermissionManager) {
        this.toolDefinitions = Objects.requireNonNull(toolDefinitions, "toolDefinitions must not be null");
        this.toolPermissionManager = Objects.requireNonNull(toolPermissionManager, "toolPermissionManager must not be null");
        this.tools = new ArrayList<>();
        this.executorService = Executors.newCachedThreadPool();
        this.hasErrored = false;
        this.discarded = false;
    }

    /**
     * 添加工具到执行队列，并尝试推进队列。
     */
    public synchronized void addTool(Message.ToolUse toolUse) {
        if (discarded) {
            return;
        }

        Tool toolDefinition = findTool(toolUse.name());
        if (toolDefinition == null) {
            // 工具不存在，直接标记为完成并返回错误
            TrackedTool tracked = new TrackedTool(
                    toolUse.id(),
                    toolUse.name(),
                    toolUse.input(),
                    false,
                    ToolStatus.COMPLETED
            );
            tracked.result = Tool.ToolExecutionResult.error("工具不存在: " + toolUse.name());
            tools.add(tracked);
            notifyAll();  // 唤醒等待中的 getRemainingResults
            return;
        }

        Tool.ValidationResult validationResult = toolDefinition.validateInput(toolUse.input());
        if (!validationResult.allowed()) {
            TrackedTool tracked = new TrackedTool(
                    toolUse.id(),
                    toolUse.name(),
                    toolUse.input(),
                    false,
                    ToolStatus.COMPLETED
            );
            tracked.result = Tool.ToolExecutionResult.error("工具参数无效: " + validationResult.message());
            tools.add(tracked);
            notifyAll();
            return;
        }

        ToolPermission permission = toolPermissionManager.permissionFor(toolUse.name());
        if (permission == ToolPermission.DENY) {
            TrackedTool tracked = new TrackedTool(
                    toolUse.id(),
                    toolUse.name(),
                    toolUse.input(),
                    false,
                    ToolStatus.COMPLETED
            );
            tracked.result = Tool.ToolExecutionResult.error("工具权限拒绝: " + toolUse.name());
            tools.add(tracked);
            notifyAll();
            return;
        }

        if (permission == ToolPermission.ASK) {
            boolean confirmed = toolPermissionManager.confirm(toolUse.name(), toolUse.input());
            if (!confirmed) {
                TrackedTool tracked = new TrackedTool(
                        toolUse.id(),
                        toolUse.name(),
                        toolUse.input(),
                        false,
                        ToolStatus.COMPLETED
                );
                tracked.result = Tool.ToolExecutionResult.error("工具未获得用户确认: " + toolUse.name());
                tools.add(tracked);
                notifyAll();
                return;
            }
        }

        boolean isConcurrencySafe = toolDefinition.isConcurrencySafe();
        TrackedTool tracked = new TrackedTool(
                toolUse.id(),
                toolUse.name(),
                toolUse.input(),
                isConcurrencySafe,
                ToolStatus.QUEUED
        );
        tools.add(tracked);

        processQueue();
    }

    /**
     * 处理队列，启动可以执行的工具。
     */
    private synchronized void processQueue() {
        for (TrackedTool tool : tools) {
            if (tool.status != ToolStatus.QUEUED) {
                continue;
            }

            // 前序 Bash 工具失败，取消后续所有工具（依赖链已断）
            if (hasErrored) {
                tool.result = Tool.ToolExecutionResult.error("已取消：前序 Bash 工具失败");
                tool.status = ToolStatus.COMPLETED;
                Console.info("  [取消] " + tool.toolName + " (id: " + tool.toolUseId + ")");
                notifyAll();
                continue;
            }

            if (canExecuteTool(tool.isConcurrencySafe)) {
                executeTool(tool);
            } else if (!tool.isConcurrencySafe) {
                // 非并发安全工具作为屏障：不能启动时，停止处理后续，保持顺序
                break;
            }
        }
    }

    /**
     * 检查工具是否可以执行：
     * <ul>
     *   <li>没有工具在执行 → 任何工具都可启动；</li>
     *   <li>有工具在执行 → 新工具必须并发安全，且所有在执行的也都并发安全。</li>
     * </ul>
     */
    private boolean canExecuteTool(boolean isConcurrencySafe) {
        List<TrackedTool> executing = tools.stream()
                .filter(t -> t.status == ToolStatus.EXECUTING)
                .toList();

        if (executing.isEmpty()) {
            return true;
        }

        return isConcurrencySafe && executing.stream().allMatch(t -> t.isConcurrencySafe);
    }

    /**
     * 异步执行工具。
     */
    private void executeTool(TrackedTool tracked) {
        tracked.status = ToolStatus.EXECUTING;

        // 能进队列的工具在 addTool 时已确认存在，这里直接取用
        Tool toolDefinition = findTool(tracked.toolName);

        CompletableFuture<Tool.ToolExecutionResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                Console.info("  [执行] " + tracked.toolName + " (id: " + tracked.toolUseId + ")");
                return toolDefinition.execute(tracked.input);
            } catch (Exception e) {
                return Tool.ToolExecutionResult.error("工具执行异常: " + e.getMessage());
            }
        }, executorService);

        tracked.future = future;

        future.whenComplete((result, throwable) -> {
            synchronized (StreamingToolExecutor.this) {
                if (throwable != null) {
                    tracked.result = Tool.ToolExecutionResult.error("工具执行失败: " + throwable.getMessage());
                } else {
                    tracked.result = result;
                }

                tracked.status = ToolStatus.COMPLETED;

                // Bash 工具出错：标记 hasErrored，并中断正在执行的 sibling 工具
                if (tracked.result.isError() && "Bash".equals(tracked.toolName)) {
                    hasErrored = true;
                    cancelExecutingSiblings(tracked);
                }

                Console.info("  [完成] " + tracked.toolName + " - " +
                        (tracked.result.isError() ? "错误" : "成功"));

                notifyAll();      // 唤醒 getRemainingResults
                processQueue();   // 推进队列
            }
        });
    }

    /**
     * 中断正在执行的 sibling 工具（Bash 失败时调用）。
     * 持有锁调用。
     */
    private void cancelExecutingSiblings(TrackedTool except) {
        for (TrackedTool tool : tools) {
            if (tool != except && tool.status == ToolStatus.EXECUTING && tool.future != null) {
                tool.future.cancel(true);  // 中断执行线程
            }
        }
    }

    /**
     * 获取已完成的结果（非阻塞，内部 helper）。
     */
    private synchronized List<ToolResult> getCompletedResults() {
        if (discarded) {
            return List.of();
        }

        List<ToolResult> results = new ArrayList<>();

        for (TrackedTool tool : tools) {
            if (tool.status == ToolStatus.YIELDED) {
                continue;
            }

            if (tool.status == ToolStatus.COMPLETED && tool.result != null) {
                tool.status = ToolStatus.YIELDED;
                results.add(new ToolResult(
                        tool.toolUseId,
                        tool.toolName,
                        tool.input,
                        tool.result.content(),
                        tool.result.isError()
                ));
            } else if (tool.status == ToolStatus.EXECUTING && !tool.isConcurrencySafe) {
                // 遇到正在执行的非并发工具，停止（保持顺序）
                break;
            }
        }

        return results;
    }

    /**
     * 等待所有工具完成并返回「全部」剩余结果（阻塞到所有工具都已产出）。
     * 使用 wait/notify 事件驱动等待，而非忙等。
     */
    public synchronized List<ToolResult> getRemainingResults() {
        List<ToolResult> all = new ArrayList<>();
        // 先 drain 一次：把进入本方法前就已完成的工具 YIELD 掉。
        // 这一步同时防止 lost-wakeup——若所有工具在调用前已完成，
        // 它们的 notifyAll 已成过去，drain 后 hasUnfinishedTools 即为 false，不会进入 wait 死等。
        all.addAll(getCompletedResults());

        while (hasUnfinishedTools()) {
            try {
                wait();  // 释放锁，等待 executeTool 完成回调 / addTool 的 notifyAll
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            all.addAll(getCompletedResults());
        }

        return all;
    }

    /**
     * 检查是否还有未完成的工具（未到 YIELDED 状态）。
     */
    private synchronized boolean hasUnfinishedTools() {
        return tools.stream().anyMatch(t -> t.status != ToolStatus.YIELDED);
    }

    /**
     * 丢弃所有工具：取消 in-flight 工具、阻止后续启动。
     * 用于流式中断/失败时回滚，防止 orphan 结果与重复副作用。
     */
    public synchronized void discard() {
        this.discarded = true;
        for (TrackedTool tool : tools) {
            if (tool.status == ToolStatus.EXECUTING && tool.future != null) {
                tool.future.cancel(true);
            }
        }
        notifyAll();
    }

    /**
     * 关闭执行器。
     */
    public void shutdown() {
        executorService.shutdownNow();
    }

    private Tool findTool(String name) {
        return toolDefinitions.stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private enum ToolStatus {
        QUEUED,      // 排队中
        EXECUTING,   // 执行中
        COMPLETED,   // 已完成
        YIELDED      // 已返回
    }

    private static class TrackedTool {
        final String toolUseId;
        final String toolName;
        final String input;
        final boolean isConcurrencySafe;
        ToolStatus status;
        Tool.ToolExecutionResult result;
        CompletableFuture<Tool.ToolExecutionResult> future;

        TrackedTool(String toolUseId, String toolName, String input,
                    boolean isConcurrencySafe, ToolStatus status) {
            this.toolUseId = toolUseId;
            this.toolName = toolName;
            this.input = input;
            this.isConcurrencySafe = isConcurrencySafe;
            this.status = status;
        }
    }

    /**
     * 工具执行结果。
     */
    public record ToolResult(String toolUseId, String toolName, String input, String content, boolean isError) {}
}
