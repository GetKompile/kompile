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

package ai.kompile.cli.main.chat.enforcer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Localhost-only embedded REST control server for a headless enforced session. Lets an external
 * process drive a running {@code kompile enforcer} session by sending commands over HTTP, so the
 * full enforcement engine (judge + diff-archive + rollback) is scriptable, manageable, and testable
 * without a TTY.
 *
 * <p>Endpoints: {@code POST /send {"message":...}}, {@code POST /command {"command":...,"arg":...}},
 * {@code GET /state}, {@code GET /judgements}, {@code POST /stop}.</p>
 *
 * <p>Security: binds to 127.0.0.1 only. When a token is configured, every request must carry it in
 * the {@code X-Kompile-Token} header.</p>
 */
public class EnforcerControlServer {

    /** The driven session — implemented by EnforcerCommand over its enforcement engine. */
    public interface Session {
        EnforcerResult send(String message) throws Exception;

        String command(String command, String arg);

        Map<String, Object> state();

        List<JudgementRecord> judgements();

        void stop();
    }

    private final HttpServer server;
    private final String token;
    private final Session session;
    private final ObjectMapper mapper;

    public EnforcerControlServer(int port, String token, Session session, ObjectMapper mapper) throws IOException {
        this.token = (token == null || token.isBlank()) ? null : token;
        this.session = session;
        this.mapper = mapper;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "enforcer-control");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/send", this::handleSend);
        server.createContext("/command", this::handleCommand);
        server.createContext("/state", this::handleState);
        server.createContext("/judgements", this::handleJudgements);
        server.createContext("/stop", this::handleStop);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    // ── Handlers ────────────────────────────────────────────────────────────

    private void handleSend(HttpExchange ex) throws IOException {
        if (!guard(ex, "POST")) {
            return;
        }
        try {
            JsonNode body = readBody(ex);
            String message = body.path("message").asText("");
            if (message.isBlank()) {
                writeJson(ex, 400, Map.of("error", "message is required"));
                return;
            }
            writeJson(ex, 200, resultMap(session.send(message)));
        } catch (Exception e) {
            writeJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private void handleCommand(HttpExchange ex) throws IOException {
        if (!guard(ex, "POST")) {
            return;
        }
        try {
            JsonNode body = readBody(ex);
            String command = body.path("command").asText("");
            String arg = body.path("arg").asText("");
            if (command.isBlank()) {
                writeJson(ex, 400, Map.of("error", "command is required"));
                return;
            }
            String out = session.command(command, arg);
            writeJson(ex, 200, Map.of("output", out == null ? "" : out));
        } catch (Exception e) {
            writeJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private void handleState(HttpExchange ex) throws IOException {
        if (!guard(ex, "GET")) {
            return;
        }
        try {
            writeJson(ex, 200, session.state());
        } catch (Exception e) {
            writeJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private void handleJudgements(HttpExchange ex) throws IOException {
        if (!guard(ex, "GET")) {
            return;
        }
        try {
            writeJsonRaw(ex, 200, mapper.writeValueAsBytes(session.judgements()));
        } catch (Exception e) {
            writeJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private void handleStop(HttpExchange ex) throws IOException {
        if (!guard(ex, "POST")) {
            return;
        }
        writeJson(ex, 200, Map.of("status", "stopping"));
        session.stop();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private boolean guard(HttpExchange ex, String method) throws IOException {
        if (token != null && !token.equals(ex.getRequestHeaders().getFirst("X-Kompile-Token"))) {
            writeJson(ex, 401, Map.of("error", "unauthorized"));
            return false;
        }
        if (!method.equalsIgnoreCase(ex.getRequestMethod())) {
            writeJson(ex, 405, Map.of("error", "method not allowed"));
            return false;
        }
        return true;
    }

    private JsonNode readBody(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readAllBytes();
        return b.length == 0 ? mapper.createObjectNode() : mapper.readTree(b);
    }

    private void writeJson(HttpExchange ex, int status, Map<String, Object> body) throws IOException {
        writeJsonRaw(ex, status, mapper.writeValueAsBytes(body));
    }

    private void writeJsonRaw(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private Map<String, Object> resultMap(EnforcerResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (r == null) {
            m.put("status", "ERROR");
            return m;
        }
        m.put("status", r.getStatus() != null ? r.getStatus().name() : "UNKNOWN");
        m.put("accepted", r.isAccepted());
        m.put("message", r.getMessage());
        m.put("attempts", r.getAttempts().size());
        m.put("output", r.getFinalOutput());
        List<String> violations = new ArrayList<>();
        if (!r.getAttempts().isEmpty()) {
            EnforcerResult.Attempt last = r.getAttempts().get(r.getAttempts().size() - 1);
            if (last.decision() != null) {
                violations.addAll(last.decision().getViolations());
            }
        }
        m.put("violations", violations);
        return m;
    }
}
