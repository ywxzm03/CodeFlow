package com.codeflow.batch;

import java.util.List;

public record BatchWorkUnit(
        String unitId,
        String title,
        String description,
        List<String> targetFilesOrDirectories,
        String expectedChange,
        String validationInstructions
) {
    public BatchWorkUnit {
        unitId = unitId == null || unitId.isBlank() ? "unit" : unitId;
        title = title == null ? unitId : title;
        description = description == null ? "" : description;
        targetFilesOrDirectories = targetFilesOrDirectories == null ? List.of() : List.copyOf(targetFilesOrDirectories);
        expectedChange = expectedChange == null ? "" : expectedChange;
        validationInstructions = validationInstructions == null ? "" : validationInstructions;
    }
}
