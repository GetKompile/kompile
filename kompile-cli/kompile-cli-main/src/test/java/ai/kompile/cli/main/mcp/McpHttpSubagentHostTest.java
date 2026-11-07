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
import ai.kompile.cli.main.chat.agent.SubagentDelegationGuard;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Delegated agents' MCP sessions served over loopback Streamable HTTP by their launcher. */
@TemporaryUserHome
class McpHttpSubagentHostTest {

    @TempDir Path workDir;

    private final ObjectMapper mapper = JsonUtils.standardMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<FakeEngine> engines = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopHost() {
        McpHttpSubagentHost.uninstall();
        System.clearProperty(McpHttpSubagentHost.TRANSPORT_PROPERTY);
    }

    @Test
    void noTicketIsIssuedUnlessThisProcessServesSessions() {
        assertNull(McpHttpSubagentHost.openTicket(workDir, Map.of()));

        installFakeEngines();
        System.setProperty(McpHttpSubagentHost.TRANSPORT_PROPERTY, "stdio");
        assertNull(McpHttpSubagentHost.openTicket(workDir, Map.of()), "the stdio kill switch wins");
    }

    @Test
    void requestsWithoutALiveTicketsTokenAreRefused() throws Exception {
        installFakeEngines();
        McpHttpSubagentHost.Ticket ticket = McpHttpSubagentHost.openTicket(workDir, Map.of());
        assertNotNull(ticket);
        assertTrue(ticket.url().startsWith("http://127.0.0.1:"), ticket.url());

        assertEquals(401, post(ticket.url(), null, null, initialize(1)).statusCode());
        assertEquals(401, post(ticket.url(), ticket.bearerToken() + "x", null, initialize(1)).statusCode());

        ticket.close();
        assertEquals(401, post(ticket.url(), ticket.bearerToken(), null, initialize(1)).statusCode());
        assertTrue(engines.isEmpty(), "a refused request must not start an engine");
    }

    @Test
    void initializeOpensASessionReachableOnlyUnderItsTicket() throws Exception {
        installFakeEngines();
        McpHttpSubagentHost.Ticket ticket = McpHttpSubagentHost.openTicket(workDir,
                Map.of("KOMPILE_SUBAGENT_DEPTH", "1", "KOMPILE_AGENT_NAME", "claude"));
        McpHttpSubagentHost.Ticket other = McpHttpSubagentHost.openTicket(workDir, Map.of());

        HttpResponse<String> init = post(ticket.url(), ticket.bearerToken(), null, initialize(1));
        assertEquals(200, init.statusCode());
        assertEquals("2025-06-18", mapper.readTree(init.body()).path("result").path("protocolVersion").asText());
        String session = init.headers().firstValue(McpHttpSubagentHost.SESSION_HEADER).orElseThrow();

        FakeEngine engine = engines.get(0);
        assertEquals("1", engine.overlay.get("KOMPILE_SUBAGENT_DEPTH"));
        assertEquals(workDir.toAbsolutePath().normalize(), engine.workingDir);

        assertEquals(400, post(ticket.url(), ticket.bearerToken(), null, request(2, "ping")).statusCode());
        assertEquals(404, post(ticket.url(), ticket.bearerToken(), "unknown", request(2, "ping")).statusCode());
        assertEquals(404, post(ticket.url(), other.bearerToken(), session, request(2, "ping")).statusCode(),
                "another agent's token must not reach this session");

        HttpResponse<String> ping = post(ticket.url(), ticket.bearerToken(), session, request(3, "ping"));
        assertEquals(200, ping.statusCode());
        assertEquals(3, mapper.readTree(ping.body()).path("id").asInt());
    }

    @Test
    void toolCallsStreamTheirProgressThenTheirResult() throws Exception {
        installFakeEngines();
        McpHttpSubagentHost.Ticket ticket = McpHttpSubagentHost.openTicket(workDir, Map.of());
        String session = openSession(ticket);

        ObjectNode call = request(7, "tools/call");
        ObjectNode params = (ObjectNode) call.get("params");
        params.put("name", "echo");
        params.putObject("_meta").put("progressToken", "tok-7");
        HttpResponse<String> response = post(ticket.url(), ticket.bearerToken(), session, call);

        assertEquals(200, response.statusCode());
        assertEquals("text/event-stream", response.headers().firstValue("Content-Type").orElse(""));
        List<JsonNode> events = sseEvents(response.body());
        assertEquals(2, events.size(), response.body());
        assertEquals("notifications/progress", events.get(0).path("method").asText());
        assertEquals("tok-7", events.get(0).path("params").path("progressToken").asText());
        assertEquals(7, events.get(1).path("id").asInt());
        assertEquals("echo", events.get(1).path("result").path("content").get(0).path("text").asText());
    }

    @Test
    void notificationsAreAcceptedAndUnrelatedOnesWaitForTheGetStream() throws Exception {
        installFakeEngines();
        McpHttpSubagentHost.Ticket ticket = McpHttpSubagentHost.openTicket(workDir, Map.of());
        String session = openSession(ticket);

        ObjectNode initialized = mapper.createObjectNode();
        initialized.put("jsonrpc", "2.0");
        initialized.put("method", "notifications/initialized");
        assertEquals(202, post(ticket.url(), ticket.bearerToken(), session, initialized).statusCode());

        assertEquals(405, send(HttpRequest.newBuilder(URI.create(ticket.url()))
                .header("Authorization", "Bearer " + ticket.bearerToken()).GET()).statusCode(),
                "GET without a session is not offered");

        HttpResponse<InputStream> stream = http.send(HttpRequest.newBuilder(URI.create(ticket.url()))
                .header("Authorization", "Bearer " + ticket.bearerToken())
                .header(McpHttpSubagentHost.SESSION_HEADER, session)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, stream.statusCode());
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
            String line;
            JsonNode held = null;
            while ((line = lines.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    held = mapper.readTree(line.substring(6));
                    break;
                }
            }
            assertNotNull(held, "the notification sent before the stream opened was dropped");
            assertEquals("notifications/message", held.path("method").asText());
        }
    }

    @Test
    void closingTheTicketEndsItsSessions() throws Exception {
        installFakeEngines();
        McpHttpSubagentHost.Ticket ticket = McpHttpSubagentHost.openTicket(workDir, Map.of());
        String session = openSession(ticket);

        ticket.close();

        assertTrue(engines.get(0).closed.get());
        assertEquals(401, post(ticket.url(), ticket.bearerToken(), session, request(2, "ping")).statusCode());
    }

    @Test
    void sessionsEndOnDeleteAndUnknownMethodsAreRefused() throws Exception {
        installFakeEngines();
        McpHttpSubagentHost.Ticket ticket = McpHttpSubagentHost.openTicket(workDir, Map.of());
        String session = openSession(ticket);

        HttpResponse<String> delete = send(HttpRequest.newBuilder(URI.create(ticket.url()))
                .header("Authorization", "Bearer " + ticket.bearerToken())
                .header(McpHttpSubagentHost.SESSION_HEADER, session).DELETE());
        assertEquals(204, delete.statusCode());
        assertTrue(engines.get(0).closed.get());
        assertEquals(404, post(ticket.url(), ticket.bearerToken(), session, request(2, "ping")).statusCode());

        assertEquals(405, send(HttpRequest.newBuilder(URI.create(ticket.url()))
                .header("Authorization", "Bearer " + ticket.bearerToken())
                .PUT(HttpRequest.BodyPublishers.noBody())).statusCode());
    }

    @Test
    void foreignOriginsAreRefused() throws Exception {
        installFakeEngines();
        McpHttpSubagentHost.Ticket ticket = McpHttpSubagentHost.openTicket(workDir, Map.of());

        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(ticket.url()))
                .header("Authorization", "Bearer " + ticket.bearerToken())
                .header("Origin", "http://evil.example")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(initialize(1)))));
        assertEquals(403, response.statusCode());

        assertTrue(McpHttpSubagentHost.loopbackOrigin(null));
        assertTrue(McpHttpSubagentHost.loopbackOrigin("http://localhost:3000"));
        assertTrue(McpHttpSubagentHost.loopbackOrigin("http://127.0.0.1:3000"));
        assertTrue(McpHttpSubagentHost.loopbackOrigin("http://[::1]:3000"));
        assertFalse(McpHttpSubagentHost.loopbackOrigin("http://127.evil.example"),
                "a name is never trusted for looking like a loopback address");
        assertFalse(McpHttpSubagentHost.loopbackOrigin("null"));
    }

    @Test
    void aSessionOfTheRealServerRunsAsTheTicketsAgentNotAsThisProcess() throws Exception {
        assertFalse(SubagentDelegationGuard.isSubagent(), "this test JVM must run as a top-level process");
        McpStdioCommand parent = new McpStdioCommand();
        McpHttpSubagentHost.install(parent::openSubagentEngine);
        McpHttpSubagentHost.Ticket ticket = McpHttpSubagentHost.openTicket(workDir,
                Map.of("KOMPILE_SUBAGENT_DEPTH", "1", "KOMPILE_AGENT_NAME", "claude"));
        McpHttpSubagentHost.Ticket topLevel = McpHttpSubagentHost.openTicket(workDir, Map.of());
        try {
            List<String> childTools = toolNames(ticket, openSession(ticket));
            List<String> topLevelTools = toolNames(topLevel, openSession(topLevel));

            assertTrue(childTools.contains("read"), childTools.toString());
            assertFalse(childTools.contains("task"), "a subagent session must not offer delegation");
            assertTrue(topLevelTools.contains("task"), "a top-level session keeps delegation");
        } finally {
            ticket.close();
            topLevel.close();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private List<String> toolNames(McpHttpSubagentHost.Ticket ticket, String session) throws Exception {
        HttpResponse<String> response = post(ticket.url(), ticket.bearerToken(), session, request(2, "tools/list"));
        assertEquals(200, response.statusCode(), response.body());
        List<String> names = new ArrayList<>();
        for (JsonNode tool : mapper.readTree(response.body()).path("result").path("tools")) {
            names.add(tool.path("name").asText());
        }
        return names;
    }

    private void installFakeEngines() {
        McpHttpSubagentHost.install((workingDir, overlay, sink) -> {
            FakeEngine engine = new FakeEngine(workingDir, overlay, sink);
            engines.add(engine);
            return engine;
        });
    }

    private String openSession(McpHttpSubagentHost.Ticket ticket) throws Exception {
        HttpResponse<String> init = post(ticket.url(), ticket.bearerToken(), null, initialize(1));
        assertEquals(200, init.statusCode(), init.body());
        return init.headers().firstValue(McpHttpSubagentHost.SESSION_HEADER).orElseThrow();
    }

    private ObjectNode initialize(int id) {
        ObjectNode request = request(id, "initialize");
        ((ObjectNode) request.get("params")).put("protocolVersion", "2025-06-18");
        return request;
    }

    private ObjectNode request(int id, String method) {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.putObject("params");
        return request;
    }

    private HttpResponse<String> post(String url, String token, String session, JsonNode body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (session != null) request.header(McpHttpSubagentHost.SESSION_HEADER, session);
        return send(request);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private List<JsonNode> sseEvents(String body) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.startsWith("data: ")) events.add(mapper.readTree(line.substring(6)));
        }
        return events;
    }

    /** Answers like a session engine: inline for most requests, from a worker for tool calls. */
    private final class FakeEngine implements McpHttpSubagentHost.Engine {
        final Path workingDir;
        final Map<String, String> overlay;
        final McpStdioCommand.Outbound sink;
        final AtomicBoolean closed = new AtomicBoolean();

        FakeEngine(Path workingDir, Map<String, String> overlay, McpStdioCommand.Outbound sink) {
            this.workingDir = workingDir;
            this.overlay = overlay;
            this.sink = sink;
        }

        @Override
        public void dispatch(JsonNode message) {
            try {
                switch (message.path("method").asText("")) {
                    case "initialize" -> {
                        ObjectNode response = response(message);
                        response.putObject("result").put("protocolVersion", "2025-06-18");
                        sink.send(response);
                    }
                    case "ping" -> {
                        ObjectNode response = response(message);
                        response.putObject("result");
                        sink.send(response);
                    }
                    case "notifications/initialized" -> {
                        ObjectNode log = mapper.createObjectNode();
                        log.put("jsonrpc", "2.0");
                        log.put("method", "notifications/message");
                        log.putObject("params").put("level", "info").put("data", "ready");
                        sink.send(log);
                    }
                    case "tools/call" -> new Thread(() -> answerCall(message)).start();
                    default -> { }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private void answerCall(JsonNode message) {
            try {
                Thread.sleep(100);
                ObjectNode progress = mapper.createObjectNode();
                progress.put("jsonrpc", "2.0");
                progress.put("method", "notifications/progress");
                progress.putObject("params")
                        .put("progressToken", message.path("params").path("_meta").path("progressToken").asText())
                        .put("progress", 1);
                sink.send(progress);
                ObjectNode response = response(message);
                response.putObject("result").putArray("content").addObject()
                        .put("type", "text").put("text", message.path("params").path("name").asText());
                sink.send(response);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private ObjectNode response(JsonNode request) {
            ObjectNode response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            return response;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
