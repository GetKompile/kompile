/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.codeindex;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches an indexed directory for file changes and triggers incremental
 * re-indexing. Uses {@link WatchService} to detect create/modify/delete
 * events with debouncing to coalesce rapid edits.
 *
 * <p>Usage:
 * <pre>
 *   IndexFileWatcher watcher = new IndexFileWatcher(rootDir, projectId, indexer, out);
 *   watcher.start();       // begins watching in a daemon thread
 *   // ... later ...
 *   watcher.stop();        // stops watching and cleans up
 * </pre>
 *
 * <p>The watcher debounces file change events: after detecting a change,
 * it waits for a quiet period (default 500ms) before triggering re-index.
 * This avoids re-indexing on every keystroke during active editing.</p>
 */
public class IndexFileWatcher {

    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".svn", ".hg", "node_modules", "__pycache__", ".gradle",
            "target", "build", "dist", "out", ".idea", ".vscode", ".settings",
            "vendor", ".tox", ".mypy_cache", ".pytest_cache", ".angular",
            ".next", ".nuxt", "coverage", ".cache", "bin", "obj"
    );

    private static final long DEBOUNCE_MS = 500;
    private static final long MAX_COALESCE_MS = 5000;

    private final Path rootDir;
    private final String projectId;
    private final LocalCodeIndexer indexer;
    private final PrintStream out;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<String> changedFiles = ConcurrentHashMap.newKeySet();
    private final Map<WatchKey, Path> keyPathMap = new ConcurrentHashMap<>();

    private WatchService watchService;
    private Thread watchThread;
    private Thread debounceThread;
    private volatile long lastChangeTime = 0;
    private volatile long firstChangeTime = 0;

    /**
     * Callback interface for index update events.
     */
    public interface WatchListener {
        /** Called when files are detected as changed (before re-index). */
        void onFilesChanged(Set<String> changedPaths);
        /** Called after successful incremental re-index. */
        void onIndexUpdated(LocalCodeIndexer.IndexResult result);
        /** Called on watch/index error. */
        void onError(String message, Exception e);
    }

    private volatile WatchListener listener;

    public IndexFileWatcher(Path rootDir, String projectId,
                            LocalCodeIndexer indexer, PrintStream out) {
        this.rootDir = rootDir.toAbsolutePath().normalize();
        this.projectId = projectId;
        this.indexer = indexer;
        this.out = out;
    }

    public void setListener(WatchListener listener) {
        this.listener = listener;
    }

    /**
     * Start watching for file changes. Non-blocking — runs in daemon threads.
     */
    public void start() throws IOException {
        if (running.getAndSet(true)) return; // already running

        watchService = FileSystems.getDefault().newWatchService();
        registerRecursive(rootDir);

        // Watch thread: polls WatchService for filesystem events
        watchThread = new Thread(this::watchLoop, "code-index-watcher-" + projectId);
        watchThread.setDaemon(true);
        watchThread.start();

        // Debounce thread: coalesces rapid changes before triggering re-index
        debounceThread = new Thread(this::debounceLoop, "code-index-debounce-" + projectId);
        debounceThread.setDaemon(true);
        debounceThread.start();

        out.println("Watching for changes: " + rootDir);
    }

    /**
     * Stop watching and clean up resources.
     */
    public void stop() {
        if (!running.getAndSet(false)) return;

        if (watchThread != null) watchThread.interrupt();
        if (debounceThread != null) debounceThread.interrupt();
        try {
            if (watchService != null) watchService.close();
        } catch (IOException ignored) {}

        // Flush any pending changes
        if (!changedFiles.isEmpty()) {
            triggerIncrementalIndex();
        }

        out.println("Stopped watching: " + rootDir);
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Get files that have changed since the last re-index.
     */
    public Set<String> getPendingChanges() {
        return Collections.unmodifiableSet(new HashSet<>(changedFiles));
    }

    // -----------------------------------------------------------------------
    // Watch loop
    // -----------------------------------------------------------------------

    private void watchLoop() {
        while (running.get()) {
            WatchKey key;
            try {
                key = watchService.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException | ClosedWatchServiceException e) {
                break;
            }

            if (key == null) continue;

            Path dir = keyPathMap.get(key);
            if (dir == null) {
                key.reset();
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                @SuppressWarnings("unchecked")
                WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                Path child = dir.resolve(pathEvent.context());

                if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
                    // New directory — register it for watching
                    try {
                        registerRecursive(child);
                    } catch (IOException ignored) {}
                }

                // Track the changed file (relative to root)
                if (Files.isRegularFile(child) || kind == StandardWatchEventKinds.ENTRY_DELETE) {
                    try {
                        String relPath = rootDir.relativize(child).toString();
                        changedFiles.add(relPath);
                        long now = System.currentTimeMillis();
                        lastChangeTime = now;
                        if (firstChangeTime == 0) firstChangeTime = now;
                    } catch (IllegalArgumentException ignored) {
                        // Path not relative to root — skip
                    }
                }
            }

            boolean valid = key.reset();
            if (!valid) {
                keyPathMap.remove(key);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Debounce loop
    // -----------------------------------------------------------------------

    private void debounceLoop() {
        while (running.get()) {
            try {
                Thread.sleep(100); // Check every 100ms
            } catch (InterruptedException e) {
                break;
            }

            if (changedFiles.isEmpty() || lastChangeTime == 0) continue;

            long now = System.currentTimeMillis();
            long sinceLast = now - lastChangeTime;
            long sinceFirst = now - firstChangeTime;

            // Trigger if: quiet for DEBOUNCE_MS, or changes coalescing > MAX_COALESCE_MS
            if (sinceLast >= DEBOUNCE_MS || sinceFirst >= MAX_COALESCE_MS) {
                triggerIncrementalIndex();
            }
        }
    }

    private void triggerIncrementalIndex() {
        Set<String> pending = new HashSet<>(changedFiles);
        changedFiles.clear();
        lastChangeTime = 0;
        firstChangeTime = 0;

        if (pending.isEmpty()) return;

        WatchListener l = listener;
        if (l != null) l.onFilesChanged(pending);

        try {
            LocalCodeIndexer.IndexResult result = indexer.index(
                    rootDir, projectId, null, null, new PrintStream(java.io.OutputStream.nullOutputStream()));
            if (l != null) l.onIndexUpdated(result);
            out.println("[watch] Re-indexed " + pending.size() + " changed file(s), " +
                    result.entitiesFound() + " entities total");
        } catch (IOException e) {
            if (l != null) l.onError("Re-index failed", e);
            out.println("[watch] Re-index error: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Directory registration
    // -----------------------------------------------------------------------

    private void registerRecursive(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName().toString();
                if (IGNORED_DIRS.contains(dirName)) return FileVisitResult.SKIP_SUBTREE;
                try {
                    WatchKey key = dir.register(watchService,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE);
                    keyPathMap.put(key, dir);
                } catch (IOException ignored) {}
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
