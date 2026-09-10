package ai.kompile.app.services.agent;

import ai.kompile.app.services.ServerPortService;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ProvisionedAgentTerminalOutcomeTest {

    private static final String AGENT_ID = "01234567-89ab-cdef-0123-456789abcdef";
    private static final String TURN_ID = "11111111-2222-3333-4444-555555555555";

    @Test
    void apiCompleteErrorAndCancelRaceCommitsOneTerminalEvent() throws Exception {
        CapturingRuntime runtime = new CapturingRuntime();
        AgentChatService service = service(runtime);
        AgentChatService.ProvisionedTurn turn = new AgentChatService.ProvisionedTurn(
                AGENT_ID, "api:race", TURN_ID);
        ApiAgentChatExecutor.ExecutionObserver observer = service.provisionedObserver(
                turn, new SseEmitter());
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            List<Future<Boolean>> outcomes = new ArrayList<>();
            outcomes.add(executor.submit(() -> race(ready, start,
                    () -> observer.onComplete("process", "answer"))));
            outcomes.add(executor.submit(() -> race(ready, start,
                    () -> observer.onError("process", "failure"))));
            outcomes.add(executor.submit(() -> race(ready, start,
                    () -> observer.onCancelled("process", "partial"))));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int winners = 0;
            for (Future<Boolean> outcome : outcomes) {
                if (outcome.get(10, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertEquals(1, winners);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertEquals(1, runtime.events.size());
        ProvisionedAgentRuntime.CanonicalEvent terminal = runtime.events.values().iterator().next();
        assertEquals("turn:" + TURN_ID + ":terminal", terminal.idempotencyKey());
        assertEquals(turn.terminalKind(), terminal.kind());
    }

    @Test
    void cliCancellationGateRejectsLaterCompletionAndError() {
        CapturingRuntime runtime = new CapturingRuntime();
        AgentChatService service = service(runtime);
        AgentChatService.ProvisionedTurn turn = new AgentChatService.ProvisionedTurn(
                AGENT_ID, "cli:cancel", TURN_ID);

        assertEquals(AgentChatService.TerminalPersistence.COMMITTED,
                service.persistProvisionedTerminal(turn, event(
                        ProvisionedAgentRuntime.EventKind.CANCELLED,
                        ProvisionedAgentRuntime.EventRole.SYSTEM,
                        "cancelled")));
        assertEquals(AgentChatService.TerminalPersistence.ALREADY_TERMINAL,
                service.persistProvisionedTerminal(turn, event(
                        ProvisionedAgentRuntime.EventKind.MESSAGE,
                        ProvisionedAgentRuntime.EventRole.ASSISTANT,
                        "late completion")));
        assertEquals(AgentChatService.TerminalPersistence.ALREADY_TERMINAL,
                service.persistProvisionedTerminal(turn, event(
                        ProvisionedAgentRuntime.EventKind.ERROR,
                        ProvisionedAgentRuntime.EventRole.SYSTEM,
                        "late error")));
        service.persistProvisionedToolEvent(turn, event(
                ProvisionedAgentRuntime.EventKind.TOOL_RESULT,
                ProvisionedAgentRuntime.EventRole.TOOL,
                "late tool result"));

        assertEquals(1, runtime.events.size());
        assertEquals(ProvisionedAgentRuntime.EventKind.CANCELLED,
                runtime.events.values().iterator().next().kind());
    }

    @Test
    void cliToolResultRetainsProviderEventTypeForDeterministicKeying() {
        ClaudeStreamParser.ParseResult result = new ClaudeStreamParser().parseLine(
                "session",
                """
                        {"type":"user","message":{"content":[
                          {"type":"tool_result","tool_use_id":"toolu_1","content":"ok"}
                        ]}}
                        """);

        assertEquals("user", result.type());
        assertTrue(result.textContent().contains("ok"));
    }

    @Test
    void terminalPersistenceRetriesTransientFailuresWithTheSameKey() {
        CapturingRuntime runtime = new CapturingRuntime(2);
        AgentChatService service = service(runtime);
        AgentChatService.ProvisionedTurn turn = new AgentChatService.ProvisionedTurn(
                AGENT_ID, "api:retry", TURN_ID);

        assertEquals(AgentChatService.TerminalPersistence.COMMITTED,
                service.persistProvisionedTerminal(turn, event(
                        ProvisionedAgentRuntime.EventKind.MESSAGE,
                        ProvisionedAgentRuntime.EventRole.ASSISTANT,
                        "answer")));
        assertEquals(3, runtime.attempts.get());
        assertEquals(1, runtime.events.size());
        assertEquals("turn:" + TURN_ID + ":terminal",
                runtime.events.values().iterator().next().idempotencyKey());
    }

    @Test
    void exhaustedPersistenceFailureReleasesTerminalClaimForRecovery() {
        CapturingRuntime runtime = new CapturingRuntime(3);
        AgentChatService service = service(runtime);
        AgentChatService.ProvisionedTurn turn = new AgentChatService.ProvisionedTurn(
                AGENT_ID, "api:recovery", TURN_ID);

        assertEquals(AgentChatService.TerminalPersistence.FAILED,
                service.persistProvisionedTerminal(turn, event(
                        ProvisionedAgentRuntime.EventKind.ERROR,
                        ProvisionedAgentRuntime.EventRole.SYSTEM,
                        "first attempt")));
        assertEquals(null, turn.terminalKind());
        assertEquals(AgentChatService.TerminalPersistence.COMMITTED,
                service.persistProvisionedTerminal(turn, event(
                        ProvisionedAgentRuntime.EventKind.MESSAGE,
                        ProvisionedAgentRuntime.EventRole.ASSISTANT,
                        "recovered")));
        assertEquals(1, runtime.events.size());
    }

    private static boolean race(
            CountDownLatch ready,
            CountDownLatch start,
            BooleanSupplier action) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return action.getAsBoolean();
    }

    private static ProvisionedAgentRuntime.EventDraft event(
            ProvisionedAgentRuntime.EventKind kind,
            ProvisionedAgentRuntime.EventRole role,
            String content) {
        return new ProvisionedAgentRuntime.EventDraft(kind, role, content, Map.of("lane", "test"));
    }

    private static AgentChatService service(ProvisionedAgentRuntime runtime) {
        AgentChatService service = new AgentChatService(
                mock(AgentRegistryService.class),
                mock(AgentProcessDiagnosticService.class),
                mock(ClaudeStreamParser.class),
                mock(AgentSubprocessExecutor.class),
                List.of(),
                List.of(),
                List.of(),
                null,
                mock(ServerPortService.class),
                null,
                mock(ApiAgentChatExecutor.class),
                null);
        service.setProvisionedAgentRuntime(runtime);
        return service;
    }

    private static final class CapturingRuntime implements ProvisionedAgentRuntime {
        private final AtomicLong sequences = new AtomicLong();
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger transientFailures;
        private final Map<String, CanonicalEvent> events = new ConcurrentHashMap<>();

        private CapturingRuntime() {
            this(0);
        }

        private CapturingRuntime(int transientFailures) {
            this.transientFailures = new AtomicInteger(transientFailures);
        }

        @Override
        public RuntimeContext prepare(PrepareRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CanonicalEvent append(AppendEventsRequest request) {
            attempts.incrementAndGet();
            if (transientFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new ProvisionedAgentRuntime.RuntimeException(503, "transient failure");
            }
            EventDraft draft = request.event();
            return events.computeIfAbsent(draft.idempotencyKey(), ignored -> new CanonicalEvent(
                    sequences.incrementAndGet(),
                    Instant.EPOCH,
                    draft.kind(),
                    draft.role(),
                    draft.content(),
                    draft.metadata(),
                    draft.idempotencyKey()));
        }

        @Override
        public ToolExecutionResult executeTool(ToolExecutionRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
