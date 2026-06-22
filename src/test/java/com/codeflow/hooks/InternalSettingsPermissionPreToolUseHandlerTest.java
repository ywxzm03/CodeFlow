package com.codeflow.hooks;

import com.codeflow.config.ConfigManager;
import com.codeflow.config.Settings;
import com.codeflow.permissions.PermissionMode;
import com.codeflow.permissions.ToolPermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InternalSettingsPermissionPreToolUseHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void readsAllowAskAndDenyToolPermissionsFromExistingSettings() throws Exception {
        ConfigManager configManager = new ConfigManager(tempDir);
        configManager.save(new Settings(
                "key",
                "url",
                "A",
                Settings.defaults().resolvedModels(),
                1000,
                5,
                PermissionMode.ASK,
                Map.of(
                        "Read", ToolPermission.ALLOW,
                        "Write", ToolPermission.ASK,
                        "Bash", ToolPermission.DENY
                ),
                Settings.Compaction.defaults()
        ));
        InternalSettingsPermissionPreToolUseHandler handler = new InternalSettingsPermissionPreToolUseHandler(configManager);

        assertEquals(HookDecision.ALLOW, handler.handle(input("Read")).decision());
        assertEquals(HookDecision.ASK, handler.handle(input("Write")).decision());
        assertEquals(HookDecision.DENY, handler.handle(input("Bash")).decision());
    }

    @Test
    void missingToolPermissionReturnsNone() throws Exception {
        ConfigManager configManager = new ConfigManager(tempDir);
        configManager.save(new Settings(
                "key",
                "url",
                "A",
                Settings.defaults().resolvedModels(),
                1000,
                5,
                PermissionMode.ASK,
                Map.of("Read", ToolPermission.ALLOW),
                Settings.Compaction.defaults()
        ));
        InternalSettingsPermissionPreToolUseHandler handler = new InternalSettingsPermissionPreToolUseHandler(configManager);

        assertEquals(HookDecision.NONE, handler.handle(input("Bash")).decision());
    }

    @Test
    void missingSettingsFileReturnsNoneWithoutCreatingFile() {
        ConfigManager configManager = new ConfigManager(tempDir);
        InternalSettingsPermissionPreToolUseHandler handler = new InternalSettingsPermissionPreToolUseHandler(configManager);

        assertEquals(HookDecision.NONE, handler.handle(input("Read")).decision());
        assertEquals(false, Files.exists(tempDir.resolve("settings.json")));
    }

    @Test
    void unreadableSettingsReturnsNone() throws Exception {
        Files.writeString(tempDir.resolve("settings.json"), "{");
        ConfigManager configManager = new ConfigManager(tempDir);
        InternalSettingsPermissionPreToolUseHandler handler = new InternalSettingsPermissionPreToolUseHandler(configManager);

        assertEquals(HookDecision.NONE, handler.handle(input("Read")).decision());
    }

    private PreToolUseInput input(String toolName) {
        return new PreToolUseInput("toolu_test", toolName, "{}", tempDir.toString(), PermissionMode.ASK);
    }
}
