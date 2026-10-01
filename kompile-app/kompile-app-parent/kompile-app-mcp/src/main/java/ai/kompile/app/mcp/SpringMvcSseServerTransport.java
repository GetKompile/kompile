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

import ai.kompile.cli.common.metrics.ToolCallUsage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Spring MVC transport for the Model Context Protocol, serving both HTTP transports.
 *
 * <p><b>SSE (protocol 2024-11-05).</b> {@code GET /mcp/sse} opens one long-lived stream whose
 * first event names the message endpoint. The client POSTs every JSON-RPC message to
 * {@code /mcp/message?sessionId=...} and receives every reply on that stream.</p>
 *
 * <p><b>Streamable HTTP (protocol 2025-03-26).</b> A POSTed {@code initialize} request creates a
 * session, named by the {@code Mcp-Session-Id} response header. Every later POSTed request gets its
 * own SSE stream, keyed by its JSON-RPC id, which carries that request's response and then ends, so
 * requests on one session may overlap. The id stays reserved until its response is sent: a client
 * that reuses an in-flight id is refused instead of being handed another request's answer. POSTed
 * notifications and responses get no stream (the caller answers 202). A GET with the session header
 * opens a server stream; up to {@value #MAX_COMMON_STREAMS} may be open, newest first.</p>
 *
 * <p>Each server-initiated request or notification goes to exactly one stream: the newest server
 * stream, or else an open request stream, because SDK 0.10.0 does not say which request a server
 * message belongs to. Responses never go to a server stream.</p>
 *
 * <p>Streams do not time out by default. An SSE client that reconnects lands in a new, uninitialized
 * session, and a tool that outlives the timeout loses its response, so open streams get a comment
 * every {@link #HEARTBEAT_INTERVAL} instead. A failed heartbeat closes an SSE session or a server
 * stream.</p>
 */
public class SpringMvcSseServerTransport implements McpServerTransportProvider {

    private static final Logger log = LoggerFactory.getLogger(SpringMvcSseServerTransport.class);

    public static final String MESSAGE_EVENT_TYPE = "message";
    public static final String ENDPOINT_EVENT_TYPE = "endpoint";

    /** Server streams kept per Streamable HTTP session; opening one more ends the oldest. */
    static final int MAX_COMMON_STREAMS = 4;

    /** Keep-alive period for open streams, well under common proxy and client idle limits. */
    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private static final String HEARTBEAT_COMMENT = "keepalive";
    private static final String STREAM_OPEN_COMMENT = "stream-open";

    private final ObjectMapper objectMapper;
    private final Supplier<String> baseUrlSupplier;
    private final String messageEndpoint;
    private final String sseEndpoint;
    private final long emitterTimeoutMillis;
    private final boolean redactEndpointLogs;

    // Active sessions mapped by session ID
    private final Map<String, SessionTransport> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean isClosing = new AtomicBoolean(false);
    private final AtomicReference<Disposable> heartbeat = new AtomicReference<>();

    private volatile McpServerSession.Factory sessionFactory;

    /**
     * Create a new Spring MVC SSE transport whose streams never time out.
     *
     * @param objectMapper Jackson ObjectMapper for JSON serialization
     * @param baseUrlSupplier Supplier for the base URL (e.g., returns "http://localhost:8080")
     *                        Using a Supplier allows lazy evaluation after the server has started
     * @param messageEndpoint Path for the message endpoint (e.g., "/mcp/message")
     * @param sseEndpoint Path for the SSE endpoint (e.g., "/mcp/sse")
     */
    public SpringMvcSseServerTransport(ObjectMapper objectMapper, Supplier<String> baseUrlSupplier,
                                        String messageEndpoint, String sseEndpoint) {
        this(objectMapper, baseUrlSupplier, messageEndpoint, sseEndpoint, 0L, false);
    }

    /**
     * Variant for isolated bearer capabilities whose endpoint must not be written to logs.
     *
     * @param emitterTimeoutMillis stream timeout in milliseconds; 0 means streams never time out
     */
    public SpringMvcSseServerTransport(ObjectMapper objectMapper, Supplier<String> baseUrlSupplier,
                                        String messageEndpoint, String sseEndpoint,
                                        long emitterTimeoutMillis, boolean redactEndpointLogs) {
        this.objectMapper = objectMapper;
        this.baseUrlSupplier = baseUrlSupplier;
        this.messageEndpoint = messageEndpoint;
        this.sseEndpoint = sseEndpoint;
        if (emitterTimeoutMillis < 0) {
            throw new IllegalArgumentException("emitterTimeoutMillis must not be negative");
        }
        this.emitterTimeoutMillis = emitterTimeoutMillis;
        this.redactEndpointLogs = redactEndpointLogs;
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

        SseEmitter emitter = new SseEmitter(emitterTimeoutMillis);

        // Attach the MCP session before the session becomes visible to message handlers
        SessionTransport sessionTransport = new SessionTransport(sessionId, emitter);
        McpServerSession.Factory factory = sessionFactory;
        if (factory != null) {
            sessionTransport.setSession(factory.create(sessionTransport));
        }
        register(sessionTransport);

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

        // Send the endpoint event to tell the client where to POST messages
        try {
            String messageUrl = baseUrlSupplier.get() + messageEndpoint + "?sessionId=" + sessionId;
            emitter.send(SseEmitter.event()
                    .name(ENDPOINT_EVENT_TYPE)
                    .data(messageUrl, MediaType.TEXT_PLAIN));
            if (redactEndpointLogs) {
                log.debug("Sent scoped endpoint event to session {} (URL redacted)", sessionId);
            } else {
                log.debug("Sent endpoint event to session {}: {}", sessionId, messageUrl);
            }
        } catch (IOException e) {
            log.error("Failed to send endpoint event to session {}", sessionId, e);
            removeSession(sessionId);
        }

        ensureHeartbeat();
        return emitter;
    }

    /**
     * Handle a message POSTed to the SSE transport's message endpoint. Any reply is sent on the
     * session's SSE stream.
     *
     * @param sessionId the session named in the endpoint event
     * @param messageBody the JSON-RPC message body
     * @return Mono that completes when the message is processed
     * @throws UnknownSessionException when no SSE session has this id
     * @throws IllegalArgumentException when the body is not a single JSON-RPC message
     * @throws IllegalStateException when the session has no MCP server attached
     */
    public Mono<Void> handleMessage(String sessionId, String messageBody) {
        SessionTransport sessionTransport = sessionId == null ? null : sessions.get(sessionId);
        if (sessionTransport == null || !sessionTransport.isLegacy() || sessionTransport.isClosed()) {
            log.warn("Received message for unknown session: {}", sessionId);
            throw new UnknownSessionException(sessionId);
        }
        McpServerSession session = sessionTransport.getSession();
        if (session == null) {
            log.warn("Session {} has no MCP session attached", sessionId);
            throw new IllegalStateException("Session not initialized");
        }
        McpSchema.JSONRPCMessage message = parseClientMessage(messageBody);

        if (message instanceof McpSchema.JSONRPCRequest request) {
            if (McpSchema.METHOD_INITIALIZE.equals(request.method())) {
                sessionTransport.markInitializeReceived();
            } else if (!sessionTransport.initializeReceived()) {
                // SDK 0.10.0 parks this request until an initialize that will never come: the
                // client's SSE stream reconnected into a fresh session. Answer instead of hanging.
                log.warn("MCP {} request on session {} arrived before initialize; the client has to reconnect",
                        request.method(), sessionId);
                return sessionTransport.sendMessage(new McpSchema.JSONRPCResponse(
                        McpSchema.JSONRPC_VERSION, request.id(), null,
                        new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INVALID_REQUEST,
                                "This MCP session was never initialized; reconnect the MCP server", null)));
            }
        }

        log.info("Dispatching MCP message for session {} to server session", sessionId);
        return session.handle(message)
                .doOnSuccess(unused -> log.info("MCP server session completed message for {}", sessionId))
                .doOnError(error -> log.error("MCP server session failed message for {}", sessionId, error));
    }

    /**
     * Check if a session exists.
     */
    public boolean hasSession(String sessionId) {
        return sessionId != null && sessions.containsKey(sessionId);
    }

    /**
     * Open a server stream (the GET stream) for an existing Streamable HTTP session. Server
     * requests and notifications prefer the newest such stream; responses never use it.
     *
     * @throws UnknownSessionException when no Streamable HTTP session has this id
     * @throws IllegalStateException when the transport is closing
     */
    public SseEmitter openStreamableHttpStream(String sessionId) {
        if (isClosing.get()) {
            throw new IllegalStateException("Transport is closing");
        }
        SessionTransport sessionTransport = requireStreamableSession(sessionId);
        SseEmitter stream = new SseEmitter(emitterTimeoutMillis);
        sessionTransport.addCommonStream(stream);
        // Commit the response headers at once: some clients give up on a GET whose headers are late.
        if (!trySend(stream, SseEmitter.event().comment(STREAM_OPEN_COMMENT))) {
            sessionTransport.removeCommonStream(stream);
        }
        ensureHeartbeat();
        log.debug("Opened a server stream for MCP session {}", sessionId);
        return stream;
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
     * Create a Streamable HTTP session from its initialize request (protocol version 2025-03-26).
     * The returned stream carries the initialize response and then ends.
     *
     * @param initialMessageBody the JSON-RPC initialize request
     * @return StreamableHttpResult containing the session ID and the initialize response stream
     * @throws IllegalArgumentException when the body is not a JSON-RPC initialize request
     * @throws IllegalStateException when the transport is closing or has no MCP server attached
     */
    public StreamableHttpResult createStreamableHttpConnection(String initialMessageBody) {
        if (isClosing.get()) {
            throw new IllegalStateException("Transport is closing");
        }
        McpSchema.JSONRPCMessage message = parseClientMessage(initialMessageBody);
        if (!(message instanceof McpSchema.JSONRPCRequest request)
                || !McpSchema.METHOD_INITIALIZE.equals(request.method())) {
            throw new IllegalArgumentException("A Streamable HTTP session must start with an initialize request");
        }
        McpServerSession.Factory factory = sessionFactory;
        if (factory == null) {
            throw new IllegalStateException("No MCP server is attached to this transport");
        }

        String sessionId = UUID.randomUUID().toString();
        SessionTransport sessionTransport = new SessionTransport(sessionId, null);
        sessionTransport.setSession(factory.create(sessionTransport));
        sessionTransport.markInitializeReceived();
        register(sessionTransport);

        SseEmitter stream = openRequestStream(sessionTransport, request);
        dispatchRequest(sessionTransport, request, stream);
        log.debug("Created Streamable HTTP session {}", sessionId);
        return new StreamableHttpResult(sessionId, stream);
    }

    /**
     * Handle a message POSTed to an existing Streamable HTTP session.
     *
     * @param sessionId the Mcp-Session-Id header
     * @param messageBody the JSON-RPC message body
     * @return the request's own response stream, or null for a notification or a response, which
     *         the caller answers with 202 Accepted
     * @throws UnknownSessionException when no Streamable HTTP session has this id
     * @throws DuplicateRequestIdException when a request with the same id is still in flight
     * @throws IllegalArgumentException when the body is not a single JSON-RPC message
     */
    public SseEmitter handleStreamableHttpMessage(String sessionId, String messageBody) {
        SessionTransport sessionTransport = requireStreamableSession(sessionId);
        McpSchema.JSONRPCMessage message = parseClientMessage(messageBody);
        if (message instanceof McpSchema.JSONRPCRequest request) {
            SseEmitter stream = openRequestStream(sessionTransport, request);
            dispatchRequest(sessionTransport, request, stream);
            return stream;
        }
        sessionTransport.getSession().handle(message).subscribe(
                unused -> { },
                error -> log.warn("MCP {} on session {} failed: {}",
                        describe(message), sessionId, error.toString()));
        return null;
    }

    /**
     * True when the body is a JSON-RPC {@code initialize} request, which always starts a new session.
     *
     * @throws IllegalArgumentException when the body is not JSON
     */
    public boolean isInitializeRequest(String messageBody) {
        JsonNode root;
        try {
            root = messageBody == null ? null : objectMapper.readTree(messageBody);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON-RPC message: " + e.getOriginalMessage(), e);
        }
        return root != null
                && root.isObject()
                && McpSchema.METHOD_INITIALIZE.equals(root.path("method").asText())
                && root.hasNonNull("id");
    }

    private SseEmitter openRequestStream(SessionTransport sessionTransport, McpSchema.JSONRPCRequest request) {
        SseEmitter stream = new SseEmitter(emitterTimeoutMillis);
        sessionTransport.claimRequestStream(requestKey(request.id()), request.id(), stream);
        // Commit the response headers at once: a slow tool must not look like a dead server.
        trySend(stream, SseEmitter.event().comment(STREAM_OPEN_COMMENT));
        ensureHeartbeat();
        return stream;
    }

    private void dispatchRequest(SessionTransport sessionTransport, McpSchema.JSONRPCRequest request,
                                 SseEmitter stream) {
        String key = requestKey(request.id());
        sessionTransport.getSession().handle(request).subscribe(
                unused -> { },
                error -> {
                    if (sessionTransport.failRequest(key, stream, request,
                            "The MCP server failed to answer " + request.method())) {
                        log.warn("MCP {} request {} on session {} failed before its response was sent",
                                request.method(), request.id(), sessionTransport.sessionId, error);
                    } else {
                        log.debug("MCP {} request {} on session {} failed after its stream was released: {}",
                                request.method(), request.id(), sessionTransport.sessionId, error.toString());
                    }
                },
                () -> {
                    if (sessionTransport.failRequest(key, stream, request,
                            "The MCP server finished " + request.method() + " without a result")) {
                        log.warn("MCP {} request {} on session {} finished without a response",
                                request.method(), request.id(), sessionTransport.sessionId);
                    }
                });
    }

    private SessionTransport requireStreamableSession(String sessionId) {
        SessionTransport sessionTransport = sessionId == null ? null : sessions.get(sessionId);
        if (sessionTransport == null || sessionTransport.isLegacy() || sessionTransport.isClosed()) {
            throw new UnknownSessionException(sessionId);
        }
        return sessionTransport;
    }

    /** Publish a session; a close() racing the caller's closing check cannot leave it behind. */
    private void register(SessionTransport sessionTransport) {
        sessions.put(sessionTransport.sessionId, sessionTransport);
        if (isClosing.get()) {
            removeSession(sessionTransport.sessionId);
            throw new IllegalStateException("Transport is closing");
        }
    }

    private McpSchema.JSONRPCMessage parseClientMessage(String messageBody) {
        try {
            return parseJsonRpcMessage(messageBody);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON-RPC message: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Parse a JSON-RPC message from a string.
     */
    private McpSchema.JSONRPCMessage parseJsonRpcMessage(String messageBody) throws JsonProcessingException {
        JsonNode jsonNode = messageBody == null ? null : objectMapper.readTree(messageBody);
        if (jsonNode == null || jsonNode.isMissingNode()) {
            throw new IllegalArgumentException("Empty JSON-RPC message");
        }
        if (jsonNode.isArray()) {
            throw new IllegalArgumentException("JSON-RPC batch requests are not supported");
        }
        if (!jsonNode.isObject()) {
            throw new IllegalArgumentException("A JSON-RPC message must be a JSON object");
        }

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
        SessionTransport sessionTransport = sessionId == null ? null : sessions.get(sessionId);
        if (sessionTransport == null) {
            return;
        }
        McpServerSession session = sessionTransport.getSession();
        if (session != null) {
            session.closeGracefully().subscribe(
                v -> log.debug("MCP session {} closed gracefully", sessionId),
                ex -> log.warn("Error closing MCP session {}: {}", sessionId, ex.getMessage())
            );
        }
        removeSession(sessionId);
    }

    /** Notify every session; one session that cannot be reached does not fail the rest. */
    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        List<SessionTransport> targets = List.copyOf(sessions.values());
        if (targets.isEmpty()) {
            log.debug("No active sessions to broadcast to");
            return Mono.empty();
        }

        log.debug("Broadcasting {} to {} active sessions", method, targets.size());

        return Flux.fromIterable(targets)
                .flatMap(target -> {
                    McpServerSession session = target.getSession();
                    if (session == null) {
                        return Mono.empty();
                    }
                    return session.sendNotification(method, params).onErrorResume(error -> {
                        log.debug("Could not notify MCP session {} of {}: {}",
                                target.sessionId, method, error.getMessage());
                        return Mono.empty();
                    });
                })
                .then();
    }

    @Override
    public Mono<Void> closeGracefully() {
        isClosing.set(true);
        stopHeartbeat();

        return Flux.fromIterable(List.copyOf(sessions.values()))
                .flatMap(target -> {
                    McpServerSession session = target.getSession();
                    Mono<Void> closing = session != null ? session.closeGracefully() : target.closeGracefully();
                    return closing.onErrorResume(error -> {
                        log.debug("Error closing MCP session {}: {}", target.sessionId, error.getMessage());
                        return Mono.empty();
                    });
                })
                .then(Mono.fromRunnable(() -> {
                    List.copyOf(sessions.values()).forEach(SessionTransport::close);
                    sessions.clear();
                    log.info("MCP SSE transport closed gracefully");
                }));
    }

    @Override
    public void close() {
        isClosing.set(true);
        stopHeartbeat();
        List.copyOf(sessions.values()).forEach(SessionTransport::close);
        sessions.clear();
        log.info("MCP SSE transport closed");
    }

    /** Start the shared keep-alive ticker; called whenever a stream opens. */
    private void ensureHeartbeat() {
        Disposable current = heartbeat.get();
        if (isClosing.get() || (current != null && !current.isDisposed())) {
            return;
        }
        Disposable started = Flux.interval(HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, Schedulers.boundedElastic())
                .subscribe(tick -> sendHeartbeatsQuietly(),
                        error -> log.warn("MCP SSE heartbeat stopped", error));
        if (!heartbeat.compareAndSet(current, started) || isClosing.get()) {
            started.dispose();
        }
    }

    private void stopHeartbeat() {
        Disposable previous = heartbeat.getAndSet(Disposables.disposed());
        if (previous != null) {
            previous.dispose();
        }
    }

    /** Send one keep-alive comment on every open stream; returns how many streams took it. */
    int sendHeartbeats() {
        int delivered = 0;
        for (SessionTransport sessionTransport : List.copyOf(sessions.values())) {
            delivered += sessionTransport.heartbeat();
        }
        return delivered;
    }

    private void sendHeartbeatsQuietly() {
        try {
            sendHeartbeats();
        } catch (RuntimeException e) {
            log.warn("MCP SSE heartbeat failed", e);
        }
    }

    /**
     * Project a CallToolResult into an equivalent wire node with
     * {@code _meta["ai.kompile/usage"]} appended. Content and isError are preserved;
     * usage metadata is added AFTER measurement (never measured itself).
     */
    private Object usageWireNode(McpSchema.CallToolResult callResult, ToolCallUsage usage) {
        ObjectNode node = objectMapper.createObjectNode();
        var content = node.putArray("content");
        if (callResult.content() != null) {
            for (McpSchema.Content c : callResult.content()) {
                if (c instanceof McpSchema.TextContent tc) {
                    ObjectNode t = content.addObject();
                    t.put("type", "text");
                    t.put("text", tc.text());
                } else {
                    content.add(objectMapper.<JsonNode>valueToTree(c));
                }
            }
        }
        node.put("isError", Boolean.TRUE.equals(callResult.isError()));
        ObjectNode meta = node.putObject("_meta");
        meta.set("ai.kompile/usage", usage.toWireMetaNode(objectMapper));
        return node;
    }

    /** Package-private test bridge over the usage projection. */
    Object usageWireNodeForTest(McpSchema.CallToolResult callResult, ToolCallUsage usage) {
        if (usage == null) {
            return null;
        }
        return usageWireNode(callResult, usage);
    }

    /** SSE requires single-line JSON. */
    private String singleLine(Object value) throws JsonProcessingException {
        return objectMapper.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(value);
    }

    /** Map key for a JSON-RPC id: the number 1 and the string "1" name different requests. */
    static String requestKey(Object requestId) {
        return (requestId instanceof Number ? "n:" : "s:") + requestId;
    }

    private static SseEmitter.SseEventBuilder messageEvent(String json) {
        return SseEmitter.event().name(MESSAGE_EVENT_TYPE).data(json, MediaType.TEXT_PLAIN);
    }

    private static SseEmitter.SseEventBuilder heartbeatEvent() {
        return SseEmitter.event().comment(HEARTBEAT_COMMENT);
    }

    /** Write one event; false when the stream is completed or the client has gone. */
    private static boolean trySend(SseEmitter stream, SseEmitter.SseEventBuilder event) {
        try {
            stream.send(event);
            return true;
        } catch (IOException | RuntimeException e) {
            log.debug("Could not write to an MCP SSE stream: {}", e.getMessage());
            return false;
        }
    }

    private static void completeQuietly(SseEmitter stream) {
        try {
            stream.complete();
        } catch (RuntimeException e) {
            log.debug("Could not complete an MCP SSE stream: {}", e.getMessage());
        }
    }

    private static String describe(McpSchema.JSONRPCMessage message) {
        if (message instanceof McpSchema.JSONRPCRequest request) {
            return "request " + request.method();
        }
        if (message instanceof McpSchema.JSONRPCNotification notification) {
            return "notification " + notification.method();
        }
        return "response";
    }

    /** No live session of the requested transport has this id. */
    public static final class UnknownSessionException extends IllegalArgumentException {
        public UnknownSessionException(String sessionId) {
            super("Unknown MCP session: " + sessionId);
        }
    }

    /** The client reused the id of a request that is still in flight on the same session. */
    public static final class DuplicateRequestIdException extends IllegalStateException {
        public DuplicateRequestIdException(Object requestId) {
            super("MCP request id " + requestId + " is already in flight on this session");
        }
    }

    /**
     * One MCP session: an SSE session with its single stream, or a Streamable HTTP session with
     * its request streams and server streams.
     */
    private final class SessionTransport implements McpServerTransport {
        private final String sessionId;
        /** The SSE transport's only stream; null for a Streamable HTTP session. */
        private final SseEmitter legacyStream;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        /** Request streams by {@link #requestKey}; an entry lives until its response is sent. */
        private final Map<String, SseEmitter> requestStreams = new ConcurrentHashMap<>();
        /** Server (GET) streams, newest first. */
        private final Deque<SseEmitter> commonStreams = new ConcurrentLinkedDeque<>();
        private volatile McpServerSession session;
        private volatile boolean initializeReceived;

        SessionTransport(String sessionId, SseEmitter legacyStream) {
            this.sessionId = sessionId;
            this.legacyStream = legacyStream;
        }

        void setSession(McpServerSession session) {
            this.session = session;
        }

        McpServerSession getSession() {
            return session;
        }

        boolean isLegacy() {
            return legacyStream != null;
        }

        boolean isClosed() {
            return closed.get();
        }

        void markInitializeReceived() {
            initializeReceived = true;
        }

        boolean initializeReceived() {
            return initializeReceived;
        }

        /** Reserve a request id for its stream until the response is sent. */
        void claimRequestStream(String key, Object requestId, SseEmitter stream) {
            if (requestStreams.putIfAbsent(key, stream) != null) {
                throw new DuplicateRequestIdException(requestId);
            }
            if (closed.get()) {
                requestStreams.remove(key, stream);
                throw new UnknownSessionException(sessionId);
            }
        }

        /**
         * Answer a request whose handling ended without a response, so its client is not left
         * waiting. False when the response was already sent or the session closed.
         */
        boolean failRequest(String key, SseEmitter stream, McpSchema.JSONRPCRequest request, String reason) {
            if (!requestStreams.remove(key, stream)) {
                return false;
            }
            try {
                String json = serialize(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
                        new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR, reason, null)));
                trySend(stream, messageEvent(json));
            } catch (RuntimeException e) {
                log.debug("Could not report the failure of MCP request {} on session {}: {}",
                        request.id(), sessionId, e.toString());
            } finally {
                completeQuietly(stream);
            }
            return true;
        }

        void addCommonStream(SseEmitter stream) {
            commonStreams.addFirst(stream);
            stream.onCompletion(() -> commonStreams.remove(stream));
            stream.onTimeout(() -> commonStreams.remove(stream));
            stream.onError(ignored -> commonStreams.remove(stream));
            if (closed.get()) {
                commonStreams.remove(stream);
                throw new UnknownSessionException(sessionId);
            }
            while (commonStreams.size() > MAX_COMMON_STREAMS) {
                SseEmitter oldest = commonStreams.pollLast();
                if (oldest == null) {
                    break;
                }
                completeQuietly(oldest);
            }
        }

        void removeCommonStream(SseEmitter stream) {
            commonStreams.remove(stream);
            completeQuietly(stream);
        }

        /** Send one keep-alive comment on every open stream; returns how many took it. */
        int heartbeat() {
            if (closed.get()) {
                return 0;
            }
            if (legacyStream != null) {
                if (trySend(legacyStream, heartbeatEvent())) {
                    return 1;
                }
                log.debug("The SSE stream of MCP session {} is gone; closing the session", sessionId);
                removeSession(sessionId);
                return 0;
            }
            int delivered = 0;
            for (SseEmitter stream : commonStreams) {
                if (trySend(stream, heartbeatEvent())) {
                    delivered++;
                } else {
                    removeCommonStream(stream);
                }
            }
            // A dead request stream stays reserved for its response; only live ones count.
            for (SseEmitter stream : requestStreams.values()) {
                if (trySend(stream, heartbeatEvent())) {
                    delivered++;
                }
            }
            return delivered;
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.fromRunnable(() -> deliver(message));
        }

        private void deliver(McpSchema.JSONRPCMessage message) {
            // Take the usage even when closed, so it cannot leak into a later response on this thread.
            McpSchema.JSONRPCMessage outgoing = withUsage(message);
            if (closed.get()) {
                throw new IllegalStateException("MCP session " + sessionId + " is closed");
            }
            String json = serialize(outgoing);
            if (legacyStream != null) {
                if (!trySend(legacyStream, messageEvent(json))) {
                    removeSession(sessionId);
                    throw new IllegalStateException("The SSE stream of MCP session " + sessionId + " is closed");
                }
                log.info("Sent MCP SSE message for session {} ({} bytes)", sessionId, json.length());
            } else if (outgoing instanceof McpSchema.JSONRPCResponse response) {
                deliverResponse(response, json);
            } else {
                deliverServerMessage(outgoing, json);
            }
        }

        /** A response goes to its own request's stream, which then ends. */
        private void deliverResponse(McpSchema.JSONRPCResponse response, String json) {
            SseEmitter stream = requestStreams.remove(requestKey(response.id()));
            if (stream == null) {
                log.warn("Dropped the MCP response to request {} on session {}: its request stream is gone",
                        response.id(), sessionId);
                return;
            }
            try {
                if (trySend(stream, messageEvent(json))) {
                    log.info("Sent MCP response to request {} on session {} ({} bytes)",
                            response.id(), sessionId, json.length());
                } else {
                    log.warn("Dropped the MCP response to request {} on session {}: the client closed its request stream",
                            response.id(), sessionId);
                }
            } finally {
                completeQuietly(stream);
            }
        }

        /** A server request or notification goes to exactly one stream, never to all of them. */
        private void deliverServerMessage(McpSchema.JSONRPCMessage message, String json) {
            for (SseEmitter stream : commonStreams) {
                if (trySend(stream, messageEvent(json))) {
                    log.debug("Sent MCP {} on a server stream of session {}", describe(message), sessionId);
                    return;
                }
                removeCommonStream(stream);
            }
            for (SseEmitter stream : requestStreams.values()) {
                if (trySend(stream, messageEvent(json))) {
                    log.debug("Sent MCP {} on a request stream of session {}", describe(message), sessionId);
                    return;
                }
            }
            if (message instanceof McpSchema.JSONRPCRequest request) {
                // Fail now rather than let the SDK wait out its request timeout.
                throw new IllegalStateException(
                        "No open stream on MCP session " + sessionId + " to send " + request.method());
            }
            log.debug("Dropped MCP {} for session {}: no open stream", describe(message), sessionId);
        }

        private McpSchema.JSONRPCMessage withUsage(McpSchema.JSONRPCMessage message) {
            // Usage projection (Task 4): when a tool handler finalized usage on
            // THIS thread, substitute the CallToolResult with an equivalent node
            // carrying _meta["ai.kompile/usage"] before serialization. SDK 0.10.0
            // CallToolResult has no _meta; JSONRPCResponse.result() is Object so
            // the substitution serializes unchanged (Task 0 smoke-verified).
            if (message instanceof McpSchema.JSONRPCResponse response
                    && response.result() instanceof McpSchema.CallToolResult callResult) {
                ToolCallUsage usage = McpUsageWireContext.take();
                if (usage != null) {
                    try {
                        return new McpSchema.JSONRPCResponse(response.jsonrpc(), response.id(),
                                usageWireNode(callResult, usage), response.error());
                    } catch (RuntimeException e) {
                        log.warn("Sending the MCP response to request {} on session {} without usage metadata: {}",
                                response.id(), sessionId, e.toString());
                    }
                }
            }
            return message;
        }

        private String serialize(McpSchema.JSONRPCMessage message) {
            try {
                return singleLine(message);
            } catch (JsonProcessingException e) {
                if (!(message instanceof McpSchema.JSONRPCResponse response)) {
                    throw new IllegalStateException("Failed to serialize MCP " + describe(message), e);
                }
                // The client is waiting on this id: answer with an error rather than nothing.
                log.error("Failed to serialize the MCP response to request {} on session {}",
                        response.id(), sessionId, e);
                try {
                    return singleLine(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, response.id(), null,
                            new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
                                    "The MCP server could not serialize its response", null)));
                } catch (JsonProcessingException unrecoverable) {
                    throw new IllegalStateException("Failed to serialize an MCP error response", unrecoverable);
                }
            }
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
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            sessions.remove(sessionId, this);
            List<SseEmitter> pending = List.copyOf(requestStreams.values());
            requestStreams.clear();
            pending.forEach(SpringMvcSseServerTransport::completeQuietly);
            SseEmitter common;
            while ((common = commonStreams.pollFirst()) != null) {
                completeQuietly(common);
            }
            if (legacyStream != null) {
                completeQuietly(legacyStream);
            }
        }
    }
}
