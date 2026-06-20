package com.codewarp.memory;

import com.codewarp.core.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * L5 完整会话记录的文件存储。
 */
public final class TranscriptStore {

    private static final String CONFIG_DIR_NAME = ".codewrap";
    private static final String MEMORY_DIR_NAME = "memory";
    private static final String L5_DIR = "L5";
    private static final String JSONL_EXTENSION = ".jsonl";
    private static final String SAFE_SESSION_ID_PATTERN = "[A-Za-z0-9_.-]+";

    private final Path transcriptRoot;
    private final ObjectMapper objectMapper;

    public TranscriptStore() {
        this(defaultTranscriptRoot());
    }

    public TranscriptStore(Path transcriptRoot) {
        this.transcriptRoot = transcriptRoot;
        this.objectMapper = new ObjectMapper();
    }

    public Path transcriptRoot() {
        return transcriptRoot;
    }

    public void initialize() throws IOException {
        Files.createDirectories(transcriptRoot);
    }

    public String newSessionId() {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now());
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return timestamp + "-" + suffix;
    }

    /**
     * 追加写入本轮消息。
     */
    public void append(String sessionId, List<Message> messages) throws IOException {
        validateSessionId(sessionId);
        if (messages == null || messages.isEmpty()) {
            return;
        }

        Files.createDirectories(transcriptRoot);
        Path path = sessionPath(sessionId);
        String parentUuid = lastUuid(path);
        String cwd = Paths.get("").toAbsolutePath().normalize().toString();
        StringBuilder lines = new StringBuilder();

        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            String uuid = UUID.randomUUID().toString();
            TranscriptEntry entry = new TranscriptEntry(
                    uuid,
                    parentUuid,
                    sessionId,
                    OffsetDateTime.now().toString(),
                    cwd,
                    message
            );
            lines.append(objectMapper.writeValueAsString(toJson(entry))).append(System.lineSeparator());
            parentUuid = uuid;
        }

        if (!lines.isEmpty()) {
            Files.writeString(path, lines.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    public List<Message> loadMessages(String sessionId) throws IOException {
        return loadEntries(sessionId).stream()
                .map(TranscriptEntry::message)
                .toList();
    }

    /**
     * 从最后一条记录沿 parentUuid 恢复会话链。
     */
    public List<Message> loadMessagesForResume(String sessionId) throws IOException {
        List<TranscriptEntry> entries = loadEntries(sessionId);
        if (entries.isEmpty()) {
            return List.of();
        }

        Map<String, TranscriptEntry> byUuid = new HashMap<>();
        for (TranscriptEntry entry : entries) {
            byUuid.put(entry.uuid(), entry);
        }

        List<Message> messages = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        TranscriptEntry current = entries.getLast();
        while (current != null && visited.add(current.uuid())) {
            messages.add(current.message());
            String parentUuid = current.parentUuid();
            current = parentUuid == null ? null : byUuid.get(parentUuid);
        }

        Collections.reverse(messages);
        return List.copyOf(messages);
    }

    /**
     * 读取 jsonl 中的有效记录。
     */
    public List<TranscriptEntry> loadEntries(String sessionId) throws IOException {
        validateSessionId(sessionId);
        Path path = sessionPath(sessionId);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("会话记录不存在: " + sessionId);
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("会话记录不是文件: " + sessionId);
        }

        List<TranscriptEntry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                entries.add(parseEntry(objectMapper.readTree(line)));
            } catch (Exception ignored) {
                // 跳过损坏行，避免整个会话不可恢复。
            }
        }
        return List.copyOf(entries);
    }

    /**
     * 列出可恢复会话。
     */
    public List<TranscriptSession> listSessions() throws IOException {
        if (!Files.isDirectory(transcriptRoot)) {
            return List.of();
        }

        try (Stream<Path> paths = Files.list(transcriptRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(JSONL_EXTENSION))
                    .sorted(Comparator.comparing(this::lastModified).reversed())
                    .map(this::toSession)
                    .toList();
        }
    }

    /**
     * 校验 sessionId，避免路径穿越。
     */
    public void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (!sessionId.matches(SAFE_SESSION_ID_PATTERN) || ".".equals(sessionId) || "..".equals(sessionId)) {
            throw new IllegalArgumentException("sessionId 只能包含字母、数字、下划线、短横线和点: " + sessionId);
        }
    }

    public Path transcriptPath(String sessionId) {
        validateSessionId(sessionId);
        return sessionPath(sessionId);
    }

    /**
     * 转为 jsonl 写入结构。
     */
    private ObjectNode toJson(TranscriptEntry entry) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("uuid", entry.uuid());
        if (entry.parentUuid() == null) {
            node.putNull("parentUuid");
        } else {
            node.put("parentUuid", entry.parentUuid());
        }
        node.put("sessionId", entry.sessionId());
        node.put("timestamp", entry.timestamp());
        node.put("cwd", entry.cwd());
        node.set("message", messageToJson(entry.message()));
        return node;
    }

    private ObjectNode messageToJson(Message message) {
        ObjectNode node = objectMapper.createObjectNode();
        switch (message) {
            case Message.User user -> {
                node.put("type", "user");
                node.put("content", user.content());
            }
            case Message.Assistant assistant -> {
                node.put("type", "assistant");
                node.put("content", assistant.content());
                ArrayNode toolUses = objectMapper.createArrayNode();
                if (assistant.toolUses() != null) {
                    for (Message.ToolUse toolUse : assistant.toolUses()) {
                        ObjectNode toolUseNode = objectMapper.createObjectNode();
                        toolUseNode.put("id", toolUse.id());
                        toolUseNode.put("name", toolUse.name());
                        toolUseNode.put("input", toolUse.input());
                        toolUses.add(toolUseNode);
                    }
                }
                node.set("toolUses", toolUses);
            }
            case Message.ToolResult toolResult -> {
                node.put("type", "tool_result");
                node.put("toolUseId", toolResult.toolUseId());
                node.put("content", toolResult.content());
                node.put("isError", toolResult.isError());
            }
        }
        return node;
    }

    /**
     * 解析一行 jsonl 记录。
     */
    private TranscriptEntry parseEntry(JsonNode node) {
        return new TranscriptEntry(
                requiredText(node, "uuid"),
                optionalText(node, "parentUuid"),
                requiredText(node, "sessionId"),
                requiredText(node, "timestamp"),
                requiredText(node, "cwd"),
                parseMessage(node.get("message"))
        );
    }

    /**
     * 还原消息模型。
     */
    private Message parseMessage(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("transcript message 必须是对象");
        }

        return switch (requiredText(node, "type")) {
            case "user" -> new Message.User(requiredText(node, "content"));
            case "assistant" -> new Message.Assistant(optionalText(node, "content"), parseToolUses(node.get("toolUses")));
            case "tool_result" -> new Message.ToolResult(
                    requiredText(node, "toolUseId"),
                    requiredText(node, "content"),
                    requiredBoolean(node, "isError")
            );
            default -> throw new IllegalArgumentException("未知 transcript message 类型: " + requiredText(node, "type"));
        };
    }

    private List<Message.ToolUse> parseToolUses(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("toolUses 必须是数组");
        }

        List<Message.ToolUse> toolUses = new ArrayList<>();
        for (JsonNode toolUse : node) {
            toolUses.add(new Message.ToolUse(
                    requiredText(toolUse, "id"),
                    requiredText(toolUse, "name"),
                    requiredText(toolUse, "input")
            ));
        }
        return List.copyOf(toolUses);
    }

    /**
     * 获取最后一条有效记录的 uuid。
     */
    private String lastUuid(Path path) throws IOException {
        if (!Files.exists(path)) {
            return null;
        }

        String lastUuid = null;
        for (String line : Files.readAllLines(path)) {
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                lastUuid = requiredText(objectMapper.readTree(line), "uuid");
            } catch (Exception ignored) {
                // 保留最后一个有效 uuid。
            }
        }
        return lastUuid;
    }

    private TranscriptSession toSession(Path path) {
        String fileName = path.getFileName().toString();
        String sessionId = fileName.substring(0, fileName.length() - JSONL_EXTENSION.length());
        return new TranscriptSession(sessionId, lastModified(path).toString(), countLines(path));
    }

    private java.nio.file.attribute.FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return java.nio.file.attribute.FileTime.fromMillis(0);
        }
    }

    private long countLines(Path path) {
        try (Stream<String> lines = Files.lines(path)) {
            return lines.filter(line -> !line.isBlank()).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private Path sessionPath(String sessionId) {
        return transcriptRoot.resolve(sessionId + JSONL_EXTENSION).normalize();
    }

    private static Path defaultTranscriptRoot() {
        return Paths.get(System.getProperty("user.home"), CONFIG_DIR_NAME, MEMORY_DIR_NAME, L5_DIR);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少 transcript 字段: " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("transcript 字段必须是字符串: " + field);
        }
        return value.asText();
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException("transcript 字段必须是布尔值: " + field);
        }
        return value.asBoolean();
    }
}
