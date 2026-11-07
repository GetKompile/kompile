package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.main.chat.agent.AgentRunController;
import ai.kompile.cli.main.chat.agent.SubagentResumeMetadata;
import ai.kompile.cli.main.chat.agent.SubagentResumeService;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** A real host tool + native child, with only the child's provider replaced by local HTTP. */
@TemporaryUserHome
class HeadlessDurableSubagentsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private String seed(Path project, String parent) throws Exception {
        String id = "subagent-browser-" + UUID.randomUUID();
        Path conversations = KompileHome.homeDirectory().toPath().resolve("conversations");
        Files.createDirectories(conversations);
        Files.writeString(conversations.resolve(id + ".txt"), "Started: now\nCWD: " + project + "\n\n> old child prompt\n\n< old child answer\n");
        var service = new SubagentResumeService(conversations, mapper);
        service.registerTranscript(id, "general", "fixture-model", "original-parent");
        service.writeMetadata(service.metadataFor(id).withRoute("custom", "api-key"));
        Files.writeString(conversations.resolve(parent + ".txt"), "CWD: " + project + "\n< PARENT-CONTENT-MUST-NOT-CHANGE\n");
        return id;
    }

    private HeadlessAgentRunner.Options options(Path project, String parent, String id, ChatConfig config,
                                                List<HeadlessRunEvent> events, AgentRunController controls, long timeout) {
        String raw = "/subagents resume " + mapper.createObjectNode().put("session_id", id).put("prompt", "follow up from browser");
        return new HeadlessAgentRunner.Options("", parent, true, null, null,
                HeadlessAgentRunner.OutputMode.JSON, project, timeout, null, null, controls,
                events::add, config, null, false, false, null, false)
                .withWebInput(new WebChatInput(1, raw, "must never reach a model", parent));
    }

    @Test void actualResumeRunsChildOnceWithoutPrimaryRequestOrTranscriptMutation(@TempDir Path project) throws Exception {
        String parent = "browser-parent-" + UUID.randomUUID();
        String id = seed(project, parent);
        var requests = new CopyOnWriteArrayList<String>();
        var events = new CopyOnWriteArrayList<HeadlessRunEvent>();
        AtomicBoolean completedAfterProvider = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"child continued\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            completedAfterProvider.set(true);
        });
        server.start();
        try {
            ChatConfig config = new ChatConfig("custom", "fixture-key", "fixture-model",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            config.setChatMode("standard");
            Path parentTranscript = KompileHome.homeDirectory().toPath().resolve("conversations/" + parent + ".txt");
            String original = Files.readString(parentTranscript);
            var result = new HeadlessAgentRunner().run(options(project, parent, id, config, events, null, 20_000));
            assertEquals(0, result.exitCode(), result.text());
            assertTrue(result.text().contains("child continued"), result.text());
            assertTrue(completedAfterProvider.get());
            assertEquals(1, requests.size(), "only the resumed child's request, never parent/title requests");
            assertTrue(requests.get(0).contains("old child prompt"));
            assertTrue(requests.get(0).contains("follow up from browser"));
            assertFalse(requests.get(0).contains("PARENT-CONTENT"));
            assertFalse(requests.get(0).contains("must never reach a model"));
            assertEquals(original, Files.readString(parentTranscript));
            var service = new SubagentResumeService(mapper);
            assertEquals(parent, service.metadataFor(id).parentSession());
            assertFalse(service.isLocked(id), "normal tool cleanup must release the child lease");
            var command = events.stream().filter(e -> e.type() == HeadlessRunEvent.Type.COMMAND_OUTCOME).toList();
            assertEquals(1, command.size());
            assertEquals("resume", command.get(0).data().path("action").asText());
            assertEquals(id, command.get(0).data().path("sessionId").asText());
            assertTrue(events.stream().noneMatch(e -> e.type() == HeadlessRunEvent.Type.TOKEN_USAGE),
                    "the primary has no model usage to report");
        } finally { server.stop(0); }
    }

    @Test void persistedWebPermissionDenialReachesTheActualHostEvenWithSkipPermissions(@TempDir Path project) throws Exception {
        String parent = "browser-permission-" + UUID.randomUUID();
        String id = seed(project, parent);
        var principal = new ai.kompile.cli.main.chat.tools.ToolContext(parent, new ai.kompile.cli.main.chat.agent.AgentRegistry().get("coder"),
                new ai.kompile.cli.main.chat.permission.PermissionService(), project, null);
        assertEquals(WebCommandResolver.Status.COMPLETED, WebPermissionSettings.resolve(
                "save {\"overrides\":{\"task\":\"deny\"},\"confirmed\":true}", principal, true).status());
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = "data: {\"choices\":[{\"delta\":{\"content\":\"should not run\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            ChatConfig config = new ChatConfig("custom", "fixture-key", "fixture-model", "http://127.0.0.1:" + server.getAddress().getPort());
            var events = new CopyOnWriteArrayList<HeadlessRunEvent>();
            var base = options(project, parent, id, config, events, null, 5000);
            var opts = new HeadlessAgentRunner.Options("", parent, false, "coder", null, HeadlessAgentRunner.OutputMode.JSON,
                    project, 5000, null, null, null, events::add, config, null, false, false, null, true).withWebInput(base.webInput());
            assertNotEquals(0, new HeadlessAgentRunner().run(opts).exitCode());
            assertEquals(0, requests.get(), "permission denial must prevent the child's provider request");
            assertEquals("original-parent", new SubagentResumeService(mapper).metadataFor(id).parentSession());
            assertFalse(new SubagentResumeService(mapper).isLocked(id));
        } finally { server.stop(0); }
    }

    @Test void stoppedRunCannotInvokeChildAndDoesNotMutateMain(@TempDir Path project) throws Exception {
        String parent = "browser-stop-" + UUID.randomUUID();
        String id = seed(project, parent);
        var controls = new AgentRunController(AgentRunController.Mode.AUTO);
        controls.stop();
        var events = new CopyOnWriteArrayList<HeadlessRunEvent>();
        ChatConfig config = new ChatConfig("custom", "fixture-key", "fixture-model", "http://127.0.0.1:1");
        config.setChatMode("standard");
        var service = new SubagentResumeService(mapper);
        String ledger = Files.readString(KompileHome.homeDirectory().toPath().resolve("conversations/" + id + ".ledger.jsonl"));
        var result = new HeadlessAgentRunner().run(options(project, parent, id, config, events, controls, 1000));
        assertNotEquals(0, result.exitCode());
        assertEquals("original-parent", service.metadataFor(id).parentSession());
        assertFalse(service.isLocked(id));
        assertEquals(ledger, Files.readString(KompileHome.homeDirectory().toPath().resolve("conversations/" + id + ".ledger.jsonl")));
        assertTrue(events.stream().noneMatch(e -> e.type() == HeadlessRunEvent.Type.ASSISTANT_DELTA));
    }

    @Test void timeoutCancelsHostOwnedContinuationAndReleasesChildLock(@TempDir Path project) throws Exception {
        String parent = "browser-timeout-" + UUID.randomUUID();
        String id = seed(project, parent);
        var events = new CopyOnWriteArrayList<HeadlessRunEvent>();
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var release = new java.util.concurrent.CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            ChatConfig config = new ChatConfig("custom", "fixture-key", "fixture-model",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            config.setChatMode("standard");
            long start = System.nanoTime();
            var result = new HeadlessAgentRunner().run(options(project, parent, id, config, events, null, 500));
            assertNotEquals(0, result.exitCode());
            assertTrue(result.text().contains("timed out"), result.text());
            assertTrue(java.time.Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5);
            assertTrue(requests.get() <= 1, "timeout must never trigger a primary or retry request");
            assertFalse(new SubagentResumeService(mapper).isLocked(id));
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void snapshotIncludesDurableChildrenWithoutModelConfiguration(@TempDir Path project) throws Exception {
        String parent = "browser-config-" + UUID.randomUUID();
        String id = seed(project, parent);
        var events = new CopyOnWriteArrayList<HeadlessRunEvent>();
        var options = new HeadlessAgentRunner.Options("", parent, false, null, null,
                HeadlessAgentRunner.OutputMode.JSON, project, 0, null, null, null,
                events::add, null, null, false, false, null, false)
                .withWebInput(new WebChatInput(1, "", "", parent, true));
        assertEquals(0, new HeadlessAgentRunner().run(options).exitCode());
        var data = events.stream().filter(e -> e.type() == HeadlessRunEvent.Type.COMMAND_OUTCOME)
                .findFirst().orElseThrow().data().path("durableSubagents");
        assertEquals("durable-subagents", data.path("menu").asText());
        assertEquals("list", data.path("action").asText());
        assertTrue(java.util.stream.StreamSupport.stream(data.path("children").spliterator(), false)
                .anyMatch(child -> id.equals(child.path("sessionId").asText())));
    }

    @Test void listAndShowDoNotRequireModelConfiguration(@TempDir Path project) throws Exception {
        String parent = "browser-read-" + UUID.randomUUID();
        String id = seed(project, parent);
        for (String raw : new String[]{"/subagents saved", "/subagents show " + id}) {
            var events = new CopyOnWriteArrayList<HeadlessRunEvent>();
            var options = new HeadlessAgentRunner.Options("", parent, false, null, null,
                    HeadlessAgentRunner.OutputMode.JSON, project, 0, null, null, null,
                    events::add, null, null, false, false, null, false)
                    .withWebInput(new WebChatInput(1, raw, "", parent));
            assertEquals(0, new HeadlessAgentRunner().run(options).exitCode());
            assertEquals(1, events.stream().filter(e -> e.type() == HeadlessRunEvent.Type.COMMAND_OUTCOME).count());
            assertTrue(events.stream().noneMatch(e -> e.type() == HeadlessRunEvent.Type.ASSISTANT_DELTA));
        }
    }
}
