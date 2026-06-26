package com.codeflow.agents;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 后台 agent 完成通知队列；不承载用户输入。
 */
public final class AgentNotificationQueue {
    private final ArrayDeque<AgentNotification> notifications = new ArrayDeque<>();
    private final Set<String> enqueuedAgentIds = new HashSet<>();

    public synchronized void enqueue(AgentNotification notification) {
        if (notification == null) {
            throw new IllegalArgumentException("notification must not be null");
        }
        if (!enqueuedAgentIds.add(notification.agentId())) {
            return;
        }
        notifications.addLast(notification);
    }

    public synchronized List<AgentNotification> drainReady() {
        List<AgentNotification> drained = new ArrayList<>(notifications);
        notifications.clear();
        return List.copyOf(drained);
    }

    public synchronized boolean hasPending() {
        return !notifications.isEmpty();
    }
}
