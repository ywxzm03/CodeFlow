package com.codewarp.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

final class ToolInputValidator {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private ToolInputValidator() {
    }

    static JsonNode parseObject(String input) {
        JsonNode node;
        try {
            node = objectMapper.readTree(input);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("输入不是合法 JSON: " + e.getOriginalMessage());
        }

        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("输入必须是 JSON 对象");
        }

        return node;
    }

    static void requireText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("缺少必填参数: " + field);
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("参数必须是字符串: " + field);
        }
        if (value.asText().isBlank()) {
            throw new IllegalArgumentException("参数不能为空: " + field);
        }
    }

    static void requireTextAllowEmpty(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("缺少必填参数: " + field);
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("参数必须是字符串: " + field);
        }
    }

    static void optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && !value.isNull() && !value.isTextual()) {
            throw new IllegalArgumentException("参数必须是字符串: " + field);
        }
    }

    static void optionalBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && !value.isNull() && !value.isBoolean()) {
            throw new IllegalArgumentException("参数必须是布尔值: " + field);
        }
    }

    static void optionalEnum(JsonNode node, String field, Set<String> allowedValues) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("参数必须是字符串: " + field);
        }
        if (!allowedValues.contains(value.asText())) {
            throw new IllegalArgumentException("参数取值非法: " + field + "，允许值: " + allowedValues);
        }
    }

    static void requireTextArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("缺少必填参数: " + field);
        }
        validateTextArray(value, field, true);
    }

    static void optionalTextArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && !value.isNull()) {
            validateTextArray(value, field, false);
        }
    }

    private static void validateTextArray(JsonNode value, String field, boolean requireNonEmpty) {
        if (!value.isArray()) {
            throw new IllegalArgumentException("参数必须是字符串数组: " + field);
        }
        if (requireNonEmpty && value.isEmpty()) {
            throw new IllegalArgumentException("参数不能为空数组: " + field);
        }
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("参数数组只能包含字符串: " + field);
            }
            if (item.asText().isBlank()) {
                throw new IllegalArgumentException("参数数组不能包含空字符串: " + field);
            }
        }
    }

    static void rejectUnknownFields(JsonNode node, Set<String> allowedFields) {
        List<String> unknownFields = node.properties().stream()
                .map(entry -> entry.getKey())
                .filter(field -> !allowedFields.contains(field))
                .toList();

        if (!unknownFields.isEmpty()) {
            throw new IllegalArgumentException("包含未知参数: " + unknownFields);
        }
    }
}
