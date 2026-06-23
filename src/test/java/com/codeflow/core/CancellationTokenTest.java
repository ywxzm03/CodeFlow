package com.codeflow.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationTokenTest {

    @Test
    void startsNotCancelledAndRunsCallbacksOnce() {
        CancellationToken token = CancellationToken.create();
        AtomicInteger callbacks = new AtomicInteger();

        token.onCancel(callbacks::incrementAndGet);
        token.cancel(CancellationToken.USER_CANCEL);
        token.cancel("second");

        assertTrue(token.isCancelled());
        assertEquals(CancellationToken.USER_CANCEL, token.reason());
        assertEquals(1, callbacks.get());
        assertThrows(UserCancelledException.class, token::throwIfCancelled);
    }

    @Test
    void noneTokenIgnoresCancel() {
        CancellationToken token = CancellationToken.none();

        token.cancel(CancellationToken.USER_CANCEL);

        assertFalse(token.isCancelled());
    }
}
