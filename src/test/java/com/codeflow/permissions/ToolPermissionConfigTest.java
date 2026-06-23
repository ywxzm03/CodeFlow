package com.codeflow.permissions;

import com.codeflow.config.Settings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolPermissionConfigTest {

    @Test
    void missingToolPermissionDefaultsToAsk() {
        ToolPermissionConfig config = new ToolPermissionConfig(Map.of("Read", ToolPermission.ALLOW));

        assertEquals(ToolPermission.ASK, config.permissionFor("Bash"));
    }

    @Test
    void toolNameMatchingIsCaseInsensitive() {
        ToolPermissionConfig config = new ToolPermissionConfig(Map.of("read", ToolPermission.ALLOW));

        assertEquals(ToolPermission.ALLOW, config.permissionFor("Read"));
    }

    @Test
    void settingsReadsToolPermissionsFromJson() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        Settings settings = objectMapper.readValue("""
                {
                  "api_key": "key",
                  "base_url": "url",
                  "model": "A",
                  "max_tokens": 1000,
                  "max_iterations": 5,
                  "tool_permissions": {
                    "Read": "allow",
                    "Bash": "deny",
                    "Write": "ask"
                  }
                }
                """, Settings.class);

        assertEquals(ToolPermission.ALLOW, settings.toolPermissions().get("Read"));
        assertEquals(ToolPermission.DENY, settings.toolPermissions().get("Bash"));
        assertEquals(ToolPermission.ASK, settings.toolPermissions().get("Write"));
    }

    @Test
    void settingsReadsPermissionModeFromJson() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        Settings settings = objectMapper.readValue("""
                {
                  "api_key": "key",
                  "base_url": "url",
                  "model": "A",
                  "max_tokens": 1000,
                  "max_iterations": 5,
                  "permission_mode": "full_access"
                }
                """, Settings.class);

        assertEquals(PermissionMode.FULL_ACCESS, settings.permissionMode());
    }

    @Test
    void settingsReadsRoutingFromJson() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        Settings settings = objectMapper.readValue("""
                {
                  "api_key": "key",
                  "base_url": "url",
                  "model": "A",
                  "max_tokens": 1000,
                  "max_iterations": 5,
                  "routing": {
                    "enabled": false,
                    "retry_current_model_once": false,
                    "unhealthy_cooldown_seconds": 120
                  }
                }
                """, Settings.class);

        assertEquals(false, settings.resolvedRouting().enabled());
        assertEquals(false, settings.resolvedRouting().retryCurrentModelOnce());
        assertEquals(120, settings.resolvedRouting().unhealthyCooldownSeconds());
    }

    @Test
    void routingCooldownMustBePositive() {
        Settings settings = new Settings(
                "key",
                "url",
                "A",
                Settings.defaults().resolvedModels(),
                1000,
                5,
                PermissionMode.ASK,
                Map.of(),
                Settings.Compaction.defaults(),
                new Settings.Routing(true, true, 0),
                Settings.Hooks.defaults()
        );

        assertEquals(false, settings.validate().valid());
    }
}
