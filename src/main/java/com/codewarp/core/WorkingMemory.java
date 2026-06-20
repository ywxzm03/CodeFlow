package com.codewarp.core;

import java.util.ArrayList;
import java.util.List;

/**
 * L4 工作记忆，仅保存在内存中。
 */
public final class WorkingMemory {

    private final List<Message> messages = new ArrayList<>();

    public synchronized int size() {
        return messages.size();
    }

    public synchronized void append(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        messages.add(message);
    }

    public synchronized List<Message> snapshot() {
        return List.copyOf(messages);
    }

    public synchronized List<Message> sliceFrom(int index) {
        validateIndex(index);
        return List.copyOf(messages.subList(index, messages.size()));
    }

    public synchronized void rollbackTo(int index) {
        validateIndex(index);
        while (messages.size() > index) {
            messages.removeLast();
        }
    }

    public synchronized void clear() {
        messages.clear();
    }

    private void validateIndex(int index) {
        if (index < 0 || index > messages.size()) {
            throw new IllegalArgumentException("index out of bounds: " + index);
        }
    }
}
