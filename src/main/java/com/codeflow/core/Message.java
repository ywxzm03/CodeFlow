package com.codeflow.core;

import java.util.List;

/**
 * 表示对话中的一条消息
 */
public sealed interface Message permits Message.User, Message.Assistant, Message.ToolResult {

    String role();

    /**
     * 用户消息
     */
    record User(String content, boolean hidden) implements Message {
        public User(String content) {
            this(content, false);
        }

        @Override
        public String role() {
            return "user";
        }
    }

    /**
     * 助手消息
     */
    record Assistant(String content, List<ToolUse> toolUses, Usage usage) implements Message {
        @Override
        public String role() {
            return "assistant";
        }

        public boolean hasToolUses() {
            return toolUses != null && !toolUses.isEmpty();
        }
    }

    /**
     * 工具调用
     */
    record ToolUse(String id, String name, String input) {}

    /**
     * 模型返回的 token 用量。
     */
    record Usage(
            long inputTokens,
            long outputTokens,
            long cacheCreationInputTokens,
            long cacheReadInputTokens
    ) {
        public long totalTokens() {
            return inputTokens + outputTokens + cacheCreationInputTokens + cacheReadInputTokens;
        }
    }

    /**
     * 工具结果消息
     */
    record ToolResult(String toolUseId, String content, boolean isError) implements Message {
        @Override
        public String role() {
            return "user";
        }
    }
}
