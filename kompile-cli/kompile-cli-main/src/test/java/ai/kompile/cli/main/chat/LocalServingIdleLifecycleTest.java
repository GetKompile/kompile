package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.harness.JudgeBackendFactory;
import ai.kompile.cli.main.project.LocalCrawlServingSession;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real HTTP clients and pool lifecycle without loading a model. */
@ResourceLock("local-serving-runtime-pool")
class LocalServingIdleLifecycleTest {
    private static final long IDLE_MILLIS = 200;
    private final ObjectMapper mapper = JsonUtils.standardMapper();
    private final List<FakeProcess> processes = new CopyOnWriteArrayList<>();
    private final List<LocalServingRuntimePool.RuntimeRequest> launches = new CopyOnWriteArrayList<>();
    private final List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch requestEntered = new CountDownLatch(1);
    private volatile CountDownLatch allowResponse = new CountDownLatch(0);
    private volatile int responseStatus = 200;
    @TempDir Path directory;
    private Path model;
    private Path tokenizer;

    @BeforeEach
    void setUp() throws IOException {
        model = Files.writeString(directory.resolve("model.gguf"), "test model");
        tokenizer = Files.writeString(directory.resolve("tokenizer.json"), "{}");
        LocalServingRuntimePool.resetForTests();
        LocalServingRuntimePool.setIdleMillisForTests(IDLE_MILLIS);
        LocalServingRuntimePool.setMaxRuntimesForTests(1);
        LocalServingRuntimePool.setStarterForTests(this::startFakeRuntime);
    }

    @AfterEach
    void tearDown() {
        allowResponse.countDown();
        LocalServingRuntimePool.resetForTests();
        processes.forEach(FakeProcess::destroy);
    }

    @Test
    void openChatClientAllowsIdleShutdownAndTransparentlyReplaysHistoryAfterRestart() throws Exception {
        ChatConfig config = boundChatConfig();
        try (DirectLlmClient client = client(config)) {
            awaitStopped(processes.get(0));
            assertEquals("ok", client.streamChat("first turn", "system", null, null).text);
            awaitStopped(processes.get(1));
            assertEquals("ok", client.streamChat("second turn", "system", null, null).text);
            awaitStopped(processes.get(2));
        }
        assertEquals(3, launches.size());
        for (LocalServingRuntimePool.RuntimeRequest request : launches) {
            assertEquals("model", request.modelId());
            assertEquals(model, request.modelPath());
            assertEquals(tokenizer, request.tokenizerPath());
            assertEquals(Map.of("dspEnabled", true), request.runtimeOptions());
            assertEquals(5, request.timeoutSeconds());
        }
        assertEquals(2, requests.size());
        String replay = requests.get(1).body().toString();
        assertTrue(replay.contains("first turn"), replay);
        assertTrue(replay.contains("second turn"), replay);
        assertTrue(replay.contains("assistant"), replay);
    }

    @Test
    void judgeClientRetainsRestartBindingForInheritedLocalEndpoint() throws Exception {
        ChatConfig config = boundChatConfig();
        try (DirectLlmClient judge = JudgeBackendFactory.createDirectJudgeClient(
                config, null, null, "model", null, mapper, directory)) {
            judge.setOutputConsumer(ignored -> { });
            awaitStopped(processes.get(0));
            assertEquals("ok", judge.streamChat("judge", "system", null, null).text);
            awaitStopped(processes.get(1));
        }
        assertEquals(model, launches.get(1).modelPath());
        assertEquals(Map.of("dspEnabled", true), launches.get(1).runtimeOptions());
    }

    @Test
    void judgeModelOverrideDoesNotReuseTheMainModelsExplicitArtifacts() throws Exception {
        ChatConfig config = boundChatConfig();
        try (DirectLlmClient judge = JudgeBackendFactory.createDirectJudgeClient(
                config, null, null, "other-model", null, mapper, directory)) {
            judge.setOutputConsumer(ignored -> { });
            awaitStopped(processes.get(0));
            assertEquals("ok", judge.streamChat("judge", "system", null, null).text);
            awaitStopped(processes.get(1));
        }
        LocalServingRuntimePool.RuntimeRequest request = launches.get(1);
        assertEquals("other-model", request.modelId());
        assertNull(request.modelPath());
        assertNull(request.tokenizerPath());
        assertFalse(request.runtimeOptions().containsKey("dspEnabled"));
        assertEquals(5, request.timeoutSeconds());
    }

    @Test
    void runtimeBindingIsCopiedInMemoryButNeverPersisted() throws Exception {
        ChatConfig config = boundChatConfig();
        String serialized = mapper.writeValueAsString(config);
        assertFalse(serialized.contains("localServingBinding"), serialized);
        assertNull(mapper.readValue(serialized, ChatConfig.class).getLocalServingBinding());
        ChatConfig copy = new ChatConfig();
        copy.applyLlmSettingsFrom(config);
        assertSame(config.getLocalServingBinding(), copy.getLocalServingBinding());
        copy.applyLlmSettingsFrom(new ChatConfig("openai", "test", "remote-model", "http://example.invalid"));
        assertNull(copy.getLocalServingBinding());
    }

    @Test
    void openCrawlSessionAllowsIdleShutdownAndRefreshesRequestCorrelationAfterRestart() throws Exception {
        try (LocalCrawlServingSession session = crawlSession()) {
            FakeProcess first = processes.get(0);
            String firstRunId = session.subprocessRunId();
            // Availability and status inspection must not count as model activity.
            while (!first.stopped.await(20, TimeUnit.MILLISECONDS)) {
                assertTrue(session.isAvailable());
                assertEquals(firstRunId, session.subprocessRunId());
                assertTrue(System.nanoTime() - first.createdNanos < TimeUnit.SECONDS.toNanos(5));
            }
            assertTrue(session.isAvailable());
            assertEquals("ok", session.generate("crawl", 16));
            assertNotEquals(firstRunId, session.subprocessRunId());
            assertTrue(session.subprocessLogPath().contains(session.subprocessRunId()));
            ReceivedRequest request = requests.get(0);
            assertEquals(session.subprocessRunId(), request.runId());
            assertFalse(request.transportId().isBlank());
            String body = request.body().toString();
            assertTrue(body.contains("job"), body);
            assertTrue(body.contains("kb"), body);
            awaitStopped(processes.get(1));
            session.close();
            assertFalse(session.isAvailable());
            assertThrows(IOException.class, () -> session.generate("closed", 16));
            assertEquals(2, launches.size());
        }
    }

    @Test
    void activeChatRequestOutlivesIdleTimeoutButReleasesModelWhenItFinishes() throws Exception {
        ChatConfig config = boundChatConfig();
        try (DirectLlmClient client = client(config)) {
            assertActiveRequestProtected(() -> client.streamChat("slow", "system", null, null).text);
        }
    }

    @Test
    void activeCrawlRequestOutlivesIdleTimeoutButReleasesModelWhenItFinishes() throws Exception {
        try (LocalCrawlServingSession session = crawlSession()) {
            assertActiveRequestProtected(() -> session.generate("slow", 16));
        }
    }

    @Test
    void failedChatResponseReleasesModelWhileClientRemainsOpen() throws Exception {
        responseStatus = 400;
        ChatConfig config = boundChatConfig();
        try (DirectLlmClient client = client(config)) {
            assertTrue(client.streamChat("fail", "system", null, null).failed);
            awaitStopped(processes.get(processes.size() - 1));
        }
    }

    @Test
    void failedCrawlResponseReleasesModelWhileSessionRemainsOpen() throws Exception {
        responseStatus = 400;
        try (LocalCrawlServingSession session = crawlSession()) {
            assertThrows(IOException.class, () -> session.generate("fail", 16));
            awaitStopped(processes.get(processes.size() - 1));
        }
    }

    private void assertActiveRequestProtected(Callable<String> call) throws Exception {
        allowResponse = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var response = executor.submit(call);
            assertTrue(requestEntered.await(5, TimeUnit.SECONDS), "request never reached the runtime");
            FakeProcess active = processes.get(processes.size() - 1);
            assertFalse(active.stopped.await(IDLE_MILLIS * 2, TimeUnit.MILLISECONDS),
                    "idle reaper stopped an active inference request");
            allowResponse.countDown();
            assertEquals("ok", response.get(5, TimeUnit.SECONDS));
            awaitStopped(active);
        } finally {
            allowResponse.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private ChatConfig boundChatConfig() throws Exception {
        ChatConfig config = new ChatConfig("kompile-local", null, "model", null);
        config.setAuthenticationMethod("none");
        try (LocalServingRuntimePool.Lease runtime = LocalServingRuntimePool.acquire(
                "model", model, tokenizer, Map.of("dspEnabled", true), 5)) {
            runtime.applyTo(config);
        }
        return config;
    }

    private DirectLlmClient client(ChatConfig config) {
        DirectLlmClient client = new DirectLlmClient(config, mapper);
        client.setOutputConsumer(ignored -> { });
        return client;
    }

    private LocalCrawlServingSession crawlSession() throws Exception {
        return LocalCrawlServingSession.start(directory, "model", Map.of(
                "localPath", model.toString(), "crawlJobId", "job", "knowledgeBaseId", "kb"), 5);
    }

    private KompileLocalServingBootstrap.StartupResult startFakeRuntime(
            LocalServingRuntimePool.RuntimeRequest request) throws KompileLocalServingBootstrap.BootstrapException {
        try {
            launches.add(request);
            String runId = "run-" + launches.size();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/llm/", exchange -> {
                try {
                    JsonNode body = mapper.readTree(exchange.getRequestBody());
                    requests.add(new ReceivedRequest(body,
                            exchange.getRequestHeaders().getFirst("X-Kompile-Subprocess-Run-Id"),
                            exchange.getRequestHeaders().getFirst("X-Kompile-Transport-Request-Id")));
                    requestEntered.countDown();
                    try {
                        if (!allowResponse.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("test response gate timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                    byte[] response = (responseStatus == 200
                            ? "{\"content\":\"ok\",\"generatedText\":\"ok\",\"rawText\":\"ok\",\"toolCalls\":[],\"finishReason\":\"completed\"}"
                            : "{\"error\":\"synthetic failure\"}").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(responseStatus, response.length);
                    exchange.getResponseBody().write(response);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            FakeProcess process = new FakeProcess(server);
            processes.add(process);
            return new KompileLocalServingBootstrap.StartupResult(request.modelId(), request.modelPath(),
                    request.tokenizerPath(), URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    new KompileLocalServingBootstrap.LauncherArtifact(directory.resolve("serving"), true),
                    process, null, null, runId, directory.resolve(runId + ".log"), null, null, null);
        } catch (IOException failure) {
            throw new KompileLocalServingBootstrap.BootstrapException("fake runtime startup failed", failure);
        }
    }

    private static void awaitStopped(FakeProcess process) throws InterruptedException {
        assertTrue(process.stopped.await(5, TimeUnit.SECONDS), "runtime remained resident after idle timeout");
    }

    private record ReceivedRequest(JsonNode body, String runId, String transportId) { }

    private static final class FakeProcess extends Process {
        private final HttpServer server;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final long createdNanos = System.nanoTime();

        private FakeProcess(HttpServer server) { this.server = server; }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public int waitFor() throws InterruptedException { stopped.await(); return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return stopped.await(timeout, unit);
        }
        @Override public int exitValue() {
            if (alive.get()) throw new IllegalThreadStateException("still running");
            return 0;
        }
        @Override public void destroy() {
            if (alive.compareAndSet(true, false)) {
                server.stop(0);
                stopped.countDown();
            }
        }
        @Override public Process destroyForcibly() { destroy(); return this; }
    }
}
