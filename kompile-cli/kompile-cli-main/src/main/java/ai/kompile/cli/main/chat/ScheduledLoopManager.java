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

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages scheduled recurring tasks (loops) within a chat session.
 * <p>
 * Supports two scheduling modes:
 * <ul>
 *   <li><b>Interval</b>: Fixed-rate repetition (e.g., "30s", "5m", "2h")</li>
 *   <li><b>Cron</b>: Standard 5-field cron expressions (minute hour dom month dow)</li>
 * </ul>
 * <p>
 * When a loop fires, the configured prompt or slash command is delivered
 * to the chat REPL via the {@code fireCallback} consumer.
 */
public class ScheduledLoopManager {

    /**
     * A single scheduled loop entry.
     */
    public static class ScheduledLoop {
        private final String id;
        private final String schedule;       // original schedule string ("5m" or "*/5 * * * *")
        private final String prompt;         // message or /command to fire
        private final Instant createdAt;
        private final boolean isCron;
        private final long intervalMs;       // only for interval-based loops
        private final int[] cronFields;      // only for cron-based loops (5 fields)
        private volatile LoopStatus status;
        private volatile Instant lastFiredAt;
        private volatile int fireCount;

        public enum LoopStatus {
            ACTIVE, PAUSED, STOPPED
        }

        ScheduledLoop(String schedule, String prompt, boolean isCron,
                      long intervalMs, int[] cronFields) {
            this.id = UUID.randomUUID().toString().substring(0, 8);
            this.schedule = schedule;
            this.prompt = prompt;
            this.createdAt = Instant.now();
            this.isCron = isCron;
            this.intervalMs = intervalMs;
            this.cronFields = cronFields;
            this.status = LoopStatus.ACTIVE;
            this.fireCount = 0;
        }

        public String getId() { return id; }
        public String getSchedule() { return schedule; }
        public String getPrompt() { return prompt; }
        public Instant getCreatedAt() { return createdAt; }
        public boolean isCron() { return isCron; }
        public long getIntervalMs() { return intervalMs; }
        public LoopStatus getStatus() { return status; }
        public void setStatus(LoopStatus status) { this.status = status; }
        public Instant getLastFiredAt() { return lastFiredAt; }
        public int getFireCount() { return fireCount; }

        void recordFire() {
            this.lastFiredAt = Instant.now();
            this.fireCount++;
        }

        public String getElapsedSinceCreation() {
            long seconds = Duration.between(createdAt, Instant.now()).getSeconds();
            if (seconds < 60) return seconds + "s";
            if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
            return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
        }

        public String getFormattedInterval() {
            if (isCron) return "cron: " + schedule;
            long sec = intervalMs / 1000;
            if (sec < 60) return sec + "s";
            if (sec < 3600) return (sec / 60) + "m";
            return (sec / 3600) + "h" + ((sec % 3600) / 60 > 0 ? " " + ((sec % 3600) / 60) + "m" : "");
        }

        public String getStatusIcon() {
            return switch (status) {
                case ACTIVE -> "▶";
                case PAUSED -> "⏸";
                case STOPPED -> "■";
            };
        }
    }

    // Interval pattern: digits + unit (s/m/h), optionally repeated (e.g., "2h30m")
    private static final Pattern INTERVAL_PATTERN = Pattern.compile(
            "^(\\d+[smh])+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTERVAL_PART = Pattern.compile(
            "(\\d+)([smh])", Pattern.CASE_INSENSITIVE);

    // Cron: 5 space-separated fields
    private static final Pattern CRON_PATTERN = Pattern.compile(
            "^([\\d*/,-]+)\\s+([\\d*/,-]+)\\s+([\\d*/,-]+)\\s+([\\d*/,-]+)\\s+([\\d*/,-]+)$");

    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledLoop> loops = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private final List<String> loopOrder = new CopyOnWriteArrayList<>();
    private final Consumer<String> fireCallback;

    /**
     * @param fireCallback invoked (on the scheduler thread) when a loop fires.
     *                     Receives the prompt/command string to inject into the chat.
     */
    public ScheduledLoopManager(Consumer<String> fireCallback) {
        this.fireCallback = fireCallback;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "loop-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Create a new scheduled loop.
     *
     * @param scheduleStr interval ("30s", "5m", "1h") or cron expression ("*&#47;5 * * * *")
     * @param prompt      the message or /command to fire on each tick
     * @return the created loop, or null if the schedule string is invalid
     */
    public ScheduledLoop create(String scheduleStr, String prompt) {
        scheduleStr = scheduleStr.trim();
        prompt = prompt.trim();

        if (INTERVAL_PATTERN.matcher(scheduleStr).matches()) {
            long ms = parseIntervalMs(scheduleStr);
            if (ms < 5000) return null; // minimum 5 seconds
            ScheduledLoop loop = new ScheduledLoop(scheduleStr, prompt, false, ms, null);
            loops.put(loop.getId(), loop);
            loopOrder.add(loop.getId());
            scheduleInterval(loop);
            return loop;
        }

        Matcher cronMatcher = CRON_PATTERN.matcher(scheduleStr);
        if (cronMatcher.matches()) {
            int[] fields = parseCronFields(scheduleStr);
            if (fields == null) return null;
            ScheduledLoop loop = new ScheduledLoop(scheduleStr, prompt, true, 0, fields);
            loops.put(loop.getId(), loop);
            loopOrder.add(loop.getId());
            scheduleCron(loop);
            return loop;
        }

        return null; // unrecognized format
    }

    /**
     * Pause an active loop.
     */
    public boolean pause(String id) {
        ScheduledLoop loop = loops.get(id);
        if (loop == null || loop.getStatus() != ScheduledLoop.LoopStatus.ACTIVE) return false;
        loop.setStatus(ScheduledLoop.LoopStatus.PAUSED);
        cancelFuture(id);
        return true;
    }

    /**
     * Resume a paused loop.
     */
    public boolean resume(String id) {
        ScheduledLoop loop = loops.get(id);
        if (loop == null || loop.getStatus() != ScheduledLoop.LoopStatus.PAUSED) return false;
        loop.setStatus(ScheduledLoop.LoopStatus.ACTIVE);
        if (loop.isCron()) {
            scheduleCron(loop);
        } else {
            scheduleInterval(loop);
        }
        return true;
    }

    /**
     * Stop and remove a loop permanently.
     */
    public boolean remove(String id) {
        ScheduledLoop loop = loops.get(id);
        if (loop == null) return false;
        loop.setStatus(ScheduledLoop.LoopStatus.STOPPED);
        cancelFuture(id);
        loops.remove(id);
        loopOrder.remove(id);
        return true;
    }

    /**
     * Get a specific loop by ID (prefix match supported).
     */
    public ScheduledLoop get(String idPrefix) {
        // Exact match first
        ScheduledLoop exact = loops.get(idPrefix);
        if (exact != null) return exact;
        // Prefix match
        for (Map.Entry<String, ScheduledLoop> e : loops.entrySet()) {
            if (e.getKey().startsWith(idPrefix)) return e.getValue();
        }
        return null;
    }

    /**
     * List all loops in creation order.
     */
    public List<ScheduledLoop> list() {
        List<ScheduledLoop> result = new ArrayList<>();
        for (String id : loopOrder) {
            ScheduledLoop loop = loops.get(id);
            if (loop != null) result.add(loop);
        }
        return result;
    }

    /**
     * Number of active (non-stopped) loops.
     */
    public int activeCount() {
        int count = 0;
        for (ScheduledLoop loop : loops.values()) {
            if (loop.getStatus() == ScheduledLoop.LoopStatus.ACTIVE) count++;
        }
        return count;
    }

    /**
     * Shutdown the scheduler and stop all loops. Call on REPL exit.
     */
    public void shutdown() {
        for (String id : new ArrayList<>(loops.keySet())) {
            ScheduledLoop loop = loops.get(id);
            if (loop != null) loop.setStatus(ScheduledLoop.LoopStatus.STOPPED);
            cancelFuture(id);
        }
        scheduler.shutdownNow();
    }

    // ── Scheduling internals ──────────────────────────────────────────

    private void scheduleInterval(ScheduledLoop loop) {
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            if (loop.getStatus() == ScheduledLoop.LoopStatus.ACTIVE) {
                loop.recordFire();
                fireCallback.accept(loop.getPrompt());
            }
        }, loop.getIntervalMs(), loop.getIntervalMs(), TimeUnit.MILLISECONDS);
        futures.put(loop.getId(), future);
    }

    private void scheduleCron(ScheduledLoop loop) {
        scheduleNextCronFire(loop);
    }

    private void scheduleNextCronFire(ScheduledLoop loop) {
        if (loop.getStatus() != ScheduledLoop.LoopStatus.ACTIVE) return;

        long delayMs = computeNextCronDelayMs(loop.cronFields);
        if (delayMs < 0) {
            // Can't compute next fire — stop
            loop.setStatus(ScheduledLoop.LoopStatus.STOPPED);
            return;
        }

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            if (loop.getStatus() == ScheduledLoop.LoopStatus.ACTIVE) {
                loop.recordFire();
                fireCallback.accept(loop.getPrompt());
            }
            // Reschedule for next cron match
            scheduleNextCronFire(loop);
        }, delayMs, TimeUnit.MILLISECONDS);
        futures.put(loop.getId(), future);
    }

    private void cancelFuture(String id) {
        ScheduledFuture<?> f = futures.remove(id);
        if (f != null) f.cancel(false);
    }

    // ── Interval parsing ──────────────────────────────────────────────

    static long parseIntervalMs(String interval) {
        long totalMs = 0;
        Matcher m = INTERVAL_PART.matcher(interval.toLowerCase());
        while (m.find()) {
            long value = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "s" -> totalMs += value * 1000;
                case "m" -> totalMs += value * 60_000;
                case "h" -> totalMs += value * 3_600_000;
            }
        }
        return totalMs;
    }

    // ── Cron parsing and next-fire computation ────────────────────────

    /**
     * Parse a 5-field cron expression into an internal representation.
     * Fields: minute(0-59) hour(0-23) day-of-month(1-31) month(1-12) day-of-week(0-6, 0=Sunday)
     * <p>
     * Returns a flat array encoding each field's allowed values as a bitmask-style
     * boolean array concatenated together:
     * [60 minute bits | 24 hour bits | 31 dom bits | 12 month bits | 7 dow bits] = 134 entries
     */
    static int[] parseCronFields(String expr) {
        String[] parts = expr.trim().split("\\s+");
        if (parts.length != 5) return null;

        // field sizes: minute=60, hour=24, dom=31, month=12, dow=7
        int[] sizes = {60, 24, 31, 12, 7};
        int[] offsets = {0, 1, 1, 1, 0}; // min value for each field
        int totalSize = 60 + 24 + 31 + 12 + 7; // 134
        int[] result = new int[totalSize];

        int pos = 0;
        for (int f = 0; f < 5; f++) {
            boolean[] allowed = parseCronField(parts[f], sizes[f], offsets[f]);
            if (allowed == null) return null;
            for (int i = 0; i < sizes[f]; i++) {
                result[pos + i] = allowed[i] ? 1 : 0;
            }
            pos += sizes[f];
        }
        return result;
    }

    /**
     * Parse a single cron field (supports *, *&#47;N, N, N-M, comma-separated).
     */
    private static boolean[] parseCronField(String field, int size, int minVal) {
        boolean[] allowed = new boolean[size];

        for (String part : field.split(",")) {
            part = part.trim();
            if (part.equals("*")) {
                Arrays.fill(allowed, true);
            } else if (part.startsWith("*/")) {
                int step;
                try { step = Integer.parseInt(part.substring(2)); } catch (NumberFormatException e) { return null; }
                if (step <= 0) return null;
                for (int i = 0; i < size; i++) {
                    if (i % step == 0) allowed[i] = true;
                }
            } else if (part.contains("-")) {
                String[] range = part.split("-", 2);
                int from, to;
                try {
                    from = Integer.parseInt(range[0]) - minVal;
                    to = Integer.parseInt(range[1]) - minVal;
                } catch (NumberFormatException e) { return null; }
                if (from < 0 || to >= size || from > to) return null;
                for (int i = from; i <= to; i++) allowed[i] = true;
            } else {
                int val;
                try { val = Integer.parseInt(part) - minVal; } catch (NumberFormatException e) { return null; }
                if (val < 0 || val >= size) return null;
                allowed[val] = true;
            }
        }
        return allowed;
    }

    /**
     * Compute milliseconds until the next matching cron minute.
     * Scans up to 366 days ahead (covers all month/dow combinations).
     */
    static long computeNextCronDelayMs(int[] cronFields) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        // Start from the next whole minute
        ZonedDateTime candidate = now.plusMinutes(1).withSecond(0).withNano(0);

        // Scan up to 366*24*60 minutes (one full year)
        int maxMinutes = 366 * 24 * 60;
        for (int i = 0; i < maxMinutes; i++) {
            if (cronMatches(cronFields, candidate)) {
                long delayMs = Duration.between(now, candidate).toMillis();
                return Math.max(delayMs, 1000); // at least 1 second
            }
            candidate = candidate.plusMinutes(1);
        }
        return -1; // no match found within a year
    }

    private static boolean cronMatches(int[] fields, ZonedDateTime dt) {
        int minute = dt.getMinute();
        int hour = dt.getHour();
        int dom = dt.getDayOfMonth() - 1;   // 0-based index into 31-slot array
        int month = dt.getMonthValue() - 1;  // 0-based index into 12-slot array
        int dow = dt.getDayOfWeek().getValue() % 7; // Monday=1..Sunday=7 → 0=Sunday

        // Offsets into the flat array: minute[0..59], hour[60..83], dom[84..114], month[115..126], dow[127..133]
        return fields[minute] == 1
                && fields[60 + hour] == 1
                && fields[84 + dom] == 1
                && fields[115 + month] == 1
                && fields[127 + dow] == 1;
    }

    /**
     * Format a scheduled loop as a summary line.
     */
    public static String formatLoop(ScheduledLoop loop) {
        StringBuilder sb = new StringBuilder();
        sb.append(loop.getStatusIcon())
                .append(" [").append(loop.getId()).append("] ")
                .append(loop.getFormattedInterval())
                .append("  ");

        String prompt = loop.getPrompt();
        if (prompt.length() > 60) prompt = prompt.substring(0, 57) + "...";
        sb.append(prompt);

        sb.append("  (fired ").append(loop.getFireCount()).append("x");
        if (loop.getLastFiredAt() != null) {
            long ago = Duration.between(loop.getLastFiredAt(), Instant.now()).getSeconds();
            if (ago < 60) sb.append(", ").append(ago).append("s ago");
            else if (ago < 3600) sb.append(", ").append(ago / 60).append("m ago");
            else sb.append(", ").append(ago / 3600).append("h ago");
        }
        sb.append(")");
        return sb.toString();
    }
}
