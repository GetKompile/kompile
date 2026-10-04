package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.ChatCommand;
import ai.kompile.cli.main.chat.ToolCallIndex;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.enforcer.JudgementLog;
import ai.kompile.cli.main.chat.enforcer.JudgementRecord;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.TestMilestoneTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The web chat's insights drawer between runs. No harness holds the session then, so the drawer
 * asks with a one-shot {@code insightsQuery} input, invoked as the chat app invokes the harness,
 * and gets the panel the terminal's dashboard area shows: this session's judge flags, tool calls,
 * the latest test result and crawls. A read never reaches the model.
 */
@TemporaryUserHome
class WebInsightsQueryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private final InputStream originalIn = System.in;
    private final AtomicInteger modelRequests = new AtomicInteger();
    private HttpServer server;

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        System.setIn(originalIn);
        if (server != null) server.stop(0);
    }

    @Test
    @Timeout(60)
    void readsThisSessionsPanelWithoutAModelTurn(@TempDir Path project) throws Exception {
        setUp(project);
        String sessionId = UUID.randomUUID().toString();
        JudgementLog.forSession(sessionId).record(JudgementRecord.builder().phase("JUDGE_TOOL").judgeMode("llm")
                .compliant(false).severity("HIGH").toolName("bash")
                .violations(List.of("deleted files outside the project")).latencyMs(40).build());
        JudgementLog.forSession("another-session").record(JudgementRecord.builder().phase("JUDGE_TOOL")
                .judgeMode("llm").compliant(false).severity("LOW").toolName("edit")
                .violations(List.of("edited a generated file")).latencyMs(30).build());
        ToolCallIndex index = ToolCallIndex.getInstance();
        index.record(sessionId, "read", "{\"file_path\":\"a.txt\"}", "claude", "mcp-stdio", false, 120);
        index.record(sessionId, "bash", "{\"command\":\"false\"}", "claude", "mcp-stdio", true, 900);
        ToolContext context = new ToolContext(sessionId, null, null, project, null);
        context.setAutoApproveAll(true);
        assertFalse(new TestMilestoneTool().execute(MAPPER.readTree("{\"action\":\"record\",\"module\":\"core\","
                + "\"total_tests\":10,\"passed\":10,\"commit\":\"" + COMMIT + "\",\"branch\":\"feature\"}"),
                context).isError());

        Run read = insights(project, sessionId);

        assertEquals(0, read.exitCode(), read.toString());
        JsonNode command = read.event("command");
        assertEquals("/insights", command.path("command").asText());
        assertEquals("COMPLETED", command.path("status").asText(), command.toString());
        JsonNode data = command.path("data");
        assertEquals("insights", data.path("menu").asText(), command.toString());
        assertEquals(sessionId, data.path("sessionId").asText());
        assertTrue(data.path("available").asBoolean(), command.toString());
        assertEquals("kompile.dashboard.v1", data.path("schemaVersion").asText());
        assertEquals("Session insights", data.path("title").asText());
        assertFalse(data.path("live").asBoolean(), "no crawl is running");
        List<String> lines = strings(data.path("lines"));
        assertEquals(6, lines.size(), lines.toString());
        assertTrue(lines.get(0).startsWith("Judge: 1 flagged of 1 verdict · last flag "), lines.get(0));
        assertTrue(lines.get(0).endsWith(": bash (high)"), lines.get(0));
        assertEquals("↳ deleted files outside the project", lines.get(1), "this session's flag, not another's");
        assertTrue(lines.get(2).startsWith("Tools: 2 calls, 1 error · p50 "), lines.get(2));
        assertTrue(lines.get(3).startsWith("↳ last error ") && lines.get(3).contains(": bash"), lines.get(3));
        assertTrue(lines.get(4).startsWith("Tests: core "), lines.get(4));
        assertEquals("Crawl: no recent crawl jobs", lines.get(5));
        assertEquals(String.join("\n", lines), command.path("text").asText(), "the text is the same rows");
        assertEquals(0, modelRequests.get(), "an insights read never reaches the model");
    }

    @Test
    @Timeout(60)
    void aReadWithoutASessionIsRefused(@TempDir Path project) throws Exception {
        setUp(project);

        Run read = insights(project, " ");

        assertEquals(2, read.exitCode(), read.toString());
        JsonNode refusal = read.event("command");
        assertEquals("/insights", refusal.path("command").asText());
        assertEquals("INVALID", refusal.path("status").asText());
        assertEquals("Session insights need a session id.", refusal.path("text").asText());
        assertTrue(refusal.path("data").isMissingNode(), refusal.toString());
        assertEquals(0, modelRequests.get());
    }

    @Test
    void anInsightsReadCarriesASessionAndNothingElse() {
        WebChatInput read = WebChatInput.parse("{\"version\":1,\"sessionId\":\" s \",\"insightsQuery\":true}");
        assertTrue(read.insightsQuery());
        assertEquals("s", read.sessionId());
        assertEquals("", read.rawInput());
        assertFalse(read.configQuery());
        assertNull(read.workflowApprove());
        assertTrue(WebChatInput.parse("{\"version\":1,\"rawInput\":\"\",\"insightsQuery\":true}").insightsQuery());
        assertFalse(WebChatInput.parse("{\"version\":1,\"rawInput\":\"hello\"}").insightsQuery());
        for (String invalid : List.of(
                "{\"version\":1,\"rawInput\":\"hello\",\"insightsQuery\":true}",
                "{\"version\":1,\"configQuery\":true,\"insightsQuery\":true}",
                "{\"version\":1,\"workflowApprove\":\"\",\"insightsQuery\":true}",
                "{\"version\":1,\"insightsQuery\":\"true\"}",
                "{\"version\":1,\"insightsQuery\":1}",
                "{\"version\":1,\"insightsQuery\":null}",
                "{\"version\":1,\"configQuery\":\"true\"}",
                "{\"version\":1,\"insightsQuery\":false}")) {
            assertThrows(IllegalArgumentException.class, () -> WebChatInput.parse(invalid), invalid);
        }
    }

    @Test
    @Timeout(60)
    void aTopicReportIsTheInsightsToolsAnswerOverEverySession(@TempDir Path project) throws Exception {
        setUp(project);
        ToolContext context = new ToolContext(UUID.randomUUID().toString(), null, null, project, null);
        context.setAutoApproveAll(true);
        assertFalse(new TestMilestoneTool().execute(MAPPER.readTree("{\"action\":\"record\",\"module\":\"core\","
                + "\"total_tests\":10,\"passed\":10,\"commit\":\"" + COMMIT + "\",\"branch\":\"feature\"}"),
                context).isError());

        Run read = topicReport(project, "tests", null);

        assertEquals(0, read.exitCode(), read.toString());
        JsonNode command = read.event("command");
        assertEquals("/insights", command.path("command").asText());
        assertEquals("COMPLETED", command.path("status").asText(), command.toString());
        JsonNode data = command.path("data");
        assertEquals("insights", data.path("menu").asText(), command.toString());
        assertEquals("tests", data.path("topic").asText());
        assertTrue(data.path("available").asBoolean(), command.toString());
        assertTrue(data.path("sessionId").isMissingNode(), "a topic report is not one session's panel");
        assertTrue(data.path("headline").asText().startsWith("Tests, all time: 1 run of 1 module"), data.toString());
        assertTrue(data.path("text").asText().contains("core"), data.toString());
        assertEquals(data.path("text").asText(), command.path("text").asText(), "the text is the report");

        // The question reaches the source: naming the module narrows the report to it. A plain word
        // such as "core" names it only next to a marker, so ordinary English never narrows by accident.
        JsonNode asked = topicReport(project, "tests", "how are the core tests doing").event("command").path("data");
        assertTrue(asked.path("headline").asText().startsWith("Tests for core, all time: "), asked.toString());
        assertEquals(0, modelRequests.get(), "a topic report never reaches the model");
    }

    @Test
    @Timeout(60)
    void anUnknownTopicIsRefusedWithTheTopicsThereAre(@TempDir Path project) throws Exception {
        setUp(project);

        Run read = topicReport(project, "weather", null);

        assertEquals(2, read.exitCode(), read.toString());
        JsonNode refusal = read.event("command");
        assertEquals("/insights", refusal.path("command").asText());
        assertEquals("INVALID", refusal.path("status").asText());
        String text = refusal.path("text").asText();
        assertTrue(text.startsWith("Unknown insights topic 'weather'. Topics: "), text);
        assertTrue(text.contains("crawl") && text.contains("graph"), text);
        assertTrue(refusal.path("data").isMissingNode(), refusal.toString());
        assertEquals(0, modelRequests.get());
    }

    @Test
    void aTopicAndAQuestionRideOnlyOnAnInsightsRead() {
        WebChatInput report = WebChatInput.parse("{\"version\":1,\"insightsQuery\":true,\"insightsTopic\":\" crawl \","
                + "\"insightsQuestion\":\" failed crawls last 7 days \"}");
        assertEquals("crawl", report.insightsTopic());
        assertEquals("failed crawls last 7 days", report.insightsQuestion());
        assertEquals("", report.sessionId(), "a topic report needs no session");
        assertNull(WebChatInput.parse("{\"version\":1,\"insightsQuery\":true,\"insightsTopic\":\"crawl\","
                + "\"insightsQuestion\":\" \"}").insightsQuestion(), "a blank question is no question");
        assertNull(WebChatInput.parse("{\"version\":1,\"sessionId\":\"s\",\"insightsQuery\":true}").insightsTopic());
        assertEquals("insightsTopic and insightsQuestion need insightsQuery", assertThrows(IllegalArgumentException.class,
                () -> WebChatInput.parse("{\"version\":1,\"rawInput\":\"hi\",\"insightsTopic\":\"crawl\"}")).getMessage());
        assertEquals("insightsQuestion needs an insightsTopic", assertThrows(IllegalArgumentException.class,
                () -> WebChatInput.parse("{\"version\":1,\"insightsQuery\":true,\"insightsQuestion\":\"why\"}"))
                .getMessage());
        for (String invalid : List.of(
                "{\"version\":1,\"insightsQuery\":true,\"insightsTopic\":\" \"}",
                "{\"version\":1,\"insightsQuery\":true,\"insightsTopic\":\"cra\\nwl\"}",
                "{\"version\":1,\"insightsQuery\":true,\"insightsTopic\":\"" + "t".repeat(65) + "\"}",
                "{\"version\":1,\"insightsQuery\":true,\"insightsTopic\":7}",
                "{\"version\":1,\"insightsQuery\":true,\"insightsTopic\":\"crawl\",\"insightsQuestion\":\""
                        + "q".repeat(1001) + "\"}")) {
            assertThrows(IllegalArgumentException.class, () -> WebChatInput.parse(invalid), invalid);
        }
    }

    private void setUp(Path project) throws Exception {
        server = fakeModel();
        // A saved project chat keeps every run off any provider key in the environment.
        new ChatConfig("custom", null, "lead-model", "http://127.0.0.1:" + server.getAddress().getPort())
                .save(ChatConfig.Scope.PROJECT, project);
    }

    /** The chat app's one-shot read between runs: the input is the first stdin line. */
    private Run insights(Path project, String sessionId) {
        ObjectNode input = MAPPER.createObjectNode().put("version", 1).put("sessionId", sessionId)
                .put("insightsQuery", true);
        return run(input.toString() + "\n", "--output-format", "stream-json", "--input-format", "web-json",
                "--working-dir", project.toString(), "--timeout", "30");
    }

    /** The chat app's insights page: one topic's report, over every session, in a one-shot run. */
    private Run topicReport(Path project, String topic, String question) {
        ObjectNode input = MAPPER.createObjectNode().put("version", 1).put("insightsQuery", true)
                .put("insightsTopic", topic);
        if (question != null) input.put("insightsQuestion", question);
        return run(input.toString() + "\n", "--output-format", "stream-json", "--input-format", "web-json",
                "--working-dir", project.toString(), "--timeout", "30");
    }

    private Run run(String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exitCode;
        try {
            exitCode = new CommandLine(new ChatCommand()).execute(args);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Run(exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** One headless invocation; every stdout line must be a JSON event. */
    private record Run(int exitCode, String stdout, String stderr) {
        JsonNode event(String type) {
            for (String line : stdout.lines().filter(line -> !line.isBlank()).toList()) {
                JsonNode event = assertDoesNotThrow(() -> MAPPER.readTree(line), line);
                if (type.equals(event.path("type").asText())) return event;
            }
            return fail("No " + type + " event: " + this);
        }
    }

    private HttpServer fakeModel() throws Exception {
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                modelRequests.incrementAndGet();
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        fake.start();
        return fake;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }
}
