package com.codeflow.permissions;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum PermissionMode {
    ASK("ask", "Ask"),
    FULL_ACCESS("full_access", "Full Access"),
    BATCH_WORKER("batch_worker", "Batch Worker");

    private final String configValue;
    private final String displayName;

    PermissionMode(String configValue, String displayName) {
        this.configValue = configValue;
        this.displayName = displayName;
    }

    @JsonCreator
    public static PermissionMode fromConfigValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("权限模式不能为空");
        }

        return switch (value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_')) {
            case "ask" -> ASK;
            case "full_access" -> FULL_ACCESS;
            case "batch_worker" -> BATCH_WORKER;
            default -> throw new IllegalArgumentException(
                    "权限模式只能是 ask、full_access 或 batch_worker: " + value
            );
        };
    }

    @JsonValue
    public String configValue() {
        return configValue;
    }

    public String displayName() {
        return displayName;
    }
}
