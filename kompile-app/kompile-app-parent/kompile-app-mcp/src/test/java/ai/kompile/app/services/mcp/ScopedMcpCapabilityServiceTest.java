package ai.kompile.app.services.mcp;

import ai.kompile.app.services.ServerPortService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScopedMcpCapabilityServiceTest {

    @Test
    void isolatesConcurrentCapabilitiesAndRevokesOnlyTheClosedScope() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-27T00:00:00Z"));
        try (ScopedMcpCapabilityService service = service(clock)) {
            var first = service.issue(tool("private_graph", "first"), Duration.ofMinutes(5));
            var second = service.issue(tool("private_graph", "second"), Duration.ofMinutes(5));

            assertNotEquals(first.endpointUrl(), second.endpointUrl());
            assertEquals(2, service.activeCapabilityCount());

            AtomicInteger connected = new AtomicInteger();
            first.onConnected(connected::incrementAndGet);
            service.connect(first.token());
            assertEquals(1, connected.get());
            assertThrows(ScopedMcpCapabilityService.ScopeUnavailableException.class,
                    () -> service.connect(first.token()),
                    "a bearer capability must admit only one MCP transport session");

            var executor = Executors.newFixedThreadPool(8);
            try {
                List<Callable<String>> calls = new ArrayList<>();
                for (int index = 0; index < 100; index++) {
                    boolean useFirst = index % 2 == 0;
                    calls.add(() -> service.invokeForTesting(
                            useFirst ? first.token() : second.token(), Map.of("action", "read")));
                }
                List<String> results = executor.invokeAll(calls).stream().map(future -> {
                    try {
                        return future.get();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }).toList();
                assertEquals(50, results.stream().filter("first"::equals).count());
                assertEquals(50, results.stream().filter("second"::equals).count());
            } finally {
                executor.shutdownNow();
            }

            first.close();
            assertThrows(ScopedMcpCapabilityService.ScopeUnavailableException.class,
                    () -> service.invokeForTesting(first.token(), Map.of("action", "read")));
            assertEquals("second", service.invokeForTesting(
                    second.token(), Map.of("action", "read")));
            assertEquals(1, service.activeCapabilityCount());
        }
    }

    @Test
    void rejectsMissingExpiredAndAuthoritySelectingScopesWithoutDistinguishingTokens() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-27T00:00:00Z"));
        try (ScopedMcpCapabilityService service = service(clock)) {
            assertThrows(ScopedMcpCapabilityService.ScopeUnavailableException.class,
                    () -> service.invokeForTesting("A".repeat(43), Map.of("action", "read")));

            var expiring = service.issue(tool("private_graph", "ok"), Duration.ofSeconds(1));
            clock.advance(Duration.ofSeconds(2));
            assertThrows(ScopedMcpCapabilityService.ScopeUnavailableException.class,
                    () -> service.invokeForTesting(expiring.token(), Map.of("action", "read")));
            assertEquals(0, service.activeCapabilityCount());

            Map<String, Object> selectingSchema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "action", Map.of("type", "string"),
                            "ownerId", Map.of("type", "string")),
                    "required", List.of("action"),
                    "additionalProperties", false);
            assertThrows(IllegalArgumentException.class,
                    () -> service.issue(new ScopedMcpCapabilityService.ScopedTool(
                            "bad", "bad selector", selectingSchema, arguments -> "bad"),
                            Duration.ofMinutes(1)));

            Map<String, Object> openSchema = Map.of(
                    "type", "object",
                    "properties", Map.of("action", Map.of("type", "string")),
                    "additionalProperties", true);
            assertThrows(IllegalArgumentException.class,
                    () -> service.issue(new ScopedMcpCapabilityService.ScopedTool(
                            "bad", "open schema", openSchema, arguments -> "bad"),
                            Duration.ofMinutes(1)));
        }
    }

    @Test
    void recognizesOnlyAnExactJsonRpcInitializeRequest() {
        try (ScopedMcpCapabilityService service = service(Clock.systemUTC())) {
            assertTrue(service.isInitializeMessage(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"));
            assertFalse(service.isInitializeMessage(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                            + "\"params\":{\"note\":\"initialize\"}}"));
            assertFalse(service.isInitializeMessage(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"params\":{}}"));
            assertThrows(IllegalArgumentException.class,
                    () -> service.isInitializeMessage("not-json"));
        }
    }

    private static ScopedMcpCapabilityService service(Clock clock) {
        ServerPortService ports = mock(ServerPortService.class);
        when(ports.getBaseUrl()).thenReturn("http://localhost:8081");
        return new ScopedMcpCapabilityService(
                new ObjectMapper(), ports, clock, new SecureRandom());
    }

    private static ScopedMcpCapabilityService.ScopedTool tool(String name, String result) {
        return new ScopedMcpCapabilityService.ScopedTool(
                name,
                "bound tool",
                Map.of(
                        "type", "object",
                        "properties", Map.of("action", Map.of("type", "string")),
                        "required", List.of("action"),
                        "additionalProperties", false),
                arguments -> result);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        private MutableClock(Instant current) {
            this.current = new AtomicReference<>(current);
        }

        void advance(Duration duration) {
            current.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
