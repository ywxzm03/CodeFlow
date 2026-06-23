package com.codeflow.batch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchPlanTest {

    @Test
    void parsesPlannerJsonFromMarkdownResponse() {
        BatchPlan plan = BatchPlan.parsePlannerResponse("""
                Here is the plan:
                ```json
                {
                  "overallGoal": "migrate parser",
                  "findings": "Parser lives in src/parser",
                  "validationRecipe": "./gradlew test",
                  "workUnits": [
                    {
                      "unitId": "parser",
                      "title": "Parser migration",
                      "description": "Update parser",
                      "targetFilesOrDirectories": ["src/parser"],
                      "expectedChange": "Use new API",
                      "validationInstructions": "./gradlew test --tests ParserTest"
                    }
                  ]
                }
                ```
                """, "fallback");

        assertEquals("migrate parser", plan.overallGoal());
        assertEquals(1, plan.workUnits().size());
        assertEquals("parser", plan.workUnits().getFirst().unitId());
        assertEquals(List.of("src/parser"), plan.workUnits().getFirst().targetFilesOrDirectories());
        assertTrue(plan.renderForApproval().contains("Parser migration"));
    }

    @Test
    void assignsUnitIdsWhenPlannerOmitsThem() {
        BatchPlan plan = BatchPlan.parsePlannerResponse("""
                {"overallGoal":"goal","work_units":[{"title":"One"}]}
                """, "fallback");

        assertEquals("unit-1", plan.workUnits().getFirst().unitId());
    }
}
