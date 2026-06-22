package com.codewarp.skills;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRendererTest {

    @Test
    void rendersSkillInvocationMessage() {
        SkillDefinition skill = new SkillDefinition(
                "commit",
                "Write commit message",
                "commit guidance",
                "<change summary>",
                "Use conventional commits.",
                SkillDefinition.Source.USER,
                Path.of("SKILL.md")
        );

        String rendered = new SkillRenderer().render(skill, "add skills", "user");

        assertTrue(rendered.contains("<skill_invocation>"));
        assertTrue(rendered.contains("<name>commit</name>"));
        assertTrue(rendered.contains("<args>add skills</args>"));
        assertTrue(rendered.contains("<source>user</source>"));
        assertTrue(rendered.contains("This skill has already been loaded"));
        assertTrue(rendered.contains("Use conventional commits."));
    }

    @Test
    void escapesNameAndArgsButKeepsSkillContentReadable() {
        SkillDefinition skill = new SkillDefinition(
                "bad<name>",
                "description",
                null,
                null,
                "Read <literal> content.",
                SkillDefinition.Source.PROJECT,
                Path.of("SKILL.md")
        );

        String rendered = new SkillRenderer().render(skill, "<args>", "model");

        assertTrue(rendered.contains("<name>bad&lt;name&gt;</name>"));
        assertTrue(rendered.contains("<args>&lt;args&gt;</args>"));
        assertTrue(rendered.contains("Read <literal> content."));
    }
}
