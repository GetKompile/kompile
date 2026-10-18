package ai.kompile.app.services.agent;

import ai.kompile.cli.common.ProductChatPolicy;
import ai.kompile.cli.common.WebSessionProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Transport only: one CLI HeadlessAgentRunner owns the loop and its private MCP child.
 * A browser connection never owns the process. No prompts, role definitions, provider
 * configuration or tool execution are implemented here.
 *
 * Wire v1: requests have version/action/requestId and action-specific fields. CLI events
 * are sequenced HeadlessRunEvent objects (seq/type), with product session payloads in data.
 * Ordinary tool events retain their separate detail field.
 * session_ready validates sessionId/policyDigest; turn_started carries turnId/requestId;
 * turn_completed carries turnId/requestId/content/status. A result event is NOT a worker
 * exit or turn boundary: only turn_completed finishes the browser view.
 */
public final class KompileCliSessionWorker implements AutoCloseable {
    static final int MAX_FRAME = 262_144;
    static final int MAX_TURNS = 256;
    static final int MAX_REPLAYS = 8;
    static final int MAX_EARLY_CANCELS = 256;
    static final long EARLY_CANCEL_TTL_NANOS = Duration.ofMinutes(5).toNanos();
    private static final String RUN_PREFIX = "harness-product-";
    static int startupTimeoutSeconds() {
        // Cold JAR-tier CLI + required game server startup is not a model-turn timeout.
        return Math.max(1, Math.min(300, Integer.getInteger("kompile.product.chat.startupTimeoutSeconds", 120)));
    }
    private static final Set<Path> JVM_LEASES = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper;
    private final ProductChatPolicy policy;
    private final String sessionId;
    private final String generation = UUID.randomUUID().toString();
    private final KompileCliHarnessClient.LauncherResolver launcher;
    private final KompileCliHarnessClient.ProcessStarter starter;
    private final ScheduledExecutorService timer;
    private final ThreadPoolExecutor views = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1024), r -> daemon(r, "product-chat-view"), new ThreadPoolExecutor.AbortPolicy());
    private final AtomicInteger viewBytes = new AtomicInteger();
    private static final int MAX_VIEW_BYTES = 524_288;
    private final LinkedHashMap<String, Turn> turns = new LinkedHashMap<>();
    private final ReceiptLedger receipts;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<Boolean> cleanupReceipt = new CompletableFuture<>();
    private final AtomicBoolean released = new AtomicBoolean();
    private final AtomicBoolean terminationStarted = new AtomicBoolean();
    private final AtomicBoolean finalized = new AtomicBoolean();
    private volatile String status = "closed";
    private volatile boolean closeAcknowledged;
    private Process process;
    private BufferedWriter stdin;
    private Thread writer;
    private final ArrayBlockingQueue<String> outgoing = new ArrayBlockingQueue<>(8);
    private Lease lease;
    private long lastSequence;
    private long lastActivity = System.nanoTime();
    private Turn active;
    private PendingRead pendingRead;
    private PendingControl pendingControl;
    private ScheduledFuture<?> idleCheck;
    private ScheduledFuture<?> processWatch;
    private OwnedProcessTree ownedProcesses;
    private ExternalOperation externalOperation;
    private boolean quarantined;

    static final class Turn {
        final String requestId;
        final String runId;
        final String text;
        HarnessReplayBuffer replay;
        String turnId;
        boolean cancelRequested;
        ScheduledFuture<?> timeout;
        Turn(String requestId, String runId, String text, HarnessReplayBuffer replay) {
            this.requestId = requestId; this.turnId = requestId;
            this.runId = runId; this.text = text; this.replay = replay;
        }
    }
    private record PendingRead(String id, String capability, CompletableFuture<JsonNode> result) { }
    private record PendingControl(String id, String type, String generation, String approvalId, boolean allow,
                                  CompletableFuture<JsonNode> result) { }

    /** Service-lifetime tombstones: replacing a CLI generation must not authorize a stale mutation. */
    static final class ReceiptLedger {
        private final Map<String, String> admitted = new HashMap<>();
        private final Map<String, Long> earlyCancels = new HashMap<>();
        private final Set<String> expiredCancels = new HashSet<>();
        private boolean cancellationHistoryFull;
        private final LongSupplier clock;
        ReceiptLedger() { this(System::nanoTime); }
        ReceiptLedger(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }
        synchronized void requireFresh(String id, String text) {
            expire();
            if (cancellationHistoryFull || expiredCancels.contains(id))
                throw new IllegalStateException("A cancellation receipt expired; the old turn must never execute");
            if (admitted.containsKey(id)) {
                if (!admitted.get(id).equals(text)) throw new IllegalArgumentException("Request id was already used for different text");
                throw new IllegalStateException("Turn belongs to an earlier generation; never resubmit it");
            }
            if (admitted.size() >= MAX_TURNS)
                throw new IllegalStateException("Product session receipt limit reached; receipts cannot be evicted safely");
        }
        synchronized boolean cancelled(String id) { expire(); return earlyCancels.containsKey(id); }
        synchronized void admit(String id, String text) {
            requireFresh(id, text); admitted.put(id, text); earlyCancels.remove(id);
        }
        synchronized boolean cancel(String id) {
            if (admitted.containsKey(id)) return false;
            expire();
            if (expiredCancels.contains(id)) return true;
            if (cancellationHistoryFull) throw new IllegalStateException("Cancellation history limit reached; admission is sealed");
            if (earlyCancels.containsKey(id)) return true; // A retry never extends the TTL.
            if (earlyCancels.size() >= MAX_EARLY_CANCELS)
                throw new IllegalStateException("Early cancellation limit reached; retry after expiry");
            earlyCancels.put(id, clock.getAsLong()); return true;
        }
        private void expire() {
            long now = clock.getAsLong();
            for (var iterator = earlyCancels.entrySet().iterator(); iterator.hasNext();) {
                var entry = iterator.next();
                if (now - entry.getValue() < EARLY_CANCEL_TTL_NANOS) continue;
                // Expiry frees a pending slot, never permission to execute a cancelled turn.
                if (expiredCancels.size() >= MAX_EARLY_CANCELS) {
                    cancellationHistoryFull = true;
                    earlyCancels.clear();
                    return; // Seal admission instead of evicting cancellation tombstones.
                }
                expiredCancels.add(entry.getKey());
                iterator.remove();
            }
        }
    }

    public KompileCliSessionWorker(ObjectMapper mapper, ProductChatPolicy policy, String sessionId) {
        this(mapper, policy, sessionId, KompileCliHarnessClient::resolveLauncher,
                (command, directory) -> new ProcessBuilder(command).directory(directory.toFile()).start());
    }

    KompileCliSessionWorker(ObjectMapper mapper, ProductChatPolicy policy, String sessionId, ReceiptLedger receipts) {
        this(mapper, policy, sessionId, KompileCliHarnessClient::resolveLauncher,
                (command, directory) -> new ProcessBuilder(command).directory(directory.toFile()).start(), receipts);
    }

    KompileCliSessionWorker(ObjectMapper mapper, ProductChatPolicy policy, String sessionId,
                            KompileCliHarnessClient.LauncherResolver launcher,
                            KompileCliHarnessClient.ProcessStarter starter) {
        this(mapper, policy, sessionId, launcher, starter, new ReceiptLedger());
    }

    KompileCliSessionWorker(ObjectMapper mapper, ProductChatPolicy policy, String sessionId,
                            KompileCliHarnessClient.LauncherResolver launcher,
                            KompileCliHarnessClient.ProcessStarter starter, LongSupplier cancellationClock) {
        this(mapper, policy, sessionId, launcher, starter, new ReceiptLedger(cancellationClock));
    }

    KompileCliSessionWorker(ObjectMapper mapper, ProductChatPolicy policy, String sessionId,
                            KompileCliHarnessClient.LauncherResolver launcher,
                            KompileCliHarnessClient.ProcessStarter starter, ReceiptLedger receipts) {
        this.receipts = Objects.requireNonNull(receipts);
        this.mapper = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.policy = policy; this.sessionId = sessionId;
        views.allowCoreThreadTimeOut(true);
        this.launcher = launcher; this.starter = starter;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "product-chat-deadlines"));
    }

    public void open() throws IOException {
        synchronized (this) {
            if (!status.equals("closed") || released.get()) throw new IllegalStateException("Create a new generation after closing the product worker");
            status = "opening";
            try {
                lease = Lease.acquire(policy.workspace());
                List<String> command = new ArrayList<>(launcher.resolve());
                command.addAll(List.of("chat", "--product-profile", policy.manifestPath().toString(),
                        "--web-session-worker", "--working-dir", policy.workspace().toString(),
                        "--session-id", sessionId, "--output-format", "stream-json"));
                process = starter.start(List.copyOf(command), policy.workspace());
                ownedProcesses = new OwnedProcessTree(process);
                // Retain handles while ancestry is visible, before EOF/shutdown can reparent MCP children.
                processWatch = timer.scheduleWithFixedDelay(ownedProcesses::capture, 0, 25, TimeUnit.MILLISECONDS);
                stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                writer = daemon(this::writeInput, "product-chat-input"); writer.start();
                daemon(this::readOutput, "product-chat-events").start();
                daemon(this::drainDiagnostics, "product-chat-diagnostics").start();
                ObjectNode open = frame("session_open", UUID.randomUUID().toString());
                open.put("sessionId", sessionId).put("policyDigest", policy.digest());
                write(open); // Bootstrap is NOT a model turn.
                idleCheck = timer.scheduleWithFixedDelay(this::expireIdle, 1, 1, TimeUnit.SECONDS);
            } catch (Exception failure) {
                fail();
                if (process == null) release();
                else daemon(this::terminateAndWait, "product-chat-failed-start").start();
                throw new IOException("Product worker could not be opened");
            }
        }
        try { ready.get(startupTimeoutSeconds(), TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); close(); throw new IOException("Product worker open interrupted");
        } catch (ExecutionException | TimeoutException invalid) {
            close(); throw new IOException("Product worker did not confirm the selected policy");
        }
    }

    public synchronized String status() { return status; }
    public String generation() { return generation; }
    /** Root exit alone is not a generation/lease handoff barrier. */
    public synchronized boolean exited() { return released.get(); }
    public synchronized boolean quarantined() { return quarantined; }

    /** No queue. A retry attaches to the existing receipt, never re-executes the turn. */
    public synchronized String submit(String requestId, String text, SseEmitter emitter) throws IOException {
        requireNotQuarantined();
        if (externalOperation != null) throw new IllegalStateException("Product domain operation is in progress");
        requireId(requestId);
        Turn prior = turns.get(requestId);
        if (prior != null) {
            if (!prior.text.equals(text)) throw new IllegalArgumentException("Request id was already used for different text");
            if (prior.replay == null || views.isShutdown()) throw new IllegalStateException("Turn replay expired; do not resubmit this turn");
            attach(prior.replay, 0, emitter);
            return prior.runId;
        }
        receipts.requireFresh(requestId, text);
        boolean cancelledBeforeAdmission = receipts.cancelled(requestId);
        if (cancelledBeforeAdmission) {
            if (!Set.of("idle", "busy", "settling").contains(status))
                throw new IllegalStateException("Product worker is unavailable");
        } else requireIdle();
        if (text == null || text.isBlank() || text.length() > 32_768)
            throw new IllegalArgumentException("Product turn must contain bounded text");
        // Slash commands are a CLI control surface, not product model input.
        if (text.stripLeading().startsWith("/")) throw new IllegalArgumentException("Product chat does not accept CLI commands");
        ObjectNode submit = frame("turn_submit", requestId); submit.put("text", text);
        WebSessionProtocol.parse(mapper.writeValueAsString(submit)); // Validate before accepting a receipt.
        Turn turn = new Turn(requestId, RUN_PREFIX + requestId, text,
                new HarnessReplayBuffer(mapper, 262_144, 512));
        receipts.admit(requestId, text);
        turns.put(requestId, turn); touch();
        // 'queued' is the browser's precomputed reconnect checkpoint, not an execution queue.
        turn.replay.send("queued", Map.of("processId", turn.runId, "sessionId", sessionId, "reconnectable", true));
        if (cancelledBeforeAdmission) {
            turn.cancelRequested = true;
            turn.replay.send("cancelled", Map.of("processId", turn.runId, "sessionId", sessionId,
                    "message", "Turn cancelled before execution; no operations were started"));
            turn.replay.complete();
            attach(turn.replay, 0, emitter); trimReplays();
            return turn.runId; // A permanent receipt replaces the short-lived cancellation intent.
        }
        active = turn; status = "busy";
        turn.replay.send("start", Map.of("processId", turn.runId, "sessionId", sessionId,
                "generation", generation, "engine", "kompile-cli-main"));
        turn.replay.send("harness_session", Map.of("session_id", sessionId, "cwd", policy.workspace().toString()));
        attach(turn.replay, 0, emitter);
        try {
            write(submit);
            turn.timeout = timer.schedule(() -> cancel(turn.runId), policy.turnTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (IOException broken) { fail(); throw new IOException("Product worker input unavailable; turn outcome is unknown"); }
        trimReplays();
        return turn.runId;
    }

    public synchronized Map<String, Object> activeRun() {
        if (views.isShutdown()) return Map.of();
        Turn recoverable = active;
        if (recoverable == null) for (Turn turn : turns.values()) if (turn.replay != null) recoverable = turn;
        if (recoverable == null) return Map.of();
        return Map.of("processId", recoverable.runId, "runId", recoverable.runId,
                "sessionId", sessionId, "generation", generation, "status", status,
                "finished", recoverable.replay.isTerminal(), "message", recoverable.text,
                "agentName", "role:" + policy.role());
    }

    public synchronized void reconnect(String runId, long after, SseEmitter emitter) {
        requireNotQuarantined();
        if (views.isShutdown()) throw new IllegalStateException("Product generation has closed; replay is unavailable");
        Turn turn = find(runId);
        if (turn == null || turn.replay == null) throw new IllegalStateException("Product turn replay unavailable");
        attach(turn.replay, after, emitter);
    }

    /** Cancellation cannot promise rollback. The persistent worker/MCP child stays alive. */
    public synchronized boolean cancel(String runId) {
        requireNotQuarantined();
        String requestId = requestIdForRun(runId);
        if (Set.of("closing", "closed", "failed").contains(status)) return false;
        Turn turn = turns.get(requestId);
        if (turn != null) {
            if (turn != active) return false; // Never turn a completed receipt into a new intent.
            if (!turn.cancelRequested) {
                turn.cancelRequested = true;
                sendCancellation(turn);
            }
            return true;
        }
        return receipts.cancel(requestId);
    }

    private static String requestIdForRun(String runId) {
        if (runId == null || !runId.startsWith(RUN_PREFIX))
            throw new IllegalArgumentException("Invalid product run identifier");
        String requestId = runId.substring(RUN_PREFIX.length());
        requireId(requestId);
        return requestId;
    }

    private void sendCancellation(Turn turn) {
        try {
            ObjectNode cancel = frame("turn_cancel", UUID.randomUUID().toString());
            cancel.put("turnId", turn.turnId); write(cancel);
        } catch (IOException broken) { fail(); }
    }

    /** Named reads run through the CLI's ordinary permission checks, never a second LLM loop. */
    public JsonNode readCapability(String capability) throws IOException {
        if (!Set.of("connection", "dashboard").contains(capability)) throw new IllegalArgumentException("Unknown read capability");
        PendingRead read;
        synchronized (this) {
            requireIdle();
            if (!policy.readCapabilities().containsKey(capability)) throw new IllegalArgumentException("Read capability not enabled by policy");
            read = new PendingRead(UUID.randomUUID().toString(), capability, new CompletableFuture<>());
            pendingRead = read; touch();
            ObjectNode frame = frame("read_capability", read.id()); frame.put("capability", capability);
            try { write(frame); }
            catch (IOException failed) { pendingRead = null; fail(); throw new IOException("Product capability input unavailable"); }
        }
        try { return read.result().get(15, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Read interrupted"); }
        catch (ExecutionException | TimeoutException failed) { throw new IOException("Product read capability unavailable"); }
        finally {
            synchronized (this) {
                // A timed-out read must not race a model turn while the child is still executing it.
                if (pendingRead == read && read.result().isDone()) { pendingRead = null; touch(); }
            }
        }
    }

    public JsonNode pendingApprovals() throws IOException {
        return contentControl("content_approvals", null, false);
    }

    public JsonNode resolveApproval(String approvalId, boolean allow) throws IOException {
        WebSessionProtocol.approvalId(approvalId);
        return contentControl("content_approval", approvalId, allow);
    }

    /** One bounded owner-routed control, no turn/model invocation and no work queue. */
    private JsonNode contentControl(String type, String approvalId, boolean allow) throws IOException {
        PendingControl control;
        synchronized (this) {
            requireIdle();
            control = new PendingControl(UUID.randomUUID().toString(), type, generation, approvalId, allow, new CompletableFuture<>());
            pendingControl = control; touch();
            ObjectNode command = frame(type, control.id());
            if (approvalId != null) command.put("text", mapper.writeValueAsString(
                    mapper.createObjectNode().put("approvalId", approvalId).put("allow", allow)));
            try { write(command); }
            catch (IOException failed) { fail(); throw new IOException("Product approval input unavailable"); }
        }
        try { return control.result().get(15, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            synchronized (this) { if (pendingControl == control) fail(); }
            throw new IOException("Product approval interrupted");
        } catch (ExecutionException | TimeoutException failed) {
            synchronized (this) { if (pendingControl == control) fail(); } // No late receipt may authorize use after timeout.
            throw new IOException("Product approval unavailable");
        }
    }

    private void requireNotQuarantined() {
        if (quarantined) throw new IllegalStateException("Product mutation outcome is unknown; explicitly close the session before further work");
    }

    private synchronized void requireIdle() {
        requireNotQuarantined();
        if (!status.equals("idle") || active != null || pendingRead != null || pendingControl != null || externalOperation != null)
            throw new IllegalStateException("Product worker is not idle");
    }

    /** Called only after the ordinary MCP connection read, at its genuine session_idle barrier. */
    synchronized ExternalOperation claimExternalOperation() throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (status.equals("settling") && active == null && pendingRead == null && !quarantined) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("Product worker did not become idle after connection read");
            try { TimeUnit.NANOSECONDS.timedWait(this, remaining); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Domain admission interrupted"); }
        }
        requireIdle();
        externalOperation = new ExternalOperation(); touch();
        return externalOperation;
    }

    /** Pins idle expiry and model admission, not a promise of remote rollback or quiescence. */
    public final class ExternalOperation implements AutoCloseable {
        private boolean closed;
        public void quarantine() {
            synchronized (KompileCliSessionWorker.this) {
                if (closed || externalOperation != this) throw new IllegalStateException("Domain lease is no longer owned");
                quarantined = true; status = "quarantined";
            }
        }
        @Override public void close() {
            synchronized (KompileCliSessionWorker.this) {
                if (closed) return;
                closed = true;
                if (externalOperation == this) externalOperation = null;
                touch(); KompileCliSessionWorker.this.notifyAll();
            }
        }
    }

    private ObjectNode frame(String action, String requestId) {
        return mapper.createObjectNode().put("version", WebSessionProtocol.VERSION).put("action", action).put("requestId", requestId);
    }

    private synchronized void write(ObjectNode frame) throws IOException {
        String json = mapper.writeValueAsString(frame);
        try { WebSessionProtocol.parse(json); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid product input frame"); }
        if (stdin == null || !outgoing.offer(json)) throw new IOException("Product input unavailable or saturated");
    }

    private void writeInput() {
        // Bounded control-frame outbox, not a turn queue. A blocked child pipe never owns
        // the session monitor, so explicit close can still terminate that exact child.
        try {
            while (!Thread.currentThread().isInterrupted()) {
                String frame = outgoing.take();
                stdin.write(frame); stdin.newLine(); stdin.flush();
            }
        } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        catch (IOException broken) {
            synchronized (this) { if (!Set.of("closing", "closed").contains(status)) fail(); }
            terminateAndWait();
        }
    }

    private void readOutput() {
        try (Reader output = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = boundedLine(output)) != null) {
                if (line.isBlank()) continue;
                JsonNode event = mapper.readTree(line);
                acceptEvent(event);
            }
        } catch (Exception invalid) { synchronized (this) { if (!finalized.get() && !status.equals("closing")) fail(); } }
        finally {
            // EOF isn't proof that the process exited. Keep the OS lease until waitFor confirms it.
            terminateAndWait();
        }
    }

    private synchronized void acceptEvent(JsonNode event) throws IOException {
        if (finalized.get()) return; // The close waiter may win the race with the last buffered stdout frame.
        if (event == null || !event.isObject() || !event.path("seq").isIntegralNumber() || !event.path("seq").canConvertToLong()
                || event.path("seq").asLong() <= lastSequence) throw new IOException("Invalid product event sequence");
        lastSequence = event.path("seq").asLong();
        String type = event.path("type").asText();
        JsonNode data = event.path("data");
        switch (type) {
            case "session_ready" -> {
                if (!data.path("version").isIntegralNumber() || data.path("version").asInt() != WebSessionProtocol.VERSION
                        || !status.equals("opening") || !sessionId.equals(data.path("sessionId").asText())
                        || !policy.digest().equals(data.path("policyDigest").asText()))
                    throw new IOException("Product policy handshake mismatch");
                status = "idle"; touch(); ready.complete(null);
            }
            case "session_idle" -> {
                if (active == null && !quarantined && !Set.of("closing", "failed").contains(status)) { status = "idle"; touch(); }
                notifyAll();
            }
            case "session_closed" -> {
                if (active == null && data.path("status").asText().equals("idle_expired")) {
                    if (ownedProcesses != null) ownedProcesses.prepareShutdown();
                    status = "closing";
                }
                if (!status.equals("closing")) throw new IOException("Unexpected session close");
                closeAcknowledged = true;
            }
            case "session_cleanup" -> {
                if (!status.equals("closing") || !closeAcknowledged || cleanupReceipt.isDone()
                        || !data.path("confirmed").isBoolean()) throw new IOException("Unexpected cleanup receipt");
                boolean managed = policy.nativeActionPolicy() == null
                        || data.path("managedWorkCleanupConfirmed").isBoolean() && data.path("managedWorkCleanupConfirmed").booleanValue();
                cleanupReceipt.complete(data.path("confirmed").booleanValue() && managed);
            }
            case "protocol_error" -> throw new IOException("Product worker refused protocol frame");
            case "content_approvals", "content_approval" -> {
                PendingControl control = pendingControl;
                if (control == null || !control.id().equals(data.path("requestId").asText())
                        || !control.type().equals(type) || !control.generation().equals(generation)
                        || !sessionId.equals(event.path("session_id").asText())
                        || !data.path("version").isIntegralNumber() || !data.path("version").canConvertToInt()
                        || data.path("version").asInt() != WebSessionProtocol.VERSION
                        || !sessionId.equals(data.path("sessionId").asText())
                        || !policy.digest().equals(data.path("policyDigest").asText())
                        || !data.path("ok").isBoolean() || !status.equals("idle"))
                    throw new IOException("Unexpected content approval receipt");
                if (type.equals("content_approvals")) {
                    JsonNode approvals = data.path("approvals");
                    if (!data.path("ok").booleanValue() || !approvals.isArray() || approvals.size() > 128)
                        throw new IOException("Invalid pending approvals");
                    Set<String> ids = new HashSet<>();
                    for (JsonNode approval : approvals) {
                        if (!approval.isObject() || !approval.path("approvalId").isTextual())
                            throw new IOException("Invalid approval summary");
                        String id = WebSessionProtocol.approvalId(approval.path("approvalId").textValue());
                        if (!ids.add(id)) throw new IOException("Duplicate approval summary");
                    }
                    control.result().complete(approvals.deepCopy());
                } else {
                    if (!Objects.equals(control.approvalId(), data.path("approvalId").asText())
                            || data.path("ok").booleanValue()
                            && !(control.allow() ? "APPROVED" : "DENIED").equals(data.path("status").asText()))
                        throw new IOException("Approval decision mismatch");
                    control.result().complete(data.deepCopy());
                }
                pendingControl = null; touch();
            }
            case "capability_result" -> {
                PendingRead read = pendingRead;
                if (read == null || !read.id().equals(data.path("requestId").asText())
                        || !read.capability().equals(data.path("capability").asText()))
                    throw new IOException("Unexpected capability receipt");
                if (data.path("ok").isBoolean() && data.path("ok").booleanValue() && data.has("content"))
                    read.result().complete(capabilityJson(data.get("content")));
                else read.result().completeExceptionally(new IOException("Capability read refused"));
                pendingRead = null; if (!status.equals("closing")) status = "settling"; touch();
            }
            case "turn_started" -> {
                Turn turn = requireTurn(data);
                String id = data.path("turnId").asText();
                if (!turn.requestId.equals(id)) throw new IOException("Turn identity mismatch");
                turn.replay.send(type, data);
            }
            case "turn_completed" -> {
                Turn turn = requireTurn(data);
                if (turn.turnId != null && !turn.turnId.equals(data.path("turnId").asText()))
                    throw new IOException("Turn receipt mismatch");
                finish(turn, data.path("status").asText(), data.path("content").asText(""));
            }
            default -> { if (active != null) adaptTurnEvent(active, type, event); }
        }
    }

    private JsonNode capabilityJson(JsonNode content) throws IOException {
        // ProductSessionRuntime.emit carries ToolResult.output as JSON text, not a nested node.
        if (content == null || !content.isTextual()) throw new IOException("Capability content must be JSON text");
        JsonNode result = mapper.reader()
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(content.textValue());
        if (result == null || !(result.isObject() || result.isArray()))
            throw new IOException("Capability did not return structured JSON");
        return result;
    }

    private Turn requireTurn(JsonNode data) throws IOException {
        if (active == null || !active.requestId.equals(data.path("requestId").asText()))
            throw new IOException("Unexpected turn receipt");
        return active;
    }

    private void adaptTurnEvent(Turn turn, String type, JsonNode event) throws IOException {
        switch (type) {
            case "text" -> turn.replay.send("chunk", event.path("text").asText(""));
            case "thinking" -> turn.replay.send("thinking", event.path("text").asText(""));
            case "tool_start" -> turn.replay.send("tool_use", Map.of("callId", event.path("call_id").asText(""),
                    "toolName", event.path("name").asText(""), "input", event.path("input").asText(""), "status", "started"));
            case "tool" -> {
                ObjectNode tool = mapper.createObjectNode().put("callId", event.path("call_id").asText(""))
                        .put("toolName", event.path("name").asText(""))
                        .put("ok", event.path("ok").asBoolean()).put("durationMs", event.path("ms").asLong())
                        .put("status", "completed");
                if (event.has("detail")) tool.set("detail", event.get("detail"));
                turn.replay.send("tool_result", tool);
            }
            case "session" -> {
                if (!sessionId.equals(event.path("session_id").asText())) throw new IOException("Session identity mismatch");
                turn.replay.send("harness_session", event);
            }
            case "title", "backend" -> turn.replay.send(type, event);
            case "sources", "stats" -> turn.replay.send(type, event.path(type));
            case "activity", "control", "turn_complete" -> turn.replay.send(type, event.path("data"));
            // CLI result/error is per-engine data; the session worker emits the authoritative boundary.
            case "usage" -> turn.replay.send("stats", Map.of("durationMs", 0, "costUsd", 0.0,
                    "numTurns", 1, "isError", false, "tokenMetrics", Map.of(
                            "inputTokens", event.path("input_tokens").asLong(), "outputTokens", event.path("output_tokens").asLong(),
                            "cacheReadTokens", event.path("cache_read_tokens").asLong(),
                            "cacheCreationTokens", event.path("cache_creation_tokens").asLong(),
                            "totalGenerationMs", 0, "tokensPerSecond", 0.0)));
            case "result", "error", "command" -> { }
            default -> throw new IOException("Unsupported product event");
        }
    }

    private void finish(Turn turn, String outcome, String content) throws IOException {
        if (turn.timeout != null) turn.timeout.cancel(false);
        switch (outcome) {
            case "completed", "complete", "success", "ok" -> turn.replay.send("complete", Map.of(
                    "processId", turn.runId, "sessionId", sessionId, "content", content,
                    "generation", generation, "engine", "kompile-cli-main", "modifiedFiles", List.of()));
            case "cancelled", "canceled" -> turn.replay.send("cancelled", Map.of("processId", turn.runId,
                    "sessionId", sessionId, "message", "Turn cancelled; completed operations are not rolled back"));
            default -> turn.replay.send("error", "Product turn failed; completed operations are not rolled back");
        }
        turn.replay.complete(); active = null;
        if (!status.equals("closing")) status = "settling"; // session_idle is the genuine quiescence barrier.
        touch(); trimReplays();
    }

    private void trimReplays() {
        int retained = 0;
        List<Turn> reverse = new ArrayList<>(turns.values()); Collections.reverse(reverse);
        for (Turn turn : reverse) if (turn.replay != null && ++retained > MAX_REPLAYS && turn != active) turn.replay = null;
    }

    private Turn find(String runId) { return turns.values().stream().filter(t -> t.runId.equals(runId)).findFirst().orElse(null); }
    private void touch() { lastActivity = System.nanoTime(); }
    private void expireIdle() {
        expireIdle(System.nanoTime());
    }

    // The timestamp may be stale, but the admission decision and closing claim never are.
    void expireIdle(long now) { expireIdle(now, () -> { }); }

    // Package seam for deterministic admission/cleanup interleaving tests; no lease bypass.
    void expireIdle(long now, Runnable afterClaim) {
        synchronized (this) {
            if (!status.equals("idle") || active != null || pendingRead != null || pendingControl != null || externalOperation != null || quarantined
                    || now - lastActivity < Duration.ofSeconds(policy.idleTimeoutSeconds()).toNanos()) return;
            claimClosing();
        }
        try { afterClaim.run(); }
        finally { terminateAndWait(); } // Never wait for the event reader under its admission monitor.
    }

    private synchronized void fail() {
        status = "failed";
        notifyAll();
        ready.completeExceptionally(new IOException("Product worker unavailable"));
        if (pendingControl != null) { pendingControl.result().completeExceptionally(new IOException("Product worker unavailable")); pendingControl = null; }
        if (pendingRead != null) { pendingRead.result().completeExceptionally(new IOException("Product worker unavailable")); pendingRead = null; }
        if (active != null) {
            if (active.timeout != null) active.timeout.cancel(false);
            try { active.replay.send("error", "Product worker disconnected; turn outcome is unknown. Do not resubmit mutations."); }
            catch (IOException ignored) { }
            active.replay.complete(); active = null;
        }
    }

    @Override public void close() {
        synchronized (this) {
            if (status.equals("closed") || status.equals("closing")) return;
            if (finalized.get() && released.get()) { status = "closed"; return; }
            if (terminationStarted.get()) return; // A failed bounded reap is sealed, not a lease-bypass retry.
            claimClosing();
        }
        terminateAndWait();
    }

    private void claimClosing() {
        // Capture BEFORE sending even the graceful frame: the CLI may exit/reparent immediately.
        if (ownedProcesses != null) ownedProcesses.prepareShutdown();
        status = "closing"; notifyAll();
        if (pendingControl != null) { pendingControl.result().completeExceptionally(new IOException("Product worker closing")); pendingControl = null; }
        try { write(frame("session_close", UUID.randomUUID().toString())); } catch (IOException ignored) { }
    }

    private void terminateAndWait() {
        if (!terminationStarted.compareAndSet(false, true)) return;
        Process child;
        synchronized (this) {
            child = process;
            if (!status.equals("closing")) fail(); // EOF/failed input seals admission before any blocking reap.
        }
        boolean interrupted = false;
        try {
            if (ownedProcesses != null) ownedProcesses.prepareShutdown();
            if (child != null && (ownedProcesses == null || ownedProcesses.safeRoot())) {
                child.waitFor(2, TimeUnit.SECONDS);
                // Session closure acknowledgement precedes normal MCP/client/JVM
                // cleanup. Permit that cooperative phase its own bounded window;
                // an unresponsive worker still has only the original grace period.
                // Content mode drains in-host jobs before the separate MCP EOF grace.
                // This fixed, profile-owned budget never extends an unacknowledged/forced close.
                if (child.isAlive() && closeAcknowledged) child.waitFor(policy.nativeActionPolicy() == null ? 10 : 20, TimeUnit.SECONDS);
                if (ownedProcesses != null) ownedProcesses.signal(false);
                if (child.isAlive()) {
                    if (ownedProcesses != null) ownedProcesses.unverifiedTerminationBoundary();
                    child.destroy();
                }
                awaitOwnedExit(2);
                // Kill/reap descendants BEFORE forcing their parent, avoiding orphan zombies where possible.
                if (ownedProcesses != null) ownedProcesses.signal(true);
                awaitDescendantExit(2);
                if ((ownedProcesses == null || ownedProcesses.descendantsExited()) && child.isAlive()) {
                    if (ownedProcesses != null) ownedProcesses.unverifiedTerminationBoundary();
                    child.destroyForcibly();
                }
                child.waitFor(2, TimeUnit.SECONDS);
                awaitOwnedExit(2);
            }
            boolean confirmed = false;
            if (child != null && !child.isAlive()) {
                // Drain the final post-MCP-cleanup receipt even if OS exit wins
                // the race with the JSON reader. No receipt is not successful closure.
                try { confirmed = cleanupReceipt.get(1, TimeUnit.SECONDS); }
                catch (ExecutionException | TimeoutException absent) { /* fail closed below */ }
            }
            if (child == null || confirmed && ownedProcesses != null && ownedProcesses.allExited()) finishExit();
            else synchronized (this) { fail(); } // Unknown/unreaped owner: keep BOTH leases, never publish exited.
            System.err.println("KOMPILE_PRODUCT_CLEANUP " + mapper.valueToTree(Map.of(
                    "acknowledged", closeAcknowledged, "mcpCleanupConfirmed", cleanupReceipt.getNow(false), "status", status,
                    "tree", ownedProcesses == null ? Map.of("absent", true) : ownedProcesses.evidence())));
        } catch (InterruptedException stopped) {
            interrupted = true;
            synchronized (this) { fail(); }
        } finally {
            stopTransport();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void awaitOwnedExit(int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (ownedProcesses != null && !ownedProcesses.trackedExited() && System.nanoTime() < end) Thread.sleep(20);
    }
    private void awaitDescendantExit(int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (ownedProcesses != null && !ownedProcesses.descendantsExited() && System.nanoTime() < end) Thread.sleep(20);
    }

    /** Only handles discovered from this exact launched child; never a system-wide PID sweep. */
    static final class OwnedProcessTree {
        private final Process child;
        private ProcessHandle root;
        private final Map<Long, ProcessHandle> owned = new LinkedHashMap<>();
        private final Map<Long, Instant> starts = new HashMap<>();
        private boolean unknown;
        private final Set<String> uncertaintyReasons = new LinkedHashSet<>();
        private void uncertain(String reason) { unknown = true; uncertaintyReasons.add(reason); }
        private boolean shutdownCaptured;
        private boolean unsafeRoot;
        private static final int MAX_OWNED = 256;
        OwnedProcessTree(Process child) {
            this.child = child;
            try {
                root = child.toHandle();
                if (currentOrAncestor(root.pid())) {
                    root = null; uncertain("unsafe_ancestry"); unsafeRoot = true; return;
                }
                remember(root); capture();
            } catch (RuntimeException unavailable) { uncertain("root_unavailable"); }
        }
        private static boolean currentOrAncestor(long pid) {
            ProcessHandle cursor = ProcessHandle.current();
            Set<Long> seen = new HashSet<>();
            for (int depth = 0; depth < 256; depth++) {
                if (cursor.pid() == pid || !seen.add(cursor.pid())) return true;
                Optional<ProcessHandle> parent = cursor.parent();
                if (parent.isEmpty()) return false;
                cursor = parent.get();
            }
            return true; // Unverifiable ancestry must never authorize termination.
        }
        synchronized void prepareShutdown() {
            capture();
            shutdownCaptured = true;
        }
        synchronized boolean safeRoot() {
            try { return !unsafeRoot && (root == null || !root.isAlive() || sameProcess(root)); }
            catch (RuntimeException unavailable) { uncertain("root_identity_unavailable"); return false; }
        }
        synchronized void unverifiedTerminationBoundary() {
            // ProcessHandle has no atomic fork/exit barrier (unlike an OS job/cgroup). A signalled
            // parent's shutdown hook could fork AFTER our last snapshot. Reap known handles, but
            // NEVER certify exhaustive quiescence/release across non-cooperative termination.
            uncertain("parent_signalled");
        }
        synchronized void capture() {
            if (root == null) { uncertain("no_root_handle"); return; }
            try {
                // An unexpected root exit may already have hidden/reparented unseen children.
                if (!root.isAlive() && !shutdownCaptured) uncertain("root_exited_before_close");
                for (ProcessHandle parent : List.copyOf(owned.values())) {
                    if (!parent.isAlive()) {
                        if (!shutdownCaptured) uncertain("descendant_exited_before_close"); // Former descendants may already have reparented.
                        continue;
                    }
                    if (!sameProcess(parent)) continue;
                    try (var descendants = parent.descendants()) {
                        descendants.limit(MAX_OWNED + 1L).forEach(handle -> {
                            if (handle.pid() == ProcessHandle.current().pid()) { uncertain("self_in_tree"); return; }
                            if (!owned.containsKey(handle.pid()) && owned.size() >= MAX_OWNED) { uncertain("tree_limit"); return; }
                            remember(handle);
                        });
                    }
                }
            } catch (RuntimeException unavailable) { uncertain("capture_unavailable"); }
        }
        private void remember(ProcessHandle handle) {
            Instant started = handle.info().startInstant().orElse(null);
            if (started == null) {
                if (shutdownCaptured && !handle.isAlive()) return;
                uncertain("start_identity_missing"); return;
            }
            Instant prior = starts.putIfAbsent(handle.pid(), started);
            if (prior != null && !prior.equals(started)) { uncertain("pid_reused"); return; } // PID reuse is not ownership.
            owned.putIfAbsent(handle.pid(), handle);
        }
        private boolean sameProcess(ProcessHandle handle) {
            Instant expected = starts.get(handle.pid());
            Optional<Instant> actual = handle.info().startInstant();
            if (expected != null && actual.filter(expected::equals).isPresent()) return true;
            // A known handle may exit between the caller's liveness check and info().
            // Match the already-exited capture branch; never excuse a live or reused PID.
            if (actual.isEmpty() && shutdownCaptured && !handle.isAlive()) return false;
            uncertain("live_identity_unverified"); return false; // Never signal a reused PID or an identity we cannot verify.
        }
        synchronized void signal(boolean force) {
            capture();
            List<ProcessHandle> descendants = new ArrayList<>(owned.values());
            Collections.reverse(descendants);
            for (ProcessHandle handle : descendants) {
                if (root == null || handle.pid() == root.pid()) continue;
                try {
                    if (handle.isAlive() && sameProcess(handle)) {
                        unverifiedTerminationBoundary();
                        if (force) handle.destroyForcibly(); else handle.destroy();
                    }
                } catch (RuntimeException unavailable) { uncertain("signal_unavailable"); }
            }
        }
        synchronized boolean descendantsExited() {
            capture();
            try { return owned.values().stream().filter(p -> root == null || p.pid() != root.pid()).noneMatch(ProcessHandle::isAlive); }
            catch (RuntimeException unavailable) { uncertain("descendant_exit_unavailable"); return false; }
        }
        synchronized boolean trackedExited() {
            try { return descendantsExited() && root != null && !root.isAlive() && !child.isAlive(); }
            catch (RuntimeException unavailable) { uncertain("root_exit_unavailable"); return false; }
        }
        synchronized Map<String, Object> evidence() {
            boolean exited = trackedExited();
            boolean safe = safeRoot();
            return Map.of("unknown", unknown, "shutdownCaptured", shutdownCaptured,
                    "tracked", owned.size(), "trackedExited", exited, "safeRoot", safe,
                    "uncertaintyReasons", List.copyOf(uncertaintyReasons));
        }
        synchronized boolean allExited() { return trackedExited() && !unknown; }
    }

    private void finishExit() {
        if (!finalized.compareAndSet(false, true)) return;
        synchronized (this) {
            boolean closing = status.equals("closing");
            fail();
            release(); // Publish 'closed' only after the canonical workspace lease is actually free.
            if (closing) status = "closed";
        }
    }

    private void release() {
        if (!released.compareAndSet(false, true)) return;
        stopTransport();
        synchronized (this) { if (lease != null) { lease.close(); lease = null; } }
    }

    private void stopTransport() {
        if (idleCheck != null) idleCheck.cancel(false);
        if (processWatch != null) processWatch.cancel(false);
        timer.shutdownNow();
        outgoing.clear(); if (writer != null) writer.interrupt();
        views.shutdown(); // Bounded terminal delivery never owns the process or workspace lease.
    }

    private void drainDiagnostics() {
        // Drain, never log/store child stderr: it may contain provider credentials or tool data.
        try (InputStream diagnostics = process.getErrorStream()) {
            byte[] bytes = new byte[4096]; while (diagnostics.read(bytes) != -1) { }
        } catch (IOException ignored) { }
    }

    static String boundedLine(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder(); int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') return line.toString();
            if (line.length() >= MAX_FRAME) throw new IOException("Product event exceeds frame limit");
            if (c != '\r') line.append((char) c);
        }
        return line.isEmpty() ? null : line.toString();
    }

    static void requireId(String id) {
        if (id == null || !id.matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("Invalid product request identifier");
    }
    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread;
    }
    private static void closeView(SseEmitter emitter) {
        try { emitter.complete(); } catch (RuntimeException ignored) { }
    }

    private void attach(HarnessReplayBuffer replay, long after, SseEmitter emitter) {
        AtomicBoolean open = new AtomicBoolean(true);
        HarnessReplayBuffer.Connection connection = new HarnessReplayBuffer.Connection() {
            private void enqueue(int bytes, Runnable delivery) throws IOException {
                if (!open.get()) throw new IOException("View detached");
                int pending = viewBytes.addAndGet(bytes);
                if (pending > MAX_VIEW_BYTES) {
                    viewBytes.addAndGet(-bytes); open.set(false); closeView(emitter);
                    throw new IOException("View delivery window exceeded; reconnect for bounded replay");
                }
                try {
                    views.execute(() -> {
                        try { if (open.get()) delivery.run(); }
                        finally { viewBytes.addAndGet(-bytes); }
                    });
                } catch (RejectedExecutionException full) {
                    viewBytes.addAndGet(-bytes); open.set(false); closeView(emitter);
                    throw new IOException("View delivery unavailable; reconnect for replay");
                }
            }
            public void send(HarnessReplayBuffer.Event event) throws IOException {
                enqueue(Math.max(32, event.bytes()), () -> {
                    try {
                        var frame = SseEmitter.event().name(event.name()).data(event.data());
                        emitter.send(event.id() == HarnessReplayBuffer.RESYNC_ID ? frame : frame.id(Long.toString(event.id())));
                    } catch (IOException | RuntimeException disconnected) {
                        open.set(false); replay.detach(this); closeView(emitter);
                    }
                });
            }
            public void complete() {
                try { enqueue(32, () -> { open.set(false); closeView(emitter); }); }
                catch (IOException detached) { closeView(emitter); }
            }
            public void superseded() {
                try { send(new HarnessReplayBuffer.Event(HarnessReplayBuffer.RESYNC_ID, "superseded",
                        mapper.valueToTree(Map.of("message", "Run connected in another view")), 128)); }
                catch (IOException ignored) { }
                complete();
            }
        };
        emitter.onCompletion(() -> { open.set(false); replay.detach(connection); });
        emitter.onError(ignored -> { open.set(false); replay.detach(connection); });
        emitter.onTimeout(() -> { open.set(false); replay.detach(connection); closeView(emitter); });
        replay.attach(after, connection);
    }

    static Path privateDirectory(Path workspace) throws IOException {
        Path root = workspace.toRealPath(); Path directory = root.resolve(".kompile");
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            try { Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
            catch (FileAlreadyExistsException raced) { /* verify below */ }
        }
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.toRealPath().getParent().equals(root)) throw new IOException("Invalid product private directory");
        return directory;
    }

    /** Canonical-workspace JVM guard plus cross-process OS lease, with no global registry. */
    static final class Lease implements AutoCloseable {
        private final Path workspace;
        private final FileChannel channel;
        private final FileLock lock;
        private Lease(Path workspace, FileChannel channel, FileLock lock) { this.workspace = workspace; this.channel = channel; this.lock = lock; }
        static Lease acquire(Path workspace) throws IOException {
            Path root = workspace.toRealPath();
            if (!JVM_LEASES.add(root)) throw new IOException("Product workspace already has a worker");
            FileChannel channel = null;
            try {
                Path file = privateDirectory(root).resolve("product-chat.worker.lock");
                channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                FileLock lock = channel.tryLock();
                if (lock == null) throw new IOException("Product workspace already has an owner");
                return new Lease(root, channel, lock);
            } catch (IOException | OverlappingFileLockException failure) {
                if (channel != null) channel.close(); JVM_LEASES.remove(root);
                throw new IOException("Product workspace lease unavailable");
            }
        }
        @Override public void close() {
            try { lock.release(); } catch (IOException ignored) { }
            try { channel.close(); } catch (IOException ignored) { }
            JVM_LEASES.remove(workspace);
        }
    }
}
