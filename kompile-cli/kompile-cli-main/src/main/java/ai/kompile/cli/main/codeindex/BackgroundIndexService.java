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

import ai.kompile.cli.main.chat.tools.grounding.CodeGraphLearningRunner;

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
import java.util.concurrent.ScheduledFuture;
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
 *   <li>{@code KOMPILE_CODE_INDEX_LOCAL_WRITE_JOIN_MS} — reads only pay the
 *       freshness join within this window after a write from THIS process
 *       (default 30000); outside it, busy projects just annotate.</li>
 *   <li>{@code KOMPILE_CODE_INDEX_BACKSTOP_SECONDS} — backstop sweep period
 *       (default 300; 0 disables).</li>
 *   <li>{@code KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS} — quiet period before
 *       configured graph learning (default 250).</li>
 *   <li>{@code KOMPILE_CODE_INDEX_LEARNING_MAX_WAIT_MS} — maximum learning
 *       debounce wait under continuous updates (default 2000).</li>
 * </ul>
 */
public final class BackgroundIndexService {

    private static final long WRITE_DEBOUNCE_MS = 750;
    private static final long LEARNING_DEBOUNCE_MS = 250;
    private static final long LEARNING_MAX_WAIT_MS = 2_000;
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
        private volatile LocalCodeKGraphPublisher.ProjectionResult projection;
        private volatile CodeGraphLearningRunner.ConfiguredResult learning;
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
        public LocalCodeKGraphPublisher.ProjectionResult projection() { return projection; }
        public CodeGraphLearningRunner.ConfiguredResult learning() { return learning; }
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
        volatile String includePatterns;
        volatile String excludePatterns;
        volatile IndexFileWatcher watcher;
        volatile boolean watcherFailed;
        final AtomicBoolean watcherStartQueued = new AtomicBoolean();
        volatile long lastTouchedMs;
        volatile boolean refreshRunning;
        /** Last time THIS process wrote into the project (read-your-writes window). */
        volatile long lastLocalWriteMs;
        /** Bumped on every write notification; refresh passes snapshot it. */
        final AtomicLong writeSeq = new AtomicLong();
        /** writeSeq value covered by the last completed refresh. */
        volatile long cleanSeq;
        volatile boolean refreshQueued;
        volatile boolean projectionDirty;
        volatile boolean projectionQueued;
        volatile boolean projectionRunning;
        volatile boolean projectionRescheduleRequested;
        final ArrayDeque<IndexJob> projectionJobs = new ArrayDeque<>();
        List<IndexJob> runningProjectionJobs = List.of();
        final AtomicReference<String> lastNote = new AtomicReference<>();
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

    private static final class LearningBatch {
        final Path graphPath;
        Path projectRoot;
        final List<IndexJob> pendingJobs = new ArrayList<>();
        List<IndexJob> runningJobs = List.of();
        ScheduledFuture<?> scheduled;
        boolean dirty;
        boolean running;
        long firstDirtyMs;
        long lastDirtyMs;

        LearningBatch(Path graphPath) { this.graphPath = graphPath; }
    }

    private final LocalCodeIndexer indexer = new LocalCodeIndexer();
    private final ScheduledThreadPoolExecutor executor;
    private final ScheduledThreadPoolExecutor projectionExecutor;
    private final ScheduledThreadPoolExecutor learningExecutor;
    private volatile ProjectionPublisher projectionPublisher = LocalCodeKGraphPublisher::publish;
    private volatile ConfiguredLearning configuredLearning = BackgroundIndexService::runConfiguredLearning;
    private final ConcurrentHashMap<String, ProjectState> projects = new ConcurrentHashMap<>();
    /** Canonical projection graph path → one serialized/coalesced learning batch. */
    private final ConcurrentHashMap<Path, LearningBatch> learningBatches = new ConcurrentHashMap<>();
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

        ScheduledThreadPoolExecutor projections = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "code-index-projection");
            t.setDaemon(true);
            return t;
        });
        projections.setKeepAliveTime(30, TimeUnit.SECONDS);
        projections.allowCoreThreadTimeOut(true);
        projections.setRemoveOnCancelPolicy(true);
        this.projectionExecutor = projections;

        ScheduledThreadPoolExecutor learning = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "code-index-learning");
            t.setDaemon(true);
            return t;
        });
        learning.setKeepAliveTime(30, TimeUnit.SECONDS);
        learning.allowCoreThreadTimeOut(true);
        learning.setRemoveOnCancelPolicy(true);
        this.learningExecutor = learning;
    }

    private void startBackstop() {
        long period = longConfig("KOMPILE_CODE_INDEX_BACKSTOP_SECONDS", 300);
        if (period > 0 && enabled()) {
            executor.scheduleWithFixedDelay(this::backstopSweep, period, period, TimeUnit.SECONDS);
        }
    }

    private void close() {
        if (!closed.compareAndSet(false, true)) return;
        List<IndexJob> strandedProjectionJobs = new ArrayList<>();
        for (ProjectState state : projects.values()) {
            IndexFileWatcher w = state.watcher;
            if (w != null) {
                try { w.stop(); } catch (Exception ignored) {}
            }
            synchronized (state) {
                strandedProjectionJobs.addAll(state.projectionJobs);
                strandedProjectionJobs.addAll(state.runningProjectionJobs);
                state.projectionJobs.clear();
                state.runningProjectionJobs = List.of();
            }
        }
        assignLearningFailure(strandedProjectionJobs, "graph projection cancelled");
        for (LearningBatch batch : learningBatches.values()) cancelLearningBatch(batch);
        learningBatches.clear();
        executor.shutdownNow();
        projectionExecutor.shutdownNow();
        learningExecutor.shutdownNow();
    }

    @FunctionalInterface
    interface ProjectionPublisher {
        LocalCodeKGraphPublisher.ProjectionResult publish(
                Path root, String projectId, String includes, String excludes) throws Exception;
    }

    @FunctionalInterface
    interface ConfiguredLearning {
        CodeGraphLearningRunner.ConfiguredResult run(
                Path projectRoot, Path graphPath, String trigger) throws Exception;
    }

    /** Package-private test seam for proving projection cannot starve index jobs. */
    void setProjectionPublisherForTests(ProjectionPublisher publisher) {
        projectionPublisher = publisher == null ? LocalCodeKGraphPublisher::publish : publisher;
    }

    /** Package-private test seam for exercising learning scheduling without model calls. */
    void setConfiguredLearningForTests(ConfiguredLearning learning) {
        configuredLearning = learning == null
                ? BackgroundIndexService::runConfiguredLearning : learning;
    }

    /** Package-private test seam for queueing a published projection directly. */
    void scheduleLearningForTests(Path root, String projectId, Path graphPath, IndexJob... jobs) {
        ProjectState state = stateFor(projectId);
        state.root = root;
        LocalCodeKGraphPublisher.ProjectionResult projection =
                new LocalCodeKGraphPublisher.ProjectionResult(graphPath, null, null, 0, 0, 0);
        List<IndexJob> represented = jobs == null ? List.of() : List.of(jobs);
        for (IndexJob job : represented) job.projection = projection;
        scheduleLearning(state, projection, represented);
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
    private long localWriteJoinWindowMs() { return longConfig("KOMPILE_CODE_INDEX_LOCAL_WRITE_JOIN_MS", 30_000); }

    // ── Background index jobs ───────────────────────────────────────────────

    /**
     * Submit a full/incremental index pass for {@code root} as a background
     * job. If a job for the project is already queued or running it is
     * returned instead of enqueuing a duplicate.
     */
    public IndexJob submitIndexJob(Path root, String projectId, String includes,
                                   String excludes, boolean force) {
        ProjectState state = stateFor(projectId);
        synchronized (state) {
            IndexJob existing = state.activeJob;
            if (existing != null && !existing.isDone()) return existing;
            state.root = root;
            state.includePatterns = includes;
            state.excludePatterns = excludes;
            state.lastTouchedMs = System.currentTimeMillis();
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
            rememberIndexedRoot(Path.of(job.rootPath()), job.projectId());
            scheduleProjection(state, job);
        } catch (Exception e) {
            if (job.result != null) state.projectionDirty = true;
            job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            job.status = JobStatus.FAILED;
            CodeIndexDiagnostics.alert("[code-index] background index failed for '"
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
            IndexAutoRefresher.RefreshOutcome outcome = IndexAutoRefresher.refresh(
                    callerIndexer != null ? callerIndexer : indexer, projectId);
            if (outcome.changed()) {
                Path root = lookupRoot(projectId);
                if (root != null) {
                    try {
                        Map<String, Object> stats = indexer.getStats(projectId);
                        LocalCodeKGraphPublisher.publish(root, projectId,
                                stringValue(stats.get("includePatterns")),
                                stringValue(stats.get("excludePatterns")));
                    } catch (Exception e) {
                        CodeIndexDiagnostics.alert("[code-index] inline KGraph publication failed for '"
                                + projectId + "': " + diagnosticMessage(e));
                    }
                }
            }
            return outcome.note();
        }
        try {
            ProjectState state = stateFor(projectId);
            state.lastTouchedMs = System.currentTimeMillis();
            if (state.root == null && !loadStateMetadata(state)) return null;

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

            // Read-your-writes is a contract with THIS process's own writes.
            // Refreshes triggered by other processes' edits (watcher events on a
            // shared tree) used to make every read block up to readWaitMs while
            // the project churned — join only inside the local-write window and
            // otherwise just annotate that results may lag.
            boolean recentLocalWrite = state.lastLocalWriteMs > 0
                    && System.currentTimeMillis() - state.lastLocalWriteMs < localWriteJoinWindowMs();
            String inProgress = null;
            if (state.busy() && (!recentLocalWrite || !awaitQuiet(state, readWaitMs()))) {
                IndexJob job = state.activeJob;
                inProgress = job != null && !job.isDone()
                        ? "[background index job " + job.id() + " running — results may lag]"
                        : "[index refresh running in background — results may lag recent edits]";
            }

            String note = consumeNote(state);
            if (note == null) return inProgress;
            return inProgress == null ? note : note + "\n" + inProgress;
        } catch (Exception e) {
            CodeIndexDiagnostics.alert("[code-index] background freshness skipped for '"
                    + projectId + "': " + diagnosticMessage(e));
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
        return state.lastNote.getAndSet(null);
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
            if (state.root == null && !loadStateMetadata(state)) return;
            state.lastTouchedMs = System.currentTimeMillis();
            state.lastLocalWriteMs = state.lastTouchedMs;
            state.writeSeq.incrementAndGet();
            scheduleRefresh(state, 0);
        } catch (Exception e) {
            CodeIndexDiagnostics.alert("[code-index] write notification dropped: "
                    + diagnosticMessage(e));
        }
    }

    String projectForPath(Path file) {
        Path candidateFile = canonicalPath(file);
        Map<Path, String> cache = rootCache;
        long now = System.currentTimeMillis();
        if (now - rootCacheBuiltMs > ROOT_CACHE_TTL_MS) {
            cache = rebuildRootCache();
        }
        String best = null;
        int bestDepth = -1;
        for (Map.Entry<Path, String> entry : cache.entrySet()) {
            Path root = entry.getKey();
            if (candidateFile.startsWith(root) && root.getNameCount() > bestDepth) {
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
                    Path root = canonicalPath(Path.of(rootPath.toString()));
                    if (!Files.isDirectory(root)) continue;
                    String candidate = projectId.toString();
                    String existing = fresh.get(root);
                    if (existing == null || preferRootOwner(root, candidate, existing)) {
                        fresh.put(root, candidate);
                    }
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

    /** Make a completed local index immediately visible to same-process write hooks. */
    private synchronized void rememberIndexedRoot(Path rootPath, String projectId) {
        if (rootPath == null || projectId == null || projectId.isBlank()) return;
        Path root = canonicalPath(rootPath);
        Map<Path, String> fresh = new LinkedHashMap<>(rootCache);
        fresh.entrySet().removeIf(entry -> projectId.equals(entry.getValue())
                && !root.equals(entry.getKey()));
        String existing = fresh.get(root);
        if (existing == null || preferRootOwner(root, projectId, existing)) {
            fresh.put(root, projectId);
        }
        rootCache = fresh;
        rootCacheBuiltMs = System.currentTimeMillis();
    }

    private static boolean preferRootOwner(Path root, String candidate, String existing) {
        try {
            String canonical = ProjectIdResolver.resolve(null, root).projectId();
            if (candidate.equals(canonical)) return true;
            if (existing.equals(canonical)) return false;
        } catch (RuntimeException ignored) {
            // Fall through to a deterministic choice when project metadata is unreadable.
        }
        return candidate.compareTo(existing) < 0;
    }

    private static Path canonicalPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException missing) {
            Path parent = absolute.getParent();
            if (parent != null) {
                try {
                    return parent.toRealPath().resolve(absolute.getFileName()).normalize();
                } catch (IOException ignored) { }
            }
            return absolute;
        }
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
            IndexAutoRefresher.RefreshOutcome outcome = IndexAutoRefresher.refresh(
                    indexer, state.projectId, throttleMs,
                    state.includePatterns, state.excludePatterns);
            String note = outcome.note();
            if (note != null) {
                state.lastNote.set(note.replace("[index auto-refreshed:",
                        "[index auto-refreshed in background:"));
            }
            if (!outcome.successful()) {
                if (throttleMs == 0) state.cleanSeq = Math.max(state.cleanSeq, seqBefore);
                state.lastRefreshCompletedMs = System.currentTimeMillis();
                return;
            }
            boolean backstopCheck = throttleMs == IndexAutoRefresherDefaultInterval.VALUE;
            if (state.root != null && (outcome.changed()
                    || throttleMs == 0 && !outcome.fileFailures()
                    || state.projectionDirty || backstopCheck)) {
                scheduleProjection(state, null);
            }
            // A bypass pass (throttle 0) always runs, so it certifies every
            // write it saw as indexed. A throttled pass may have been skipped
            // and must not clear dirty state.
            if (throttleMs == 0) {
                state.cleanSeq = Math.max(state.cleanSeq, seqBefore);
            }
            state.lastRefreshCompletedMs = System.currentTimeMillis();
        } catch (Exception e) {
            state.projectionDirty = true;
            CodeIndexDiagnostics.alert("[code-index] background refresh failed for '"
                    + state.projectId + "': " + diagnosticMessage(e));
            // A persistent projection failure must not keep the project dirty and
            // reschedule the same alert every debounce interval. A later write or
            // periodic backstop will retry without wedging reads in the meantime.
            state.cleanSeq = Math.max(state.cleanSeq, seqBefore);
        } finally {
            synchronized (state) {
                state.refreshRunning = false;
                state.notifyAll();
            }
            if (state.dirty()) scheduleRefresh(state, 0);
        }
    }

    // ── KGraph projection scheduling ───────────────────────────────────────

    /**
     * Projection is local but independent from SQLite index maintenance. A slow
     * archive rewrite must never occupy either index worker or the watcher
     * debounce thread. Repeated writes coalesce into one follow-up projection.
     */
    private void scheduleProjection(ProjectState state, IndexJob job) {
        if (closed.get()) {
            if (job != null && job.learning == null) {
                job.learning = learningFailure("graph projection cancelled");
            }
            return;
        }
        synchronized (state) {
            state.projectionDirty = true;
            if (job != null && !state.projectionJobs.contains(job)) {
                state.projectionJobs.addLast(job);
            }
            if (state.projectionRunning) {
                state.projectionRescheduleRequested = true;
                return;
            }
            if (state.projectionQueued) return;
            state.projectionQueued = true;
        }
        try {
            projectionExecutor.execute(() -> runProjection(state));
        } catch (RuntimeException failure) {
            synchronized (state) {
                state.projectionQueued = false;
                state.notifyAll();
            }
            CodeIndexDiagnostics.alert("[code-index] graph projection scheduling failed for '"
                    + state.projectId + "': " + diagnosticMessage(failure));
        }
    }

    private void scheduleLearning(ProjectState state,
                                  LocalCodeKGraphPublisher.ProjectionResult projection,
                                  List<IndexJob> jobs) {
        Path graphPath = canonicalPath(projection.graphPath());
        LearningBatch batch = learningBatches.computeIfAbsent(graphPath, LearningBatch::new);
        List<IndexJob> stranded = List.of();
        synchronized (batch) {
            if (closed.get()) {
                stranded = jobs == null ? List.of() : List.copyOf(jobs);
            } else {
                batch.projectRoot = state.root;
                long now = System.currentTimeMillis();
                if (batch.firstDirtyMs == 0) batch.firstDirtyMs = now;
                batch.lastDirtyMs = now;
                batch.dirty = true;
                if (jobs != null) {
                    for (IndexJob job : jobs) {
                        if (job != null && !batch.pendingJobs.contains(job)) {
                            batch.pendingJobs.add(job);
                        }
                    }
                }
                if (!batch.running && batch.scheduled == null && !scheduleLearningTaskLocked(batch)) {
                    stranded = drainPendingJobs(batch);
                }
            }
        }
        if (!stranded.isEmpty()) assignLearningFailure(stranded, "learning lane closed");
    }

    private boolean scheduleLearningTaskLocked(LearningBatch batch) {
        if (!batch.dirty || batch.scheduled != null || closed.get()) return true;
        long now = System.currentTimeMillis();
        if (batch.firstDirtyMs == 0) batch.firstDirtyMs = now;
        if (batch.lastDirtyMs == 0) batch.lastDirtyMs = batch.firstDirtyMs;
        long quiet = Math.max(0, learningDebounceMs());
        long maximum = Math.max(quiet, learningMaxWaitMs());
        long quietDeadline = batch.lastDirtyMs + quiet;
        long maximumDeadline = batch.firstDirtyMs + maximum;
        long delay = Math.max(0, Math.min(quietDeadline, maximumDeadline) - now);
        try {
            batch.scheduled = learningExecutor.schedule(
                    () -> runLearning(batch), delay, TimeUnit.MILLISECONDS);
            return true;
        } catch (RuntimeException failure) {
            batch.scheduled = null;
            batch.dirty = false;
            batch.firstDirtyMs = 0;
            batch.lastDirtyMs = 0;
            CodeIndexDiagnostics.alert("[code-index] graph learning scheduling failed for '"
                    + batch.graphPath + "': " + diagnosticMessage(failure));
            return false;
        }
    }

    private void runLearning(LearningBatch batch) {
        final Path projectRoot;
        final List<IndexJob> jobs;
        boolean due = false;
        List<IndexJob> stranded = List.of();
        synchronized (batch) {
            batch.scheduled = null;
            if (closed.get() || !batch.dirty) return;
            long now = System.currentTimeMillis();
            long quiet = Math.max(0, learningDebounceMs());
            long maximum = Math.max(quiet, learningMaxWaitMs());
            long quietDeadline = batch.lastDirtyMs + quiet;
            long maximumDeadline = batch.firstDirtyMs + maximum;
            if (now < Math.min(quietDeadline, maximumDeadline)) {
                if (!scheduleLearningTaskLocked(batch)) stranded = drainPendingJobs(batch);
                projectRoot = null;
                jobs = List.of();
            } else {
                due = true;
                batch.running = true;
                batch.dirty = false;
                batch.firstDirtyMs = 0;
                batch.lastDirtyMs = 0;
                projectRoot = batch.projectRoot;
                jobs = new ArrayList<>(batch.pendingJobs);
                batch.pendingJobs.clear();
                batch.runningJobs = jobs;
            }
        }
        if (!stranded.isEmpty()) {
            assignLearningFailure(stranded, "learning lane closed");
        }
        if (!due) return;

        CodeGraphLearningRunner.ConfiguredResult result;
        try {
            if (projectRoot == null) throw new IOException("project root unavailable");
            result = configuredLearning.run(projectRoot, batch.graphPath,
                    CodeGraphReasoningConfig.TRIGGER_BUILD);
            if (result == null) result = learningFailure("configured learning returned no result");
            if (result.error() != null && !closed.get()) {
                CodeIndexDiagnostics.alert("[code-index] configured code-graph learning failed for '"
                        + batch.graphPath + "': " + result.error());
            }
        } catch (Exception failure) {
            boolean interrupted = closed.get() || Thread.currentThread().isInterrupted()
                    || failure instanceof InterruptedException
                    || failure instanceof java.nio.channels.ClosedByInterruptException;
            result = learningFailure(interrupted ? "learning interrupted" : diagnosticMessage(failure));
            if (!interrupted) {
                CodeIndexDiagnostics.alert("[code-index] configured code-graph learning failed for '"
                        + batch.graphPath + "': " + diagnosticMessage(failure));
            }
        }
        for (IndexJob job : jobs) {
            if (job.learning == null) job.learning = result;
        }

        stranded = List.of();
        synchronized (batch) {
            batch.runningJobs = List.of();
            batch.running = false;
            if (!closed.get() && "SUPERSEDED".equals(result.status())) {
                batch.dirty = true;
                long now = System.currentTimeMillis();
                if (batch.firstDirtyMs == 0) batch.firstDirtyMs = now;
                if (batch.lastDirtyMs == 0) batch.lastDirtyMs = now;
            }
            if (closed.get()) {
                stranded = drainPendingJobs(batch);
            } else if (batch.dirty || !batch.pendingJobs.isEmpty()) {
                if (!scheduleLearningTaskLocked(batch)) {
                    stranded = drainPendingJobs(batch);
                }
            }
        }
        if (!stranded.isEmpty()) assignLearningFailure(stranded, "learning lane closed");
    }

    private static List<IndexJob> drainPendingJobs(LearningBatch batch) {
        List<IndexJob> jobs = new ArrayList<>(batch.pendingJobs);
        batch.pendingJobs.clear();
        batch.dirty = false;
        batch.firstDirtyMs = 0;
        batch.lastDirtyMs = 0;
        return jobs;
    }

    private void cancelLearningBatch(LearningBatch batch) {
        List<IndexJob> jobs;
        synchronized (batch) {
            if (batch.scheduled != null) batch.scheduled.cancel(false);
            jobs = new ArrayList<>(batch.pendingJobs);
            jobs.addAll(batch.runningJobs);
            batch.pendingJobs.clear();
            batch.runningJobs = List.of();
            batch.scheduled = null;
            batch.dirty = false;
            batch.firstDirtyMs = 0;
            batch.lastDirtyMs = 0;
            batch.running = false;
        }
        assignLearningFailure(jobs, "learning cancelled");
    }

    private static void assignLearningFailure(List<IndexJob> jobs, String message) {
        if (jobs == null || jobs.isEmpty()) return;
        CodeGraphLearningRunner.ConfiguredResult failure = learningFailure(message);
        for (IndexJob job : jobs) {
            if (job != null && job.learning == null) job.learning = failure;
        }
    }

    private static CodeGraphLearningRunner.ConfiguredResult learningFailure(String message) {
        return new CodeGraphLearningRunner.ConfiguredResult(null, message);
    }

    private static CodeGraphLearningRunner.ConfiguredResult runConfiguredLearning(
            Path projectRoot, Path graphPath, String trigger) {
        return new CodeGraphLearningRunner().runConfigured(projectRoot, graphPath, trigger);
    }

    private long learningDebounceMs() {
        return longConfig("KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS", LEARNING_DEBOUNCE_MS);
    }

    private long learningMaxWaitMs() {
        return longConfig("KOMPILE_CODE_INDEX_LEARNING_MAX_WAIT_MS", LEARNING_MAX_WAIT_MS);
    }

    private void runProjection(ProjectState state) {
        final long seqBefore;
        final List<IndexJob> targetJobs;
        synchronized (state) {
            state.projectionQueued = false;
            if (!state.projectionDirty || state.root == null) {
                state.notifyAll();
                return;
            }
            state.projectionRunning = true;
            seqBefore = state.writeSeq.get();
            targetJobs = new ArrayList<>(state.projectionJobs);
            state.projectionJobs.clear();
            state.runningProjectionJobs = targetJobs;
        }

        boolean successful = false;
        try {
            LocalCodeKGraphPublisher.ProjectionResult projection = projectionPublisher.publish(
                    state.root, state.projectId, state.includePatterns, state.excludePatterns);
            for (IndexJob targetJob : targetJobs) targetJob.projection = projection;
            if (projection != null && projection.graphPath() != null) {
                scheduleLearning(state, projection, targetJobs);
            }
            successful = true;
        } catch (Exception failure) {
            boolean interrupted = closed.get() || Thread.currentThread().isInterrupted()
                    || failure instanceof InterruptedException
                    || failure instanceof java.nio.channels.ClosedByInterruptException
                    || failure instanceof java.nio.channels.FileLockInterruptionException;
            if (!interrupted) {
                CodeIndexDiagnostics.alert("[code-index] background KGraph publication failed for '"
                        + state.projectId + "': " + diagnosticMessage(failure));
            }
        } finally {
            boolean rerun;
            synchronized (state) {
                if (!successful && !closed.get() && !targetJobs.isEmpty()) {
                    for (int i = targetJobs.size() - 1; i >= 0; i--) {
                        state.projectionJobs.addFirst(targetJobs.get(i));
                    }
                }
                boolean changedDuringProjection = state.writeSeq.get() != seqBefore
                        || state.projectionRescheduleRequested;
                if (successful && !changedDuringProjection) {
                    state.projectionDirty = false;
                }
                state.projectionRunning = false;
                state.runningProjectionJobs = List.of();
                rerun = changedDuringProjection;
                state.projectionRescheduleRequested = false;
                state.notifyAll();
            }
            // Retry immediately only when new indexed work arrived during a
            // successful/failed projection. Persistent failures otherwise wait
            // for the periodic backstop or a later write instead of spinning.
            if (rerun && !closed.get()) scheduleProjection(state, null);
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
                        state.includePatterns, state.excludePatterns, silentStream());
                watcher.setListener(new IndexFileWatcher.WatchListener() {
                    @Override
                    public void onFilesChanged(Set<String> changedPaths) {
                        state.writeSeq.incrementAndGet();
                        state.refreshRunning = true;
                    }

                    @Override
                    public void onIndexUpdated(LocalCodeIndexer.IndexResult result) {
                        int attempted = Math.max(0,
                                result.filesProcessed() - result.filesSkipped());
                        int failed = Math.min(attempted, Math.max(0, result.errors()));
                        int reindexed = Math.max(0, attempted - failed);
                        if (reindexed > 0 || result.filesDeleted() > 0 || failed > 0) {
                            StringBuilder note = new StringBuilder(
                                    "[index auto-refreshed in background: ")
                                    .append(reindexed).append(" file")
                                    .append(reindexed == 1 ? "" : "s").append(" re-indexed");
                            if (result.filesDeleted() > 0) {
                                note.append(", ").append(result.filesDeleted()).append(" deleted");
                            }
                            if (failed > 0) {
                                note.append(", ").append(failed).append(" failed");
                            }
                            state.lastNote.set(note.append(']').toString());
                            if (reindexed > 0 || result.filesDeleted() > 0) {
                                scheduleProjection(state, null);
                            }
                        }
                        finishWatcherPass(true);
                    }

                    @Override
                    public void onError(String message, Exception e) {
                        finishWatcherPass(false);
                    }

                    private void finishWatcherPass(boolean successful) {
                        IndexFileWatcher w = state.watcher;
                        if (successful && (w == null || w.getPendingChanges().isEmpty())) {
                            state.cleanSeq = state.writeSeq.get();
                        }
                        state.lastRefreshCompletedMs = System.currentTimeMillis();
                        synchronized (state) {
                            state.refreshRunning = false;
                            state.notifyAll();
                        }
                        if (!successful) scheduleRefresh(state, WRITE_DEBOUNCE_MS);
                    }
                });
                watcher.start();
                state.watcher = watcher;
                return true;
            } catch (Exception e) {
                // Typically inotify watch exhaustion — degrade to polling.
                state.watcherFailed = true;
                CodeIndexDiagnostics.alert("[code-index] watcher unavailable for '"
                        + state.projectId + "' (falling back to periodic refresh): "
                        + diagnosticMessage(e));
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
        if (state.projectionRunning) sb.append("; graph projection running");
        else if (state.projectionQueued) sb.append("; graph projection queued");
        else if (state.projectionDirty) sb.append("; graph projection pending");
        if (state.lastRefreshCompletedMs > 0) {
            long ago = (System.currentTimeMillis() - state.lastRefreshCompletedMs) / 1000;
            sb.append("; last background pass ").append(ago).append("s ago");
        }
        sb.append("; database ").append(IndexMaintenance.status(projectId));
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

    private boolean loadStateMetadata(ProjectState state) {
        try {
            Map<String, Object> stats = indexer.getStats(state.projectId);
            Object rootPath = stats.get("rootPath");
            if (rootPath == null) return false;
            Path root = Path.of(rootPath.toString()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) return false;
            state.root = root;
            state.includePatterns = stringValue(stats.get("includePatterns"));
            state.excludePatterns = stringValue(stats.get("excludePatterns"));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String stringValue(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static String diagnosticMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank()
                ? error == null ? "unknown failure" : error.getClass().getSimpleName()
                : message;
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
