package com.codeflow.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class BackgroundTaskRegistry {
    private final Map<String, BackgroundAgentTask> tasks = new ConcurrentHashMap<>();
    private final Path taskRoot;
    private final ObjectMapper objectMapper;

    public BackgroundTaskRegistry(Path projectRoot) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("projectRoot must not be null");
        }
        this.taskRoot = projectRoot.toAbsolutePath().normalize().resolve(".codeflow").resolve("tasks");
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public BackgroundAgentTask register(String batchId, String agentId, String unitId, String description) {
        return register(batchId, agentId, "Coder", description, unitId, "", description);
    }

    public BackgroundAgentTask register(
            String batchId,
            String agentId,
            String agentType,
            String displayName,
            String unitId,
            String targetAgentId,
            String description
    ) {
        String taskId = agentId;
        Path logPath = agentLogPath(agentId);
        BackgroundAgentTask task = new BackgroundAgentTask(
                taskId,
                batchId,
                agentId,
                agentType,
                displayName,
                unitId,
                targetAgentId,
                description,
                logPath
        );
        tasks.put(taskId, task);
        persist(task);
        return task;
    }

    public Optional<BackgroundAgentTask> find(String taskIdOrAgentId) {
        if (taskIdOrAgentId == null || taskIdOrAgentId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tasks.get(taskIdOrAgentId));
    }

    public List<BackgroundAgentTask.Snapshot> listBatch(String batchId) {
        return tasks.values().stream()
                .map(BackgroundAgentTask::snapshot)
                .filter(task -> task.batchId().equals(batchId))
                .sorted(Comparator.comparing(BackgroundAgentTask.Snapshot::unitId))
                .toList();
    }

    public List<BackgroundAgentTask.Snapshot> listAll() {
        return tasks.values().stream()
                .map(BackgroundAgentTask::snapshot)
                .sorted(Comparator.comparing(BackgroundAgentTask.Snapshot::batchId)
                        .thenComparing(BackgroundAgentTask.Snapshot::unitId))
                .toList();
    }

    public List<BackgroundAgentTask.Snapshot> listRunning() {
        return tasks.values().stream()
                .map(BackgroundAgentTask::snapshot)
                .filter(task -> task.status() == BackgroundAgentTask.Status.QUEUED || task.status() == BackgroundAgentTask.Status.RUNNING)
                .sorted(Comparator.comparing(BackgroundAgentTask.Snapshot::displayName))
                .toList();
    }

    public boolean cancel(String agentId) {
        Optional<BackgroundAgentTask> task = find(agentId);
        task.ifPresent(value -> {
            value.cancel();
            persist(value);
        });
        return task.isPresent();
    }

    public void persist(BackgroundAgentTask task) {
        try {
            Files.createDirectories(agentMetadataDir());
            Files.createDirectories(agentLogDir());
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(agentMetadataPath(task.agentId()).toFile(), task.snapshot());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist background task " + task.agentId(), e);
        }
    }

    public Path appendLog(String agentId, String content) {
        try {
            Files.createDirectories(agentLogDir());
            Path path = agentLogPath(agentId);
            Files.writeString(
                    path,
                    content == null ? "" : content,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND
            );
            return path;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append task log for " + agentId, e);
        }
    }

    private Path agentMetadataDir() {
        return taskRoot.resolve("agents");
    }

    private Path agentLogDir() {
        return taskRoot.resolve("logs");
    }

    private Path agentMetadataPath(String agentId) {
        return agentMetadataDir().resolve(agentId + ".json");
    }

    private Path agentLogPath(String agentId) {
        return agentLogDir().resolve(agentId + ".jsonl");
    }
}
