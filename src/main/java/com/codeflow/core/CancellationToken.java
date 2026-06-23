package com.codeflow.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Per-turn cancellation signal shared by terminal, model calls, and tools.
 */
public final class CancellationToken {

    public static final String USER_CANCEL = "user-cancel";

    private static final CancellationToken NONE = new CancellationToken(false);

    private final boolean cancellable;
    private final List<Runnable> callbacks;
    private boolean cancelled;
    private String reason;

    private CancellationToken(boolean cancellable) {
        this.cancellable = cancellable;
        this.callbacks = new ArrayList<>();
    }

    public static CancellationToken none() {
        return NONE;
    }

    public static CancellationToken create() {
        return new CancellationToken(true);
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    public synchronized String reason() {
        return reason;
    }

    public void cancel(String reason) {
        List<Runnable> toRun;
        synchronized (this) {
            if (!cancellable || cancelled) {
                return;
            }
            cancelled = true;
            this.reason = reason == null || reason.isBlank() ? USER_CANCEL : reason;
            toRun = List.copyOf(callbacks);
            callbacks.clear();
            notifyAll();
        }
        for (Runnable callback : toRun) {
            callback.run();
        }
    }

    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new UserCancelledException(reason());
        }
    }

    public void onCancel(Runnable callback) {
        Objects.requireNonNull(callback, "callback must not be null");
        boolean runNow;
        synchronized (this) {
            runNow = cancelled;
            if (!runNow && cancellable) {
                callbacks.add(callback);
            }
        }
        if (runNow) {
            callback.run();
        }
    }
}
