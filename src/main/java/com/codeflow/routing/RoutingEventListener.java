package com.codeflow.routing;

public interface RoutingEventListener {
    RoutingEventListener none = new RoutingEventListener() {};

    default void beforeModelCall(RoutingEvents.BeforeModelCall event) {}

    default void afterModelSuccess(RoutingEvents.AfterModelSuccess event) {}

    default void afterModelException(RoutingEvents.AfterModelException event) {}

    default void beforeFallbackSwitch(RoutingEvents.BeforeFallbackSwitch event) {}

    default void afterAllCandidatesFailed(RoutingEvents.AfterAllCandidatesFailed event) {}
}
