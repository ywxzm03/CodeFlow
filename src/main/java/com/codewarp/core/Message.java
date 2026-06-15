package com.codewarp.core;

import java.util.List;

/**
 * 表示对话中的一条消息
 */
public sealed interface Message permits Message.User, Message.Assistant, Message.ToolResult {

    String role();

    /**
     * 用户消息
     */
    record User(String content) implements Message {
        @Override
        public String role() {
            return "user";
        }
    }

    /**
     * 助手消息
     */
    record Assistant(String content, List<ToolUse> toolUses) implements Message {
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
     * 工具结果消息
     */
    record ToolResult(String toolUseId, String content, boolean isError) implements Message {
        @Override
        public String role() {
            return "user";
        }
    }
}
