package com.codewarp.llm;

import com.codewarp.core.Message;
import com.codewarp.tools.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Anthropic Claude API客户端
 */
public class AnthropicClient implements LLMClient {

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AnthropicClient(String apiKey, String baseUrl, String model, int maxTokens) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.maxTokens = maxTokens;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public LLMResponse call(String systemPrompt, List<Message> messages, List<Tool> tools) {
        try {
            // 构建请求体
            String requestBody = buildRequestBody(systemPrompt, messages, tools);

            // 发送HTTP请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("API调用失败: " + response.statusCode() + " - " + response.body());
            }

            // 解析响应
            return parseResponse(response.body());

        } catch (Exception e) {
            throw new RuntimeException("LLM调用失败", e);
        }
    }

    private String buildRequestBody(String systemPrompt, List<Message> messages, List<Tool> tools) throws Exception {
        var root = objectMapper.createObjectNode();

        root.put("model", model);
        root.put("max_tokens", maxTokens);
        root.put("system", systemPrompt);

        // 转换消息
        var messagesArray = root.putArray("messages");
        for (Message msg : messages) {
            var msgNode = objectMapper.createObjectNode();
            msgNode.put("role", msg.role());

            switch (msg) {
                case Message.User user -> {
                    msgNode.put("content", user.content());
                }
                case Message.Assistant assistant -> {
                    var contentArray = msgNode.putArray("content");

                    // 添加文本内容
                    if (assistant.content() != null && !assistant.content().isEmpty()) {
                        var textBlock = objectMapper.createObjectNode();
                        textBlock.put("type", "text");
                        textBlock.put("text", assistant.content());
                        contentArray.add(textBlock);
                    }

                    // 添加工具调用
                    if (assistant.hasToolUses()) {
                        for (var toolUse : assistant.toolUses()) {
                            var toolBlock = objectMapper.createObjectNode();
                            toolBlock.put("type", "tool_use");
                            toolBlock.put("id", toolUse.id());
                            toolBlock.put("name", toolUse.name());
                            toolBlock.set("input", objectMapper.readTree(toolUse.input()));
                            contentArray.add(toolBlock);
                        }
                    }
                }
                case Message.ToolResult toolResult -> {
                    var contentArray = msgNode.putArray("content");
                    var toolResultBlock = objectMapper.createObjectNode();
                    toolResultBlock.put("type", "tool_result");
                    toolResultBlock.put("tool_use_id", toolResult.toolUseId());
                    toolResultBlock.put("content", toolResult.content());
                    if (toolResult.isError()) {
                        toolResultBlock.put("is_error", true);
                    }
                    contentArray.add(toolResultBlock);
                }
            }

            messagesArray.add(msgNode);
        }

        // 添加工具定义
        if (tools != null && !tools.isEmpty()) {
            var toolsArray = root.putArray("tools");
            for (Tool tool : tools) {
                var toolNode = objectMapper.createObjectNode();
                toolNode.put("name", tool.name());
                toolNode.put("description", tool.description());
                toolNode.set("input_schema", objectMapper.readTree(tool.inputSchema()));
                toolsArray.add(toolNode);
            }
        }

        return objectMapper.writeValueAsString(root);
    }

    private LLMResponse parseResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode contentArray = root.get("content");

        StringBuilder textContent = new StringBuilder();
        List<Message.ToolUse> toolUses = new ArrayList<>();

        for (JsonNode block : contentArray) {
            String type = block.get("type").asText();

            if ("text".equals(type)) {
                textContent.append(block.get("text").asText());
            } else if ("tool_use".equals(type)) {
                String id = block.get("id").asText();
                String name = block.get("name").asText();
                String input = objectMapper.writeValueAsString(block.get("input"));
                toolUses.add(new Message.ToolUse(id, name, input));
            }
        }

        return new LLMResponse(textContent.toString(), toolUses);
    }
}
