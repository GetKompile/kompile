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

package ai.kompile.cli.main.mcp;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Serves delegated agents' MCP sessions from the server that launched them.
 *
 * <p>A delegated Claude agent used to get an MCP server process of its own, and could
 * not send its first prompt until that JVM had booted and listed its tools. Here the
 * already running server answers it over loopback Streamable HTTP instead: each
 * session is a fresh engine with its own tools, session tracking and coordination
 * identity, run under the identity the agent was launched with
 * ({@link ai.kompile.cli.main.chat.agent.SessionEnvironment}).</p>
 *
 * <p>Admission is a {@link Ticket}: the launcher opens one per agent run and closes it
 * when the run ends. Its bearer token is the agent's identity, so a request without a
 * live ticket's token is refused, and a session is only reachable with the token it
 * was opened under.</p>
 *
 * <p>Requests are answered as JSON, except {@code tools/call}, which streams as
 * server-sent events: progress for the call, keepalive comments while it runs (an MCP
 * client's HTTP stack times out a silent response), then its result. Notifications not
 * tied to a request go to the session's {@code GET} stream, held until one opens.</p>
 */
public final class McpHttpSubagentHost {

    /** {@code stdio} gives delegated agents an MCP server process of their own again. */
    public static final String TRANSPORT_ENV = "KOMPILE_SUBAGENT_MCP_TRANSPORT";
    /** System-property form of {@link #TRANSPORT_ENV}. */
    public static final String TRANSPORT_PROPERTY = "kompile.subagent.mcpTransport";

    static final String SESSION_HEADER = "Mcp-Session-Id";
    static final String PATH = "/mcp";
    private static final long KEEPALIVE_MILLIS = 15_000;
    private static final int MAX_HELD_NOTIFICATIONS = 256;
    private static final Pattern LOOPBACK_V4 = Pattern.compile("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    /** Queued after an exchange's last event: it is finished. */
    private static final JsonNode END = JsonUtils.standardMapper().createObjectNode();

    /** Builds the engine for one session of a ticket. */
    @FunctionalInterface
    interface EngineFactory {
        Engine open(Path workingDir, Map<String, String> overlay, McpStdioCommand.Outbound sink)
                throws Exception;
    }

    /** One session's server side: takes client messages, answers through its sink. */
    interface Engine {
        void dispatch(JsonNode message);

        void close();
    }

    /** One agent run's admission: where its MCP client connects and the token it presents. */
    public interface Ticket extends AutoCloseable {
        String url();

        String bearerToken();

        /** Revokes the token and ends every session opened under it. */
        @Override
        void close();
    }

    private static final Object LOCK = new Object();
    private static EngineFactory factory;
    private static McpHttpSubagentHost running;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectMapper om = JsonUtils.standardMapper();
    private final EngineFactory engines;
    private final HttpServer server;
    private final ExecutorService executor;
    private final String url;
    private final Map<String, Admission> admissions = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private McpHttpSubagentHost(EngineFactory engines) throws IOException {
        this.engines = engines;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicInteger threads = new AtomicInteger();
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mcp-http-subagent-" + threads.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext(PATH, this::handle);
        server.start();
        url = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort() + PATH;
        System.err.println("[MCP] Subagent sessions served at " + url);
    }

    /** Serves subagent sessions from this process with {@code factory}'s engines. */
    static void install(EngineFactory engines) {
        synchronized (LOCK) {
            factory = engines;
        }
    }

    /** Stops serving: every ticket is revoked and every session ended. */
    static void uninstall() {
        McpHttpSubagentHost host;
        synchronized (LOCK) {
            factory = null;
            host = running;
            running = null;
        }
        if (host != null) host.stop();
    }

    /** Whether delegated agents are served here rather than by servers of their own. */
    public static boolean enabled() {
        String configured = System.getProperty(TRANSPORT_PROPERTY);
        if (configured == null || configured.isBlank()) configured = System.getenv(TRANSPORT_ENV);
        return configured == null || !"stdio".equalsIgnoreCase(configured.trim());
    }

    /**
     * Admits one agent run whose tools work in {@code workingDir} under {@code overlay}'s
     * identity, or {@code null} when this process serves no sessions (not an MCP server,
     * or the transport is turned off) and the agent needs a server of its own.
     */
    public static Ticket openTicket(Path workingDir, Map<String, String> overlay) {
        if (!enabled()) return null;
        McpHttpSubagentHost host;
        synchronized (LOCK) {
            if (factory == null) return null;
            if (running == null) {
                try {
                    running = new McpHttpSubagentHost(factory);
                } catch (IOException e) {
                    System.err.println("[MCP] Subagent session host unavailable: " + e.getMessage());
                    return null;
                }
            }
            host = running;
        }
        return host.admit(workingDir, overlay);
    }

    private Ticket admit(Path workingDir, Map<String, String> overlay) {
        Admission admission = new Admission(randomToken(), workingDir.toAbsolutePath().normalize(),
                Map.copyOf(overlay));
        admissions.put(admission.token, admission);
        return admission;
    }

    private void stop() {
        server.stop(0);
        for (Admission admission : admissions.values()) admission.close();
        executor.shutdownNow();
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private void handle(HttpExchange exchange) {
        try {
            if (!loopbackOrigin(exchange.getRequestHeaders().getFirst("Origin"))) {
                status(exchange, 403);
                return;
            }
            Admission admission = authenticate(exchange.getRequestHeaders().getFirst("Authorization"));
            if (admission == null) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                status(exchange, 401);
                return;
            }
            switch (exchange.getRequestMethod().toUpperCase(Locale.ROOT)) {
                case "POST" -> post(exchange, admission);
                case "GET" -> {
                    Session session = session(exchange, admission);
                    if (session != null) session.stream(exchange);
                }
                case "DELETE" -> {
                    Session session = session(exchange, admission);
                    if (session != null) {
                        session.close();
                        status(exchange, 204);
                    }
                }
                default -> {
                    exchange.getResponseHeaders().set("Allow", "GET, POST, DELETE");
                    status(exchange, 405);
                }
            }
        } catch (IOException e) {
            // The client went away mid-response; its session and calls are handled by their owners.
        } catch (RuntimeException e) {
            System.err.println("[MCP] Subagent session request failed: " + e);
        } finally {
            exchange.close();
        }
    }

    private void post(HttpExchange exchange, Admission admission) throws IOException {
        JsonNode message;
        try {
            message = om.readTree(exchange.getRequestBody());
        } catch (IOException e) {
            json(exchange, 400, error(null, -32700, "Parse error"));
            return;
        }
        if (message == null || !message.isObject()) {
            json(exchange, 400, error(null, -32600, "Expected one JSON-RPC message"));
            return;
        }
        String method = message.path("method").asText("");
        if ("initialize".equals(method)) {
            Session session = admission.openSession();
            if (session == null) {
                json(exchange, 500, error(message.get("id"), -32603, "Could not start the session"));
                return;
            }
            exchange.getResponseHeaders().set(SESSION_HEADER, session.id);
            session.request(exchange, message, false);
            return;
        }
        Session session = session(exchange, admission);
        if (session == null) return;
        if (!message.has("id") || message.get("id").isNull() || method.isEmpty()) {
            // A notification, or the client's answer to a request: nothing to send back.
            session.engine.dispatch(message);
            status(exchange, 202);
            return;
        }
        session.request(exchange, message, "tools/call".equals(method));
    }

    /** The session this request names under {@code admission}, else answers it 400/404. */
    private Session session(HttpExchange exchange, Admission admission) throws IOException {
        String id = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
        if (id == null || id.isBlank()) {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) status(exchange, 405);
            else json(exchange, 400, error(null, -32600, "Missing " + SESSION_HEADER));
            return null;
        }
        Session session = sessions.get(id.trim());
        if (session == null || session.admission != admission) {
            status(exchange, 404);
            return null;
        }
        return session;
    }

    private Admission authenticate(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        byte[] presented = authorization.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        Admission match = null;
        for (Admission admission : admissions.values()) {
            if (MessageDigest.isEqual(presented, admission.tokenBytes)) match = admission;
        }
        return match != null && !match.revoked ? match : null;
    }

    /**
     * No origin (a non-browser client) or a loopback one; anything else is a DNS rebinding
     * attempt. Names are never resolved: a rebound name resolving to loopback is the attack.
     */
    static boolean loopbackOrigin(String origin) {
        if (origin == null || origin.isBlank()) return true;
        try {
            String host = URI.create(origin.trim()).getHost();
            if (host == null) return false;
            return "localhost".equalsIgnoreCase(host) || LOOPBACK_V4.matcher(host).matches()
                    || "[::1]".equals(host) || "::1".equals(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private ObjectNode error(JsonNode id, int code, String text) {
        ObjectNode response = om.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) response.set("id", id);
        else response.putNull("id");
        response.putObject("error").put("code", code).put("message", text);
        return response;
    }

    private void json(HttpExchange exchange, int code, JsonNode body) throws IOException {
        byte[] bytes = om.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void status(HttpExchange exchange, int code) throws IOException {
        exchange.sendResponseHeaders(code, -1);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ── Tickets and sessions ────────────────────────────────────────────────

    private final class Admission implements Ticket {
        final String token;
        final byte[] tokenBytes;
        final Path workingDir;
        final Map<String, String> overlay;
        final Map<String, Session> owned = new ConcurrentHashMap<>();
        volatile boolean revoked;

        Admission(String token, Path workingDir, Map<String, String> overlay) {
            this.token = token;
            this.tokenBytes = token.getBytes(StandardCharsets.UTF_8);
            this.workingDir = workingDir;
            this.overlay = overlay;
        }

        @Override
        public String url() {
            return url;
        }

        @Override
        public String bearerToken() {
            return token;
        }

        Session openSession() {
            if (revoked) return null;
            Session session = new Session(randomToken(), this);
            try {
                session.engine = engines.open(workingDir, overlay, session);
            } catch (Exception e) {
                System.err.println("[MCP] Subagent session failed to start: " + e.getMessage());
                return null;
            }
            owned.put(session.id, session);
            sessions.put(session.id, session);
            // A ticket closed while this engine started must not leave it running.
            if (revoked) {
                session.close();
                return null;
            }
            return session;
        }

        @Override
        public void close() {
            revoked = true;
            admissions.remove(token, this);
            for (Session session : owned.values()) session.close();
        }
    }

    /** One MCP session: routes its engine's messages to the HTTP responses waiting for them. */
    private final class Session implements McpStdioCommand.Outbound {
        final String id;
        final Admission admission;
        volatile Engine engine;
        /** Requests awaiting their response, by JSON-RPC id. */
        final Map<String, Exchange> pending = new ConcurrentHashMap<>();
        /** Streaming requests by the progress token they asked for. */
        final Map<String, Exchange> progress = new ConcurrentHashMap<>();
        /** Notifications for the GET stream, held while none is open. */
        final Deque<JsonNode> held = new ArrayDeque<>();
        Exchange stream;
        volatile boolean closed;

        Session(String id, Admission admission) {
            this.id = id;
            this.admission = admission;
        }

        @Override
        public void send(JsonNode message) {
            if (message.has("id") && (message.has("result") || message.has("error"))) {
                Exchange waiting = pending.remove(McpStdioCommand.callKey(message.get("id")));
                if (waiting != null) waiting.finish(message);
                return;
            }
            if ("notifications/progress".equals(message.path("method").asText(""))) {
                Exchange call = progress.get(message.path("params").path("progressToken").asText(""));
                if (call != null) {
                    call.event(message);
                    return;
                }
            }
            synchronized (this) {
                if (stream != null) {
                    stream.event(message);
                } else {
                    if (held.size() >= MAX_HELD_NOTIFICATIONS) held.removeFirst();
                    held.addLast(message);
                }
            }
        }

        /** Answers {@code message}, streaming as SSE when {@code streamed}. */
        void request(HttpExchange http, JsonNode message, boolean streamed) throws IOException {
            String key = McpStdioCommand.callKey(message.get("id"));
            Exchange exchange = new Exchange();
            String progressToken = message.path("params").path("_meta").path("progressToken").asText("");
            if (key != null) pending.put(key, exchange);
            if (streamed && !progressToken.isEmpty()) progress.put(progressToken, exchange);
            try {
                engine.dispatch(message);
                if (streamed) {
                    if (!exchange.streamTo(http)) cancel(message.get("id"));
                } else {
                    JsonNode response = exchange.awaitResponse();
                    if (response == null) {
                        json(http, 503, error(message.get("id"), -32603, "Session ended"));
                    } else {
                        json(http, 200, response);
                    }
                }
            } finally {
                if (key != null) pending.remove(key, exchange);
                if (!progressToken.isEmpty()) progress.remove(progressToken, exchange);
            }
        }

        /** Holds the session's GET stream open until the session ends or the client leaves. */
        void stream(HttpExchange http) throws IOException {
            Exchange exchange = new Exchange();
            synchronized (this) {
                if (stream != null) {
                    // One standalone stream per session (MCP Streamable HTTP).
                    status(http, 409);
                    return;
                }
                stream = exchange;
                while (!held.isEmpty()) exchange.event(held.removeFirst());
            }
            try {
                exchange.streamTo(http);
            } finally {
                synchronized (this) {
                    if (stream == exchange) stream = null;
                }
            }
        }

        /** The client dropped a call's stream: stop the call as an explicit cancel would. */
        private void cancel(JsonNode requestId) {
            if (closed || requestId == null) return;
            ObjectNode cancelled = om.createObjectNode();
            cancelled.put("jsonrpc", "2.0");
            cancelled.put("method", "notifications/cancelled");
            ObjectNode params = cancelled.putObject("params");
            params.set("requestId", requestId);
            params.put("reason", "client disconnected");
            engine.dispatch(cancelled);
        }

        void close() {
            if (closed) return;
            closed = true;
            sessions.remove(id, this);
            admission.owned.remove(id, this);
            for (Exchange waiting : pending.values()) waiting.finish(null);
            pending.clear();
            Exchange open;
            synchronized (this) {
                open = stream;
                held.clear();
            }
            if (open != null) open.finish(null);
            Engine started = engine;
            if (started != null) started.close();
        }
    }

    /** One HTTP response in progress; only its handler thread writes to the connection. */
    private final class Exchange {
        private final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        private volatile JsonNode response;

        void event(JsonNode message) {
            events.add(message);
        }

        /** Completes with {@code message}; {@code null} ends it without a response. */
        void finish(JsonNode message) {
            response = message;
            events.add(END);
        }

        JsonNode awaitResponse() {
            try {
                while (true) {
                    if (events.take() == END) return response;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        /** Streams events until finished; {@code false} when the client went away first. */
        boolean streamTo(HttpExchange http) {
            try {
                http.getResponseHeaders().set("Content-Type", "text/event-stream");
                http.getResponseHeaders().set("Cache-Control", "no-cache");
                http.sendResponseHeaders(200, 0);
                OutputStream out = http.getResponseBody();
                while (true) {
                    JsonNode next = events.poll(KEEPALIVE_MILLIS, TimeUnit.MILLISECONDS);
                    if (next == null) {
                        out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    } else if (next == END) {
                        if (response != null) write(out, response);
                        out.flush();
                        return true;
                    } else {
                        write(out, next);
                    }
                    out.flush();
                }
            } catch (IOException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        }

        private void write(OutputStream out, JsonNode message) throws IOException {
            out.write(("event: message\ndata: " + om.writeValueAsString(message) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
    }
}
