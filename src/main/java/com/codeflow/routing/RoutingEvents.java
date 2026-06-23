package com.codeflow.routing;

import java.util.List;
import java.util.Map;

public final class RoutingEvents {
    private RoutingEvents() {
    }

    public record BeforeModelCall(
            ModelCallType callType,
            ModelRoute activeModel,
            List<ModelRoute> candidates,
            Map<String, ModelHealth> health
    ) {}

    public record AfterModelSuccess(
            ModelCallType callType,
            ModelRoute model,
            boolean fallback
    ) {}

    public record AfterModelException(
            ModelCallType callType,
            ModelRoute model,
            Throwable error,
            boolean retry,
            FallbackDecision decision
    ) {}

    public record BeforeFallbackSwitch(
            ModelCallType callType,
            ModelRoute from,
            ModelRoute to,
            String reason
    ) {}

    public record AfterAllCandidatesFailed(
            ModelCallType callType,
            List<ModelRoute> attempted,
            Throwable lastError,
            String summary
    ) {}
}
