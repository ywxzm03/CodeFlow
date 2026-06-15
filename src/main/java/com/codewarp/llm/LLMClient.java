package com.codewarp.llm;

import com.codewarp.core.Message;
import com.codewarp.tools.Tool;

import java.util.List;

/**
 * LLM客户端接口
 */
public interface LLMClient {

    /**
     * 调用LLM
     *
     * @param systemPrompt 系统提示词
     * @param messages 历史消息
     * @param tools 可用工具列表
     * @return LLM响应
     */
    LLMResponse call(String systemPrompt, List<Message> messages, List<Tool> tools);

    /**
     * LLM响应
     */
    record LLMResponse(String content, List<Message.ToolUse> toolUses) {
        public boolean hasToolUses() {
            return toolUses != null && !toolUses.isEmpty();
        }
    }
}
