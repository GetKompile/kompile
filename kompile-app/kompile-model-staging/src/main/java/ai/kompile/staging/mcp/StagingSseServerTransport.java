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

package ai.kompile.staging.mcp;

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
 * Spring MVC-based SSE Server Transport for the staging MCP server.
 */
public class StagingSseServerTransport implements McpServerTransportProvider {

    private static final Logger log = LoggerFactory.getLogger(StagingSseServerTransport.class);

    public static final String MESSAGE_EVENT_TYPE = "message";
    public static final String ENDPOINT_EVENT_TYPE = "endpoint";

    private final ObjectMapper objectMapper;
    private final Supplier<String> baseUrlSupplier;
    private final String messageEndpoint;
    private final String sseEndpoint;

    private final Map<String, SessionTransport> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean isClosing = new AtomicBoolean(false);
    private McpServerSession.Factory sessionFactory;

    public StagingSseServerTransport(ObjectMapper objectMapper, Supplier<String> baseUrlSupplier,
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

    public SseEmitter createConnection() {
        if (isClosing.get()) {
            throw new IllegalStateException("Transport is closing");
        }

        String sessionId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(300000L);

        SessionTransport sessionTransport = new SessionTransport(sessionId, emitter, objectMapper);
        sessions.put(sessionId, sessionTransport);

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

        if (sessionFactory != null) {
            McpServerSession session = sessionFactory.create(sessionTransport);
            sessionTransport.setSession(session);
        }

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

    public Mono<Void> handleMessage(String sessionId, String messageBody) {
        SessionTransport sessionTransport = sessions.get(sessionId);
        if (sessionTransport == null) {
            return Mono.error(new IllegalArgumentException("Unknown session: " + sessionId));
        }

        try {
            McpSchema.JSONRPCMessage message = parseJsonRpcMessage(messageBody);
            McpServerSession session = sessionTransport.getSession();
            if (session != null) {
                return session.handle(message);
            } else {
                return Mono.error(new IllegalStateException("Session not initialized"));
            }
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }

    public boolean hasSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    public int getSessionCount() {
        return sessions.size();
    }

    public record StreamableHttpResult(String sessionId, SseEmitter emitter) {}

    public StreamableHttpResult createStreamableHttpConnection(String initialMessageBody) {
        if (isClosing.get()) {
            throw new IllegalStateException("Transport is closing");
        }

        String sessionId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(300000L);

        SessionTransport sessionTransport = new SessionTransport(sessionId, emitter, objectMapper);
        sessionTransport.setStreamableHttp(true);
        sessions.put(sessionId, sessionTransport);

        emitter.onCompletion(() -> log.debug("Streamable HTTP connection completed for session: {}", sessionId));
        emitter.onTimeout(() -> { log.debug("Streamable HTTP connection timed out: {}", sessionId); removeSession(sessionId); });
        emitter.onError(ex -> { log.debug("Streamable HTTP error for session {}: {}", sessionId, ex.getMessage()); removeSession(sessionId); });

        if (sessionFactory != null) {
            McpServerSession session = sessionFactory.create(sessionTransport);
            sessionTransport.setSession(session);
        }

        sessionTransport.setResponseEmitter(emitter);

        try {
            McpSchema.JSONRPCMessage message = parseJsonRpcMessage(initialMessageBody);
            McpServerSession session = sessionTransport.getSession();
            if (session != null) {
                session.handle(message).subscribe(
                    v -> log.debug("Initial message processed for session {}", sessionId),
                    ex -> log.error("Error processing initial message for session {}: {}", sessionId, ex.getMessage())
                );
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse initial message for session {}: {}", sessionId, e.getMessage());
            try { emitter.completeWithError(e); } catch (Exception ignored) {}
        }

        return new StreamableHttpResult(sessionId, emitter);
    }

    public SseEmitter handleStreamableHttpMessage(String sessionId, String messageBody) {
        SessionTransport sessionTransport = sessions.get(sessionId);
        if (sessionTransport == null) {
            throw new IllegalArgumentException("Unknown session: " + sessionId);
        }

        SseEmitter responseEmitter = new SseEmitter(300000L);

        try {
            McpSchema.JSONRPCMessage message = parseJsonRpcMessage(messageBody);
            boolean isNotification = message instanceof McpSchema.JSONRPCNotification;

            if (isNotification) {
                McpServerSession session = sessionTransport.getSession();
                if (session != null) {
                    session.handle(message).subscribe(
                        v -> {}, ex -> log.error("Error processing notification: {}", ex.getMessage())
                    );
                }
                responseEmitter.complete();
                return responseEmitter;
            }

            sessionTransport.setResponseEmitter(responseEmitter);

            McpServerSession session = sessionTransport.getSession();
            if (session != null) {
                session.handle(message).subscribe(
                    v -> sessionTransport.setResponseEmitter(null),
                    ex -> {
                        sessionTransport.setResponseEmitter(null);
                        try { responseEmitter.completeWithError(ex); } catch (Exception ignored) {}
                    }
                );
            }
        } catch (JsonProcessingException e) {
            sessionTransport.setResponseEmitter(null);
            try { responseEmitter.completeWithError(e); } catch (Exception ignored) {}
            throw new RuntimeException("Failed to parse message", e);
        }

        return responseEmitter;
    }

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

    private McpSchema.JSONRPCMessage parseJsonRpcMessage(String messageBody) throws JsonProcessingException {
        JsonNode jsonNode = objectMapper.readTree(messageBody);
        boolean hasMethod = jsonNode.has("method") && !jsonNode.get("method").isNull();
        boolean hasId = jsonNode.has("id") && !jsonNode.get("id").isNull();

        if (hasMethod && hasId) {
            return objectMapper.treeToValue(jsonNode, McpSchema.JSONRPCRequest.class);
        } else if (hasMethod) {
            return objectMapper.treeToValue(jsonNode, McpSchema.JSONRPCNotification.class);
        } else if (hasId) {
            return objectMapper.treeToValue(jsonNode, McpSchema.JSONRPCResponse.class);
        } else {
            throw new IllegalArgumentException("Invalid JSON-RPC message: missing both 'method' and 'id' fields");
        }
    }

    private void removeSession(String sessionId) {
        SessionTransport removed = sessions.remove(sessionId);
        if (removed != null) {
            removed.close();
        }
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        if (sessions.isEmpty()) return Mono.empty();
        return Mono.when(
            sessions.values().stream()
                .map(s -> s.getSession() != null ? s.getSession().sendNotification(method, params) : Mono.empty())
                .toList()
        );
    }

    @Override
    public Mono<Void> closeGracefully() {
        isClosing.set(true);
        return Mono.when(
            sessions.values().stream()
                .map(s -> s.getSession() != null ? s.getSession().closeGracefully() : Mono.empty())
                .toList()
        ).then(Mono.fromRunnable(() -> {
            sessions.values().forEach(SessionTransport::close);
            sessions.clear();
        }));
    }

    @Override
    public void close() {
        isClosing.set(true);
        sessions.values().forEach(SessionTransport::close);
        sessions.clear();
    }

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

        void setSession(McpServerSession session) { this.session = session; }
        McpServerSession getSession() { return session; }
        void setStreamableHttp(boolean streamableHttp) { this.streamableHttp = streamableHttp; }
        void setResponseEmitter(SseEmitter responseEmitter) { this.responseEmitter = responseEmitter; }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            if (closed.get()) return Mono.error(new IllegalStateException("Transport is closed"));
            return Mono.fromRunnable(() -> {
                try {
                    String json = objectMapper.writer()
                            .without(SerializationFeature.INDENT_OUTPUT)
                            .writeValueAsString(message);
                    SseEmitter targetEmitter = responseEmitter != null ? responseEmitter : emitter;
                    targetEmitter.send(SseEmitter.event()
                            .name(MESSAGE_EVENT_TYPE)
                            .data(json, MediaType.TEXT_PLAIN));
                    if (responseEmitter != null) {
                        try { responseEmitter.complete(); } catch (Exception ignored) {}
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to send message", e);
                }
            });
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
            return objectMapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() { return Mono.fromRunnable(this::close); }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        }
    }
}
