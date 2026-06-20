package com.codewarp.memory;

import com.codewarp.core.Message;
import com.codewarp.core.WorkingMemory;
import com.codewarp.util.Console;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 当前会话的 L5 记录器。
 */
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

    public List<String> recordWithUuids(List<Message> messages) {
        if (!enabled() || messages == null || messages.isEmpty()) {
            return List.of();
        }

        try {
            return transcriptStore.append(sessionId, messages);
        } catch (IOException | IllegalArgumentException e) {
            Console.warn("[Memory] 写入 transcript 失败，已跳过: " + e.getMessage());
            return List.of();
        }
    }

    public void recordUnpersisted(WorkingMemory workingMemory) {
        if (!enabled() || workingMemory == null) {
            return;
        }

        List<WorkingMemory.Entry> entries = workingMemory.snapshotEntries();
        List<Message> batch = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            WorkingMemory.Entry entry = entries.get(i);
            if (entry.transcriptUuid() == null || entry.transcriptUuid().isBlank()) {
                batch.add(entry.message());
                indexes.add(i);
            }
        }
        if (batch.isEmpty()) {
            return;
        }

        List<String> uuids = recordWithUuids(batch);
        for (int i = 0; i < uuids.size() && i < indexes.size(); i++) {
            workingMemory.markTranscriptUuid(indexes.get(i), uuids.get(i));
        }
    }

    public void switchSession(String sessionId) {
        if (transcriptStore != null) {
            transcriptStore.validateSessionId(sessionId);
        }
        this.sessionId = sessionId;
    }
}
