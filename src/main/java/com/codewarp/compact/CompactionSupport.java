package com.codewarp.compact;

import com.codewarp.core.Message;
import com.codewarp.core.WorkingMemory;
import com.codewarp.llm.LLMClient;
import com.codewarp.memory.TranscriptRecord;
import com.codewarp.memory.TranscriptRecorder;
import com.codewarp.memory.TranscriptStore;
import com.codewarp.util.Console;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 压缩共用逻辑。
 */
final class CompactionSupport {

    private static final String SYSTEM_PROMPT = """
            You summarize previous CodeWarp conversation context for continuation.
            Produce a concise but useful summary. Preserve decisions, constraints, file paths, commands, errors, fixes, tests, and pending tasks.
            Do not call tools.
            """;
    private static final List<String> KEYWORDS = List.of(
            "决定", "确认", "承诺", "必须", "不要", "以后", "默认", "配置", "路径", "key", "权限", "策略"
    );
    /** {@link #summaryContent} 产出的固定前缀，用来识别「上一次压缩的摘要消息」。 */
    private static final String SUMMARY_PREFIX = "This session has been compacted.";

    private CompactionSupport() {
    }

    /**
     * 计算需要保留的消息下标（保持原始顺序）：先按关键词，再按最近热消息。
     */
    static List<Integer> preservedIndices(List<Message> messages, int hotMessageCount, boolean keepKeywordMessages) {
        Set<Integer> preserved = new LinkedHashSet<>();
        if (keepKeywordMessages) {
            for (int i = 0; i < messages.size(); i++) {
                if (isKeyMessage(messages.get(i))) {
                    preserved.add(i);
                }
            }
        }
        int start = Math.max(0, messages.size() - Math.max(0, hotMessageCount));
        for (int i = start; i < messages.size(); i++) {
            preserved.add(i);
        }
        return preserved.stream().sorted().toList();
    }

    /**
     * 执行一次压缩并以单条原子 boundary 记录落盘。
     *
     * <p>流程：先 {@code recordUnpersisted} 保证完整原文（含 preserved）已在 L5；
     * 计算 preserved/cold；把摘要内联、preserved 用 uuid 引用，写入单条 boundary；
     * 写盘成功后用 {@link WorkingMemory#restore} 原子替换 L4。L5 先写，失败则 L4 不动、无需回滚。
     *
     * <p>上一次压缩的摘要消息（其 uuid 指向 boundary 记录而非 message 记录）不可被
     * uuid 引用，因此一律排除出 preserved、并入 cold 重新摘要。
     */
    static CompactionOutcome applyCompaction(
            WorkingMemory workingMemory,
            TranscriptRecorder recorder,
            TranscriptStore store,
            LLMClient llmClient,
            String mode,
            String reason,
            long estimatedTokensBefore,
            int hotMessageCount,
            int retryCount,
            boolean keepKeywordMessages,
            boolean requireNonEmptyCold
    ) {
        // 保证压缩前完整 L4（含 preserved 原文）已写入 L5。
        recorder.recordUnpersisted(workingMemory);

        List<WorkingMemory.Entry> beforeEntries = workingMemory.snapshotEntries();
        if (beforeEntries.isEmpty()) {
            return CompactionOutcome.notCompacted();
        }
        List<Message> beforeMessages = beforeEntries.stream().map(WorkingMemory.Entry::message).toList();

        // 排除上一次压缩的摘要消息：它没有独立 message 记录，无法被 uuid 引用，统一并入 cold。
        List<Integer> preservedIdx = preservedIndices(beforeMessages, hotMessageCount, keepKeywordMessages).stream()
                .filter(i -> !isCompactionSummary(beforeMessages.get(i)))
                .toList();
        Set<Integer> preservedSet = new HashSet<>(preservedIdx);

        List<Message> cold = new ArrayList<>();
        for (int i = 0; i < beforeMessages.size(); i++) {
            if (!preservedSet.contains(i)) {
                cold.add(beforeMessages.get(i));
            }
        }
        if (requireNonEmptyCold && cold.isEmpty()) {
            return CompactionOutcome.notCompacted();
        }

        List<WorkingMemory.Entry> preservedEntries = preservedIdx.stream().map(beforeEntries::get).toList();
        // preserved uuid 必须全部非空，否则 boundary 引用无法解析（recordUnpersisted 若因 IO 失败会留 null）。
        for (WorkingMemory.Entry entry : preservedEntries) {
            if (entry.transcriptUuid() == null || entry.transcriptUuid().isBlank()) {
                Console.warn("[Compact] preserved 消息缺少 transcript uuid，已跳过本次压缩");
                return CompactionOutcome.notCompacted();
            }
        }

        String summaryContent = summaryContent(summarize(llmClient, cold), recorder.transcriptPath());
        List<String> preservedUuids = preservedEntries.stream().map(WorkingMemory.Entry::transcriptUuid).toList();
        TranscriptRecord.CompactBoundary boundary = new TranscriptRecord.CompactBoundary(
                mode,
                reason,
                estimatedTokensBefore,
                hotMessageCount,
                retryCount,
                summaryContent,
                preservedUuids,
                recorder.transcriptPath()
        );

        // —— L5 先写：唯一会失败的 IO，单条原子记录 ——
        String boundaryUuid;
        try {
            boundaryUuid = store.appendCompactBoundary(recorder.sessionId(), boundary);
        } catch (IOException | IllegalArgumentException e) {
            Console.warn("[Compact] 写入 compact boundary 失败，已保留原始 L4: " + e.getMessage());
            return CompactionOutcome.ioFailure();
        }

        // L5 已落盘，原子替换 L4：摘要挂在 boundaryUuid 上（避免被 recordUnpersisted 重复落盘），preserved 保留原 uuid。
        List<WorkingMemory.Entry> newEntries = new ArrayList<>();
        newEntries.add(new WorkingMemory.Entry(new Message.User(summaryContent), boundaryUuid));
        newEntries.addAll(preservedEntries);
        workingMemory.restore(newEntries);
        return new CompactionOutcome(true, boundaryUuid, newEntries.size(), false);
    }

    /**
     * 用模型把冷数据压成摘要。
     */
    static String summarize(LLMClient llmClient, List<Message> cold) {
        if (cold == null || cold.isEmpty()) {
            return "No older cold messages needed summarization.";
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("Summarize these earlier CodeWarp messages for future continuation.\n\n");
        for (Message message : cold) {
            prompt.append(formatMessage(message)).append("\n\n");
        }
        return llmClient.call(SYSTEM_PROMPT, List.of(new Message.User(prompt.toString())), List.of()).content();
    }

    static String summaryContent(String summary, String transcriptPath) {
        return """
                This session has been compacted. The summary below covers earlier conversation context.

                %s

                Full transcript path: %s
                If specific historical details are needed, search or read the transcript with narrow terms.
                """.formatted(summary, transcriptPath).strip();
    }

    static boolean isCompactionSummary(Message message) {
        return message instanceof Message.User user
                && user.content() != null
                && user.content().startsWith(SUMMARY_PREFIX);
    }

    private static boolean isKeyMessage(Message message) {
        String text = switch (message) {
            case Message.User user -> user.content();
            case Message.Assistant assistant -> assistant.content();
            case Message.ToolResult ignored -> "";
        };
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String keyword : KEYWORDS) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String formatMessage(Message message) {
        return switch (message) {
            case Message.User user -> "[user] " + user.content();
            case Message.Assistant assistant -> "[assistant] " + assistant.content() + formatToolUses(assistant.toolUses());
            case Message.ToolResult toolResult -> "[tool_result " + toolResult.toolUseId() + "] " + toolResult.content();
        };
    }

    private static String formatToolUses(List<Message.ToolUse> toolUses) {
        if (toolUses == null || toolUses.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Message.ToolUse toolUse : toolUses) {
            builder.append("\n[tool_use ")
                    .append(toolUse.name())
                    .append("] ")
                    .append(toolUse.input());
        }
        return builder.toString();
    }

    /**
     * 一次压缩的结果。{@code ioFailed} 表示 L5 写盘失败、L4 未改动。
     */
    record CompactionOutcome(boolean compacted, String boundaryUuid, int messageCount, boolean ioFailed) {
        static CompactionOutcome notCompacted() {
            return new CompactionOutcome(false, null, 0, false);
        }

        static CompactionOutcome ioFailure() {
            return new CompactionOutcome(false, null, 0, true);
        }
    }
}
