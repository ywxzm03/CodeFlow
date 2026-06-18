package com.codewarp.permissions;

import com.codewarp.config.Settings;
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
}
