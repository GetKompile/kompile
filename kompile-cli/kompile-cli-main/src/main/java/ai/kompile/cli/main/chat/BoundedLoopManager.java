/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.skill.ManagedFileLock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Bounded variant of {@link ScheduledLoopManager}: each loop repeats on a fixed
 * interval but stops by itself once its bound is reached, instead of running forever.
 * <p>
 * A bound is either a fixed number of fires ("10") or a wall-clock duration
 * ("30m", "1h", "2h30m") — a bare number is a count, anything carrying a time
 * unit is a duration. When a loop reaches its bound it is marked
 * {@link BoundedLoop.LoopStatus#COMPLETED} and the completion callback fires once,
 * so the host can show a clear end-of-loop indicator.
 * <p>
 * Mirrors the scheduled-loop manager's persistence (atomic rewrite under a file
 * lock), attempt accounting (dispatch attempts, not successful deliveries), and
 * failure-callback containment (a failed dispatch or reporter never terminates
 * the loop before its bound is reached).
 */
public class BoundedLoopManager {

    public static final class BoundedLoop {

        public enum BoundType { COUNT, DURATION }

        public enum LoopStatus { ACTIVE, PAUSED, COMPLETED, STOPPED }

        private final String id;
        private final String interval;
        private final long intervalMs;
        private final BoundType boundType;
        private final int boundCount;
        private final long durationMs;
        private final Instant deadline;
        private final String prompt;
        private final Instant createdAt;
        private LoopStatus status;
        private Instant lastFiredAt;
        private int fireCount;
        private volatile String completionReason;
        private volatile Instant completedAt;

        BoundedLoop(String id, String interval, long intervalMs,
                    BoundType boundType, int boundCount, long durationMs, Instant deadline,
                    String prompt, Instant createdAt, LoopStatus status,
                    Instant lastFiredAt, int fireCount) {
            this.id = id;
            this.interval = interval;
            this.intervalMs = intervalMs;
            this.boundType = boundType;
            this.boundCount = boundCount;
            this.durationMs = durationMs;
            this.deadline = deadline;
            this.prompt = prompt;
            this.createdAt = createdAt;
            this.status = status;
            this.lastFiredAt = lastFiredAt;
            this.fireCount = fireCount;
        }

        public String getId() { return id; }
        public String getInterval() { return interval; }
        public long getIntervalMs() { return intervalMs; }
        public BoundType getBoundType() { return boundType; }
        /** Maximum fires for {@link BoundType#COUNT} loops, -1 otherwise. */
        public int getBoundCount() { return boundCount; }
        /** Total duration for {@link BoundType#DURATION} loops, -1 otherwise. */
        public long getDurationMs() { return durationMs; }
        /** Wall-clock end for {@link BoundType#DURATION} loops, null otherwise. */
        public Instant getDeadline() { return deadline; }
        public String getPrompt() { return prompt; }
        public Instant getCreatedAt() { return createdAt; }
        public LoopStatus getStatus() { return status; }
        public void setStatus(LoopStatus status) { this.status = status; }
        /** Time of the last dispatch attempt, including failed dispatches. */
        public Instant getLastFiredAt() { return lastFiredAt; }
        /** Number of dispatch attempts, not successful deliveries. */
        public int getFireCount() { return fireCount; }
        /** Why the loop completed, once it did; null while still active or paused. */
        public String getCompletionReason() { return completionReason; }
        /** When the loop completed, once it did; null while still active or paused. */
        public Instant getCompletedAt() { return completedAt; }

        void recordFire() {
            recordFire(Instant.now(), fireCount + 1);
        }

        void recordFire(Instant firedAt, int persistedFireCount) {
            this.lastFiredAt = firedAt;
            this.fireCount = persistedFireCount;
        }

        /** Fires that may still happen before the bound stops this loop. */
        public int getRemainingFires() {
            if (boundType != BoundType.COUNT) return -1;
            return Math.max(0, boundCount - fireCount);
        }

        /** Milliseconds left until a duration loop ends, 0 once elapsed; -1 for count loops. */
        public long getRemainingMs() {
            if (boundType != BoundType.DURATION) return -1;
            long remaining = Duration.between(Instant.now(), deadline).toMillis();
            return Math.max(0, remaining);
        }

        public String getFormattedInterval() {
            return formatDurationMs(intervalMs);
        }

        /** Compact bound label: "×10" for counts, "for 1h" for durations. */
        public String getFormattedBound() {
            if (boundType == BoundType.COUNT) return "×" + boundCount;
            return "for " + formatDurationMs(durationMs);
        }

        /** Live remaining label: "7 of 10 remaining" or "42m remaining"; "" once completed. */
        public String getFormattedRemaining() {
            if (status == LoopStatus.COMPLETED || status == LoopStatus.STOPPED) return "";
            if (boundType == BoundType.COUNT) {
                return getRemainingFires() + " of " + boundCount + " remaining";
            }
            return formatDurationMs(getRemainingMs()) + " remaining";
        }

        public String getStatusIcon() {
            return switch (status) {
                case ACTIVE -> "▶";
                case PAUSED -> "⏸";
                case COMPLETED -> "✓";
                case STOPPED -> "■";
            };
        }
    }

    // "10", "3" — a bare number is a fire count
    private static final Pattern COUNT_PATTERN = Pattern.compile("^\\d+$");
    // "30s", "5m", "1h", "2h30m" — one or more value+unit groups
    static final Pattern DURATION_PATTERN = Pattern.compile(
            "^(\\d+[smh])+$", Pattern.CASE_INSENSITIVE);

    private static final ConcurrentMap<Path, Object> JVM_STATE_LOCKS = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;
    private final Map<String, BoundedLoop> loops = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> guards = new ConcurrentHashMap<>();
    private final List<String> loopOrder = new CopyOnWriteArrayList<>();
    private final Consumer<String> fireCallback;
    private final BiConsumer<BoundedLoop, RuntimeException> failureCallback;
    private final Consumer<BoundedLoop> completionCallback;
    private final Path stateFile;
    private final long minimumIntervalMs;
    private final ObjectMapper objectMapper = JsonUtils.standardMapper();

    /** @param fireCallback invoked (on the scheduler thread) when a loop fires. */
    public BoundedLoopManager(Consumer<String> fireCallback, Consumer<BoundedLoop> completionCallback) {
        this(fireCallback, completionCallback, null, 5000);
    }

    public BoundedLoopManager(
            Consumer<String> fireCallback, Consumer<BoundedLoop> completionCallback, Path stateFile) {
        this(fireCallback, completionCallback, stateFile, 5000);
    }

    /**
     * @param fireCallback dispatches to the host's fixed target; throw a RuntimeException
     *                     if dispatch is rejected (for example, the target session closed)
     * @param completionCallback invoked exactly once when a loop reaches its bound;
     *                           RuntimeExceptions from it are contained
     * @param stateFile optional persistence path; null keeps state in memory
     * @param failureCallback optional failure reporter receiving the loop and original
     *                        exception on the firing thread; reporting failure only,
     *                        never retrying against a different session
     * @param minimumIntervalMs smallest allowed loop interval (tests use 1ms)
     */
    BoundedLoopManager(
            Consumer<String> fireCallback, Consumer<BoundedLoop> completionCallback, Path stateFile,
            long minimumIntervalMs, BiConsumer<BoundedLoop, RuntimeException> failureCallback) {
        this.fireCallback = fireCallback;
        this.completionCallback = completionCallback;
        this.failureCallback = failureCallback;
        this.stateFile = stateFile;
        this.minimumIntervalMs = Math.max(1, minimumIntervalMs);
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "bounded-loop-scheduler");
            t.setDaemon(true);
            return t;
        });
        loadPersistedLoops();
    }

    BoundedLoopManager(
            Consumer<String> fireCallback, Consumer<BoundedLoop> completionCallback,
            Path stateFile, long minimumIntervalMs) {
        this(fireCallback, completionCallback, stateFile, minimumIntervalMs, null);
    }

    /** Stable local state path scoped to one conversation, including across resume. */
    public static Path stateFileForSession(String sessionId) {
        String safeId = sessionId == null || sessionId.isBlank()
                ? "unknown-session"
                : sessionId.replaceAll("[^A-Za-z0-9._-]", "_");
        return KompileHome.homeDirectory().toPath()
                .resolve("conversations")
                .resolve(safeId + ".bounded-loops.json")
                .toAbsolutePath().normalize();
    }

    /**
     * Create a new bounded loop.
     *
     * @param interval interval between fires ("30s", "5m", "1h"); must carry a unit
     * @param bound    stop condition: a bare number ("10") is a fire count, a value
     *                 with time units ("1h", "2h30m") is a total active duration
     * @param prompt   the message or /command to fire on each tick
     * @return the created loop, or null if the interval/bound is invalid
     */
    public synchronized BoundedLoop create(String interval, String bound, String prompt) {
        if (interval == null || bound == null || prompt == null || prompt.isBlank()) return null;
        interval = interval.trim();
        bound = bound.trim();
        prompt = prompt.trim();

        if (!DURATION_PATTERN.matcher(interval).matches()) return null;
        long intervalMs = ScheduledLoopManager.parseIntervalMs(interval);
        if (intervalMs < minimumIntervalMs) return null;

        BoundedLoop.BoundType boundType;
        int boundCount = -1;
        long durationMs = -1;
        Instant deadline = null;
        if (COUNT_PATTERN.matcher(bound).matches()) {
            boundType = BoundedLoop.BoundType.COUNT;
            boundCount = Integer.parseInt(bound);
            if (boundCount <= 0) return null;
        } else if (DURATION_PATTERN.matcher(bound).matches()) {
            boundType = BoundedLoop.BoundType.DURATION;
            durationMs = ScheduledLoopManager.parseIntervalMs(bound);
            if (durationMs < 1) return null;
            deadline = Instant.now().plusMillis(durationMs);
        } else {
            return null; // unrecognized bound format
        }

        BoundedLoop loop = new BoundedLoop(
                UUID.randomUUID().toString().substring(0, 8),
                interval, intervalMs, boundType, boundCount, durationMs, deadline,
                prompt, Instant.now(), BoundedLoop.LoopStatus.ACTIVE, null, 0);
        loops.put(loop.getId(), loop);
        loopOrder.add(loop.getId());
        persistUpsert(loop);
        scheduleInterval(loop);
        scheduleCompletionGuard(loop);
        return loop;
    }

    /**
     * Pause an active loop; its remaining bound is preserved.
     */
    public synchronized boolean pause(String id) {
        BoundedLoop loop = get(id);
        if (loop == null || loop.getStatus() != BoundedLoop.LoopStatus.ACTIVE) return false;
        loop.setStatus(BoundedLoop.LoopStatus.PAUSED);
        if (!persistExisting(loop)) {
            discardStaleLoop(loop);
            return false;
        }
        cancelFutures(loop.getId());
        return true;
    }

    /**
     * Resume a paused loop. A duration loop whose deadline already passed while
     * paused completes immediately instead of scheduling past-the-bound fires.
     */
    public synchronized boolean resume(String id) {
        BoundedLoop loop = get(id);
        if (loop == null || loop.getStatus() != BoundedLoop.LoopStatus.PAUSED) return false;
        if (loop.getBoundType() == BoundedLoop.BoundType.DURATION
                && !Instant.now().isBefore(loop.getDeadline())) {
            loop.setStatus(BoundedLoop.LoopStatus.ACTIVE);
            complete(loop, "duration " + formatDurationMs(loop.getDurationMs())
                    + " elapsed while paused");
            return true;
        }
        loop.setStatus(BoundedLoop.LoopStatus.ACTIVE);
        if (!persistExisting(loop)) {
            discardStaleLoop(loop);
            return false;
        }
        scheduleInterval(loop);
        scheduleCompletionGuard(loop);
        return true;
    }

    /**
     * Stop and remove a loop permanently.
     */
    public synchronized boolean remove(String id) {
        BoundedLoop loop = get(id);
        if (loop == null) return false;
        loop.setStatus(BoundedLoop.LoopStatus.STOPPED);
        cancelFutures(loop.getId());
        loops.remove(loop.getId());
        loopOrder.remove(loop.getId());
        persistRemove(loop.getId());
        return true;
    }

    /**
     * Stop and remove every bounded loop in this manager's scope.
     *
     * @return the number of distinct in-memory or persisted loops removed
     */
    public synchronized int clear() {
        List<String> localIds = new ArrayList<>(loops.keySet());
        for (String id : localIds) {
            BoundedLoop loop = loops.get(id);
            if (loop != null) loop.setStatus(BoundedLoop.LoopStatus.STOPPED);
            cancelFutures(id);
        }

        int cleared = persistClear(localIds);
        for (String id : localIds) {
            BoundedLoop loop = loops.remove(id);
            if (loop != null) loop.setStatus(BoundedLoop.LoopStatus.STOPPED);
            cancelFutures(id);
        }
        loopOrder.removeAll(localIds);
        return cleared;
    }

    /**
     * Get a specific loop by ID (prefix match supported).
     */
    public BoundedLoop get(String idPrefix) {
        // Exact match first
        BoundedLoop exact = loops.get(idPrefix);
        if (exact != null) return exact;
        // Prefix match
        for (Map.Entry<String, BoundedLoop> e : loops.entrySet()) {
            if (e.getKey().startsWith(idPrefix)) return e.getValue();
        }
        return null;
    }

    /**
     * List all loops in creation order, including completed ones.
     */
    public List<BoundedLoop> list() {
        List<BoundedLoop> result = new ArrayList<>();
        for (String id : loopOrder) {
            BoundedLoop loop = loops.get(id);
            if (loop != null) result.add(loop);
        }
        return result;
    }

    /**
     * Number of loops that can still fire (active, bound not yet reached).
     */
    public int activeCount() {
        int count = 0;
        for (BoundedLoop loop : loops.values()) {
            if (loop.getStatus() == BoundedLoop.LoopStatus.ACTIVE) count++;
        }
        return count;
    }

    /**
     * Fire one loop immediately without changing its bound accounting.
     * @return true only if the dispatch callback returns normally; false if the loop
     *         cannot fire or dispatch fails. A failed dispatch still counts as an attempt.
     */
    public boolean runNow(String idPrefix) {
        BoundedLoop loop = get(idPrefix);
        if (loop == null || loop.getStatus() != BoundedLoop.LoopStatus.ACTIVE) return false;
        if (loop.getBoundType() == BoundedLoop.BoundType.DURATION
                && !Instant.now().isBefore(loop.getDeadline())) {
            complete(loop, "duration " + formatDurationMs(loop.getDurationMs()) + " elapsed");
            return false;
        }
        return fire(loop, true);
    }

    /**
     * Shutdown the scheduler and stop all loops. Call on REPL exit.
     */
    public void shutdown() {
        for (String id : new ArrayList<>(loops.keySet())) cancelFutures(id);
        scheduler.shutdownNow();
    }

    // ── Scheduling internals ──────────────────────────────────────────

    private void scheduleInterval(BoundedLoop loop) {
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            if (loop.getStatus() != BoundedLoop.LoopStatus.ACTIVE) return;
            // A duration loop must not fire at or after its deadline; the guard completes it.
            if (loop.getBoundType() == BoundedLoop.BoundType.DURATION
                    && !Instant.now().isBefore(loop.getDeadline())) {
                complete(loop, "duration " + formatDurationMs(loop.getDurationMs()) + " elapsed");
                return;
            }
            fire(loop, false);
        }, loop.getIntervalMs(), loop.getIntervalMs(), TimeUnit.MILLISECONDS);
        futures.put(loop.getId(), future);
    }

    /** Ends a duration loop at its deadline even when no tick lands there. */
    private void scheduleCompletionGuard(BoundedLoop loop) {
        if (loop.getBoundType() != BoundedLoop.BoundType.DURATION) return;
        long remainingMs = Math.max(1, loop.getRemainingMs());
        ScheduledFuture<?> guard = scheduler.schedule(() -> {
            if (loop.getStatus() == BoundedLoop.LoopStatus.ACTIVE) {
                complete(loop, "duration " + formatDurationMs(loop.getDurationMs()) + " elapsed");
            }
        }, remainingMs, TimeUnit.MILLISECONDS);
        guards.put(loop.getId(), guard);
    }

    private void cancelFutures(String id) {
        ScheduledFuture<?> f = futures.remove(id);
        if (f != null) f.cancel(false);
        ScheduledFuture<?> g = guards.remove(id);
        if (g != null) g.cancel(false);
    }

    private boolean fire(BoundedLoop loop, boolean manual) {
        // cancel(false) cannot retract a scheduler invocation that has already started.
        // Recheck here so an invocation queued before clear/remove cannot begin a callback
        // after the loop has been marked stopped or completed.
        if (loop.getStatus() != BoundedLoop.LoopStatus.ACTIVE) return false;
        // Persist attempt accounting before dispatch; failure does not roll it back.
        if (!preparePersistedFire(loop, manual)) {
            cancelFutures(loop.getId());
            if (loop.getStatus() == BoundedLoop.LoopStatus.STOPPED) {
                discardStaleLoop(loop);
            }
            return false;
        }
        boolean dispatched;
        try {
            fireCallback.accept(loop.getPrompt());
            dispatched = true;
        } catch (RuntimeException failure) {
            // Report failure without retargeting or terminating the loop before its bound.
            if (failureCallback != null) {
                try {
                    failureCallback.accept(loop, failure);
                } catch (RuntimeException ignored) {
                    // A failed reporter must not terminate the loop either.
                }
            }
            dispatched = false;
        }
        // Attempt accounting is bound-exhaustion accounting: the count stops here
        // whether or not the final dispatch succeeded.
        if (loop.getBoundType() == BoundedLoop.BoundType.COUNT
                && loop.getFireCount() >= loop.getBoundCount()) {
            complete(loop, "reached " + loop.getBoundCount() + " fires");
        }
        return dispatched;
    }

    /**
     * Mark a loop completed and report it once. Safe against the tick/guard race:
     * only the first caller past the status check notifies.
     */
    private synchronized void complete(BoundedLoop loop, String reason) {
        if (loop.getStatus() != BoundedLoop.LoopStatus.ACTIVE) return;
        loop.completionReason = reason;
        loop.completedAt = Instant.now();
        loop.setStatus(BoundedLoop.LoopStatus.COMPLETED);
        cancelFutures(loop.getId());
        persistExisting(loop);
        if (completionCallback != null) {
            try {
                completionCallback.accept(loop);
            } catch (RuntimeException ignored) {
                // A failed completion reporter must not break other loops.
            }
        }
    }

    private boolean preparePersistedFire(BoundedLoop loop, boolean manual) {
        if (stateFile == null) {
            loop.recordFire();
            return true;
        }
        try {
            return withStateFileLock(() -> {
                LinkedHashMap<String, ObjectNode> entries = readPersistedEntries();
                ObjectNode persisted = entries.get(loop.getId());
                if (persisted == null) {
                    if (!Files.exists(stateFile)) {
                        loop.recordFire();
                        return true;
                    }
                    loop.setStatus(BoundedLoop.LoopStatus.STOPPED);
                    return false;
                }
                BoundedLoop.LoopStatus persistedStatus = parseStatus(persisted);
                loop.setStatus(persistedStatus);
                if (!manual && persistedStatus != BoundedLoop.LoopStatus.ACTIVE) {
                    return false;
                }
                Instant firedAt = Instant.now();
                loop.recordFire(firedAt, persisted.path("fireCount").asInt(0) + 1);
                entries.put(loop.getId(), serialize(loop));
                writePersistedEntries(entries);
                return true;
            });
        } catch (IOException ignored) {
            // A temporary persistence failure must not terminate an in-memory loop.
            loop.recordFire();
            return true;
        }
    }

    private void persistUpsert(BoundedLoop loop) {
        if (stateFile == null) return;
        try {
            withStateFileLock(() -> {
                LinkedHashMap<String, ObjectNode> entries = readPersistedEntries();
                entries.put(loop.getId(), serialize(loop));
                writePersistedEntries(entries);
                return null;
            });
        } catch (IOException ignored) {
            // Scheduling remains available in memory if local persistence fails.
        }
    }

    /** Update an existing persisted entry without allowing a stale manager to recreate it. */
    private boolean persistExisting(BoundedLoop loop) {
        if (stateFile == null) return true;
        try {
            return withStateFileLock(() -> {
                boolean fileExists = Files.exists(stateFile);
                LinkedHashMap<String, ObjectNode> entries = readPersistedEntries();
                if (fileExists && !entries.containsKey(loop.getId())) return false;
                ObjectNode persisted = entries.get(loop.getId());
                if (persisted != null) mergePersistedFireState(loop, persisted);
                entries.put(loop.getId(), serialize(loop));
                writePersistedEntries(entries);
                return true;
            });
        } catch (IOException ignored) {
            // Preserve the prior in-memory behavior when storage is temporarily unavailable.
            return true;
        }
    }

    private void persistRemove(String id) {
        if (stateFile == null) return;
        try {
            withStateFileLock(() -> {
                LinkedHashMap<String, ObjectNode> entries = readPersistedEntries();
                entries.remove(id);
                writePersistedEntries(entries);
                return null;
            });
        } catch (IOException ignored) {
            // Scheduling remains available in memory if local persistence fails.
        }
    }

    private int persistClear(List<String> localIds) {
        if (stateFile == null) return localIds.size();
        try {
            return withStateFileLock(() -> {
                LinkedHashMap<String, ObjectNode> entries = readPersistedEntries();
                int cleared = entries.size();
                for (String id : localIds) {
                    if (!entries.containsKey(id)) cleared++;
                }
                entries.clear();
                writePersistedEntries(entries);
                return cleared;
            });
        } catch (IOException ignored) {
            // Clear the in-memory scope even if local persistence is temporarily unavailable.
            return localIds.size();
        }
    }

    private void discardStaleLoop(BoundedLoop loop) {
        loop.setStatus(BoundedLoop.LoopStatus.STOPPED);
        cancelFutures(loop.getId());
        loops.remove(loop.getId());
        loopOrder.remove(loop.getId());
    }

    private ObjectNode serialize(BoundedLoop loop) {
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("id", loop.getId())
                .put("interval", loop.getInterval())
                .put("boundType", loop.getBoundType().name())
                .put("boundCount", loop.getBoundCount())
                .put("durationMs", loop.getDurationMs())
                .put("prompt", loop.getPrompt())
                .put("createdAt", loop.getCreatedAt().toString())
                .put("status", loop.getStatus().name());
        if (loop.getDeadline() == null) {
            entry.putNull("deadline");
        } else {
            entry.put("deadline", loop.getDeadline().toString());
        }
        if (loop.getLastFiredAt() == null) {
            entry.putNull("lastFiredAt");
        } else {
            entry.put("lastFiredAt", loop.getLastFiredAt().toString());
        }
        entry.put("fireCount", loop.getFireCount());
        return entry;
    }

    private LinkedHashMap<String, ObjectNode> readPersistedEntries() throws IOException {
        LinkedHashMap<String, ObjectNode> result = new LinkedHashMap<>();
        if (!Files.exists(stateFile)) return result;
        if (Files.isSymbolicLink(stateFile) || !Files.isRegularFile(stateFile)) {
            throw new IOException("Bounded-loop state must be a regular file: " + stateFile);
        }
        JsonNode root = objectMapper.readTree(Files.readString(stateFile));
        if (root == null || !root.isArray()) {
            throw new IOException("Bounded-loop state must contain a JSON array: " + stateFile);
        }
        for (JsonNode value : root) {
            if (!(value instanceof ObjectNode entry)) continue;
            String id = entry.path("id").asText("").strip();
            if (!id.isEmpty()) result.put(id, entry.deepCopy());
        }
        return result;
    }

    private void writePersistedEntries(LinkedHashMap<String, ObjectNode> persisted)
            throws IOException {
        Path parent = stateFile.getParent();
        if (parent == null) throw new IOException("Bounded-loop state has no parent: " + stateFile);
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(stateFile)) {
            throw new IOException("Bounded-loop state must not be a symbolic link: " + stateFile);
        }
        ArrayNode entries = objectMapper.createArrayNode();
        persisted.values().forEach(entries::add);
        Path temp = Files.createTempFile(parent, "." + stateFile.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temp, objectMapper.writeValueAsString(entries));
            try {
                Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private <T> T withStateFileLock(ManagedFileLock.Operation<T> operation) throws IOException {
        Path normalized = stateFile.toAbsolutePath().normalize();
        Object monitor = JVM_STATE_LOCKS.computeIfAbsent(normalized, ignored -> new Object());
        synchronized (monitor) {
            return ManagedFileLock.withLock(normalized, operation);
        }
    }

    private static BoundedLoop.LoopStatus parseStatus(JsonNode entry) {
        try {
            return BoundedLoop.LoopStatus.valueOf(entry.path("status").asText("ACTIVE"));
        } catch (IllegalArgumentException e) {
            return BoundedLoop.LoopStatus.ACTIVE;
        }
    }

    private static void mergePersistedFireState(BoundedLoop loop, JsonNode persisted) {
        loop.recordFire(
                Instant.now(),
                persisted.path("fireCount").asInt(loop.getFireCount()));
    }

    private void loadPersistedLoops() {
        if (stateFile == null || !Files.isRegularFile(stateFile)) return;
        try {
            JsonNode entries = objectMapper.readTree(Files.readString(stateFile));
            if (!entries.isArray()) return;
            for (JsonNode entry : entries) {
                String interval = entry.path("interval").asText("");
                String prompt = entry.path("prompt").asText("");
                if (interval.isBlank() || prompt.isBlank()) continue;
                if (!DURATION_PATTERN.matcher(interval).matches()) continue;
                long intervalMs = ScheduledLoopManager.parseIntervalMs(interval);
                if (intervalMs < minimumIntervalMs) continue;

                BoundedLoop.BoundType boundType;
                try {
                    boundType = BoundedLoop.BoundType.valueOf(entry.path("boundType").asText(""));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                int boundCount = entry.path("boundCount").asInt(-1);
                long durationMs = entry.path("durationMs").asLong(-1);
                Instant deadline = entry.path("deadline").isNull()
                        || entry.path("deadline").asText("").isBlank()
                        ? null : Instant.parse(entry.path("deadline").asText());
                if (boundType == BoundedLoop.BoundType.COUNT && boundCount < 1) continue;
                if (boundType == BoundedLoop.BoundType.DURATION
                        && (durationMs < 1 || deadline == null)) continue;

                BoundedLoop.LoopStatus status = parseStatus(entry);
                if (status == BoundedLoop.LoopStatus.STOPPED
                        || status == BoundedLoop.LoopStatus.COMPLETED) continue;
                // A duration loop whose window elapsed while the chat was closed is done.
                if (boundType == BoundedLoop.BoundType.DURATION
                        && !Instant.now().isBefore(deadline)) continue;

                BoundedLoop loop = new BoundedLoop(
                        entry.path("id").asText(UUID.randomUUID().toString().substring(0, 8)),
                        interval, intervalMs, boundType, boundCount, durationMs, deadline,
                        prompt,
                        Instant.parse(entry.path("createdAt").asText(Instant.now().toString())),
                        status,
                        entry.path("lastFiredAt").isNull()
                                || entry.path("lastFiredAt").asText("").isBlank()
                                ? null : Instant.parse(entry.path("lastFiredAt").asText()),
                        entry.path("fireCount").asInt(0));
                loops.put(loop.getId(), loop);
                loopOrder.add(loop.getId());
                if (status == BoundedLoop.LoopStatus.ACTIVE) {
                    scheduleInterval(loop);
                    scheduleCompletionGuard(loop);
                }
            }
        } catch (Exception ignored) {
            // Ignore malformed local state instead of preventing chat startup.
        }
    }

    // ── Bound parsing and formatting ──────────────────────────────────

    /** True when the bound string is a bare fire count ("10"). */
    static boolean isCountBound(String bound) {
        return bound != null && COUNT_PATTERN.matcher(bound.trim()).matches();
    }

    /** Format milliseconds the way the CLI renders intervals: "90s", "5m", "2h30m". */
    static String formatDurationMs(long durationMs) {
        long sec = durationMs / 1000;
        if (sec < 60) return sec + "s";
        if (sec < 3600) return (sec / 60) + "m" + (sec % 60 > 0 ? " " + (sec % 60) + "s" : "");
        String result = (sec / 3600) + "h";
        long minutes = (sec % 3600) / 60;
        if (minutes > 0) result += " " + minutes + "m";
        return result;
    }

    /**
     * Format a bounded loop as a summary line, including its bound and remaining budget.
     */
    public static String formatBoundedLoop(BoundedLoop loop) {
        StringBuilder sb = new StringBuilder();
        sb.append(loop.getStatusIcon())
                .append(" [").append(loop.getId()).append("] ")
                .append(loop.getFormattedInterval())
                .append(' ')
                .append(loop.getFormattedBound())
                .append("  ");

        String prompt = loop.getPrompt();
        if (prompt.length() > 60) prompt = prompt.substring(0, 57) + "...";
        sb.append(prompt);

        sb.append("  (fired ").append(loop.getFireCount());
        if (loop.getBoundType() == BoundedLoop.BoundType.COUNT) {
            sb.append('/').append(loop.getBoundCount());
        }
        if (loop.getLastFiredAt() != null) {
            long ago = Duration.between(loop.getLastFiredAt(), Instant.now()).getSeconds();
            if (ago < 60) sb.append(", ").append(ago).append("s ago");
            else if (ago < 3600) sb.append(", ").append(ago / 60).append("m ago");
            else sb.append(", ").append(ago / 3600).append("h ago");
        }
        sb.append(")");
        if (loop.getStatus() == BoundedLoop.LoopStatus.COMPLETED
                && loop.getCompletionReason() != null) {
            sb.append(" — completed: ").append(loop.getCompletionReason());
        }
        return sb.toString();
    }
}
