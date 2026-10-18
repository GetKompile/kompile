package ai.kompile.app.services.agent;

import ai.kompile.app.web.controllers.ProductDomainProxyController;
import ai.kompile.app.web.dto.AgentChatRequest;
import ai.kompile.cli.common.ProductChatPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Real disposable worker JVM + loopback POST handler, not a mock of quarantine/admission. */
class ProductDomainAdmissionTest {
    private static final String RUNTIME = "643b9814-ea85-401c-9a33-160f84955aa2";
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @Test void acceptedPostCanCommitAfterProxyTimeoutButCannotAdmitReplayModelOrDomainWorkUntilExplicitClose() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1), allowCommit = new CountDownLatch(1), committed = new CountDownLatch(1);
        AtomicInteger posts = new AtomicInteger(), revisions = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                assertEquals("POST", exchange.getRequestMethod());
                assertEquals(RUNTIME, exchange.getRequestHeaders().getFirst("X-Elthoria-Runtime-Id"));
                exchange.getRequestBody().readAllBytes(); posts.incrementAndGet(); accepted.countDown();
                try { if (!allowCommit.await(18, TimeUnit.SECONDS)) return; }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                revisions.incrementAndGet(); committed.countDown(); // Remote mutation survives client cancel/timeout.
                byte[] bytes = "{\"revision\":1}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try { exchange.getResponseBody().write(bytes); } catch (IOException clientGone) { }
            } finally { exchange.close(); }
        });
        server.start();
        var executor = Executors.newFixedThreadPool(2);
        Process child = null;
        ProcessHandle mcp = null;
        try (ProductChatSessionService service = new ProductChatSessionService(json, policy())) {
            ObjectNode connection = connection("http://127.0.0.1:" + server.getAddress().getPort());
            child = ProductWorkerProcessFixture.start("transport-tree", Base64.getEncoder().encodeToString(json.writeValueAsBytes(connection)));
            Process launched = child;
            KompileCliSessionWorker worker = new KompileCliSessionWorker(json, service.policy(), service.sessionId(),
                    () -> List.of("fixture"), (args, root) -> launched);
            var binding = ProductChatSessionService.class.getDeclaredField("worker"); binding.setAccessible(true); binding.set(service, worker);
            worker.open();
            try (var descendants = child.descendants()) { mcp = descendants.findFirst().orElseThrow(); }
            assertTrue(mcp.isAlive()); assertNotEquals(ProcessHandle.current().pid(), mcp.pid());
            ProductDomainProxyController controller = new ProductDomainProxyController(service);
            var relay = executor.submit(() -> controller.proxy(RUNTIME, request("POST")));
            assertTrue(accepted.await(3, TimeUnit.SECONDS), "The POST must actually reach the remote handler");
            assertTrue(child.isAlive());
            // Even a reference fetched before domain capture cannot bypass the worker's external lease.
            assertThrows(IllegalStateException.class, () -> worker.submit(id("prefetched-owner"), "inspect", new SseEmitter()));
            worker.expireIdle(System.nanoTime() + TimeUnit.HOURS.toNanos(1));
            assertEquals("idle", worker.status()); assertTrue(child.isAlive());
            CountDownLatch modelAttempt = new CountDownLatch(1);
            AtomicReference<Thread> modelThread = new AtomicReference<>();
            var model = executor.submit(() -> {
                modelThread.set(Thread.currentThread()); modelAttempt.countDown();
                return assertThrows(ResponseStatusException.class, () -> service.stream(turn(service, "blocked-model")));
            });
            assertTrue(modelAttempt.await(1, TimeUnit.SECONDS));
            await(() -> modelThread.get().getState() == Thread.State.BLOCKED);
            var response = relay.get(12, TimeUnit.SECONDS);
            assertEquals(504, response.getStatusCode().value());
            assertEquals("upstream_timeout", json.readTree(response.getBody()).path("code").asText());
            assertEquals(409, model.get(2, TimeUnit.SECONDS).getStatusCode().value());
            assertTrue(worker.quarantined()); assertEquals("quarantined", worker.status());
            assertTrue(child.isAlive(), "Unknown remote outcome must not automatically kill/recreate the CLI");
            assertTrue(mcp.isAlive(), "The actual owned MCP-like descendant must also remain alive");
            assertFalse(worker.exited());
            assertThrows(IOException.class, () -> KompileCliSessionWorker.Lease.acquire(directory));
            allowCommit.countDown(); assertTrue(committed.await(3, TimeUnit.SECONDS));
            assertEquals(1, revisions.get()); assertEquals(1, posts.get());
            for (String name : List.of("blocked-model", "distinct-after-unknown")) {
                assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.stream(turn(service, name))).getStatusCode().value());
            }
            assertEquals(409, assertThrows(ResponseStatusException.class, service::openSession).getStatusCode().value());
            assertThrows(ResponseStatusException.class, () -> service.readCapability("connection"));
            assertThrows(ResponseStatusException.class, () -> service.reconnect("harness-product-" + id("blocked-model"), 0));
            assertThrows(ResponseStatusException.class, () -> service.cancel("harness-product-" + id("blocked-model")));
            assertEquals(409, controller.proxy(RUNTIME, request("POST")).getStatusCode().value());
            assertEquals(409, controller.proxy(RUNTIME, request("GET")).getStatusCode().value());
            assertEquals(1, posts.get(), "Neither a repeated POST nor model turn may be replayed");
            assertTrue(worker.activeRun().isEmpty());
            worker.expireIdle(System.nanoTime() + TimeUnit.HOURS.toNanos(1));
            assertTrue(child.isAlive(), "Even idle expiry must preserve quarantined ownership"); assertTrue(mcp.isAlive());
            service.closeSession();
            await(() -> worker.status().equals("closed"));
            assertFalse(child.isAlive()); assertFalse(mcp.isAlive()); assertTrue(worker.exited());
            try (var lease = KompileCliSessionWorker.Lease.acquire(directory)) { assertNotNull(lease); }
        } finally {
            allowCommit.countDown(); server.stop(0); executor.shutdownNow();
            if (child != null && child.isAlive()) {
                // Only this fixture's descendants, while their real parent can still reap them.
                try (var descendants = child.descendants()) {
                    for (ProcessHandle owned : descendants.toList()) {
                        assertNotEquals(ProcessHandle.current().pid(), owned.pid());
                        if (owned.isAlive()) owned.destroyForcibly();
                        try { owned.onExit().get(3, TimeUnit.SECONDS); } catch (Exception ignored) { }
                    }
                }
                child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS);
            }
        }
    }

    private ProductChatPolicy policy() throws Exception {
        Files.writeString(directory.resolve("role.md"), "Fixture product role");
        Files.writeString(directory.resolve("mcp.json"), "{}");
        ObjectNode manifest = json.createObjectNode().put("schemaVersion", ProductChatPolicy.SCHEMA)
                .put("id", "elthoria-play").put("title", "Elthoria").put("role", "elthoria")
                .put("roleFile", "role.md").put("mcpConfigFile", "mcp.json").put("assetsDirectory", "assets")
                .put("idleTimeoutSeconds", 60).put("turnTimeoutSeconds", 60);
        manifest.putArray("nativeTools"); manifest.putArray("mcpServers").add("elthoria");
        manifest.putObject("readCapabilities").put("connection", "mcp__elthoria__get_play_connection");
        Path file = directory.resolve("product.json"); Files.writeString(file, json.writeValueAsString(manifest));
        return ProductChatPolicy.load(file, directory);
    }
    private ObjectNode connection(String origin) {
        return json.createObjectNode().put("schemaVersion", "elthoria.play-connection.v1").put("status", "READY")
                .put("apiBaseUrl", origin).put("runtimeId", RUNTIME).put("transport", "stdio").put("lifecycle", "HARNESS_CHILD")
                .put("localOnly", true).put("authentication", "NOT_PROVIDED").put("tacticalAudience", "GM")
                .put("tacticalPlayerSafe", false).put("uiStarted", false);
    }
    private static MockHttpServletRequest request(String method) {
        String path = "/api/agents/chat/product/domain/" + RUNTIME + "/api/tactical-boards/1";
        MockHttpServletRequest request = new MockHttpServletRequest(method, path + (method.equals("POST") ? "/commands" : ""));
        if (method.equals("POST")) { request.setContentType("application/json"); request.setContent("{}".getBytes(StandardCharsets.UTF_8)); }
        else request.setQueryString("campaignId=1");
        return request;
    }
    private static AgentChatRequest turn(ProductChatSessionService service, String name) {
        AgentChatRequest request = new AgentChatRequest(); request.setSkipPermissions(false);
        request.setMessage("inspect"); request.setSessionId(service.sessionId()); request.setTurnId(id(name));
        return request;
    }
    private static String id(String name) { return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString().replace("-", ""); }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Condition did not settle");
    }
}
