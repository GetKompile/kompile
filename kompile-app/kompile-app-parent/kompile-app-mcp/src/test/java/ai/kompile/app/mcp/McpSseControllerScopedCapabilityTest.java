package ai.kompile.app.mcp;

import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.services.mcp.ScopedMcpCapabilityService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpSseControllerScopedCapabilityTest {

    @Test
    void scopedSseBearerAdmitsOneConnectionAndDeletesClientConfigCallback() {
        try (Fixture fixture = new Fixture()) {
            var capability = fixture.service.issue(tool(), Duration.ofMinutes(5));
            String token = token(capability);
            AtomicInteger connected = new AtomicInteger();
            capability.onConnected(connected::incrementAndGet);

            assertNotNull(fixture.controller.connectScoped(token, null));
            assertEquals(1, connected.get());

            ResponseStatusException unavailable = assertThrows(
                    ResponseStatusException.class,
                    () -> fixture.controller.connectScoped(token, null));
            assertEquals(HttpStatus.NOT_FOUND, unavailable.getStatusCode());
        }
    }

    @Test
    void streamableRouteRequiresExactInitializeAndRejectsCrossTokenSessions() {
        try (Fixture fixture = new Fixture()) {
            var first = fixture.service.issue(tool(), Duration.ofMinutes(5));
            var second = fixture.service.issue(tool(), Duration.ofMinutes(5));
            String firstToken = token(first);
            String secondToken = token(second);

            MockHttpServletResponse initialized = new MockHttpServletResponse();
            assertNotNull(fixture.controller.handleScopedStreamableHttp(
                    firstToken,
                    null,
                    request(initialize(1)),
                    initialized));
            String firstSession = initialized.getHeader("Mcp-Session-Id");
            assertNotNull(firstSession);
            Object notification = fixture.controller.handleScopedStreamableHttp(
                    firstToken,
                    firstSession,
                    request("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"),
                    new MockHttpServletResponse());
            assertEquals(HttpStatus.ACCEPTED,
                    ((ResponseEntity<?>) notification).getStatusCode());
            assertNotNull(fixture.controller.connectScoped(firstToken, firstSession));
            assertFalse(fixture.service.hasSession(secondToken, firstSession));
            assertEquals(HttpStatus.NOT_FOUND, fixture.controller.handleScopedMessage(
                    secondToken,
                    firstSession,
                    request("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                    .getStatusCode());

            String misleading = "{\"jsonrpc\":\"2.0\",\"id\":2,"
                    + "\"method\":\"tools/call\","
                    + "\"params\":{\"note\":\"initialize\"}}";
            ResponseStatusException missingSession = assertThrows(
                    ResponseStatusException.class,
                    () -> fixture.controller.handleScopedStreamableHttp(
                            secondToken, null, request(misleading), new MockHttpServletResponse()));
            assertEquals(HttpStatus.BAD_REQUEST, missingSession.getStatusCode());

            MockHttpServletResponse secondInitialized = new MockHttpServletResponse();
            assertNotNull(fixture.controller.handleScopedStreamableHttp(
                    secondToken,
                    null,
                    request(initialize(3)),
                    secondInitialized));
            assertNotNull(secondInitialized.getHeader("Mcp-Session-Id"),
                    "the misleading request must not consume the second bearer");

            assertEquals(true, fixture.service.hasSession(firstToken, firstSession),
                    "completing initialize response must retain the logical MCP session");
        }
    }

    private static MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static String initialize(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"initialize\",\"params\":{}}";
    }

    private static String token(ScopedMcpCapabilityService.Capability capability) {
        String endpoint = capability.endpointUrl();
        String prefix = "/mcp/scoped/";
        int start = endpoint.indexOf(prefix) + prefix.length();
        return endpoint.substring(start, endpoint.length() - "/sse".length());
    }

    private static ScopedMcpCapabilityService.ScopedTool tool() {
        return new ScopedMcpCapabilityService.ScopedTool(
                "agent_private_graph",
                "bound graph",
                Map.of(
                        "type", "object",
                        "properties", Map.of("action", Map.of("type", "string")),
                        "required", List.of("action"),
                        "additionalProperties", false),
                arguments -> "ok");
    }

    private static final class Fixture implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final ServerPortService ports = mock(ServerPortService.class);
        private final SpringMvcSseServerTransport globalTransport;
        private final ScopedMcpCapabilityService service;
        private final McpSseController controller;

        private Fixture() {
            when(ports.getBaseUrl()).thenReturn("http://localhost:8081");
            globalTransport = new SpringMvcSseServerTransport(
                    mapper, ports::getBaseUrl, "/mcp/message", "/mcp/sse");
            service = new ScopedMcpCapabilityService(mapper, ports);
            controller = new McpSseController(globalTransport, service);
        }

        @Override
        public void close() {
            service.close();
            globalTransport.close();
        }
    }
}
