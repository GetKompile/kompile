package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class HeadlessChatTitleTest {
    @Test
    @Timeout(30)
    void webTitleUsesAnIsolatedRequestAndRestoresManualNames(@TempDir Path project) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                JsonNode request = mapper.readTree(exchange.getRequestBody());
                requests.add(request);
                boolean title = request.path("messages").toString().contains("Generate a concise, specific chat title");
                String text = title ? "Repair login authentication" : "main answer";
                byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"" + text
                        + "\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            String id = UUID.randomUUID().toString();
            ChatConfig config = new ChatConfig("custom", null, "configured-model",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            List<HeadlessRunEvent> events = new CopyOnWriteArrayList<>();
            String prompt = "Please investigate why login authentication fails";
            HeadlessAgentRunner.Options first = new HeadlessAgentRunner.Options(
                    prompt, id, false, null, null, HeadlessAgentRunner.OutputMode.QUIET,
                    project, 0, null, null, null, events::add, config, null,
                    false, false, null, false)
                    .withWebInput(new WebChatInput(WebChatInput.VERSION, prompt, "", id));
            assertEquals(0, new HeadlessAgentRunner().run(first).exitCode());
            assertEquals(2, requests.size(), requests.toString());
            JsonNode titleRequest = requests.stream().filter(r -> r.path("messages").toString()
                    .contains("Generate a concise, specific chat title")).findFirst().orElseThrow();
            assertEquals("configured-model", titleRequest.path("model").asText());
            assertFalse(titleRequest.has("tools"), titleRequest.toString());
            assertEquals(2, titleRequest.path("messages").size());
            assertTrue(titleRequest.path("messages").get(1).path("content").asText().contains(prompt));
            ChatHistory history = new ChatHistory(id);
            assertEquals("Repair login authentication", history.readSessionTitle());
            assertEquals(2, history.readTurns().size(), "Utility request must not become a chat turn");
            assertEquals(List.of(prompt, "Repair login authentication"), events.stream()
                    .filter(e -> e.type() == HeadlessRunEvent.Type.SESSION_TITLE).map(HeadlessRunEvent::text).toList());

            history.renameSession("My custom name");
            events.clear();
            HeadlessAgentRunner.Options resumed = new HeadlessAgentRunner.Options(
                    "continue", id, true, null, null, HeadlessAgentRunner.OutputMode.QUIET,
                    project, 0, null, null, null, events::add, config, null,
                    false, false, null, false)
                    .withWebInput(new WebChatInput(WebChatInput.VERSION, "continue", "", id));
            assertEquals(0, new HeadlessAgentRunner().run(resumed).exitCode());
            assertEquals(3, requests.size(), "Resume must not generate another title");
            assertEquals("My custom name", history.readSessionTitle());
            assertEquals(List.of("My custom name"), events.stream()
                    .filter(e -> e.type() == HeadlessRunEvent.Type.SESSION_TITLE).map(HeadlessRunEvent::text).toList());
            history.close();
        } finally { server.stop(0); }
    }
}
