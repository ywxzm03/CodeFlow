package com.codeflow.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsUserAndProjectSkills() throws Exception {
        writeSkill(tempDir.resolve("user/commit"), "Write commit message", "commit instructions");
        writeSkill(tempDir.resolve("project/review"), "Review code", "review instructions");
        SkillStore store = new SkillStore(tempDir.resolve("user"), tempDir.resolve("project"));

        List<SkillDefinition> skills = store.list();

        assertEquals(List.of("commit", "review"), skills.stream().map(SkillDefinition::name).toList());
        assertEquals(SkillDefinition.Source.USER, store.find("commit").orElseThrow().source());
        assertEquals(SkillDefinition.Source.PROJECT, store.find("/review").orElseThrow().source());
    }

    @Test
    void projectSkillOverridesUserSkillWithSameName() throws Exception {
        writeSkill(tempDir.resolve("user/commit"), "User commit", "user content");
        writeSkill(tempDir.resolve("project/commit"), "Project commit", "project content");
        SkillStore store = new SkillStore(tempDir.resolve("user"), tempDir.resolve("project"));

        SkillDefinition skill = store.find("commit").orElseThrow();

        assertEquals(SkillDefinition.Source.PROJECT, skill.source());
        assertEquals("Project commit", skill.description());
        assertEquals("project content", skill.content());
    }

    @Test
    void skipsSkillsWithoutDescription() throws Exception {
        Path skillDir = tempDir.resolve("user/bad");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                when_to_use: bad
                ---
                bad content
                """);
        SkillStore store = new SkillStore(tempDir.resolve("user"), tempDir.resolve("project"));

        assertTrue(store.list().isEmpty());
    }

    @Test
    void missingDirectoriesReturnEmptySkillList() {
        SkillStore store = new SkillStore(tempDir.resolve("missing-user"), tempDir.resolve("missing-project"));

        assertTrue(store.list().isEmpty());
        assertEquals("", store.renderIndex());
    }

    @Test
    void rendersSkillIndexFromFrontmatter() throws Exception {
        writeSkillWithFrontmatter(
                tempDir.resolve("user/commit"),
                """
                        description: "Write commit message"
                        when_to_use: User asks for commit guidance
                        argument_hint: <change summary>
                        """,
                "commit instructions"
        );
        SkillStore store = new SkillStore(tempDir.resolve("user"), tempDir.resolve("project"));

        String index = store.renderIndex();

        assertTrue(index.contains("Available skills:"));
        assertTrue(index.contains("- commit: Write commit message"));
        assertTrue(index.contains("when_to_use: User asks for commit guidance"));
        assertTrue(index.contains("args: <change summary>"));
    }

    private void writeSkill(Path skillDir, String description, String content) throws Exception {
        writeSkillWithFrontmatter(skillDir, "description: \"" + description + "\"", content);
    }

    private void writeSkillWithFrontmatter(Path skillDir, String frontmatter, String content) throws Exception {
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                %s
                ---
                %s
                """.formatted(frontmatter.strip(), content));
    }
}
