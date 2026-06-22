package com.codeflow.compact;

import com.codeflow.core.Message;
import com.codeflow.tools.Tool;

import java.util.List;

/**
 * 上下文 token 估算器。
 */
public final class TokenEstimator {

    private static final int DEFAULT_CHARS_PER_TOKEN = 4;

    public long estimate(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        // 优先用最近一次真实 usage，再粗算后续新增消息。
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof Message.Assistant assistant && assistant.usage() != null) {
                return assistant.usage().totalTokens() + estimateRough(messages.subList(i + 1, messages.size()));
            }
        }

        return estimateRough(messages);
    }

    /**
     * 估算完整请求：system + messages + tools。
     */
    public long estimate(String systemPrompt, List<Message> messages, List<Tool> tools) {
        long total = roughText(systemPrompt);
        total += estimate(messages);
        if (tools != null) {
            for (Tool tool : tools) {
                if (tool == null) {
                    continue;
                }
                total += roughText(tool.name());
                total += roughText(tool.description());
                total += roughText(tool.inputSchema());
            }
        }
        return total;
    }

    public long estimateRough(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        long total = 0;
        for (Message message : messages) {
            total += estimateRough(message);
        }
        return total;
    }

    public long estimateRough(Message message) {
        return switch (message) {
            case Message.User user -> roughText(user.content());
            case Message.Assistant assistant -> {
                long total = roughText(assistant.content());
                if (assistant.toolUses() != null) {
                    for (Message.ToolUse toolUse : assistant.toolUses()) {
                        total += roughText(toolUse.name() + toolUse.input());
                    }
                }
                yield total;
            }
            case Message.ToolResult toolResult -> roughText(toolResult.content());
        };
    }

    public long roughText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.round((double) text.length() / DEFAULT_CHARS_PER_TOKEN);
    }
}
