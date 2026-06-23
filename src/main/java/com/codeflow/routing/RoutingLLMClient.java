package com.codeflow.routing;

import com.codeflow.core.Message;
import com.codeflow.core.CancellationToken;
import com.codeflow.core.UserCancelledException;
import com.codeflow.llm.LLMClient;
import com.codeflow.tools.Tool;
import com.codeflow.util.Console;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class RoutingLLMClient implements LLMClient, RoutingStatusProvider {
    private final LLMClient delegate;
    private final List<ModelRoute> routes;
    private final FallbackPolicy policy;
    private final ModelHealthRegistry healthRegistry;
    private final RoutingEventListener eventListener;
    private volatile ModelRoute activeRoute;

    public RoutingLLMClient(
            LLMClient delegate,
            Map<String, String> models,
            String activeModelKey,
            FallbackPolicy policy
    ) {
        this(delegate, models, activeModelKey, policy, new ModelHealthRegistry(policy.unhealthyCooldown()), RoutingEventListener.none);
    }

    public RoutingLLMClient(
            LLMClient delegate,
            Map<String, String> models,
            String activeModelKey,
            FallbackPolicy policy,
            ModelHealthRegistry healthRegistry,
            RoutingEventListener eventListener
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.routes = routesFrom(models);
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("Routing models must not be empty");
        }
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.healthRegistry = Objects.requireNonNull(healthRegistry, "healthRegistry must not be null");
        this.eventListener = eventListener == null ? RoutingEventListener.none : eventListener;
        this.activeRoute = routeByKey(activeModelKey).orElse(routes.getFirst());
        this.delegate.setModel(activeRoute.model());
    }

    @Override
    public LLMResponse call(String systemPrompt, List<Message> messages, List<Tool> tools) {
        return call(systemPrompt, messages, tools, CancellationToken.none());
    }

    @Override
    public LLMResponse call(String systemPrompt, List<Message> messages, List<Tool> tools, CancellationToken cancellationToken) {
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        return execute(ModelCallType.SYNC, route -> delegate.call(systemPrompt, messages, tools, token));
    }

    @Override
    public Flux<StreamEvent> callStreaming(String systemPrompt, List<Message> messages, List<Tool> tools) {
        return callStreaming(systemPrompt, messages, tools, CancellationToken.none());
    }

    @Override
    public Flux<StreamEvent> callStreaming(String systemPrompt, List<Message> messages, List<Tool> tools, CancellationToken cancellationToken) {
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        return Flux.defer(() -> {
            token.throwIfCancelled();
            ModelRoute startingRoute = activeRoute;
            List<ModelRoute> candidates = candidatesFor(startingRoute);
            eventListener.beforeModelCall(new RoutingEvents.BeforeModelCall(
                    ModelCallType.STREAMING,
                    startingRoute,
                    candidates,
                    healthRegistry.snapshot()
            ));
            return streamAttempt(systemPrompt, messages, tools, token, startingRoute, candidates, new ArrayList<>(), false, false);
        });
    }

    @Override
    public synchronized void setModel(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Model must not be blank");
        }
        Optional<ModelRoute> route = routeByModel(model);
        route.ifPresent(modelRoute -> activeRoute = modelRoute);
        delegate.setModel(model);
    }

    public synchronized String activeModelKey() {
        return activeRoute.key();
    }

    public RoutingSnapshot snapshot() {
        return routingSnapshot();
    }

    @Override
    public RoutingSnapshot routingSnapshot() {
        ModelRoute active = activeRoute;
        return new RoutingSnapshot(
                true,
                active,
                fallbackCandidatesFor(active),
                policy.retryCurrentModelOnce(),
                policy.unhealthyCooldown(),
                healthRegistry.snapshot()
        );
    }

    private <T> T execute(ModelCallType callType, ModelOperation<T> operation) {
        ModelRoute startingRoute = activeRoute;
        List<ModelRoute> candidates = candidatesFor(startingRoute);
        eventListener.beforeModelCall(new RoutingEvents.BeforeModelCall(
                callType,
                startingRoute,
                candidates,
                healthRegistry.snapshot()
        ));

        List<ModelRoute> attempted = new ArrayList<>();
        Throwable lastError = null;

        AttemptResult<T> current = attempt(callType, startingRoute, operation, false, false);
        addAttempted(attempted, startingRoute);
        if (current.success()) {
            return current.value();
        }
        lastError = current.error();
        if (!current.decision().fallbackable()) {
            throw rethrow(lastError);
        }

        if (policy.retryCurrentModelOnce()) {
            Console.warn("[Routing] 模型调用失败，重试当前模型: " + startingRoute.key());
            AttemptResult<T> retry = attempt(callType, startingRoute, operation, true, false);
            if (retry.success()) {
                return retry.value();
            }
            lastError = retry.error();
            if (!retry.decision().fallbackable()) {
                throw rethrow(lastError);
            }
            current = retry;
        }

        healthRegistry.markUnhealthy(startingRoute.key(), current.decision().reason());
        ModelRoute failedRoute = startingRoute;
        for (ModelRoute candidate : candidates) {
            if (candidate.key().equals(startingRoute.key())) {
                continue;
            }
            if (!healthRegistry.isAvailableCandidate(candidate.key())) {
                continue;
            }

            eventListener.beforeFallbackSwitch(new RoutingEvents.BeforeFallbackSwitch(
                    callType,
                    failedRoute,
                    candidate,
                    current.decision().reason()
            ));
            Console.warn("[Routing] 切换 fallback 模型: " + failedRoute.key() + " -> " + candidate.key());

            AttemptResult<T> fallback = attempt(callType, candidate, operation, false, true);
            addAttempted(attempted, candidate);
            if (fallback.success()) {
                return fallback.value();
            }
            lastError = fallback.error();
            if (!fallback.decision().fallbackable()) {
                throw rethrow(lastError);
            }
            healthRegistry.markUnhealthy(candidate.key(), fallback.decision().reason());
            failedRoute = candidate;
            current = fallback;
        }

        String summary = "All routing candidates failed after " + attempted.size() + " candidate(s)";
        eventListener.afterAllCandidatesFailed(new RoutingEvents.AfterAllCandidatesFailed(
                callType,
                List.copyOf(attempted),
                lastError,
                summary
        ));
        throw new RoutingModelException(summary, lastError);
    }

    private Flux<StreamEvent> streamAttempt(
            String systemPrompt,
            List<Message> messages,
            List<Tool> tools,
            CancellationToken cancellationToken,
            ModelRoute route,
            List<ModelRoute> candidates,
            List<ModelRoute> attempted,
            boolean retry,
            boolean fallback
    ) {
        cancellationToken.throwIfCancelled();
        selectRoute(route);
        addAttempted(attempted, route);
        return delegate.callStreaming(systemPrompt, messages, tools, cancellationToken)
                .doOnComplete(() -> {
                    healthRegistry.markHealthy(route.key());
                    activateRoute(route);
                    eventListener.afterModelSuccess(new RoutingEvents.AfterModelSuccess(
                            ModelCallType.STREAMING,
                            route,
                            fallback
                    ));
                })
                .onErrorResume(error -> {
                    if (isUserCancelled(error)) {
                        return Flux.error(error);
                    }
                    FallbackDecision decision = policy.classify(error);
                    eventListener.afterModelException(new RoutingEvents.AfterModelException(
                            ModelCallType.STREAMING,
                            route,
                            error,
                            retry,
                            decision
                    ));
                    if (!decision.fallbackable()) {
                        return Flux.error(error);
                    }
                    if (!fallback && !retry && policy.retryCurrentModelOnce()) {
                        Console.warn("[Routing] 流式模型调用失败，重试当前模型: " + route.key());
                        return streamAttempt(systemPrompt, messages, tools, cancellationToken, route, candidates, attempted, true, fallback);
                    }

                    healthRegistry.markUnhealthy(route.key(), decision.reason());
                    ModelRoute next = nextAvailableCandidate(candidates, route);
                    if (next == null) {
                        String summary = "All routing candidates failed after " + attempted.size() + " candidate(s)";
                        eventListener.afterAllCandidatesFailed(new RoutingEvents.AfterAllCandidatesFailed(
                                ModelCallType.STREAMING,
                                List.copyOf(attempted),
                                error,
                                summary
                        ));
                        return Flux.error(new RoutingModelException(summary, error));
                    }

                    eventListener.beforeFallbackSwitch(new RoutingEvents.BeforeFallbackSwitch(
                            ModelCallType.STREAMING,
                            route,
                            next,
                            decision.reason()
                    ));
                    Console.warn("[Routing] 流式调用切换 fallback 模型: " + route.key() + " -> " + next.key());
                    return streamAttempt(systemPrompt, messages, tools, cancellationToken, next, candidates, attempted, false, true);
                });
    }

    private <T> AttemptResult<T> attempt(
            ModelCallType callType,
            ModelRoute route,
            ModelOperation<T> operation,
            boolean retry,
            boolean fallback
    ) {
        try {
            selectRoute(route);
            T value = operation.run(route);
            healthRegistry.markHealthy(route.key());
            activateRoute(route);
            eventListener.afterModelSuccess(new RoutingEvents.AfterModelSuccess(callType, route, fallback));
            return AttemptResult.success(value);
        } catch (Throwable error) {
            if (isUserCancelled(error)) {
                throw rethrow(error);
            }
            FallbackDecision decision = policy.classify(error);
            eventListener.afterModelException(new RoutingEvents.AfterModelException(
                    callType,
                    route,
                    error,
                    retry,
                    decision
            ));
            return AttemptResult.failure(error, decision);
        }
    }

    private boolean isUserCancelled(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof UserCancelledException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private synchronized void selectRoute(ModelRoute route) {
        delegate.setModel(route.model());
    }

    private synchronized void activateRoute(ModelRoute route) {
        activeRoute = route;
        delegate.setModel(route.model());
    }

    private void addAttempted(List<ModelRoute> attempted, ModelRoute route) {
        boolean alreadyAttempted = attempted.stream().anyMatch(existing -> existing.key().equals(route.key()));
        if (!alreadyAttempted) {
            attempted.add(route);
        }
    }

    private List<ModelRoute> candidatesFor(ModelRoute active) {
        List<ModelRoute> candidates = new ArrayList<>();
        candidates.add(active);
        for (ModelRoute route : routes) {
            if (route.key().equals(active.key())) {
                continue;
            }
            if (healthRegistry.isAvailableCandidate(route.key())) {
                candidates.add(route);
            }
        }
        return candidates;
    }

    private List<ModelRoute> fallbackCandidatesFor(ModelRoute active) {
        return candidatesFor(active).stream()
                .filter(route -> !route.key().equals(active.key()))
                .toList();
    }

    private ModelRoute nextAvailableCandidate(List<ModelRoute> candidates, ModelRoute current) {
        boolean afterCurrent = false;
        for (ModelRoute candidate : candidates) {
            if (!afterCurrent) {
                afterCurrent = candidate.key().equals(current.key());
                continue;
            }
            if (healthRegistry.isAvailableCandidate(candidate.key())) {
                return candidate;
            }
        }
        return null;
    }

    private Optional<ModelRoute> routeByKey(String key) {
        return routes.stream()
                .filter(route -> route.key().equals(key))
                .findFirst();
    }

    private Optional<ModelRoute> routeByModel(String model) {
        return routes.stream()
                .filter(route -> route.model().equals(model))
                .findFirst();
    }

    private RuntimeException rethrow(Throwable error) {
        if (error instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new RuntimeException(error);
    }

    private static List<ModelRoute> routesFrom(Map<String, String> models) {
        if (models == null || models.isEmpty()) {
            return List.of();
        }
        Map<String, String> ordered = new LinkedHashMap<>(models);
        List<ModelRoute> routes = new ArrayList<>();
        for (Map.Entry<String, String> entry : ordered.entrySet()) {
            routes.add(new ModelRoute(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(routes);
    }

    @FunctionalInterface
    private interface ModelOperation<T> {
        T run(ModelRoute route);
    }

    private record AttemptResult<T>(T value, Throwable error, FallbackDecision decision) {
        static <T> AttemptResult<T> success(T value) {
            return new AttemptResult<>(value, null, null);
        }

        static <T> AttemptResult<T> failure(Throwable error, FallbackDecision decision) {
            return new AttemptResult<>(null, error, decision);
        }

        boolean success() {
            return error == null;
        }
    }
}
