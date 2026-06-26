package com.codeflow.core;

import com.codeflow.agents.AgentNotification;
import com.codeflow.memory.MemoryReflection;
import com.codeflow.memory.TranscriptRecorder;

import java.util.List;
import java.util.Objects;

/**
 * 终端会话协调器，维护 L4 工作记忆。
 * 同时负责触发 L5 记录和 L2/L3 反思。
 */
public final class ConversationSession {

    private final QueryEngine queryEngine;
    private final WorkingMemory workingMemory;
    private final MemoryReflection memoryReflection;
    private final TranscriptRecorder transcriptRecorder;

    public ConversationSession(
            QueryEngine queryEngine,
            MemoryReflection memoryReflection,
            TranscriptRecorder transcriptRecorder
    ) {
        this.queryEngine = Objects.requireNonNull(queryEngine, "queryEngine must not be null");
        this.workingMemory = new WorkingMemory();
        this.memoryReflection = memoryReflection;
        this.transcriptRecorder = Objects.requireNonNull(transcriptRecorder, "transcriptRecorder must not be null");
    }

    public QueryEngine.QueryResult handleUserInput(String input) {
        return handleUserInput(input, CancellationToken.none());
    }

    public QueryEngine.QueryResult handleUserInput(String input, CancellationToken cancellationToken) {
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        int startIndex = workingMemory.size();
        QueryEngine.QueryResult result = queryEngine.query(input, workingMemory, token);
        transcriptRecorder.recordUnpersisted(workingMemory);
        if (result.stopReason() == QueryEngine.QueryResult.StopReason.COMPLETED && memoryReflection != null) {
            if (token == CancellationToken.none()) {
                memoryReflection.reflect(result.turnMessages());
            } else {
                memoryReflection.reflect(result.turnMessages(), token);
            }
        }
        return result;
    }

    public QueryEngine.QueryResult handleAgentNotifications(
            List<AgentNotification> notifications,
            CancellationToken cancellationToken
    ) {
        if (notifications == null || notifications.isEmpty()) {
            throw new IllegalArgumentException("notifications must not be empty");
        }
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        QueryEngine.QueryResult result = queryEngine.query(renderAgentNotificationPrompt(notifications), workingMemory, token);
        transcriptRecorder.recordUnpersisted(workingMemory);
        if (result.stopReason() == QueryEngine.QueryResult.StopReason.COMPLETED && memoryReflection != null) {
            if (token == CancellationToken.none()) {
                memoryReflection.reflect(result.turnMessages());
            } else {
                memoryReflection.reflect(result.turnMessages(), token);
            }
        }
        return result;
    }

    public void resume(String sessionId, List<WorkingMemory.Entry> entries) {
        transcriptRecorder.switchSession(sessionId);
        workingMemory.restore(entries);
    }

    public void clear() {
        workingMemory.clear();
    }

    public QueryEngine.CompactResult compact() {
        return compact(CancellationToken.none());
    }

    public QueryEngine.CompactResult compact(CancellationToken cancellationToken) {
        return queryEngine.compact(workingMemory, cancellationToken);
    }

    public WorkingMemory workingMemory() {
        return workingMemory;
    }

    public String transcriptSessionId() {
        return transcriptRecorder.sessionId();
    }

    private static String renderAgentNotificationPrompt(List<AgentNotification> notifications) {
        StringBuilder builder = new StringBuilder("""
                The following background subagents have completed. The user has not seen these worker results yet.
                Summarize the results concisely for the user.
                Do not relaunch equivalent subagents.
                Do not invent details that are not present in the notifications.
                If a task failed or was cancelled, say so clearly.
                Prioritize VERDICT, TESTS, and commit when present.

                <agent_notifications>
                """);
        for (AgentNotification notification : notifications) {
            builder.append("<agent_notification>\n");
            appendField(builder, "agentId", notification.agentId());
            appendField(builder, "agentType", notification.agentType());
            appendField(builder, "displayName", notification.displayName());
            appendField(builder, "batchId", notification.batchId());
            appendField(builder, "unitId", notification.unitId());
            appendField(builder, "description", notification.description());
            appendField(builder, "status", notification.status().name());
            appendField(builder, "resultSummary", notification.resultSummary());
            appendField(builder, "testSummary", notification.testSummary());
            appendField(builder, "verdict", notification.verdict());
            appendField(builder, "failureReason", notification.failureReason());
            appendField(builder, "worktreePath", notification.worktreePath() == null ? "" : notification.worktreePath().toString());
            appendField(builder, "branchName", notification.branchName());
            appendField(builder, "commitSha", notification.commitSha());
            appendField(builder, "logPath", notification.logPath() == null ? "" : notification.logPath().toString());
            appendField(builder, "completedAt", notification.completedAt() == null ? "" : notification.completedAt().toString());
            builder.append("</agent_notification>\n");
        }
        builder.append("</agent_notifications>");
        return builder.toString();
    }

    private static void appendField(StringBuilder builder, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        builder.append(name).append(": ").append(value).append('\n');
    }
}
