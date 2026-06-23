package com.codeflow.core;

/**
 * Raised when a turn is cancelled by the user via Ctrl+C or Esc.
 */
public final class UserCancelledException extends RuntimeException {

    private final String reason;

    public UserCancelledException(String reason) {
        super("Request cancelled by user");
        this.reason = reason == null || reason.isBlank() ? CancellationToken.USER_CANCEL : reason;
    }

    public String reason() {
        return reason;
    }
}
