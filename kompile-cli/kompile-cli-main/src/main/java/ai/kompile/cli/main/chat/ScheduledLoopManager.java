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
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
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
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages one scope of scheduled recurring tasks (loops) for a chat REPL.
 * <p>
 * Supports two scheduling modes:
 * <ul>
 *   <li><b>Interval</b>: Fixed-rate repetition (e.g., "30s", "5m", "2h")</li>
 *   <li><b>Cron</b>: Standard 5-field cron expressions (minute hour dom month dow)</li>
 * </ul>
 * <p>
 * When a loop fires, the configured prompt or slash command is delivered
 * to the chat REPL via the {@code fireCallback} consumer.
 */
public class ScheduledLoopManager {

    /**
     * A single scheduled loop entry.
     */
    public static class ScheduledLoop {
        private final String id;
        private final String schedule;       // original schedule string ("5m" or "*/5 * * * *")
        private final String prompt;         // message or /command to fire
        private final Instant createdAt;
        private final boolean isCron;
        private final long intervalMs;       // only for interval-based loops
        private final int[] cronFields;      // only for cron-based loops (5 fields)
        private volatile LoopStatus status;
        private volatile Instant lastFiredAt;
        private volatile int fireCount;

        public enum LoopStatus {
            ACTIVE, PAUSED, STOPPED
        }

        ScheduledLoop(String schedule, String prompt, boolean isCron,
                      long intervalMs, int[] cronFields) {
            this(UUID.randomUUID().toString().substring(0, 8), schedule, prompt,
                    Instant.now(), isCron, intervalMs, cronFields,
                    LoopStatus.ACTIVE, null, 0);
        }

        private ScheduledLoop(
                String id, String schedule, String prompt, Instant createdAt,
                boolean isCron, long intervalMs, int[] cronFields,
                LoopStatus status, Instant lastFiredAt, int fireCount) {
            this.id = id;
            this.schedule = schedule;
            this.prompt = prompt;
            this.createdAt = createdAt;
            this.isCron = isCron;
            this.intervalMs = intervalMs;
            this.cronFields = cronFields;
            this.status = status;
            this.lastFiredAt = lastFiredAt;
            this.fireCount = fireCount;
        }

        public String getId() { return id; }
        public String getSchedule() { return schedule; }
        public String getPrompt() { return prompt; }
        public Instant getCreatedAt() { return createdAt; }
        public boolean isCron() { return isCron; }
        public long getIntervalMs() { return intervalMs; }
        public LoopStatus getStatus() { return status; }
        public void setStatus(LoopStatus status) { this.status = status; }
        /** Time of the last dispatch attempt, including failed dispatches. */
        public Instant getLastFiredAt() { return lastFiredAt; }
        /** Number of dispatch attempts, not successful deliveries. */
        public int getFireCount() { return fireCount; }

        void recordFire() {
            recordFire(Instant.now(), fireCount + 1);
        }

        void recordFire(Instant firedAt, int persistedFireCount) {
            this.lastFiredAt = firedAt;
            this.fireCount = persistedFireCount;
        }

        public String getElapsedSinceCreation() {
            long seconds = Duration.between(createdAt, Instant.now()).getSeconds();
            if (seconds < 60) return seconds + "s";
            if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
            return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
        }

        public String getFormattedInterval() {
            if (isCron) return "cron: " + schedule;
            long sec = intervalMs / 1000;
            if (sec < 60) return sec + "s";
            if (sec < 3600) return (sec / 60) + "m";
            return (sec / 3600) + "h" + ((sec % 3600) / 60 > 0 ? " " + ((sec % 3600) / 60) + "m" : "");
        }

        public String getStatusIcon() {
            return switch (status) {
                case ACTIVE -> "▶";
                case PAUSED -> "⏸";
                case STOPPED -> "■";
            };
        }
    }

    // Interval pattern: digits + unit (s/m/h), optionally repeated (e.g., "2h30m")
    private static final Pattern INTERVAL_PATTERN = Pattern.compile(
            "^(\\d+[smh])+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTERVAL_PART = Pattern.compile(
            "(\\d+)([smh])", Pattern.CASE_INSENSITIVE);

    // Cron: 5 space-separated fields
    private static final Pattern CRON_PATTERN = Pattern.compile(
            "^([\\d*/,-]+)\\s+([\\d*/,-]+)\\s+([\\d*/,-]+)\\s+([\\d*/,-]+)\\s+([\\d*/,-]+)$");
    private static final ConcurrentMap<Path, Object> JVM_STATE_LOCKS = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledLoop> loops = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private final List<String> loopOrder = new CopyOnWriteArrayList<>();
    private final Consumer<String> fireCallback;
    private final BiConsumer<ScheduledLoop, RuntimeException> failureCallback;
    private final Path stateFile;
    private final long minimumIntervalMs;
    private final ObjectMapper objectMapper = JsonUtils.standardMapper();

    /**
     * @param fireCallback invoked (on the scheduler thread) when a loop fires.
     *                     Receives the prompt/command string to inject into the chat.
     */
    public ScheduledLoopManager(Consumer<String> fireCallback) {
        this(fireCallback, null, 5000);
    }

    public ScheduledLoopManager(Consumer<String> fireCallback, Path stateFile) {
        this(fireCallback, stateFile, 5000);
    }

    /**
     * @param fireCallback dispatches to the host's fixed target; throw a RuntimeException
     *                     if dispatch is rejected (for example, the target session closed)
     * @param stateFile optional persistence path; null keeps state in memory
     * @param failureCallback optional failure reporter receiving the loop and original exception
     *                        on the firing thread (scheduler, or caller of runNow). This reports
     *                        failure only; it must not retry against a different session.
     *                        RuntimeExceptions from the reporter are contained to keep schedules alive.
     */
    public ScheduledLoopManager(
            Consumer<String> fireCallback, Path stateFile,
            BiConsumer<ScheduledLoop, RuntimeException> failureCallback) {
        this(fireCallback, stateFile, 5000, failureCallback);
    }

    ScheduledLoopManager(
            Consumer<String> fireCallback, Path stateFile, long minimumIntervalMs) {
        this(fireCallback, stateFile, minimumIntervalMs, null);
    }

    ScheduledLoopManager(
            Consumer<String> fireCallback, Path stateFile, long minimumIntervalMs,
            BiConsumer<ScheduledLoop, RuntimeException> failureCallback) {
        this.fireCallback = fireCallback;
        this.failureCallback = failureCallback;
        this.stateFile = stateFile;
        this.minimumIntervalMs = Math.max(1, minimumIntervalMs);
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "loop-scheduler");
            t.setDaemon(true);
            return t;
        });
        loadPersistedLoops();
    }

    /** Stable local state path scoped to one project directory. */
    public static Path stateFileForProject(Path workingDirectory) {
        Path normalized = workingDirectory.toAbsolutePath().normalize();
        String projectId = UUID.nameUUIDFromBytes(
                normalized.toString().getBytes(StandardCharsets.UTF_8)).toString();
        return KompileHome.homeDirectory().toPath()
                .resolve("scheduled-loops").resolve(projectId + ".json");
    }

    /** Stable local state path scoped to one conversation, including across resume. */
    public static Path stateFileForSession(String sessionId) {
        String safeId = sessionId == null || sessionId.isBlank()
                ? "unknown-session"
                : sessionId.replaceAll("[^A-Za-z0-9._-]", "_");
        return KompileHome.homeDirectory().toPath()
                .resolve("conversations")
                .resolve(safeId + ".loops.json")
                .toAbsolutePath().normalize();
    }

    /**
     * Create a new scheduled loop.
     *
     * @param scheduleStr interval ("30s", "5m", "1h") or cron expression ("*&#47;5 * * * *")
     * @param prompt      the message or /command to fire on each tick
     * @return the created loop, or null if the schedule string is invalid
     */
    public synchronized ScheduledLoop create(String scheduleStr, String prompt) {
        if (scheduleStr == null || prompt == null || prompt.isBlank()) return null;
        scheduleStr = scheduleStr.trim();
        prompt = prompt.trim();

        if (INTERVAL_PATTERN.matcher(scheduleStr).matches()) {
            long ms = parseIntervalMs(scheduleStr);
            if (ms < minimumIntervalMs) return null;
            ScheduledLoop loop = new ScheduledLoop(scheduleStr, prompt, false, ms, null);
            loops.put(loop.getId(), loop);
            loopOrder.add(loop.getId());
            persistUpsert(loop);
            scheduleInterval(loop);
            return loop;
        }

        Matcher cronMatcher = CRON_PATTERN.matcher(scheduleStr);
        if (cronMatcher.matches()) {
            int[] fields = parseCronFields(scheduleStr);
            if (fields == null) return null;
            ScheduledLoop loop = new ScheduledLoop(scheduleStr, prompt, true, 0, fields);
            loops.put(loop.getId(), loop);
            loopOrder.add(loop.getId());
            persistUpsert(loop);
            scheduleCron(loop);
            return loop;
        }

        return null; // unrecognized format
    }

    /**
     * Pause an active loop.
     */
    public synchronized boolean pause(String id) {
        ScheduledLoop loop = get(id);
        if (loop == null || loop.getStatus() != ScheduledLoop.LoopStatus.ACTIVE) return false;
        loop.setStatus(ScheduledLoop.LoopStatus.PAUSED);
        if (!persistExisting(loop)) {
            discardStaleLoop(loop);
            return false;
        }
        cancelFuture(loop.getId());
        return true;
    }

    /**
     * Resume a paused loop.
     */
    public synchronized boolean resume(String id) {
        ScheduledLoop loop = get(id);
        if (loop == null || loop.getStatus() != ScheduledLoop.LoopStatus.PAUSED) return false;
        loop.setStatus(ScheduledLoop.LoopStatus.ACTIVE);
        if (!persistExisting(loop)) {
            discardStaleLoop(loop);
            return false;
        }
        if (loop.isCron()) {
            scheduleCron(loop);
        } else {
            scheduleInterval(loop);
        }
        return true;
    }

    /**
     * Stop and remove a loop permanently.
     */
    public synchronized boolean remove(String id) {
        ScheduledLoop loop = get(id);
        if (loop == null) return false;
        loop.setStatus(ScheduledLoop.LoopStatus.STOPPED);
        cancelFuture(loop.getId());
        loops.remove(loop.getId());
        loopOrder.remove(loop.getId());
        persistRemove(loop.getId());
        return true;
    }

    /**
     * Stop and remove every loop in this manager's scope.
     *
     * @return the number of distinct in-memory or persisted loops removed
     */
    public synchronized int clear() {
        List<String> localIds = new ArrayList<>(loops.keySet());
        for (String id : localIds) {
            ScheduledLoop loop = loops.get(id);
            if (loop != null) loop.setStatus(ScheduledLoop.LoopStatus.STOPPED);
            cancelFuture(id);
        }

        int cleared = persistClear(localIds);
        for (String id : localIds) {
            ScheduledLoop loop = loops.remove(id);
            if (loop != null) loop.setStatus(ScheduledLoop.LoopStatus.STOPPED);
            cancelFuture(id);
        }
        loopOrder.removeAll(localIds);
        return cleared;
    }

    /**
     * Get a specific loop by ID (prefix match supported).
     */
    public ScheduledLoop get(String idPrefix) {
        // Exact match first
        ScheduledLoop exact = loops.get(idPrefix);
        if (exact != null) return exact;
        // Prefix match
        for (Map.Entry<String, ScheduledLoop> e : loops.entrySet()) {
            if (e.getKey().startsWith(idPrefix)) return e.getValue();
        }
        return null;
    }

    /**
     * List all loops in creation order.
     */
    public List<ScheduledLoop> list() {
        List<ScheduledLoop> result = new ArrayList<>();
        for (String id : loopOrder) {
            ScheduledLoop loop = loops.get(id);
            if (loop != null) result.add(loop);
        }
        return result;
    }

    /**
     * Number of active (non-stopped) loops.
     */
    public int activeCount() {
        int count = 0;
        for (ScheduledLoop loop : loops.values()) {
            if (loop.getStatus() == ScheduledLoop.LoopStatus.ACTIVE) count++;
        }
        return count;
    }

    /**
     * Fire one loop immediately without changing its recurring schedule.
     * @return true only if the dispatch callback returns normally; false if the loop
     *         cannot fire or dispatch fails. A failed dispatch still counts as an attempt.
     */
    public boolean runNow(String idPrefix) {
        ScheduledLoop loop = get(idPrefix);
        if (loop == null || loop.getStatus() == ScheduledLoop.LoopStatus.STOPPED) return false;
        return fire(loop, true);
    }

    /**
     * Shutdown the scheduler and stop all loops. Call on REPL exit.
     */
    public void shutdown() {
        for (String id : new ArrayList<>(loops.keySet())) cancelFuture(id);
        scheduler.shutdownNow();
    }

    // ── Scheduling internals ──────────────────────────────────────────

    private void scheduleInterval(ScheduledLoop loop) {
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            if (loop.getStatus() == ScheduledLoop.LoopStatus.ACTIVE) {
                fire(loop, false);
            }
        }, loop.getIntervalMs(), loop.getIntervalMs(), TimeUnit.MILLISECONDS);
        futures.put(loop.getId(), future);
    }

    private void scheduleCron(ScheduledLoop loop) {
        scheduleNextCronFire(loop);
    }

    private void scheduleNextCronFire(ScheduledLoop loop) {
        if (loop.getStatus() != ScheduledLoop.LoopStatus.ACTIVE) return;

        long delayMs = computeNextCronDelayMs(loop.cronFields);
        if (delayMs < 0) {
            // Can't compute next fire — stop
            loop.setStatus(ScheduledLoop.LoopStatus.STOPPED);
            return;
        }

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            if (loop.getStatus() == ScheduledLoop.LoopStatus.ACTIVE) {
                fire(loop, false);
            }
            // Reschedule for next cron match
            scheduleNextCronFire(loop);
        }, delayMs, TimeUnit.MILLISECONDS);
        futures.put(loop.getId(), future);
    }

    private void cancelFuture(String id) {
        ScheduledFuture<?> f = futures.remove(id);
        if (f != null) f.cancel(false);
    }

    private boolean fire(ScheduledLoop loop, boolean manual) {
        // cancel(false) cannot retract a scheduler invocation that has already started.
        // Recheck here so an invocation queued before clear/remove cannot begin a callback
        // after the loop has been marked stopped.
        if (loop.getStatus() == ScheduledLoop.LoopStatus.STOPPED) return false;
        // Persist attempt accounting before dispatch; failure does not roll it back.
        if (!preparePersistedFire(loop, manual)) {
            cancelFuture(loop.getId());
            if (loop.getStatus() == ScheduledLoop.LoopStatus.STOPPED) {
                discardStaleLoop(loop);
            }
            return false;
        }
        try {
            fireCallback.accept(loop.getPrompt());
        } catch (RuntimeException failure) {
            // Report failure without retargeting or terminating the recurring schedule.
            if (failureCallback != null) {
                try {
                    failureCallback.accept(loop, failure);
                } catch (RuntimeException ignored) {
                    // A failed reporter must not terminate the recurring schedule either.
                }
            }
            return false;
        }
        return true;
    }

    private boolean preparePersistedFire(ScheduledLoop loop, boolean manual) {
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
                    loop.setStatus(ScheduledLoop.LoopStatus.STOPPED);
                    return false;
                }
                ScheduledLoop.LoopStatus persistedStatus = parseStatus(persisted);
                loop.setStatus(persistedStatus);
                if (!manual && persistedStatus != ScheduledLoop.LoopStatus.ACTIVE) {
                    return false;
                }
                Instant firedAt = Instant.now();
                loop.recordFire(firedAt, persisted.path("fireCount").asInt(0) + 1);
                entries.put(loop.getId(), serialize(loop));
                writePersistedEntries(entries);
                return true;
            });
        } catch (IOException ignored) {
            // A temporary persistence failure must not terminate an in-memory schedule.
            loop.recordFire();
            return true;
        }
    }

    private void persistUpsert(ScheduledLoop loop) {
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
    private boolean persistExisting(ScheduledLoop loop) {
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

    private void discardStaleLoop(ScheduledLoop loop) {
        loop.setStatus(ScheduledLoop.LoopStatus.STOPPED);
        cancelFuture(loop.getId());
        loops.remove(loop.getId(), loop);
        loopOrder.remove(loop.getId());
    }

    private static void mergePersistedFireState(ScheduledLoop loop, JsonNode persisted) {
        int fireCount = Math.max(loop.getFireCount(), persisted.path("fireCount").asInt(0));
        Instant persistedLast = parseInstant(persisted.path("lastFiredAt").asText(""));
        Instant localLast = loop.getLastFiredAt();
        Instant lastFiredAt = localLast == null || persistedLast != null
                && persistedLast.isAfter(localLast) ? persistedLast : localLast;
        loop.recordFire(lastFiredAt, fireCount);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            return null;
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

    private ObjectNode serialize(ScheduledLoop loop) {
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("id", loop.getId())
                .put("schedule", loop.getSchedule())
                .put("prompt", loop.getPrompt())
                .put("createdAt", loop.getCreatedAt().toString())
                .put("status", loop.getStatus().name());
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
            throw new IOException("Scheduled-loop state must be a regular file: " + stateFile);
        }
        JsonNode root = objectMapper.readTree(Files.readString(stateFile));
        if (root == null || !root.isArray()) {
            throw new IOException("Scheduled-loop state must contain a JSON array: " + stateFile);
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
        if (parent == null) throw new IOException("Scheduled-loop state has no parent: " + stateFile);
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(stateFile)) {
            throw new IOException("Scheduled-loop state must not be a symbolic link: " + stateFile);
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

    private static ScheduledLoop.LoopStatus parseStatus(JsonNode entry) {
        try {
            return ScheduledLoop.LoopStatus.valueOf(entry.path("status").asText("ACTIVE"));
        } catch (IllegalArgumentException e) {
            return ScheduledLoop.LoopStatus.ACTIVE;
        }
    }

    private void loadPersistedLoops() {
        if (stateFile == null || !Files.isRegularFile(stateFile)) return;
        try {
            JsonNode entries = objectMapper.readTree(Files.readString(stateFile));
            if (!entries.isArray()) return;
            for (JsonNode entry : entries) {
                String schedule = entry.path("schedule").asText("");
                String prompt = entry.path("prompt").asText("");
                if (schedule.isBlank() || prompt.isBlank()) continue;
                boolean cron = CRON_PATTERN.matcher(schedule).matches();
                long intervalMs = cron ? 0 : parseIntervalMs(schedule);
                int[] cronFields = cron ? parseCronFields(schedule) : null;
                if ((!cron && intervalMs < minimumIntervalMs) || (cron && cronFields == null)) continue;
                ScheduledLoop.LoopStatus status = parseStatus(entry);
                if (status == ScheduledLoop.LoopStatus.STOPPED) continue;
                ScheduledLoop loop = new ScheduledLoop(
                        entry.path("id").asText(UUID.randomUUID().toString().substring(0, 8)),
                        schedule, prompt,
                        Instant.parse(entry.path("createdAt").asText(Instant.now().toString())),
                        cron, intervalMs, cronFields, status,
                        entry.path("lastFiredAt").isNull()
                                || entry.path("lastFiredAt").asText("").isBlank()
                                ? null : Instant.parse(entry.path("lastFiredAt").asText()),
                        entry.path("fireCount").asInt(0));
                loops.put(loop.getId(), loop);
                loopOrder.add(loop.getId());
                if (status == ScheduledLoop.LoopStatus.ACTIVE) {
                    if (cron) scheduleCron(loop); else scheduleInterval(loop);
                }
            }
        } catch (Exception ignored) {
            // Ignore malformed local state instead of preventing chat startup.
        }
    }

    // ── Interval parsing ──────────────────────────────────────────────

    static long parseIntervalMs(String interval) {
        long totalMs = 0;
        Matcher m = INTERVAL_PART.matcher(interval.toLowerCase());
        while (m.find()) {
            long value = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "s" -> totalMs += value * 1000;
                case "m" -> totalMs += value * 60_000;
                case "h" -> totalMs += value * 3_600_000;
            }
        }
        return totalMs;
    }

    // ── Cron parsing and next-fire computation ────────────────────────

    /**
     * Parse a 5-field cron expression into an internal representation.
     * Fields: minute(0-59) hour(0-23) day-of-month(1-31) month(1-12) day-of-week(0-6, 0=Sunday)
     * <p>
     * Returns a flat array encoding each field's allowed values as a bitmask-style
     * boolean array concatenated together:
     * [60 minute bits | 24 hour bits | 31 dom bits | 12 month bits | 7 dow bits] = 134 entries
     */
    static int[] parseCronFields(String expr) {
        String[] parts = expr.trim().split("\\s+");
        if (parts.length != 5) return null;

        // field sizes: minute=60, hour=24, dom=31, month=12, dow=7
        int[] sizes = {60, 24, 31, 12, 7};
        int[] offsets = {0, 1, 1, 1, 0}; // min value for each field
        int totalSize = 60 + 24 + 31 + 12 + 7; // 134
        int[] result = new int[totalSize];

        int pos = 0;
        for (int f = 0; f < 5; f++) {
            boolean[] allowed = parseCronField(parts[f], sizes[f], offsets[f]);
            if (allowed == null) return null;
            for (int i = 0; i < sizes[f]; i++) {
                result[pos + i] = allowed[i] ? 1 : 0;
            }
            pos += sizes[f];
        }
        return result;
    }

    /**
     * Parse a single cron field (supports *, *&#47;N, N, N-M, comma-separated).
     */
    private static boolean[] parseCronField(String field, int size, int minVal) {
        boolean[] allowed = new boolean[size];

        for (String part : field.split(",")) {
            part = part.trim();
            if (part.equals("*")) {
                Arrays.fill(allowed, true);
            } else if (part.startsWith("*/")) {
                int step;
                try { step = Integer.parseInt(part.substring(2)); } catch (NumberFormatException e) { return null; }
                if (step <= 0) return null;
                for (int i = 0; i < size; i++) {
                    if (i % step == 0) allowed[i] = true;
                }
            } else if (part.contains("-")) {
                String[] range = part.split("-", 2);
                int from, to;
                try {
                    from = Integer.parseInt(range[0]) - minVal;
                    to = Integer.parseInt(range[1]) - minVal;
                } catch (NumberFormatException e) { return null; }
                if (from < 0 || to >= size || from > to) return null;
                for (int i = from; i <= to; i++) allowed[i] = true;
            } else {
                int val;
                try { val = Integer.parseInt(part) - minVal; } catch (NumberFormatException e) { return null; }
                if (val < 0 || val >= size) return null;
                allowed[val] = true;
            }
        }
        return allowed;
    }

    /**
     * Compute milliseconds until the next matching cron minute.
     * Scans up to 366 days ahead (covers all month/dow combinations).
     */
    static long computeNextCronDelayMs(int[] cronFields) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        // Start from the next whole minute
        ZonedDateTime candidate = now.plusMinutes(1).withSecond(0).withNano(0);

        // Scan up to 366*24*60 minutes (one full year)
        int maxMinutes = 366 * 24 * 60;
        for (int i = 0; i < maxMinutes; i++) {
            if (cronMatches(cronFields, candidate)) {
                long delayMs = Duration.between(now, candidate).toMillis();
                return Math.max(delayMs, 1000); // at least 1 second
            }
            candidate = candidate.plusMinutes(1);
        }
        return -1; // no match found within a year
    }

    private static boolean cronMatches(int[] fields, ZonedDateTime dt) {
        int minute = dt.getMinute();
        int hour = dt.getHour();
        int dom = dt.getDayOfMonth() - 1;   // 0-based index into 31-slot array
        int month = dt.getMonthValue() - 1;  // 0-based index into 12-slot array
        int dow = dt.getDayOfWeek().getValue() % 7; // Monday=1..Sunday=7 → 0=Sunday

        // Offsets into the flat array: minute[0..59], hour[60..83], dom[84..114], month[115..126], dow[127..133]
        return fields[minute] == 1
                && fields[60 + hour] == 1
                && fields[84 + dom] == 1
                && fields[115 + month] == 1
                && fields[127 + dow] == 1;
    }

    /**
     * Format a scheduled loop as a summary line.
     */
    public static String formatLoop(ScheduledLoop loop) {
        StringBuilder sb = new StringBuilder();
        sb.append(loop.getStatusIcon())
                .append(" [").append(loop.getId()).append("] ")
                .append(loop.getFormattedInterval())
                .append("  ");

        String prompt = loop.getPrompt();
        if (prompt.length() > 60) prompt = prompt.substring(0, 57) + "...";
        sb.append(prompt);

        sb.append("  (fired ").append(loop.getFireCount()).append("x");
        if (loop.getLastFiredAt() != null) {
            long ago = Duration.between(loop.getLastFiredAt(), Instant.now()).getSeconds();
            if (ago < 60) sb.append(", ").append(ago).append("s ago");
            else if (ago < 3600) sb.append(", ").append(ago / 60).append("m ago");
            else sb.append(", ").append(ago / 3600).append("h ago");
        }
        sb.append(")");
        return sb.toString();
    }
}
