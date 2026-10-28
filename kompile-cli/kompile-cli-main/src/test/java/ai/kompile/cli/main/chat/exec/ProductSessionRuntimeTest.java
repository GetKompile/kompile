package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.ProductChatPolicy;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.mcp.ProductMcpTestEndpoint;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real loop tool dispatch + in-memory MCP lifecycle tests; no model/process/service calls. */
class ProductSessionRuntimeTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    private ProductChatPolicy policy(int idle, int turn) throws Exception {
        Files.writeString(root.resolve("role.md"), "Frozen GM");
        Files.writeString(root.resolve("mcp.json"), "{}");
        Files.writeString(root.resolve("policy.json"), """
                {"schemaVersion":"kompile.product-chat-policy.v1","id":"play","title":"Play","role":"gm",
                "roleFile":"role.md","mcpConfigFile":"mcp.json","assetsDirectory":"assets","nativeTools":["read"],
                "mcpServers":["elthoria"],"readCapabilities":{"connection":"mcp__elthoria__get_play_connection"},
                "idleTimeoutSeconds":%d,"turnTimeoutSeconds":%d}
                """.formatted(idle, turn));
        return ProductChatPolicy.load(root.resolve("policy.json"), root);
    }

    private final class Session implements AutoCloseable {
        final PipedInputStream pipe = new PipedInputStream();
        final PipedOutputStream commands = new PipedOutputStream(pipe);
        final AtomicInteger inputCloses = new AtomicInteger();
        final List<HeadlessRunEvent> events = new CopyOnWriteArrayList<>();
        final AtomicReference<AtomicBoolean> signal = new AtomicReference<>(new AtomicBoolean());
        final ExecutorService owner = Executors.newSingleThreadExecutor(task -> { Thread t = new Thread(task); t.setDaemon(true); return t; });
        final ToolRegistry tools = ToolRegistry.createProduct(() -> new ToolRegistry(mapper));
        final AgentRegistry agents = new AgentRegistry();
        final PermissionService permissions = new PermissionService();
        final AgenticChatLoop loop = new AgenticChatLoop(null, mapper, tools, permissions, agents, root, null, null);
        final AtomicLong sequence = new AtomicLong();
        final ProductSessionRuntime runtime;
        Future<String> result;
        int cursor;

        Session(ProductChatPolicy policy) throws Exception {
            String open = "{\"version\":1,\"action\":\"session_open\",\"requestId\":\"open\",\"sessionId\":\"session-1\",\"policyDigest\":\"" + policy.digest() + "\"}\n";
            InputStream stream = new FilterInputStream(new SequenceInputStream(
                    new ByteArrayInputStream(open.getBytes(StandardCharsets.UTF_8)), pipe)) {
                public void close() throws IOException { inputCloses.incrementAndGet(); super.close(); }
            };
            runtime = ProductSessionRuntime.open(stream, policy);
        }
        void start(WebHarnessControls.Turn turn, ProductSessionRuntime.Read read, CliTool... mcpTools) {
            tools.freezeProductPolicy(runtime.policy(), Set.of("read"));
            for (CliTool tool : mcpTools) tools.registerProductMcp(tool, runtime.policy());
            result = owner.submit(() -> runtime.run(tools, turn, read,
                    event -> events.add(event.withSequence(sequence.incrementAndGet())),
                    next -> { signal.set(next); loop.setCancelSignal(next); }, loop::cancelActiveTurn));
        }
        ToolResult execute(String tool) throws Exception {
            ToolContext context = new ToolContext("session-1", agents.getDefault(), permissions, root, tools);
            context.setAutoApproveAll(true);
            return invokeTool(loop, tools.get(tool), context);
        }
        void send(String action, String request, String field, String value) throws Exception {
            var n = mapper.createObjectNode().put("version", 1).put("action", action).put("requestId", request);
            if (field != null) n.put(field, value);
            commands.write((n + "\n").getBytes(StandardCharsets.UTF_8));
            commands.flush();
        }
        HeadlessRunEvent await(String type, String request) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (System.nanoTime() < end) {
                while (cursor < events.size()) {
                    var event = events.get(cursor++);
                    if (event.type().name().equals(type) && (request == null || request.equals(event.data().path("requestId").asText()))) return event;
                }
                if (result.isDone()) result.get();
                Thread.sleep(5);
            }
            fail("Missing event " + type + " for " + request + ": " + events);
            return null;
        }
        public void close() throws Exception {
            commands.close();
            runtime.close();
            owner.shutdownNow();
        }
    }

    @Test void displayTurnIdentityIsTheActualAdmittedRequestNotReadAheadOrCapability() throws Exception {
        try (Session session = new Session(policy(10, 10))) {
            session.start(prompt -> {
                assertEquals("turn-1", session.runtime.currentTurnId());
                return "answer";
            }, capability -> {
                assertNull(session.runtime.currentTurnId());
                return ToolResult.success("{}");
            });
            session.await("SESSION_READY", "open"); session.await("SESSION_IDLE", null);
            assertNull(session.runtime.currentTurnId());
            session.send("turn_submit", "turn-1", "text", "inspect");
            session.await("TURN_COMPLETED", "turn-1"); session.await("SESSION_IDLE", null);
            assertNull(session.runtime.currentTurnId());
            session.send("read_capability", "read-1", "capability", "connection");
            session.await("CAPABILITY_RESULT", "read-1"); session.await("SESSION_IDLE", null);
            assertNull(session.runtime.currentTurnId());
            session.send("session_close", "close", null, null); session.result.get(4, TimeUnit.SECONDS);
        }
    }

    @Test void bootstrapHasNoPhantomTurnAndWaitsAcrossIdleThenReadsWithoutModel() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger(), reads = new AtomicInteger();
        try (Session session = new Session(policy(10, 10))) {
            session.start(prompt -> { modelCalls.incrementAndGet(); return "answer"; }, capability -> { reads.incrementAndGet(); return ToolResult.success("{\"runtimeId\":\"same-child\"}"); });
            session.await("SESSION_READY", "open");
            session.await("SESSION_IDLE", null);
            Thread.sleep(100);
            assertFalse(session.result.isDone());
            assertEquals(0, modelCalls.get());
            session.send("read_capability", "read-1", "capability", "connection");
            assertTrue(session.await("CAPABILITY_RESULT", "read-1").data().path("content").asText().contains("same-child"));
            session.await("SESSION_IDLE", null);
            assertEquals(0, modelCalls.get());
            assertEquals(1, reads.get());
            session.send("session_close", "close", null, null);
            session.result.get(4, TimeUnit.SECONDS);
            session.runtime.close();
            assertEquals(1, session.inputCloses.get());
            assertEquals(1, session.events.stream().filter(e -> e.type().name().equals("SESSION_CLOSED")).count());
            long expectedSequence = 1;
            for (HeadlessRunEvent event : session.events) {
                JsonNode wire = mapper.readTree(ExecJsonEvents.event(mapper, event));
                assertEquals(expectedSequence++, wire.path("seq").asLong());
                assertEquals(event.type().name().toLowerCase(java.util.Locale.ROOT), wire.path("type").asText());
                assertEquals(event.data(), wire.path("data"));
                assertFalse(wire.has("detail"));
            }
        }
    }

    @Test void legacyProductApprovalMetadataIsEmptyAndAuthorityFailsWithoutATurn() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Session session = new Session(policy(10, 10))) {
            session.start(prompt -> { calls.incrementAndGet(); return "answer"; }, capability -> {
                calls.incrementAndGet(); return ToolResult.success("{}");
            });
            session.await("SESSION_READY", "open"); session.await("SESSION_IDLE", null);
            session.send("content_approvals", "pending", null, null);
            JsonNode pending = session.await("CONTENT_APPROVALS", "pending").data();
            assertTrue(pending.path("approvals").isArray()); assertEquals(0, pending.path("approvals").size());
            session.send("content_approval", "allow", "text", "{\"approvalId\":\"foreign-or-expired\",\"allow\":true}");
            JsonNode refused = session.await("CONTENT_APPROVAL", "allow").data();
            assertFalse(refused.path("ok").asBoolean()); assertEquals("CONTENT_APPROVAL_DISABLED", refused.path("code").asText());
            session.send("content_approval", "deny", "text", "{\"approvalId\":\"foreign-or-expired\",\"allow\":false}");
            assertFalse(session.await("CONTENT_APPROVAL", "deny").data().path("ok").asBoolean());
            session.send("content_approvals", "pending", null, null);
            assertEquals("duplicate", session.await("PROTOCOL_ERROR", "pending").data().path("status").asText());
            assertEquals(0, calls.get());
            assertEquals(0, session.events.stream().filter(e -> e.type() == HeadlessRunEvent.Type.TURN_STARTED).count());
            session.send("session_close", "close", null, null); session.result.get(4, TimeUnit.SECONDS);
        }
    }

    @Test void explicitCloseDoesNotReadAheadIntoMonitorLockedNativeStyleStdin() throws Exception {
        ProductChatPolicy product = policy(10, 10);
        String wire = "{\"version\":1,\"action\":\"session_open\",\"requestId\":\"open\",\"sessionId\":\"session-1\",\"policyDigest\":\"" + product.digest() + "\"}\n"
                + "{\"version\":1,\"action\":\"session_close\",\"requestId\":\"close\"}\n";
        CountDownLatch unblock = new CountDownLatch(1);
        AtomicInteger readAhead = new AtomicInteger(), closes = new AtomicInteger();
        InputStream lockedInput = new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read() {
                if (pos >= count) {
                    readAhead.incrementAndGet();
                    while (unblock.getCount() != 0) {
                        try { unblock.await(); } catch (InterruptedException ignored) { }
                    }
                    return -1;
                }
                return super.read();
            }
            @Override public synchronized void close() { closes.incrementAndGet(); }
        };
        ProductSessionRuntime runtime = ProductSessionRuntime.open(lockedInput, product);
        ToolRegistry tools = ToolRegistry.createProduct(() -> new ToolRegistry(mapper));
        tools.freezeProductPolicy(product, Set.of("read"));
        List<HeadlessRunEvent> events = new CopyOnWriteArrayList<>();
        ExecutorService owner = Executors.newSingleThreadExecutor(task -> { Thread t = new Thread(task); t.setDaemon(true); return t; });
        try {
            Future<String> result = owner.submit(() -> runtime.run(tools, prompt -> { fail("No model turn during close"); return ""; },
                    capability -> { fail("No capability during close"); return null; }, events::add, next -> {}, () -> {}));
            result.get(3, TimeUnit.SECONDS);
            assertEquals(0, readAhead.get());
            assertEquals(1, closes.get());
            assertEquals(1, events.stream().filter(e -> e.type() == HeadlessRunEvent.Type.SESSION_CLOSED).count());
        } finally {
            unblock.countDown(); runtime.close(); owner.shutdownNow();
        }
    }

    private ToolResult invokeTool(AgenticChatLoop loop, CliTool tool, ToolContext context) throws Exception {
        var execute = AgenticChatLoop.class.getDeclaredMethod("executeToolInterruptibly", CliTool.class,
                JsonNode.class, ToolContext.class, String.class, java.util.function.Function.class);
        execute.setAccessible(true);
        try {
            return (ToolResult) execute.invoke(loop, tool, mapper.createObjectNode(), context, tool.id(),
                    (java.util.function.Function<ToolResult, Path>) result -> null);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw new AssertionError(e.getCause());
        }
    }

    @Test void cancelWaitsForActualToolExitAndSecondTurnGetsFreshSignal() throws Exception {
        cancelledProductToolRetainsMcpUntilCompletion(false);
    }

    @Test void interruptedTurnOwnerDoesNotInterruptProductMcpWorker() throws Exception {
        cancelledProductToolRetainsMcpUntilCompletion(true);
    }

    private void cancelledProductToolRetainsMcpUntilCompletion(boolean interruptOwner) throws Exception {
        CountDownLatch toolEntered = new CountDownLatch(1), toolRelease = new CountDownLatch(1);
        AtomicInteger turns = new AtomicInteger();
        AtomicBoolean workerInterrupted = new AtomicBoolean(), ownerInterruptRestored = new AtomicBoolean();
        AtomicReference<Thread> turnOwner = new AtomicReference<>();
        try (var mcp = new ProductMcpTestEndpoint(() -> {
            toolEntered.countDown();
            try { toolRelease.await(); }
            catch (InterruptedException e) { workerInterrupted.set(true); throw e; }
            return ToolResult.success("same MCP resource");
        }); Session session = new Session(policy(10, 10))) {
            session.start(prompt -> {
                int number = turns.incrementAndGet();
                if (number == 1) {
                    turnOwner.set(Thread.currentThread());
                    try { return session.execute(mcp.tool().id()).getOutput(); }
                    finally { ownerInterruptRestored.set(Thread.currentThread().isInterrupted()); }
                }
                assertFalse(session.signal.get().get());
                assertTrue(mcp.isOpen());
                return session.execute(mcp.tool().id()).getOutput();
            }, capability -> session.execute(mcp.tool().id()), mcp.tool());
            session.await("SESSION_READY", null);
            session.await("SESSION_IDLE", null);
            session.send("turn_submit", "first", "text", "First turn");
            assertTrue(toolEntered.await(2, TimeUnit.SECONDS));
            if (interruptOwner) {
                turnOwner.get().interrupt(); // NOT Future.cancel/isDone: observe actual callback completion.
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!session.signal.get().get() && System.nanoTime() < end) Thread.sleep(5);
                assertTrue(session.signal.get().get());
            } else {
                session.send("turn_cancel", "cancel", "turnId", "first");
                session.await("CONTROL", "cancel");
            }
            session.send("turn_submit", "too-early", "text", "Never queue this");
            assertEquals("busy", session.await("PROTOCOL_ERROR", "too-early").data().path("status").asText());
            assertEquals(1, turns.get());
            assertEquals(0, session.inputCloses.get());
            assertTrue(mcp.isOpen());
            assertEquals(0, mcp.closes());
            assertFalse(workerInterrupted.get());
            assertFalse(session.events.stream().anyMatch(e -> e.type() == HeadlessRunEvent.Type.TURN_COMPLETED));
            assertEquals(1, session.events.stream().filter(e -> e.type() == HeadlessRunEvent.Type.SESSION_IDLE).count());
            toolRelease.countDown();
            assertEquals("cancelled", session.await("TURN_COMPLETED", "first").data().path("status").asText());
            session.await("SESSION_IDLE", null);
            assertEquals(interruptOwner, ownerInterruptRestored.get());
            assertFalse(session.tools.hasProductUnknownOutcome());
            session.send("turn_submit", "second", "text", "Second turn");
            assertEquals("same MCP resource", session.await("TURN_COMPLETED", "second").data().path("content").asText());
            session.await("SESSION_IDLE", null);
            session.send("turn_submit", "second", "text", "Duplicate must not execute");
            assertEquals("duplicate", session.await("PROTOCOL_ERROR", "second").data().path("status").asText());
            assertEquals(2, turns.get());
            assertEquals(2, mcp.calls());
            assertTrue(mcp.isOpen());
            assertEquals(0, mcp.closes());
            session.commands.close(); // EOF is one cleanup path, never a pretend tool cancellation.
            session.result.get(4, TimeUnit.SECONDS);
            assertEquals(1, session.inputCloses.get());
            assertFalse(workerInterrupted.get());
            mcp.close();
            mcp.close();
            assertEquals(1, mcp.closes());
        } finally { toolRelease.countDown(); }
    }

    @Test void failedMcpTransportQuarantinesTurnAndReadsUntilExplicitClose() throws Exception {
        AtomicInteger turns = new AtomicInteger();
        try (var mcp = new ProductMcpTestEndpoint(() -> { throw new TimeoutException("unknown server outcome"); });
             Session session = new Session(policy(1, 10))) {
            session.start(prompt -> { turns.incrementAndGet(); return session.execute(mcp.tool().id()).getOutput(); },
                    capability -> session.execute(mcp.tool().id()), mcp.tool());
            session.await("SESSION_IDLE", null);
            CliTool retained = session.tools.get(mcp.tool().id());
            session.send("turn_submit", "unknown", "text", "First turn");
            var completion = session.await("TURN_COMPLETED", "unknown");
            assertEquals("quarantined", completion.data().path("status").asText());
            assertTrue(completion.data().path("remoteOutcomeUnknown").asBoolean());
            assertFalse(completion.data().path("ok").asBoolean());
            assertTrue(session.tools.hasProductUnknownOutcome());
            session.send("turn_submit", "blocked-turn", "text", "Never reopen");
            assertEquals("quarantined", session.await("PROTOCOL_ERROR", "blocked-turn").data().path("status").asText());
            session.send("read_capability", "blocked-read", "capability", "connection");
            assertEquals("quarantined", session.await("PROTOCOL_ERROR", "blocked-read").data().path("status").asText());
            assertTrue(retained.execute(null, null).isError());
            assertThrows(IllegalStateException.class, session.tools::openProductTurn);
            // Even idle expiry cannot auto-close/reopen a quarantined generation.
            Thread.sleep(1100);
            assertFalse(session.result.isDone());
            assertEquals(1, session.events.stream().filter(e -> e.type() == HeadlessRunEvent.Type.SESSION_IDLE).count());
            assertEquals(1, turns.get());
            assertEquals(1, mcp.calls());
            assertTrue(mcp.isOpen());
            assertEquals(0, mcp.closes());
            session.send("session_close", "close", null, null);
            session.result.get(4, TimeUnit.SECONDS);
            var closed = session.events.stream().filter(e -> e.type() == HeadlessRunEvent.Type.SESSION_CLOSED).findFirst().orElseThrow();
            assertTrue(closed.data().path("remoteOutcomeUnknown").asBoolean());
            assertFalse(closed.data().path("ok").asBoolean());
            assertTrue(closed.data().path("content").asText().contains("unknown"));
            assertEquals(1, session.events.stream().filter(e -> e.type() == HeadlessRunEvent.Type.SESSION_CLOSED).count());
            assertEquals(1, session.inputCloses.get());
            mcp.close();
            mcp.close();
            assertEquals(1, mcp.closes());
        }
    }

    @Test void duplicateCloseNonceFailsClosedEvenInQuarantine() throws Exception {
        try (var mcp = new ProductMcpTestEndpoint(() -> { throw new TimeoutException("unknown outcome"); });
             Session session = new Session(policy(1, 10))) {
            session.start(prompt -> session.execute(mcp.tool().id()).getOutput(), capability -> session.execute(mcp.tool().id()), mcp.tool());
            session.await("SESSION_IDLE", null);
            session.send("turn_submit", "same-nonce", "text", "One uncertain operation");
            assertEquals("quarantined", session.await("TURN_COMPLETED", "same-nonce").data().path("status").asText());
            session.send("session_close", "same-nonce", null, null);
            session.result.get(4, TimeUnit.SECONDS);
            assertTrue(session.events.stream().anyMatch(e -> e.type() == HeadlessRunEvent.Type.PROTOCOL_ERROR
                    && e.data().path("status").asText().equals("duplicate")));
            var closed = session.events.stream().filter(e -> e.type() == HeadlessRunEvent.Type.SESSION_CLOSED).findFirst().orElseThrow();
            assertEquals("duplicate_session_close", closed.data().path("status").asText());
            assertTrue(closed.data().path("remoteOutcomeUnknown").asBoolean());
            assertFalse(closed.data().path("ok").asBoolean());
            assertEquals(1, session.inputCloses.get());
            assertEquals(1, mcp.calls());
            mcp.close(); assertEquals(1, mcp.closes());
        }
    }

    @Test void completedMcpBusinessErrorDoesNotQuarantineNextTurn() throws Exception {
        AtomicInteger remoteCalls = new AtomicInteger();
        try (var mcp = new ProductMcpTestEndpoint(() -> remoteCalls.incrementAndGet() == 1
                ? ToolResult.error("business validation failed") : ToolResult.success("same MCP resource"));
             Session session = new Session(policy(10, 10))) {
            session.start(prompt -> session.execute(mcp.tool().id()).getOutput(),
                    capability -> session.execute(mcp.tool().id()), mcp.tool());
            session.await("SESSION_IDLE", null);
            session.send("read_capability", "validation", "capability", "connection");
            var validation = session.await("CAPABILITY_RESULT", "validation");
            assertFalse(validation.data().path("ok").asBoolean());
            assertEquals("business validation failed", validation.data().path("content").asText());
            session.await("SESSION_IDLE", null);
            assertFalse(session.tools.hasProductUnknownOutcome());
            session.send("turn_submit", "after-validation", "text", "Try again");
            assertEquals("same MCP resource", session.await("TURN_COMPLETED", "after-validation").data().path("content").asText());
            session.await("SESSION_IDLE", null);
            assertEquals(2, mcp.calls());
            assertTrue(mcp.isOpen());
            assertEquals(0, mcp.closes());
            session.send("session_close", "close", null, null);
            session.result.get(4, TimeUnit.SECONDS);
        }
    }

    @Test void completedProductInvocationRevokesRetainedContextBeforeNextTurn() throws Exception {
        AtomicReference<ToolContext> retained = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger(), turns = new AtomicInteger();
        try (Session session = new Session(policy(10, 10))) {
            session.tools.register(new CliTool() {
                public String id() { return "read"; }
                public String description() { return "retained context"; }
                public JsonNode parameterSchema() { return mapper.createObjectNode(); }
                public String permissionKey() { return "read"; }
                public ToolResult execute(JsonNode params, ToolContext context) {
                    calls.incrementAndGet();
                    retained.compareAndSet(null, context);
                    assertFalse(context.isAborted());
                    assertFalse(context.requestSelfBackground());
                    return ToolResult.success("done");
                }
            });
            CliTool retainedTool = session.tools.get("read");
            session.start(prompt -> {
                if (turns.incrementAndGet() == 2) {
                    assertTrue(retained.get().isAborted());
                    assertTrue(retainedTool.execute(null, retained.get()).isError());
                }
                return session.execute("read").getOutput();
            }, capability -> ToolResult.error("unused"));
            session.await("SESSION_IDLE", null);
            for (String id : List.of("first-context", "second-context")) {
                session.send("turn_submit", id, "text", "Read");
                assertTrue(session.await("TURN_COMPLETED", id).data().path("ok").asBoolean());
                session.await("SESSION_IDLE", null);
            }
            assertEquals(2, calls.get());
            session.send("session_close", "close", null, null);
            session.result.get(4, TimeUnit.SECONDS);
        }
    }

    @Test void normalModeStillInterruptsToolAndReleasesOwnerBeforeDelegateExit() throws Exception {
        var tools = new ToolRegistry(mapper);
        var agents = new AgentRegistry();
        var permissions = new PermissionService();
        var loop = new AgenticChatLoop(null, mapper, tools, permissions, agents, root, null, null);
        AtomicBoolean cancel = new AtomicBoolean();
        loop.setCancelSignal(cancel);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1),
                interrupted = new CountDownLatch(1), finished = new CountDownLatch(1);
        CliTool tool = new CliTool() {
            public String id() { return "read"; }
            public String description() { return "normal cancellation fixture"; }
            public JsonNode parameterSchema() { return mapper.createObjectNode(); }
            public String permissionKey() { return "read"; }
            public ToolResult execute(JsonNode args, ToolContext context) {
                entered.countDown();
                try {
                    for (;;) {
                        try { release.await(); break; }
                        catch (InterruptedException e) { interrupted.countDown(); }
                    }
                    return ToolResult.success("settled later");
                } finally { finished.countDown(); }
            }
        };
        tools.register(tool); // Normal dispatch enforces registry policy before invoking a delegate.
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<ToolResult> owner = executor.submit(() -> invokeTool(loop, tool,
                    new ToolContext("normal", agents.getDefault(), permissions, root, tools)));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            loop.cancelActiveTurn();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> owner.get(2, TimeUnit.SECONDS));
            assertInstanceOf(ToolExecutionException.class, failure.getCause());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(1, finished.getCount(), "normal dispatch still releases before the delegate exits");
            assertFalse(tools.isProductMode());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
        }
    }

    @Test void turnTimeoutAndIdleTtlAreSeparateAndReadsFailHonestly() throws Exception {
        try (Session session = new Session(policy(1, 1))) {
            session.start(prompt -> { while (!session.signal.get().get()) Thread.sleep(5); return "stopped"; },
                    capability -> ToolResult.error("permission denied"));
            session.await("SESSION_IDLE", null);
            session.send("read_capability", "denied", "capability", "connection");
            assertFalse(session.await("CAPABILITY_RESULT", "denied").data().path("ok").asBoolean());
            session.await("SESSION_IDLE", null);
            session.send("turn_submit", "timeout", "text", "Wait for timeout");
            assertEquals("timed_out", session.await("TURN_COMPLETED", "timeout").data().path("status").asText());
            session.await("SESSION_IDLE", null);
            session.result.get(4, TimeUnit.SECONDS);
            assertEquals("idle_expired", session.events.stream().filter(e -> e.type().name().equals("SESSION_CLOSED")).findFirst().orElseThrow().data().path("status").asText());
        }
    }
}
