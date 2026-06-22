package com.codeflow.hooks;

public record StopHookResult(boolean blocked, String feedback) {

    public StopHookResult {
        feedback = feedback == null ? "" : feedback;
    }

    public static StopHookResult allow() {
        return new StopHookResult(false, "");
    }

    public static StopHookResult block(String feedback) {
        return new StopHookResult(true, feedback);
    }
}
