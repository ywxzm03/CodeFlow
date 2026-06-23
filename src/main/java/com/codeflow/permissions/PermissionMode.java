package com.codeflow.permissions;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum PermissionMode {
    ASK("ask", "Ask"),
    FULL_ACCESS("full_access", "Full Access"),
    BATCH_WORKER("batch_worker", "Batch Worker"),
    SUBAGENT_READ_ONLY("subagent_read_only", "Subagent Read Only"),
    SUBAGENT_CODER("subagent_coder", "Subagent Coder"),
    SUBAGENT_VERIFIER("subagent_verifier", "Subagent Verifier");

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
            case "subagent_read_only" -> SUBAGENT_READ_ONLY;
            case "subagent_coder" -> SUBAGENT_CODER;
            case "subagent_verifier" -> SUBAGENT_VERIFIER;
            default -> throw new IllegalArgumentException(
                    "权限模式只能是 ask、full_access、batch_worker、subagent_read_only、subagent_coder 或 subagent_verifier: " + value
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
