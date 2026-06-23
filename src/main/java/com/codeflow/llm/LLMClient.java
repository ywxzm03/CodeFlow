package com.codeflow.llm;

import com.codeflow.core.Message;
import com.codeflow.core.CancellationToken;
import com.codeflow.tools.Tool;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * LLM客户端接口 - 支持流式响应
 */
public interface LLMClient {

    /**
     * 调用LLM（同步方式）
     *
     * @param systemPrompt 系统提示词
     * @param messages 历史消息
     * @param tools 可用工具列表
     * @return LLM响应
     */
    LLMResponse call(String systemPrompt, List<Message> messages, List<Tool> tools);

    default LLMResponse call(String systemPrompt, List<Message> messages, List<Tool> tools, CancellationToken cancellationToken) {
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        token.throwIfCancelled();
        LLMResponse response = call(systemPrompt, messages, tools);
        token.throwIfCancelled();
        return response;
    }

    /**
     * 调用LLM（流式方式）
     *
     * <p>必须由实现类提供真正的流式实现——不提供「退化为同步」的默认实现，
     * 避免出现伪流式。
     *
     * @param systemPrompt 系统提示词
     * @param messages 历史消息
     * @param tools 可用工具列表
     * @return 流式事件序列
     */
    Flux<StreamEvent> callStreaming(String systemPrompt, List<Message> messages, List<Tool> tools);

    default Flux<StreamEvent> callStreaming(String systemPrompt, List<Message> messages, List<Tool> tools, CancellationToken cancellationToken) {
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        return callStreaming(systemPrompt, messages, tools)
                .doOnSubscribe(subscription -> token.throwIfCancelled())
                .doOnNext(ignored -> token.throwIfCancelled());
    }

    /**
     * 切换后续请求使用的模型。
     */
    void setModel(String model);

    /**
     * LLM响应
     */
    record LLMResponse(String content, List<Message.ToolUse> toolUses, Message.Usage usage) {
        public boolean hasToolUses() {
            return toolUses != null && !toolUses.isEmpty();
        }
    }

    /**
     * 流式事件
     */
    sealed interface StreamEvent {
        /**
         * 文本内容事件
         */
        record TextDelta(String text) implements StreamEvent {}

        /**
         * 工具调用事件
         */
        record ToolUse(Message.ToolUse toolUse) implements StreamEvent {}

        /**
         * 本次响应的 token 用量事件。
         */
        record Usage(Message.Usage usage) implements StreamEvent {}
    }
}
