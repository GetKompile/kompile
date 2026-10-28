package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A durable web /role selection drives the turn like --role: the role's system
 * prompt reaches the provider, not just the run's reported agent name.
 */
@TemporaryUserHome
class HeadlessWebRoleTest {

    private static final String SESSION = "web-role-session";
    private static final String MARKER = "ROLE-MARKER-7f3c";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void localSubprocessOutputReachesTheWebBeforeItsCompletion(@TempDir Path project) throws Exception {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String delta = requests.getAndIncrement() == 0
                        ? "{\"tool_calls\":[{\"index\":0,\"id\":\"bash-web\",\"type\":\"function\",\"function\":{\"name\":\"bash\","
                            + "\"arguments\":\"{\\\"command\\\":\\\"printf web-live-output\\\"}\"}}]}"
                        : "{\"content\":\"done\"}";
                String finish = requests.get() == 1 ? "tool_calls" : "stop";
                byte[] body = ("data: {\"choices\":[{\"delta\":" + delta + ",\"finish_reason\":\"" + finish
                        + "\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            ChatConfig config = new ChatConfig("custom", null, "model", "http://127.0.0.1:" + server.getAddress().getPort());
            config.setDefaultMemory(false);
            List<HeadlessRunEvent> events = new CopyOnWriteArrayList<>();
            var options = new HeadlessAgentRunner.Options("run the command", "web-live-local", false, null, null,
                    HeadlessAgentRunner.OutputMode.QUIET, project, 20_000, null, null, null,
                    events::add, config, null, false, false, null, true);
            assertEquals(0, new HeadlessAgentRunner().run(options).exitCode());
            var output = events.stream().filter(event -> event.type() == HeadlessRunEvent.Type.TOOL_OUTPUT).findFirst().orElseThrow();
            var complete = events.stream().filter(event -> event.type() == HeadlessRunEvent.Type.TOOL_COMPLETED).findFirst().orElseThrow();
            assertEquals("bash-web", output.callId());
            assertTrue(output.sequence() < complete.sequence());
            assertTrue(output.data().toString().contains("web-live-output"));
            assertTrue(complete.data().toString().contains("web-live-output"));
            assertFalse(events.stream().anyMatch(event -> event.type() == HeadlessRunEvent.Type.ASSISTANT_DELTA
                    && event.text().contains("web-live-output")));
        } finally { server.stop(0); }
    }

    @Test
    void durableWebRoleSystemPromptReachesTheProvider(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Path roles = Files.createDirectories(project.resolve(".kompile").resolve("roles"));
        Files.writeString(roles.resolve("marker-reviewer.md"),
                "You are the marker reviewer. " + MARKER + "\n");
        ChatSessionStateStore store = new ChatSessionStateStore(tempDir.resolve("web-state"));
        assertTrue(store.updateRole(SESSION, project, "marker-reviewer").applied());

        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                requests.add(mapper.readTree(exchange.getRequestBody()));
                byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"role answer\"},"
                        + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            ChatConfig config = new ChatConfig("custom", null, "model",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            List<HeadlessRunEvent> events = new ArrayList<>();
            HeadlessAgentRunner.Options options = new HeadlessAgentRunner.Options(
                    "review this", SESSION, false, null, null,
                    HeadlessAgentRunner.OutputMode.JSON, project, 0, null, null, null,
                    events::add, config, null, false, false, null, false)
                    .withWebInput(new WebChatInput(WebChatInput.VERSION, "review this", "", SESSION))
                    .withSessionStateStore(store);

            HeadlessAgentRunner.Result result = new HeadlessAgentRunner().run(options);

            assertEquals(0, result.exitCode(), result.text());
            assertEquals("marker-reviewer", events.get(0).metadata().get("agent"));
            assertEquals("marker-reviewer", events.get(0).metadata().get("role"));
            assertFalse(requests.isEmpty(), "the provider was never called");
            String instructions = instructions(turnRequest(requests));
            assertTrue(instructions.contains(MARKER), instructions);
        } finally {
            server.stop(0);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"high", "default"})
    void durableWebThinkingReachesTheProvider(String thinking, @TempDir Path project) throws Exception {
        ChatSessionStateStore store = new ChatSessionStateStore(project.resolve("state"));
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                requests.add(mapper.readTree(exchange.getRequestBody()));
                byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},"
                        + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            ChatConfig config = new ChatConfig("openai", "fixture-key", "gpt-5.5",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            config.setThinking("low");
            WebCommandResolver.Resolution selected = WebCommandResolver.resolve(
                    new WebChatInput(WebChatInput.VERSION, "/thinking " + thinking, "", SESSION),
                    project, store, config);
            assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, selected.status());
            HeadlessAgentRunner.Options options = new HeadlessAgentRunner.Options(
                    "hello", SESSION, false, null, null, HeadlessAgentRunner.OutputMode.JSON,
                    project, 0, null, null, null, event -> {}, config, null, false, false, null, false)
                    .withWebInput(new WebChatInput(WebChatInput.VERSION, "hello", "", SESSION))
                    .withSessionStateStore(store);
            HeadlessAgentRunner.Result result = new HeadlessAgentRunner().run(options);
            assertEquals(0, result.exitCode(), result.text());
            assertFalse(requests.isEmpty());
            JsonNode turn = turnRequest(requests);
            if ("default".equals(thinking)) assertFalse(turn.has("reasoning_effort"));
            else assertEquals(thinking, turn.path("reasoning_effort").asText());
        } finally { server.stop(0); }
    }

    /**
     * The turn's own request. A new web chat's title request runs on its own thread beside the turn, so
     * either can reach the provider first.
     */
    private static JsonNode turnRequest(List<JsonNode> requests) {
        return requests.stream()
                .filter(request -> !instructions(request).contains("Generate a concise, specific chat title"))
                .findFirst().orElseThrow(() -> new AssertionError("only the title request reached the provider"));
    }

    /** Every instruction-bearing part of an OpenAI-compatible request. */
    private static String instructions(JsonNode request) {
        StringBuilder text = new StringBuilder();
        text.append(request.path("instructions").toString());
        text.append(request.path("system").toString());
        for (JsonNode message : request.path("messages")) {
            String role = message.path("role").asText();
            if ("system".equals(role) || "developer".equals(role)) {
                text.append(message.path("content").toString());
            }
        }
        return text.toString();
    }
}
