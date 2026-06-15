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

package ai.kompile.cli.main.coordination;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Watches the project directory tree for file modifications and notifies
 * registered agents. When agent A edits a file that agent B has read,
 * agent B receives a notification with the changed file path and change type.
 * Inspired by jcode's swarm file activity tracking.
 */
public class FileWatcherService {

    private final Path rootDir;
    private final WatchService watchService;
    private final Map<WatchKey, Path> watchKeyToDir = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> agentFileReads = new ConcurrentHashMap<>(); // agentId -> set of read file paths
    private final Map<String, Set<String>> agentFileWrites = new ConcurrentHashMap<>(); // agentId -> set of written file paths
    private final List<FileChangeEvent> recentEvents = new CopyOnWriteArrayList<>();
    private final Map<String, Consumer<FileChangeEvent>> notificationCallbacks = new ConcurrentHashMap<>();
    private final ExecutorService watchExecutor;
    private volatile boolean running = false;
    private static final int MAX_RECENT_EVENTS = 500;
    private static final Set<String> IGNORED_DIRS = Set.of(
        ".git", "node_modules", ".kompile", "target", "build", ".gradle", "__pycache__", ".angular"
    );

    public FileWatcherService(Path rootDir) throws IOException {
        this.rootDir = rootDir;
        this.watchService = FileSystems.getDefault().newWatchService();
        this.watchExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "file-watcher");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (running) return;
        running = true;

        // Register root and subdirectories
        try {
            registerRecursive(rootDir);
        } catch (IOException e) {
            System.err.println("[FileWatcher] Error registering directories: " + e.getMessage());
        }

        // Start watching
        watchExecutor.submit(this::watchLoop);
    }

    public void stop() {
        running = false;
        watchExecutor.shutdownNow();
        try { watchService.close(); } catch (IOException ignored) {}
    }

    /**
     * Record that an agent has read a file. Used to determine who to notify on changes.
     */
    public void recordRead(String agentId, String filePath) {
        agentFileReads.computeIfAbsent(agentId, k -> ConcurrentHashMap.newKeySet()).add(filePath);
    }

    /**
     * Record that an agent has written/edited a file.
     */
    public void recordWrite(String agentId, String filePath) {
        agentFileWrites.computeIfAbsent(agentId, k -> ConcurrentHashMap.newKeySet()).add(filePath);
    }

    /**
     * Register a callback to receive notifications for a specific agent.
     */
    public void registerCallback(String agentId, Consumer<FileChangeEvent> callback) {
        notificationCallbacks.put(agentId, callback);
    }

    public void unregisterCallback(String agentId) {
        notificationCallbacks.remove(agentId);
    }

    /**
     * Get recent file change events.
     */
    public List<FileChangeEvent> getRecentEvents(int limit) {
        int size = recentEvents.size();
        int from = Math.max(0, size - limit);
        return new ArrayList<>(recentEvents.subList(from, size));
    }

    /**
     * Get notifications pending for a specific agent (files they've read that were changed by others).
     */
    public List<FileChangeEvent> getPendingNotifications(String agentId) {
        Set<String> readFiles = agentFileReads.getOrDefault(agentId, Set.of());
        if (readFiles.isEmpty()) return Collections.emptyList();

        List<FileChangeEvent> pending = new ArrayList<>();
        for (FileChangeEvent event : recentEvents) {
            if (!event.agentId.equals(agentId) && readFiles.contains(event.filePath)) {
                pending.add(event);
            }
        }
        return pending;
    }

    /**
     * Clear notifications for an agent (after they've been consumed).
     */
    public void clearNotifications(String agentId, List<FileChangeEvent> consumed) {
        // Remove from reads the files that have been acknowledged
        Set<String> readFiles = agentFileReads.get(agentId);
        if (readFiles != null) {
            for (FileChangeEvent event : consumed) {
                readFiles.remove(event.filePath);
            }
        }
    }

    public void deregisterAgent(String agentId) {
        agentFileReads.remove(agentId);
        agentFileWrites.remove(agentId);
        notificationCallbacks.remove(agentId);
    }

    public String statusSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("FileWatcher Status:\n");
        sb.append("  Running: ").append(running).append("\n");
        sb.append("  Watched dirs: ").append(watchKeyToDir.size()).append("\n");
        sb.append("  Tracked agents: ").append(agentFileReads.size()).append("\n");
        sb.append("  Recent events: ").append(recentEvents.size()).append("\n");
        for (Map.Entry<String, Set<String>> entry : agentFileReads.entrySet()) {
            sb.append("  Agent ").append(entry.getKey())
              .append(": ").append(entry.getValue().size()).append(" tracked files\n");
        }
        return sb.toString();
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private void watchLoop() {
        while (running) {
            try {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) continue;

                Path dir = watchKeyToDir.get(key);
                if (dir == null) { key.cancel(); continue; }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path changed = dir.resolve(pathEvent.context());
                    String absPath = changed.toAbsolutePath().toString();

                    // Skip ignored paths
                    if (shouldIgnore(changed)) continue;

                    // If a new directory was created, register it
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
                        try { registerRecursive(changed); } catch (IOException ignored) {}
                    }

                    // Determine which agent caused this change (if tracked)
                    String causingAgent = "unknown";
                    for (Map.Entry<String, Set<String>> entry : agentFileWrites.entrySet()) {
                        if (entry.getValue().remove(absPath)) {
                            causingAgent = entry.getKey();
                            break;
                        }
                    }

                    FileChangeEvent fce = new FileChangeEvent(
                        absPath, kindToString(kind), causingAgent, Instant.now()
                    );
                    recentEvents.add(fce);

                    // Trim old events
                    while (recentEvents.size() > MAX_RECENT_EVENTS) {
                        recentEvents.remove(0);
                    }

                    // Notify agents that have read this file
                    for (Map.Entry<String, Set<String>> entry : agentFileReads.entrySet()) {
                        String agentId = entry.getKey();
                        if (agentId.equals(causingAgent)) continue; // Don't notify the agent that made the change
                        if (entry.getValue().contains(absPath)) {
                            Consumer<FileChangeEvent> callback = notificationCallbacks.get(agentId);
                            if (callback != null) {
                                try { callback.accept(fce); } catch (Exception ignored) {}
                            }
                        }
                    }
                }

                boolean valid = key.reset();
                if (!valid) {
                    watchKeyToDir.remove(key);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }
        }
    }

    private void registerRecursive(Path start) throws IOException {
        if (!Files.isDirectory(start)) return;
        Files.walkFileTree(start, EnumSet.noneOf(FileVisitOption.class), 5,
            new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (shouldIgnore(dir)) return FileVisitResult.SKIP_SUBTREE;
                    try {
                        WatchKey key = dir.register(watchService,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE);
                        watchKeyToDir.put(key, dir);
                    } catch (IOException ignored) {}
                    return FileVisitResult.CONTINUE;
                }
            });
    }

    private boolean shouldIgnore(Path path) {
        for (Path component : path) {
            if (IGNORED_DIRS.contains(component.toString())) return true;
        }
        return false;
    }

    private static String kindToString(WatchEvent.Kind<?> kind) {
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) return "created";
        if (kind == StandardWatchEventKinds.ENTRY_MODIFY) return "modified";
        if (kind == StandardWatchEventKinds.ENTRY_DELETE) return "deleted";
        return "unknown";
    }

    // ── Data Types ────────────────────────────────────────────────────────

    public static class FileChangeEvent {
        public final String filePath;
        public final String changeType;
        public final String agentId;
        public final Instant timestamp;

        public FileChangeEvent(String filePath, String changeType, String agentId, Instant timestamp) {
            this.filePath = filePath;
            this.changeType = changeType;
            this.agentId = agentId;
            this.timestamp = timestamp;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s %s (by %s)", timestamp, changeType, filePath, agentId);
        }
    }
}
