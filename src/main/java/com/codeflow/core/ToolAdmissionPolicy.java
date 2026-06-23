package com.codeflow.core;

public interface ToolAdmissionPolicy {

    ToolAdmissionResult authorize(Message.ToolUse toolUse);

    record ToolAdmissionResult(boolean allowed, String errorMessage) {
        public ToolAdmissionResult {
            errorMessage = errorMessage == null ? "" : errorMessage;
        }

        public static ToolAdmissionResult allow() {
            return new ToolAdmissionResult(true, "");
        }

        public static ToolAdmissionResult deny(String errorMessage) {
            return new ToolAdmissionResult(false, errorMessage);
        }
    }
}
