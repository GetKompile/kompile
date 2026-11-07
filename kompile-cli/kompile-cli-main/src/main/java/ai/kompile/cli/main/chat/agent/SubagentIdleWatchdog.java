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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Alerts the parent agent when a running subagent goes quiet. A child is tracked
 * from {@link SubagentRunner.LifecycleListener#onSubagentStart} to
 * {@link SubagentRunner.LifecycleListener#onSubagentEnd}; every status, activity,
 * output, or metadata event in between proves it is alive. A child that reports
 * nothing for the idle threshold gets one alert per quiet stretch, and its next
 * event re-arms the alert. Alerts wait while the parent is blocked in a foreground
 * tool call: it could not act on them, and a child it is synchronously awaiting
 * ends before the parent is free, so it is never reported stale.
 */
public final class SubagentIdleWatchdog implements AutoCloseable {

    /** Default quiet time before the parent is alerted; zero or less disables the watchdog. */
    public static final int DEFAULT_IDLE_SECONDS = 300;

    private static final long MAX_SCAN_INTERVAL_MS = 15_000L;
    private static final int MAX_DESCRIPTION_CHARS = 120;

    private static final class Tracked {
        final String type;
        final String description;
        volatile long lastEventNanos;
        volatile String lastStatus;
        volatile String lastActivity;
        volatile boolean alerted;

        Tracked(String type, String description, long now) {
            this.type = type;
            this.description = description;
            this.lastEventNanos = now;
        }
    }

    private final long idleNanos;
    private final LongSupplier nanoClock;
    private final BiConsumer<String, String> alertSink;
    private final Map<String, Tracked> tracked = new ConcurrentHashMap<>();
    private volatile BooleanSupplier parentBlocked = () -> false;
    private ScheduledExecutorService scanner;

    /** {@code alertSink} receives the idle subagent's id and the notice for the parent agent. */
    public SubagentIdleWatchdog(Duration idleThreshold, BiConsumer<String, String> alertSink) {
        this(idleThreshold, System::nanoTime, alertSink);
    }

    SubagentIdleWatchdog(Duration idleThreshold, LongSupplier nanoClock, BiConsumer<String, String> alertSink) {
        this.idleNanos = idleThreshold == null || idleThreshold.isNegative() ? 0L : idleThreshold.toNanos();
        this.nanoClock = nanoClock;
        this.alertSink = alertSink;
    }

    public boolean isEnabled() {
        return idleNanos > 0;
    }

    /** True while the parent is blocked in a foreground tool call; due alerts wait until it is free. */
    public void setParentBlocked(BooleanSupplier parentBlocked) {
        this.parentBlocked = parentBlocked == null ? () -> false : parentBlocked;
    }

    public void onStart(String id, String type, String description) {
        if (id == null) return;
        // A follow-up run restarts the same id; it starts a fresh quiet stretch.
        tracked.put(id, new Tracked(type, description, nanoClock.getAsLong()));
    }

    public void onStatus(String id, String status) {
        Tracked entry = touch(id);
        if (entry != null && status != null) entry.lastStatus = status;
    }

    public void onActivity(String id, String summary) {
        Tracked entry = touch(id);
        if (entry != null && summary != null) entry.lastActivity = summary;
    }

    /** Streamed output or metadata: proof of life with nothing new to describe. */
    public void onProgress(String id) {
        touch(id);
    }

    public void onEnd(String id) {
        if (id != null) tracked.remove(id);
    }

    private Tracked touch(String id) {
        Tracked entry = id == null ? null : tracked.get(id);
        if (entry != null) {
            entry.lastEventNanos = nanoClock.getAsLong();
            entry.alerted = false;
        }
        return entry;
    }

    /** Starts periodic scanning. A disabled watchdog never starts a thread. */
    public synchronized void start() {
        if (!isEnabled() || scanner != null) return;
        long intervalMs = Math.max(1_000L,
                Math.min(MAX_SCAN_INTERVAL_MS, TimeUnit.NANOSECONDS.toMillis(idleNanos) / 4));
        scanner = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "subagent-idle-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        scanner.scheduleWithFixedDelay(() -> {
            try {
                scan();
            } catch (RuntimeException ignored) {
                // A failing alert sink must not end the schedule; the next scan retries.
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** Alerts every tracked subagent whose quiet stretch reached the threshold; returns how many. */
    int scan() {
        if (!isEnabled() || parentBlocked.getAsBoolean()) return 0;
        long now = nanoClock.getAsLong();
        int alerts = 0;
        for (Map.Entry<String, Tracked> e : tracked.entrySet()) {
            Tracked entry = e.getValue();
            long quiet = now - entry.lastEventNanos;
            if (entry.alerted || quiet < idleNanos) continue;
            entry.alerted = true;
            alerts++;
            alertSink.accept(e.getKey(), idleNotice(e.getKey(), entry, Duration.ofNanos(quiet)));
        }
        return alerts;
    }

    private static String idleNotice(String id, Tracked entry, Duration quiet) {
        StringBuilder notice = new StringBuilder("[System subagent watchdog]\n")
                .append("Subagent ").append(id);
        if (present(entry.type)) notice.append(" (").append(entry.type).append(')');
        if (present(entry.description)) {
            notice.append(" \"").append(truncate(entry.description)).append('"');
        }
        notice.append(" has reported no activity for ").append(formatDuration(quiet)).append('.');
        if (present(entry.lastStatus)) notice.append(" Last status: ").append(entry.lastStatus).append('.');
        if (present(entry.lastActivity)) {
            notice.append(" Last activity: ").append(truncate(entry.lastActivity)).append('.');
        }
        notice.append("\nIt may be blocked on a long tool call, a stalled provider, or a hung process. ")
                .append("Decide whether to keep waiting or stop it: subagent_resume with action=cancel and ")
                .append("session_id=").append(id).append(" cancels it, after which continue the user's work ")
                .append("without it. If you keep waiting and it finishes, its result is still delivered normally.");
        return notice.toString();
    }

    static String formatDuration(Duration duration) {
        long seconds = Math.max(0L, duration.getSeconds());
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }

    private static String truncate(String text) {
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= MAX_DESCRIPTION_CHARS ? flat : flat.substring(0, MAX_DESCRIPTION_CHARS - 3) + "...";
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public synchronized void close() {
        if (scanner != null) {
            scanner.shutdownNow();
            scanner = null;
        }
        tracked.clear();
    }
}
