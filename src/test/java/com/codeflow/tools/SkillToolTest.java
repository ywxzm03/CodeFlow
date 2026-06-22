package com.codeflow.tools;

import com.codeflow.skills.SkillRenderer;
import com.codeflow.skills.SkillStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillToolTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsKnownSkill() throws Exception {
        writeSkill(tempDir.resolve("skills/commit"));
        SkillTool tool = new SkillTool(new SkillStore(tempDir.resolve("skills"), tempDir.resolve("project")), new SkillRenderer());

        Tool.ToolExecutionResult result = tool.execute("""
                {"skill":"commit","args":"add skills"}
                """);

        assertFalse(result.isError());
        assertTrue(result.content().contains("Skill loaded: commit"));
        assertTrue(tool.renderInvocation("{\"skill\":\"commit\",\"args\":\"add skills\"}", "model")
                .orElseThrow()
                .contains("<name>commit</name>"));
    }

    @Test
    void unknownSkillReturnsError() {
        SkillTool tool = new SkillTool(new SkillStore(tempDir.resolve("missing"), tempDir.resolve("project")), new SkillRenderer());

        Tool.ToolExecutionResult result = tool.execute("""
                {"skill":"missing"}
                """);

        assertTrue(result.isError());
        assertTrue(result.content().contains("Unknown skill"));
    }

    @Test
    void validatesInputShape() {
        SkillTool tool = new SkillTool(new SkillStore(tempDir.resolve("missing"), tempDir.resolve("project")), new SkillRenderer());

        assertFalse(tool.validateInput("{}").allowed());
        assertFalse(tool.validateInput("{\"skill\":\"\"}").allowed());
        assertFalse(tool.validateInput("{\"skill\":\"commit\",\"extra\":\"x\"}").allowed());
    }

    private void writeSkill(Path skillDir) throws Exception {
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                description: Write commit message
                when_to_use: User asks for commit guidance
                argument_hint: <change summary>
                ---
                Use conventional commits.
                """);
    }
}
