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

package ai.kompile.app.subprocess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Centralized registry for all managed subprocesses.
 *
 * Solves three problems:
 * <ol>
 *   <li><b>Orphan protection</b> — registers a JVM shutdown hook so subprocesses
 *       are killed even on hard parent crash (OOM, SIGKILL won't fire the hook,
 *       but SIGTERM, System.exit, and uncaught exceptions will).</li>
 *   <li><b>Unified lifecycle view</b> — single place to query all running
 *       subprocesses regardless of which launcher started them.</li>
 *   <li><b>Coordinated shutdown</b> — {@link #shutdownAll()} destroys every
 *       tracked process with SIGTERM → wait → SIGKILL.</li>
 * </ol>
 *
 * Each launcher calls {@link #register(String, Process, String)} when it starts
 * a subprocess and {@link #deregister(String)} when the process exits normally.
 */
@Component
public class SubprocessRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessRegistry.class);

    private static final int GRACEFUL_SHUTDOWN_SECONDS = 10;
    private static final int FORCE_KILL_WAIT_SECONDS = 5;

    private final ConcurrentHashMap<String, TrackedProcess> processes = new ConcurrentHashMap<>();
    private volatile boolean shutdownHookRegistered = false;

    public SubprocessRegistry() {
        registerShutdownHook();
    }

    /**
     * Register a subprocess with the registry.
     *
     * @param id      unique identifier (e.g. "embedding", "vector-pop-taskId", "ingest-taskId")
     * @param process the live {@link Process}
     * @param type    human-readable type label (e.g. "embedding", "vector-population", "ingest")
     */
    public void register(String id, Process process, String type) {
        TrackedProcess tracked = new TrackedProcess(id, process, type, Instant.now());
        TrackedProcess previous = processes.put(id, tracked);
        if (previous != null && previous.process.isAlive()) {
            logger.warn("Replacing already-registered subprocess id={} (PID={}, type={}) — destroying old process",
                    id, previous.process.pid(), previous.type);
            destroyProcess(previous);
        }
        logger.info("Subprocess registered: id={}, PID={}, type={}", id, process.pid(), type);
    }

    /**
     * Mark a subprocess as ready. Launchers call this once the subprocess
     * has emitted its readiness signal (READY message, first heartbeat, etc.).
     */
    public void markReady(String id) {
        TrackedProcess tracked = processes.get(id);
        if (tracked != null) {
            tracked.readyAt = Instant.now();
            logger.info("Subprocess ready: id={}, PID={}, type={}, startupTime={}ms",
                    id, tracked.process.pid(), tracked.type,
                    Duration.between(tracked.registeredAt, tracked.readyAt).toMillis());
        }
    }

    /**
     * Deregister a subprocess (normal exit). Does NOT destroy the process.
     */
    public void deregister(String id) {
        TrackedProcess removed = processes.remove(id);
        if (removed != null) {
            logger.info("Subprocess deregistered: id={}, PID={}, type={}", id, removed.process.pid(), removed.type);
        }
    }

    /**
     * Destroy and deregister a specific subprocess.
     */
    public void destroyAndDeregister(String id) {
        TrackedProcess tracked = processes.remove(id);
        if (tracked != null) {
            logger.info("Destroying subprocess: id={}, PID={}, type={}", id, tracked.process.pid(), tracked.type);
            destroyProcess(tracked);
        }
    }

    /**
     * Shut down all registered subprocesses. Called by {@link PreDestroy}
     * and the JVM shutdown hook.
     */
    public synchronized void shutdownAll() {
        if (processes.isEmpty()) {
            return;
        }

        logger.info("Shutting down {} registered subprocess(es)", processes.size());

        // Send SIGTERM to all
        List<TrackedProcess> toKill = new ArrayList<>(processes.values());
        for (TrackedProcess tracked : toKill) {
            if (tracked.process.isAlive()) {
                logger.info("Sending SIGTERM to subprocess: id={}, PID={}, type={}",
                        tracked.id, tracked.process.pid(), tracked.type);
                tracked.process.destroy();
            }
        }

        // Wait for graceful shutdown
        Instant deadline = Instant.now().plusSeconds(GRACEFUL_SHUTDOWN_SECONDS);
        for (TrackedProcess tracked : toKill) {
            if (tracked.process.isAlive()) {
                long waitMs = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
                try {
                    tracked.process.waitFor(waitMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // Force kill any survivors
        for (TrackedProcess tracked : toKill) {
            if (tracked.process.isAlive()) {
                logger.warn("Force killing subprocess: id={}, PID={}, type={}",
                        tracked.id, tracked.process.pid(), tracked.type);
                tracked.process.destroyForcibly();
                try {
                    tracked.process.waitFor(FORCE_KILL_WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        processes.clear();
        logger.info("All subprocesses shut down");
    }

    /**
     * Get a snapshot of all tracked subprocesses.
     */
    public List<SubprocessInfo> listAll() {
        List<SubprocessInfo> result = new ArrayList<>();
        for (TrackedProcess tracked : processes.values()) {
            result.add(toInfo(tracked));
        }
        result.sort(Comparator.comparing(SubprocessInfo::registeredAt));
        return result;
    }

    /**
     * Get info for a specific subprocess.
     */
    public Optional<SubprocessInfo> get(String id) {
        TrackedProcess tracked = processes.get(id);
        return tracked != null ? Optional.of(toInfo(tracked)) : Optional.empty();
    }

    /**
     * Get the count of alive subprocesses.
     */
    public int aliveCount() {
        int count = 0;
        for (TrackedProcess tracked : processes.values()) {
            if (tracked.process.isAlive()) count++;
        }
        return count;
    }

    /**
     * Check if a specific subprocess is registered and alive.
     */
    public boolean isAlive(String id) {
        TrackedProcess tracked = processes.get(id);
        return tracked != null && tracked.process.isAlive();
    }

    /**
     * Check if a subprocess has reported ready.
     */
    public boolean isReady(String id) {
        TrackedProcess tracked = processes.get(id);
        return tracked != null && tracked.readyAt != null;
    }

    /**
     * Clean up entries for processes that have exited but were never deregistered.
     */
    public int cleanupDead() {
        int removed = 0;
        Iterator<Map.Entry<String, TrackedProcess>> it = processes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, TrackedProcess> entry = it.next();
            TrackedProcess tracked = entry.getValue();
            if (!tracked.process.isAlive()) {
                logger.info("Cleaning up dead subprocess: id={}, PID={}, type={}, exitCode={}",
                        tracked.id, tracked.process.pid(), tracked.type, exitCodeSafe(tracked.process));
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    @PreDestroy
    public void onDestroy() {
        shutdownAll();
    }

    private void registerShutdownHook() {
        if (!shutdownHookRegistered) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                // In shutdown hook context, logging may not work — use stderr
                System.err.println("[SubprocessRegistry] JVM shutting down — killing " + processes.size() + " subprocess(es)");
                for (TrackedProcess tracked : processes.values()) {
                    if (tracked.process.isAlive()) {
                        System.err.println("[SubprocessRegistry] Killing PID=" + tracked.process.pid()
                                + " type=" + tracked.type + " id=" + tracked.id);
                        tracked.process.destroyForcibly();
                    }
                }
            }, "subprocess-registry-shutdown-hook"));
            shutdownHookRegistered = true;
        }
    }

    private void destroyProcess(TrackedProcess tracked) {
        if (!tracked.process.isAlive()) return;

        tracked.process.destroy();
        try {
            boolean terminated = tracked.process.waitFor(GRACEFUL_SHUTDOWN_SECONDS, TimeUnit.SECONDS);
            if (!terminated && tracked.process.isAlive()) {
                logger.warn("Subprocess id={} PID={} did not terminate gracefully, force killing",
                        tracked.id, tracked.process.pid());
                tracked.process.destroyForcibly();
                tracked.process.waitFor(FORCE_KILL_WAIT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            tracked.process.destroyForcibly();
        }
    }

    private SubprocessInfo toInfo(TrackedProcess tracked) {
        return new SubprocessInfo(
                tracked.id,
                tracked.type,
                tracked.process.pid(),
                tracked.process.isAlive(),
                tracked.registeredAt,
                tracked.readyAt,
                tracked.readyAt != null
                        ? Duration.between(tracked.registeredAt, tracked.readyAt).toMillis()
                        : null,
                Duration.between(tracked.registeredAt, Instant.now()).toMillis(),
                tracked.process.isAlive() ? null : exitCodeSafe(tracked.process)
        );
    }

    private static Integer exitCodeSafe(Process p) {
        try {
            return p.exitValue();
        } catch (IllegalThreadStateException e) {
            return null;
        }
    }

    private static class TrackedProcess {
        final String id;
        final Process process;
        final String type;
        final Instant registeredAt;
        volatile Instant readyAt;

        TrackedProcess(String id, Process process, String type, Instant registeredAt) {
            this.id = id;
            this.process = process;
            this.type = type;
            this.registeredAt = registeredAt;
        }
    }

    /**
     * Immutable snapshot of a tracked subprocess.
     */
    public record SubprocessInfo(
            String id,
            String type,
            long pid,
            boolean alive,
            Instant registeredAt,
            Instant readyAt,
            Long startupTimeMs,
            long uptimeMs,
            Integer exitCode
    ) {}
}
