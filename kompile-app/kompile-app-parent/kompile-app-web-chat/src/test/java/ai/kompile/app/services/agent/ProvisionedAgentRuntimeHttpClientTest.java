/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.services.agent;

import ai.kompile.app.web.security.IntegrationControlCredentials;
import ai.kompile.channel.api.ChannelControlHeaders;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProvisionedAgentRuntimeHttpClientTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String AGENT_ID = "01234567-89ab-cdef-0123-456789abcdef";
    private static final String REVISION = "0".repeat(64);

    @TempDir
    Path tempDir;

    private HttpServer server;
    private ObjectMapper mapper;
    private final AtomicReference<JsonNode> contextRequest = new AtomicReference<>();
    private final AtomicReference<JsonNode> eventRequest = new AtomicReference<>();
    private final AtomicReference<JsonNode> toolRequest = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        mapper = new ObjectMapper().findAndRegisterModules();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/kclaw/runtime/context", exchange -> {
            contextRequest.set(authenticatedJson(exchange));
            respond(exchange, 200, mapper.writeValueAsBytes(context()));
        });
        server.createContext("/api/kclaw/runtime/events", exchange -> {
            JsonNode request = authenticatedJson(exchange);
            eventRequest.set(request);
            JsonNode event = request.path("event");
            respond(exchange, 200, mapper.writeValueAsBytes(
                    new ProvisionedAgentRuntime.CanonicalEvent(
                            1,
                            Instant.EPOCH,
                            ProvisionedAgentRuntime.EventKind.valueOf(event.path("kind").asText()),
                            ProvisionedAgentRuntime.EventRole.valueOf(event.path("role").asText()),
                            event.path("content").asText(),
                            Map.of(),
                            event.path("idempotencyKey").asText())));
        });
        server.createContext("/api/kclaw/runtime/tool", exchange -> {
            toolRequest.set(authenticatedJson(exchange));
            respond(exchange, 200, mapper.writeValueAsBytes(
                    new ProvisionedAgentRuntime.ToolExecutionResult("{\"success\":true}", REVISION)));
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsServerOwnedBearerAndMutationProofWithoutOwnerOrStorageSelectors() {
        ProvisionedAgentRuntimeHttpClient client = new ProvisionedAgentRuntimeHttpClient(
                mapper,
                new IntegrationControlCredentials(TOKEN, tempDir.toString()),
                () -> "http://127.0.0.1:" + server.getAddress().getPort(),
                HttpClient.newHttpClient());

        ProvisionedAgentRuntime.RuntimeContext prepared = client.prepare(
                new ProvisionedAgentRuntime.PrepareRequest(
                        AGENT_ID, "web:window", "hello"));
        ProvisionedAgentRuntime.CanonicalEvent appended = client.append(
                new ProvisionedAgentRuntime.AppendEventsRequest(
                AGENT_ID,
                "web:window",
                new ProvisionedAgentRuntime.EventDraft(
                        ProvisionedAgentRuntime.EventKind.MESSAGE,
                        ProvisionedAgentRuntime.EventRole.USER,
                        "hello",
                        Map.of(),
                        "turn:11111111-2222-3333-4444-555555555555:user")));
        ProvisionedAgentRuntime.ToolExecutionResult tool = client.executeTool(
                new ProvisionedAgentRuntime.ToolExecutionRequest(
                        AGENT_ID, Map.of("action", "read")));

        assertEquals(AGENT_ID, prepared.provisionedAgentId());
        assertEquals("turn:11111111-2222-3333-4444-555555555555:user",
                appended.idempotencyKey());
        assertEquals(REVISION, tool.graphRevision());
        assertSelectorLimited(contextRequest.get());
        assertSelectorLimited(eventRequest.get());
        assertSelectorLimited(toolRequest.get());
        assertEquals("read", toolRequest.get().path("arguments").path("action").asText());
        assertTrue(eventRequest.get().has("event"));
        assertFalse(eventRequest.get().has("events"));
    }

    @Test
    void maximumAcceptedAppendAndProjectedContextFitHttpCapsAfterJsonEscaping()
            throws Exception {
        Map<String, String> appendMetadata = new LinkedHashMap<>();
        for (int index = 0; index < ProvisionedAgentRuntime.MAX_METADATA_ENTRIES; index++) {
            appendMetadata.put(
                    "k" + index + "\u0000".repeat(
                            ProvisionedAgentRuntime.MAX_METADATA_KEY_BYTES - 2),
                    "\u0000".repeat(ProvisionedAgentRuntime.MAX_METADATA_VALUE_BYTES));
        }
        ProvisionedAgentRuntime.AppendEventsRequest append =
                new ProvisionedAgentRuntime.AppendEventsRequest(
                        AGENT_ID,
                        "\u0000".repeat(ProvisionedAgentRuntime.MAX_CONVERSATION_KEY_BYTES),
                        new ProvisionedAgentRuntime.EventDraft(
                                ProvisionedAgentRuntime.EventKind.MESSAGE,
                                ProvisionedAgentRuntime.EventRole.USER,
                                "\u0000".repeat(ProvisionedAgentRuntime.MAX_EVENT_CONTENT_BYTES),
                                appendMetadata,
                                "k" + "x".repeat(
                                        ProvisionedAgentRuntime.MAX_IDEMPOTENCY_KEY_BYTES - 1)));
        assertTrue(mapper.writeValueAsBytes(append).length
                <= ProvisionedAgentRuntimeHttpClient.MAX_REQUEST_BYTES);

        Map<String, String> contextMetadata = new LinkedHashMap<>();
        for (int index = 0; index < ProvisionedAgentRuntime.MAX_CONTEXT_METADATA_ENTRIES; index++) {
            contextMetadata.put(
                    "m" + index + "\u0000".repeat(
                            ProvisionedAgentRuntime.MAX_METADATA_KEY_BYTES - 2),
                    "\u0000".repeat(ProvisionedAgentRuntime.MAX_CONTEXT_METADATA_VALUE_BYTES));
        }
        List<ProvisionedAgentRuntime.CanonicalEvent> history = new ArrayList<>();
        for (int index = 0; index < ProvisionedAgentRuntime.MAX_CONTEXT_HISTORY_EVENTS; index++) {
            history.add(new ProvisionedAgentRuntime.CanonicalEvent(
                    index + 1L,
                    Instant.EPOCH,
                    ProvisionedAgentRuntime.EventKind.MESSAGE,
                    index % 2 == 0
                            ? ProvisionedAgentRuntime.EventRole.USER
                            : ProvisionedAgentRuntime.EventRole.ASSISTANT,
                    "\u0000".repeat(ProvisionedAgentRuntime.MAX_CONTEXT_EVENT_CONTENT_BYTES),
                    contextMetadata,
                    "history:" + index));
        }
        ProvisionedAgentRuntime.RuntimeContext context = new ProvisionedAgentRuntime.RuntimeContext(
                AGENT_ID,
                "\u0000".repeat(ProvisionedAgentRuntime.MAX_CONVERSATION_KEY_BYTES),
                "\u0000".repeat(ProvisionedAgentRuntime.MAX_AUTOMATIC_CONTEXT_CHARACTERS),
                REVISION,
                history,
                history.size(),
                false,
                new ProvisionedAgentRuntime.ToolDescriptor(
                        "agent_private_graph",
                        "\u0000".repeat(2_048),
                        Map.of("type", "object", "properties", Map.of(
                                "action", Map.of("type", "string"))),
                        true));
        assertTrue(mapper.writeValueAsBytes(context).length
                <= ProvisionedAgentRuntimeHttpClient.MAX_RESPONSE_BYTES);
    }

    @Test
    void refusesToSendTheServerCredentialToRemotePlainHttp() {
        ProvisionedAgentRuntimeHttpClient client = new ProvisionedAgentRuntimeHttpClient(
                mapper,
                new IntegrationControlCredentials(TOKEN, tempDir.toString()),
                () -> "http://admin.example.test:8080",
                HttpClient.newHttpClient());

        ProvisionedAgentRuntime.RuntimeException failure = assertThrows(
                ProvisionedAgentRuntime.RuntimeException.class,
                () -> client.prepare(new ProvisionedAgentRuntime.PrepareRequest(
                        AGENT_ID, "web:window", "hello")));

        assertEquals(503, failure.statusCode());
        assertTrue(failure.getMessage().contains("HTTPS"));
    }

    private JsonNode authenticatedJson(HttpExchange exchange) throws java.io.IOException {
        assertEquals(TOKEN, exchange.getRequestHeaders().getFirst(
                ChannelControlHeaders.TOKEN_HEADER));
        assertEquals("1", exchange.getRequestHeaders().getFirst(
                ChannelControlHeaders.REQUEST_HEADER));
        return mapper.readTree(exchange.getRequestBody());
    }

    private static void assertSelectorLimited(JsonNode request) {
        assertEquals(AGENT_ID, request.path("provisionedAgentId").asText());
        assertFalse(request.has("ownerId"));
        assertFalse(request.has("factSheetId"));
        assertFalse(request.has("knowledgeBase"));
        assertFalse(request.has("path"));
    }

    private static void respond(HttpExchange exchange, int status, byte[] body)
            throws java.io.IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    private static ProvisionedAgentRuntime.RuntimeContext context() {
        return new ProvisionedAgentRuntime.RuntimeContext(
                AGENT_ID,
                "web:window",
                "context",
                REVISION,
                List.of(),
                0,
                false,
                new ProvisionedAgentRuntime.ToolDescriptor(
                        "agent_private_graph",
                        "bound graph",
                        Map.of("type", "object", "properties", Map.of("action", Map.of())),
                        true));
    }
}
