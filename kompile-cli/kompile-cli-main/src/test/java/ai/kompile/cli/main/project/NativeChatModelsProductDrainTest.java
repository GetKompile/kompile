package ai.kompile.cli.main.project;

import ai.kompile.cli.common.ProductChatPolicy;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.ProductContentSessionTest;
import ai.kompile.cli.main.chat.tools.ProductManagedWorkTracker;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.grounding.PipelineTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/client/native-call lifecycle; no fake HostExecutor and no external inference. */
@TemporaryUserHome
class NativeChatModelsProductDrainTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private JsonNode success(PipelineTool tool, ObjectNode request, ToolContext context) throws Exception {
        ToolResult result = tool.execute(request, context);
        assertFalse(result.isError(), result.getOutput());
        return mapper.readTree(result.getOutput());
    }

    private ToolContext context(ProductManagedWorkTracker tracker) {
        ToolContext context = new ToolContext("native-http-drain", AgentConfig.builder("native-http-drain")
                .enabledTools(Set.of("pipeline")).build(), new PermissionService(), root, new ToolRegistry(mapper));
        context.setAutoApproveAll(true);
        context.linkProductManagedWork(tracker);
        return context;
    }

    private String start(PipelineTool tool, ToolContext context) throws Exception {
        ObjectNode definition = mapper.createObjectNode().put("pipelineId", "native-http-drain")
                .put("kind", "LLM").put("topology", "SEQUENCE");
        definition.putObject("processor").put("type", "CHAT_MODEL").put("provider", "custom")
                .put("modelId", "fixture-only-model");
        ObjectNode create = mapper.createObjectNode().put("action", "create");
        create.set("definition", definition);
        success(tool, create, context);
        ObjectNode run = mapper.createObjectNode().put("action", "run").put("pipelineId", "native-http-drain");
        run.putObject("input").put("text", "Local fixture only");
        return success(tool, run, context).path("runId").asText();
    }

    // Reuse the immutable product fixture and real host approval API. Only its probe
    // profile is changed; its PDF/generation plan is not used by these probe tests.
    private ProductContentSessionTest.Fixture probeFixture(String provider) throws Exception {
        var fixture = new ProductContentSessionTest.Fixture(root);
        fixture.profile.put("provider", provider).put("modelId", "fixture-only-model");
        ObjectNode revision = mapper.createObjectNode().put("schemaVersion", 1).put("profileId", "profile-one")
                .put("revision", 1).put("sha256", ProductContentSessionTest.digest(fixture.profile));
        revision.set("profile", fixture.profile);
        ProductContentSessionTest.write(root.resolve("data/content/profiles/profile-one/1/manifest.json"), revision);
        return fixture;
    }

    private ObjectNode probeRequest(String provider) {
        return mapper.createObjectNode().put("action", "capabilities").put("provider", provider)
                .put("model", "fixture-only-model").put("operation", "pdf").put("probe", true)
                .put("probeTimeoutSeconds", 2);
    }

    private void approve(ToolRegistry registry, ObjectNode request, ToolContext context) throws Exception {
        JsonNode pending = mapper.readTree(registry.get("pipeline").execute(request, context).getOutput());
        assertEquals("NATIVE_APPROVAL_REQUIRED", pending.path("code").asText(), pending.toString());
        assertEquals("APPROVED", registry.productContentSession()
                .resolveApproval(pending.path("approvalId").asText(), true).path("status").asText());
    }

    @Test void registryProbeTimeoutWaitsForPhysicalHttpWorkerWithoutContentManager() throws Exception {
        assertProbeDrain(false);
    }

    @Test void approvedContentProbeTimeoutWaitsForPhysicalHttpWorker() throws Exception {
        assertProbeDrain(true);
    }

    private void assertProbeDrain(boolean contentAuthority) throws Exception {
        CountDownLatch readerEntered = new CountDownLatch(1), readerInterrupted = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1), releaseServer = new CountDownLatch(1);
        CountDownLatch delegateReturned = new CountDownLatch(1), guardedReturned = new CountDownLatch(1);
        CountDownLatch quiescent = new CountDownLatch(1);
        AtomicReference<Thread> ownedReader = new AtomicReference<>();
        AtomicReference<ToolResult> probeResult = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean(), returnedWithActiveRequest = new AtomicBoolean();
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            mapper.readTree(exchange.getRequestBody());
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write("data: {\"choices\":[{\"delta\":{\"content\":\"fixture\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
                try { releaseServer.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            } catch (IOException disconnected) { /* Local cancellation may close this stream. */ }
            finally { exchange.close(); }
        });
        server.start();
        Thread owner = null, drain = null;
        try (AutoCloseable fixtureClient = NativeChatModels.withRequestClientFactoryForTest(root,
                (config, json, policy, project) -> new DirectLlmClient(config, json, project) {
                    @Override public void setOutputConsumer(Consumer<String> consumer) {
                        super.setOutputConsumer(chunk -> {
                            ownedReader.set(Thread.currentThread());
                            readerEntered.countDown();
                            boolean interrupted = false;
                            while (true) {
                                try { releaseReader.await(); break; }
                                catch (InterruptedException cancelled) {
                                    interrupted = true;
                                    readerInterrupted.countDown();
                                }
                            }
                            if (interrupted) Thread.currentThread().interrupt();
                            consumer.accept(chunk);
                        });
                    }
                })) {
            var fixture = probeFixture("custom");
            new ChatConfig("custom", null, "fixture-only-model",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1").saveProject(root);
            PipelineTool pipeline = new PipelineTool(mapper);
            ToolRegistry registry = ToolRegistry.createProduct(() -> new ToolRegistry(mapper));
            registry.register(new CliTool() {
                public String id() { return pipeline.id(); }
                public String description() { return pipeline.description(); }
                public JsonNode parameterSchema() { return pipeline.parameterSchema(); }
                public String permissionKey() { return pipeline.permissionKey(); }
                public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
                    try {
                        ToolResult result = pipeline.execute(params, context); // Actual synchronous capabilities/probe.
                        probeResult.set(result);
                        return result;
                    } finally { delegateReturned.countDown(); }
                }
            });
            ProductManagedWorkTracker tracker;
            ToolContext context = fixture.context(registry);
            if (contentAuthority) {
                fixture.bind(registry);
                tracker = registry.productContentSession().tracker();
            } else {
                // Isolate the registry backstop from ProductContentSession's own drain.
                // This is the supported legacy policy shape, not a production consent bypass.
                ObjectNode legacy = (ObjectNode) mapper.readTree(fixture.policy.manifestPath().toFile());
                legacy.remove("nativeActionPolicy");
                ProductContentSessionTest.write(root.resolve("legacy-policy.json"), legacy);
                registry.freezeProductPolicy(ProductChatPolicy.load(root.resolve("legacy-policy.json"), root), Set.of("pipeline"));
                registry.openProductTurn();
                tracker = new ProductManagedWorkTracker();
                context.linkProductManagedWork(tracker);
            }
            ObjectNode request = probeRequest("custom");
            if (contentAuthority) approve(registry, request, context);
            assertNull(probeResult.get(), "Approval must not execute the delegate");
            owner = new Thread(() -> {
                try { registry.get("pipeline").execute(request, context); }
                catch (Throwable problem) { failure.set(problem); }
                finally {
                    returnedWithActiveRequest.set(NativeChatModels.hasActiveRequest(Thread.currentThread()));
                    interruptPreserved.set(Thread.currentThread().isInterrupted());
                    guardedReturned.countDown();
                }
            }, "product-probe-owner");
            owner.setDaemon(true);
            owner.start();
            assertTrue(readerEntered.await(5, TimeUnit.SECONDS), "The real HTTP response must reach the client callback");
            assertTrue(delegateReturned.await(10, TimeUnit.SECONDS), "The actual PipelineTool probe must time out");
            assertNotNull(probeResult.get());
            assertFalse(probeResult.get().isError(), probeResult.get().getOutput());
            JsonNode selection = mapper.readTree(probeResult.get().getOutput()).path("chatModel").path("selection");
            assertEquals("TIMEOUT", selection.path("failureKind").asText(), selection.toString());
            assertEquals("FAILED", selection.path("probeStatus").asText());
            assertEquals("UNKNOWN", selection.path("readiness").asText());
            assertTrue(readerInterrupted.await(5, TimeUnit.SECONDS));
            assertTrue(ownedReader.get().isAlive());
            assertTrue(NativeChatModels.hasActiveRequest(owner));
            assertTrue(tracker.cancelAndAwait(Duration.ZERO), "The empty job tracker alone cannot prove probe cleanup");
            registry.stopProductAdmission();
            assertThrows(IllegalStateException.class, registry::openProductTurn, "The real guarded call must remain active");
            owner.interrupt(); // Interruption during drain must be retained, never mistaken for completion.
            drain = new Thread(() -> { registry.awaitProductQuiescence(); quiescent.countDown(); }, "product-probe-drain");
            drain.setDaemon(true);
            drain.start();
            assertFalse(guardedReturned.await(100, TimeUnit.MILLISECONDS));
            assertFalse(quiescent.await(100, TimeUnit.MILLISECONDS));
            registry.markProductUnknownOutcome();
            releaseReader.countDown();
            assertTrue(guardedReturned.await(5, TimeUnit.SECONDS));
            assertTrue(quiescent.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertFalse(returnedWithActiveRequest.get());
            assertTrue(interruptPreserved.get());
            assertTrue(registry.hasProductUnknownOutcome(), "Local drain must not clear a remote unknown outcome");
            assertThrows(IllegalStateException.class, registry::openProductTurn);
            assertEquals(1, requests.get(), "Timeout/drain must not submit a replacement request");
        } finally {
            releaseReader.countDown();
            releaseServer.countDown();
            try {
                if (owner != null) { owner.join(5000); assertFalse(owner.isAlive()); }
                if (drain != null) { drain.join(5000); assertFalse(drain.isAlive()); }
                Thread reader = ownedReader.get();
                if (reader != null) { reader.join(5000); assertFalse(reader.isAlive()); }
            } finally { server.stop(0); }
        }
    }

    @Test void actualProductProbeAndCatalogRefuseCliSelectionAfterManagerPrecheck() throws Exception {
        var fixture = probeFixture("claude");
        PipelineTool pipeline = new PipelineTool(mapper);
        ToolRegistry registry = ToolRegistry.createProduct(() -> new ToolRegistry(mapper));
        AtomicInteger delegates = new AtomicInteger(), requestClients = new AtomicInteger();
        registry.register(new CliTool() {
            public String id() { return pipeline.id(); }
            public String description() { return pipeline.description(); }
            public JsonNode parameterSchema() { return pipeline.parameterSchema(); }
            public String permissionKey() { return pipeline.permissionKey(); }
            public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
                delegates.incrementAndGet();
                // Model an operator config edit AFTER the manager's pinned HTTP precheck.
                try { claudeConfig("native").saveProject(root); }
                catch (IOException failure) { throw new AssertionError(failure); }
                return pipeline.execute(params, context);
            }
        });
        fixture.bind(registry);
        ToolContext context = fixture.context(registry);
        try (AutoCloseable clientFixture = NativeChatModels.withRequestClientFactoryForTest(root,
                (config, json, policy, project) -> {
                    requestClients.incrementAndGet();
                    throw new AssertionError("Product CLI selection must be refused before creating a request client");
                })) {
            ObjectNode metadata = probeRequest("claude").put("probe", false).put("operation", "text");
            ObjectNode live = probeRequest("claude");
            ObjectNode catalog = probeRequest("claude").put("action", "list_models");
            for (ObjectNode request : List.of(metadata, live, catalog)) {
                claudeConfig("api-key").saveProject(root);
                if (request != metadata) approve(registry, request, context);
                ToolResult refused = registry.get("pipeline").execute(request, context);
                assertTrue(refused.isError(), refused.getOutput());
                assertTrue(refused.getOutput().contains("in-host HTTP route, not CLAUDE_CLI"), refused.getOutput());
                assertFalse(NativeChatModels.hasActiveRequest(Thread.currentThread()));
            }
            assertEquals(3, delegates.get(), "Refusal must happen at the real execution boundary, not only the manager");
            assertEquals(0, requestClients.get());
            // Ordinary native metadata still resolves the same CLI route; no live CLI is invoked.
            assertEquals("CLAUDE_CLI", NativeChatModels.probe(root, "claude", "fixture-only-model", "text",
                    false, Duration.ofSeconds(1)).get("protocol"));
            ChatConfig noModel = claudeConfig("native");
            noModel.setModel(null);
            noModel.saveProject(root);
            IOException refused = assertThrows(IOException.class, () -> NativeChatModels.listModels(root, "claude", true));
            assertTrue(refused.getMessage().contains("in-host HTTP route, not CLAUDE_CLI"));
        }
    }

    private ChatConfig claudeConfig(String authentication) {
        ChatConfig config = new ChatConfig("anthropic", null, "fixture-only-model", "http://127.0.0.1:1/v1");
        config.setAuthenticationMethod(authentication);
        return config;
    }

    @Test void withheldHttpResponseCancelsActualClientWithoutOwningRemoteHandler() throws Exception {
        CountDownLatch requestEntered = new CountDownLatch(1), releaseServer = new CountDownLatch(1);
        CountDownLatch serverExited = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            mapper.readTree(exchange.getRequestBody());
            requestEntered.countDown();
            try {
                releaseServer.await(); // Withhold all response headers/body, not just completion text.
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); serverExited.countDown(); }
        });
        server.start();
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        try {
            new ChatConfig("custom", null, "fixture-only-model",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1").saveProject(root);
            PipelineTool tool = new PipelineTool(mapper); // No client factory or fake host boundary.
            ToolContext context = context(tracker);
            String id = start(tool, context);
            assertTrue(requestEntered.await(5, TimeUnit.SECONDS));
            assertTrue(tracker.cancelAndAwait(Duration.ofSeconds(5)), "The actual interruptible HTTP client must drain");
            assertEquals(1L, serverExited.getCount(), "A remote server handler is not an owned client worker");
            JsonNode terminal = success(tool, mapper.createObjectNode().put("action", "status").put("runId", id), context);
            assertEquals("CANCELLED", terminal.path("status").asText(), terminal.toString());
            assertFalse(terminal.has("output"));
        } finally {
            releaseServer.countDown();
            try { assertTrue(tracker.cancelAndAwait(Duration.ofSeconds(5))); }
            finally { server.stop(0); }
        }
    }

    @Test void cancelledFutureDoesNotConfirmCleanupUntilOwnedRealHttpReaderExits() throws Exception {
        CountDownLatch releaseReader = new CountDownLatch(1), readerEntered = new CountDownLatch(1);
        CountDownLatch readerInterrupted = new CountDownLatch(1), releaseServer = new CountDownLatch(1);
        CountDownLatch serverExited = new CountDownLatch(1);
        AtomicReference<Thread> ownedReader = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            mapper.readTree(exchange.getRequestBody());
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(("data: {\"choices\":[{\"delta\":{\"content\":\"partial fixture\"}}]}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                output.flush();
                // The remote handler is intentionally still alive after LOCAL cleanup confirms.
                // It is not an owned client/auth worker and must not control the cleanup verdict.
                try { releaseServer.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            } catch (IOException disconnectedClient) {
                // Expected when the cancelled local client closes its response stream.
            } finally { serverExited.countDown(); }
        });
        server.start();
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        try (AutoCloseable fixture = NativeChatModels.withRequestClientFactoryForTest(root,
                (config, json, policy, project) -> new DirectLlmClient(config, json, project) {
                    @Override public void setOutputConsumer(Consumer<String> consumer) {
                        super.setOutputConsumer(chunk -> {
                            // This is the actual HTTP reader callback, entered only after a real
                            // HTTP response. Deliberately resist interruption to exercise drain
                            // accounting, not to claim that a real socket/auth library resists it.
                            Thread reader = Thread.currentThread();
                            ownedReader.set(reader);
                            readerEntered.countDown();
                            boolean interrupted = false;
                            while (true) {
                                try { releaseReader.await(); break; }
                                catch (InterruptedException cancelled) {
                                    interrupted = true;
                                    readerInterrupted.countDown();
                                }
                            }
                            if (interrupted) reader.interrupt();
                            consumer.accept(chunk);
                        });
                    }
                })) {
            new ChatConfig("custom", null, "fixture-only-model",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1").saveProject(root);
            ToolContext context = context(tracker);
            PipelineTool tool = new PipelineTool(mapper); // Real default host executor and native call.
            String id = start(tool, context);
            assertTrue(tracker.owns("pipeline", id));
            assertTrue(readerEntered.await(5, TimeUnit.SECONDS), "Real HTTP response never reached the owned reader");
            Thread reader = ownedReader.get();
            assertNotNull(reader);
            assertEquals("mcp-native-chat", reader.getName());
            assertTrue(reader.isAlive());
            assertEquals(1, requests.get());

            assertFalse(tracker.cancelAndAwait(Duration.ofMillis(100)),
                    "A cancelled Future is not proof that its owned native HTTP worker exited");
            assertTrue(readerInterrupted.await(5, TimeUnit.SECONDS), "Native request Future.cancel(true) must interrupt this worker");
            assertTrue(reader.isAlive(), "The resistant LOCAL reader must still physically exist");
            assertFalse(tracker.cancelAndAwait(Duration.ZERO));
            assertEquals(1L, serverExited.getCount());

            releaseReader.countDown();
            assertTrue(tracker.cancelAndAwait(Duration.ofSeconds(5)), "Cleanup must confirm once the owned reader exits");
            reader.join(2000);
            assertFalse(reader.isAlive());
            assertEquals(1L, serverExited.getCount(), "Remote handler lifetime is not local ownership");
            JsonNode terminal = success(tool, mapper.createObjectNode().put("action", "status").put("runId", id), context);
            assertEquals("CANCELLED", terminal.path("status").asText(), terminal.toString());
            assertFalse(terminal.has("output"));
            assertEquals(1, requests.get(), "Cancellation must not trigger another request/model");
        } finally {
            releaseReader.countDown();
            releaseServer.countDown();
            try {
                assertTrue(tracker.cancelAndAwait(Duration.ofSeconds(5)));
                Thread reader = ownedReader.get();
                if (reader != null) {
                    reader.join(2000);
                    assertFalse(reader.isAlive());
                }
            } finally { server.stop(0); }
        }
    }
}
