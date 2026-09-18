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

import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import ai.kompile.cli.main.coordination.ProcessCoordEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mirrors processes owned by OTHER sessions — published to shared coordination
 * state by, for example, the MCP {@code process} tool running in its own JVM —
 * into this chat session's {@link BackgroundProcessManager} so the activity
 * panel and process browser show the real process trail with live log tails.
 *
 * <p>The mirror is a passive reader: it polls {@link
 * CoordinationStateManager#snapshotProcesses()}, upserts each entry as a
 * {@code SHARED} mirror, prunes mirrors whose coordination entries vanished,
 * and fires the manager's change listeners only when something actually
 * changed (new row, state change, or output-file size movement). Coordination
 * files are never written and owner log files are never deleted.</p>
 */
public final class SharedProcessMirror implements AutoCloseable {

    /** How often coordination state is re-read. */
    static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(1);

    private final BackgroundProcessManager processes;
    private final CoordinationStateManager coordinator;
    private final String localSessionId;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean refreshQueued = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** Poll sequencing: a slow poll must not overlap the next scheduled one. */
    private final AtomicBoolean pollInFlight = new AtomicBoolean(false);
    private volatile String pollFailure = "";

    /**
     * @param processes       the session-local manager that backs the activity panel
     * @param coordinator     coordination state shared with other CLI/MCP sessions
     * @param localSessionId  this session's id; its own publishes are skipped so a
     *                        locally launched process is never rendered twice
     */
    public SharedProcessMirror(BackgroundProcessManager processes,
                               CoordinationStateManager coordinator,
                               String localSessionId) {
        this(processes, coordinator, localSessionId, DEFAULT_INTERVAL);
    }

    SharedProcessMirror(BackgroundProcessManager processes,
                        CoordinationStateManager coordinator,
                        String localSessionId,
                        Duration interval) {
        this.processes = processes;
        this.coordinator = coordinator;
        this.localSessionId = localSessionId;
        this.interval = interval == null || interval.isNegative() || interval.isZero()
                ? DEFAULT_INTERVAL : interval;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "shared-process-mirror");
            t.setDaemon(true);
            return t;
        });
    }

    /** Start periodic mirroring. Safe to call once; repeated calls are ignored. */
    public synchronized void start() {
        if (closed.get()) return;
        long millis = Math.max(50L, interval.toMillis());
        scheduler.scheduleWithFixedDelay(this::pollGuarded, millis, millis, TimeUnit.MILLISECONDS);
    }

    /** Run one mirror pass immediately (also used by tests). */
    public void pollOnce() {
        pollGuarded();
    }

    private void pollGuarded() {
        if (closed.get() || !pollInFlight.compareAndSet(false, true)) return;
        try {
            refreshQueued.set(false);
            poll();
            pollFailure = "";
        } catch (RuntimeException failure) {
            // Mirroring is best-effort observability; never break the host session,
            // but record why so a silent mirror is still diagnosable.
            pollFailure = failure.getClass().getSimpleName()
                    + (failure.getMessage() != null ? ": " + failure.getMessage() : "");
        } finally {
            pollInFlight.set(false);
        }
    }

    /** Why the last poll failed, or empty when the last poll succeeded. */
    public String pollFailure() {
        return pollFailure;
    }

    private void poll() {
        List<ProcessCoordEntry> entries = coordinator.snapshotProcesses();
        if (entries == null) {
            // Transient coordination failure: keep the previous mirror state and
            // skip pruning so a lost lock race is never mistaken for eviction.
            pollFailure = "coordination snapshot unavailable";
            return;
        }
        Map<String, String> kept = new HashMap<>();
        for (ProcessCoordEntry entry : entries) {
            if (entry == null || entry.getProcessId() == null || entry.getProcessId().isBlank()) {
                continue;
            }
            if (entry.getSessionId() != null && entry.getSessionId().equals(localSessionId)) {
                // This session owns the entry and renders it through its own
                // manager already; mirroring it would draw the row twice.
                continue;
            }
            String key = mirrorKey(entry);
            kept.put(key, entry.getProcessId());
            processes.upsertShared(
                    key,
                    entry.getCommand(),
                    describe(entry),
                    entry.getPid(),
                    entry.getStartedAt(),
                    mirrorState(entry),
                    entry.getExitCode(),
                    entry.getEndedAt(),
                    ownerOutputFile(entry),
                    metadata(entry));
        }
        processes.pruneShared(kept.keySet());
    }

    /** Stable per-owner mirror id: coordination ids can collide across sessions. */
    private static String mirrorKey(ProcessCoordEntry entry) {
        String owner = entry.getSessionId() == null || entry.getSessionId().isBlank()
                ? "unknown" : entry.getSessionId();
        return "shared-" + owner + "-" + entry.getProcessId();
    }

    private String describe(ProcessCoordEntry entry) {
        String label = entry.getDescription();
        if (label == null || label.isBlank()) {
            label = entry.getCommand();
        }
        if (label == null || label.isBlank()) {
            label = "shared process";
        }
        String owner = entry.getAgentName() != null && !entry.getAgentName().isBlank()
                ? entry.getAgentName() : entry.getSessionId();
        return owner == null || owner.isBlank() ? label : label + " · " + truncate(owner, 24);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static BackgroundProcessManager.ProcessState mirrorState(ProcessCoordEntry entry) {
        if (entry.isTerminalState()) {
            String normalized = entry.getState() == null ? "" : entry.getState().trim();
            return switch (normalized.toUpperCase(Locale.ROOT)) {
                case "COMPLETED" -> BackgroundProcessManager.ProcessState.COMPLETED;
                case "FAILED" -> BackgroundProcessManager.ProcessState.FAILED;
                case "KILLED", "LOST" -> BackgroundProcessManager.ProcessState.KILLED;
                default -> BackgroundProcessManager.ProcessState.FAILED;
            };
        }
        return BackgroundProcessManager.ProcessState.RUNNING;
    }

    private static Map<String, String> metadata(ProcessCoordEntry entry) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("ownerSessionId", safe(entry.getSessionId()));
        metadata.put("sharedProcessId", safe(entry.getProcessId()));
        if (entry.getAgentName() != null && !entry.getAgentName().isBlank()) {
            metadata.put("ownerAgent", entry.getAgentName());
        }
        if (entry.getRoleName() != null && !entry.getRoleName().isBlank()) {
            metadata.put("ownerRole", entry.getRoleName());
        }
        if (entry.getKind() != null && !entry.getKind().isBlank()) {
            metadata.put("kind", entry.getKind());
        }
        if (entry.getResourceClass() != null && !entry.getResourceClass().isBlank()) {
            metadata.put("resourceClass", entry.getResourceClass());
        }
        metadata.put("source", "coordination");
        return Map.copyOf(metadata);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** Resolve the owner's durable log path; missing/unreadable paths are skipped. */
    private static Path ownerOutputFile(ProcessCoordEntry entry) {
        String raw = entry.getOutputFile();
        if (raw == null || raw.isBlank()) return null;
        try {
            Path path = Paths.get(raw);
            return Files.isRegularFile(path) ? path : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** Total output-file bytes across current mirrors (diagnostics/tests). */
    public long mirroredOutputBytes() throws IOException {
        long total = 0L;
        List<BackgroundProcessManager.ProcessEntry> mirrored = new ArrayList<>();
        for (BackgroundProcessManager.ProcessEntry entry : processes.listAll()) {
            if (entry.getKind() == BackgroundProcessManager.ProcessKind.SHARED
                    && entry.getOutputFile() != null) {
                mirrored.add(entry);
            }
        }
        for (BackgroundProcessManager.ProcessEntry entry : mirrored) {
            total += Files.size(entry.getOutputFile());
        }
        return total;
    }

    /** IDs currently mirrored (diagnostics/tests). */
    public Set<String> mirroredIds() {
        return processes.listAll().stream()
                .filter(entry -> entry.getKind() == BackgroundProcessManager.ProcessKind.SHARED)
                .map(BackgroundProcessManager.ProcessEntry::getId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        scheduler.shutdownNow();
    }
}
