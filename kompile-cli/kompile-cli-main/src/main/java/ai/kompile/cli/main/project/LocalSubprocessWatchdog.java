/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import com.sun.management.OperatingSystemMXBean;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Parent-side memory watchdog for the local crawl runner's subprocesses.
 *
 * <p>The local CLI crawl stack runs three kinds of children: pooled model-serving runtimes,
 * pooled pipeline runtime sessions, and bounded learning children. Each child protects itself
 * in-JVM (heap/GPU/off-heap thresholds via {@code SubprocessMemoryWatchdog}), but no child can
 * see its own total resident set relative to the host, and a native OOM (SIGKILL-style) kills a
 * child without any in-JVM hook firing. This parent-side watchdog is the outer net: it samples
 * {@code /proc/<pid>/status} RSS for every tracked child and force-destroys any child whose RSS
 * exceeds the effective limit for {@code breachCount} consecutive checks.</p>
 *
 * <p>The limit is the lower of an absolute {@code maxRssMb} and {@code maxRssFraction} of total
 * system RAM; both zero (the default) disable enforcement while leaving live process tracking
 * available for the {@code subprocess_watchdog} MCP tool. Configuration is exposed and mutable
 * through the same tool.</p>
 *
 * <p>The same service owns pre-admission for project-local crawls. Before expensive work starts,
 * it can wait with a bounded timeout, fail immediately, or remain disabled. Admission samples
 * host RAM and device-agnostic GPU headroom; it never selects or hardcodes a device.</p>
 *
 * <p>Deliberately framework-free and single-JVM: the local crawl runner never spans hosts.</p>
 */
public final class LocalSubprocessWatchdog {

    // ── Configuration properties (also the config_update surface) ─────────────
    public static final String ENABLED_PROPERTY = "kompile.subprocess.watchdog.enabled";
    public static final String INTERVAL_PROPERTY = "kompile.subprocess.watchdog.intervalMs";
    public static final String MAX_RSS_MB_PROPERTY = "kompile.subprocess.watchdog.maxRssMb";
    public static final String MAX_RSS_FRACTION_PROPERTY = "kompile.subprocess.watchdog.maxRssFraction";
    public static final String BREACH_COUNT_PROPERTY = "kompile.subprocess.watchdog.breachCount";
    public static final String GRACE_SECONDS_PROPERTY = "kompile.subprocess.watchdog.graceSeconds";
    public static final String ADMISSION_MODE_PROPERTY = "kompile.subprocess.watchdog.admissionMode";
    public static final String ADMISSION_TIMEOUT_MS_PROPERTY =
            "kompile.subprocess.watchdog.admissionTimeoutMs";
    public static final String ADMISSION_POLL_INTERVAL_MS_PROPERTY =
            "kompile.subprocess.watchdog.admissionPollIntervalMs";
    public static final String ADMISSION_MIN_AVAILABLE_RAM_MB_PROPERTY =
            "kompile.subprocess.watchdog.admissionMinAvailableRamMb";
    public static final String ADMISSION_MAX_RAM_USED_FRACTION_PROPERTY =
            "kompile.subprocess.watchdog.admissionMaxRamUsedFraction";
    public static final String ADMISSION_MIN_AVAILABLE_GPU_MB_PROPERTY =
            "kompile.subprocess.watchdog.admissionMinAvailableGpuMb";
    public static final String ADMISSION_MAX_GPU_USED_FRACTION_PROPERTY =
            "kompile.subprocess.watchdog.admissionMaxGpuUsedFraction";

    private static final long DEFAULT_INTERVAL_MS = 30_000L;
    private static final int DEFAULT_BREACH_COUNT = 2;
    private static final int DEFAULT_GRACE_SECONDS = 60;
    private static final String DEFAULT_ADMISSION_MODE = "wait";
    private static final long DEFAULT_ADMISSION_TIMEOUT_MS = 300_000L;
    private static final long DEFAULT_ADMISSION_POLL_INTERVAL_MS = 2_000L;
    private static final double DEFAULT_ADMISSION_MAX_RAM_USED_FRACTION = 0.75;
    private static final double DEFAULT_ADMISSION_MAX_GPU_USED_FRACTION = 0.75;
    private static final long NVIDIA_SMI_TIMEOUT_MS = 2_000L;

    private static final LocalSubprocessWatchdog INSTANCE = new LocalSubprocessWatchdog();

    /** One tracked child. Registration happens at process start; removal at pool close. */
    public static final class TrackedSubprocess {
        private final String id;
        private final String type;
        private final String description;
        private final long pid;
        private final ProcessHandle handle;
        private final Instant startedAt = Instant.now();
        private final AtomicInteger consecutiveBreaches = new AtomicInteger();
        private volatile String lastEvent;

        private TrackedSubprocess(String id, String type, String description,
                                  long pid, ProcessHandle handle) {
            this.id = id;
            this.type = type;
            this.description = description;
            this.pid = pid;
            this.handle = handle;
        }

        public String id() { return id; }
        public String type() { return type; }
        public String description() { return description; }
        public long pid() { return pid; }
        public Instant startedAt() { return startedAt; }
        public String lastEvent() { return lastEvent; }

        boolean alive() {
            return handle != null && handle.isAlive();
        }

        long rssMb() {
            return alive() ? readRssMb(pid) : 0L;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("type", type);
            map.put("pid", pid);
            map.put("alive", alive());
            map.put("rssMb", rssMb());
            map.put("startedAt", startedAt.toString());
            map.put("uptimeSeconds", Duration.between(startedAt, Instant.now()).getSeconds());
            if (description != null && !description.isBlank()) {
                map.put("description", description);
            }
            if (lastEvent != null) {
                map.put("lastEvent", lastEvent);
            }
            return map;
        }
    }

    /** Physical memory state for one NVIDIA device. Index is informational, never a placement choice. */
    public record GpuCapacity(int index, long usedMb, long totalMb) {
        public long availableMb() {
            return Math.max(0L, totalMb - usedMb);
        }

        public double usedFraction() {
            return totalMb > 0
                    ? Math.max(0.0, Math.min(1.0, (double) usedMb / totalMb))
                    : -1.0;
        }

        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("index", index);
            result.put("usedMb", usedMb);
            result.put("availableMb", availableMb());
            result.put("totalMb", totalMb);
            result.put("usedFraction", usedFraction());
            return result;
        }
    }

    /** Point-in-time host capacity used for crawl admission. */
    public record HardwareCapacitySnapshot(long totalRamMb,
                                           long availableRamMb,
                                           double ramUsedFraction,
                                           List<GpuCapacity> gpus,
                                           String gpuProbe,
                                           Instant sampledAt) {
        public HardwareCapacitySnapshot {
            gpus = gpus == null ? List.of() : List.copyOf(gpus);
            sampledAt = sampledAt == null ? Instant.now() : sampledAt;
        }

        public long bestGpuAvailableMb() {
            return gpus.stream().mapToLong(GpuCapacity::availableMb).max().orElse(-1L);
        }

        public double bestGpuUsedFraction() {
            return gpus.stream().mapToDouble(GpuCapacity::usedFraction)
                    .filter(value -> value >= 0.0).min().orElse(-1.0);
        }

        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sampledAt", sampledAt.toString());
            result.put("totalRamMb", totalRamMb);
            result.put("availableRamMb", availableRamMb);
            result.put("ramUsedFraction", ramUsedFraction);
            result.put("gpuProbe", gpuProbe);
            result.put("gpuCount", gpus.size());
            result.put("bestGpuAvailableMb", bestGpuAvailableMb());
            result.put("bestGpuUsedFraction", bestGpuUsedFraction());
            result.put("gpus", gpus.stream().map(GpuCapacity::toMap).toList());
            return result;
        }
    }

    /** Result returned to the local crawl boundary before any expensive subprocess work begins. */
    public record CapacityAdmission(boolean admitted,
                                    String outcome,
                                    String reason,
                                    boolean waited,
                                    long waitedMs,
                                    int samples,
                                    HardwareCapacitySnapshot capacity) {
    }

    @FunctionalInterface
    interface CapacityProbe {
        HardwareCapacitySnapshot sample() throws InterruptedException;
    }

    @FunctionalInterface
    interface CapacitySleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private record CapacityDecision(boolean admitted,
                                    String reason,
                                    HardwareCapacitySnapshot capacity) {
    }

    private record GpuProbeResult(List<GpuCapacity> gpus, String source) {
    }

    private final ConcurrentHashMap<String, TrackedSubprocess> tracked = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "local-subprocess-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong killedCount = new AtomicLong();
    private final AtomicInteger waitingAdmissions = new AtomicInteger();
    private final AtomicLong admittedCount = new AtomicLong();
    private final AtomicLong waitedCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();
    private final AtomicLong timedOutCount = new AtomicLong();
    private volatile HardwareCapacitySnapshot lastCapacitySnapshot;
    private volatile Map<String, Object> lastAdmissionEvent = Map.of();
    private volatile ScheduledFuture<?> scheduledTask;
    private volatile boolean started;

    private LocalSubprocessWatchdog() {
        if (enabled()) {
            startScheduler();
        }
    }

    public static LocalSubprocessWatchdog get() {
        return INSTANCE;
    }

    // ── Registration ──────────────────────────────────────────────────────────

    /** Track a child by an existing {@link Process}. Returns the tracking id. */
    public String register(String id, Process process, String type, String description) {
        if (id == null || id.isBlank() || process == null) {
            throw new IllegalArgumentException("id and process are required");
        }
        tracked.put(id, new TrackedSubprocess(
                id, type, description, process.pid(), process.toHandle()));
        maybeStart();
        return id;
    }

    /** Track a child by PID (used where only {@code pid()} is reachable, e.g. pool sessions). */
    public String register(String id, long pid, String type, String description) {
        if (id == null || id.isBlank() || pid <= 0) {
            throw new IllegalArgumentException("id and a positive pid are required");
        }
        tracked.put(id, new TrackedSubprocess(
                id, type, description, pid, ProcessHandle.of(pid).orElse(null)));
        maybeStart();
        return id;
    }

    public boolean deregister(String id) {
        return id != null && tracked.remove(id) != null;
    }

    public Optional<TrackedSubprocess> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(tracked.get(id));
    }

    public List<TrackedSubprocess> list() {
        List<TrackedSubprocess> all = new ArrayList<>(tracked.values());
        all.sort(Comparator.comparing(TrackedSubprocess::startedAt));
        return all;
    }

    public int trackedCount() {
        return tracked.size();
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    public boolean enabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"));
    }

    public long intervalMs() {
        return positiveLong(INTERVAL_PROPERTY, DEFAULT_INTERVAL_MS);
    }

    public long maxRssMb() {
        return Math.max(0L, longProperty(MAX_RSS_MB_PROPERTY, 0L));
    }

    public double maxRssFraction() {
        return Math.max(0.0, doubleProperty(MAX_RSS_FRACTION_PROPERTY, 0.0));
    }

    public int breachCount() {
        return (int) Math.max(1L, longProperty(BREACH_COUNT_PROPERTY, DEFAULT_BREACH_COUNT));
    }

    public int graceSeconds() {
        return (int) Math.max(0L, longProperty(GRACE_SECONDS_PROPERTY, DEFAULT_GRACE_SECONDS));
    }

    public String admissionMode() {
        try {
            return normalizeAdmissionMode(System.getProperty(
                    ADMISSION_MODE_PROPERTY, DEFAULT_ADMISSION_MODE));
        } catch (IllegalArgumentException ignored) {
            return DEFAULT_ADMISSION_MODE;
        }
    }

    public long admissionTimeoutMs() {
        return Math.max(0L, longProperty(
                ADMISSION_TIMEOUT_MS_PROPERTY, DEFAULT_ADMISSION_TIMEOUT_MS));
    }

    public long admissionPollIntervalMs() {
        return positiveLong(ADMISSION_POLL_INTERVAL_MS_PROPERTY,
                DEFAULT_ADMISSION_POLL_INTERVAL_MS);
    }

    public long admissionMinAvailableRamMb() {
        return Math.max(0L, longProperty(ADMISSION_MIN_AVAILABLE_RAM_MB_PROPERTY, 0L));
    }

    public double admissionMaxRamUsedFraction() {
        return fractionProperty(ADMISSION_MAX_RAM_USED_FRACTION_PROPERTY,
                DEFAULT_ADMISSION_MAX_RAM_USED_FRACTION);
    }

    public long admissionMinAvailableGpuMb() {
        return Math.max(0L, longProperty(ADMISSION_MIN_AVAILABLE_GPU_MB_PROPERTY, 0L));
    }

    public double admissionMaxGpuUsedFraction() {
        return fractionProperty(ADMISSION_MAX_GPU_USED_FRACTION_PROPERTY,
                DEFAULT_ADMISSION_MAX_GPU_USED_FRACTION);
    }

    /** Effective enforcement limit in MB; 0 = enforcement disabled. */
    public long effectiveLimitMb() {
        long absoluteMb = maxRssMb();
        long fractionMb = 0L;
        double fraction = maxRssFraction();
        if (fraction > 0.0) {
            long totalRamMb = systemTotalRamMb();
            if (totalRamMb > 0) {
                fractionMb = (long) (fraction * totalRamMb);
            }
        }
        if (absoluteMb > 0 && fractionMb > 0) {
            return Math.min(absoluteMb, fractionMb);
        }
        return Math.max(absoluteMb, fractionMb);
    }

    /** Apply a config update from the MCP tool; keys match the *_PROPERTY constants' suffixes. */
    public synchronized Map<String, Object> updateConfig(Map<String, Object> updates) {
        if (updates != null) {
            setIfPresent(updates, "enabled", ENABLED_PROPERTY, Boolean::parseBoolean);
            setIfPresent(updates, "intervalMs", INTERVAL_PROPERTY, LocalSubprocessWatchdog::parseLong);
            setIfPresent(updates, "maxRssMb", MAX_RSS_MB_PROPERTY, LocalSubprocessWatchdog::parseLong);
            setIfPresent(updates, "maxRssFraction", MAX_RSS_FRACTION_PROPERTY,
                    LocalSubprocessWatchdog::parseDouble);
            setIfPresent(updates, "breachCount", BREACH_COUNT_PROPERTY, LocalSubprocessWatchdog::parseLong);
            setIfPresent(updates, "graceSeconds", GRACE_SECONDS_PROPERTY, LocalSubprocessWatchdog::parseLong);
            setIfPresent(updates, "admissionMode", ADMISSION_MODE_PROPERTY,
                    LocalSubprocessWatchdog::normalizeAdmissionMode);
            setIfPresent(updates, "admissionTimeoutMs", ADMISSION_TIMEOUT_MS_PROPERTY,
                    LocalSubprocessWatchdog::parseNonNegativeLong);
            setIfPresent(updates, "admissionPollIntervalMs", ADMISSION_POLL_INTERVAL_MS_PROPERTY,
                    LocalSubprocessWatchdog::parsePositiveLong);
            setIfPresent(updates, "admissionMinAvailableRamMb",
                    ADMISSION_MIN_AVAILABLE_RAM_MB_PROPERTY,
                    LocalSubprocessWatchdog::parseNonNegativeLong);
            setIfPresent(updates, "admissionMaxRamUsedFraction",
                    ADMISSION_MAX_RAM_USED_FRACTION_PROPERTY,
                    LocalSubprocessWatchdog::parseFraction);
            setIfPresent(updates, "admissionMinAvailableGpuMb",
                    ADMISSION_MIN_AVAILABLE_GPU_MB_PROPERTY,
                    LocalSubprocessWatchdog::parseNonNegativeLong);
            setIfPresent(updates, "admissionMaxGpuUsedFraction",
                    ADMISSION_MAX_GPU_USED_FRACTION_PROPERTY,
                    LocalSubprocessWatchdog::parseFraction);
        }
        restartSchedulerIfNeeded();
        return configMap();
    }

    public Map<String, Object> configMap() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", enabled());
        config.put("intervalMs", intervalMs());
        config.put("maxRssMb", maxRssMb());
        config.put("maxRssFraction", maxRssFraction());
        config.put("breachCount", breachCount());
        config.put("graceSeconds", graceSeconds());
        config.put("effectiveLimitMb", effectiveLimitMb());
        config.put("admissionMode", admissionMode());
        config.put("admissionTimeoutMs", admissionTimeoutMs());
        config.put("admissionPollIntervalMs", admissionPollIntervalMs());
        config.put("admissionMinAvailableRamMb", admissionMinAvailableRamMb());
        config.put("admissionMaxRamUsedFraction", admissionMaxRamUsedFraction());
        config.put("admissionMinAvailableGpuMb", admissionMinAvailableGpuMb());
        config.put("admissionMaxGpuUsedFraction", admissionMaxGpuUsedFraction());
        config.put("systemTotalRamMb", systemTotalRamMb());
        config.put("platformSupported", isLinux());
        return config;
    }

    // ── Crawl admission ────────────────────────────────────────────────────────

    /**
     * Wait for enough physical host/GPU memory to start a project-local crawl, or reject it
     * immediately when {@code admissionMode=fail}. The wait is interruptible so cancelling an
     * asynchronous crawl releases its worker promptly.
     */
    public CapacityAdmission awaitCrawlCapacity(Consumer<String> waitObserver)
            throws InterruptedException {
        return awaitCrawlCapacity(this::probeHardwareCapacity, Thread::sleep, waitObserver);
    }

    CapacityAdmission awaitCrawlCapacity(CapacityProbe probe,
                                         CapacitySleeper sleeper,
                                         Consumer<String> waitObserver)
            throws InterruptedException {
        String mode = admissionMode();
        if (!enabled() || "off".equals(mode) || !capacityThresholdsEnabled()) {
            return completeAdmission(true, "DISABLED", null, false, 0L, 0, null);
        }

        long startedNanos = System.nanoTime();
        CapacityDecision decision = sampleCapacity(probe);
        int samples = 1;
        if (decision.admitted()) {
            return completeAdmission(true, "ADMITTED", null, false, 0L, samples,
                    decision.capacity());
        }
        if ("fail".equals(mode)) {
            return completeAdmission(false, "REJECTED", decision.reason(), false, 0L,
                    samples, decision.capacity());
        }

        waitedCount.incrementAndGet();
        waitingAdmissions.incrementAndGet();
        String lastReason = null;
        try {
            while (true) {
                if (!decision.reason().equals(lastReason)) {
                    notifyWaitObserver(waitObserver, decision.reason());
                    lastReason = decision.reason();
                }
                mode = admissionMode();
                if (!enabled() || "off".equals(mode) || !capacityThresholdsEnabled()) {
                    return completeAdmission(true, "DISABLED", null, true,
                            elapsedMillis(startedNanos), samples, decision.capacity());
                }
                if ("fail".equals(mode)) {
                    return completeAdmission(false, "REJECTED", decision.reason(), true,
                            elapsedMillis(startedNanos), samples, decision.capacity());
                }
                long timeoutMs = admissionTimeoutMs();
                long waitedMs = elapsedMillis(startedNanos);
                if (waitedMs >= timeoutMs) {
                    return completeAdmission(false, "TIMED_OUT", decision.reason(), true,
                            waitedMs, samples, decision.capacity());
                }
                long remainingMs = timeoutMs - waitedMs;
                sleeper.sleep(Math.max(1L, Math.min(admissionPollIntervalMs(), remainingMs)));
                mode = admissionMode();
                if (!enabled() || "off".equals(mode) || !capacityThresholdsEnabled()) {
                    return completeAdmission(true, "DISABLED", null, true,
                            elapsedMillis(startedNanos), samples, decision.capacity());
                }
                decision = sampleCapacity(probe);
                samples++;
                if (decision.admitted()) {
                    return completeAdmission(true, "ADMITTED", null, true,
                            elapsedMillis(startedNanos), samples, decision.capacity());
                }
                if ("fail".equals(mode)) {
                    return completeAdmission(false, "REJECTED", decision.reason(), true,
                            elapsedMillis(startedNanos), samples, decision.capacity());
                }
            }
        } finally {
            waitingAdmissions.decrementAndGet();
        }
    }

    /** Current would-admit decision for the manual watchdog check action; never waits. */
    public Map<String, Object> sampleCapacityStatus() throws InterruptedException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", admissionMode());
        result.put("enabled", enabled() && !"off".equals(admissionMode())
                && capacityThresholdsEnabled());
        if (!Boolean.TRUE.equals(result.get("enabled"))) {
            result.put("wouldAdmit", true);
            result.put("reason", "capacity admission is disabled");
            return result;
        }
        CapacityDecision decision = sampleCapacity(this::probeHardwareCapacity);
        result.put("wouldAdmit", decision.admitted());
        if (decision.reason() != null) {
            result.put("reason", decision.reason());
        }
        result.put("capacity", decision.capacity().toMap());
        return result;
    }

    private CapacityDecision sampleCapacity(CapacityProbe probe) throws InterruptedException {
        HardwareCapacitySnapshot snapshot = probe.sample();
        lastCapacitySnapshot = snapshot;
        String reason = capacityPressureReason(snapshot);
        return new CapacityDecision(reason == null, reason, snapshot);
    }

    private String capacityPressureReason(HardwareCapacitySnapshot snapshot) {
        List<String> reasons = new ArrayList<>();
        long availableRamMb = snapshot.availableRamMb();
        long ramFloorMb = admissionMinAvailableRamMb();
        if (ramFloorMb > 0) {
            if (availableRamMb < 0) {
                reasons.add("available RAM is unknown; cannot verify the configured "
                        + ramFloorMb + " MB admission floor");
            } else if (availableRamMb < ramFloorMb) {
                reasons.add(String.format(Locale.ROOT,
                        "available RAM %d MB is below the %d MB admission floor",
                        availableRamMb, ramFloorMb));
            }
        }
        double maxRamFraction = admissionMaxRamUsedFraction();
        if (maxRamFraction > 0 && snapshot.ramUsedFraction() >= maxRamFraction) {
            reasons.add(String.format(Locale.ROOT,
                    "system RAM is %.1f%% used (admission limit %.1f%%)",
                    snapshot.ramUsedFraction() * 100.0, maxRamFraction * 100.0));
        }

        long gpuFloorMb = admissionMinAvailableGpuMb();
        if (snapshot.gpus().isEmpty()) {
            if (gpuFloorMb > 0) {
                reasons.add("GPU capacity is unknown (" + snapshot.gpuProbe()
                        + "); cannot verify the configured " + gpuFloorMb
                        + " MB admission floor");
            }
        } else {
            long bestAvailableMb = snapshot.bestGpuAvailableMb();
            if (gpuFloorMb > 0 && bestAvailableMb >= 0 && bestAvailableMb < gpuFloorMb) {
                reasons.add(String.format(Locale.ROOT,
                        "no GPU has %d MB free (best available %d MB)",
                        gpuFloorMb, bestAvailableMb));
            }
            double maxGpuFraction = admissionMaxGpuUsedFraction();
            double bestUsedFraction = snapshot.bestGpuUsedFraction();
            if (maxGpuFraction > 0 && bestUsedFraction >= maxGpuFraction) {
                reasons.add(String.format(Locale.ROOT,
                        "all GPUs are at least %.1f%% used (admission limit %.1f%%)",
                        bestUsedFraction * 100.0, maxGpuFraction * 100.0));
            }
        }
        return reasons.isEmpty() ? null : String.join("; ", reasons);
    }

    private boolean capacityThresholdsEnabled() {
        return admissionMinAvailableRamMb() > 0
                || admissionMaxRamUsedFraction() > 0
                || admissionMinAvailableGpuMb() > 0
                || admissionMaxGpuUsedFraction() > 0;
    }

    private CapacityAdmission completeAdmission(boolean admitted,
                                                String outcome,
                                                String reason,
                                                boolean waited,
                                                long waitedMs,
                                                int samples,
                                                HardwareCapacitySnapshot capacity) {
        switch (outcome) {
            case "ADMITTED" -> admittedCount.incrementAndGet();
            case "REJECTED" -> rejectedCount.incrementAndGet();
            case "TIMED_OUT" -> timedOutCount.incrementAndGet();
            default -> {
                // Disabled admission is intentionally not counted as a capacity decision.
            }
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("outcome", outcome);
        event.put("waited", waited);
        event.put("waitedMs", waitedMs);
        event.put("samples", samples);
        event.put("at", Instant.now().toString());
        if (reason != null) event.put("reason", reason);
        lastAdmissionEvent = Map.copyOf(event);
        return new CapacityAdmission(admitted, outcome, reason, waited, waitedMs, samples, capacity);
    }

    private static void notifyWaitObserver(Consumer<String> observer, String reason) {
        if (observer == null) return;
        try {
            observer.accept(reason);
        } catch (RuntimeException ignored) {
            // Observability must not change admission behavior.
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
    }

    // ── Enforcement ───────────────────────────────────────────────────────────

    /** Manual kill from the MCP tool. Returns false when the process is already dead. */
    public boolean kill(String id, String reason) {
        TrackedSubprocess process = id == null ? null : tracked.get(id);
        if (process == null) {
            return false;
        }
        return forceDestroy(process,
                reason == null || reason.isBlank() ? "manual kill" : reason);
    }

    /** One pass over tracked children. Returns the number of children killed this pass. */
    public synchronized int checkAll() {
        if (!enabled()) {
            return 0;
        }
        long limitMb = effectiveLimitMb();
        if (limitMb <= 0) {
            return 0;
        }
        int killed = 0;
        for (TrackedSubprocess process : tracked.values()) {
            if (!process.alive()) {
                tracked.remove(process.id(), process);
                continue;
            }
            long uptimeSeconds = Duration.between(process.startedAt, Instant.now()).getSeconds();
            if (uptimeSeconds < graceSeconds()) {
                continue; // startup spikes (model load, JIT, mmap) are legitimate
            }
            long rssMb = process.rssMb();
            if (rssMb <= 0) {
                continue; // /proc read failed or platform unsupported
            }
            if (rssMb > limitMb) {
                int breaches = process.consecutiveBreaches.incrementAndGet();
                if (breaches >= breachCount()) {
                    String reason = String.format(
                            "RSS %d MB exceeded watchdog limit %d MB on %d consecutive check(s)",
                            rssMb, limitMb, breaches);
                    if (forceDestroy(process, reason)) {
                        killed++;
                    }
                } else {
                    process.lastEvent = String.format(
                            "RSS breach %d/%d: %d MB > %d MB limit",
                            breaches, breachCount(), rssMb, limitMb);
                }
            } else {
                process.consecutiveBreaches.set(0);
            }
        }
        return killed;
    }

    private boolean forceDestroy(TrackedSubprocess process, String reason) {
        process.lastEvent = "KILLED: " + reason;
        killedCount.incrementAndGet();
        System.err.println("[local-subprocess-watchdog] Killing pid=" + process.pid()
                + " id=" + process.id() + " type=" + process.type() + ": " + reason);
        boolean destroyed = false;
        if (process.handle != null) {
            process.handle.destroy();
            try {
                process.handle.onExit().get(10, TimeUnit.SECONDS);
                destroyed = true;
            } catch (Exception ignored) {
                // fall through to the forcible path
            }
            if (process.handle.isAlive()) {
                process.handle.destroyForcibly();
                destroyed = true;
            }
        }
        tracked.remove(process.id(), process);
        return destroyed;
    }

    // ── Scheduler ─────────────────────────────────────────────────────────────

    private synchronized void maybeStart() {
        if (!started && enabled()) {
            startScheduler();
        }
    }

    private synchronized void startScheduler() {
        if (started) {
            return;
        }
        started = true;
        long interval = intervalMs();
        scheduledTask = executor.scheduleAtFixedRate(
                this::runCheck, interval, interval, TimeUnit.MILLISECONDS);
    }

    private synchronized void restartSchedulerIfNeeded() {
        boolean shouldRun = enabled();
        if (shouldRun && !started) {
            startScheduler();
        } else if (!shouldRun && started && scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
            started = false;
        } else if (shouldRun && started && scheduledTask != null) {
            // Interval changed: reschedule.
            scheduledTask.cancel(false);
            long interval = intervalMs();
            scheduledTask = executor.scheduleAtFixedRate(
                    this::runCheck, interval, interval, TimeUnit.MILLISECONDS);
        }
    }

    private void runCheck() {
        try {
            checkAll();
        } catch (Throwable t) {
            System.err.println("[local-subprocess-watchdog] check failed (non-fatal): "
                    + t.getMessage());
        }
    }

    // ── Status snapshot (MCP tool payload) ────────────────────────────────────

    public Map<String, Object> statusMap() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("backend", "project-local");
        status.put("name", "local-subprocess-watchdog");
        status.put("config", configMap());
        List<Map<String, Object>> processes = new ArrayList<>();
        int alive = 0;
        for (TrackedSubprocess process : list()) {
            Map<String, Object> map = process.toMap();
            processes.add(map);
            if (Boolean.TRUE.equals(map.get("alive"))) {
                alive++;
            }
        }
        status.put("trackedProcesses", processes);
        status.put("trackedCount", processes.size());
        status.put("aliveCount", alive);
        status.put("killedCount", killedCount.get());
        Map<String, Object> admission = new LinkedHashMap<>();
        admission.put("waiting", waitingAdmissions.get());
        admission.put("admittedCount", admittedCount.get());
        admission.put("waitedCount", waitedCount.get());
        admission.put("rejectedCount", rejectedCount.get());
        admission.put("timedOutCount", timedOutCount.get());
        if (!lastAdmissionEvent.isEmpty()) admission.put("lastEvent", lastAdmissionEvent);
        HardwareCapacitySnapshot capacity = lastCapacitySnapshot;
        if (capacity != null) admission.put("lastCapacity", capacity.toMap());
        status.put("capacityAdmission", admission);
        status.put("childSelfProtection", "serving/learning children run their own in-JVM "
                + "SubprocessMemoryWatchdog on heap/GPU/off-heap thresholds; this parent "
                + "watchdog gates new local crawls on host/GPU capacity and enforces total RSS "
                + "from outside the child.");
        return status;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Resident-set size in MB from {@code /proc/<pid>/status}; 0 when unavailable. */
    static long readRssMb(long pid) {
        if (!isLinux()) {
            return 0L;
        }
        try {
            Path statusPath = Path.of("/proc", Long.toString(pid), "status");
            if (!Files.isReadable(statusPath)) {
                return 0L;
            }
            for (String line : Files.readAllLines(statusPath)) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]) / 1024L; // kB → MB
                    }
                }
            }
        } catch (Exception ignored) {
            // Process exited between listing and read, or permission denied.
        }
        return 0L;
    }

    static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    static long systemTotalRamMb() {
        try {
            long bytes = ((OperatingSystemMXBean)
                    ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
            return bytes > 0 ? bytes / (1024L * 1024L) : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    static long systemAvailableRamMb() {
        if (isLinux()) {
            try {
                Path meminfo = Path.of("/proc/meminfo");
                if (Files.isReadable(meminfo)) {
                    for (String line : Files.readAllLines(meminfo)) {
                        if (line.startsWith("MemAvailable:")) {
                            String[] parts = line.trim().split("\\s+");
                            if (parts.length >= 2) {
                                return Long.parseLong(parts[1]) / 1024L;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // Fall through to the MXBean's less precise MemFree-style value.
            }
        }
        try {
            long bytes = ((OperatingSystemMXBean)
                    ManagementFactory.getOperatingSystemMXBean()).getFreeMemorySize();
            return bytes >= 0 ? bytes / (1024L * 1024L) : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private HardwareCapacitySnapshot probeHardwareCapacity() throws InterruptedException {
        long totalRamMb = systemTotalRamMb();
        long availableRamMb = systemAvailableRamMb();
        double ramUsedFraction = totalRamMb > 0 && availableRamMb >= 0
                ? Math.max(0.0, Math.min(1.0,
                (double) (totalRamMb - availableRamMb) / totalRamMb))
                : -1.0;
        GpuProbeResult gpu = admissionMinAvailableGpuMb() > 0
                || admissionMaxGpuUsedFraction() > 0
                ? probeNvidiaGpuMemory()
                : new GpuProbeResult(List.of(), "disabled");
        return new HardwareCapacitySnapshot(totalRamMb, availableRamMb, ramUsedFraction,
                gpu.gpus(), gpu.source(), Instant.now());
    }

    private static GpuProbeResult probeNvidiaGpuMemory() throws InterruptedException {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "nvidia-smi",
                    "--query-gpu=index,memory.used,memory.total",
                    "--format=csv,noheader,nounits")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(NVIDIA_SMI_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new GpuProbeResult(List.of(), "nvidia-smi-timeout");
            }
            if (process.exitValue() != 0) {
                return new GpuProbeResult(List.of(), "nvidia-smi-exit-" + process.exitValue());
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            List<GpuCapacity> gpus = parseNvidiaGpuMemory(output);
            return new GpuProbeResult(gpus, gpus.isEmpty() ? "nvidia-smi-no-devices" : "nvidia-smi");
        } catch (IOException ignored) {
            return new GpuProbeResult(List.of(), "nvidia-smi-unavailable");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    static List<GpuCapacity> parseNvidiaGpuMemory(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<GpuCapacity> result = new ArrayList<>();
        for (String line : csv.split("\\R")) {
            String[] parts = line.trim().split(",\\s*");
            if (parts.length != 3) continue;
            try {
                int index = Integer.parseInt(parts[0].trim());
                long usedMb = Long.parseLong(parts[1].trim());
                long totalMb = Long.parseLong(parts[2].trim());
                if (index >= 0 && usedMb >= 0 && totalMb > 0) {
                    result.add(new GpuCapacity(index, usedMb, totalMb));
                }
            } catch (NumberFormatException ignored) {
                // Skip malformed or unsupported rows; the remaining devices are still useful.
            }
        }
        return List.copyOf(result);
    }

    private static long positiveLong(String key, long fallback) {
        long value = longProperty(key, fallback);
        return value > 0 ? value : fallback;
    }

    private static long longProperty(String key, long fallback) {
        try {
            return parseLong(System.getProperty(key, Long.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double doubleProperty(String key, double fallback) {
        try {
            return parseDouble(System.getProperty(key, Double.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double fractionProperty(String key, double fallback) {
        try {
            return parseFraction(System.getProperty(key, Double.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new NumberFormatException("blank");
        }
        return Long.parseLong(raw.trim());
    }

    private static double parseDouble(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new NumberFormatException("blank");
        }
        return Double.parseDouble(raw.trim());
    }

    private static long parseNonNegativeLong(String raw) {
        long value = parseLong(raw);
        if (value < 0) throw new NumberFormatException("negative");
        return value;
    }

    private static long parsePositiveLong(String raw) {
        long value = parseLong(raw);
        if (value <= 0) throw new NumberFormatException("not positive");
        return value;
    }

    private static double parseFraction(String raw) {
        double value = parseDouble(raw);
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new NumberFormatException("fraction outside [0,1]");
        }
        return value;
    }

    private static String normalizeAdmissionMode(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_');
        return switch (normalized) {
            case "wait", "block" -> "wait";
            case "fail", "fail_fast", "failfast" -> "fail";
            case "off", "disabled", "disable" -> "off";
            default -> throw new IllegalArgumentException(
                    "Invalid admissionMode '" + raw + "' (expected wait|fail|off)");
        };
    }

    @SuppressWarnings("unchecked")
    private static void setIfPresent(Map<String, Object> updates, String key, String property,
                                     Function<String, ?> parser) {
        if (!updates.containsKey(key)) {
            return;
        }
        Object value = updates.get(key);
        if (value == null) {
            System.clearProperty(property);
            return;
        }
        String raw = value instanceof Map || value instanceof List
                ? null : String.valueOf(value);
        if (raw == null || raw.isBlank()) {
            System.clearProperty(property);
            return;
        }
        try {
            Object parsed = parser.apply(raw);
            System.setProperty(property, String.valueOf(parsed));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid value for " + key + ": '" + raw + "'", e);
        }
    }
}
