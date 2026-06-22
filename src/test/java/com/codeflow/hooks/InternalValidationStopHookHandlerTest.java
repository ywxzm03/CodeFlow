package com.codeflow.hooks;

import com.codeflow.permissions.PermissionMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalValidationStopHookHandlerTest {

    private final InternalValidationStopHookHandler handler = new InternalValidationStopHookHandler();

    @Test
    void blocksWhenFinalMessageDoesNotMentionValidation() {
        StopHookResult result = handler.handle(input("改好了。", false));

        assertTrue(result.blocked());
        assertTrue(result.feedback().contains("没有说明测试或验证结果"));
    }

    @Test
    void allowsWhenFinalMessageMentionsValidation() {
        StopHookResult result = handler.handle(input("已运行 ./gradlew test，测试通过。", false));

        assertFalse(result.blocked());
    }

    @Test
    void allowsWhenStopHookIsAlreadyActive() {
        StopHookResult result = handler.handle(input("改好了。", true));

        assertFalse(result.blocked());
    }

    private StopHookInput input(String lastAssistantMessage, boolean stopHookActive) {
        return new StopHookInput(
                lastAssistantMessage,
                "/tmp/project",
                PermissionMode.ASK,
                stopHookActive
        );
    }
}
