package ai.kompile.app.services.agent;

import ai.kompile.cli.common.ProductChatPolicy;
import ai.kompile.cli.common.WebSessionProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Transport-only tests: no installed CLI, model, provider, MCP server or Spring context. */
class KompileCliSessionWorkerTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void startupBudgetIsOperatorOwnedAndBoundedSeparatelyFromTurnDeadlines() {
        String key = "kompile.product.chat.startupTimeoutSeconds", previous = System.getProperty(key);
        try {
            System.clearProperty(key); assertEquals(120, KompileCliSessionWorker.startupTimeoutSeconds());
            System.setProperty(key, "2"); assertEquals(2, KompileCliSessionWorker.startupTimeoutSeconds());
            System.setProperty(key, "-1"); assertEquals(1, KompileCliSessionWorker.startupTimeoutSeconds());
            System.setProperty(key, "99999"); assertEquals(300, KompileCliSessionWorker.startupTimeoutSeconds());
        } finally { if (previous == null) System.clearProperty(key); else System.setProperty(key, previous); }
    }

    @Test void bootstrapIsNotATurnAndIdleWorkerSurvivesViewsAndReconnects() throws Exception {
        ProductChatPolicy policy = policy(); FakeProcess child = new FakeProcess();
        List<String> command = new ArrayList<>();
        try (KompileCliSessionWorker worker = worker(policy, child, command)) {
            worker.open(); assertEquals("idle", worker.status());
            assertEquals(0, child.count("turn_submit"));
            assertTrue(command.contains("--web-session-worker"));
            assertEquals(List.of("--output-format", "stream-json"), command.subList(command.size() - 2, command.size()));
            assertFalse(command.contains("--input-format"));
            assertFalse(command.contains("--web-controls"));
            assertFalse(command.contains("-"), "Product bootstrap is a protocol frame, not a positional prompt");
            assertFalse(command.contains("--role")); assertFalse(command.contains("--skip-permissions"));
            SseEmitter first = new SseEmitter();
            String run = worker.submit(id("turn-1"), "inspect the board", first);
            await(() -> child.count("turn_submit") == 1);
            first.complete(); // A lost view is not cancellation.
            assertTrue(child.isAlive()); assertEquals(0, child.count("turn_cancel"));
            worker.reconnect(run, 0, new SseEmitter());
            child.finish(id("turn-1"), "completed"); await(() -> worker.status().equals("idle"));
            assertTrue(child.isAlive());
            worker.submit(id("turn-2"), "describe what changed", new SseEmitter());
            await(() -> child.count("turn_submit") == 2);
            assertEquals(2, child.count("turn_submit"));
            assertEquals(1, child.count("session_open"));
        }
        await(() -> child.count("session_close") == 1);
        assertEquals(1, child.count("session_close")); assertFalse(child.isAlive());
        assertEquals(0, child.destroyed);
    }

    @Test void stalledBrowserCannotHoldTheWorkerMonitorOrPreventATurnFromFinishing() throws Exception {
        FakeProcess child = new FakeProcess(); CountDownLatch entered = new CountDownLatch(1), unblock = new CountDownLatch(1);
        SseEmitter slow = new SseEmitter() {
            @Override public void send(SseEmitter.SseEventBuilder event) throws IOException {
                entered.countDown();
                try { unblock.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("view interrupted"); }
            }
        };
        try (KompileCliSessionWorker worker = worker(policy(), child, new ArrayList<>())) {
            worker.open(); worker.submit(id("slow-view-turn"), "inspect", slow);
            assertTrue(entered.await(1, TimeUnit.SECONDS)); await(() -> child.count("turn_submit") == 1);
            child.finish(id("slow-view-turn"), "completed"); await(() -> worker.status().equals("idle"));
            assertTrue(child.isAlive()); assertEquals(0, child.count("turn_cancel"));
        } finally { unblock.countDown(); }
    }

    @Test void deduplicatesAndRejectsCompetingTurnsWithoutAQueue() throws Exception {
        FakeProcess child = new FakeProcess();
        try (KompileCliSessionWorker worker = worker(policy(), child, new ArrayList<>())) {
            worker.open();
            String run = worker.submit(id("same-id"), "advance one step", new SseEmitter());
            await(() -> child.count("turn_submit") == 1);
            assertEquals(run, worker.submit(id("same-id"), "advance one step", new SseEmitter()));
            assertThrows(IllegalArgumentException.class, () -> worker.submit(id("same-id"), "different mutation", new SseEmitter()));
            assertThrows(IllegalStateException.class, () -> worker.submit(id("other-id"), "another step", new SseEmitter()));
            assertEquals(1, child.count("turn_submit"));
            child.finish(id("same-id"), "completed"); await(() -> worker.status().equals("idle"));
            worker.submit(id("same-id"), "advance one step", new SseEmitter());
            assertEquals(1, child.count("turn_submit"));
        }
    }

    @Test void stopCancelsOnlyTheTurnWhileCloseUsesSeparateGracefulFrame() throws Exception {
        FakeProcess child = new FakeProcess();
        try (KompileCliSessionWorker worker = worker(policy(), child, new ArrayList<>())) {
            worker.open(); String run = worker.submit(id("turn-cancel"), "advance", new SseEmitter());
            assertTrue(worker.cancel(run)); assertTrue(worker.cancel(run));
            await(() -> child.count("turn_cancel") == 1);
            assertEquals(1, child.count("turn_cancel")); assertEquals(0, child.count("session_close"));
            assertTrue(child.isAlive()); assertEquals(0, child.destroyed);
            assertEquals(id("turn-cancel"), child.last("turn_cancel").turnId());
            child.finish(id("turn-cancel"), "cancelled"); await(() -> worker.status().equals("idle"));
            assertFalse(worker.cancel(run)); assertTrue(child.isAlive());
        }
        await(() -> child.count("session_close") == 1);
        assertEquals(1, child.count("session_close"));
    }

    @Test void preSseCancellationCreatesATerminalReceiptAndRetainsTheIdleChild() throws Exception {
        FakeProcess child = new FakeProcess(); AtomicLong clock = new AtomicLong();
        try (KompileCliSessionWorker worker = new KompileCliSessionWorker(mapper, policy(), "product-session",
                () -> List.of("installed-kompile"), (args, root) -> child, clock::get)) {
            worker.open(); String turn = id("cancel-before-stream"), run = "harness-product-" + turn;
            // Stop wins the HTTP race, before stream() can accept a receipt or produce SSE.
            assertTrue(worker.cancel(run)); assertTrue(worker.cancel(run));
            RecordingEmitter first = new RecordingEmitter();
            assertEquals(run, worker.submit(turn, "advance once", first));
            await(() -> first.frames.stream().anyMatch(s -> s.contains("event:cancelled")));
            assertEquals(0, child.count("turn_submit")); assertEquals(0, child.count("turn_cancel"));
            assertEquals("idle", worker.status()); assertTrue(child.isAlive());
            assertEquals(Boolean.TRUE, worker.activeRun().get("finished"));
            assertEquals(run, worker.activeRun().get("processId"));
            assertEquals(worker.generation(), worker.activeRun().get("generation"));
            assertFalse(run.contains(worker.generation()));
            // Once admitted, TTL expiry never deletes the receipt or authorizes a retry.
            clock.addAndGet(KompileCliSessionWorker.EARLY_CANCEL_TTL_NANOS + 1);
            assertEquals(run, worker.submit(turn, "advance once", new SseEmitter()));
            RecordingEmitter replay = new RecordingEmitter(); worker.reconnect(run, 0, replay);
            await(() -> replay.frames.stream().anyMatch(s -> s.contains("event:cancelled")));
            assertThrows(IllegalArgumentException.class, () -> worker.submit(turn, "different mutation", new SseEmitter()));
            assertEquals(0, child.count("turn_submit"));
            worker.submit(id("next-after-early-cancel"), "inspect", new SseEmitter());
            await(() -> child.count("turn_submit") == 1);
            child.finish(id("next-after-early-cancel"), "completed"); await(() -> worker.status().equals("idle"));
            assertEquals(1, child.count("session_open")); assertTrue(child.isAlive());
        }
    }

    @Test void earlyCancellationDoesNotDisturbAnotherActiveTurnOrReexecuteAnEvictedReceipt() throws Exception {
        FakeProcess child = new FakeProcess();
        try (KompileCliSessionWorker worker = worker(policy(), child, new ArrayList<>())) {
            worker.open(); String active = id("active"), cancelled = id("cancelled-while-busy");
            worker.submit(active, "inspect", new SseEmitter()); await(() -> child.count("turn_submit") == 1);
            String run = "harness-product-" + cancelled; assertTrue(worker.cancel(run));
            worker.submit(cancelled, "never execute", new SseEmitter());
            assertEquals("busy", worker.status()); assertEquals(0, child.count("turn_cancel"));
            child.finish(active, "completed"); await(() -> worker.status().equals("idle"));
            for (int i = 0; i < KompileCliSessionWorker.MAX_REPLAYS; i++) {
                String turn = id("later-early-cancel-" + i), earlyRun = "harness-product-" + turn;
                assertTrue(worker.cancel(earlyRun)); worker.submit(turn, "never execute", new SseEmitter());
            }
            assertThrows(IllegalStateException.class, () -> worker.reconnect(run, 0, new SseEmitter()));
            assertThrows(IllegalStateException.class, () -> worker.submit(cancelled, "never execute", new SseEmitter()));
            assertThrows(IllegalArgumentException.class, () -> worker.submit(cancelled, "changed", new SseEmitter()));
            assertEquals(1, child.count("turn_submit")); assertEquals("idle", worker.status()); assertTrue(child.isAlive());
        }
    }

    @Test void expiredCancelReceiptsStayInertWhilePendingCapacityIsReclaimed() throws Exception {
        FakeProcess child = new FakeProcess(); AtomicLong clock = new AtomicLong();
        try (KompileCliSessionWorker worker = new KompileCliSessionWorker(mapper, policy(), "product-session",
                () -> List.of("installed-kompile"), (args, root) -> child, clock::get)) {
            worker.open();
            for (int i = 0; i < KompileCliSessionWorker.MAX_EARLY_CANCELS; i++)
                assertTrue(worker.cancel("harness-product-" + id("intent-" + i)));
            assertThrows(IllegalStateException.class, () -> worker.cancel("harness-product-" + id("overflow")));
            assertThrows(IllegalArgumentException.class, () -> worker.cancel("harness-product-not-a-turn"));
            clock.set(KompileCliSessionWorker.EARLY_CANCEL_TTL_NANOS - 1);
            assertTrue(worker.cancel("harness-product-" + id("intent-0")));
            worker.submit(id("intent-1"), "cancelled before TTL", new SseEmitter());
            assertEquals(0, child.count("turn_submit")); // Capacity pressure did not evict a live intent.
            clock.incrementAndGet();
            assertThrows(IllegalStateException.class,
                    () -> worker.submit(id("intent-0"), "intent expired before admission", new SseEmitter()));
            assertEquals(0, child.count("turn_submit"), "Expiry must never resurrect a cancelled mutation");
            worker.submit(id("fresh-after-expiry"), "a distinct newly approved turn", new SseEmitter());
            await(() -> child.count("turn_submit") == 1);
            child.finish(id("fresh-after-expiry"), "completed"); await(() -> worker.status().equals("idle"));
            assertTrue(worker.cancel("harness-product-" + id("after-expiry")));
        }
    }

    @Test void replacingTheCliGenerationCannotReexecuteReceiptsOrForgetUnexpiredEarlyCancellation() throws Exception {
        ProductChatPolicy policy = policy();
        KompileCliSessionWorker.ReceiptLedger receipts = new KompileCliSessionWorker.ReceiptLedger();
        FakeProcess first = new FakeProcess(); String oldTurn = id("previous-generation"), pending = id("pending-across-close");
        String oldGeneration;
        try (KompileCliSessionWorker worker = new KompileCliSessionWorker(mapper, policy, "product-session",
                () -> List.of("installed-kompile"), (args, root) -> first, receipts)) {
            worker.open(); oldGeneration = worker.generation(); worker.submit(oldTurn, "advance once", new SseEmitter());
            await(() -> first.count("turn_submit") == 1);
            first.finish(oldTurn, "completed"); await(() -> worker.status().equals("idle"));
            assertTrue(worker.cancel("harness-product-" + pending));
            worker.close(); await(() -> worker.status().equals("closed"));
        }
        FakeProcess second = new FakeProcess();
        try (KompileCliSessionWorker worker = new KompileCliSessionWorker(mapper, policy, "product-session",
                () -> List.of("installed-kompile"), (args, root) -> second, receipts)) {
            worker.open(); assertNotEquals(oldGeneration, worker.generation());
            assertThrows(IllegalStateException.class, () -> worker.submit(oldTurn, "advance once", new SseEmitter()));
            assertThrows(IllegalArgumentException.class, () -> worker.submit(oldTurn, "changed mutation", new SseEmitter()));
            assertFalse(worker.cancel("harness-product-" + oldTurn));
            assertThrows(IllegalStateException.class, () -> worker.reconnect("harness-product-" + oldTurn, 0, new SseEmitter()));
            RecordingEmitter emitter = new RecordingEmitter();
            worker.submit(pending, "never execute", emitter);
            await(() -> emitter.frames.stream().anyMatch(s -> s.contains("event:cancelled")));
            assertEquals(0, second.count("turn_submit")); assertEquals("idle", worker.status());
            worker.submit(id("fresh-generation-turn"), "inspect", new SseEmitter());
            await(() -> second.count("turn_submit") == 1); assertTrue(second.isAlive());
        }
    }

    @Test void acceptedReceiptTombstonesHaveABoundedFailClosedBudgetAndCannotBeEvictedByNewGenerations() {
        KompileCliSessionWorker.ReceiptLedger receipts = new KompileCliSessionWorker.ReceiptLedger();
        for (int i = 0; i < KompileCliSessionWorker.MAX_TURNS; i++) receipts.admit(id("accepted-" + i), "inspect");
        assertThrows(IllegalStateException.class, () -> receipts.admit(id("overflow"), "inspect"));
        assertThrows(IllegalStateException.class, () -> receipts.requireFresh(id("accepted-0"), "inspect"));
        assertThrows(IllegalArgumentException.class, () -> receipts.requireFresh(id("accepted-0"), "different mutation"));
        assertFalse(receipts.cancel(id("accepted-0")));
    }

    @Test void ordinaryToolDetailIsNotReplacedByProductEventData() throws Exception {
        FakeProcess child = new FakeProcess(); RecordingEmitter emitter = new RecordingEmitter();
        try (KompileCliSessionWorker worker = worker(policy(), child, new ArrayList<>())) {
            worker.open(); worker.submit(id("tool-detail"), "inspect", emitter);
            await(() -> child.count("turn_submit") == 1);
            ObjectNode event = mapper.createObjectNode().put("type", "tool").put("name", "inspect").put("call_id", "call").put("ok", true);
            ObjectNode detail = event.putObject("detail").put("output", "ordinary tool output");
            ObjectNode presentation = detail.putObject("presentation").put("version", 1).put("kind", "vendor.board");
            presentation.putObject("data").put("savedId", 7);
            presentation.putObject("source").put("serverId", "server").put("toolName", "show");
            presentation.putObject("identity").put("sessionId", "product-session").put("turnId", id("tool-detail")).put("callId", "call");
            event.putObject("data").put("output", "not tool detail"); child.rawEvent(event);
            await(() -> emitter.payloads.stream().anyMatch(p -> p.path("toolName").asText().equals("inspect")));
            JsonNode tool = emitter.payloads.stream().filter(p -> p.path("toolName").asText().equals("inspect")).findFirst().orElseThrow();
            assertEquals("ordinary tool output", tool.path("detail").path("output").asText());
            assertEquals(presentation, tool.path("detail").path("presentation"));
        }
    }

    @Test void legacyProductDetailPayloadIsNotAcceptedAsACompletion() throws Exception {
        FakeProcess child = new FakeProcess();
        try (KompileCliSessionWorker worker = worker(policy(), child, new ArrayList<>())) {
            worker.open(); worker.submit(id("legacy-event"), "inspect", new SseEmitter());
            await(() -> child.count("turn_submit") == 1);
            ObjectNode event = mapper.createObjectNode().put("type", "turn_completed");
            event.putObject("detail").put("requestId", id("legacy-event")).put("turnId", id("legacy-event")).put("status", "completed");
            child.rawEvent(event); await(() -> worker.status().equals("failed"));
            assertEquals(1, child.count("turn_submit"));
        }
    }

    @Test void namedReadsAreActualJsonAndCannotCompeteWithATurn() throws Exception {
        FakeProcess child = new FakeProcess();
        try (KompileCliSessionWorker worker = worker(policy(), child, new ArrayList<>())) {
            worker.open();
            assertEquals("runtime-1", worker.readCapability("connection").path("runtimeId").asText());
            await(() -> worker.status().equals("idle"));
            worker.submit(id("busy-turn"), "look", new SseEmitter());
            assertThrows(IllegalStateException.class, () -> worker.readCapability("dashboard"));
            await(() -> child.count("read_capability") == 1);
            assertEquals(1, child.count("read_capability"));
            assertThrows(IllegalArgumentException.class, () -> worker.readCapability("shell"));
        }
    }

    @Test void capabilityContentRequiresCanonicalJsonTextRatherThanAnObjectFallback() throws Exception {
        FakeProcess child = new FakeProcess(); child.capabilityContent = mapper.createObjectNode().put("runtimeId", "wrong-wire");
        try (KompileCliSessionWorker worker = worker(policy(), child, new ArrayList<>())) {
            worker.open(); assertThrows(IOException.class, () -> worker.readCapability("connection"));
            assertEquals("failed", worker.status()); assertEquals(0, child.count("turn_submit"));
        }
    }

    @Test void staleIdleTimerCannotCloseATurnAdmittedBeforeItsClosingClaim() throws Exception {
        FakeProcess child = new FakeProcess();
        CountDownLatch timestampCaptured = new CountDownLatch(1), decide = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (KompileCliSessionWorker worker = worker(policy(), child, new ArrayList<>())) {
            worker.open();
            var expiry = executor.submit(() -> {
                long staleNow = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
                timestampCaptured.countDown();
                assertTrue(decide.await(2, TimeUnit.SECONDS));
                worker.expireIdle(staleNow);
                return null;
            });
            assertTrue(timestampCaptured.await(1, TimeUnit.SECONDS));
            worker.submit(id("wins-idle-race"), "inspect", new SseEmitter());
            decide.countDown(); expiry.get(2, TimeUnit.SECONDS);
            assertEquals("busy", worker.status()); assertTrue(child.isAlive());
            await(() -> child.count("turn_submit") == 1);
            assertEquals(0, child.count("session_close"));
        } finally { decide.countDown(); executor.shutdownNow(); }
    }

    @Test void idleClosingClaimIsAtomicWithAdmissionButCleanupDoesNotOwnTheMonitor() throws Exception {
        FakeProcess child = new FakeProcess(); child.ignoreClose = true;
        CountDownLatch claimed = new CountDownLatch(1), cleanup = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (KompileCliSessionWorker worker = worker(policy(), child, new ArrayList<>())) {
            worker.open();
            var expiry = executor.submit(() -> worker.expireIdle(System.nanoTime() + TimeUnit.MINUTES.toNanos(2), () -> {
                assertFalse(Thread.holdsLock(worker), "Blocking cleanup must not deadlock the event reader");
                claimed.countDown();
                try { assertTrue(cleanup.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            }));
            assertTrue(claimed.await(1, TimeUnit.SECONDS));
            assertEquals("closing", worker.status());
            assertThrows(IllegalStateException.class, () -> worker.submit(id("loses-idle-race"), "inspect", new SseEmitter()));
            assertEquals(0, child.count("turn_submit"));
            cleanup.countDown(); expiry.get(8, TimeUnit.SECONDS);
            assertFalse(child.isAlive());
            assertEquals("failed", worker.status(), "This deliberately non-cooperative close cannot certify an OS-tree handoff");
            assertFalse(worker.exited());
        } finally { cleanup.countDown(); executor.shutdownNow(); }
    }

    @Test void externalOperationPinsWorkerIdleExpiryAndAllModelAdmission() throws Exception {
        FakeProcess child = new FakeProcess();
        try (KompileCliSessionWorker worker = worker(policy(), child, new ArrayList<>())) {
            worker.open(); worker.readCapability("connection");
            try (var operation = worker.claimExternalOperation()) {
                worker.expireIdle(System.nanoTime() + TimeUnit.HOURS.toNanos(1));
                assertEquals("idle", worker.status()); assertTrue(child.isAlive());
                assertThrows(IllegalStateException.class, () -> worker.submit(id("domain-pinned"), "inspect", new SseEmitter()));
                assertThrows(IllegalStateException.class, () -> worker.readCapability("dashboard"));
            }
            worker.submit(id("after-domain"), "inspect", new SseEmitter());
            await(() -> child.count("turn_submit") == 1);
        }
    }

    @Test void forcedCloseReapsRealOwnedDescendantAndSealsTheUnprovableForcedBoundary() throws Exception {
        ProductChatPolicy policy = policy();
        Process child = ProductWorkerProcessFixture.start("tree");
        assertNotEquals(ProcessHandle.current().pid(), child.pid());
        KompileCliSessionWorker worker = new KompileCliSessionWorker(mapper, policy, "product-session",
                () -> List.of("fixture"), (args, root) -> child);
        ProcessHandle descendant = null;
        var executor = Executors.newSingleThreadExecutor();
        try {
            worker.open();
            try (var children = child.descendants()) { descendant = children.findFirst().orElseThrow(); }
            assertTrue(descendant.isAlive());
            assertNotEquals(ProcessHandle.current().pid(), descendant.pid());
            var closing = executor.submit(worker::close);
            await(() -> worker.status().equals("closing"));
            assertTrue(child.isAlive()); assertTrue(descendant.isAlive());
            assertFalse(worker.exited(), "A live descendant must retain generation ownership");
            assertThrows(IOException.class, () -> KompileCliSessionWorker.Lease.acquire(directory.resolve(".")));
            AtomicBoolean replacementLaunched = new AtomicBoolean();
            try (KompileCliSessionWorker replacement = new KompileCliSessionWorker(mapper, policy, "product-session",
                    () -> List.of("fixture"), (args, root) -> { replacementLaunched.set(true); throw new IOException("must not launch"); })) {
                assertThrows(IOException.class, replacement::open);
            }
            assertFalse(replacementLaunched.get());
            closing.get(15, TimeUnit.SECONDS);
            assertFalse(descendant.isAlive(), "Must prove the real MCP-like descendant exited, not only its CLI parent");
            assertFalse(child.isAlive());
            // Still no release: ProcessHandle cannot prove that force-killed parents did not
            // fork an unseen child between our last snapshot and exit. Known-tree reap stays strict.
            assertFalse(worker.exited()); assertEquals("failed", worker.status());
            worker.close(); // Idempotent cleanup cannot turn uncertainty into a handoff.
            assertFalse(worker.exited());
            assertThrows(IOException.class, () -> KompileCliSessionWorker.Lease.acquire(directory));
        } finally {
            // Test cleanup targets only handles obtained from this exact disposable parent.
            if (descendant != null && descendant.isAlive()) {
                descendant.destroyForcibly();
                try { descendant.onExit().get(3, TimeUnit.SECONDS); } catch (Exception ignored) { }
            }
            if (child.isAlive()) child.destroyForcibly();
            child.waitFor(3, TimeUnit.SECONDS);
            worker.close(); executor.shutdownNow();
        }
    }

    @Test void realCooperativeProtocolCloseReleasesTheLeaseOnlyAfterActualJvmExit() throws Exception {
        ProductChatPolicy policy = policy(); Process child = ProductWorkerProcessFixture.start("transport");
        try (KompileCliSessionWorker worker = new KompileCliSessionWorker(mapper, policy, "product-session",
                () -> List.of("fixture"), (args, root) -> child)) {
            worker.open(); assertTrue(child.isAlive()); worker.close();
            await(() -> worker.status().equals("closed"));
            assertFalse(child.isAlive()); assertTrue(worker.exited());
            try (var lease = KompileCliSessionWorker.Lease.acquire(directory)) { assertNotNull(lease); }
        } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS); } }
    }

    @Test void acknowledgedCooperativeCleanupOutlivesInitialGraceWithoutForcingOwner() throws Exception {
        ProductChatPolicy product = policy();
        Process child = ProductWorkerProcessFixture.start("slow-cleanup");
        try (KompileCliSessionWorker worker = new KompileCliSessionWorker(mapper, product, "product-session",
                () -> List.of("fixture"), (args, root) -> child)) {
            worker.open(); worker.close();
            assertFalse(child.isAlive());
            assertEquals(0, child.exitValue());
            assertTrue(worker.exited());
            assertEquals("closed", worker.status());
            try (var lease = KompileCliSessionWorker.Lease.acquire(directory)) { assertNotNull(lease); }
        } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS); } }
    }

    @Test void contentCleanupHasASeparateAcknowledgedManagedThenMcpBudget() throws Exception {
        ProductChatPolicy base = policy();
        ObjectNode manifest = (ObjectNode) mapper.readTree(Files.readString(base.manifestPath()));
        manifest.put("nativeActionPolicy", "elthoria.content.v1");
        Files.writeString(base.manifestPath(), manifest.toString());
        ProductChatPolicy content = ProductChatPolicy.load(base.manifestPath(), directory);
        Process child = ProductWorkerProcessFixture.start("content-slow-cleanup");
        try (KompileCliSessionWorker worker = new KompileCliSessionWorker(mapper, content, "product-session",
                () -> List.of("fixture"), (args, root) -> child)) {
            worker.open(); worker.close();
            assertFalse(child.isAlive()); assertEquals(0, child.exitValue(), "Cooperative cleanup must not be signalled");
            assertTrue(worker.exited()); assertEquals("closed", worker.status());
            try (var lease = KompileCliSessionWorker.Lease.acquire(directory)) { assertNotNull(lease); }
        } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS); } }
    }

    @Test void missingMcpCleanupReceiptNeverReleasesADeadOwnersLease() throws Exception {
        unconfirmedCleanupKeepsLease("missing-cleanup");
    }

    @Test void forcedInnerMcpCleanupReceiptNeverReleasesADeadOwnersLease() throws Exception {
        unconfirmedCleanupKeepsLease("uncertain-cleanup");
    }

    private void unconfirmedCleanupKeepsLease(String mode) throws Exception {
        Process child = ProductWorkerProcessFixture.start(mode);
        try (KompileCliSessionWorker worker = new KompileCliSessionWorker(mapper, policy(), "product-session",
                () -> List.of("fixture"), (args, root) -> child)) {
            worker.open(); worker.close();
            assertFalse(child.isAlive()); assertEquals(0, child.exitValue());
            assertFalse(worker.exited()); assertEquals("failed", worker.status());
            assertThrows(IOException.class, () -> KompileCliSessionWorker.Lease.acquire(directory));
        } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS); } }
    }

    @Test void unavailableOsOwnershipFailsClosedEvenAfterTheRootReportsExit() throws Exception {
        FakeProcess child = new FakeProcess(); child.unavailableHandle = true;
        try (KompileCliSessionWorker worker = worker(policy(), child, new ArrayList<>())) {
            worker.open(); worker.close();
            assertFalse(child.isAlive());
            assertFalse(worker.exited(), "Root waitFor is not proof of descendant quiescence without OS ownership");
            assertEquals("failed", worker.status());
            assertThrows(IOException.class, () -> KompileCliSessionWorker.Lease.acquire(directory));
            worker.close(); assertFalse(worker.exited()); // Explicit retry is not a generic lease bypass.
        }
    }

    @Test void workspaceLeaseUsesCanonicalPathAndIsReleasedOnlyAfterExit() throws Exception {
        ProductChatPolicy policy = policy(); FakeProcess child = new FakeProcess();
        try (KompileCliSessionWorker worker = worker(policy, child, new ArrayList<>())) {
            worker.open();
            assertThrows(IOException.class, () -> KompileCliSessionWorker.Lease.acquire(directory.resolve(".")));
        }
        assertFalse(child.isAlive());
        try (var lease = KompileCliSessionWorker.Lease.acquire(directory)) { assertNotNull(lease); }
    }

    @Test void replayIsBoundedButAnEvictedReceiptNeverReexecutes() throws Exception {
        FakeProcess child = new FakeProcess();
        try (KompileCliSessionWorker worker = worker(policy(), child, new ArrayList<>())) {
            worker.open(); String first = null;
            for (int i = 0; i < KompileCliSessionWorker.MAX_REPLAYS + 2; i++) {
                String id = id("turn-" + i);
                String run = worker.submit(id, "inspect", new SseEmitter());
                if (first == null) first = run;
                long submitted = i + 1L; await(() -> child.count("turn_submit") == submitted);
                child.finish(id, "completed"); await(() -> worker.status().equals("idle"));
            }
            String firstRun = first;
            assertThrows(IllegalStateException.class, () -> worker.reconnect(firstRun, 0, new SseEmitter()));
            long accepted = child.count("turn_submit");
            assertThrows(IllegalStateException.class, () -> worker.submit(id("turn-0"), "inspect", new SseEmitter()));
            assertEquals(accepted, child.count("turn_submit"));
        }
    }

    @Test void malformedOrOversizedEventsFailClosedAndIdentityIsWorkspacePrivate() throws Exception {
        assertThrows(IOException.class, () -> KompileCliSessionWorker.boundedLine(new StringReader("x".repeat(KompileCliSessionWorker.MAX_FRAME + 1))));
        String id = ProductChatSessionService.persistentSessionId(directory);
        assertEquals(id, ProductChatSessionService.persistentSessionId(directory));
        assertEquals(UUID.fromString(id).toString(), id);
        Path other = Files.createDirectory(directory.resolve("other"));
        Files.createSymbolicLink(other.resolve(".kompile"), directory.resolve(".kompile"));
        assertThrows(IOException.class, () -> ProductChatSessionService.persistentSessionId(other));
    }

    @Test void nonMonotonicWorkerFramesFailClosedWithoutInventingASuccessReceipt() throws Exception {
        FakeProcess child = new FakeProcess();
        try (KompileCliSessionWorker worker = worker(policy(), child, new ArrayList<>())) {
            worker.open(); worker.submit(id("sequence-turn"), "inspect", new SseEmitter());
            await(() -> child.count("turn_submit") == 1);
            synchronized (child) { child.sequence = 0; child.event("text", mapper.createObjectNode()); }
            await(() -> worker.status().equals("failed"));
            assertThrows(IllegalStateException.class, () -> worker.submit(id("new-turn"), "inspect", new SseEmitter()));
            assertEquals(1, child.count("turn_submit"));
        }
        await(() -> !child.isAlive());
    }

    @Test void contentControlsRouteToTheOwnerWithoutStartingATurnAndRejectForeignExpiredReplayIds() throws Exception {
        FakeProcess child = new FakeProcess();
        try (KompileCliSessionWorker worker = worker(policy(), child, new ArrayList<>())) {
            worker.open();
            assertEquals("pending-1", worker.pendingApprovals().get(0).path("approvalId").asText());
            assertTrue(worker.resolveApproval("pending-1", true).path("ok").booleanValue());
            assertTrue(child.approvalAllowed);
            assertFalse(worker.resolveApproval("pending-1", true).path("ok").booleanValue(), "single resolution, not repeat consent");
            assertFalse(worker.resolveApproval("foreign", true).path("ok").booleanValue());
            assertFalse(worker.resolveApproval("expired-1", true).path("ok").booleanValue());
            child.approvalPending = true;
            assertTrue(worker.resolveApproval("pending-1", false).path("ok").booleanValue());
            assertFalse(child.approvalAllowed);
            assertFalse(worker.resolveApproval("pending-1", true).path("ok").booleanValue(), "denial cannot become a replayed grant");
            assertEquals(0, worker.pendingApprovals().size());
            assertEquals(0, child.count("turn_submit")); assertEquals(0, child.count("read_capability"));
            assertEquals("idle", worker.status());
        }
    }

    @Test void approvalFutureIsBoundedNotQueuedAndClosureRejectsIt() throws Exception {
        FakeProcess child = new FakeProcess(); child.holdApprovals = true;
        ExecutorService requests = Executors.newSingleThreadExecutor();
        try (KompileCliSessionWorker worker = worker(policy(), child, new ArrayList<>())) {
            worker.open(); Future<JsonNode> pending = requests.submit(worker::pendingApprovals);
            await(() -> child.count("content_approvals") == 1);
            assertThrows(IllegalStateException.class, worker::pendingApprovals);
            assertThrows(IllegalStateException.class, () -> worker.resolveApproval("pending-1", true));
            assertThrows(IllegalStateException.class, () -> worker.submit(id("while-approval"), "do not run", new SseEmitter()));
            worker.close();
            assertInstanceOf(IOException.class, assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS)).getCause());
            assertEquals(0, child.count("turn_submit")); assertEquals(0, child.count("content_approval"));
        } finally { requests.shutdownNow(); }
    }

    @Test void approvalReceiptWithForeignCorrelationFailsClosed() throws Exception {
        rejectApprovalReceipt("requestId");
    }
    @Test void approvalReceiptWithWrongTypeFailsClosed() throws Exception {
        rejectApprovalReceipt("type");
    }
    @Test void approvalReceiptFromAnotherRuntimeFailsClosed() throws Exception {
        rejectApprovalReceipt("sessionId");
    }
    private void rejectApprovalReceipt(String mismatch) throws Exception {
        FakeProcess child = new FakeProcess(); child.holdApprovals = true;
        ExecutorService requests = Executors.newSingleThreadExecutor();
        try (KompileCliSessionWorker worker = worker(policy(), child, new ArrayList<>())) {
            worker.open(); Future<JsonNode> pending = requests.submit(worker::pendingApprovals);
            await(() -> child.count("content_approvals") == 1);
            ObjectNode data = mapper.createObjectNode().put("version", 1).put("sessionId", "product-session")
                    .put("policyDigest", child.approvalDigest).put("requestId", "earlier-generation-request").put("ok", true);
            data.putArray("approvals");
            if (!mismatch.equals("requestId")) data.put("requestId", child.last("content_approvals").requestId());
            if (mismatch.equals("sessionId")) data.put("sessionId", "foreign-runtime");
            child.event(mismatch.equals("type") ? "content_approval" : "content_approvals", data);
            assertInstanceOf(IOException.class, assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS)).getCause());
            assertThrows(IllegalStateException.class, () -> worker.resolveApproval("pending-1", true));
            assertEquals(0, child.count("turn_submit"));
        } finally { requests.shutdownNow(); }
    }

    @Test void replayedControlReceiptCannotBeReusedAsNewAuthority() throws Exception {
        FakeProcess child = new FakeProcess();
        try (KompileCliSessionWorker worker = worker(policy(), child, new ArrayList<>())) {
            worker.open(); worker.pendingApprovals(); await(() -> child.count("content_approvals") == 1);
            ObjectNode data = mapper.createObjectNode().put("version", 1).put("sessionId", "product-session")
                    .put("policyDigest", child.approvalDigest).put("requestId", child.last("content_approvals").requestId()).put("ok", true);
            data.putArray("approvals"); child.event("content_approvals", data);
            await(() -> worker.status().equals("failed"));
            assertThrows(IllegalStateException.class, () -> worker.resolveApproval("pending-1", true));
            assertEquals(0, child.count("turn_submit"));
        }
    }

    @Test void serviceApprovalBodiesAreExactAndRefusedDecisionsAreConflicts() throws Exception {
        ProductChatPolicy policy = policy(); FakeProcess child = new FakeProcess();
        try (KompileCliSessionWorker worker = worker(policy, child, new ArrayList<>());
             ProductChatSessionService service = new ProductChatSessionService(mapper, policy)) {
            worker.open(); var field = ProductChatSessionService.class.getDeclaredField("worker");
            field.setAccessible(true); field.set(service, worker);
            assertTrue(service.pendingApprovals().isArray());
            assertTrue(service.resolveApproval("pending-1", mapper.createObjectNode().put("allow", true)).path("ok").booleanValue());
            assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.resolveApproval("foreign",
                    mapper.createObjectNode().put("allow", true))).getStatusCode().value());
            for (String body : List.of("{}", "null", "{\"allow\":\"true\"}", "{\"allow\":true,\"approved\":true}",
                    "{\"allow\":true,\"tool\":\"crawl_source\"}", "{\"allow\":false,\"generation\":\"foreign\"}"))
                assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.resolveApproval("pending-1", mapper.readTree(body))).getStatusCode().value());
            assertEquals(0, child.count("turn_submit"));
        }
    }

    @Test void productBrowserCannotReplaceProfileRoleWorkspaceOrEngineFields() throws Exception {
        try (ProductChatSessionService service = new ProductChatSessionService(mapper, policy())) {
            ObjectNode body = mapper.createObjectNode().put("sessionId", service.sessionId())
                    .put("message", "hello").put("turnId", id("turn-1"));
            service.validateBody(body);
            service.validateBody(body.deepCopy().put("agentName", "role:elthoria").put("workingDirectory", directory.toRealPath().toString()));
            for (String field : List.of("message", "sessionId", "turnId")) {
                ObjectNode missing = body.deepCopy(); missing.remove(field);
                assertThrows(ResponseStatusException.class, () -> service.validateBody(missing), field);
            }
            assertThrows(ResponseStatusException.class, () -> service.validateBody(body.deepCopy().putNull("agentName")));
            assertThrows(ResponseStatusException.class, () -> service.validateBody(body.deepCopy().putNull("workingDirectory")));
            for (String escape : List.of("systemPromptOverride", "agentArgs", "native", "framework", "model", "provider", "productProfile", "runId")) {
                ObjectNode invalid = body.deepCopy().put(escape, "escape");
                assertThrows(ResponseStatusException.class, () -> service.validateBody(invalid), escape);
            }
            for (String field : List.of("skipPermissions", "includeHistory", "enableRag", "enableGraphRag"))
                assertThrows(ResponseStatusException.class, () -> service.validateBody(body.deepCopy().put(field, false)), field);
            for (String field : List.of("chatHistory", "attachments")) {
                ObjectNode invalid = body.deepCopy(); invalid.putArray(field);
                assertThrows(ResponseStatusException.class, () -> service.validateBody(invalid), field);
            }
            assertThrows(ResponseStatusException.class, () -> service.validateBody(body.deepCopy().put("turnId", "not-32-hex")));
            assertThrows(ResponseStatusException.class, () -> service.validateBody(body.deepCopy().put("turnId", "A".repeat(32))));
            assertThrows(ResponseStatusException.class, () -> service.validateBody(body.deepCopy().put("sessionId", "browser-chosen")));
            assertThrows(ResponseStatusException.class, () -> service.validateBody(body.deepCopy().put("workingDirectory", "/tmp")));
            assertThrows(ResponseStatusException.class, () -> service.validateBody(body.deepCopy().put("agentName", "native:claude")));
        }
    }

    private static String id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
    }

    private ProductChatPolicy policy() throws Exception {
        Files.writeString(directory.resolve("role.md"), "Product play role");
        Files.writeString(directory.resolve("mcp.json"), "{}");
        ObjectNode manifest = mapper.createObjectNode().put("schemaVersion", ProductChatPolicy.SCHEMA)
                .put("id", "elthoria").put("title", "Elthoria").put("role", "elthoria")
                .put("roleFile", "role.md").put("mcpConfigFile", "mcp.json").put("assetsDirectory", "assets")
                .put("idleTimeoutSeconds", 60).put("turnTimeoutSeconds", 60);
        manifest.putArray("nativeTools"); manifest.putArray("mcpServers").add("elthoria");
        manifest.putObject("readCapabilities").put("connection", "mcp__elthoria__connection").put("dashboard", "mcp__elthoria__dashboard");
        Path file = directory.resolve("product.json"); Files.writeString(file, mapper.writeValueAsString(manifest));
        return ProductChatPolicy.load(file, directory);
    }
    private KompileCliSessionWorker worker(ProductChatPolicy policy, FakeProcess child, List<String> command) {
        return new KompileCliSessionWorker(mapper, policy, "product-session", () -> List.of("installed-kompile"),
                (args, root) -> { command.addAll(args); assertEquals(directory.toRealPath(), root); return child; });
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "worker condition did not settle");
    }

    private static final class RecordingEmitter extends SseEmitter {
        final List<String> frames = new CopyOnWriteArrayList<>();
        final List<JsonNode> payloads = new CopyOnWriteArrayList<>();
        @Override public void send(SseEmitter.SseEventBuilder event) {
            for (var part : event.build()) {
                if (part.getData() instanceof JsonNode json) payloads.add(json);
                else frames.add(String.valueOf(part.getData()));
            }
        }
    }

    private final class FakeProcess extends Process {
        private final PipedInputStream stdout = new PipedInputStream(524_288);
        private final PipedOutputStream events;
        // Protocol responses are simulated; ownership/exit is a REAL disposable JVM handle.
        private final Process osProcess;
        private final List<WebSessionProtocol.Frame> frames = new CopyOnWriteArrayList<>();
        private final CountDownLatch exited = new CountDownLatch(1);
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private long sequence;
        volatile JsonNode capabilityContent = mapper.getNodeFactory().textNode("{\"runtimeId\":\"runtime-1\"}");
        volatile int destroyed;
        volatile boolean unavailableHandle;
        volatile boolean ignoreClose;
        volatile boolean holdApprovals;
        volatile boolean approvalPending = true;
        volatile boolean approvalAllowed;
        volatile String approvalDigest;
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream() {
            @Override public synchronized void flush() throws IOException {
                String json = toString(StandardCharsets.UTF_8); reset();
                if (json.isBlank()) return;
                WebSessionProtocol.Frame frame = WebSessionProtocol.parse(json.strip());
                ObjectNode data = mapper.createObjectNode().put("version", WebSessionProtocol.VERSION).put("requestId", frame.requestId());
                switch (frame.action()) {
                    case "session_open" -> {
                        approvalDigest = frame.policyDigest();
                        event("session_ready", data.put("sessionId", frame.sessionId()).put("policyDigest", frame.policyDigest()));
                    }
                    case "content_approvals", "content_approval" -> {
                        if (!holdApprovals) {
                            data.put("sessionId", "product-session").put("policyDigest", approvalDigest).put("ok", true);
                            if (frame.action().equals("content_approvals")) {
                                var approvals = data.putArray("approvals");
                                if (approvalPending) approvals.addObject().put("approvalId", "pending-1").put("operation", "text");
                            } else {
                                var decision = WebSessionProtocol.parseApproval(frame.text());
                                data.put("approvalId", decision.approvalId());
                                boolean ok = approvalPending && decision.approvalId().equals("pending-1");
                                data.put("ok", ok).put("status", ok ? decision.allow() ? "APPROVED" : "DENIED" : "REJECTED");
                                if (ok) { approvalPending = false; approvalAllowed = decision.allow(); }
                            }
                            event(frame.action(), data);
                        }
                    }
                    case "turn_submit" -> event("turn_started", data.put("turnId", frame.requestId()));
                    case "read_capability" -> {
                        data.put("capability", frame.capability()).put("ok", true);
                        data.set("content", capabilityContent); event("capability_result", data);
                        event("session_idle", mapper.createObjectNode());
                    }
                    case "session_close" -> { if (!ignoreClose) {
                        event("session_closed", data);
                        event("session_cleanup", mapper.createObjectNode().put("confirmed", true));
                        exit();
                    } }
                    default -> { }
                }
                frames.add(frame);
            }
        };
        FakeProcess() throws IOException { events = new PipedOutputStream(stdout); osProcess = ProductWorkerProcessFixture.start("leaf"); }
        private synchronized void event(String type, JsonNode data) throws IOException {
            ObjectNode event = mapper.createObjectNode().put("type", type).put("session_id", "product-session"); event.set("data", data);
            rawEvent(event);
        }
        private synchronized void rawEvent(ObjectNode event) throws IOException {
            event.put("seq", ++sequence);
            events.write((mapper.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8)); events.flush();
        }
        void finish(String request, String status) throws IOException {
            event("turn_completed", mapper.createObjectNode().put("requestId", request).put("turnId", request).put("status", status).put("content", "done"));
            event("session_idle", mapper.createObjectNode());
        }
        long count(String action) { return frames.stream().filter(f -> f.action().equals(action)).count(); }
        WebSessionProtocol.Frame last(String action) { return frames.stream().filter(f -> f.action().equals(action)).reduce((a, b) -> b).orElseThrow(); }
        private void exit() throws IOException {
            if (!alive.compareAndSet(true, false)) return;
            osProcess.destroy();
            try {
                if (!osProcess.waitFor(3, TimeUnit.SECONDS)) {
                    osProcess.destroyForcibly();
                    if (!osProcess.waitFor(3, TimeUnit.SECONDS)) throw new IOException("Fixture JVM did not exit");
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
            finally { exited.countDown(); events.close(); }
        }
        @Override public ProcessHandle toHandle() {
            if (unavailableHandle) throw new UnsupportedOperationException("Fixture ownership unavailable");
            return osProcess.toHandle();
        }
        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() throws InterruptedException { exited.await(); return 0; }
        @Override public boolean waitFor(long duration, TimeUnit unit) throws InterruptedException { return exited.await(duration, unit); }
        @Override public int exitValue() { if (alive.get()) throw new IllegalThreadStateException(); return 0; }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public void destroy() { destroyed++; try { exit(); } catch (IOException ignored) { } }
        @Override public Process destroyForcibly() { destroy(); return this; }
    }
}
