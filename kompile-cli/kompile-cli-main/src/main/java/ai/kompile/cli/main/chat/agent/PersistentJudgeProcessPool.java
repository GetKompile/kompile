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

package ai.kompile.cli.main.chat.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Process pool for persistent judge agent subprocesses.
 *
 * <p>Every judge consumer (enforcer turn gate, realtime tap, MCP tool-call guard, the
 * {@code enforcer}/{@code post_feedback} MCP tools, performance harness) used to own a
 * private {@code PersistentAgentProcess} — so one enforced session could hold several
 * identical claude judges, and the per-call MCP tools booted and destroyed a fresh judge
 * on <b>every invocation</b>. This pool shares one warm process per unique
 * (binary, model, args, system prompt) spec:</p>
 *
 * <ul>
 *   <li>{@link #acquire} returns a {@link Lease}; concurrent acquires of the same spec
 *       share one process (spawn is collapsed under a per-spec lock).</li>
 *   <li>{@link Lease#close()} releases instead of destroying. When the last lease is
 *       released the process is kept warm for {@code kompile.judge.pool.idleMs}
 *       (default 120s) and destroyed only if nobody re-acquires in that window — a
 *       burst of {@code enforcer} tool calls reuses one judge instead of paying an
 *       agent boot per call.</li>
 *   <li>The pool key includes the system prompt, so consumers with different judge
 *       prompts (enforcer vs post-feedback vs harness) never share a process — the old
 *       per-instance code silently reused a process spawned with a DIFFERENT prompt if
 *       callers varied it.</li>
 *   <li>A JVM shutdown hook destroys everything still pooled, so judge subprocesses
 *       cannot outlive the CLI.</li>
 * </ul>
 *
 * <p>Turn-level serialization: a shared process serializes concurrent judgements on
 * {@code PersistentAgentProcess}'s send lock. Judge calls within a session are almost
 * always sequential (turn gate after the turn, tap rate-limited during it); the trade
 * is deliberate — far fewer agent processes on boxes running many sessions.</p>
 */
public final class PersistentJudgeProcessPool {

    /** Narrow view of a pooled process — lets tests swap in a fake factory. */
    interface PooledProcess {
        String sendMessage(String message, int timeoutSeconds) throws IOException, InterruptedException;
        boolean isAlive();
        void close();
    }

    /** Factory seam (package-private for tests). */
    interface ProcessFactory {
        PooledProcess create(Spec spec) throws IOException, InterruptedException;
    }

    /** Immutable description of the judge process to run. */
    public static final class Spec {
        final String binary;
        final String model;
        final boolean skipPermissions;
        final List<String> extraArgs;
        final List<String> clearEnvPrefixes;
        final String systemPrompt;
        final int startTimeoutSeconds;

        public Spec(String binary, String model, boolean skipPermissions,
                    List<String> extraArgs, List<String> clearEnvPrefixes,
                    String systemPrompt, int startTimeoutSeconds) {
            this.binary = Objects.requireNonNull(binary, "binary");
            this.model = model;
            this.skipPermissions = skipPermissions;
            this.extraArgs = extraArgs != null ? List.copyOf(extraArgs) : List.of();
            this.clearEnvPrefixes = clearEnvPrefixes != null ? List.copyOf(clearEnvPrefixes) : List.of();
            this.systemPrompt = systemPrompt != null ? systemPrompt : "";
            this.startTimeoutSeconds = startTimeoutSeconds;
        }

        String key() {
            // System prompt is part of the identity: persistent agents receive it only at
            // spawn, so processes with different prompts must never be shared.
            return binary + "|" + (model == null ? "" : model)
                    + "|" + skipPermissions
                    + "|" + String.join("|", extraArgs)
                    + "|" + String.join("|", clearEnvPrefixes)
                    + "|" + Integer.toHexString(systemPrompt.hashCode())
                    + ":" + systemPrompt.length();
        }
    }

    /** A refcounted handle on a pooled process. Closing releases; it never destroys directly. */
    public static final class Lease implements AutoCloseable {
        private final Entry entry;
        private boolean closed;

        private Lease(Entry entry) {
            this.entry = entry;
        }

        public String sendMessage(String message, int timeoutSeconds) throws IOException, InterruptedException {
            PooledProcess p;
            synchronized (entry.lock) {
                p = entry.process;
            }
            if (p == null) {
                throw new IOException("Pooled judge process is not running");
            }
            return p.sendMessage(message, timeoutSeconds);
        }

        public boolean isAlive() {
            synchronized (entry.lock) {
                return entry.process != null && entry.process.isAlive();
            }
        }

        @Override
        public void close() {
            synchronized (entry.lock) {
                if (closed) {
                    return;
                }
                closed = true;
                entry.leases--;
                if (entry.leases <= 0 && entry.process != null && !entry.removed) {
                    entry.scheduleReap();
                }
            }
        }
    }

    private static final class Entry {
        final Object lock = new Object();
        final String key;
        PooledProcess process;
        int leases;
        boolean removed;
        ScheduledFuture<?> pendingReap;

        Entry(String key) {
            this.key = key;
        }

        // Called with lock held.
        void scheduleReap() {
            cancelReap();
            pendingReap = REAPER.schedule(() -> reap(this), idleMillis(), TimeUnit.MILLISECONDS);
        }

        // Called with lock held.
        void cancelReap() {
            if (pendingReap != null) {
                pendingReap.cancel(false);
                pendingReap = null;
            }
        }
    }

    private static final Object POOL_LOCK = new Object();
    private static final Map<String, Entry> ENTRIES = new HashMap<>();

    private static final ScheduledExecutorService REAPER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "judge-pool-reaper");
                t.setDaemon(true);
                return t;
            });

    /** Idle keep-warm window; override for tests via {@link #setIdleMillisForTests}. */
    private static volatile long idleMillisOverride = -1;

    /** Test seam; production always uses {@link #DEFAULT_FACTORY}. */
    static volatile ProcessFactory factory;

    private static final ProcessFactory DEFAULT_FACTORY = spec -> {
        PersistentAgentProcess.Builder builder = PersistentAgentProcess.builder(spec.binary)
                .systemPrompt(spec.systemPrompt.isEmpty() ? null : spec.systemPrompt)
                .model(spec.model)
                .skipPermissions(spec.skipPermissions)
                .extraArgs(spec.extraArgs);
        for (String prefix : spec.clearEnvPrefixes) {
            builder.clearEnvPrefix(prefix);
        }
        PersistentAgentProcess process = builder.build();
        process.start(spec.startTimeoutSeconds);
        return new PooledProcess() {
            @Override
            public String sendMessage(String message, int timeoutSeconds)
                    throws IOException, InterruptedException {
                return process.sendMessage(message, timeoutSeconds);
            }

            @Override
            public boolean isAlive() {
                return process.isAlive();
            }

            @Override
            public void close() {
                process.close();
            }
        };
    };

    static {
        factory = DEFAULT_FACTORY;
        Runtime.getRuntime().addShutdownHook(new Thread(
                PersistentJudgeProcessPool::closeAll, "judge-pool-shutdown"));
    }

    private PersistentJudgeProcessPool() {}

    /**
     * Acquire a lease on the shared process for this spec, spawning it if absent or dead.
     * Blocks (bounded by the spec's start timeout) while a spawn for the same spec is in
     * flight — concurrent acquirers share the one spawn instead of racing their own.
     */
    public static Lease acquire(Spec spec) throws IOException, InterruptedException {
        while (true) {
            Entry entry;
            synchronized (POOL_LOCK) {
                entry = ENTRIES.computeIfAbsent(spec.key(), Entry::new);
            }
            synchronized (entry.lock) {
                if (entry.removed) {
                    continue; // reaped between map lookup and lock — retry with a fresh entry
                }
                entry.cancelReap();
                if (entry.process == null || !entry.process.isAlive()) {
                    if (entry.process != null) {
                        entry.process.close();
                        entry.process = null;
                    }
                    entry.process = factory.create(spec);
                }
                entry.leases++;
                return new Lease(entry);
            }
        }
    }

    private static void reap(Entry entry) {
        synchronized (entry.lock) {
            if (entry.leases > 0 || entry.removed) {
                return; // re-acquired (or already gone) while the reap was queued
            }
            if (entry.process != null) {
                entry.process.close();
                entry.process = null;
            }
            entry.removed = true;
            entry.pendingReap = null;
        }
        synchronized (POOL_LOCK) {
            ENTRIES.remove(entry.key, entry);
        }
    }

    /** Destroy every pooled process. Used by the shutdown hook and tests. */
    static void closeAll() {
        List<Entry> snapshot;
        synchronized (POOL_LOCK) {
            snapshot = new ArrayList<>(ENTRIES.values());
            ENTRIES.clear();
        }
        for (Entry entry : snapshot) {
            synchronized (entry.lock) {
                entry.cancelReap();
                if (entry.process != null) {
                    entry.process.close();
                    entry.process = null;
                }
                entry.removed = true;
            }
        }
    }

    private static long idleMillis() {
        long override = idleMillisOverride;
        if (override >= 0) {
            return override;
        }
        return Long.getLong("kompile.judge.pool.idleMs", 120_000L);
    }

    // ── Test support ────────────────────────────────────────────────────────

    static void setIdleMillisForTests(long millis) {
        idleMillisOverride = millis;
    }

    static void resetForTests() {
        closeAll();
        factory = DEFAULT_FACTORY;
        idleMillisOverride = -1;
    }

    /** Live pooled-process count (for tests/diagnostics). */
    static int pooledCount() {
        synchronized (POOL_LOCK) {
            return ENTRIES.size();
        }
    }
}
