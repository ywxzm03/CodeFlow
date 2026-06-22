package com.codeflow.hooks;

public interface StopHookHandler {

    StopHookResult handle(StopHookInput input);

    static StopHookHandler none() {
        return input -> StopHookResult.allow();
    }
}
