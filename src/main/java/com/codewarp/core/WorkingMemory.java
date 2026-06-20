package com.codewarp.core;

import java.util.ArrayList;
import java.util.List;

/**
 * L4 工作记忆，仅保存在内存中。
 */
public final class WorkingMemory {

    private final List<Message> messages = new ArrayList<>();
    private final List<String> transcriptUuids = new ArrayList<>();

    public synchronized int size() {
        return messages.size();
    }

    public synchronized void append(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        messages.add(message);
        transcriptUuids.add(null);
    }

    public synchronized List<Entry> snapshotEntries() {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            entries.add(new Entry(messages.get(i), transcriptUuids.get(i)));
        }
        return List.copyOf(entries);
    }

    public synchronized List<Message> snapshot() {
        return List.copyOf(messages);
    }

    public synchronized void replace(int index, Message message) {
        validateExistingIndex(index);
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        messages.set(index, message);
    }

    public synchronized String transcriptUuidAt(int index) {
        validateExistingIndex(index);
        return transcriptUuids.get(index);
    }

    public synchronized void markTranscriptUuid(int index, String uuid) {
        validateExistingIndex(index);
        if (uuid == null || uuid.isBlank()) {
            throw new IllegalArgumentException("uuid must not be blank");
        }
        transcriptUuids.set(index, uuid);
    }

    public synchronized List<Message> sliceFrom(int index) {
        validateIndex(index);
        return List.copyOf(messages.subList(index, messages.size()));
    }

    public synchronized void rollbackTo(int index) {
        validateIndex(index);
        while (messages.size() > index) {
            messages.removeLast();
            transcriptUuids.removeLast();
        }
    }

    public synchronized void restore(List<Entry> entries) {
        messages.clear();
        transcriptUuids.clear();
        if (entries == null) {
            return;
        }
        for (Entry entry : entries) {
            if (entry == null || entry.message() == null) {
                throw new IllegalArgumentException("entry message must not be null");
            }
            messages.add(entry.message());
            transcriptUuids.add(entry.transcriptUuid());
        }
    }

    public synchronized void clear() {
        messages.clear();
        transcriptUuids.clear();
    }

    private void validateIndex(int index) {
        if (index < 0 || index > messages.size()) {
            throw new IllegalArgumentException("index out of bounds: " + index);
        }
    }

    private void validateExistingIndex(int index) {
        if (index < 0 || index >= messages.size()) {
            throw new IllegalArgumentException("index out of bounds: " + index);
        }
    }

    public record Entry(Message message, String transcriptUuid) {
    }
}
