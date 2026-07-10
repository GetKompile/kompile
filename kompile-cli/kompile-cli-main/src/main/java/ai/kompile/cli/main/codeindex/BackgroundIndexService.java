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

package ai.kompile.cli.main.codeindex;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps local code indexes fresh in the background, per project, so read
 * actions never pay the stat-walk inline and full index builds never block a
 * tool call.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li><b>Background index jobs</b> — {@link #submitIndexJob} runs a full or
 *       incremental index pass on a daemon executor with status tracking
 *       ({@code queued/running/completed/failed}), deduplicating concurrent
 *       submissions per project.</li>
 *   <li><b>Per-project watchers</b> — the first read or completed job for a
 *       project auto-starts an {@link IndexFileWatcher} (capped, LRU-evicted)
 *       so filesystem edits trigger incremental re-index within seconds.</li>
 *   <li><b>Write hooks</b> — {@link #noteFileWritten} lets kompile's own
 *       edit/write tools mark a project dirty the instant they touch a file,
 *       independent of inotify delivery.</li>
 *   <li><b>Freshness join</b> — {@link #prepareForRead} replaces the old
 *       blocking inline refresh: when background work is pending or running
 *       for the project it waits briefly (bounded) so callers still read
 *       their own writes; when the index is quiet it returns immediately
 *       (no stat walk at all).</li>
 *   <li><b>Periodic backstop</b> — a low-frequency sweep re-runs the cheap
 *       fingerprint pass for recently-used projects, catching anything a
 *       watcher missed (inotify overflow, unwatchable trees).</li>
 * </ul>
 *
 * <p>All state is in-memory and per-process; daemon threads die with the
 * process. Cross-process index writes stay safe via {@link IndexLockManager}
 * file locks. Every failure path is swallowed after logging to stderr — index
 * maintenance must never break a search.
 *
 * <p>Tunables (system property first, then environment variable):
 * <ul>
 *   <li>{@code KOMPILE_CODE_INDEX_BACKGROUND} — master switch (default true;
 *       when false, {@link #prepareForRead} falls back to the legacy inline
 *       throttled refresh and no watchers/jobs are started).</li>
 *   <li>{@code KOMPILE_CODE_INDEX_WATCH} — watcher auto-start (default true).</li>
 *   <li>{@code KOMPILE_CODE_INDEX_MAX_WATCHERS} — watcher cap (default 8).</li>
 *   <li>{@code KOMPILE_CODE_INDEX_READ_WAIT_MS} — freshness-join budget for
 *       read actions (default 2000).</li>
 *   <li>{@code KOMPILE_CODE_INDEX_BACKSTOP_SECONDS} — backstop sweep period
 *       (default 300; 0 disables).</li>
 * </ul>
 */
public final class BackgroundIndexService {

    private static final long WRITE_DEBOUNCE_MS = 750;
    private static final long ROOT_CACHE_TTL_MS = 60_000;
    private static final long ACTIVE_PROJECT_WINDOW_MS = TimeUnit.HOURS.toMillis(1);
    private static final int COMPLETED_JOB_RETENTION = 32;

    private static final AtomicReference<BackgroundIndexService> INSTANCE = new AtomicReference<>();

    public static BackgroundIndexService getInstance() {
        BackgroundIndexService existing = INSTANCE.get();
        if (existing != null) return existing;
        BackgroundIndexService created = new BackgroundIndexService();
        if (INSTANCE.compareAndSet(null, created)) {
            created.startBackstop();
            return created;
        }
        created.close();
        return INSTANCE.get();
    }

    /** Test seam: tear down the current instance (stops watchers/executor). */
    static void resetForTests() {
        BackgroundIndexService existing = INSTANCE.getAndSet(null);
        if (existing != null) existing.close();
    }

    // ── Job model ───────────────────────────────────────────────────────────

    public enum JobStatus { QUEUED, RUNNING, COMPLETED, FAILED }

    /** Mutable status holder for one background index pass. */
    public static final class IndexJob {
        private final String id;
        private final String projectId;
        private final String rootPath;
        private final boolean force;
        private final Instant submittedAt = Instant.now();
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile JobStatus status = JobStatus.QUEUED;
        private volatile String progressLine = "";
        private volatile LocalCodeIndexer.IndexResult result;
        private volatile String error;

        IndexJob(String id, String projectId, String rootPath, boolean force) {
            this.id = id;
            this.projectId = projectId;
            this.rootPath = rootPath;
            this.force = force;
        }

        public String id() { return id; }
        public String projectId() { return projectId; }
        public String rootPath() { return rootPath; }
        public boolean force() { return force; }
        public JobStatus status() { return status; }
        public Instant submittedAt() { return submittedAt; }
        public Instant startedAt() { return startedAt; }
        public Instant finishedAt() { return finishedAt; }
        public String progressLine() { return progressLine; }
        public LocalCodeIndexer.IndexResult result() { return result; }
        public String error() { return error; }
        public boolean isDone() {
            return status == JobStatus.COMPLETED || status == JobStatus.FAILED;
        }

        /** Milliseconds spent running, or since start when still running. */
        public long runtimeMillis() {
            Instant start = startedAt;
            if (start == null) return 0;
            Instant end = finishedAt != null ? finishedAt : Instant.now();
            return end.toEpochMilli() - start.toEpochMilli();
        }
    }

    // ── Per-project state ───────────────────────────────────────────────────

    private static final class ProjectState {
        final String projectId;
        volatile Path root;
        volatile IndexFileWatcher watcher;
        volatile boolean watcherFailed;
        final AtomicBoolean watcherStartQueued = new AtomicBoolean();
        volatile long lastTouchedMs;
        volatile boolean refreshRunning;
        /** Bumped on every write notification; refresh passes snapshot it. */
        final AtomicLong writeSeq = new AtomicLong();
        /** writeSeq value covered by the last completed refresh. */
        volatile long cleanSeq;
        volatile boolean refreshQueued;
        volatile String lastNote;
        volatile long lastRefreshCompletedMs;
        volatile IndexJob activeJob;

        ProjectState(String projectId) { this.projectId = projectId; }

        boolean dirty() { return writeSeq.get() > cleanSeq; }

        boolean busy() {
            IndexJob job = activeJob;
            if (job != null && !job.isDone()) return true;
            if (refreshRunning || refreshQueued || dirty()) return true;
            IndexFileWatcher w = watcher;
            return w != null && !w.getPendingChanges().isEmpty();
        }
    }

    private final LocalCodeIndexer indexer = new LocalCodeIndexer();
    private final ScheduledThreadPoolExecutor executor;
    private final ConcurrentHashMap<String, ProjectState> projects = new ConcurrentHashMap<>();
    private final ArrayDeque<IndexJob> recentJobs = new ArrayDeque<>();
    private final AtomicLong jobCounter = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** rootPath (absolute, normalized) → projectId, rebuilt lazily. */
    private volatile Map<Path, String> rootCache = Map.of();
    private volatile long rootCacheBuiltMs;

    private BackgroundIndexService() {
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "code-index-background");
            t.setDaemon(true);
            return t;
        });
        ex.setKeepAliveTime(30, TimeUnit.SECONDS);
        ex.allowCoreThreadTimeOut(true);
        ex.setRemoveOnCancelPolicy(true);
        this.executor = ex;
    }

    private void startBackstop() {
        long period = longConfig("KOMPILE_CODE_INDEX_BACKSTOP_SECONDS", 300);
        if (period > 0 && enabled()) {
            executor.scheduleWithFixedDelay(this::backstopSweep, period, period, TimeUnit.SECONDS);
        }
    }

    private void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (ProjectState state : projects.values()) {
            IndexFileWatcher w = state.watcher;
            if (w != null) {
                try { w.stop(); } catch (Exception ignored) {}
            }
        }
        executor.shutdownNow();
    }

    // ── Configuration ───────────────────────────────────────────────────────

    static String config(String key, String fallback) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static boolean boolConfig(String key, boolean fallback) {
        return Boolean.parseBoolean(config(key, Boolean.toString(fallback)));
    }

    private static long longConfig(String key, long fallback) {
        try {
            return Long.parseLong(config(key, Long.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean enabled() { return boolConfig("KOMPILE_CODE_INDEX_BACKGROUND", true); }
    private boolean watchEnabled() { return boolConfig("KOMPILE_CODE_INDEX_WATCH", true); }
    private int maxWatchers() { return (int) longConfig("KOMPILE_CODE_INDEX_MAX_WATCHERS", 8); }
    private long readWaitMs() { return longConfig("KOMPILE_CODE_INDEX_READ_WAIT_MS", 2000); }

    // ── Background index jobs ───────────────────────────────────────────────

    /**
     * Submit a full/incremental index pass for {@code root} as a background
     * job. If a job for the project is already queued or running it is
     * returned instead of enqueuing a duplicate.
     */
    public IndexJob submitIndexJob(Path root, String projectId, String includes,
                                   String excludes, boolean force) {
        ProjectState state = stateFor(projectId);
        state.root = root;
        state.lastTouchedMs = System.currentTimeMillis();

        synchronized (state) {
            IndexJob existing = state.activeJob;
            if (existing != null && !existing.isDone()) return existing;
            IndexJob job = new IndexJob("idx-" + jobCounter.incrementAndGet(),
                    projectId, root.toString(), force);
            state.activeJob = job;
            executor.execute(() -> runJob(state, job, includes, excludes));
            return job;
        }
    }

    private void runJob(ProjectState state, IndexJob job, String includes, String excludes) {
        job.startedAt = Instant.now();
        job.status = JobStatus.RUNNING;
        long seqBefore = state.writeSeq.get();
        try {
            LocalCodeIndexer.IndexResult result = indexer.index(
                    Path.of(job.rootPath()), job.projectId(), includes, excludes,
                    job.force(), lastLineStream(job));
            job.result = result;
            job.status = JobStatus.COMPLETED;
            state.cleanSeq = Math.max(state.cleanSeq, seqBefore);
            state.lastRefreshCompletedMs = System.currentTimeMillis();
        } catch (Exception e) {
            job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            job.status = JobStatus.FAILED;
            System.err.println("[code-index] background index failed for '"
                    + job.projectId() + "': " + job.error);
        } finally {
            job.finishedAt = Instant.now();
            retainJob(job);
            synchronized (state) {
                if (state.activeJob == job) state.activeJob = null;
                state.notifyAll();
            }
            // A freshly (re)indexed project is worth watching from now on.
            if (job.status == JobStatus.COMPLETED) {
                ensureWatcher(state);
                if (state.dirty()) scheduleRefresh(state, 0);
            }
        }
    }

    private void retainJob(IndexJob job) {
        synchronized (recentJobs) {
            recentJobs.addFirst(job);
            while (recentJobs.size() > COMPLETED_JOB_RETENTION) recentJobs.removeLast();
        }
    }

    /** Wait up to {@code millis} for the job to finish; returns done-ness. */
    public boolean awaitJob(IndexJob job, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        ProjectState state = stateFor(job.projectId());
        synchronized (state) {
            while (!job.isDone()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return false;
                try {
                    state.wait(Math.min(remaining, 100));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return job.isDone();
                }
            }
        }
        return true;
    }

    /** The active (queued/running) job for a project, if any. */
    public IndexJob activeJob(String projectId) {
        ProjectState state = projects.get(projectId);
        IndexJob job = state == null ? null : state.activeJob;
        return job != null && !job.isDone() ? job : null;
    }

    /** Recent jobs, newest first; filtered to a project when non-null. */
    public List<IndexJob> jobs(String projectId) {
        List<IndexJob> out = new ArrayList<>();
        for (ProjectState state : projects.values()) {
            IndexJob active = state.activeJob;
            if (active != null && !active.isDone()
                    && (projectId == null || projectId.equals(active.projectId()))) {
                out.add(active);
            }
        }
        synchronized (recentJobs) {
            for (IndexJob job : recentJobs) {
                if (projectId == null || projectId.equals(job.projectId())) out.add(job);
            }
        }
        out.sort(Comparator.comparing(IndexJob::submittedAt).reversed());
        return out;
    }

    // ── Read-path freshness ─────────────────────────────────────────────────

    /**
     * Called before a read action against {@code projectId}. Ensures the
     * project is under background maintenance, then waits briefly for any
     * pending/in-flight work so the caller reads its own writes.
     *
     * @return a short annotation (last background refresh note, consumed
     *         once; or an in-progress note) or {@code null} when there is
     *         nothing worth saying.
     */
    public String prepareForRead(LocalCodeIndexer callerIndexer, String projectId) {
        if (projectId == null || projectId.isBlank() || closed.get()) return null;
        if (!enabled()) {
            // Legacy behavior: synchronous throttled inline refresh.
            return IndexAutoRefresher.maybeRefresh(
                    callerIndexer != null ? callerIndexer : indexer, projectId);
        }
        try {
            ProjectState state = stateFor(projectId);
            state.lastTouchedMs = System.currentTimeMillis();
            if (state.root == null) {
                Path root = lookupRoot(projectId);
                if (root == null) return null; // not indexed yet — nothing to maintain
                state.root = root;
            }

            // Watcher registration walks the tree — never pay that on a read.
            boolean watching = isWatching(projectId);
            if (!watching) {
                ensureWatcherAsync(state);
                if (!state.dirty()) {
                    // No watcher coverage yet: keep the index converging via
                    // the legacy throttled pass, off the caller's thread.
                    scheduleRefresh(state, IndexAutoRefresherDefaultInterval.VALUE);
                }
            }

            String inProgress = null;
            if (state.busy() && !awaitQuiet(state, readWaitMs())) {
                IndexJob job = state.activeJob;
                inProgress = job != null && !job.isDone()
                        ? "[background index job " + job.id() + " running — results may lag]"
                        : "[index refresh running in background — results may lag recent edits]";
            }

            String note = consumeNote(state);
            if (note == null) return inProgress;
            return inProgress == null ? note : note + "\n" + inProgress;
        } catch (Exception e) {
            System.err.println("[code-index] background freshness skipped for '"
                    + projectId + "': " + e.getMessage());
            return null;
        }
    }

    private boolean awaitQuiet(ProjectState state, long budgetMs) {
        long deadline = System.currentTimeMillis() + budgetMs;
        while (state.busy()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) return false;
            synchronized (state) {
                if (!state.busy()) return true;
                try {
                    state.wait(Math.min(remaining, 50));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return !state.busy();
                }
            }
        }
        return true;
    }

    private String consumeNote(ProjectState state) {
        String note = state.lastNote;
        if (note != null) state.lastNote = null;
        return note;
    }

    // ── Write notifications ─────────────────────────────────────────────────

    /**
     * Notify the service that kompile itself just wrote {@code file}. When the
     * file sits inside an indexed project root, the project is marked dirty
     * and a debounced incremental refresh is scheduled. Cheap and safe to call
     * on every write; never throws.
     */
    public void noteFileWritten(Path file) {
        if (file == null || closed.get() || !enabled()) return;
        try {
            String projectId = projectForPath(file.toAbsolutePath().normalize());
            if (projectId == null) return;
            ProjectState state = stateFor(projectId);
            if (state.root == null) state.root = lookupRoot(projectId);
            state.lastTouchedMs = System.currentTimeMillis();
            state.writeSeq.incrementAndGet();
            scheduleRefresh(state, 0);
        } catch (Exception e) {
            System.err.println("[code-index] write notification dropped: " + e.getMessage());
        }
    }

    private String projectForPath(Path file) {
        Map<Path, String> cache = rootCache;
        long now = System.currentTimeMillis();
        if (now - rootCacheBuiltMs > ROOT_CACHE_TTL_MS) {
            cache = rebuildRootCache();
        }
        String best = null;
        int bestDepth = -1;
        for (Map.Entry<Path, String> entry : cache.entrySet()) {
            Path root = entry.getKey();
            if (file.startsWith(root) && root.getNameCount() > bestDepth) {
                best = entry.getValue();
                bestDepth = root.getNameCount();
            }
        }
        return best;
    }

    private synchronized Map<Path, String> rebuildRootCache() {
        long now = System.currentTimeMillis();
        if (now - rootCacheBuiltMs <= ROOT_CACHE_TTL_MS) return rootCache;
        Map<Path, String> fresh = new LinkedHashMap<>();
        try {
            for (Map<String, Object> meta : indexer.listProjects()) {
                Object projectId = meta.get("projectId");
                Object rootPath = meta.get("rootPath");
                if (projectId == null || rootPath == null) continue;
                try {
                    fresh.put(Path.of(rootPath.toString()).toAbsolutePath().normalize(),
                            projectId.toString());
                } catch (Exception ignored) {}
            }
        } catch (IOException e) {
            // Keep the stale cache on listing failure.
            rootCacheBuiltMs = now;
            return rootCache;
        }
        rootCache = fresh;
        rootCacheBuiltMs = now;
        return fresh;
    }

    // ── Incremental refresh scheduling ──────────────────────────────────────

    /**
     * Schedule a single-flight incremental refresh for the project. A
     * {@code throttleMs} of 0 bypasses the shared throttle (used when we know
     * something changed); otherwise the legacy 30s throttle applies.
     */
    private void scheduleRefresh(ProjectState state, long throttleMs) {
        synchronized (state) {
            if (state.refreshQueued) return;
            state.refreshQueued = true;
        }
        long delay = throttleMs == 0 ? WRITE_DEBOUNCE_MS : 0;
        try {
            executor.schedule(() -> runRefresh(state, throttleMs), delay, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            synchronized (state) {
                state.refreshQueued = false;
                state.notifyAll();
            }
        }
    }

    private void runRefresh(ProjectState state, long throttleMs) {
        long seqBefore = state.writeSeq.get();
        synchronized (state) {
            state.refreshQueued = false;
            IndexJob job = state.activeJob;
            if (job != null && !job.isDone()) {
                // A full pass is in flight; its completion re-schedules us if
                // writes landed meanwhile. Don't spin against it.
                state.notifyAll();
                return;
            }
            state.refreshRunning = true;
        }
        try {
            String note = IndexAutoRefresher.maybeRefresh(indexer, state.projectId, throttleMs);
            if (note != null) {
                state.lastNote = note.replace("[index auto-refreshed:",
                        "[index auto-refreshed in background:");
            }
            // A bypass pass (throttle 0) always runs, so it certifies every
            // write it saw as indexed. A throttled pass may have been skipped
            // and must not clear dirty state.
            if (throttleMs == 0) {
                state.cleanSeq = Math.max(state.cleanSeq, seqBefore);
            }
            state.lastRefreshCompletedMs = System.currentTimeMillis();
        } catch (Exception e) {
            System.err.println("[code-index] background refresh failed for '"
                    + state.projectId + "': " + e.getMessage());
            // Don't wedge waiters on a persistently failing project.
            state.cleanSeq = Math.max(state.cleanSeq, seqBefore);
        } finally {
            synchronized (state) {
                state.refreshRunning = false;
                state.notifyAll();
            }
            if (state.dirty()) scheduleRefresh(state, 0);
        }
    }

    // ── Watcher lifecycle ───────────────────────────────────────────────────

    /** Queue watcher startup on the executor (registration walks the tree). */
    private void ensureWatcherAsync(ProjectState state) {
        if (!watchEnabled() || state.watcherFailed || closed.get()) return;
        IndexFileWatcher existing = state.watcher;
        if (existing != null && existing.isRunning()) return;
        if (!state.watcherStartQueued.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    ensureWatcher(state);
                } finally {
                    state.watcherStartQueued.set(false);
                }
            });
        } catch (Exception e) {
            state.watcherStartQueued.set(false);
        }
    }

    private boolean ensureWatcher(ProjectState state) {
        if (!watchEnabled() || state.watcherFailed || closed.get()) return false;
        IndexFileWatcher existing = state.watcher;
        if (existing != null && existing.isRunning()) return true;
        Path root = state.root;
        if (root == null || !Files.isDirectory(root)) return false;

        synchronized (state) {
            existing = state.watcher;
            if (existing != null && existing.isRunning()) return true;
            evictWatchersOverCap(state.projectId);
            try {
                IndexFileWatcher watcher = indexer.createWatcher(root, state.projectId,
                        silentStream());
                watcher.setListener(new IndexFileWatcher.WatchListener() {
                    @Override
                    public void onFilesChanged(Set<String> changedPaths) {
                        state.writeSeq.incrementAndGet();
                        state.refreshRunning = true;
                    }

                    @Override
                    public void onIndexUpdated(LocalCodeIndexer.IndexResult result) {
                        int reindexed = Math.max(0,
                                result.filesProcessed() - result.filesSkipped());
                        if (reindexed > 0 || result.filesDeleted() > 0) {
                            StringBuilder note = new StringBuilder(
                                    "[index auto-refreshed in background: ")
                                    .append(reindexed).append(" file")
                                    .append(reindexed == 1 ? "" : "s").append(" re-indexed");
                            if (result.filesDeleted() > 0) {
                                note.append(", ").append(result.filesDeleted()).append(" deleted");
                            }
                            state.lastNote = note.append(']').toString();
                        }
                        finishWatcherPass();
                    }

                    @Override
                    public void onError(String message, Exception e) {
                        finishWatcherPass();
                    }

                    private void finishWatcherPass() {
                        IndexFileWatcher w = state.watcher;
                        if (w == null || w.getPendingChanges().isEmpty()) {
                            state.cleanSeq = state.writeSeq.get();
                        }
                        state.lastRefreshCompletedMs = System.currentTimeMillis();
                        synchronized (state) {
                            state.refreshRunning = false;
                            state.notifyAll();
                        }
                    }
                });
                watcher.start();
                state.watcher = watcher;
                return true;
            } catch (Exception e) {
                // Typically inotify watch exhaustion — degrade to polling.
                state.watcherFailed = true;
                System.err.println("[code-index] watcher unavailable for '"
                        + state.projectId + "' (falling back to periodic refresh): "
                        + e.getMessage());
                return false;
            }
        }
    }

    private void evictWatchersOverCap(String incomingProjectId) {
        int cap = Math.max(1, maxWatchers());
        List<ProjectState> watching = new ArrayList<>();
        for (ProjectState state : projects.values()) {
            IndexFileWatcher w = state.watcher;
            if (w != null && w.isRunning() && !state.projectId.equals(incomingProjectId)) {
                watching.add(state);
            }
        }
        if (watching.size() < cap) return;
        watching.sort(Comparator.comparingLong(s -> s.lastTouchedMs));
        for (int i = 0; i <= watching.size() - cap; i++) {
            ProjectState evict = watching.get(i);
            IndexFileWatcher w = evict.watcher;
            evict.watcher = null;
            if (w != null) {
                try { w.stop(); } catch (Exception ignored) {}
            }
        }
    }

    /** Whether a live watcher is maintaining this project's index. */
    public boolean isWatching(String projectId) {
        ProjectState state = projects.get(projectId);
        IndexFileWatcher w = state == null ? null : state.watcher;
        return w != null && w.isRunning();
    }

    // ── Backstop sweep ──────────────────────────────────────────────────────

    private void backstopSweep() {
        long cutoff = System.currentTimeMillis() - ACTIVE_PROJECT_WINDOW_MS;
        for (ProjectState state : projects.values()) {
            if (state.lastTouchedMs < cutoff) continue;
            Path root = state.root;
            if (root == null || !Files.isDirectory(root)) continue;
            scheduleRefresh(state, IndexAutoRefresherDefaultInterval.VALUE);
        }
    }

    // ── Status surfacing ────────────────────────────────────────────────────

    /** One-line background-maintenance status for a project, or null. */
    public String statusLine(String projectId) {
        ProjectState state = projects.get(projectId);
        if (state == null || !enabled()) return null;
        StringBuilder sb = new StringBuilder();
        IndexJob job = state.activeJob;
        if (job != null && !job.isDone()) {
            sb.append("background job ").append(job.id()).append(' ')
                    .append(job.status().name().toLowerCase());
            if (!job.progressLine().isEmpty()) sb.append(" — ").append(job.progressLine());
        } else if (isWatching(projectId)) {
            sb.append("watching for changes");
        } else if (state.watcherFailed) {
            sb.append("periodic refresh (watcher unavailable)");
        } else {
            sb.append("on-demand refresh");
        }
        if (state.lastRefreshCompletedMs > 0) {
            long ago = (System.currentTimeMillis() - state.lastRefreshCompletedMs) / 1000;
            sb.append("; last background pass ").append(ago).append("s ago");
        }
        return sb.toString();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private ProjectState stateFor(String projectId) {
        return projects.computeIfAbsent(projectId, ProjectState::new);
    }

    private Path lookupRoot(String projectId) {
        try {
            if (!Files.isDirectory(LocalCodeIndexer.getIndexDir(projectId))) return null;
            Map<String, Object> stats = indexer.getStats(projectId);
            Object rootPath = stats == null ? null : stats.get("rootPath");
            if (rootPath == null) return null;
            Path root = Path.of(rootPath.toString()).toAbsolutePath().normalize();
            return Files.isDirectory(root) ? root : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static PrintStream silentStream() {
        return new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8);
    }

    /** PrintStream that mirrors the indexer's last progress line into the job. */
    private static PrintStream lastLineStream(IndexJob job) {
        OutputStream sink = new OutputStream() {
            private final StringBuilder line = new StringBuilder();

            @Override
            public void write(int b) {
                if (b == '\n' || b == '\r') {
                    if (line.length() > 0) {
                        job.progressLine = line.length() > 200
                                ? line.substring(0, 200) : line.toString();
                        line.setLength(0);
                    }
                } else if (line.length() < 400) {
                    line.append((char) b);
                }
            }
        };
        return new PrintStream(sink, false, StandardCharsets.UTF_8);
    }

    /**
     * The legacy throttle interval, referenced without widening
     * {@link IndexAutoRefresher}'s API surface.
     */
    private static final class IndexAutoRefresherDefaultInterval {
        static final long VALUE = 30_000;
    }
}
