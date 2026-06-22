package com.codewarp.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 从用户目录和项目目录加载 skills。
 */
public class SkillStore {

    private static final String CONFIG_DIR_NAME = ".codewarp";
    private static final String SKILLS_DIR_NAME = "skills";
    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String SAFE_SKILL_NAME_PATTERN = "[A-Za-z0-9_.-]+";

    private final Path userSkillsRoot;
    private final Path projectSkillsRoot;

    public SkillStore() {
        this(defaultUserSkillsRoot(), defaultProjectSkillsRoot());
    }

    public SkillStore(Path userSkillsRoot, Path projectSkillsRoot) {
        this.userSkillsRoot = userSkillsRoot;
        this.projectSkillsRoot = projectSkillsRoot;
    }

    public List<SkillDefinition> list() {
        Map<String, SkillDefinition> skills = new LinkedHashMap<>();
        loadDirectory(userSkillsRoot, SkillDefinition.Source.USER).forEach(skill -> skills.put(skill.name(), skill));
        loadDirectory(projectSkillsRoot, SkillDefinition.Source.PROJECT).forEach(skill -> skills.put(skill.name(), skill));
        return skills.values().stream()
                .sorted(Comparator.comparing(SkillDefinition::name))
                .toList();
    }

    public Optional<SkillDefinition> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String normalized = stripLeadingSlash(name.strip());
        return list().stream()
                .filter(skill -> skill.name().equals(normalized))
                .findFirst();
    }

    public String renderIndex() {
        List<SkillDefinition> skills = list();
        if (skills.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Available skills:\n");
        for (SkillDefinition skill : skills) {
            builder.append("- ")
                    .append(skill.name())
                    .append(": ")
                    .append(skill.description());
            if (skill.whenToUse() != null && !skill.whenToUse().isBlank()) {
                builder.append(" when_to_use: ").append(skill.whenToUse());
            }
            if (skill.argumentHint() != null && !skill.argumentHint().isBlank()) {
                builder.append(" args: ").append(skill.argumentHint());
            }
            builder.append('\n');
        }
        builder.append("\nUse the Skill tool when one of these skills matches the task.");
        return builder.toString().strip();
    }

    public Path userSkillsRoot() {
        return userSkillsRoot;
    }

    public Path projectSkillsRoot() {
        return projectSkillsRoot;
    }

    private List<SkillDefinition> loadDirectory(Path root, SkillDefinition.Source source) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }

        List<SkillDefinition> skills = new ArrayList<>();
        try (var entries = Files.list(root)) {
            for (Path skillDir : entries
                    .filter(Files::isDirectory)
                    .sorted()
                    .toList()) {
                parseSkill(skillDir, source).ifPresent(skills::add);
            }
        } catch (IOException ignored) {
            return List.of();
        }
        return skills;
    }

    private Optional<SkillDefinition> parseSkill(Path skillDir, SkillDefinition.Source source) {
        String name = skillDir.getFileName().toString();
        if (!isSafeSkillName(name)) {
            return Optional.empty();
        }

        Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
        if (!Files.isRegularFile(skillFile)) {
            return Optional.empty();
        }

        try {
            ParsedSkillFile parsed = parseSkillFile(Files.readString(skillFile));
            String description = parsed.frontmatter().get("description");
            if (description == null || description.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new SkillDefinition(
                    name,
                    description.strip(),
                    stripToNull(parsed.frontmatter().get("when_to_use")),
                    stripToNull(parsed.frontmatter().get("argument_hint")),
                    parsed.content().strip(),
                    source,
                    skillFile
            ));
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private static ParsedSkillFile parseSkillFile(String raw) {
        String normalized = raw == null ? "" : raw.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return new ParsedSkillFile(Map.of(), normalized);
        }

        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0) {
            return new ParsedSkillFile(Map.of(), normalized);
        }

        String frontmatterText = normalized.substring(4, end);
        String content = normalized.substring(end + "\n---\n".length());
        Map<String, String> frontmatter = new LinkedHashMap<>();
        for (String line : frontmatterText.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).strip();
            String value = unquote(line.substring(colon + 1).strip());
            if (!key.isEmpty()) {
                frontmatter.put(key, value);
            }
        }
        return new ParsedSkillFile(frontmatter, content);
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String stripToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private static boolean isSafeSkillName(String name) {
        return name != null
                && name.matches(SAFE_SKILL_NAME_PATTERN)
                && !".".equals(name)
                && !"..".equals(name);
    }

    private static String stripLeadingSlash(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private static Path defaultUserSkillsRoot() {
        return Paths.get(System.getProperty("user.home"), CONFIG_DIR_NAME, SKILLS_DIR_NAME);
    }

    private static Path defaultProjectSkillsRoot() {
        return Paths.get("").toAbsolutePath().normalize().resolve(CONFIG_DIR_NAME).resolve(SKILLS_DIR_NAME);
    }

    private record ParsedSkillFile(Map<String, String> frontmatter, String content) {
    }
}
