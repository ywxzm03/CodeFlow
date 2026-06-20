package com.codewarp.compact;

import com.codewarp.core.Message;
import com.codewarp.llm.LLMClient;

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

    private CompactionSupport() {
    }

    /**
     * 按需保留关键消息，再保留最近热消息。
     */
    static List<Message> preservedMessages(List<Message> messages, int hotMessageCount, boolean keepKeywordMessages) {
        Set<Message> preserved = new LinkedHashSet<>();
        if (keepKeywordMessages) {
            for (Message message : messages) {
                if (isKeyMessage(message)) {
                    preserved.add(message);
                }
            }
        }
        int start = Math.max(0, messages.size() - Math.max(0, hotMessageCount));
        for (int i = start; i < messages.size(); i++) {
            preserved.add(messages.get(i));
        }
        return messages.stream().filter(preserved::contains).toList();
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
}
