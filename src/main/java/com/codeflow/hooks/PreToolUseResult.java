package com.codeflow.hooks;

public record PreToolUseResult(HookDecision decision, String reason) {

    public PreToolUseResult {
        decision = decision == null ? HookDecision.NONE : decision;
        reason = reason == null ? "" : reason;
    }

    public static PreToolUseResult allow(String reason) {
        return new PreToolUseResult(HookDecision.ALLOW, reason);
    }

    public static PreToolUseResult ask(String reason) {
        return new PreToolUseResult(HookDecision.ASK, reason);
    }

    public static PreToolUseResult deny(String reason) {
        return new PreToolUseResult(HookDecision.DENY, reason);
    }

    public static PreToolUseResult none() {
        return new PreToolUseResult(HookDecision.NONE, "");
    }
}
