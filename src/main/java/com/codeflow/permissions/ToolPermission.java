package com.codeflow.permissions;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum ToolPermission {
    ALLOW("allow"),
    DENY("deny"),
    ASK("ask");

    private final String configValue;

    ToolPermission(String configValue) {
        this.configValue = configValue;
    }

    @JsonCreator
    public static ToolPermission fromConfigValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("工具权限不能为空");
        }

        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "allow" -> ALLOW;
            case "deny" -> DENY;
            case "ask" -> ASK;
            default -> throw new IllegalArgumentException(
                    "工具权限只能是 allow、deny 或 ask: " + value
            );
        };
    }

    @JsonValue
    public String configValue() {
        return configValue;
    }
}
