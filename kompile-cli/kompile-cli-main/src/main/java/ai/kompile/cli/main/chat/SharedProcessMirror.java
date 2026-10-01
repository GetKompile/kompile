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
 *
 * <p>A process that another session launched on this session's behalf (its owner
 * registered this session as its parent) and holds a completion monitor on wakes
 * the {@linkplain #setMonitorListener monitor listener} once when it ends, as a
 * local monitor would. While such a process runs, {@link #owesWake()} is true, so
 * a host that closes when idle can stay open for the wake-up.</p>
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

    /** Poll sequencing: passes run one at a time, a scheduled one and {@link #pollOnce()} included. */
    private final Object pollLock = new Object();
    private volatile String pollFailure = "";

    private volatile BackgroundProcessManager.MonitorCallback monitorListener;
    /** Wake-up bookkeeping per mirror key; only a poll touches it, and polls never overlap. */
    private final Map<String, WakeState> wakes = new HashMap<>();
    /** False until the first poll, so a process already over when the chat starts never wakes it. */
    private boolean seeded;
    /** Monitored processes launched for this session that were running at the last pass. */
    private volatile int owedWakes;

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

    /**
     * Run one mirror pass now, after any pass already under way, so it reads
     * coordination state as it stands at the call (also used by tests).
     */
    public void pollOnce() {
        pollGuarded();
    }

    private void pollGuarded() {
        synchronized (pollLock) {
            if (closed.get()) return;
            try {
                refreshQueued.set(false);
                poll();
                pollFailure = "";
            } catch (RuntimeException failure) {
                // Mirroring is best-effort observability; never break the host session,
                // but record why so a silent mirror is still diagnosable.
                pollFailure = failure.getClass().getSimpleName()
                        + (failure.getMessage() != null ? ": " + failure.getMessage() : "");
            }
        }
    }

    /** Why the last poll failed, or empty when the last poll succeeded. */
    public String pollFailure() {
        return pollFailure;
    }

    /**
     * Receives the mirror row and a monitor carrying the owner's process id and
     * wake-up message when a monitored process launched on this session's behalf
     * ends. It runs on the poll thread.
     */
    public void setMonitorListener(BackgroundProcessManager.MonitorCallback listener) {
        this.monitorListener = listener;
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
        List<Wake> due = new ArrayList<>();
        int owed = 0;
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
            BackgroundProcessManager.ProcessEntry mirrored = processes.upsertShared(
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
            if (mirrored != null && dueForWake(key, entry)) {
                due.add(new Wake(mirrored, new BackgroundProcessManager.ProcessMonitor(
                        entry.getProcessId(), entry.getMonitorMessage(), Instant.now())));
            } else if (mirrored != null && awaitsWake(key, entry)) {
                owed++;
            }
        }
        processes.pruneShared(kept.keySet());
        wakes.keySet().retainAll(kept.keySet());
        seeded = true;
        BackgroundProcessManager.MonitorCallback listener = monitorListener;
        if (listener != null) {
            for (Wake wake : due) {
                try {
                    listener.onMonitoredProcessExit(wake.entry(), wake.monitor());
                } catch (RuntimeException ignored) {
                    // As for local monitors: a failed wake-up must not stop mirroring.
                }
            }
        }
        // Updated only after the wake-ups went out, so a host that sees nothing owed
        // has already been handed every wake-up this pass found.
        owedWakes = owed;
    }

    /**
     * Whether a monitored process launched on this session's behalf was still
     * running at the last pass, so its end will wake this session. A pass
     * delivers the wake-ups it finds due before it updates this. A closed mirror
     * owes nothing: it will not wake anyone.
     */
    public boolean owesWake() {
        return !closed.get() && owedWakes > 0;
    }

    /**
     * True once per entry: when a process this session is the parent of ends while
     * its owner monitors it. Only an entry seen running, or first seen after the
     * first poll, can wake, and the monitor flag may land after the exit.
     */
    private boolean dueForWake(String key, ProcessCoordEntry entry) {
        WakeState state = wakes.computeIfAbsent(key, ignored -> new WakeState(seeded));
        if (entry.isRunningState()) state.eligible = true;
        if (state.woken || !state.eligible || !entry.isTerminalState() || !entry.isMonitored()
                || localSessionId == null || !localSessionId.equals(entry.getParentSessionId())) {
            return false;
        }
        state.woken = true;
        return true;
    }

    /** True while a process this session is the parent of runs under its owner's monitor. */
    private boolean awaitsWake(String key, ProcessCoordEntry entry) {
        WakeState state = wakes.get(key);
        return state != null && !state.woken && entry.isRunningState() && entry.isMonitored()
                && localSessionId != null && localSessionId.equals(entry.getParentSessionId());
    }

    private static final class WakeState {
        boolean eligible;
        boolean woken;

        WakeState(boolean eligible) {
            this.eligible = eligible;
        }
    }

    private record Wake(BackgroundProcessManager.ProcessEntry entry,
                        BackgroundProcessManager.ProcessMonitor monitor) {
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
