package com.codeflow.skills;

import java.nio.file.Path;

/**
 * 一个可按需展开的 skill。
 */
public record SkillDefinition(
        String name,
        String description,
        String whenToUse,
        String argumentHint,
        String content,
        Source source,
        Path path
) {
    public enum Source {
        USER,
        PROJECT
    }
}
