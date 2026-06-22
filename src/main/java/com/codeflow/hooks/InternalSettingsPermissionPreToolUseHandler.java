package com.codeflow.hooks;

import com.codeflow.config.ConfigManager;
import com.codeflow.config.Settings;
import com.codeflow.permissions.ToolPermission;
import com.codeflow.permissions.ToolPermissionConfig;
import com.codeflow.util.Console;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

public class InternalSettingsPermissionPreToolUseHandler implements PreToolUseHandler {

    private final ConfigManager configManager;

    public InternalSettingsPermissionPreToolUseHandler(ConfigManager configManager) {
        this.configManager = Objects.requireNonNull(configManager, "configManager must not be null");
    }

    @Override
    public PreToolUseResult handle(PreToolUseInput input) {
        if (input == null || input.toolName() == null || input.toolName().isBlank()) {
            return PreToolUseResult.none();
        }

        Optional<Settings> settings;
        try {
            settings = configManager.loadExisting();
        } catch (IOException e) {
            Console.warn("[Hooks] PreToolUse 读取 settings.json 失败，回落到默认 ask: " + e.getMessage());
            return PreToolUseResult.none();
        }
        if (settings.isEmpty() || settings.get().toolPermissions() == null) {
            return PreToolUseResult.none();
        }

        Optional<ToolPermission> permission = new ToolPermissionConfig(settings.get().toolPermissions())
                .configuredPermissionFor(input.toolName());
        if (permission.isEmpty()) {
            return PreToolUseResult.none();
        }

        String reason = "settings.json tool_permissions matched " + input.toolName();
        return switch (permission.get()) {
            case ALLOW -> PreToolUseResult.allow(reason);
            case ASK -> PreToolUseResult.ask(reason);
            case DENY -> PreToolUseResult.deny(reason);
        };
    }
}
