package com.codeflow.hooks;

public interface PreToolUseHandler {

    PreToolUseResult handle(PreToolUseInput input);

    static PreToolUseHandler none() {
        return input -> PreToolUseResult.none();
    }
}
