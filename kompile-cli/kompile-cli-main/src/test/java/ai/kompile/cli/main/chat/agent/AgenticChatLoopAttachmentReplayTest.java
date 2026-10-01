package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.context.ConversationLedger;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A follow-up still shows the model the images earlier turns attached. The web chat runs
 * the CLI once per message with {@code --resume}, so every follow-up is a new process that
 * rebuilds the provider history from the conversation ledger.
 */
@TemporaryUserHome
class AgenticChatLoopAttachmentReplayTest {

    private static final String MODEL = "vision-test-model";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13};
    private static final String PNG_BASE64 = Base64.getEncoder().encodeToString(PNG);
    private static final String ANSWER =
            "{\"content\":\"ok\",\"rawText\":\"ok\",\"finishReason\":\"stop\",\"toolCalls\":[]}";

    @TempDir
    Path workingDirectory;

    private final ObjectMapper mapper = JsonUtils.standardMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final String sessionId = "attachment-replay-" + UUID.randomUUID();
    private volatile boolean visionModel = true;
    private HttpServer server;

    @BeforeEach
    void startServing() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/llm/status", exchange -> respond(exchange,
                "{\"loaded\":true,\"modelId\":\"" + MODEL + "\",\"supportsImageInput\":" + visionModel
                        + ",\"maxContextLength\":32768,\"maxOutputTokens\":4096}"));
        server.createContext("/api/llm/chat", exchange -> {
            try {
                requests.add(mapper.readTree(exchange.getRequestBody()));
            } finally {
                respond(exchange, ANSWER);
            }
        });
        server.start();
        ChatCompleter.setContentOutput(ignored -> { });
    }

    @AfterEach
    void stopServing() {
        server.stop(0);
        ChatCompleter.setContentOutput(null);
        ChatCompleter.setActivity(null);
    }

    @Test
    void aFollowUpInANewProcessResendsTheImageAnEarlierTurnAttached() throws Exception {
        try (DirectLlmClient client = client()) {
            AgenticChatLoop loop = loop(client);
            loop.setPendingAttachments(List.of(new DirectLlmClient.AttachmentInput(
                    "report.png", "image/png", true, PNG_BASE64, null)));
            loop.chat("what is in it?", sessionId, "coder", "default", false);
        }
        assertImage(lastUser(requestEndingWith("what is in it?")));

        try (DirectLlmClient client = client()) {
            loop(client).chat("and the total?", sessionId, "coder", "default", false);
        }
        JsonNode followUp = requestEndingWith("and the total?");
        assertImage(userMessageContaining(followUp, "what is in it?"));
        assertTrue(lastUser(followUp).path("images").isMissingNode(),
                "the follow-up attached nothing itself: " + lastUser(followUp));
    }

    @Test
    void resumingAnAnsweredTurnResendsItsAttachments() throws Exception {
        recordTurn(true);

        JsonNode earlier = userMessageContaining(resumeAndAsk(), "what is in it?");

        assertImage(earlier);
        assertEquals("[File: notes.txt]\nmargin notes\n\nwhat is in it?", earlier.path("content").asText());
    }

    @Test
    void anUnansweredTurnIsResentWithMarkersInsteadOfItsAttachments() throws Exception {
        recordTurn(false);

        JsonNode earlier = userMessageContaining(resumeAndAsk(), "what is in it?");

        assertTrue(earlier.path("images").isMissingNode(), earlier.toString());
        assertEquals("what is in it?\n[Attached image: report.png]\n[Attached file: notes.txt]",
                earlier.path("content").asText());
    }

    @Test
    void aModelThatTakesNoImagesGetsAMarkerAndStillTheTextAttachment() throws Exception {
        visionModel = false;
        recordTurn(true);

        JsonNode earlier = userMessageContaining(resumeAndAsk(), "what is in it?");

        assertTrue(earlier.path("images").isMissingNode(), earlier.toString());
        assertEquals("[File: notes.txt]\nmargin notes\n\nwhat is in it?\n[Attached image: report.png]",
                earlier.path("content").asText());
    }

    @Test
    void aStoredImageThatIsGoneIsResentAsAMarker() throws Exception {
        CompactionService.Attachment image = recordTurn(true);
        Files.delete(KompileHome.homeDirectory().toPath().resolve("conversations")
                .resolve(sessionId + ".attachments").resolve(image.digest()));

        JsonNode earlier = userMessageContaining(resumeAndAsk(), "what is in it?");

        assertTrue(earlier.path("images").isMissingNode(), earlier.toString());
        assertEquals("[File: notes.txt]\nmargin notes\n\nwhat is in it?\n[Attached image: report.png]",
                earlier.path("content").asText());
    }

    @Test
    void aTurnThatSentOnlyAnImageIsResentWithIt() throws Exception {
        ConversationLedger ledger = new ConversationLedger(mapper);
        ledger.configureSession(sessionId);
        ledger.append(CompactionService.ConversationEntry.user("", List.of(
                ledger.storeAttachment("report.png", "image/png", true, PNG))));
        ledger.append(CompactionService.ConversationEntry.assistant("A quarterly report."));

        JsonNode messages = resumeAndAsk().path("request").path("messages");

        assertEquals("user", messages.path(1).path("role").asText(), messages.toString());
        assertImage(messages.path(1));
    }

    /** What an earlier process left: a turn with an image and a text file, answered or not. */
    private CompactionService.Attachment recordTurn(boolean answered) {
        ConversationLedger ledger = new ConversationLedger(mapper);
        ledger.configureSession(sessionId);
        CompactionService.Attachment image = ledger.storeAttachment("report.png", "image/png", true, PNG);
        CompactionService.Attachment notes = ledger.storeAttachment("notes.txt", "text/plain", false,
                "margin notes".getBytes(StandardCharsets.UTF_8));
        ledger.append(CompactionService.ConversationEntry.user("what is in it?", List.of(image, notes)));
        if (answered) ledger.append(CompactionService.ConversationEntry.assistant("A quarterly report."));
        return image;
    }

    /** A new process resumes the session and asks a follow-up; the request it sent. */
    private JsonNode resumeAndAsk() throws Exception {
        try (DirectLlmClient client = client()) {
            loop(client);
            client.streamChat("and the total?", "system", null, null);
        }
        return requestEndingWith("and the total?");
    }

    private DirectLlmClient client() {
        ChatConfig config = new ChatConfig("kompile-local", null, MODEL,
                "http://127.0.0.1:" + server.getAddress().getPort());
        config.setDefaultMemory(false);
        DirectLlmClient client = new DirectLlmClient(config, mapper, workingDirectory);
        client.setOutputConsumer(ignored -> { });
        return client;
    }

    private AgenticChatLoop loop(DirectLlmClient client) {
        AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                new PermissionService(), new AgentRegistry(), workingDirectory, client, null);
        loop.configureConversationSession(sessionId);
        return loop;
    }

    /** The latest request whose current turn is this message. */
    private JsonNode requestEndingWith(String message) {
        for (int index = requests.size() - 1; index >= 0; index--) {
            JsonNode request = requests.get(index);
            if (lastUser(request).path("content").asText().contains(message)) return request;
        }
        return fail("no request's current turn is \"" + message + "\": " + requests);
    }

    private static JsonNode lastUser(JsonNode request) {
        JsonNode last = MissingNode.getInstance();
        for (JsonNode message : request.path("request").path("messages")) {
            if ("user".equals(message.path("role").asText())) last = message;
        }
        return last;
    }

    private static JsonNode userMessageContaining(JsonNode request, String text) {
        for (JsonNode message : request.path("request").path("messages")) {
            if ("user".equals(message.path("role").asText())
                    && message.path("content").asText().contains(text)) {
                return message;
            }
        }
        return fail("no user message contains \"" + text + "\": " + request);
    }

    private static void assertImage(JsonNode message) {
        assertEquals(1, message.path("images").size(), message.toString());
        JsonNode image = message.path("images").path(0);
        assertEquals("image/png", image.path("mimeType").asText());
        assertEquals(PNG_BASE64, image.path("base64Data").asText());
        assertTrue(image.path("path").isMissingNode(), "the wire carries no local path: " + image);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }
}
