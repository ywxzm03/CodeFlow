package com.codewarp.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class MemoryStore {

    private static final String CONFIG_DIR_NAME = ".codewrap";
    private static final String MEMORY_DIR_NAME = "memory";
    private static final String L0_DIR = "L0";
    private static final String L1_DIR = "L1";
    private static final String L2_DIR = "L2";
    private static final String L3_DIR = "L3";
    private static final String RULES_FILE = "memory_rules.md";
    private static final String INDEX_FILE = "index.txt";
    private static final String SAFE_FILE_NAME_PATTERN = "[A-Za-z0-9_.-]+";

    private final Path memoryRoot;

    public MemoryStore() {
        this(defaultMemoryRoot());
    }

    public MemoryStore(Path memoryRoot) {
        this.memoryRoot = memoryRoot;
    }

    public Path memoryRoot() {
        return memoryRoot;
    }

    public void initialize() throws IOException {
        Files.createDirectories(memoryRoot.resolve(L0_DIR));
        Files.createDirectories(memoryRoot.resolve(L1_DIR));
        Files.createDirectories(memoryRoot.resolve(L2_DIR));
        Files.createDirectories(memoryRoot.resolve(L3_DIR));

        Path rulesFile = rulesPath();
        if (!Files.exists(rulesFile)) {
            Files.writeString(rulesFile, defaultRules());
        }

        Path indexFile = indexPath();
        if (!Files.exists(indexFile)) {
            Files.writeString(indexFile, defaultIndex());
        }
    }

    public String readRules() throws IOException {
        return Files.readString(rulesPath());
    }

    public String readIndex() throws IOException {
        return Files.readString(indexPath());
    }

    public String readMemory(String relativePath) throws IOException {
        Path path = resolveReadableMemoryPath(relativePath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("记忆文件不存在: " + relativePath);
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("记忆路径不是文件: " + relativePath);
        }
        return Files.readString(path);
    }

    public void validateReadableMemoryPath(String relativePath) {
        resolveReadableMemoryPath(relativePath);
    }

    public void applyUpdate(MemoryUpdate update) throws IOException {
        if (update == null) {
            throw new IllegalArgumentException("记忆更新不能为空");
        }

        Path target = resolveWritableMemoryPath(update.layer(), update.fileName());
        String content = requireText(update.content(), "记忆内容不能为空");
        Files.createDirectories(target.getParent());
        Files.writeString(
                target,
                ensureTrailingNewline(content),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );

        if (update.indexEntry() != null && !update.indexEntry().isBlank()) {
            appendIndexEntry(update.indexEntry());
        }
    }

    public void appendIndexEntry(String indexEntry) throws IOException {
        String normalized = requireText(indexEntry, "索引内容不能为空").strip();
        Files.writeString(
                indexPath(),
                normalized + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    private Path resolveReadableMemoryPath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("记忆路径不能为空");
        }

        Path relative = Paths.get(relativePath);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("记忆路径必须是相对路径");
        }
        if (containsTraversal(relative)) {
            throw new IllegalArgumentException("记忆路径不能包含 ..");
        }

        Path normalized = relative.normalize();
        if (normalized.getNameCount() != 2) {
            throw new IllegalArgumentException("记忆路径必须形如 L2/file.txt 或 L3/file.md");
        }

        String layerName = normalized.getName(0).toString();
        String fileName = normalized.getName(1).toString();
        MemoryLayer layer = MemoryLayer.fromDirectoryName(layerName);
        validateFileName(layer, fileName);
        return memoryRoot.resolve(layer.directoryName()).resolve(fileName).normalize();
    }

    private Path resolveWritableMemoryPath(MemoryLayer layer, String fileName) {
        if (layer == null) {
            throw new IllegalArgumentException("记忆层级不能为空");
        }
        validateFileName(layer, fileName);
        return memoryRoot.resolve(layer.directoryName()).resolve(fileName).normalize();
    }

    private static void validateFileName(MemoryLayer layer, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("记忆文件名不能为空");
        }
        if (!fileName.matches(SAFE_FILE_NAME_PATTERN) || ".".equals(fileName) || "..".equals(fileName)) {
            throw new IllegalArgumentException("记忆文件名只能包含字母、数字、下划线、短横线和点: " + fileName);
        }
        if (!fileName.endsWith(layer.fileExtension())) {
            throw new IllegalArgumentException(layer.directoryName() + " 记忆文件必须以 " + layer.fileExtension() + " 结尾");
        }
    }

    private static boolean containsTraversal(Path path) {
        for (Path segment : path) {
            if ("..".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private Path rulesPath() {
        return memoryRoot.resolve(L0_DIR).resolve(RULES_FILE);
    }

    private Path indexPath() {
        return memoryRoot.resolve(L1_DIR).resolve(INDEX_FILE);
    }

    private static Path defaultMemoryRoot() {
        return Paths.get(System.getProperty("user.home"), CONFIG_DIR_NAME, MEMORY_DIR_NAME);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String ensureTrailingNewline(String value) {
        return value.endsWith(System.lineSeparator()) || value.endsWith("\n")
                ? value
                : value + System.lineSeparator();
    }

    private static String defaultRules() {
        return """
                # CodeWarp Memory Rules

                L0 is the manual rule layer. Do not update this file automatically.

                Write to L2 only when the information is long-lived factual context, such as user preferences, project facts, stable environment details, or durable constraints.

                Write to L3 only when the information is a verified lesson from a pitfall, repeated failure, non-obvious constraint, or reusable task experience.

                Do not write temporary task state, guesses, generic knowledge, one-off command output, or details that can be easily re-read from project files.

                Every memory update must be confirmed by the user, even when permission mode is Full Access.
                """;
    }

    private static String defaultIndex() {
        return """
                # CodeWarp Memory Index

                L1 only lists which L2/L3 files exist and when they are useful.
                It must not contain full memory content.
                Use MemoryRead with paths like L2/user_preferences.txt or L3/some_pitfall.md when details are needed.
                """;
    }
}
