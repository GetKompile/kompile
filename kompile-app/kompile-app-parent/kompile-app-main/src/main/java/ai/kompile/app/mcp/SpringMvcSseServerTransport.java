/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Spring MVC-based SSE Server Transport for MCP.
 *
 * This implementation uses Spring's SseEmitter to provide SSE transport for the
 * Model Context Protocol, supporting both:
 *
 * 1. SSE Transport (protocol version 2024-11-05):
 *    - GET /mcp/sse - Establishes an SSE connection
 *    - POST /mcp/message - Receives JSON-RPC messages
 *
 * 2. Streamable HTTP Transport (protocol version 2025-03-26):
 *    - POST /mcp/sse - Handles JSON-RPC messages directly with SSE response
 *    - DELETE /mcp/sse - Terminates sessions
 *    - Uses Mcp-Session-Id header for session management
 */
public class SpringMvcSseServerTransport implements McpServerTransportProvider {

    private static final Logger log = LoggerFactory.getLogger(SpringMvcSseServerTransport.class);

    public static final String MESSAGE_EVENT_TYPE = "message";
    public static final String ENDPOINT_EVENT_TYPE = "endpoint";

    private final ObjectMapper objectMapper;
    private final Supplier<String> baseUrlSupplier;
    private final String messageEndpoint;
    private final String sseEndpoint;

    // Active sessions mapped by session ID
    private final Map<String, SessionTransport> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean isClosing = new AtomicBoolean(false);

    private McpServerSession.Factory sessionFactory;

    /**
     * Create a new Spring MVC SSE transport.
     *
     * @param objectMapper Jackson ObjectMapper for JSON serialization
     * @param baseUrlSupplier Supplier for the base URL (e.g., returns "http://localhost:8080")
     *                        Using a Supplier allows lazy evaluation after the server has started
     * @param messageEndpoint Path for the message endpoint (e.g., "/mcp/message")
     * @param sseEndpoint Path for the SSE endpoint (e.g., "/mcp/sse")
     */
    public SpringMvcSseServerTransport(ObjectMapper objectMapper, Supplier<String> baseUrlSupplier,
                                        String messageEndpoint, String sseEndpoint) {
        this.objectMapper = objectMapper;
        this.baseUrlSupplier = baseUrlSupplier;
        this.messageEndpoint = messageEndpoint;
        this.sseEndpoint = sseEndpoint;
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    /**
     * Create a new SSE connection for a client.
     *
     * @return SseEmitter for the connection
     */
    public SseEmitter createConnection() {
        if (isClosing.get()) {
            throw new IllegalStateException("Transport is closing");
        }

        String sessionId = UUID.randomUUID().toString();

        // Create SSE emitter with 5 minute timeout
        SseEmitter emitter = new SseEmitter(300000L);

        // Create the session transport
        SessionTransport sessionTransport = new SessionTransport(sessionId, emitter, objectMapper);
        sessions.put(sessionId, sessionTransport);

        // Set up emitter callbacks
        emitter.onCompletion(() -> {
            log.debug("SSE connection completed for session: {}", sessionId);
            removeSession(sessionId);
        });

        emitter.onTimeout(() -> {
            log.debug("SSE connection timed out for session: {}", sessionId);
            removeSession(sessionId);
        });

        emitter.onError(ex -> {
            log.debug("SSE connection error for session {}: {}", sessionId, ex.getMessage());
            removeSession(sessionId);
        });

        // Create the MCP session
        if (sessionFactory != null) {
            McpServerSession session = sessionFactory.create(sessionTransport);
            sessionTransport.setSession(session);
        }

        // Send the endpoint event to tell the client where to POST messages
        try {
            String messageUrl = baseUrlSupplier.get() + messageEndpoint + "?sessionId=" + sessionId;
            emitter.send(SseEmitter.event()
                    .name(ENDPOINT_EVENT_TYPE)
                    .data(messageUrl, MediaType.TEXT_PLAIN));
            log.debug("Sent endpoint event to session {}: {}", sessionId, messageUrl);
        } catch (IOException e) {
            log.error("Failed to send endpoint event to session {}", sessionId, e);
            removeSession(sessionId);
        }

        return emitter;
    }

    /**
     * Handle an incoming message from a client.
     *
     * @param sessionId The session ID
     * @param messageBody The JSON-RPC message body
     * @return Mono that completes when the message is processed
     */
    public Mono<Void> handleMessage(String sessionId, String messageBody) {
        SessionTransport sessionTransport = sessions.get(sessionId);
        if (sessionTransport == null) {
            log.warn("Received message for unknown session: {}", sessionId);
            return Mono.error(new IllegalArgumentException("Unknown session: " + sessionId));
        }

        try {
            McpSchema.JSONRPCMessage message = parseJsonRpcMessage(messageBody);

            McpServerSession session = sessionTransport.getSession();
            if (session != null) {
                return session.handle(message);
            } else {
                log.warn("Session {} has no MCP session attached", sessionId);
                return Mono.error(new IllegalStateException("Session not initialized"));
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse message for session {}", sessionId, e);
            return Mono.error(e);
        }
    }

    /**
     * Check if a session exists.
     */
    public boolean hasSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /**
     * Get the number of active sessions.
     */
    public int getSessionCount() {
        return sessions.size();
    }

    /**
     * Result of creating a Streamable HTTP connection.
     */
    public record StreamableHttpResult(String sessionId, SseEmitter emitter) {}

    /**
     * Create a new Streamable HTTP connection and process the initial message.
     * Used for the Streamable HTTP transport (protocol version 2025-03-26).
     *
     * @param initialMessageBody The JSON-RPC message body (typically an initialize request)
     * @return StreamableHttpResult containing the session ID and SseEmitter
     */
    public StreamableHttpResult createStreamableHttpConnection(String initialMessageBody) {
        if (isClosing.get()) {
            throw new IllegalStateException("Transport is closing");
        }

        String sessionId = UUID.randomUUID().toString();

        // Create SSE emitter with 5 minute timeout
        SseEmitter emitter = new SseEmitter(300000L);

        // Create the session transport with Streamable HTTP mode
        SessionTransport sessionTransport = new SessionTransport(sessionId, emitter, objectMapper);
        sessionTransport.setStreamableHttp(true);
        sessions.put(sessionId, sessionTransport);

        // Set up emitter callbacks
        emitter.onCompletion(() -> {
            log.debug("Streamable HTTP connection completed for session: {}", sessionId);
            removeSession(sessionId);
        });

        emitter.onTimeout(() -> {
            log.debug("Streamable HTTP connection timed out for session: {}", sessionId);
            removeSession(sessionId);
        });

        emitter.onError(ex -> {
            log.debug("Streamable HTTP connection error for session {}: {}", sessionId, ex.getMessage());
            removeSession(sessionId);
        });

        // Create the MCP session
        if (sessionFactory != null) {
            McpServerSession session = sessionFactory.create(sessionTransport);
            sessionTransport.setSession(session);
        }

        // Set this emitter as the response emitter
        sessionTransport.setResponseEmitter(emitter);

        // Process the initial message
        try {
            McpSchema.JSONRPCMessage message = parseJsonRpcMessage(initialMessageBody);
            McpServerSession session = sessionTransport.getSession();
            if (session != null) {
                session.handle(message).subscribe(
                    v -> log.debug("Initial message processed for session {}", sessionId),
                    ex -> log.error("Error processing initial message for session {}", sessionId, ex)
                );
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse initial message for session {}", sessionId, e);
            try {
                emitter.completeWithError(e);
            } catch (Exception ex) {
                log.debug("Failed to signal parse error on SSE emitter for session {}: {}", sessionId, ex.getMessage());
            }
        }

        return new StreamableHttpResult(sessionId, emitter);
    }

    /**
     * Handle a Streamable HTTP message for an existing session.
     *
     * @param sessionId The session ID
     * @param messageBody The JSON-RPC message body
     * @return SseEmitter for streaming the response
     */
    public SseEmitter handleStreamableHttpMessage(String sessionId, String messageBody) {
        SessionTransport sessionTransport = sessions.get(sessionId);
        if (sessionTransport == null) {
            throw new IllegalArgumentException("Unknown session: " + sessionId);
        }

        // Each request gets its own emitter for the response
        SseEmitter responseEmitter = new SseEmitter(300000L);

        try {
            McpSchema.JSONRPCMessage message = parseJsonRpcMessage(messageBody);

            // Check if this is a notification (no response expected)
            boolean isNotification = message instanceof McpSchema.JSONRPCNotification;

            if (isNotification) {
                McpServerSession session = sessionTransport.getSession();
                if (session != null) {
                    session.handle(message).subscribe(
                        v -> log.debug("Notification processed for session {}", sessionId),
                        ex -> log.error("Error processing notification for session {}: {}", sessionId, ex.getMessage())
                    );
                }
                responseEmitter.complete();
                return responseEmitter;
            }

            // Set the response emitter for this request
            sessionTransport.setResponseEmitter(responseEmitter);

            McpServerSession session = sessionTransport.getSession();
            if (session != null) {
                session.handle(message).subscribe(
                    v -> {
                        log.debug("Message processed for session {}", sessionId);
                        sessionTransport.setResponseEmitter(null);
                    },
                    ex -> {
                        log.error("Error processing message for session {}: {}", sessionId, ex.getMessage());
                        sessionTransport.setResponseEmitter(null);
                        try {
                            responseEmitter.completeWithError(ex);
                        } catch (Exception signalEx) {
                            log.debug("Failed to signal processing error on SSE response emitter for session {}: {}", sessionId, signalEx.getMessage());
                        }
                    }
                );
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse message for session {}", sessionId, e);
            sessionTransport.setResponseEmitter(null);
            try {
                responseEmitter.completeWithError(e);
            } catch (Exception signalEx) {
                log.debug("Failed to signal parse error on SSE response emitter for session {}: {}", sessionId, signalEx.getMessage());
            }
            throw new RuntimeException("Failed to parse message", e);
        }

        return responseEmitter;
    }

    /**
     * Parse a JSON-RPC message from a string.
     */
    private McpSchema.JSONRPCMessage parseJsonRpcMessage(String messageBody) throws JsonProcessingException {
        JsonNode jsonNode = objectMapper.readTree(messageBody);

        boolean hasMethod = jsonNode.has("method") && !jsonNode.get("method").isNull();
        boolean hasId = jsonNode.has("id") && !jsonNode.get("id").isNull();

        if (hasMethod && hasId) {
            log.debug("Parsed JSONRPCRequest: method={}", jsonNode.get("method").asText());
            return objectMapper.treeToValue(jsonNode, McpSchema.JSONRPCRequest.class);
        } else if (hasMethod) {
            log.debug("Parsed JSONRPCNotification: method={}", jsonNode.get("method").asText());
            return objectMapper.treeToValue(jsonNode, McpSchema.JSONRPCNotification.class);
        } else if (hasId) {
            log.debug("Parsed JSONRPCResponse: id={}", jsonNode.get("id"));
            return objectMapper.treeToValue(jsonNode, McpSchema.JSONRPCResponse.class);
        } else {
            throw new IllegalArgumentException("Invalid JSON-RPC message: missing both 'method' and 'id' fields");
        }
    }

    private void removeSession(String sessionId) {
        SessionTransport removed = sessions.remove(sessionId);
        if (removed != null) {
            removed.close();
            log.debug("Removed session: {}", sessionId);
        }
    }

    /**
     * Terminate a session explicitly.
     */
    public void terminateSession(String sessionId) {
        SessionTransport sessionTransport = sessions.get(sessionId);
        if (sessionTransport != null) {
            McpServerSession session = sessionTransport.getSession();
            if (session != null) {
                session.closeGracefully().subscribe(
                    v -> log.debug("MCP session {} closed gracefully", sessionId),
                    ex -> log.warn("Error closing MCP session {}: {}", sessionId, ex.getMessage())
                );
            }
            removeSession(sessionId);
        }
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        if (sessions.isEmpty()) {
            log.debug("No active sessions to broadcast to");
            return Mono.empty();
        }

        log.debug("Broadcasting {} to {} active sessions", method, sessions.size());

        return Mono.when(
            sessions.values().stream()
                .map(session -> {
                    if (session.getSession() != null) {
                        return session.getSession().sendNotification(method, params);
                    }
                    return Mono.empty();
                })
                .toList()
        );
    }

    @Override
    public Mono<Void> closeGracefully() {
        isClosing.set(true);

        return Mono.when(
            sessions.values().stream()
                .map(session -> {
                    if (session.getSession() != null) {
                        return session.getSession().closeGracefully();
                    }
                    return Mono.empty();
                })
                .toList()
        ).then(Mono.fromRunnable(() -> {
            sessions.values().forEach(SessionTransport::close);
            sessions.clear();
            log.info("MCP SSE transport closed gracefully");
        }));
    }

    @Override
    public void close() {
        isClosing.set(true);
        sessions.values().forEach(SessionTransport::close);
        sessions.clear();
        log.info("MCP SSE transport closed");
    }

    /**
     * Inner class representing a single session's transport.
     */
    private static class SessionTransport implements McpServerTransport {
        private final String sessionId;
        private final SseEmitter emitter;
        private final ObjectMapper objectMapper;
        private McpServerSession session;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private boolean streamableHttp = false;
        private volatile SseEmitter responseEmitter = null;

        SessionTransport(String sessionId, SseEmitter emitter, ObjectMapper objectMapper) {
            this.sessionId = sessionId;
            this.emitter = emitter;
            this.objectMapper = objectMapper;
        }

        void setSession(McpServerSession session) {
            this.session = session;
        }

        McpServerSession getSession() {
            return session;
        }

        void setStreamableHttp(boolean streamableHttp) {
            this.streamableHttp = streamableHttp;
        }

        void setResponseEmitter(SseEmitter responseEmitter) {
            this.responseEmitter = responseEmitter;
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            if (closed.get()) {
                return Mono.error(new IllegalStateException("Transport is closed"));
            }

            return Mono.fromRunnable(() -> {
                try {
                    // SSE requires single-line JSON
                    String json = objectMapper.writer()
                            .without(SerializationFeature.INDENT_OUTPUT)
                            .writeValueAsString(message);

                    SseEmitter targetEmitter = responseEmitter != null ? responseEmitter : emitter;
                    targetEmitter.send(SseEmitter.event()
                            .name(MESSAGE_EVENT_TYPE)
                            .data(json, MediaType.TEXT_PLAIN));
                    log.trace("Sent message to session {}: {}", sessionId, json);

                    // For Streamable HTTP, complete the response emitter after sending
                    if (responseEmitter != null) {
                        try {
                            responseEmitter.complete();
                        } catch (Exception e) {
                            log.debug("Error completing response emitter for session {}: {}", sessionId, e.getMessage());
                        }
                    }
                } catch (IOException e) {
                    log.error("Failed to send message to session {}", sessionId, e);
                    throw new RuntimeException("Failed to send message", e);
                }
            });
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
            return objectMapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(this::close);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    emitter.complete();
                } catch (Exception e) {
                    log.debug("Error completing emitter for session {}: {}", sessionId, e.getMessage());
                }
            }
        }
    }
}
