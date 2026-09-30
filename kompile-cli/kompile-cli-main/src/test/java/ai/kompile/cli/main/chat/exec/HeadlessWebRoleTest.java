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
            String instructions = instructions(requests.get(0));
            assertTrue(instructions.contains(MARKER), instructions);
        } finally {
            server.stop(0);
        }
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
