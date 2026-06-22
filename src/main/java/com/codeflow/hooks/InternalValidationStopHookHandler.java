package com.codeflow.hooks;

import java.util.Locale;
import java.util.regex.Pattern;

public class InternalValidationStopHookHandler implements StopHookHandler {

    public static final String FEEDBACK = "你准备结束了，但没有说明测试或验证结果。请先补充验证。";

    private static final Pattern VALIDATION_KEYWORDS = Pattern.compile(
            "(测试|验证|校验|检查|通过|失败|未运行|无法运行|没有运行|test|tests|tested|testing|verify|verified|verification|validation|check|checked|checks|passed|failed|not run|could not run)"
    );

    @Override
    public StopHookResult handle(StopHookInput input) {
        if (input == null || input.stopHookActive()) {
            return StopHookResult.allow();
        }

        String lastAssistantMessage = input.lastAssistantMessage();
        if (lastAssistantMessage == null || lastAssistantMessage.isBlank()) {
            return StopHookResult.block(FEEDBACK);
        }

        String normalized = lastAssistantMessage.toLowerCase(Locale.ROOT);
        if (VALIDATION_KEYWORDS.matcher(normalized).find()) {
            return StopHookResult.allow();
        }
        return StopHookResult.block(FEEDBACK);
    }
}
