package com.codewarp.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.InputJsonDelta;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawContentBlockStopEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.TextDelta;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Anthropic Messages API 客户端（基于官方 Anthropic Java SDK）。
 *
 * <p>流式工具调用参考 Claude Code 的做法：使用 SDK 的 raw stream，
 * 自己维护 content block 状态，并在 content_block_stop 时发出完整工具调用。
 */
public class AnthropicClient implements LLMClient {

    private final com.anthropic.client.AnthropicClient client;
    private final String model;
    private final int maxTokens;
    private final ObjectMapper objectMapper;

    public AnthropicClient(String apiKey, String baseUrl, String model, int maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
        this.objectMapper = new ObjectMapper();
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(normalizeBaseUrl(baseUrl))
                .build();
    }

    /**
     * 同步调用：一次性拿到完整 Message，遍历 content blocks 收集文本与工具调用。
     */
    @Override
    public LLMResponse call(String systemPrompt, List<com.codewarp.core.Message> messages, List<com.codewarp.tools.Tool> tools) {
        MessageCreateParams params = buildParams(systemPrompt, messages, tools);
        Message response = client.messages().create(params);

        StringBuilder content = new StringBuilder();
        List<com.codewarp.core.Message.ToolUse> toolUses = new ArrayList<>();

        for (ContentBlock block : response.content()) {
            if (block.isText()) {
                content.append(block.asText().text());
            } else if (block.isToolUse()) {
                ToolUseBlock toolUse = block.asToolUse();
                toolUses.add(new com.codewarp.core.Message.ToolUse(
                        toolUse.id(),
                        toolUse.name(),
                        jsonValueToString(toolUse._input())
                ));
            }
        }

        return new LLMResponse(content.toString(), toolUses);
    }

    @Override
    public Iterator<StreamEvent> callStreaming(String systemPrompt, List<com.codewarp.core.Message> messages, List<com.codewarp.tools.Tool> tools) {
        MessageCreateParams params = buildParams(systemPrompt, messages, tools);
        StreamResponse<RawMessageStreamEvent> streamResponse = client.messages().createStreaming(params);
        Iterator<RawMessageStreamEvent> stream = streamResponse.stream().iterator();

        // 把 SDK 的 push 式 raw 事件流适配成上层的 pull 式 StreamEvent 迭代器。
        // 难点：SDK 事件与 StreamEvent 不是一对一——很多事件（工具参数分片、message 级事件）
        // 产出 0 个 StreamEvent，而 Iterator.next() 必须返回一个元素。
        // 解法：pending 队列做缓冲，fill() 不断拉取 SDK 事件直到攒出至少一个 StreamEvent 或流结束。
        return new Iterator<>() {
            // 已就绪、待 next() 取走的事件
            private final Deque<StreamEvent> pending = new ArrayDeque<>();
            // 按 content block 索引累积状态（文本块的类型、工具块的 id/name/分片参数）
            private final Map<Long, BlockAccumulator> contentBlocks = new LinkedHashMap<>();
            private boolean streamClosed = false;

            @Override
            public boolean hasNext() {
                fill();
                return !pending.isEmpty();
            }

            @Override
            public StreamEvent next() {
                fill();
                if (pending.isEmpty()) {
                    throw new NoSuchElementException();
                }
                return pending.poll();
            }

            /**
             * 把底层 Anthropic 的原始流事件，尽量转换成上层 StreamEvent，塞进 pending 队列
             */
            private void fill() {
                while (pending.isEmpty() && !streamClosed) {
                    try {
                        if (stream.hasNext()) {
                            processEvent(stream.next());
                        } else {
                            closeStream();
                        }
                    } catch (RuntimeException e) {
                        closeStream();
                        throw e;
                    }
                }
            }

            /** 只关心三类 content_block 事件，其余（message_start/delta/stop、ping）忽略。 */
            private void processEvent(RawMessageStreamEvent event) {
                if (event.isContentBlockStart()) {
                    handleContentBlockStart(event.asContentBlockStart());
                } else if (event.isContentBlockDelta()) {
                    handleContentBlockDelta(event.asContentBlockDelta());
                } else if (event.isContentBlockStop()) {
                    handleContentBlockStop(event.asContentBlockStop());
                }
            }

            /**
             * 块开始：工具块只记下 id/name 待后续拼参数；文本块若自带初始文本则立即发出。
             */
            private void handleContentBlockStart(RawContentBlockStartEvent event) {
                RawContentBlockStartEvent.ContentBlock block = event.contentBlock();
                long index = event.index();

                if (block.isToolUse()) {
                    ToolUseBlock toolUse = block.asToolUse();
                    contentBlocks.put(index, new BlockAccumulator(
                            BlockType.TOOL_USE,
                            toolUse.id(),
                            toolUse.name()
                    ));
                } else if (block.isText()) {
                    contentBlocks.put(index, new BlockAccumulator(BlockType.TEXT, null, null));
                    String text = block.asText().text();
                    if (!text.isEmpty()) {
                        pending.add(new StreamEvent.TextDelta(text));
                    }
                } else {
                    contentBlocks.put(index, new BlockAccumulator(BlockType.OTHER, null, null));
                }
            }

            /**
             * 块增量：文本增量即时发出（逐字流式）；工具的 input_json 分片追加到累积器，暂不发出。
             */
            private void handleContentBlockDelta(RawContentBlockDeltaEvent event) {
                BlockAccumulator block = contentBlocks.get(event.index());
                if (block == null) {
                    return;
                }

                RawContentBlockDelta delta = event.delta();
                if (delta.isText()) {
                    TextDelta textDelta = delta.asText();
                    String text = textDelta.text();
                    if (!text.isEmpty()) {
                        pending.add(new StreamEvent.TextDelta(text));
                    }
                } else if (delta.isInputJson() && block.type == BlockType.TOOL_USE) {
                    InputJsonDelta inputDelta = delta.asInputJson();
                    block.input.append(inputDelta.partialJson());
                }
            }

            /**
             * 块结束：工具块此刻参数已拼完整，发出完整的 ToolUse 事件（文本块无需处理）。
             */
            private void handleContentBlockStop(RawContentBlockStopEvent event) {
                BlockAccumulator block = contentBlocks.get(event.index());
                if (block == null || block.type != BlockType.TOOL_USE) {
                    return;
                }

                pending.add(new StreamEvent.ToolUse(new com.codewarp.core.Message.ToolUse(
                        block.id,
                        block.name,
                        block.input.toString()
                )));
            }

            private void closeStream() {
                if (!streamClosed) {
                    streamClosed = true;
                    streamResponse.close();
                }
            }
        };
    }

    /**
     * 构建 Messages API 请求参数：system + 历史消息 + 工具定义。
     */
    private MessageCreateParams buildParams(String systemPrompt, List<com.codewarp.core.Message> messages, List<com.codewarp.tools.Tool> tools) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens);

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            builder.system(systemPrompt);
        }

        for (com.codewarp.core.Message message : messages) {
            builder.addMessage(toAnthropicMessage(message));
        }

        if (tools != null) {
            for (com.codewarp.tools.Tool tool : tools) {
                builder.addTool(toAnthropicTool(tool));
            }
        }

        return builder.build();
    }

    /**
     * 把内部 Message 转成 Anthropic MessageParam。
     * 注意：工具结果（ToolResult）在 Anthropic 协议里属于 user 角色的 tool_result 块，而非独立角色。
     */
    private MessageParam toAnthropicMessage(com.codewarp.core.Message message) {
        return switch (message) {
            case com.codewarp.core.Message.User user -> MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(user.content())
                    .build();

            case com.codewarp.core.Message.Assistant assistant -> {
                List<ContentBlockParam> blocks = new ArrayList<>();
                if (assistant.content() != null && !assistant.content().isEmpty()) {
                    blocks.add(ContentBlockParam.ofText(TextBlockParam.builder()
                            .text(assistant.content())
                            .build()));
                }
                if (assistant.hasToolUses()) {
                    for (com.codewarp.core.Message.ToolUse toolUse : assistant.toolUses()) {
                        blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                                .id(toolUse.id())
                                .name(toolUse.name())
                                .input(toToolUseInput(toolUse.input()))
                                .build()));
                    }
                }
                yield MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .contentOfBlockParams(blocks)
                        .build();
            }

            case com.codewarp.core.Message.ToolResult toolResult -> MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(List.of(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolResult.toolUseId())
                                    .content(toolResult.content())
                                    .isError(toolResult.isError())
                                    .build()
                    )))
                    .build();
        };
    }

    private Tool toAnthropicTool(com.codewarp.tools.Tool tool) {
        return Tool.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(toInputSchema(tool.inputSchema()))
                .build();
    }

    /**
     * 把工具的 JSON Schema 字符串转成 SDK 的 InputSchema：
     * type/properties/required 走专用 setter，其余字段（如 additionalProperties、$defs）原样透传。
     */
    private Tool.InputSchema toInputSchema(String schemaJson) {
        try {
            JsonNode root = objectMapper.readTree(schemaJson);
            Tool.InputSchema.Builder builder = Tool.InputSchema.builder()
                    .type(JsonValue.from(root.get("type").asText()));

            JsonNode properties = root.get("properties");
            if (properties != null && properties.isObject()) {
                Map<String, JsonValue> propertyMap = new LinkedHashMap<>();
                properties.fields().forEachRemaining(entry ->
                        propertyMap.put(entry.getKey(), JsonValue.fromJsonNode(entry.getValue())));
                builder.properties(Tool.InputSchema.Properties.builder()
                        .additionalProperties(propertyMap)
                        .build());
            }

            JsonNode required = root.get("required");
            if (required != null && required.isArray()) {
                List<String> requiredFields = new ArrayList<>();
                required.forEach(node -> requiredFields.add(node.asText()));
                builder.required(requiredFields);
            }

            Map<String, JsonValue> additional = new LinkedHashMap<>();
            root.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                if (!"type".equals(key) && !"properties".equals(key) && !"required".equals(key)) {
                    additional.put(key, JsonValue.fromJsonNode(entry.getValue()));
                }
            });
            if (!additional.isEmpty()) {
                builder.additionalProperties(additional);
            }

            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException("解析工具 JSON Schema 失败", e);
        }
    }

    /**
     * 把工具调用输入（JSON 字符串）转成 SDK 的 Input 对象，供历史消息回放时使用。
     * 空输入按空对象处理。
     */
    private ToolUseBlockParam.Input toToolUseInput(String inputJson) {
        try {
            JsonNode root = inputJson == null || inputJson.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(inputJson);
            Map<String, JsonValue> fields = objectMapper.convertValue(
                    root, new TypeReference<Map<String, Object>>() {}
            ).entrySet().stream().collect(
                    LinkedHashMap::new,
                    (map, entry) -> map.put(entry.getKey(), JsonValue.from(entry.getValue())),
                    LinkedHashMap::putAll
            );
            return ToolUseBlockParam.Input.builder()
                    .additionalProperties(fields)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("解析工具调用输入失败", e);
        }
    }

    /**
     * 把 SDK 的 JsonValue（同步响应里的工具 input）序列化成 JSON 字符串，
     * 与流式路径下累积出的 input 字符串保持同一表示。
     */
    private String jsonValueToString(JsonValue value) {
        try {
            Object raw = value.convert(Object.class);
            return objectMapper.writeValueAsString(raw);
        } catch (Exception e) {
            throw new RuntimeException("序列化工具调用输入失败", e);
        }
    }

    /**
     * SDK builder 接收 API 根地址，不接 /v1/messages 完整路径。
     */
    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.anthropic.com";
        }

        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/v1/messages")) {
            return url.substring(0, url.length() - "/v1/messages".length());
        }
        return url;
    }

    private enum BlockType {
        TEXT,
        TOOL_USE,
        OTHER
    }

    /**
     * 单个 content block 的流式累积状态。
     * 文本块只用到 type；工具块用 id/name 标识、input 跨多个 delta 拼接出完整参数。
     */
    private static final class BlockAccumulator {
        final BlockType type;
        final String id;
        final String name;
        final StringBuilder input = new StringBuilder();

        BlockAccumulator(BlockType type, String id, String name) {
            this.type = type;
            this.id = id;
            this.name = name;
        }
    }
}
