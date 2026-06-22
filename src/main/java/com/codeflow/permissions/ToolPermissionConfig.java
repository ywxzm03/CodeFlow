package com.codeflow.permissions;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class ToolPermissionConfig {

    private final Map<String, ToolPermission> permissions;

    public ToolPermissionConfig(Map<String, ToolPermission> permissions) {
        this.permissions = normalize(permissions);
    }

    public static ToolPermissionConfig empty() {
        return new ToolPermissionConfig(Map.of());
    }

    public ToolPermission permissionFor(String toolName) {
        return configuredPermissionFor(toolName).orElse(ToolPermission.ASK);
    }

    public Optional<ToolPermission> configuredPermissionFor(String toolName) {
        if (toolName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(permissions.get(normalizeToolName(toolName)));
    }

    private static Map<String, ToolPermission> normalize(Map<String, ToolPermission> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return Map.of();
        }

        Map<String, ToolPermission> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, ToolPermission> entry : permissions.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            normalized.put(normalizeToolName(entry.getKey()), entry.getValue());
        }
        return Map.copyOf(normalized);
    }

    private static String normalizeToolName(String toolName) {
        return toolName.trim().toLowerCase(Locale.ROOT);
    }
}
