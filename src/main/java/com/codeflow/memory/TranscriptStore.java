package com.codeflow.memory;

import com.codeflow.core.Message;
import com.codeflow.core.WorkingMemory;
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

    private static final String CONFIG_DIR_NAME = ".codeflow";
    private static final String MEMORY_DIR_NAME = "memory";
    private static final String L5_DIR = "L5";
    private static final String HOOKS_DIR = ".hooks";
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
    public List<String> append(String sessionId, List<Message> messages) throws IOException {
        validateSessionId(sessionId);
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        Files.createDirectories(transcriptRoot);
        Path path = sessionPath(sessionId);
        String parentUuid = lastUuid(path);
        String cwd = Paths.get("").toAbsolutePath().normalize().toString();
        StringBuilder lines = new StringBuilder();
        List<String> uuids = new ArrayList<>();

        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            String uuid = UUID.randomUUID().toString();
            uuids.add(uuid);
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
        return List.copyOf(uuids);
    }

    /**
     * 追加写入 compact 边界元数据。
     */
    public String appendCompactBoundary(String sessionId, TranscriptRecord.CompactBoundary boundary) throws IOException {
        validateSessionId(sessionId);
        if (boundary == null) {
            throw new IllegalArgumentException("boundary must not be null");
        }

        Files.createDirectories(transcriptRoot);
        Path path = sessionPath(sessionId);
        String uuid = UUID.randomUUID().toString();
        TranscriptRecord record = new TranscriptRecord(
                uuid,
                lastUuid(path),
                sessionId,
                OffsetDateTime.now().toString(),
                Paths.get("").toAbsolutePath().normalize().toString(),
                "compact_boundary",
                null,
                boundary,
                null
        );
        Files.writeString(
                path,
                objectMapper.writeValueAsString(toJson(record)) + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
        return uuid;
    }

    /**
     * 追加写入 snip compact 元数据。
     */
    public String appendSnipCompact(String sessionId, TranscriptRecord.SnipCompact snip) throws IOException {
        validateSessionId(sessionId);
        if (snip == null) {
            throw new IllegalArgumentException("snip must not be null");
        }

        Files.createDirectories(transcriptRoot);
        Path path = sessionPath(sessionId);
        String uuid = UUID.randomUUID().toString();
        TranscriptRecord record = new TranscriptRecord(
                uuid,
                lastUuid(path),
                sessionId,
                OffsetDateTime.now().toString(),
                Paths.get("").toAbsolutePath().normalize().toString(),
                "snip_compact",
                null,
                null,
                snip
        );
        Files.writeString(
                path,
                objectMapper.writeValueAsString(toJson(record)) + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
        return uuid;
    }

    public List<Message> loadMessages(String sessionId) throws IOException {
        return loadEntries(sessionId).stream()
                .map(TranscriptEntry::message)
                .toList();
    }

    /**
     * 从最后一条记录沿 parentUuid 恢复会话链。
     */
    public List<WorkingMemory.Entry> loadWorkingMemoryEntriesForResume(String sessionId) throws IOException {
        List<TranscriptRecord> records = loadRecords(sessionId);
        if (records.isEmpty()) {
            return List.of();
        }

        Map<String, TranscriptRecord> byUuid = new HashMap<>();
        for (TranscriptRecord record : records) {
            byUuid.put(record.uuid(), record);
        }

        List<TranscriptRecord> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        TranscriptRecord current = records.getLast();
        while (current != null && visited.add(current.uuid())) {
            chain.add(current);
            String parentUuid = current.parentUuid();
            current = parentUuid == null ? null : byUuid.get(parentUuid);
        }

        Collections.reverse(chain);
        // preserved 引用的原文及其 snip 都在 boundary 之前，按整链建立 snip 索引。
        Map<String, TranscriptRecord.SnipCompact> snipsByTarget = snipsByTarget(chain, 0);

        List<WorkingMemory.Entry> entries = new ArrayList<>();
        int boundaryIndex = lastCompactBoundaryIndex(chain);
        int startIndex = 0;
        if (boundaryIndex >= 0) {
            TranscriptRecord boundaryRecord = chain.get(boundaryIndex);
            TranscriptRecord.CompactBoundary boundary = boundaryRecord.compactBoundary();
            // 1. 内联摘要
            entries.add(new WorkingMemory.Entry(new Message.User(boundary.summary()), boundaryRecord.uuid()));
            // 2. 引用的 preserved 原文（按 uuid 查，应用 snip）
            for (String uuid : boundary.preservedUuids()) {
                TranscriptRecord preserved = byUuid.get(uuid);
                if (preserved != null && preserved.isMessage()) {
                    entries.add(new WorkingMemory.Entry(applySnip(preserved, snipsByTarget.get(uuid)), uuid));
                }
            }
            startIndex = boundaryIndex + 1;
        }
        // 3. boundary 之后压缩产生的新消息
        for (int i = startIndex; i < chain.size(); i++) {
            TranscriptRecord record = chain.get(i);
            if (record.isMessage()) {
                entries.add(new WorkingMemory.Entry(applySnip(record, snipsByTarget.get(record.uuid())), record.uuid()));
            }
        }
        return List.copyOf(entries);
    }

    private Map<String, TranscriptRecord.SnipCompact> snipsByTarget(List<TranscriptRecord> records, int startIndex) {
        Map<String, TranscriptRecord.SnipCompact> snips = new HashMap<>();
        for (int i = startIndex; i < records.size(); i++) {
            TranscriptRecord record = records.get(i);
            if (record.isSnipCompact()) {
                snips.put(record.snipCompact().targetUuid(), record.snipCompact());
            }
        }
        return snips;
    }

    private Message applySnip(TranscriptRecord record, TranscriptRecord.SnipCompact snip) {
        if (snip == null || !(record.message() instanceof Message.ToolResult toolResult)) {
            return record.message();
        }
        if (!toolResult.toolUseId().equals(snip.toolUseId())) {
            return record.message();
        }
        return new Message.ToolResult(toolResult.toolUseId(), snip.summary(), toolResult.isError());
    }

    private int lastCompactBoundaryIndex(List<TranscriptRecord> records) {
        int index = -1;
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).isCompactBoundary()) {
                index = i;
            }
        }
        return index;
    }

    /**
     * 读取 jsonl 中的有效记录。
     */
    public List<TranscriptEntry> loadEntries(String sessionId) throws IOException {
        return loadRecords(sessionId).stream()
                .filter(TranscriptRecord::isMessage)
                .map(record -> new TranscriptEntry(
                        record.uuid(),
                        record.parentUuid(),
                        record.sessionId(),
                        record.timestamp(),
                        record.cwd(),
                        record.message()
                ))
                .toList();
    }

    /**
     * 读取 jsonl 中的有效通用记录。
     */
    public List<TranscriptRecord> loadRecords(String sessionId) throws IOException {
        validateSessionId(sessionId);
        Path path = sessionPath(sessionId);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("会话记录不存在: " + sessionId);
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("会话记录不是文件: " + sessionId);
        }

        List<TranscriptRecord> records = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                records.add(parseRecord(objectMapper.readTree(line)));
            } catch (Exception ignored) {
                // 跳过损坏行，避免整个会话不可恢复。
            }
        }
        return List.copyOf(records);
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

    public Path writeHookSnapshot(String sessionId, String hookEventName, List<Message> messages) throws IOException {
        validateSessionId(sessionId);
        String safeHookEventName = safeHookEventName(hookEventName);
        Path hookDirectory = transcriptRoot.resolve(HOOKS_DIR);
        Files.createDirectories(hookDirectory);

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Path path = hookDirectory
                .resolve(sessionId + "-" + safeHookEventName + "-" + suffix + JSONL_EXTENSION)
                .normalize();

        String cwd = Paths.get("").toAbsolutePath().normalize().toString();
        StringBuilder lines = new StringBuilder();
        String parentUuid = null;
        if (messages != null) {
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
        }

        Files.writeString(path, lines.toString(), StandardOpenOption.CREATE_NEW);
        return path;
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
        node.put("type", messageType(entry.message()));
        node.set("message", anthropicMessageToJson(entry.message()));
        return node;
    }

    private ObjectNode toJson(TranscriptRecord record) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("uuid", record.uuid());
        if (record.parentUuid() == null) {
            node.putNull("parentUuid");
        } else {
            node.put("parentUuid", record.parentUuid());
        }
        node.put("sessionId", record.sessionId());
        node.put("timestamp", record.timestamp());
        node.put("cwd", record.cwd());
        node.put("type", record.type());
        if (record.isMessage()) {
            node.set("message", anthropicMessageToJson(record.message()));
        } else if (record.isCompactBoundary()) {
            node.set("compact", compactBoundaryToJson(record.compactBoundary()));
        } else if (record.isSnipCompact()) {
            node.set("snip", snipCompactToJson(record.snipCompact()));
        }
        return node;
    }

    private ObjectNode compactBoundaryToJson(TranscriptRecord.CompactBoundary boundary) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("mode", boundary.mode());
        node.put("reason", boundary.reason());
        node.put("estimated_tokens_before", boundary.estimatedTokensBefore());
        node.put("hot_message_count", boundary.hotMessageCount());
        node.put("retry_count", boundary.retryCount());
        node.put("summary", boundary.summary());
        ArrayNode preserved = objectMapper.createArrayNode();
        if (boundary.preservedUuids() != null) {
            for (String uuid : boundary.preservedUuids()) {
                preserved.add(uuid);
            }
        }
        node.set("preserved_uuids", preserved);
        node.put("transcript_path", boundary.transcriptPath());
        return node;
    }

    private ObjectNode snipCompactToJson(TranscriptRecord.SnipCompact snip) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("targetUuid", snip.targetUuid());
        node.put("tool_use_id", snip.toolUseId());
        node.put("strategy", snip.strategy());
        node.put("threshold_chars", snip.thresholdChars());
        node.put("original_chars", snip.originalChars());
        node.put("summary_chars", snip.summaryChars());
        node.put("tokens_freed", snip.tokensFreed());
        node.put("summary", snip.summary());
        return node;
    }

    private String messageType(Message message) {
        return switch (message) {
            case Message.User ignored -> "user";
            case Message.Assistant ignored -> "assistant";
            case Message.ToolResult ignored -> "user";
        };
    }

    private ObjectNode anthropicMessageToJson(Message message) {
        ObjectNode node = objectMapper.createObjectNode();
        switch (message) {
            case Message.User user -> {
                node.put("role", "user");
                node.put("content", user.content());
            }
            case Message.Assistant assistant -> {
                node.put("role", "assistant");
                node.set("content", assistantContentToJson(assistant));
                if (assistant.usage() != null) {
                    node.set("usage", usageToJson(assistant.usage()));
                }
            }
            case Message.ToolResult toolResult -> {
                node.put("role", "user");
                ArrayNode content = objectMapper.createArrayNode();
                ObjectNode toolResultNode = objectMapper.createObjectNode();
                toolResultNode.put("type", "tool_result");
                toolResultNode.put("tool_use_id", toolResult.toolUseId());
                toolResultNode.put("content", toolResult.content());
                toolResultNode.put("is_error", toolResult.isError());
                content.add(toolResultNode);
                node.set("content", content);
            }
        }
        return node;
    }

    private ArrayNode assistantContentToJson(Message.Assistant assistant) {
        ArrayNode content = objectMapper.createArrayNode();
        if (assistant.content() != null && !assistant.content().isBlank()) {
            ObjectNode textNode = objectMapper.createObjectNode();
            textNode.put("type", "text");
            textNode.put("text", assistant.content());
            content.add(textNode);
        }
        if (assistant.toolUses() != null) {
            for (Message.ToolUse toolUse : assistant.toolUses()) {
                ObjectNode toolUseNode = objectMapper.createObjectNode();
                toolUseNode.put("type", "tool_use");
                toolUseNode.put("id", toolUse.id());
                toolUseNode.put("name", toolUse.name());
                toolUseNode.put("input", toolUse.input());
                content.add(toolUseNode);
            }
        }
        return content;
    }

    private ObjectNode usageToJson(Message.Usage usage) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("input_tokens", usage.inputTokens());
        node.put("output_tokens", usage.outputTokens());
        node.put("cache_creation_input_tokens", usage.cacheCreationInputTokens());
        node.put("cache_read_input_tokens", usage.cacheReadInputTokens());
        return node;
    }

    /**
     * 解析一行 jsonl 记录。
     */
    private TranscriptEntry parseEntry(JsonNode node) {
        TranscriptRecord record = parseRecord(node);
        if (!record.isMessage()) {
            throw new IllegalArgumentException("transcript entry 不是消息记录");
        }
        return new TranscriptEntry(
                record.uuid(),
                record.parentUuid(),
                record.sessionId(),
                record.timestamp(),
                record.cwd(),
                record.message()
        );
    }

    private TranscriptRecord parseRecord(JsonNode node) {
        String type = requiredText(node, "type");
        Message message = isMessageType(type) ? parseMessage(type, node.get("message")) : null;
        TranscriptRecord.CompactBoundary compactBoundary = "compact_boundary".equals(type)
                ? parseCompactBoundary(node.get("compact"))
                : null;
        TranscriptRecord.SnipCompact snipCompact = "snip_compact".equals(type)
                ? parseSnipCompact(node.get("snip"))
                : null;
        if (message == null && compactBoundary == null && snipCompact == null) {
            throw new IllegalArgumentException("未知 transcript record 类型: " + type);
        }
        return new TranscriptRecord(
                requiredText(node, "uuid"),
                optionalText(node, "parentUuid"),
                requiredText(node, "sessionId"),
                requiredText(node, "timestamp"),
                requiredText(node, "cwd"),
                type,
                message,
                compactBoundary,
                snipCompact
        );
    }

    private boolean isMessageType(String type) {
        return "user".equals(type) || "assistant".equals(type);
    }

    private TranscriptRecord.CompactBoundary parseCompactBoundary(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("compact boundary 必须是对象");
        }
        return new TranscriptRecord.CompactBoundary(
                requiredText(node, "mode"),
                requiredText(node, "reason"),
                optionalLong(node, "estimated_tokens_before"),
                (int) optionalLong(node, "hot_message_count"),
                (int) optionalLong(node, "retry_count"),
                requiredText(node, "summary"),
                parseStringArray(node, "preserved_uuids"),
                requiredText(node, "transcript_path")
        );
    }

    private static List<String> parseStringArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("transcript 字段必须是数组: " + field);
        }
        List<String> result = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isTextual()) {
                throw new IllegalArgumentException("transcript 数组元素必须是字符串: " + field);
            }
            result.add(element.asText());
        }
        return List.copyOf(result);
    }

    private TranscriptRecord.SnipCompact parseSnipCompact(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("snip compact 必须是对象");
        }
        return new TranscriptRecord.SnipCompact(
                requiredText(node, "targetUuid"),
                requiredText(node, "tool_use_id"),
                requiredText(node, "strategy"),
                (int) optionalLong(node, "threshold_chars"),
                (int) optionalLong(node, "original_chars"),
                (int) optionalLong(node, "summary_chars"),
                optionalLong(node, "tokens_freed"),
                requiredText(node, "summary")
        );
    }

    /**
     * 还原消息模型。
     */
    private Message parseMessage(String type, JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("transcript message 必须是对象");
        }

        return switch (type) {
            case "user" -> parseUserMessage(node);
            case "assistant" -> parseAssistantMessage(node);
            default -> throw new IllegalArgumentException("未知 transcript message 类型: " + type);
        };
    }

    private Message parseUserMessage(JsonNode node) {
        JsonNode content = node.get("content");
        if (content != null && content.isArray() && !content.isEmpty()) {
            JsonNode firstBlock = content.get(0);
            if ("tool_result".equals(optionalText(firstBlock, "type"))) {
                return parseToolResultMessage(node);
            }
        }
        return new Message.User(requiredText(node, "content"));
    }

    private Message.ToolResult parseToolResultMessage(JsonNode node) {
        JsonNode block = firstContentBlock(node, "tool_result");
        return new Message.ToolResult(
                requiredText(block, "tool_use_id"),
                requiredText(block, "content"),
                requiredBoolean(block, "is_error")
        );
    }

    private Message.Assistant parseAssistantMessage(JsonNode node) {
        JsonNode content = node.get("content");
        if (content == null || !content.isArray()) {
            throw new IllegalArgumentException("assistant content 必须是数组");
        }

        StringBuilder text = new StringBuilder();
        List<Message.ToolUse> toolUses = new ArrayList<>();
        for (JsonNode block : content) {
            String blockType = requiredText(block, "type");
            if ("text".equals(blockType)) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(requiredText(block, "text"));
            } else if ("tool_use".equals(blockType)) {
                toolUses.add(new Message.ToolUse(
                        requiredText(block, "id"),
                        requiredText(block, "name"),
                        requiredText(block, "input")
                ));
            }
        }
        return new Message.Assistant(text.toString(), List.copyOf(toolUses), parseUsage(node));
    }

    private Message.Usage parseUsage(JsonNode node) {
        JsonNode usage = node.get("usage");
        if (usage == null || usage.isNull()) {
            return null;
        }
        if (!usage.isObject()) {
            throw new IllegalArgumentException("assistant usage 必须是对象");
        }
        return new Message.Usage(
                optionalLong(usage, "input_tokens"),
                optionalLong(usage, "output_tokens"),
                optionalLong(usage, "cache_creation_input_tokens"),
                optionalLong(usage, "cache_read_input_tokens")
        );
    }

    private JsonNode firstContentBlock(JsonNode node, String expectedType) {
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        JsonNode content = node.get("content");
        if (content == null || !content.isArray() || content.isEmpty()) {
            throw new IllegalArgumentException("message content 必须是非空数组");
        }

        JsonNode block = content.get(0);
        String actualType = requiredText(block, "type");
        if (!expectedType.equals(actualType)) {
            throw new IllegalArgumentException("message content 类型错误: " + actualType);
        }
        return block;
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

    private String safeHookEventName(String hookEventName) {
        if (hookEventName == null || hookEventName.isBlank()) {
            return "Hook";
        }
        return hookEventName.replaceAll("[^A-Za-z0-9_.-]", "_");
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

    private static long optionalLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return 0;
        }
        if (!value.isNumber()) {
            throw new IllegalArgumentException("transcript 字段必须是数字: " + field);
        }
        return value.asLong();
    }
}
