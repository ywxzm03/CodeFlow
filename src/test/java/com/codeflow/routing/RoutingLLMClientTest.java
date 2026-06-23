package com.codeflow.routing;

import com.codeflow.core.Message;
import com.codeflow.core.UserCancelledException;
import com.codeflow.llm.LLMClient;
import com.codeflow.tools.Tool;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingLLMClientTest {

    @Test
    void retriesCurrentModelOnceThenFallsBackToNextCandidate() {
        FakeClient delegate = new FakeClient();
        delegate.syncFailures("model-a", fallbackable("a-1"), fallbackable("a-2"));
        delegate.syncResponse("model-b", response("from-b"));
        RoutingLLMClient client = client(delegate);

        LLMClient.LLMResponse response = client.call("system", List.of(), List.of());

        assertEquals("from-b", response.content());
        assertEquals(List.of("model-a", "model-a", "model-b"), delegate.syncCalls);
        assertEquals("B", client.activeModelKey());
    }

    @Test
    void nonFallbackableErrorDoesNotSwitchModel() {
        FakeClient delegate = new FakeClient();
        IllegalArgumentException error = new IllegalArgumentException("bad url");
        delegate.syncFailures("model-a", error);
        RoutingLLMClient client = client(delegate);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> client.call("system", List.of(), List.of()));

        assertEquals(error, thrown);
        assertEquals(List.of("model-a"), delegate.syncCalls);
        assertEquals("A", client.activeModelKey());
    }

    @Test
    void userCancellationDoesNotFallbackOrMarkUnhealthy() {
        FakeClient delegate = new FakeClient();
        delegate.syncFailures("model-a", new UserCancelledException("user-cancel"));
        ModelHealthRegistry health = new ModelHealthRegistry(Duration.ofSeconds(300));
        RecordingListener listener = new RecordingListener();
        RoutingLLMClient client = new RoutingLLMClient(
                delegate,
                models(),
                "A",
                new FallbackPolicy(true, Duration.ofSeconds(300)),
                health,
                listener
        );

        assertThrows(UserCancelledException.class, () -> client.call("system", List.of(), List.of()));

        assertEquals(List.of("model-a"), delegate.syncCalls);
        assertEquals("A", client.activeModelKey());
        assertEquals(ModelHealthStatus.UNKNOWN, health.health("A").status());
        assertTrue(listener.exceptions.isEmpty());
        assertTrue(listener.switches.isEmpty());
        assertTrue(listener.allFailed.isEmpty());
    }

    @Test
    void fallbackModelRemainsActiveForLaterCalls() {
        FakeClient delegate = new FakeClient();
        delegate.syncFailures("model-a", fallbackable("a-1"), fallbackable("a-2"));
        delegate.syncResponse("model-b", response("from-b"));
        RoutingLLMClient client = client(delegate);

        client.call("system", List.of(), List.of());
        LLMClient.LLMResponse second = client.call("system", List.of(), List.of());

        assertEquals("from-b", second.content());
        assertEquals(List.of("model-a", "model-a", "model-b", "model-b"), delegate.syncCalls);
        assertEquals("B", client.activeModelKey());
    }

    @Test
    void skipsUnhealthyCandidateUntilCooldownExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-23T00:00:00Z"));
        ModelHealthRegistry health = new ModelHealthRegistry(Duration.ofSeconds(300), clock);
        FakeClient delegate = new FakeClient();
        delegate.syncFailures("model-a", fallbackable("a-1"), fallbackable("a-2"));
        delegate.syncFailures("model-b", fallbackable("b-1"));
        delegate.syncResponse("model-c", response("from-c"));
        RoutingLLMClient client = client(delegate, health);

        client.call("system", List.of(), List.of());
        assertEquals(List.of("model-a", "model-a", "model-b", "model-c"), delegate.syncCalls);

        delegate.syncFailures("model-c", fallbackable("c-1"), fallbackable("c-2"));
        delegate.syncResponse("model-a", response("from-a"));
        RoutingModelException secondFailure = assertThrows(RoutingModelException.class,
                () -> client.call("system", List.of(), List.of()));
        assertTrue(secondFailure.getMessage().contains("All routing candidates failed"));
        assertEquals(List.of("model-a", "model-a", "model-b", "model-c", "model-c", "model-c"), delegate.syncCalls);

        clock.advance(Duration.ofSeconds(301));
        delegate.syncFailures("model-c", fallbackable("c-3"), fallbackable("c-4"));
        delegate.syncResponse("model-a", response("from-a"));
        client.call("system", List.of(), List.of());

        assertEquals("A", client.activeModelKey());
        assertEquals(List.of("model-c", "model-c", "model-a"),
                delegate.syncCalls.subList(delegate.syncCalls.size() - 3, delegate.syncCalls.size()));
    }

    @Test
    void allCandidatesFailedThrowsRoutingExceptionAndEmitsEvent() {
        FakeClient delegate = new FakeClient();
        delegate.syncFailures("model-a", fallbackable("a-1"), fallbackable("a-2"));
        delegate.syncFailures("model-b", fallbackable("b-1"));
        delegate.syncFailures("model-c", fallbackable("c-1"));
        RecordingListener listener = new RecordingListener();
        RoutingLLMClient client = client(delegate, listener);

        RoutingModelException thrown = assertThrows(RoutingModelException.class,
                () -> client.call("system", List.of(), List.of()));

        assertTrue(thrown.getMessage().contains("All routing candidates failed"));
        assertEquals(1, listener.allFailed.size());
        assertEquals(List.of("A", "B", "C"), listener.allFailed.getFirst().attempted().stream()
                .map(ModelRoute::key)
                .toList());
    }

    @Test
    void streamingFallbackReplaysOnNextCandidateAfterPartialOutput() {
        FakeClient delegate = new FakeClient();
        delegate.stream("model-a", Flux.concat(
                Flux.just(new LLMClient.StreamEvent.TextDelta("partial-a")),
                Flux.error(fallbackable("stream-a"))
        ));
        delegate.stream("model-b", Flux.just(new LLMClient.StreamEvent.TextDelta("from-b")));
        RoutingLLMClient client = client(delegate);

        List<LLMClient.StreamEvent> events = client.callStreaming("system", List.of(), List.of())
                .collectList()
                .block();

        assertEquals(List.of("partial-a", "partial-a", "from-b"), events.stream()
                .map(event -> ((LLMClient.StreamEvent.TextDelta) event).text())
                .toList());
        assertEquals(List.of("model-a", "model-a", "model-b"), delegate.streamCalls);
        assertEquals("B", client.activeModelKey());
    }

    @Test
    void eventsRecordFallbackSwitchAndSuccess() {
        FakeClient delegate = new FakeClient();
        delegate.syncFailures("model-a", fallbackable("a-1"), fallbackable("a-2"));
        delegate.syncResponse("model-b", response("from-b"));
        RecordingListener listener = new RecordingListener();
        RoutingLLMClient client = client(delegate, listener);

        client.call("system", List.of(), List.of());

        assertEquals(1, listener.beforeCalls.size());
        assertEquals(List.of("A", "B", "C"), listener.beforeCalls.getFirst().candidates().stream()
                .map(ModelRoute::key)
                .toList());
        assertEquals(1, listener.switches.size());
        assertEquals("A", listener.switches.getFirst().from().key());
        assertEquals("B", listener.switches.getFirst().to().key());
        assertEquals("B", listener.successes.getLast().model().key());
    }

    @Test
    void setModelUpdatesActiveKeyWhenModelIsKnown() {
        FakeClient delegate = new FakeClient();
        delegate.syncResponse("model-c", response("from-c"));
        RoutingLLMClient client = client(delegate);

        client.setModel("model-c");
        LLMClient.LLMResponse response = client.call("system", List.of(), List.of());

        assertEquals("C", client.activeModelKey());
        assertEquals("from-c", response.content());
        assertEquals(List.of("model-c"), delegate.syncCalls);
    }

    private RoutingLLMClient client(FakeClient delegate) {
        return client(delegate, new ModelHealthRegistry(Duration.ofSeconds(300)));
    }

    private RoutingLLMClient client(FakeClient delegate, ModelHealthRegistry health) {
        return new RoutingLLMClient(
                delegate,
                models(),
                "A",
                new FallbackPolicy(true, Duration.ofSeconds(300)),
                health,
                RoutingEventListener.none
        );
    }

    private RoutingLLMClient client(FakeClient delegate, RecordingListener listener) {
        return new RoutingLLMClient(
                delegate,
                models(),
                "A",
                new FallbackPolicy(true, Duration.ofSeconds(300)),
                new ModelHealthRegistry(Duration.ofSeconds(300)),
                listener
        );
    }

    private Map<String, String> models() {
        Map<String, String> models = new LinkedHashMap<>();
        models.put("A", "model-a");
        models.put("B", "model-b");
        models.put("C", "model-c");
        return models;
    }

    private RuntimeException fallbackable(String message) {
        return new RuntimeException(message, new java.net.SocketTimeoutException(message));
    }

    private LLMClient.LLMResponse response(String content) {
        return new LLMClient.LLMResponse(content, List.of(), null);
    }

    private static final class FakeClient implements LLMClient {
        private final Map<String, List<Object>> syncByModel = new LinkedHashMap<>();
        private final Map<String, Flux<StreamEvent>> streamByModel = new LinkedHashMap<>();
        private final List<String> syncCalls = new ArrayList<>();
        private final List<String> streamCalls = new ArrayList<>();
        private String model;

        void syncResponse(String model, LLMResponse response) {
            syncByModel.put(model, new ArrayList<>(List.of(response)));
        }

        void syncFailures(String model, RuntimeException... failures) {
            syncByModel.put(model, new ArrayList<>(List.of(failures)));
        }

        void stream(String model, Flux<StreamEvent> stream) {
            streamByModel.put(model, stream);
        }

        @Override
        public LLMResponse call(String systemPrompt, List<Message> messages, List<Tool> tools) {
            syncCalls.add(model);
            List<Object> results = syncByModel.get(model);
            if (results == null || results.isEmpty()) {
                throw new IllegalStateException("No sync result for " + model);
            }
            Object result = results.size() == 1 ? results.getFirst() : results.removeFirst();
            if (result instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            return (LLMResponse) result;
        }

        @Override
        public Flux<StreamEvent> callStreaming(String systemPrompt, List<Message> messages, List<Tool> tools) {
            streamCalls.add(model);
            Flux<StreamEvent> stream = streamByModel.get(model);
            if (stream == null) {
                return Flux.error(new IllegalStateException("No stream for " + model));
            }
            return stream;
        }

        @Override
        public void setModel(String model) {
            this.model = model;
        }
    }

    private static final class RecordingListener implements RoutingEventListener {
        private final List<RoutingEvents.BeforeModelCall> beforeCalls = new ArrayList<>();
        private final List<RoutingEvents.AfterModelSuccess> successes = new ArrayList<>();
        private final List<RoutingEvents.AfterModelException> exceptions = new ArrayList<>();
        private final List<RoutingEvents.BeforeFallbackSwitch> switches = new ArrayList<>();
        private final List<RoutingEvents.AfterAllCandidatesFailed> allFailed = new ArrayList<>();

        @Override
        public void beforeModelCall(RoutingEvents.BeforeModelCall event) {
            beforeCalls.add(event);
        }

        @Override
        public void afterModelSuccess(RoutingEvents.AfterModelSuccess event) {
            successes.add(event);
        }

        @Override
        public void afterModelException(RoutingEvents.AfterModelException event) {
            exceptions.add(event);
        }

        @Override
        public void beforeFallbackSwitch(RoutingEvents.BeforeFallbackSwitch event) {
            switches.add(event);
        }

        @Override
        public void afterAllCandidatesFailed(RoutingEvents.AfterAllCandidatesFailed event) {
            allFailed.add(event);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
