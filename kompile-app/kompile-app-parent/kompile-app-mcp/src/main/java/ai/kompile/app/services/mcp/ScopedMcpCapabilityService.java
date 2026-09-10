/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.services.mcp;

import ai.kompile.app.mcp.SpringMvcSseServerTransport;
import ai.kompile.app.services.ServerPortService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.PreDestroy;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Issues short-lived, bearer-URL MCP capabilities backed by an isolated one-tool server.
 *
 * <p>The URL token is the only transport authority. Tool executors are trusted server-side
 * closures, so clients cannot select an owner, graph, storage path, fact sheet, or knowledge
 * base. Revocation removes the transport before releasing the closure.</p>
 */
public final class ScopedMcpCapabilityService implements AutoCloseable {

    public static final int MAX_ACTIVE_CAPABILITIES = 256;
    public static final int MAX_SCHEMA_BYTES = 64 * 1024;
    public static final int MAX_TOOL_RESULT_CHARACTERS = 64 * 1024;
    public static final Duration MAX_LIFETIME = Duration.ofHours(2);

    private static final int TOKEN_BYTES = 32;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Set<String> FORBIDDEN_SELECTOR_NAMES = Set.of(
            "owner", "ownerid", "agent", "agentid", "provisionedagentid",
            "graph", "graphid", "factsheet", "factsheetid", "knowledgebase",
            "knowledgebaseid", "path", "storagepath");

    private final ObjectMapper mapper;
    private final ServerPortService serverPortService;
    private final Clock clock;
    private final SecureRandom random;
    private final Map<String, Binding> bindings = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ScopedMcpCapabilityService(
            ObjectMapper mapper,
            ServerPortService serverPortService) {
        this(mapper, serverPortService, Clock.systemUTC(), new SecureRandom());
    }

    ScopedMcpCapabilityService(
            ObjectMapper mapper,
            ServerPortService serverPortService,
            Clock clock,
            SecureRandom random) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.serverPortService = Objects.requireNonNull(serverPortService, "serverPortService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.reaper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "scoped-mcp-capability-reaper");
            thread.setDaemon(true);
            return thread;
        });
        this.reaper.scheduleWithFixedDelay(this::pruneExpired, 1, 1, TimeUnit.MINUTES);
    }

    /** Issue one isolated MCP endpoint. Closing the handle revokes it immediately. */
    public synchronized Capability issue(ScopedTool tool, Duration lifetime) {
        Objects.requireNonNull(tool, "tool");
        validateLifetime(lifetime);
        ensureOpen();
        pruneExpired();
        if (bindings.size() >= MAX_ACTIVE_CAPABILITIES) {
            throw new IllegalStateException("Too many active scoped MCP capabilities");
        }

        String token;
        Binding binding;
        while (true) {
            token = newToken();
            Instant expiresAt = clock.instant().plus(lifetime);
            binding = new Binding(token, tool, expiresAt, lifetime);
            if (bindings.putIfAbsent(token, binding) == null) {
                break;
            }
            binding.revoke();
        }

        String endpoint = serverPortService.getBaseUrl() + scopedSsePath(token);
        return new Capability(token, endpoint, binding.expiresAt, binding);
    }

    public SseEmitter connect(String token) {
        return require(token).connect();
    }

    public SseEmitter openStream(String token, String sessionId) {
        return require(token).transport.openStreamableHttpStream(sessionId);
    }

    public SpringMvcSseServerTransport.StreamableHttpResult createStreamableConnection(
            String token,
            String initialMessage) {
        return require(token).createStreamableConnection(initialMessage);
    }

    public boolean isInitializeMessage(String message) {
        try {
            JsonNode root = mapper.readTree(message);
            return root != null
                    && "initialize".equals(root.path("method").asText())
                    && root.hasNonNull("id");
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid scoped MCP JSON-RPC message", invalid);
        }
    }

    public boolean isNotificationMessage(String message) {
        try {
            JsonNode root = mapper.readTree(message);
            return root != null && root.hasNonNull("method") && !root.hasNonNull("id");
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid scoped MCP JSON-RPC message", invalid);
        }
    }

    public SseEmitter handleStreamableMessage(
            String token,
            String sessionId,
            String message) {
        return require(token).transport.handleStreamableHttpMessage(sessionId, message);
    }

    public Mono<Void> handleMessage(String token, String sessionId, String message) {
        return require(token).transport.handleMessage(sessionId, message);
    }

    public boolean hasSession(String token, String sessionId) {
        return require(token).transport.hasSession(sessionId);
    }

    public void terminateSession(String token, String sessionId) {
        require(token).transport.terminateSession(sessionId);
    }

    String invokeForTesting(String token, Map<String, Object> arguments) {
        return require(token).execute(arguments);
    }

    int activeCapabilityCount() {
        pruneExpired();
        return bindings.size();
    }

    private Binding require(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw new ScopeUnavailableException();
        }
        Binding binding = bindings.get(token);
        if (binding == null) {
            throw new ScopeUnavailableException();
        }
        if (binding.isExpired(clock.instant())) {
            revoke(token, binding);
            throw new ScopeUnavailableException();
        }
        return binding;
    }

    private void revoke(String token) {
        Binding binding = bindings.remove(token);
        if (binding != null) {
            binding.revoke();
        }
    }

    private void revoke(String token, Binding expected) {
        if (bindings.remove(token, expected)) {
            expected.revoke();
        }
    }

    private void pruneExpired() {
        Instant now = clock.instant();
        bindings.forEach((token, binding) -> {
            if (binding.isExpired(now)) {
                revoke(token, binding);
            }
        });
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Scoped MCP capability service is closed");
        }
    }

    private static void validateLifetime(Duration lifetime) {
        if (lifetime == null || lifetime.isZero() || lifetime.isNegative()
                || lifetime.compareTo(MAX_LIFETIME) > 0) {
            throw new IllegalArgumentException(
                    "Scoped MCP capability lifetime must be positive and at most " + MAX_LIFETIME);
        }
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String scopedSsePath(String token) {
        return "/mcp/scoped/" + token + "/sse";
    }

    private static String scopedMessagePath(String token) {
        return "/mcp/scoped/" + token + "/message";
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        reaper.shutdownNow();
        bindings.forEach((token, binding) -> revoke(token, binding));
        bindings.clear();
    }

    /** Selector-free MCP tool definition plus its already-bound server executor. */
    public record ScopedTool(
            String name,
            String description,
            Map<String, Object> inputSchema,
            Function<Map<String, Object>, String> executor) {
        public ScopedTool {
            if (name == null || name.isBlank() || name.length() > 128) {
                throw new IllegalArgumentException("Scoped MCP tool name is invalid");
            }
            if (description == null || description.isBlank() || description.length() > 2_048) {
                throw new IllegalArgumentException("Scoped MCP tool description is invalid");
            }
            inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
            validateSelectorFreeSchema(inputSchema);
            Objects.requireNonNull(executor, "executor");
        }

        private static void validateSelectorFreeSchema(Map<String, Object> schema) {
            if (!"object".equals(schema.get("type"))) {
                throw new IllegalArgumentException("Scoped MCP tool schema must describe an object");
            }
            if (!Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                throw new IllegalArgumentException(
                        "Scoped MCP tool schema must reject additional properties");
            }
            Object rawProperties = schema.get("properties");
            if (!(rawProperties instanceof Map<?, ?> properties)) {
                throw new IllegalArgumentException("Scoped MCP tool schema properties are required");
            }
            for (Object rawName : properties.keySet()) {
                String normalized = String.valueOf(rawName).toLowerCase(Locale.ROOT)
                        .replace("_", "").replace("-", "");
                if (FORBIDDEN_SELECTOR_NAMES.contains(normalized)) {
                    throw new IllegalArgumentException(
                            "Scoped MCP tool schema may not expose authority selector: " + rawName);
                }
            }
        }
    }

    /** One issued bearer URL. The token itself is intentionally not exposed. */
    public final class Capability implements AutoCloseable {
        private final String token;
        private final String endpointUrl;
        private final Instant expiresAt;
        private final Binding binding;
        private final AtomicBoolean revoked = new AtomicBoolean(false);

        private Capability(
                String token,
                String endpointUrl,
                Instant expiresAt,
                Binding binding) {
            this.token = token;
            this.endpointUrl = endpointUrl;
            this.expiresAt = expiresAt;
            this.binding = binding;
        }

        public String endpointUrl() {
            return endpointUrl;
        }

        public Instant expiresAt() {
            return expiresAt;
        }

        String token() {
            return token;
        }

        /** Run once after the sole MCP client has established its transport session. */
        public void onConnected(Runnable callback) {
            binding.onConnected(callback);
        }

        @Override
        public void close() {
            if (revoked.compareAndSet(false, true)) {
                ScopedMcpCapabilityService.this.revoke(token);
            }
        }
    }

    /** Deliberately identical for malformed, unknown, revoked, and expired bearer tokens. */
    public static final class ScopeUnavailableException extends RuntimeException {
        public ScopeUnavailableException() {
            super("Scoped MCP capability is unavailable");
        }
    }

    private final class Binding {
        private final Instant expiresAt;
        private final ScopedTool tool;
        private final SpringMvcSseServerTransport transport;
        @SuppressWarnings("unused")
        private final McpSyncServer server;
        private boolean active = true;
        private boolean connected;
        private Runnable connectionCallback;

        private Binding(String token, ScopedTool tool, Instant expiresAt, Duration lifetime) {
            this.expiresAt = expiresAt;
            this.tool = tool;
            long emitterTimeoutMillis = Math.max(1_000L, lifetime.toMillis());
            this.transport = new SpringMvcSseServerTransport(
                    mapper,
                    serverPortService::getBaseUrl,
                    scopedMessagePath(token),
                    scopedSsePath(token),
                    emitterTimeoutMillis,
                    true);
            ServerCapabilities capabilities = ServerCapabilities.builder().tools(false).build();
            this.server = McpServer.sync(transport)
                    .serverInfo("kompile-scoped-capability", "1")
                    .capabilities(capabilities)
                    .build();
            this.server.addTool(toolSpecification(this));
        }

        private synchronized boolean isExpired(Instant now) {
            return !active || !now.isBefore(expiresAt);
        }

        private synchronized SseEmitter connect() {
            requireNewConnection();
            SseEmitter emitter = transport.createConnection();
            markConnected();
            return emitter;
        }

        private synchronized SpringMvcSseServerTransport.StreamableHttpResult
                createStreamableConnection(String initialMessage) {
            requireNewConnection();
            SpringMvcSseServerTransport.StreamableHttpResult result =
                    transport.createStreamableHttpConnection(initialMessage);
            markConnected();
            return result;
        }

        private void requireNewConnection() {
            if (!active || connected || !clock.instant().isBefore(expiresAt)) {
                throw new ScopeUnavailableException();
            }
        }

        private void markConnected() {
            connected = true;
            Runnable callback = connectionCallback;
            connectionCallback = null;
            if (callback != null) {
                try {
                    callback.run();
                } catch (RuntimeException ignored) {
                    // Capability validity does not depend on best-effort client config deletion.
                }
            }
        }

        private synchronized void onConnected(Runnable callback) {
            Objects.requireNonNull(callback, "callback");
            if (!active) {
                return;
            }
            if (connected) {
                callback.run();
            } else {
                connectionCallback = callback;
            }
        }

        private synchronized String execute(Map<String, Object> arguments) {
            if (!active || !clock.instant().isBefore(expiresAt)) {
                throw new ScopeUnavailableException();
            }
            String result = tool.executor().apply(
                    arguments == null ? Map.of() : Map.copyOf(arguments));
            String safe = result == null ? "null" : result;
            if (safe.length() > MAX_TOOL_RESULT_CHARACTERS) {
                throw new IllegalArgumentException("Scoped MCP tool result exceeds the bounded limit");
            }
            return safe;
        }

        private synchronized void revoke() {
            if (!active) {
                return;
            }
            active = false;
            transport.close();
        }
    }

    private McpServerFeatures.SyncToolSpecification toolSpecification(Binding binding) {
        String schemaJson;
        try {
            schemaJson = mapper.writeValueAsString(binding.tool.inputSchema());
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Could not encode scoped MCP tool schema", invalid);
        }
        if (schemaJson.length() > MAX_SCHEMA_BYTES) {
            throw new IllegalArgumentException("Scoped MCP tool schema exceeds the bounded limit");
        }
        Tool protocolTool = new Tool(
                binding.tool.name(), binding.tool.description(), schemaJson);
        return new McpServerFeatures.SyncToolSpecification(
                protocolTool,
                (exchange, arguments) -> {
                    try {
                        return new CallToolResult(
                                List.of(new TextContent(binding.execute(arguments))), false);
                    } catch (Throwable failure) {
                        return new CallToolResult(
                                List.of(new TextContent("Scoped MCP tool execution failed")), true);
                    }
                });
    }
}
