/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.insights.Panel;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/**
 * Keeps the session panel current between tool calls. It asks for a refresh when a file the
 * panel reads changes, and on a timer counted from the last refresh: every {@link #LIVE_TICK}
 * while some topic changes on its own (a running crawl), every {@link #IDLE_TICK} otherwise.
 *
 * <p>A burst of writes asks once: the watcher waits {@link #SETTLE} after the first change, and
 * at least {@link #MIN_GAP} between the refreshes it asks for. Without a watch service, or for a
 * directory that cannot be watched (past the inotify limit), the timer and the refresh after
 * each tool call still keep the panel current.</p>
 */
final class PanelWatcher implements AutoCloseable {

    static final Duration LIVE_TICK = Duration.ofSeconds(5);
    static final Duration IDLE_TICK = Duration.ofSeconds(30);
    static final Duration SETTLE = Duration.ofMillis(250);
    static final Duration MIN_GAP = Duration.ofSeconds(1);

    private final Runnable onChange;
    private final long liveTickNanos;
    private final long idleTickNanos;
    private final long settleNanos;
    private final long minGapNanos;
    private final Object lock = new Object();

    /** The last panel's watches, by directory. Guarded by lock. */
    private final Map<Path, List<Panel.Watch>> watches = new HashMap<>();
    /** The directories registered with {@link #service}. Guarded by lock. */
    private final Map<Path, WatchKey> keys = new HashMap<>();
    /** Created for the first watch; null after close. Guarded by lock. */
    private WatchService service;
    /** True when the platform could not create a watch service. Guarded by lock. */
    private boolean timerOnly;
    /** Guarded by lock. */
    private boolean live;
    /** {@link System#nanoTime()} of the last refresh, reported or asked for. Guarded by lock. */
    private long lastRefresh;
    /** Guarded by lock. */
    private Thread thread;
    /** Guarded by lock. */
    private boolean closed;

    PanelWatcher(Runnable onChange) {
        this(onChange, LIVE_TICK, IDLE_TICK, SETTLE, MIN_GAP);
    }

    PanelWatcher(Runnable onChange, Duration liveTick, Duration idleTick, Duration settle, Duration minGap) {
        this.onChange = Objects.requireNonNull(onChange, "onChange");
        this.liveTickNanos = Math.max(1L, liveTick.toNanos());
        this.idleTickNanos = Math.max(liveTickNanos, idleTick.toNanos());
        this.settleNanos = Math.max(0L, settle.toNanos());
        this.minGapNanos = Math.max(0L, minGap.toNanos());
    }

    /**
     * Follows the panel just shown: its {@code panelWatches} from now on, and its timer restarted.
     * Called after every refresh; the first call starts the watcher's thread.
     */
    void update(List<Panel.Watch> panelWatches, boolean panelLive) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            live = panelLive;
            lastRefresh = System.nanoTime();
            register(panelWatches == null ? List.of() : panelWatches);
            if (thread == null) {
                thread = new Thread(this::run, "kompile-session-panel-watch");
                thread.setDaemon(true);
                thread.start();
            }
            lock.notifyAll();
        }
    }

    /** True while {@code directory} is registered for change events. */
    boolean isWatching(Path directory) {
        synchronized (lock) {
            return keys.containsKey(directory);
        }
    }

    boolean isRunning() {
        synchronized (lock) {
            return thread != null && thread.isAlive();
        }
    }

    /** Registers the directories of {@code panelWatches} and cancels the others. Holds lock. */
    private void register(List<Panel.Watch> panelWatches) {
        watches.clear();
        for (Panel.Watch watch : panelWatches) {
            watches.computeIfAbsent(watch.directory(), directory -> new ArrayList<>()).add(watch);
        }
        for (Iterator<Map.Entry<Path, WatchKey>> it = keys.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Path, WatchKey> entry = it.next();
            if (!watches.containsKey(entry.getKey()) || !entry.getValue().isValid()) {
                entry.getValue().cancel();
                it.remove();
            }
        }
        if (watches.isEmpty() || timerOnly) {
            return;
        }
        if (service == null) {
            try {
                service = FileSystems.getDefault().newWatchService();
            } catch (IOException | RuntimeException e) {
                timerOnly = true;
                return;
            }
        }
        for (Path directory : watches.keySet()) {
            if (!keys.containsKey(directory)) {
                try {
                    keys.put(directory, directory.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE));
                } catch (IOException | RuntimeException e) {
                    // Gone since the panel read it, or past the inotify limit: the timer covers it.
                }
            }
        }
    }

    private void run() {
        boolean changed = false;
        long changedAt = 0L;
        long lastAsked = System.nanoTime() - minGapNanos;
        try {
            while (true) {
                WatchService watchService;
                long waitNanos;
                synchronized (lock) {
                    if (closed) {
                        return;
                    }
                    long now = System.nanoTime();
                    long due = lastRefresh + (live ? liveTickNanos : idleTickNanos) - now;
                    if (changed) {
                        due = Math.min(due, Math.max(changedAt + settleNanos, lastAsked + minGapNanos) - now);
                    }
                    // Waits are cut at the live tick, so a panel that turns live is noticed.
                    waitNanos = Math.min(Math.max(0L, due), liveTickNanos);
                    watchService = service;
                    if (watchService == null && waitNanos > 0) {
                        TimeUnit.NANOSECONDS.timedWait(lock, waitNanos);
                        continue;
                    }
                }
                if (watchService != null && waitNanos > 0) {
                    for (WatchKey key = watchService.poll(waitNanos, TimeUnit.NANOSECONDS);
                         key != null; key = watchService.poll()) {
                        if (relevant(key) && !changed) {
                            changed = true;
                            changedAt = System.nanoTime();
                        }
                    }
                }
                boolean ask;
                synchronized (lock) {
                    if (closed) {
                        return;
                    }
                    long now = System.nanoTime();
                    ask = (changed && now - changedAt >= settleNanos && now - lastAsked >= minGapNanos)
                            || now - lastRefresh >= (live ? liveTickNanos : idleTickNanos);
                    if (ask) {
                        changed = false;
                        lastAsked = now;
                        lastRefresh = now;
                    }
                }
                if (ask) {
                    try {
                        onChange.run();
                    } catch (RuntimeException e) {
                        // The next change or tick asks again.
                    }
                }
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            // Closed, or the JVM is exiting.
        }
    }

    /** Drains the key's events: true when one names a file the panel reads, or the directory went away. */
    private boolean relevant(WatchKey key) {
        List<WatchEvent<?>> events = key.pollEvents();
        boolean valid = key.reset();
        synchronized (lock) {
            Path directory = key.watchable() instanceof Path path ? path : null;
            if (directory == null || keys.get(directory) != key) {
                return false;
            }
            List<Panel.Watch> directoryWatches = watches.getOrDefault(directory, List.of());
            if (!valid) {
                keys.remove(directory);
                return !directoryWatches.isEmpty();
            }
            for (WatchEvent<?> event : events) {
                if (event.kind() == OVERFLOW) {
                    return !directoryWatches.isEmpty();
                }
                if (event.context() instanceof Path entry) {
                    for (Panel.Watch watch : directoryWatches) {
                        if (watch.matches(entry.toString())) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    @Override
    public void close() {
        WatchService watchService;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            watchService = service;
            service = null;
            keys.clear();
            watches.clear();
            lock.notifyAll();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                // Nothing more to release.
            }
        }
    }
}
