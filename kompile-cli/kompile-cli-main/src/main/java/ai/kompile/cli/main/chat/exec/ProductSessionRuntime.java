package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.ProductChatPolicy;
import ai.kompile.cli.common.WebSessionProtocol;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Admission/lifetime adapter for the existing chat loop, not an inference engine. */
public final class ProductSessionRuntime implements AutoCloseable {
    @FunctionalInterface public interface Read { ToolResult call(String capability) throws Exception; }
    private record Input(WebSessionProtocol.Frame frame, boolean busyAtArrival, String error) { }
    private record Outcome(String content, boolean ok) { }
    private final InputStream input;
    private final ProductChatPolicy policy;
    private final WebSessionProtocol.Frame bootstrap;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean ran = new AtomicBoolean();
    private final AtomicBoolean inputClosed = new AtomicBoolean();
    private volatile Thread inputReader;
    private boolean remoteOutcomeUnknown;
    private volatile String currentTurnId;

    /** Actual admitted product request; not read-ahead admission or producer-selected identity. */
    public String currentTurnId() { return currentTurnId; }
    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.concurrent.atomic.AtomicReference<String> admission = new java.util.concurrent.atomic.AtomicReference<>();

    private ProductSessionRuntime(InputStream input, ProductChatPolicy policy, WebSessionProtocol.Frame bootstrap) {
        this.input = input;
        this.policy = policy;
        this.bootstrap = bootstrap;
    }

    /** Read exactly one strict bootstrap, with no model/config/command effects. */
    public static ProductSessionRuntime open(InputStream input, ProductChatPolicy policy) throws IOException {
        WebSessionProtocol.Frame frame = WebSessionProtocol.read(input);
        if (frame == null || !"session_open".equals(frame.action()) || !policy.digest().equals(frame.policyDigest()))
            throw new IllegalArgumentException("Product session bootstrap/policy mismatch");
        return new ProductSessionRuntime(input, policy, frame);
    }

    public ProductChatPolicy policy() { return policy; }
    public String sessionId() { return bootstrap.sessionId(); }

    public String run(AgenticChatLoop loop, ToolRegistry tools, WebHarnessControls.Turn turn,
                      Read read, Consumer<HeadlessRunEvent> events) throws Exception {
        return run(tools, turn, read, events, loop::setCancelSignal, loop::cancelActiveTurn);
    }

    // Source-test seam: fake the callbacks, never create a second model/runtime.
    String run(ToolRegistry tools, WebHarnessControls.Turn turn, Read read,
               Consumer<HeadlessRunEvent> events, Consumer<AtomicBoolean> cancellation,
               Runnable cancelActive) throws Exception {
        if (!ran.compareAndSet(false, true)) throw new IllegalStateException("Product session already run");
        ArrayBlockingQueue<Input> incoming = new ArrayBlockingQueue<>(32);
        Thread reader = new Thread(() -> {
            try {
                while (!closed.get()) {
                    WebSessionProtocol.Frame frame = WebSessionProtocol.read(input);
                    boolean operation = frame != null && (frame.action().equals("turn_submit")
                            || frame.action().equals("read_capability"));
                    // Reserve at read time: pipelined submits are rejected, not queued
                    // merely because a very fast first turn completed before dispatch.
                    boolean arrivedBusy = operation ? !admission.compareAndSet(null, frame.requestId())
                            : admission.get() != null;
                    incoming.put(new Input(frame, arrivedBusy, null));
                    // No read-ahead after terminal admission: native stdin may
                    // block while holding the very monitor required by close().
                    if (frame == null || frame.action().equals("session_close")) return;
                }
            } catch (Exception error) {
                try { incoming.put(new Input(null, admission.get() != null, "Invalid product session input")); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            } finally {
                closeOwnedInput();
            }
        }, "product-session-input");
        inputReader = reader;
        reader.setDaemon(true);
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "product-session-turn");
            thread.setDaemon(true);
            return thread;
        });
        Set<String> seen = new HashSet<>();
        seen.add(bootstrap.requestId());
        Future<Outcome> pending = null;
        WebSessionProtocol.Frame active = null;
        AtomicBoolean cancelled = new AtomicBoolean();
        String status = "completed";
        String latest = "";
        String closeReason = "closed";
        long started = 0;
        long idleSince = System.nanoTime();
        remoteOutcomeUnknown = tools.hasProductUnknownOutcome();
        if (remoteOutcomeUnknown) admission.set("quarantined");
        try {
            if (!remoteOutcomeUnknown) bindContentScope(policy, tools, read, mapper);
            emit(events, "session_ready", bootstrap.requestId(), null,
                    remoteOutcomeUnknown ? "quarantined" : "ready", "", !remoteOutcomeUnknown, null);
            if (!remoteOutcomeUnknown) emit(events, "session_idle", bootstrap.requestId(), null, "idle", "", true, null);
            reader.start();
            while (!closed.get()) {
                if (tools.hasProductUnknownOutcome()) {
                    remoteOutcomeUnknown = true;
                    status = "quarantined";
                    tools.stopProductAdmission();
                    if (!cancelled.getAndSet(true)) cancelActive.run();
                }
                Input next = incoming.poll(25, TimeUnit.MILLISECONDS);
                if (next != null) {
                    if (next.frame() == null) {
                        closeReason = next.error() == null ? "eof" : "protocol_error";
                        if (next.error() != null) emit(events, "protocol_error", null, null, "rejected", next.error(), false, null);
                        break;
                    }
                    WebSessionProtocol.Frame frame = next.frame();
                    if (seen.size() >= WebSessionProtocol.MAX_REQUESTS) { closeReason = "request_limit"; break; }
                    if (!seen.add(frame.requestId())) {
                        if (!next.busyAtArrival() && (frame.action().equals("turn_submit") || frame.action().equals("read_capability")))
                            admission.compareAndSet(frame.requestId(), null);
                        emit(events, "protocol_error", frame.requestId(), null, "duplicate", "Request already seen", false, null);
                        // The input owner stops at every terminal frame. A reused
                        // nonce is still rejected, but cannot leave a quarantined
                        // generation forever waiting on a reader that has exited.
                        if (frame.action().equals("session_close")) { closeReason = "duplicate_session_close"; close(); }
                        continue;
                    }
                    idleSince = System.nanoTime();
                    switch (frame.action()) {
                        case "session_close" -> { closeReason = "session_close"; close(); }
                        case "turn_cancel" -> {
                            if (active == null || !active.action().equals("turn_submit") || !active.requestId().equals(frame.turnId())) {
                                emit(events, "protocol_error", frame.requestId(), frame.turnId(), "rejected", "No matching active turn", false, null);
                            } else {
                                status = "cancelled";
                                tools.stopProductAdmission();
                                cancelled.set(true);
                                cancelActive.run();
                                emit(events, "control", frame.requestId(), frame.turnId(), "cancelling", "", true, "turn_cancel");
                            }
                        }
                        case "content_approvals", "content_approval" -> {
                            // Metadata/authority only: never open a tool turn or submit work to the executor.
                            ObjectNode data = mapper.createObjectNode();
                            data.put("version", WebSessionProtocol.VERSION).put("sessionId", sessionId())
                                    .put("policyDigest", policy.digest()).put("requestId", frame.requestId());
                            var manager = tools.productContentSession();
                            if (frame.action().equals("content_approvals")) {
                                data.set("approvals", manager == null ? mapper.createArrayNode() : manager.pendingApprovals());
                                data.put("ok", true);
                            } else {
                                var approval = WebSessionProtocol.parseApproval(frame.text());
                                if (active != null || next.busyAtArrival() || remoteOutcomeUnknown) {
                                    data.put("approvalId", approval.approvalId()).put("ok", false)
                                            .put("code", "CONTENT_APPROVAL_UNAVAILABLE");
                                } else if (manager == null) {
                                    data.put("approvalId", approval.approvalId()).put("ok", false)
                                            .put("code", "CONTENT_APPROVAL_DISABLED");
                                } else {
                                    var resolved = manager.resolveApproval(approval.approvalId(), approval.allow());
                                    if (resolved == null || !resolved.isObject()) throw new IOException("Invalid approval result");
                                    data.setAll((ObjectNode) resolved);
                                    String decision = resolved.path("status").asText();
                                    boolean accepted = approval.approvalId().equals(resolved.path("approvalId").asText())
                                            && (approval.allow() ? "APPROVED".equals(decision) : "DENIED".equals(decision));
                                    data.put("ok", accepted);
                                    // The manager cannot replace transport correlation or scope.
                                    data.put("version", WebSessionProtocol.VERSION).put("sessionId", sessionId())
                                            .put("policyDigest", policy.digest()).put("requestId", frame.requestId())
                                            .put("approvalId", approval.approvalId());
                                }
                            }
                            events.accept(new HeadlessRunEvent(0, frame.action().equals("content_approvals")
                                    ? HeadlessRunEvent.Type.CONTENT_APPROVALS : HeadlessRunEvent.Type.CONTENT_APPROVAL,
                                    sessionId(), "", "", "", "", data.path("ok").asBoolean(), 0, 0, "", Map.of(), data));
                        }
                        case "turn_submit", "read_capability" -> {
                            if (tools.hasProductUnknownOutcome()) {
                                remoteOutcomeUnknown = true;
                                emit(events, "protocol_error", frame.requestId(), null, "quarantined",
                                        "Remote tool outcome unknown; close this session", false, null);
                                continue;
                            }
                            if (active != null || next.busyAtArrival()) {
                                emit(events, "protocol_error", frame.requestId(), null, "busy", "One active operation; no queue", false, null);
                                continue;
                            }
                            if (frame.action().equals("read_capability") && !policy.readCapabilities().containsKey(frame.capability())) {
                                admission.compareAndSet(frame.requestId(), null);
                                emit(events, "capability_result", frame.requestId(), null, "rejected", "Unknown read capability", false, frame.capability());
                                continue;
                            }
                            active = frame;
                            status = "completed";
                            cancelled = new AtomicBoolean();
                            cancellation.accept(cancelled);
                            tools.openProductTurn();
                            started = System.nanoTime();
                            if (frame.action().equals("turn_submit"))
                                emit(events, "turn_started", frame.requestId(), frame.requestId(), "running", "", true, null);
                            currentTurnId = frame.action().equals("turn_submit") ? frame.requestId() : null;
                            pending = executor.submit(() -> {
                                try {
                                    if (frame.action().equals("turn_submit")) return new Outcome(turn.chat(frame.text()), true);
                                    ToolResult result = read.call(frame.capability());
                                    return new Outcome(result == null ? "" : result.getOutput(), result != null && !result.isError());
                                } catch (Exception failure) {
                                    // No provider headers, stack traces or config snapshots in browser events.
                                    return new Outcome("Operation failed", false);
                                } finally {
                                    tools.stopProductAdmission();
                                    tools.awaitProductQuiescence();
                                    currentTurnId = null;
                                }
                            });
                        }
                        default -> emit(events, "protocol_error", frame.requestId(), null, "rejected", "Session already open", false, null);
                    }
                }
                if (pending != null && pending.isDone()) {
                    Outcome outcome = pending.get(); // real callable completion, never Future.cancel()
                    boolean isTurn = active.action().equals("turn_submit");
                    if (isTurn) latest = outcome.content() == null ? "" : outcome.content();
                    remoteOutcomeUnknown |= tools.hasProductUnknownOutcome();
                    String completedStatus = remoteOutcomeUnknown ? "quarantined"
                            : !status.equals("completed") ? status : cancelled.get() ? "cancelled"
                            : outcome.ok() ? "completed" : "failed";
                    emit(events, isTurn ? "turn_completed" : "capability_result", active.requestId(),
                            isTurn ? active.requestId() : null, completedStatus, outcome.content(),
                            !remoteOutcomeUnknown && !cancelled.get() && outcome.ok()
                                    && status.equals("completed"), active.capability());
                    pending = null;
                    if (!remoteOutcomeUnknown) admission.compareAndSet(active.requestId(), null);
                    active = null;
                    idleSince = System.nanoTime();
                    // A locally returned transport failure is NOT remote quiescence.
                    // Keep the generation reserved until explicit close/EOF; never auto-resume.
                    if (!remoteOutcomeUnknown) emit(events, "session_idle", null, null, "idle", "", true, null);
                } else if (pending != null && !cancelled.get() && elapsedSeconds(started) >= policy.turnTimeoutSeconds()) {
                    status = "timed_out";
                    tools.stopProductAdmission();
                    cancelled.set(true);
                    cancelActive.run();
                    // Remain busy until both the loop and every actual tool delegate exit.
                } else if (pending == null && !remoteOutcomeUnknown
                        && elapsedSeconds(idleSince) >= policy.idleTimeoutSeconds()) {
                    closeReason = "idle_expired";
                    break;
                }
            }
        } finally {
            if (pending != null) {
                tools.stopProductAdmission();
                cancelled.set(true);
                cancelActive.run();
                boolean interrupted = false;
                Outcome closingOutcome = new Outcome("", false);
                for (;;) {
                    try { closingOutcome = pending.get(); break; }
                    catch (InterruptedException e) { interrupted = true; }
                    catch (ExecutionException e) { break; }
                }
                tools.awaitProductQuiescence();
                remoteOutcomeUnknown |= tools.hasProductUnknownOutcome();
                boolean isTurn = active.action().equals("turn_submit");
                emit(events, isTurn ? "turn_completed" : "capability_result", active.requestId(),
                        isTurn ? active.requestId() : null, remoteOutcomeUnknown ? "quarantined"
                                : status.equals("completed") ? "cancelled" : status,
                        closingOutcome.content(), false, active.capability());
                if (interrupted) Thread.currentThread().interrupt();
            }
            executor.shutdown();
            close();
            reader.interrupt();
            // Interrupted Java pipes finish here; a native stdin read may not.
            // It is a daemon, owns no game/tool work and cannot delay MCP cleanup.
            try { reader.join(1_000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            if (!reader.isAlive()) closeOwnedInput(); // Also covers failure before the reader was started.
            admission.set(null);
            remoteOutcomeUnknown |= tools.hasProductUnknownOutcome();
            emit(events, "session_closed", null, null, closeReason,
                    remoteOutcomeUnknown ? "Remote tool outcome remains unknown; closing does not confirm rollback or completion" : "",
                    !remoteOutcomeUnknown, null);
        }
        return latest;
    }

    static void bindContentScope(ProductChatPolicy policy, ToolRegistry tools, Read read, ObjectMapper mapper) throws Exception {
        var manager = tools.productContentSession();
        if (manager == null) return;
        if (!"mcp__elthoria__elthoria_knowledge_workspace".equals(policy.readCapabilities().get("knowledge")))
            throw new IllegalStateException("Product content requires the pinned knowledge metadata capability");
        tools.openProductTurn();
        try {
            ToolResult result = read.call("knowledge");
            if (result == null || result.isError()) throw new IllegalStateException("Product knowledge metadata is unavailable");
            manager.bindKnowledgeScope(mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(result.getOutput()));
        } finally { tools.awaitProductQuiescence(); }
    }

    private static long elapsedSeconds(long since) { return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - since); }

    private void emit(Consumer<HeadlessRunEvent> events, String type, String request, String turn,
                      String status, String content, boolean ok, String capability) {
        ObjectNode data = mapper.createObjectNode();
        data.put("version", WebSessionProtocol.VERSION);
        data.put("sessionId", sessionId());
        data.put("policyDigest", policy.digest());
        if (request != null) data.put("requestId", request);
        if (turn != null) data.put("turnId", turn);
        if (capability != null) data.put("capability", capability);
        data.put("status", status);
        data.put("content", content == null ? "" : content);
        data.put("ok", ok);
        if (remoteOutcomeUnknown) data.put("remoteOutcomeUnknown", true);
        events.accept(new HeadlessRunEvent(0, HeadlessRunEvent.Type.valueOf(type.toUpperCase(java.util.Locale.ROOT)),
                sessionId(), "", "", "", "", ok, 0, 0, "", Map.of(), data));
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            Thread reader = inputReader;
            if (reader == null) closeOwnedInput();
            else reader.interrupt(); // Only the reader closes a potentially locked stdin.
        }
    }

    private void closeOwnedInput() {
        if (inputClosed.compareAndSet(false, true)) {
            try { input.close(); } catch (IOException ignored) { }
        }
    }
}
