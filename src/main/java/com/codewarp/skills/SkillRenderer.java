package com.codewarp.skills;

/**
 * 将 skill 调用渲染成模型可读的普通用户消息。
 */
public class SkillRenderer {

    public String render(SkillDefinition skill, String args, String trigger) {
        if (skill == null) {
            throw new IllegalArgumentException("skill must not be null");
        }

        String normalizedArgs = args == null ? "" : args.strip();
        String normalizedTrigger = trigger == null || trigger.isBlank() ? "unknown" : trigger.strip();
        return """
                <skill_invocation>
                <name>%s</name>
                <args>%s</args>
                <source>%s</source>
                <instruction>This skill has already been loaded. Follow its instructions directly; do not call Skill again for the same skill invocation.</instruction>
                <content>
                %s
                </content>
                </skill_invocation>
                """.formatted(
                escapeXml(skill.name()),
                escapeXml(normalizedArgs),
                escapeXml(normalizedTrigger),
                skill.content().strip()
        ).strip();
    }

    private static String escapeXml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
