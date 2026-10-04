/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.insights.Panel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelWatcherTest {

    /** A tick that never comes during a test. */
    private static final Duration NEVER = Duration.ofHours(1);
    private static final Duration SETTLE = Duration.ofMillis(150);
    private static final Duration GAP = Duration.ofMillis(300);
    /** Longer than a settle and a gap together, so a second refresh would have been asked for by then. */
    private static final long QUIET_MILLIS = 900;

    @TempDir
    Path tempDir;

    private final AtomicInteger asks = new AtomicInteger();
    private final List<PanelWatcher> watchers = new ArrayList<>();

    @AfterEach
    void closeWatchers() {
        watchers.forEach(PanelWatcher::close);
    }

    @Test
    void aBurstOfWritesToAWatchedFileAsksOnce() throws Exception {
        Path log = tempDir.resolve("chat-1.jsonl");
        PanelWatcher watcher = watcher(NEVER, NEVER);
        watcher.update(List.of(new Panel.Watch(tempDir, "chat-1.jsonl")), false);
        assertTrue(watcher.isWatching(tempDir));
        assertTrue(watcher.isRunning());

        for (int i = 0; i < 5; i++) {
            append(log);
        }
        awaitAsks(1);
        Thread.sleep(QUIET_MILLIS);
        assertEquals(1, asks.get(), "one refresh for the whole burst");

        append(log);
        awaitAsks(2);
    }

    @Test
    void otherEntriesOfAWatchedDirectoryDoNotAsk() throws Exception {
        PanelWatcher watcher = watcher(NEVER, NEVER);
        watcher.update(List.of(new Panel.Watch(tempDir, "chat-1.jsonl")), false);

        append(tempDir.resolve("all-tool-calls.jsonl"));
        append(tempDir.resolve("chat-2.jsonl"));
        Thread.sleep(QUIET_MILLIS);
        assertEquals(0, asks.get(), "other sessions write the same directory");

        append(tempDir.resolve("chat-1.jsonl"));
        awaitAsks(1);
    }

    @Test
    void aWatchWithoutAFileNameFollowsEveryEntry() throws Exception {
        PanelWatcher watcher = watcher(NEVER, NEVER);
        watcher.update(List.of(new Panel.Watch(tempDir, null)), false);

        append(tempDir.resolve("run-0a1b2c3d.json"));
        awaitAsks(1);
    }

    @Test
    void aWatchedDirectoryThatGoesAwayAsksAndIsDropped() throws Exception {
        Path store = Files.createDirectory(tempDir.resolve("crawl-jobs"));
        PanelWatcher watcher = watcher(NEVER, NEVER);
        watcher.update(List.of(new Panel.Watch(store, "state.json")), false);
        assertTrue(watcher.isWatching(store));

        Files.delete(store);
        awaitAsks(1);
        assertFalse(watcher.isWatching(store));
    }

    @Test
    void followsOnlyTheDirectoriesOfTheLatestPanel() throws Exception {
        Path judge = Files.createDirectory(tempDir.resolve("judge"));
        Path tools = Files.createDirectory(tempDir.resolve("tools"));
        PanelWatcher watcher = watcher(NEVER, NEVER);

        watcher.update(List.of(new Panel.Watch(judge, null), new Panel.Watch(tools, "chat-1.jsonl")), false);
        assertTrue(watcher.isWatching(judge));
        assertTrue(watcher.isWatching(tools));

        watcher.update(List.of(new Panel.Watch(tools, "chat-1.jsonl")), false);
        assertFalse(watcher.isWatching(judge));
        assertTrue(watcher.isWatching(tools));
        append(judge.resolve("judgements.jsonl"));
        Thread.sleep(QUIET_MILLIS);
        assertEquals(0, asks.get(), "a directory the panel no longer reads");

        Path missing = tempDir.resolve("missing");
        watcher.update(List.of(new Panel.Watch(missing, null)), false);
        assertFalse(watcher.isWatching(missing), "not there yet: the timer and tool calls cover it");
        assertFalse(watcher.isWatching(tools));
        assertTrue(watcher.isRunning());
    }

    @Test
    void aLivePanelRefreshesOnTheShortTickAndAnIdleOneWaitsForTheLongOne() throws Exception {
        PanelWatcher watcher = watcher(Duration.ofMillis(100), NEVER);
        watcher.update(List.of(), true);
        awaitAsks(3);

        watcher.update(List.of(), false);
        Thread.sleep(300);
        int settled = asks.get();
        Thread.sleep(QUIET_MILLIS);
        assertEquals(settled, asks.get(), "an idle panel waits for the idle tick");
    }

    @Test
    void eachRefreshRestartsTheTimer() throws Exception {
        PanelWatcher watcher = watcher(Duration.ofSeconds(2), NEVER);
        watcher.update(List.of(), true);
        for (int i = 0; i < 4; i++) {
            Thread.sleep(200);
            watcher.update(List.of(), true);
        }
        assertEquals(0, asks.get(), "a panel refreshed within the tick is not refreshed again");

        awaitAsks(1);
    }

    @Test
    void aFailingRefreshDoesNotStopTheWatcher() throws Exception {
        PanelWatcher watcher = new PanelWatcher(() -> {
            ask();
            throw new IllegalStateException("refresh failed");
        }, Duration.ofMillis(100), NEVER, SETTLE, GAP);
        watchers.add(watcher);
        watcher.update(List.of(), true);

        awaitAsks(2);
        assertTrue(watcher.isRunning());
    }

    @Test
    void closeStopsTheWatcherForGood() throws Exception {
        PanelWatcher watcher = watcher(NEVER, NEVER);
        watcher.update(List.of(new Panel.Watch(tempDir, null)), false);
        assertTrue(watcher.isRunning());

        watcher.close();
        awaitStopped(watcher);
        assertFalse(watcher.isWatching(tempDir));

        watcher.update(List.of(new Panel.Watch(tempDir, null)), true);
        assertFalse(watcher.isWatching(tempDir), "an update after close is ignored");
        assertFalse(watcher.isRunning());
        append(tempDir.resolve("late.json"));
        Thread.sleep(QUIET_MILLIS);
        assertEquals(0, asks.get());
        watcher.close();
    }

    @Test
    void closeStopsAWatcherWithNothingToWatch() throws Exception {
        PanelWatcher watcher = watcher(NEVER, NEVER);
        watcher.update(List.of(), false);
        assertTrue(watcher.isRunning());

        watcher.close();
        awaitStopped(watcher);
    }

    private PanelWatcher watcher(Duration liveTick, Duration idleTick) {
        PanelWatcher watcher = new PanelWatcher(this::ask, liveTick, idleTick, SETTLE, GAP);
        watchers.add(watcher);
        return watcher;
    }

    private void ask() {
        synchronized (asks) {
            asks.incrementAndGet();
            asks.notifyAll();
        }
    }

    private void awaitAsks(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        synchronized (asks) {
            while (asks.get() < expected) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                TimeUnit.NANOSECONDS.timedWait(asks, left);
            }
        }
        assertTrue(asks.get() >= expected, "expected " + expected + " refreshes, got " + asks.get());
    }

    private static void awaitStopped(PanelWatcher watcher) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (watcher.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(watcher.isRunning(), "the watcher's thread ends on close");
    }

    private static void append(Path file) throws IOException {
        Files.writeString(file, "{}\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
