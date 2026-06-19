package com.codewarp.memory;

import com.codewarp.core.Message;
import com.codewarp.util.Console;

import java.io.IOException;
import java.util.List;

public final class TranscriptRecorder {

    private final TranscriptStore transcriptStore;
    private volatile String sessionId;

    public TranscriptRecorder(TranscriptStore transcriptStore) {
        this(transcriptStore, transcriptStore == null ? null : transcriptStore.newSessionId());
    }

    public TranscriptRecorder(TranscriptStore transcriptStore, String sessionId) {
        this.transcriptStore = transcriptStore;
        this.sessionId = sessionId;
    }

    public static TranscriptRecorder disabled() {
        return new TranscriptRecorder(null, null);
    }

    public boolean enabled() {
        return transcriptStore != null && sessionId != null && !sessionId.isBlank();
    }

    public String sessionId() {
        return sessionId;
    }

    public String transcriptPath() {
        if (!enabled()) {
            return "";
        }
        return transcriptStore.transcriptPath(sessionId).toString();
    }

    public void record(List<Message> messages) {
        if (!enabled() || messages == null || messages.isEmpty()) {
            return;
        }

        try {
            transcriptStore.append(sessionId, messages);
        } catch (IOException | IllegalArgumentException e) {
            Console.warn("[Memory] 写入 transcript 失败，已跳过: " + e.getMessage());
        }
    }

    public void switchSession(String sessionId) {
        if (transcriptStore != null) {
            transcriptStore.validateSessionId(sessionId);
        }
        this.sessionId = sessionId;
    }
}
