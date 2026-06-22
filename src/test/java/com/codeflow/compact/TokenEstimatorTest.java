package com.codeflow.compact;

import com.codeflow.core.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void usesLatestAssistantUsageAsAnchor() {
        List<Message> messages = List.of(
                new Message.User("old text that is already counted"),
                new Message.Assistant("answer", List.of(), new Message.Usage(100, 20, 5, 3)),
                new Message.ToolResult("toolu_1", "abcdefghijkl", false)
        );

        assertEquals(131, estimator.estimate(messages));
    }

    @Test
    void fallsBackToRoughEstimateWhenNoUsageExists() {
        List<Message> messages = List.of(
                new Message.User("abcdefgh"),
                new Message.ToolResult("toolu_1", "abcdefghijkl", false)
        );

        assertEquals(5, estimator.estimate(messages));
    }

    @Test
    void estimatesAssistantToolUseByNameAndInput() {
        Message.Assistant assistant = new Message.Assistant(
                "abcd",
                List.of(new Message.ToolUse("toolu_1", "Read", "{\"file_path\":\"A.java\"}")),
                null
        );

        assertEquals(8, estimator.estimateRough(assistant));
    }
}
